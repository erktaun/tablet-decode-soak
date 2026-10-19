package com.ald.soak

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Reads the frame-number barcode that make_clips.py burns into the top strip of every clip:
 * 18 equal cells across the width, 1/20 of the height tall. Cell 0 is always white, cell 17
 * always black (guards); cells 1..16 hold the frame index within the clip, LSB first.
 */
object FrameStamp {
    private const val CELLS = 18

    /** Frame index, or -1 when the guards don't match (corrupt frame or foreign stream). */
    fun read(y: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, w: Int, h: Int): Int {
        val row = top + h / 40
        val cellW = w.toDouble() / CELLS
        fun luma(cell: Int): Int {
            val cx = left + ((cell + 0.5) * cellW).toInt()
            var sum = 0
            for (dx in -2..2) sum += y.get(row * rowStride + (cx + dx) * pixelStride).toInt() and 0xff
            return sum / 5
        }
        if (luma(0) < 160 || luma(CELLS - 1) > 96) return -1
        var idx = 0
        for (bit in 0 until 16) if (luma(bit + 1) >= 128) idx = idx or (1 shl bit)
        return idx
    }
}

/**
 * Stand-in for model preprocessing: YUV frame -> letterboxed size x size RGB float tensor (CHW,
 * 0..1), nearest-neighbour. It runs at the inference rate, so the soak test carries the same
 * CPU-side frame work a detector would need, minus the detector itself.
 */
class Preprocessor(private val size: Int) {
    private companion object { const val INV255 = 1f / 255f }

    val tensor = FloatArray(3 * size * size)
    private val plane = size * size
    private var srcW = 0
    private var srcH = 0
    private var xs = IntArray(0)
    private var ys = IntArray(0)
    private var outW = 0
    private var outH = 0
    private var padX = 0
    private var padY = 0

    private fun layout(w: Int, h: Int) {
        if (w == srcW && h == srcH) return
        srcW = w; srcH = h
        val scale = min(size.toDouble() / w, size.toDouble() / h)
        outW = (w * scale).toInt(); outH = (h * scale).toInt()
        padX = (size - outW) / 2; padY = (size - outH) / 2
        xs = IntArray(outW) { min(w - 1, (it / scale).toInt()) }
        ys = IntArray(outH) { min(h - 1, (it / scale).toInt()) }
    }

    fun run(img: Image) {
        val crop = img.cropRect
        layout(crop.width(), crop.height())
        tensor.fill(114f / 255f)
        val (yp, up, vp) = img.planes
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val yRs = yp.rowStride; val yPs = yp.pixelStride
        val uRs = up.rowStride; val uPs = up.pixelStride
        val vRs = vp.rowStride; val vPs = vp.pixelStride
        for (oy in 0 until outH) {
            val sy = crop.top + ys[oy]
            val yRow = sy * yRs
            val uRow = (sy / 2) * uRs
            val vRow = (sy / 2) * vRs
            var o = (oy + padY) * size + padX
            for (ox in 0 until outW) {
                val sx = crop.left + xs[ox]
                val yy = yb.get(yRow + sx * yPs).toInt() and 0xff
                val u = (ub.get(uRow + (sx / 2) * uPs).toInt() and 0xff) - 128
                val v = (vb.get(vRow + (sx / 2) * vPs).toInt() and 0xff) - 128
                tensor[o] = clamp(yy + 1.402f * v)
                tensor[plane + o] = clamp(yy - 0.344f * u - 0.714f * v)
                tensor[2 * plane + o] = clamp(yy + 1.772f * u)
                o++
            }
        }
    }

    /** Same tensor from a letterboxed RGBA image already scaled on the GPU (GlSink). */
    fun fromRgba(rgba: ByteBuffer) {
        if (bytes.size != 4 * plane) bytes = ByteArray(4 * plane)
        rgba.position(0)
        rgba.get(bytes)
        rgba.position(0)
        for (i in 0 until plane) {
            tensor[i] = (bytes[4 * i].toInt() and 0xff) * INV255
            tensor[plane + i] = (bytes[4 * i + 1].toInt() and 0xff) * INV255
            tensor[2 * plane + i] = (bytes[4 * i + 2].toInt() and 0xff) * INV255
        }
    }

    private var bytes = ByteArray(0)

    private fun clamp(x: Float): Float = (if (x < 0f) 0f else if (x > 255f) 255f else x) / 255f

    /** Small preview of what the "model" would see; built at ~1 Hz for the UI only. */
    fun bitmap(): Bitmap {
        val px = IntArray(plane) { i ->
            val r = (tensor[i] * 255).toInt()
            val g = (tensor[plane + i] * 255).toInt()
            val b = (tensor[2 * plane + i] * 255).toInt()
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
