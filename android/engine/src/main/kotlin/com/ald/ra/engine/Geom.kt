package com.ald.ra.engine

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box, xyxy pixels. */
data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    val cx get() = (x0 + x1) / 2
    val cy get() = (y0 + y1) / 2
    val area get() = max(0.0, w) * max(0.0, h)
    val size get() = max(w, h)
    fun toList() = listOf(x0, y0, x1, y1).map { Math.round(it * 10) / 10.0 }

    companion object {
        fun of(l: List<Double>) = Box(l[0], l[1], l[2], l[3])
    }
}

fun iou(a: Box, b: Box): Double {
    val w = max(0.0, min(a.x1, b.x1) - max(a.x0, b.x0))
    val h = max(0.0, min(a.y1, b.y1) - max(a.y0, b.y0))
    val inter = w * h
    return inter / (a.area + b.area - inter + 1e-9)
}

/** Polygon in image pixels; `null` points means "whole view" (contains everything). */
class Poly(points: List<List<Double>>?) {
    private val xs = points?.map { it[0] }?.toDoubleArray()
    private val ys = points?.map { it[1] }?.toDoubleArray()
    val whole get() = xs == null

    /** Even-odd ray casting; points on the edge may fall either way, which doesn't matter at pixel scale. */
    fun contains(x: Double, y: Double): Boolean {
        val xs = xs ?: return true
        val ys = ys!!
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
            j = i
        }
        return inside
    }

    fun contains(b: Box) = contains(b.cx, b.cy)

    fun centroid(w: Double, h: Double): Pair<Double, Double> =
        if (xs == null) w / 2 to h / 2 else xs.average() to ys!!.average()
}

/**
 * Plane-to-plane mapping (homography) from 4+ point pairs, e.g. image pixels of a counter -> floor-plan metres
 * (spec §4.3). Direct linear transform with h33 = 1, solved by least squares, so it covers one flat surface only.
 */
class Homography(src: List<List<Double>>, dst: List<List<Double>>) {
    private val h: DoubleArray

    init {
        require(src.size >= 4 && src.size == dst.size) { "homography needs 4+ matching points" }
        val a = mutableListOf<DoubleArray>(); val b = mutableListOf<Double>()
        for (i in src.indices) {
            val (x, y) = src[i][0] to src[i][1]; val (u, v) = dst[i][0] to dst[i][1]
            a += doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); b += u
            a += doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); b += v
        }
        // Normal equations (A^T A) h = A^T b, solved by Gaussian elimination with partial pivoting.
        val n = 8
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a.indices.sumOf { a[it][i] * a[it][j] } else a.indices.sumOf { a[it][i] * b[it] } } }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { kotlin.math.abs(m[it][c]) }
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            require(kotlin.math.abs(m[c][c]) > 1e-12) { "map points are degenerate (3 on a line?)" }
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (j in c..n) m[r][j] -= f * m[c][j]
            }
        }
        h = DoubleArray(9) { if (it < 8) m[it][n] / m[it][it] else 1.0 }
    }

    fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = h[6] * x + h[7] * y + h[8]
        return (h[0] * x + h[1] * y + h[2]) / w to (h[3] * x + h[4] * y + h[5]) / w
    }
}
