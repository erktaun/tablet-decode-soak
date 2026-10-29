package com.ald.ra.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Laptop replay: run the engine on detections recorded by the Python perception step
 * (cache/scene_<name>.json: per-frame detections + zone occupancy) and write events.jsonl.
 *
 *   gradle :engine:run --args="--site sites/pizza_trailer --detections ../cache/scene_pizza_trailer.json \
 *       --checker recorded:../outputs/pizza_trailer/decision_calls.jsonl --out out/pizza_trailer"
 *
 * `--checker live` sends real crops (cut from `--video` with ffmpeg) to the Decisions API, key from `--env`.
 * `--referee live` turns the track referee on and sends its cases to the Decisions API (needs `--video`); every case
 * goes to referee_calls.jsonl with its images in referee/.
 * `--params '{"checkEveryS":10}'` overrides site params.
 * Several cameras: each needs a `crop` of the recorded video (one wide video played as several cameras).
 * `--tracks <file.jsonl>` also writes, per frame and camera, the tracker's confirmed tracks for every class (the same
 * ByteTrack, params and fps as custody), for drawing track ids on the video (tools/render_tracks.py).
 *
 * `--probe-referee '{"camera":"cam0","before":[{"t":212,"boxes":{"A":[x0,y0,x1,y1],"B":[...]}}],
 *                    "after":{"t":253,"boxes":{"1":[...]}}}'` asks the referee about hand-picked frames of `--video`
 * (no detections needed) and prints its answers: a way to see what it can tell apart on a site's own boxes.
 */
fun main(args: Array<String>) {
    val a = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val dir = File(a["site"] ?: error("--site <site pack dir>"))
    var site = loadSite(File(dir, "site.json"))
    val refereeLive = a["referee"] == "live"
    if (refereeLive) site = site.copy(params = site.params.copy(referee = true))
    a["params"]?.let { over ->
        val merged = JsonObject(json.encodeToJsonElement(Params.serializer(), site.params).jsonObject + json.parseToJsonElement(over).jsonObject)
        site = site.copy(params = json.decodeFromJsonElement(Params.serializer(), merged))
    }
    val menu = loadMenu(File(dir, "menu.json"))
    val orders = loadOrders(File(dir, "orders.json"))
    val out = File(a["out"] ?: "out/${site.site}")
    val checkerSpec = a["checker"] ?: "none"
    val client by lazy { DecisionsClient(envKey(File(a["env"] ?: "../.env"), "OPENAI_API_KEY"), File(a["cache"] ?: "cache/decisions")) }
    val video = a["video"]?.let { VideoFrames(File(it)) }
    val checker: Checker? = when {
        checkerSpec.startsWith("recorded:") -> RecordedChecker(File(checkerSpec.removePrefix("recorded:")))
        checkerSpec == "live" -> DecisionsChecker(client, menu, site.classes.base.first())
        else -> null
    }
    out.mkdirs()
    val calls = File(out, "decision_calls.jsonl").also { it.writeText("") }
    val runner = CheckRunner { req, done ->
        val res = checker?.check(req)
        if (res != null) calls.appendText(Event.toEl(mapOf("dish" to req.baseId, "t" to req.t, "probs" to res.probs,
            "latency_s" to res.latencyS, "tokens_in" to res.tokensIn, "cost_usd" to res.costUsd, "cached" to res.cached)).toString() + "\n")
        done(res)
    }
    val refCalls = File(out, "referee_calls.jsonl").also { it.writeText("") }
    val referee = if (refereeLive) DecisionsReferee(client) else null
    val refRunner = referee?.let { r -> RefereeRunner { req, done ->
        val res = r.judge(req)
        val imgDir = File(out, "referee").also { it.mkdirs() }
        req.images.forEachIndexed { k, (_, jpeg) -> File(imgDir, "case${req.id}_$k.jpg").writeBytes(jpeg) }
        refCalls.appendText(Event.toEl(mapOf("case" to req.id, "t" to req.t, "camera" to req.camera, "trigger" to req.trigger,
            "units" to req.units, "boxes" to req.boxes.mapValues { it.value.toList() }, "captions" to req.images.map { it.first },
            "probs" to res?.probs, "latency_s" to res?.latencyS, "cost_usd" to res?.costUsd, "cached" to res?.cached)).toString() + "\n")
        done(res)
    } }
    val cams = site.cameras.associateBy { it.name }
    require(cams.size == 1 || cams.values.all { it.crop != null }) { "several cameras: each needs a crop of the recorded video" }
    val painter = video?.let { v -> Painter { s -> sketchJpeg(camImage(v.at(s.t), cams[s.camera]!!), s.labels) } }
    a["probe-referee"]?.let { return probeReferee(json.parseToJsonElement(it).jsonObject, DecisionsReferee(client), painter!!, out) }
    val engine = Engine(site, menu, orders, runner, refereeRunner = refRunner, painter = painter)
    val per = json.parseToJsonElement(File(a["detections"] ?: error("--detections <python perception json>")).readText()).jsonObject
    var lastT = 0.0
    val trackOut = a["tracks"]?.let { File(it).also { f -> f.parentFile?.mkdirs(); f.writeText("") } }
    val trackers = mutableMapOf<Pair<String, String>, ByteTrack>()
    for (fe in per["frames"]!!.jsonArray) {
        val fo = fe.jsonObject
        val t = fo["t"]!!.jsonPrimitive.double
        val dets = fo["dets"]!!.jsonObject.flatMap { (cls, arr) ->
            arr.jsonArray.map { d ->
                val o = d.jsonObject
                Det(cls, Box.of(o["box"]!!.jsonArray.map { it.jsonPrimitive.double }), o["conf"]!!.jsonPrimitive.double)
            }
        }
        val occ = fo["zones"]?.jsonObject?.mapValues { it.value.jsonObject["occ"]!!.jsonPrimitive.double }.orEmpty()
        for (cam in site.cameras) {
            val crop = video?.let { v -> { box: Box, mask: List<Box> -> cropJpeg(camImage(v.at(t), cam), box, site.params.cropSide, site.params.cropPad, mask) } }
            val zones = site.zones.filter { it.camera == cam.name }.map { it.name }.toSet()
            val camDets = inCamera(dets, cam)
            engine.onFrame(Frame(cam.name, t, camDets, occ.filterKeys { it in zones }, crop))
            if (trackOut != null) {
                val byCls = (camDets.map { it.cls } + trackers.keys.filter { it.first == cam.name }.map { it.second }).distinct().associateWith { cls ->
                    trackers.getOrPut(cam.name to cls) { ByteTrack(site.params, cam.fps) }.update(camDets.filter { it.cls == cls }, t)
                        .map { listOf(it.id.toDouble()) + it.box.toList() }
                }
                trackOut.appendText(Event.toEl(mapOf("t" to t, "cam" to cam.name, "tracks" to byCls)).toString() + "\n")
            }
        }
        lastT = t
    }
    engine.finish(lastT)
    engine.log.write(File(out, "events.jsonl"))
    for (e in engine.log.sorted()) if (e.kind != "ingredient_check")
        println("%7.1f (known %6.1f)  %-20s %s".format(e.t, e.knownT, e.kind, e.data.entries.joinToString(" ") { "${it.key}=${it.value}" }))
    val cs = calls.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }
    if (cs.isNotEmpty()) {
        val lat = cs.map { it["latency_s"]!!.jsonPrimitive.double }.sorted()
        println("checks %d  p50 %.2f s  p95 %.2f s  cost \$%.4f".format(cs.size, lat[lat.size / 2],
            lat[minOf(lat.size - 1, (lat.size * 0.95).toInt())], cs.sumOf { it["cost_usd"]!!.jsonPrimitive.double }))
    }
    println("wrote ${File(out, "events.jsonl")}")
}

private fun probeReferee(q: JsonObject, ref: DecisionsReferee, painter: Painter, out: File) {
    val cam = q["camera"]!!.jsonPrimitive.content
    fun boxes(o: JsonObject) = o["boxes"]!!.jsonObject.mapValues { Box.of(it.value.jsonArray.map { v -> v.jsonPrimitive.double }) }
    fun sketch(o: JsonObject) = Sketch(cam, o["t"]!!.jsonPrimitive.double, boxes(o).toList())
    val before = q["before"]!!.jsonArray.map { sketch(it.jsonObject) }
    val after = sketch(q["after"]!!.jsonObject)
    val imgs = before.map { "BEFORE (t = %.1f s, camera %s): known boxes %s".format(it.t, cam, it.labels.joinToString { l -> l.first }) to it } +
        listOf("AFTER (t = %.1f s, camera %s): boxes to identify %s".format(after.t, cam, after.labels.joinToString { it.first }) to after)
    val req = RefereeRequest(0, after.t, cam, "probe", before.flatMap { it.labels }.associate { it.first to null },
        after.labels.toMap(), imgs.map { (cap, s) -> cap to painter.paint(s)!! })
    val dir = File(out, "probe").also { it.mkdirs() }
    req.images.forEachIndexed { k, (_, jpeg) -> File(dir, "image$k.jpg").writeBytes(jpeg) }
    val res = ref.judge(req) ?: error("no answer")
    for ((k, v) in res.probs.toSortedMap()) println("  %-6s %.3f".format(k, v))
    val ranked = RefereeDesk.assignments(req.units.keys.toList(), req.boxes.keys.toList())
        .map { m -> m to m.entries.sumOf { (b, u) -> res.probs["$u=$b"] ?: 0.0 } }.sortedByDescending { it.second }
    println("best %s (%.2f), next %s (%.2f); latency %.2f s, %d tokens, \$%.5f%s".format(ranked[0].first, ranked[0].second,
        ranked.getOrNull(1)?.first, ranked.getOrNull(1)?.second ?: 0.0, res.latencyS, res.tokensIn, res.costUsd,
        if (res.cached) " (cached)" else ""))
    println("images in $dir")
}

/** Detections of the whole recorded frame as seen by one camera: those centred in its crop, shifted and clipped. */
fun inCamera(dets: List<Det>, cam: Camera): List<Det> {
    val c = cam.crop ?: return dets
    return dets.filter { it.box.cx >= c[0] && it.box.cx < c[2] && it.box.cy >= c[1] && it.box.cy < c[3] }.map {
        Det(it.cls, Box(maxOf(it.box.x0, c[0]) - c[0], maxOf(it.box.y0, c[1]) - c[1], minOf(it.box.x1, c[2]) - c[0],
            minOf(it.box.y1, c[3]) - c[1]), it.conf)
    }
}

/** The camera's view of a recorded frame (its crop, or the whole frame). */
fun camImage(src: BufferedImage, cam: Camera): BufferedImage = cam.crop?.let { c ->
    src.getSubimage(c[0].toInt(), c[1].toInt(), (c[2] - c[0]).toInt(), (c[3] - c[1]).toInt())
} ?: src

/** A referee image: the frame with each labelled box outlined in its own colour and tagged, long side at most `maxSide`. */
fun sketchJpeg(src: BufferedImage, labels: List<Pair<String, Box>>, maxSide: Int = 960): ByteArray {
    val s = minOf(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
    val w = Math.round(src.width * s).toInt(); val h = Math.round(src.height * s).toInt()
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.drawImage(src, 0, 0, w, h, null)
    val colours = listOf(Color(230, 25, 75), Color(0, 130, 200), Color(255, 225, 25), Color(60, 180, 75), Color(145, 30, 180), Color(245, 130, 48))
    val fs = maxOf(14, h / 22)
    g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, fs)
    g.stroke = java.awt.BasicStroke(maxOf(2f, h / 240f))
    labels.forEachIndexed { i, (label, b) ->
        val col = colours[i % colours.size]
        val x0 = (b.x0 * s).toInt(); val y0 = (b.y0 * s).toInt()
        g.color = col
        g.drawRect(x0, y0, (b.w * s).toInt(), (b.h * s).toInt())
        val tw = g.fontMetrics.stringWidth(label) + fs / 2
        val ty = maxOf(0, y0 - fs - 4)
        g.fillRect(x0, ty, tw, fs + 4)
        g.color = if (col.red + col.green > 400) Color.BLACK else Color.WHITE
        g.drawString(label, x0 + fs / 4, ty + fs)
    }
    g.dispose()
    return jpeg(img, 0.9f)
}

private fun jpeg(img: BufferedImage, q: Float): ByteArray {
    val bos = ByteArrayOutputStream()
    val w = ImageIO.getImageWritersByFormatName("jpg").next()
    val prm = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
    w.output = ImageIO.createImageOutputStream(bos)
    w.write(null, IIOImage(img, null, null), prm)
    w.dispose()
    return bos.toByteArray()
}

/** Reads one key from a .env file without printing it. */
fun envKey(f: File, name: String): String = System.getenv(name)
    ?: f.readLines().map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim()?.trim('"', '\'')
    ?: error("$name not found in environment or $f")

/** Frames of a video by time, decoded with ffmpeg (laptop only; the app has its own decoder). */
class VideoFrames(private val f: File) {
    private val cache = mutableMapOf<Double, BufferedImage>()

    fun at(t: Double): BufferedImage = cache.getOrPut(t) {
        if (cache.size > 8) cache.clear()
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-ss", "%.3f".format(t), "-i", f.path, "-frames:v", "1",
            "-f", "image2pipe", "-vcodec", "png", "-").start()
        val img = ImageIO.read(p.inputStream)
        p.waitFor()
        img ?: error("no frame at $t in $f")
    }
}

/**
 * Same crop as src/ingtrack/decisions.py: other boxes (IoU < 0.5 with this one) greyed out, padded, long side
 * scaled to `side`, JPEG quality 90.
 */
fun cropJpeg(src: BufferedImage, box: Box, side: Int, pad: Double, mask: List<Box>): ByteArray {
    val img = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.drawImage(src, 0, 0, null)
    g.color = Color(128, 128, 128)
    for (m in mask) if (iou(m, box) < 0.5) g.fillRect(m.x0.toInt(), m.y0.toInt(), (m.x1 - m.x0).toInt(), (m.y1 - m.y0).toInt())
    g.dispose()
    val pp = pad * box.size
    val x0 = maxOf(0, (box.x0 - pp).toInt()); val y0 = maxOf(0, (box.y0 - pp).toInt())
    val x1 = minOf(img.width, (box.x1 + pp).toInt()); val y1 = minOf(img.height, (box.y1 + pp).toInt())
    val c = img.getSubimage(x0, y0, x1 - x0, y1 - y0)
    val s = side.toDouble() / maxOf(c.width, c.height)
    val outW = Math.round(c.width * s).toInt(); val outH = Math.round(c.height * s).toInt()
    val o = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
    val g2 = o.createGraphics()
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g2.drawImage(c, 0, 0, outW, outH, null)
    g2.dispose()
    return jpeg(o, 0.9f)
}
