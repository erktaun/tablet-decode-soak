package com.ald.ra.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import com.ald.ra.engine.Box
import com.ald.ra.engine.CheckRunner
import com.ald.ra.engine.Checker
import com.ald.ra.engine.DecisionsChecker
import com.ald.ra.engine.DecisionsClient
import com.ald.ra.engine.Engine
import com.ald.ra.engine.Event
import com.ald.ra.engine.Frame
import com.ald.ra.engine.Params
import com.ald.ra.engine.Role
import com.ald.ra.engine.Site
import com.ald.ra.engine.json
import com.ald.ra.engine.loadMenu
import com.ald.ra.engine.loadOrders
import com.ald.ra.engine.loadSite
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One run, from `adb shell am start ... --es <key> <value>` or the start button. */
data class RunCfg(
    val site: String,
    val runId: String,
    val speed: Float,       // file sources: playback speed (1 = real time)
    val viewMax: Int,       // long side of the camera view read back for crops, occupancy and preview
    val gpu: Boolean,
    val params: String?,    // JSON merged over the site's params
    val saveDets: Boolean,  // write every frame's detections (detections.jsonl), for comparing with the laptop
) {
    companion object {
        fun from(extras: Bundle?): RunCfg {
            fun s(k: String) = extras?.getString(k)?.takeIf { it.isNotBlank() }
            val site = s("site") ?: "pizza_trailer"
            return RunCfg(site, s("run_id") ?: (SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + site),
                s("speed")?.toFloat() ?: 1f, s("view_max")?.toInt() ?: 1920, s("gpu") != "false", s("params"),
                s("save_dets") == "true")
        }
    }
}

/**
 * One analytics run: site pack -> streams -> detector -> engine -> events. Files in runs/<run_id>/:
 * events.jsonl (live, as events happen), events_final.jsonl (sorted, at the end), decision_calls.jsonl,
 * metrics.jsonl (every 5 s), log.txt, done.json (when finished), detections.jsonl (if asked).
 */
class Session(private val ctx: Context, val cfg: RunCfg) {
    val siteDir = File(ctx.getExternalFilesDir("sites"), cfg.site)
    val dir = File(ctx.getExternalFilesDir("runs"), cfg.runId).apply { mkdirs() }
    private val logFile = File(dir, "log.txt")
    private val eventsFile = File(dir, "events.jsonl")
    private val callsFile = File(dir, "decision_calls.jsonl")
    private val metricsFile = File(dir, "metrics.jsonl")
    private val detsFile = File(dir, "detections.jsonl")

    val site: Site
    private val engineThread = HandlerThread("engine").apply { start() }
    private val detThread = HandlerThread("detector").apply { start() }
    private val metricsThread = HandlerThread("metrics").apply { start() }
    private val engineH = Handler(engineThread.looper)
    private val detH = Handler(detThread.looper)
    private val metricsH = Handler(metricsThread.looper)
    private val checks = Executors.newFixedThreadPool(4)
    private lateinit var engine: Engine
    private var detector: Detector? = null
    val cams: List<CamView>
    private val streams: List<Stream>
    private val occ = mutableMapOf<String, Occupancy>()
    private val t0Ns = SystemClock.elapsedRealtimeNanos()

    // status, read by the UI
    @Volatile var state = "starting"
    @Volatile var backend = "-"
    @Volatile var checker = "-"
    val previews = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    @Volatile var orderText = ""
    val feed = ArrayDeque<String>()
    @Volatile var statusText = ""
    var onUpdate: (() -> Unit)? = null
    private val inferMs = mutableListOf<Double>()
    private val engineMs = mutableListOf<Double>()
    private val nChecks = AtomicInteger()
    private var costUsd = 0.0
    private val checkLat = mutableListOf<Double>()
    private var lastT = 0.0
    private var ended = 0
    private val lastPreview = mutableMapOf<String, Long>()
    private val dishes = linkedMapOf<Int, MutableMap<String, Any?>>()

    init {
        var s = loadSite(File(siteDir, "site.json"))
        cfg.params?.let { over ->
            val merged = JsonObject(json.encodeToJsonElement(Params.serializer(), s.params).jsonObject + json.parseToJsonElement(over).jsonObject)
            s = s.copy(params = json.decodeFromJsonElement(Params.serializer(), merged))
        }
        site = s
        val info = json.parseToJsonElement(File(siteDir, "model.json").readText()).jsonObject
        val size = info["imgsz"]!!.jsonPrimitive.content.toInt()
        cams = site.cameras.map { c -> CamView(c, roi(c.name), size, cfg.viewMax) }
        val fps = site.cameras.associate { it.name to it.fps }
        streams = cams.groupBy { it.camera.url }.map { (url, cs) ->
            val file = if (url.startsWith("file:")) url.removePrefix("file:").let { n ->
                listOf(File(siteDir, n), File(ctx.getExternalFilesDir("clips"), n)).firstOrNull { it.exists() }
                    ?: error("video $n not found in the site pack or clips/")
            } else null
            Stream(ctx, url, file, cs, cs.maxOf { fps[it.name]!! }, cfg.speed, t0Ns, ::onShot, ::onEnded, ::log)
        }
    }

    /** The detector's region of interest for a camera: the union of its zones, padded by 5% of the view (spec §2). */
    private fun roi(cam: String): Box {
        val c = site.cameras.first { it.name == cam }
        val w = c.crop?.let { it[2] - it[0] } ?: c.width.toDouble()
        val h = c.crop?.let { it[3] - it[1] } ?: c.height.toDouble()
        val zs = site.zones.filter { it.camera == cam }
        if (zs.isEmpty() || zs.any { it.poly == null }) return Box(0.0, 0.0, w, h)
        val pts = zs.flatMap { it.poly!! }
        val px = 0.05 * w; val py = 0.05 * h
        return Box(maxOf(0.0, pts.minOf { it[0] } - px), maxOf(0.0, pts.minOf { it[1] } - py),
            minOf(w, pts.maxOf { it[0] } + px), minOf(h, pts.maxOf { it[1] } + py))
    }

    fun start() {
        File(dir, "run.json").writeText(org.json.JSONObject().put("site", cfg.site).put("run_id", cfg.runId).put("speed", cfg.speed)
            .put("view_max", cfg.viewMax).put("gpu", cfg.gpu).put("params", cfg.params)
            .put("rois", org.json.JSONObject(cams.associate { it.name to it.roi.toList().toString() })).toString(2))
        detH.post {
            try {
                val info = json.parseToJsonElement(File(siteDir, "model.json").readText()).jsonObject
                val classes = info["classes"]!!.jsonArray.map { it.jsonPrimitive.content }
                detector = Detector(File(siteDir, "model.tflite"), classes, cams.first().modelSize, cfg.gpu).also { backend = it.backend }
                log("detector ${backend}, classes $classes")
            } catch (e: Throwable) { fail("detector: $e"); return@post }
            engineH.post { startEngine() }
        }
    }

    private fun startEngine() {
        val keyFile = File(ctx.filesDir, "openai.key")
        val chk: Checker? = if (keyFile.exists()) {
            checker = "Decisions API"
            DecisionsChecker(DecisionsClient(keyFile.readText().trim(), File(ctx.cacheDir, "decisions")), loadMenu(File(siteDir, "menu.json")),
                site.classes.base.firstOrNull() ?: "dish")
        } else { checker = "off (no key)"; null }
        val runner = CheckRunner { req, done ->
            if (chk == null) return@CheckRunner done(null)
            checks.execute {
                val res = try { chk.check(req) } catch (e: Exception) { log("check_error ${e.message?.take(200)}"); null }
                if (res != null) synchronized(checkLat) {
                    nChecks.incrementAndGet(); costUsd += res.costUsd; checkLat += res.latencyS
                    callsFile.appendText(Event.toEl(mapOf("dish" to req.baseId, "t" to req.t, "probs" to res.probs, "latency_s" to res.latencyS,
                        "tokens_in" to res.tokensIn, "cost_usd" to res.costUsd, "cached" to res.cached)).toString() + "\n")
                }
                done(res)
            }
        }
        engine = Engine(site, loadMenu(File(siteDir, "menu.json")), loadOrders(File(siteDir, "orders.json")), runner)
        engine.log.listeners += { e ->
            eventsFile.appendText(e.toJson().toString() + "\n")
            track(e)
            if (e.kind != "ingredient_check") synchronized(feed) {
                feed.addFirst("%6.1f  %-18s %s".format(e.knownT, e.kind, e.data.entries.filter { it.key != "probs" }
                    .joinToString(" ") { "${it.key}=${it.value}" }).take(160))
                while (feed.size > 40) feed.removeLast()
            }
        }
        // Occupancy only matters for the checkout fallback (no closed-container class).
        if (site.classes.closedContainer.isEmpty()) for (c in cams) {
            val zs = site.zones.filter { it.camera == c.name && it.role == Role.CHECKOUT }
            if (zs.isEmpty()) continue
            occ[c.name] = Occupancy(c, zs).also { o -> background(c)?.let { o.setBackground(it) } }
        }
        state = "running"
        streams.forEach { it.start() }
        metricsH.postDelayed(metrics, 5000)
        log("started: ${cams.size} cameras on ${streams.size} streams; rois ${cams.map { it.name to it.roi.toList() }}")
    }

    /** Empty-counter background from the site pack (background/<camera>.png or .jpg, camera pixels), as view RGBA. */
    private fun background(c: CamView): java.nio.ByteBuffer? {
        val f = listOf("png", "jpg").map { File(siteDir, "background/${c.name}.$it") }.firstOrNull { it.exists() } ?: return null
        val bm = Bitmap.createScaledBitmap(BitmapFactory.decodeFile(f.path), c.viewW, c.viewH, true)
        return GlFrames.direct(4 * c.viewW * c.viewH).also { bm.copyPixelsToBuffer(it); it.position(0) }
    }

    // GL thread -> detector thread -> engine thread
    private fun onShot(s: Shot) {
        detH.post {
            val d = detector ?: return@post s.cam.give(s)
            val a = SystemClock.elapsedRealtimeNanos()
            s.dets = try { d.detect(s.model, s.cam.lb, s.cam.roi) } catch (e: Exception) { log("detect_error $e"); emptyList() }
            s.inferMs = (SystemClock.elapsedRealtimeNanos() - a) / 1e6
            engineH.post { process(s) }
        }
    }

    private fun process(s: Shot) {
        val a = SystemClock.elapsedRealtimeNanos()
        try {
            val c = s.cam
            val o = occ[c.name]?.let { if (!it.hasBackground) { it.setBackground(s.view); log("background ${c.name} from first frame t=${s.t}") }; it.measure(s.view) }.orEmpty()
            var bm: Bitmap? = null
            val crop = { box: Box, mask: List<Box> ->
                val v = bm ?: viewBitmap(s).also { bm = it }
                cropJpeg(v, c.viewScale, box, site.params.cropSide, site.params.cropPad, mask)
            }
            engine.onFrame(Frame(c.name, s.t, s.dets, o, crop))
            lastT = maxOf(lastT, s.t)
            if (cfg.saveDets) detsFile.appendText(Event.toEl(mapOf("cam" to c.name, "t" to s.t,
                "dets" to s.dets.map { listOf(it.cls, it.conf) + it.box.toList() }, "occ" to o)).toString() + "\n")
            synchronized(inferMs) { inferMs += s.inferMs; engineMs += (SystemClock.elapsedRealtimeNanos() - a) / 1e6 }
            val now = SystemClock.elapsedRealtime()
            if (now - (lastPreview[c.name] ?: 0L) > 400) {
                lastPreview[c.name] = now
                previews[c.name] = preview(s, bm ?: viewBitmap(s))
                orderText = orders()
                onUpdate?.invoke()
            }
        } catch (e: Exception) {
            log("engine_error ${e.stackTraceToString().take(600)}")
        } finally {
            s.cam.give(s)
        }
    }

    private fun onEnded() {
        engineH.post {
            ended++
            if (ended < streams.count { it.url.startsWith("file:") }) return@post
            // Let outstanding checks come back before closing (up to 30 s).
            checks.shutdown(); checks.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            finish("all files ended")
        }
    }

    private fun finish(why: String) {
        if (state == "finished") return
        engine.finish(lastT)
        engine.log.write(File(dir, "events_final.jsonl"))
        orderText = orders()
        state = "finished"
        sampleMetrics()
        File(dir, "done.json").writeText(org.json.JSONObject().put("reason", why).put("t", lastT)
            .put("events", engine.log.events.size).put("checks", nChecks.get()).put("cost_usd", costUsd).toString(2))
        log("finished: $why")
        onUpdate?.invoke()
    }

    fun stop() {
        streams.forEach { it.stop() }
        if (::engine.isInitialized) engineH.post { finish("stopped") }
        engineH.post {
            metricsH.removeCallbacksAndMessages(null)
            detH.post { detector?.close(); detector = null }
        }
    }

    // ---------------------------------------------------------------- status

    private val metrics = object : Runnable {
        override fun run() {
            if (state == "finished") return
            try { sampleMetrics() } catch (e: Exception) { log("metrics_error $e") }
            metricsH.postDelayed(this, 5000)
        }
    }

    private fun sampleMetrics() {
        val (inf, eng) = synchronized(inferMs) { val r = inferMs.sorted() to engineMs.sorted(); inferMs.clear(); engineMs.clear(); r }
        val lat = synchronized(checkLat) { checkLat.sorted() }
        fun p(l: List<Double>, q: Double) = if (l.isEmpty()) null else Math.round(l[minOf(l.size - 1, (l.size * q).toInt())] * 10) / 10.0
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val m = mapOf(
            "wall_s" to (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1e9, "t" to lastT, "backend" to backend,
            "shots_5s" to inf.size, "infer_ms_p50" to p(inf, 0.5), "infer_ms_p95" to p(inf, 0.95), "engine_ms_p50" to p(eng, 0.5),
            "cams" to cams.associate { it.name to mapOf("shots" to it.shots.get(), "dropped" to it.dropped.get()) },
            "streams" to streams.associate { it.url to mapOf("frames" to it.frames.get(), "state" to it.state, "decoder" to it.decoder,
                "errors" to it.errors.get(), "last_error" to it.lastError) },
            "checks" to nChecks.get(), "check_p50_s" to p(lat, 0.5), "cost_usd" to costUsd, "thermal" to pm.currentThermalStatus,
        )
        metricsFile.appendText(Event.toEl(m).toString() + "\n")
        statusText = "%s · %s · t %.1f s · detector %s ms (p50, %d in 5 s) · checks %d (%s s, \$%.4f) · thermal %d".format(
            state, backend, lastT, p(inf, 0.5) ?: "-", inf.size, nChecks.get(), p(lat, 0.5) ?: "-", costUsd, pm.currentThermalStatus) +
            "\n" + cams.joinToString("  ") { "${it.name}: ${it.shots.get()} shots, ${it.dropped.get()} dropped" } +
            "  ·  checker: $checker"
        onUpdate?.invoke()
    }

    /** Dishes being assembled, from events (the stations keep theirs private). */
    private fun track(e: Event) {
        val id = (e.data["dish"] as? Int) ?: return
        val d = dishes.getOrPut(id) { mutableMapOf("found" to sortedSetOf<String>()) }
        when (e.kind) {
            "dish_started" -> d["zone"] = e.data["zone"]
            "ingredient_confirmed" -> @Suppress("UNCHECKED_CAST") (d["found"] as MutableSet<String>).add(e.data["ingredient"] as String)
            "dish_verdict" -> { d["order"] = e.data["order"]; d["recipe"] = e.data["recipe"]; d["missing"] = e.data["missing"]; d["extra"] = e.data["extra"] }
            "dish_done" -> d["done"] = true
        }
    }

    private fun orders(): String {
        val sb = StringBuilder()
        for (o in engine.orders) {
            val us = engine.units.filter { it.orderId == o.id }
            val st = when {
                us.size == o.items.size && us.all { it.handedOver } -> "HANDED OVER"
                us.isNotEmpty() && us.size == o.items.size && us.all { it.atCheckout } -> "AT CHECKOUT"
                us.isNotEmpty() && us.size == o.items.size && us.all { it.packedT != null } -> "PACKED"
                else -> "${us.size}/${o.items.size} made"
            }
            sb.append("${o.id}  $st\n")
            o.items.forEachIndexed { i, it ->
                val u = us.firstOrNull { u -> u.item == i }
                val mods = it.modifiers.takeIf { m -> m.isNotEmpty() }?.joinToString(", ", " (", ")") ?: ""
                sb.append("  ${it.recipe}$mods: ")
                if (u == null) sb.append("—\n") else {
                    sb.append("dish ${u.id} ${u.contents.joinToString(", ")}")
                    if (u.missing.isNotEmpty()) sb.append("  MISSING ${u.missing.joinToString(", ")}")
                    if (u.extra.isNotEmpty()) sb.append("  EXTRA ${u.extra.joinToString(", ")}")
                    if (u.candidates.isNotEmpty()) sb.append("  UNCERTAIN ${u.candidates}")
                    sb.append("\n")
                }
            }
        }
        val open = dishes.filter { it.value["done"] != true }
        if (open.isNotEmpty()) {
            sb.append("\nOn the line:\n")
            for ((id, d) in open) sb.append("  dish $id: ${(d["found"] as Set<*>).joinToString(", ").ifEmpty { "…" }}\n")
        }
        return sb.toString()
    }

    private val paints = mapOf(
        "zone" to Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(200, 255, 255, 255) },
        "text" to Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true; setShadowLayer(3f, 1f, 1f, Color.BLACK) },
    )
    private val classColours = intArrayOf(Color.rgb(255, 196, 0), Color.rgb(0, 200, 255), Color.rgb(120, 255, 120),
        Color.rgb(255, 90, 90), Color.rgb(200, 120, 255))

    /** Camera view at most 960 px wide with zones, the detector's region and detections drawn on it. */
    private fun preview(s: Shot, view: Bitmap): Bitmap {
        val c = s.cam
        val k = minOf(1.0, 960.0 / c.viewW)
        val bm = Bitmap.createScaledBitmap(view, (c.viewW * k).toInt(), (c.viewH * k).toInt(), true).copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(bm)
        val sc = (c.viewScale * k).toFloat()
        val tp = paints["text"]!!
        for (z in site.zones.filter { it.camera == c.name && it.poly != null }) {
            val path = Path()
            z.poly!!.forEachIndexed { i, p -> if (i == 0) path.moveTo(p[0].toFloat() * sc, p[1].toFloat() * sc) else path.lineTo(p[0].toFloat() * sc, p[1].toFloat() * sc) }
            path.close()
            cv.drawPath(path, paints["zone"]!!)
            cv.drawText("${z.name} (${z.role.name.lowercase()})", z.poly!![0][0].toFloat() * sc + 4, z.poly!![0][1].toFloat() * sc + 20, tp)
        }
        val roiP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(160, 255, 255, 0); pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f) }
        cv.drawRect(c.roi.x0.toFloat() * sc, c.roi.y0.toFloat() * sc, c.roi.x1.toFloat() * sc, c.roi.y1.toFloat() * sc, roiP)
        val classes = s.dets.map { it.cls }.distinct().sorted()
        for (d in s.dets) {
            val col = classColours[(site.classes.base + site.classes.openContainer + site.classes.closedContainer + site.classes.person)
                .indexOf(d.cls).let { if (it < 0) classes.indexOf(d.cls) else it } % classColours.size]
            val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = col }
            cv.drawRect(d.box.x0.toFloat() * sc, d.box.y0.toFloat() * sc, d.box.x1.toFloat() * sc, d.box.y1.toFloat() * sc, p)
            cv.drawText("%s %.2f".format(d.cls, d.conf), d.box.x0.toFloat() * sc, d.box.y0.toFloat() * sc - 4, tp)
        }
        for (u in engine.units.filter { it.camera == c.name && it.lastBox != null && !it.handedOver }) {
            val b = u.lastBox!!
            cv.drawText("${u.orderId ?: "unit ${u.id}"} ${u.state.name.lowercase()}", b.x0.toFloat() * sc, b.y1.toFloat() * sc + 22, tp)
        }
        cv.drawText("%s  t %.1f s  %d dets".format(c.name, s.t, s.dets.size), 8f, bm.height - 10f, tp)
        return bm
    }

    fun log(msg: String) {
        val line = "%s %s".format(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()), msg)
        synchronized(logFile) { logFile.appendText(line + "\n") }
        android.util.Log.i("ra", msg)
    }

    private fun fail(msg: String) {
        state = "failed: $msg"
        log("FAILED $msg")
        File(dir, "done.json").writeText(org.json.JSONObject().put("reason", "failed").put("error", msg).toString(2))
        onUpdate?.invoke()
    }
}
