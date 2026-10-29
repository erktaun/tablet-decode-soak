package com.ald.ra.engine

/** One detection from the model: class name, box, confidence. */
data class Det(val cls: String, val box: Box, val conf: Double)

/** A confirmed track seen in the current frame. `box` is the detection itself, not the Kalman estimate. */
data class TrackOut(val id: Int, val box: Box, val conf: Double, val ageS: Double)

/**
 * ByteTrack: two-stage IoU matching (high-confidence detections first, then low-confidence ones only to keep
 * existing tracks alive) against Kalman-predicted boxes. Port of src/ingtrack/track.py plus the constant-velocity
 * Kalman filter from the ByteTrack paper, so boxes that are carried still overlap their prediction at 5-10 fps.
 * Matching is greedy by IoU (Hungarian makes no difference at a handful of objects), plus a centre-distance pass for
 * fast-moving boxes. One instance per camera and class.
 */
class ByteTrack(private val p: Params, private val fps: Double) {
    private class T(var kf: Kalman, var box: Box, var conf: Double, var hits: Int, val first: Double, var last: Double) {
        var id: Int? = null
    }

    private val tracks = mutableListOf<T>()
    private var nextId = 1
    private var lastT: Double? = null

    fun update(dets: List<Det>, t: Double): List<TrackOut> {
        val steps = lastT?.let { ((t - it) * fps).coerceAtLeast(0.0) } ?: 0.0
        lastT = t
        for (tr in tracks) tr.kf.predict(steps)
        val hi = dets.filter { it.conf >= p.trackHigh }
        val lo = dets.filter { it.conf >= p.trackLow && it.conf < p.trackHigh }
        val (pairs, restT, restD) = match(tracks, hi)
        for ((ti, di) in pairs) hit(tracks[ti], hi[di], t)
        val left = restT.map { tracks[it] }
        val (pairs2, restT2, _) = match(left, lo)
        for ((ti, di) in pairs2) hit(left[ti], lo[di], t)
        // Third pass: a box carried faster than about half its width per frame doesn't overlap its prediction until
        // the Kalman filter has learned its speed, so match leftovers by centre distance (within maxJump box sizes).
        val left2 = restT2.map { left[it] }
        val hiLeft = restD.map { hi[it] }
        val (pairs3, _, restD3) = matchByDistance(left2, hiLeft)
        for ((ti, di) in pairs3) hit(left2[ti], hiLeft[di], t)
        for (di in restD3) tracks += T(Kalman(hiLeft[di].box), hiLeft[di].box, hiLeft[di].conf, 1, t, t)
        tracks.removeAll { t - it.last > p.trackMaxLostS }
        val out = mutableListOf<TrackOut>()
        for (tr in tracks) {
            if (tr.id == null && tr.hits >= p.trackMinHits) tr.id = nextId++
            if (tr.id != null && tr.last == t) out += TrackOut(tr.id!!, tr.box, tr.conf, t - tr.first)
        }
        return out
    }

    private fun match(ts: List<T>, ds: List<Det>): Triple<List<Pair<Int, Int>>, List<Int>, List<Int>> {
        val cand = mutableListOf<Triple<Int, Int, Double>>()
        for (i in ts.indices) {
            val pred = ts[i].kf.box()
            for (j in ds.indices) {
                val v = iou(pred, ds[j].box)
                if (v >= p.trackMatchIou) cand += Triple(i, j, v)
            }
        }
        cand.sortByDescending { it.third }
        val usedT = mutableSetOf<Int>(); val usedD = mutableSetOf<Int>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for ((i, j, _) in cand) if (i !in usedT && j !in usedD) { pairs += i to j; usedT += i; usedD += j }
        return Triple(pairs, ts.indices.filter { it !in usedT }, ds.indices.filter { it !in usedD })
    }

    private fun matchByDistance(ts: List<T>, ds: List<Det>): Triple<List<Pair<Int, Int>>, List<Int>, List<Int>> {
        val cand = mutableListOf<Triple<Int, Int, Double>>()
        for (i in ts.indices) {
            val pred = ts[i].kf.box()
            for (j in ds.indices) {
                val b = ds[j].box
                val dist = kotlin.math.hypot(pred.cx - b.cx, pred.cy - b.cy) / maxOf(pred.size, b.size, 1.0)
                val sizeRatio = b.area / maxOf(pred.area, 1e-6)
                if (dist <= p.trackMaxJump && sizeRatio in 0.5..2.0) cand += Triple(i, j, dist)
            }
        }
        cand.sortBy { it.third }
        val usedT = mutableSetOf<Int>(); val usedD = mutableSetOf<Int>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for ((i, j, _) in cand) if (i !in usedT && j !in usedD) { pairs += i to j; usedT += i; usedD += j }
        return Triple(pairs, ts.indices.filter { it !in usedT }, ds.indices.filter { it !in usedD })
    }

    private fun hit(tr: T, d: Det, t: Double) {
        tr.kf.update(d.box); tr.box = d.box; tr.conf = d.conf; tr.last = t; tr.hits++
    }
}

/**
 * Constant-velocity Kalman filter on (cx, cy, aspect, height), as in ByteTrack/DeepSORT. Noise scales with the
 * box height. `predict(steps)` takes the elapsed time in frames, so uneven frame gaps are handled.
 */
class Kalman(b: Box) {
    private val x = DoubleArray(8)
    private val P = Array(8) { DoubleArray(8) }

    init {
        val m = meas(b)
        for (i in 0 until 4) x[i] = m[i]
        val h = m[3]
        val std = doubleArrayOf(2 * WP * h, 2 * WP * h, 1e-2, 2 * WP * h, 10 * WV * h, 10 * WV * h, 1e-5, 10 * WV * h)
        for (i in 0 until 8) P[i][i] = std[i] * std[i]
    }

    fun predict(steps: Double) {
        if (steps <= 0) return
        val h = x[3]
        for (i in 0 until 4) x[i] += steps * x[i + 4]
        // P = F P F^T + Q, with F = [[I, dt I], [0, I]]
        val f = Array(8) { i -> DoubleArray(8) { j -> if (i == j) 1.0 else if (j == i + 4) steps else 0.0 } }
        val fp = mul(f, P)
        val fpf = mul(fp, transpose(f))
        val q = doubleArrayOf(WP * h, WP * h, 1e-2, WP * h, WV * h, WV * h, 1e-5, WV * h)
        for (i in 0 until 8) for (j in 0 until 8) P[i][j] = fpf[i][j] + if (i == j) q[i] * q[i] * steps else 0.0
    }

    fun update(b: Box) {
        val z = meas(b)
        val h = x[3]
        val r = doubleArrayOf(WP * h, WP * h, 1e-1, WP * h)
        // S = H P H^T + R (H picks the first 4 states), K = P H^T S^-1
        val s = Array(4) { i -> DoubleArray(4) { j -> P[i][j] + if (i == j) r[i] * r[i] else 0.0 } }
        val sInv = inv4(s)
        val k = Array(8) { i -> DoubleArray(4) { j -> (0 until 4).sumOf { m -> P[i][m] * sInv[m][j] } } }
        val y = DoubleArray(4) { z[it] - x[it] }
        for (i in 0 until 8) x[i] += (0 until 4).sumOf { k[i][it] * y[it] }
        // P = (I - K H) P
        val newP = Array(8) { i -> DoubleArray(8) { j -> P[i][j] - (0 until 4).sumOf { m -> k[i][m] * P[m][j] } } }
        for (i in 0 until 8) newP[i].copyInto(P[i])
    }

    fun box(): Box {
        val (cx, cy, a, h) = listOf(x[0], x[1], x[2], x[3])
        val w = a * h
        return Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun meas(b: Box) = doubleArrayOf(b.cx, b.cy, b.w / b.h.coerceAtLeast(1e-6), b.h)

    companion object {
        const val WP = 1.0 / 20
        const val WV = 1.0 / 160

        private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(a.size) { i -> DoubleArray(b[0].size) { j -> a[i].indices.sumOf { m -> a[i][m] * b[m][j] } } }

        private fun transpose(a: Array<DoubleArray>) = Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }

        /** Gauss-Jordan inverse of a 4x4 (S is symmetric positive definite, so no pivoting issues in practice). */
        private fun inv4(m: Array<DoubleArray>): Array<DoubleArray> {
            val a = Array(4) { i -> DoubleArray(8) { j -> if (j < 4) m[i][j] else if (j - 4 == i) 1.0 else 0.0 } }
            for (c in 0 until 4) {
                val piv = (c until 4).maxBy { kotlin.math.abs(a[it][c]) }
                val tmp = a[c]; a[c] = a[piv]; a[piv] = tmp
                val d = a[c][c]
                for (j in 0 until 8) a[c][j] /= d
                for (r in 0 until 4) if (r != c) {
                    val f = a[r][c]
                    for (j in 0 until 8) a[r][j] -= f * a[c][j]
                }
            }
            return Array(4) { i -> DoubleArray(4) { j -> a[i][j + 4] } }
        }
    }
}
