package com.ald.ra.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

/** A camera frame at time t with labelled boxes drawn on it (no labels = plain frame). */
class Sketch(val camera: String, val t: Double, val labels: List<Pair<String, Box>>)

/** Draws a sketch as a JPEG. The host supplies it (java.awt on the laptop, Canvas on Android). Null = frame no longer available. */
fun interface Painter {
    fun paint(s: Sketch): ByteArray?
}

/**
 * One referee case: which lettered unit (BEFORE images) is which numbered box (AFTER image)?
 * `units`: letter -> order id; `boxes`: number -> box in the AFTER image. Images come with a caption each.
 */
class RefereeRequest(
    val id: Int,
    val t: Double,
    val camera: String,
    val trigger: String,
    val units: Map<String, String?>,
    val boxes: Map<String, Box>,
    val images: List<Pair<String, ByteArray>>,
)

/** Probability per pair, keyed "A=1". */
class RefereeResult(val probs: Map<String, Double>, val latencyS: Double, val tokensIn: Int, val costUsd: Double, val cached: Boolean)

/** Runs referee calls. The replay runs them inline; the app on a worker thread. */
fun interface RefereeRunner {
    fun submit(req: RefereeRequest, done: (RefereeResult?) -> Unit)
}

/** The referee on the Decisions API: one predicate per (unit, box) pair, all images in one message. */
class DecisionsReferee(private val client: DecisionsClient) {
    val context = "Frames from fixed cameras in a restaurant. Takeaway boxes are being tracked from frame to frame. " +
        "In the BEFORE image(s), known boxes are outlined and labelled with letters (A, B, ...). In the AFTER image, " +
        "boxes to identify are outlined and labelled with numbers (1, 2, ...); staff may have moved them in between. " +
        "Use anything visible: position, what people did in between, stickers, writing, damage, contents. If the boxes " +
        "look identical and you can't see where each one went, give a probability near 0.5."

    fun judge(req: RefereeRequest): RefereeResult? {
        if (req.images.isEmpty()) return null
        val qs = buildJsonArray {
            for (u in req.units.keys) for (b in req.boxes.keys)
                add(DecisionsClient.predicate("${u}_is_$b", "Box $u in the BEFORE image(s) is the same physical box as box $b in the AFTER image."))
        }
        val content = buildJsonArray {
            add(DecisionsClient.text(context))
            for ((cap, jpeg) in req.images) { add(DecisionsClient.text(cap)); add(DecisionsClient.image(jpeg)) }
        }
        val (o, cached) = client.ask(listOf(JsonPrimitive(context)) + req.images.map { JsonPrimitive(it.first) }, content, qs,
            req.images.map { it.second }) { resp, lat -> DecisionsClient.answers(resp, lat) { it.replace("_is_", "=") } }
        val tokens = o["tokens_in"]!!.jsonPrimitive.int
        return RefereeResult(o["probs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }, o["latency_s"]!!.jsonPrimitive.double,
            tokens, client.cost(tokens), cached)
    }
}

/**
 * Engine side of the referee (spec §4.5): decides when to ask, what to draw, and applies the answer.
 * Triggers:
 *  - "uncertain": custody bound a box with several candidate units (e.g. identical boxes out of view in transit);
 *  - "crossed": two bound boxes came close while one person touched both, then separated (the tracker may have
 *    swapped them).
 * The best assignment (highest sum of probabilities) is applied only if it beats the next best by `refereeMargin`;
 * otherwise nothing changes and an uncertain unit stays uncertain. It corrects identity, never positions.
 */
class RefereeDesk(private val e: Engine, private val runner: RefereeRunner, private val painter: Painter) {
    private val p = e.p
    private var nextId = 1
    private val open = mutableSetOf<Pair<String, Int>>()

    /** What a unit was before the case, to put it back if the referee gives its box to another unit. */
    class Snap(u: OrderUnit) {
        val state = u.state; val camera = u.camera; val lastBox = u.lastBox; val lastSeenT = u.lastSeenT
        val lostT = u.lostT; val coveredBy = u.coveredBy; val carrier = u.carrier; val transitT = u.transitT
        val zone = u.zone; val leftT = u.leftT; val atCheckout = u.atCheckout
        fun restore(u: OrderUnit) {
            u.state = state; u.camera = camera; u.lastBox = lastBox; u.lastSeenT = lastSeenT
            u.lostT = lostT; u.coveredBy = coveredBy; u.carrier = carrier; u.transitT = transitT
            u.zone = zone; u.leftT = leftT; u.atCheckout = atCheckout
        }
    }

    /** Called by custody before the bound unit `u` is updated, so its state is still the one it had before. */
    fun uncertain(u: OrderUnit, cands: List<OrderUnit>, c: String, tr: TrackOut, t: Double) {
        if (!open.add(c to tr.id)) return
        val since = cands.minOf { it.transitT ?: it.lostT ?: it.lastSeenT }
        // Other candidates already bound to a new box in this camera since then are part of the same puzzle.
        val others = cands.filter { it !== u && it.state == UState.SURFACE && it.camera == c }
            .mapNotNull { v -> v.tracks[c]?.takeIf { id -> (e.custody.trackStart(c, id) ?: -1.0) >= since - 1.0 } }
        val tracks = listOf(tr.id) + others
        val before = cands.filter { it.camera != null && it.lastBox != null }
            .groupBy { it.camera!! }.flatMap { (cam, us) -> groupByTime(us).map { g -> Sketch(cam, g.maxOf { it.lastSeenT },
                g.map { letter(cands, it) to it.lastBox!! }) } }
            .sortedByDescending { it.t }.take(3).sortedBy { it.t }
        val during = before.lastOrNull()?.takeIf { b -> before.all { it.camera == c } && t - b.t > 1.0 }
            ?.let { Sketch(c, (it.t + t) / 2, emptyList()) }
        ask(c, t, "uncertain", cands, tracks, before, during, cands.associateWith { Snap(it) }) { open.remove(c to tr.id) }
    }

    /** Called by custody when bound tracks a and b separate after being close under one person; `apartT` = last time they were apart. */
    fun crossed(c: String, a: Int, ua: OrderUnit, b: Int, ub: OrderUnit, apartT: Double, t: Double) {
        if (ua.orderId == ub.orderId) return
        val ba = e.custody.boxAt(c, a, apartT) ?: return
        val bb = e.custody.boxAt(c, b, apartT) ?: return
        val units = listOf(ua, ub)
        ask(c, t, "crossed", units, listOf(a, b), listOf(Sketch(c, apartT, listOf("A" to ba, "B" to bb))),
            Sketch(c, (apartT + t) / 2, emptyList()), emptyMap()) {}
    }

    private fun ask(c: String, t: Double, trigger: String, units: List<OrderUnit>, tracks: List<Int>, before: List<Sketch>,
                    during: Sketch?, snaps: Map<OrderUnit, Snap>, close: () -> Unit) {
        val id = nextId++
        val letters = units.indices.associate { ('A' + it).toString() to units[it] }
        val now = tracks.mapNotNull { tid -> e.custody.boxAt(c, tid, t)?.let { tid to it } }
        val numbers = now.indices.associate { (it + 1).toString() to now[it] }
        val sketches = before.map { "BEFORE (t = %.1f s, camera %s): known boxes %s".format(it.t, it.camera, it.labels.joinToString { l -> l.first }) to it } +
            listOfNotNull(during?.let { "DURING (t = %.1f s, camera %s)".format(it.t, it.camera) to it }) +
            listOf("AFTER (t = %.1f s, camera %s): boxes to identify %s".format(t, c, numbers.keys.joinToString()) to
                Sketch(c, t, numbers.map { it.key to it.value.second }))
        val images = sketches.map { (cap, s) -> painter.paint(s)?.let { cap to it } }
        if (images.any { it == null } || numbers.isEmpty()) {
            e.log.add(t, "referee_skipped", t, "case" to id, "trigger" to trigger, "camera" to c, "reason" to "no frames")
            return close()
        }
        val req = RefereeRequest(id, t, c, trigger, letters.mapValues { it.value.orderId }, numbers.mapValues { it.value.second },
            images.filterNotNull())
        e.later(runner, req) { res ->
            close()
            if (res == null) return@later e.log.add(t, "referee_skipped", t, "case" to id, "trigger" to trigger, "reason" to "no answer")
            decide(req, res, letters, numbers.mapValues { it.value.first }, snaps)
        }
    }

    private fun decide(req: RefereeRequest, res: RefereeResult, letters: Map<String, OrderUnit>, numbers: Map<String, Int>,
                       snaps: Map<OrderUnit, Snap>) {
        val known = req.t + res.latencyS
        val ranked = assignments(letters.keys.toList(), numbers.keys.toList())
            .map { a -> a to a.entries.sumOf { (b, u) -> res.probs["$u=$b"] ?: 0.0 } }.sortedByDescending { it.second }
        val best = ranked.first()
        val margin = best.second - (ranked.getOrNull(1)?.second ?: 0.0)
        val accepted = margin >= p.refereeMargin - 1e-9 && letters.values.none { it.state == UState.HANDED }
        val assign = best.first.entries.associate { (b, u) -> numbers[b]!! to letters[u]!! }
        val corrected = accepted && e.custody.reassign(req.camera, assign, known) { u -> snaps[u]?.restore(u) }
        if (accepted) for (u in letters.values) u.candidates = emptyList()
        e.log.add(req.t, "referee", known, "case" to req.id, "trigger" to req.trigger, "camera" to req.camera,
            "units" to req.units, "probs" to res.probs, "chosen" to best.first.mapValues { req.units[it.value] },
            "margin" to margin, "accepted" to accepted, "corrected" to corrected, "latency_s" to res.latencyS, "cost_usd" to res.costUsd)
    }

    /** Units whose last sightings are within 1 s of each other share a BEFORE image. */
    private fun groupByTime(us: List<OrderUnit>): List<List<OrderUnit>> {
        val out = mutableListOf<MutableList<OrderUnit>>()
        for (u in us.sortedBy { it.lastSeenT })
            if (out.isNotEmpty() && u.lastSeenT - out.last().first().lastSeenT <= 1.0) out.last() += u else out += mutableListOf(u)
        return out
    }

    private fun letter(units: List<OrderUnit>, u: OrderUnit) = ('A' + units.indexOf(u)).toString()

    companion object {
        /** Every one-to-one pairing of boxes with units: box number -> unit letter. */
        fun assignments(units: List<String>, boxes: List<String>): List<Map<String, String>> {
            if (boxes.isEmpty()) return listOf(emptyMap())
            val b = boxes.first()
            return (units.map { u -> u to units - u } + if (units.size < boxes.size) listOf(null to units) else emptyList())
                .flatMap { (u, rest) -> assignments(rest, boxes.drop(1)).map { m -> if (u == null) m else m + (b to u) } }
        }
    }
}
