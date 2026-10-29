package com.ald.ra.app

import android.graphics.Bitmap
import com.ald.ra.engine.Box
import com.ald.ra.engine.Det
import com.ald.ra.engine.Role
import com.ald.ra.engine.Site
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Order codes (QR / barcodes printed with the order id) read with ML Kit on the checkout cameras (spec §4.4), on
 * their own thread so the detector never waits. Regions: closed-container detections when the site has that class,
 * otherwise the checkout zones, grown by half their size. Small regions are enlarged first: a 60 px QR has about 2.5 px per module.
 * Codes found are handed to the engine with the next frame of that camera (Frame.codes).
 */
class CodeReader(private val site: Site, private val everyS: Double = 0.5, private val log: (String) -> Unit) {
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39).build())
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private val found = ConcurrentHashMap<String, ConcurrentLinkedQueue<Pair<Box, String>>>()
    private val lastT = mutableMapOf<String, Double>()
    private val checkout = site.zones.filter { it.role == Role.CHECKOUT }
    val cameras = checkout.map { it.camera }.toSet()
    val scans = AtomicInteger()
    val reads = AtomicInteger()
    @Volatile var last: String? = null

    /** Called on the engine thread with each analysed frame; starts a scan when this camera is due and none is running. */
    fun offer(cam: CamView, t: Double, dets: List<Det>, view: () -> Bitmap) {
        if (cam.name !in cameras || t - (lastT[cam.name] ?: Double.NEGATIVE_INFINITY) < everyS || !busy.compareAndSet(false, true)) return
        lastT[cam.name] = t
        val boxes = site.classes.closedContainer.takeIf { it.isNotEmpty() }?.let { k -> dets.filter { it.cls in k }.map { grow(it.box, 0.15) } }
            ?: checkout.filter { it.camera == cam.name }.map { z ->
                val pts = z.poly ?: listOf(listOf(0.0, 0.0), listOf(cam.w, cam.h))
                // A box (and its label) often sticks out of the zone: pizza_qr's QR started 10 px left of the zone, and
                // the zone crop cut its finder pattern off. Half the zone's size on each side.
                grow(Box(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] }), 0.5)
            }
        if (boxes.isEmpty()) { busy.set(false); return }
        val bm = view()
        exec.execute {
            try { boxes.forEach { scan(cam, bm, it, t) } } catch (e: Exception) { log("code_error $e") } finally { busy.set(false) }
        }
    }

    /** Codes read since the last call for this camera, in camera pixels. */
    fun take(cam: String): List<Pair<Box, String>> {
        val q = found[cam] ?: return emptyList()
        return generateSequence { q.poll() }.toList()
    }

    private fun scan(cam: CamView, view: Bitmap, b: Box, t: Double) {
        val s = cam.viewScale
        val x0 = maxOf(0, (b.x0 * s).toInt()); val y0 = maxOf(0, (b.y0 * s).toInt())
        val x1 = minOf(view.width, (b.x1 * s).toInt()); val y1 = minOf(view.height, (b.y1 * s).toInt())
        if (x1 - x0 < 8 || y1 - y0 < 8) return
        var crop = Bitmap.createBitmap(view, x0, y0, x1 - x0, y1 - y0)
        val up = maxOf(1.0, 480.0 / minOf(crop.width, crop.height)).coerceAtMost(4.0)
        if (up > 1.0) crop = Bitmap.createScaledBitmap(crop, (crop.width * up).toInt(), (crop.height * up).toInt(), true)
        scans.incrementAndGet()
        for (c in Tasks.await(scanner.process(InputImage.fromBitmap(crop, 0)))) {
            val v = c.rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val r = c.boundingBox ?: continue
            val k = 1.0 / (up * s)
            val box = Box(x0 / s + r.left * k, y0 / s + r.top * k, x0 / s + r.right * k, y0 / s + r.bottom * k)
            found.getOrPut(cam.name) { ConcurrentLinkedQueue() }.add(box to v)
            reads.incrementAndGet()
            if (last != v) log("code %s at t=%.1f on %s (%.0f px)".format(v, t, cam.name, box.w))
            last = v
        }
    }

    private fun grow(b: Box, f: Double) = Box(b.x0 - f * b.w, b.y0 - f * b.h, b.x1 + f * b.w, b.y1 + f * b.h)

    fun close() { exec.shutdownNow(); scanner.close() }
}
