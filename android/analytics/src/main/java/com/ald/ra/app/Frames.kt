package com.ald.ra.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.ald.ra.engine.Box
import com.ald.ra.engine.Poly
import com.ald.ra.engine.Zone
import com.ald.ra.engine.iou
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The camera view of a shot as a Bitmap (RGBA rows top-down, as read back from the GPU). */
fun viewBitmap(s: Shot): Bitmap = Bitmap.createBitmap(s.cam.viewW, s.cam.viewH, Bitmap.Config.ARGB_8888).apply {
    s.view.position(0); copyPixelsFromBuffer(s.view); s.view.position(0)
}

/**
 * Same crop as the laptop replay (cropJpeg) and src/ingtrack/decisions.py: other boxes (IoU < 0.5 with this one)
 * greyed out, padded, long side scaled to `side`, JPEG quality 90. Boxes are in camera pixels; `scale` maps them
 * to the view.
 */
fun cropJpeg(view: Bitmap, scale: Double, box: Box, side: Int, pad: Double, mask: List<Box>): ByteArray {
    val pp = pad * box.size
    val x0 = maxOf(0, ((box.x0 - pp) * scale).toInt()); val y0 = maxOf(0, ((box.y0 - pp) * scale).toInt())
    val x1 = minOf(view.width, ((box.x1 + pp) * scale).toInt()); val y1 = minOf(view.height, ((box.y1 + pp) * scale).toInt())
    val c = Bitmap.createBitmap(view, x0, y0, maxOf(1, x1 - x0), maxOf(1, y1 - y0)).copy(Bitmap.Config.ARGB_8888, true)
    val cv = Canvas(c)
    val grey = Paint().apply { color = 0xff808080.toInt() }
    for (m in mask) if (iou(m, box) < 0.5)
        cv.drawRect((m.x0 * scale - x0).toFloat(), (m.y0 * scale - y0).toFloat(), (m.x1 * scale - x0).toFloat(), (m.y1 * scale - y0).toFloat(), grey)
    val s = side.toDouble() / maxOf(c.width, c.height)
    val o = Bitmap.createScaledBitmap(c, maxOf(1, Math.round(c.width * s).toInt()), maxOf(1, Math.round(c.height * s).toInt()), true)
    return ByteArrayOutputStream().also { o.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/**
 * Zone occupancy (spec §4.1 fallback), as in src/ingtrack/scene_perception.py: per zone, the fraction of pixels whose
 * blurred grey value differs from the empty background by more than `diff`, after matching the zone's mean
 * brightness to the background's (undoes global light changes). Works on the camera view (view pixels).
 */
class Occupancy(private val cam: CamView, zones: List<Zone>, private val diff: Int = 30) {
    private inner class Z(val name: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val inside: BooleanArray) {
        var ref: FloatArray? = null
        var refMean = 0f
    }

    private val zs = zones.mapNotNull { z ->
        val poly = Poly(z.poly)
        val s = cam.viewScale
        val pts = z.poly ?: listOf(listOf(0.0, 0.0), listOf(cam.w, 0.0), listOf(cam.w, cam.h), listOf(0.0, cam.h))
        val x0 = maxOf(0, (pts.minOf { it[0] } * s).toInt()); val y0 = maxOf(0, (pts.minOf { it[1] } * s).toInt())
        val x1 = minOf(cam.viewW - 1, (pts.maxOf { it[0] } * s).toInt()); val y1 = minOf(cam.viewH - 1, (pts.maxOf { it[1] } * s).toInt())
        if (x1 <= x0 || y1 <= y0) return@mapNotNull null
        val w = x1 - x0 + 1
        val inside = BooleanArray(w * (y1 - y0 + 1)) { i ->
            val x = (x0 + i % w + 0.5) / s; val y = (y0 + i / w + 0.5) / s
            poly.contains(x, y)
        }
        Z(z.name, x0, y0, x1, y1, inside)
    }
    val hasBackground get() = zs.all { it.ref != null }

    /** Use this view (RGBA, view size) as the empty background. */
    fun setBackground(rgba: ByteBuffer) {
        for (z in zs) { val g = grey(rgba, z); z.ref = g; z.refMean = mean(g, z) }
    }

    fun measure(rgba: ByteBuffer): Map<String, Double> = zs.filter { it.ref != null }.associate { z ->
        val cur = grey(rgba, z)
        val k = z.refMean / maxOf(mean(cur, z), 1f)
        var n = 0; var hit = 0
        val ref = z.ref!!
        for (i in cur.indices) if (z.inside[i]) { n++; if (kotlin.math.abs(cur[i] * k - ref[i]) > diff) hit++ }
        z.name to (if (n == 0) 0.0 else Math.round(hit * 1000.0 / n) / 1000.0)
    }

    private fun mean(g: FloatArray, z: Z): Float {
        var s = 0.0; var n = 0
        for (i in g.indices) if (z.inside[i]) { s += g[i]; n++ }
        return if (n == 0) 0f else (s / n).toFloat()
    }

    /** Grey (0.299 R + 0.587 G + 0.114 B) of the zone's bounding box, blurred with OpenCV's 5x5 Gaussian ([1 4 6 4 1] / 16). */
    private fun grey(rgba: ByteBuffer, z: Z): FloatArray {
        val vw = cam.viewW; val vh = cam.viewH
        val w = z.x1 - z.x0 + 1; val h = z.y1 - z.y0 + 1
        // grey with a 2 px border (clamped at the image edge), then separable blur
        val bw = w + 4; val bh = h + 4
        val g = FloatArray(bw * bh)
        for (yy in 0 until bh) {
            val y = (z.y0 - 2 + yy).coerceIn(0, vh - 1)
            for (xx in 0 until bw) {
                val x = (z.x0 - 2 + xx).coerceIn(0, vw - 1)
                val o = 4 * (y * vw + x)
                g[yy * bw + xx] = 0.299f * (rgba.get(o).toInt() and 0xff) + 0.587f * (rgba.get(o + 1).toInt() and 0xff) +
                    0.114f * (rgba.get(o + 2).toInt() and 0xff)
            }
        }
        val k = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        val tmp = FloatArray(bw * h)
        for (yy in 0 until h) for (xx in 0 until bw) {
            var s = 0f
            for (j in 0..4) s += k[j] * g[(yy + j) * bw + xx]
            tmp[yy * bw + xx] = s
        }
        val out = FloatArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            var s = 0f
            for (j in 0..4) s += k[j] * tmp[yy * bw + xx + j]
            out[yy * w + xx] = s
        }
        return out
    }
}
