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
 * `--params '{"checkEveryS":10}'` overrides site params.
 */
fun main(args: Array<String>) {
    val a = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val dir = File(a["site"] ?: error("--site <site pack dir>"))
    var site = loadSite(File(dir, "site.json"))
    a["params"]?.let { over ->
        val merged = JsonObject(json.encodeToJsonElement(Params.serializer(), site.params).jsonObject + json.parseToJsonElement(over).jsonObject)
        site = site.copy(params = json.decodeFromJsonElement(Params.serializer(), merged))
    }
    val menu = loadMenu(File(dir, "menu.json"))
    val orders = loadOrders(File(dir, "orders.json"))
    val out = File(a["out"] ?: "out/${site.site}")
    val checkerSpec = a["checker"] ?: "none"
    val video = a["video"]?.let { VideoFrames(File(it)) }
    val checker: Checker? = when {
        checkerSpec.startsWith("recorded:") -> RecordedChecker(File(checkerSpec.removePrefix("recorded:")))
        checkerSpec == "live" -> DecisionsChecker(envKey(File(a["env"] ?: "../.env"), "OPENAI_API_KEY"), menu,
            site.classes.base.first(), File(a["cache"] ?: "cache/decisions"))
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
    val engine = Engine(site, menu, orders, runner)
    val cam = site.cameras.first().name
    val per = json.parseToJsonElement(File(a["detections"] ?: error("--detections <python perception json>")).readText()).jsonObject
    var lastT = 0.0
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
        val crop = video?.let { v -> { box: Box, mask: List<Box> -> cropJpeg(v.at(t), box, site.params.cropSide, site.params.cropPad, mask) } }
        engine.onFrame(Frame(cam, t, dets, occ, crop))
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
    val bos = ByteArrayOutputStream()
    val w = ImageIO.getImageWritersByFormatName("jpg").next()
    val prm = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.9f }
    w.output = ImageIO.createImageOutputStream(bos)
    w.write(null, IIOImage(o, null, null), prm)
    w.dispose()
    return bos.toByteArray()
}
