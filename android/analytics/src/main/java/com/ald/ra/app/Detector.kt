package com.ald.ra.app

import com.ald.ra.engine.Box
import com.ald.ra.engine.Det
import com.ald.ra.engine.iou
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The site's detector (YOLOE with the site's classes baked in, tools/make_site_model.py): input NCHW float32 RGB
 * 0-1, letterboxed; output [1, 4 + nc + 32, N] with xywh normalised to the input square, class scores, mask
 * coefficients (ignored). Runs on the GPU delegate when the device supports it, else 4 CPU threads.
 * Not thread-safe: one thread calls [detect].
 */
class Detector(model: File, private val classes: List<String>, val size: Int, gpu: Boolean = true,
               private val conf: Float = 0.2f, private val nmsIou: Double = 0.7) {
    private var delegate: GpuDelegate? = null
    private val interp: Interpreter
    val backend: String
    private val input = GlFrames.direct(4 * 3 * size * size)
    private val outIndex: Int
    private val rows: Int
    private val n: Int
    private val out: ByteBuffer

    init {
        val buf = FileInputStream(model).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        val opts = Interpreter.Options()
        var be = "cpu4"
        if (gpu && runCatching { CompatibilityList().isDelegateSupportedOnThisDevice }.getOrDefault(false)) {
            delegate = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true))
            opts.addDelegate(delegate); be = "gpu"
        } else opts.setNumThreads(4)
        interp = try { Interpreter(buf, opts) } catch (e: Exception) {
            // GPU delegate refused the graph: fall back to the CPU rather than not running at all.
            delegate?.close(); delegate = null; be = "cpu4 (gpu failed: ${e.message?.take(120)})"
            Interpreter(buf, Interpreter.Options().setNumThreads(4))
        }
        backend = be
        val shape = interp.getInputTensor(0).shape()
        check(shape.contentEquals(intArrayOf(1, 3, size, size))) { "model input ${shape.toList()}, expected [1, 3, $size, $size]" }
        outIndex = (0 until interp.outputTensorCount).first { interp.getOutputTensor(it).shape().size == 3 }
        val os = interp.getOutputTensor(outIndex).shape()
        rows = os[1]; n = os[2]
        check(rows >= 4 + classes.size) { "model has ${rows - 4} score rows, site pack lists ${classes.size} classes" }
        out = GlFrames.direct(4 * rows * n)
    }

    /**
     * Detect on a letterboxed RGBA image (size x size, rows top-down). `lb` says where the region of interest sits
     * in it; `roi` is that region in camera pixels. Returns boxes in camera pixels.
     */
    fun detect(rgba: ByteBuffer, lb: Letterbox, roi: Box): List<Det> {
        toTensor(rgba)
        out.position(0)
        val outs = HashMap<Int, Any>()
        outs[outIndex] = out
        interp.runForMultipleInputsOutputs(arrayOf<Any>(input), outs)
        out.position(0)
        val f = out.asFloatBuffer()
        val nc = classes.size
        val cand = ArrayList<Det>()
        for (i in 0 until n) {
            var best = 0f; var k = -1
            for (c in 0 until nc) { val s = f.get((4 + c) * n + i); if (s > best) { best = s; k = c } }
            if (k < 0 || best < conf) continue
            val cx = f.get(i) * size; val cy = f.get(n + i) * size; val w = f.get(2 * n + i) * size; val h = f.get(3 * n + i) * size
            fun mx(x: Float) = roi.x0 + ((x - lb.x) / lb.scale).coerceIn(0.0, roi.w)
            fun my(y: Float) = roi.y0 + ((y - lb.y) / lb.scale).coerceIn(0.0, roi.h)
            cand += Det(classes[k], Box(mx(cx - w / 2), my(cy - h / 2), mx(cx + w / 2), my(cy + h / 2)), best.toDouble())
        }
        return nms(cand)
    }

    /** Greedy NMS per class, highest score first, at most 300 boxes (ultralytics' defaults). */
    private fun nms(ds: List<Det>): List<Det> {
        val keep = ArrayList<Det>()
        for ((_, group) in ds.groupBy { it.cls }) {
            val sorted = group.sortedByDescending { it.conf }
            val kept = ArrayList<Det>()
            for (d in sorted) if (kept.none { iou(it.box, d.box) > nmsIou }) kept += d
            keep += kept
        }
        return keep.sortedByDescending { it.conf }.take(300)
    }

    private val plane = size * size
    private val px = ByteArray(4 * plane)
    private val r = FloatArray(plane)
    private val g = FloatArray(plane)
    private val b = FloatArray(plane)

    private fun toTensor(rgba: ByteBuffer) {
        val fb = input.asFloatBuffer()
        rgba.position(0)
        rgba.get(px)
        rgba.position(0)
        for (i in 0 until plane) {
            r[i] = (px[4 * i].toInt() and 0xff) * INV255
            g[i] = (px[4 * i + 1].toInt() and 0xff) * INV255
            b[i] = (px[4 * i + 2].toInt() and 0xff) * INV255
        }
        fb.put(r); fb.put(g); fb.put(b)
        input.position(0)
    }

    fun close() { interp.close(); delegate?.close() }

    private companion object { const val INV255 = 1f / 255f }
}
