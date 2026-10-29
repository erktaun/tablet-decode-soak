package com.ald.ra.engine

/** Where a unit is (spec §4.2). */
enum class UState { PENDING, SURFACE, MISSING, CARRIED, TRANSIT, HANDED, LOST }

/**
 * Custody (spec §4.2-4.4): follows units once they are boxed (closed-container class) or, in `in_container` mode,
 * once the base is done. Box and person tracks come from one ByteTrack per camera and class; a unit is bound to the
 * box track(s) that show it. When a bound box disappears, rules decide what happened:
 *  - a person covered it and moved away -> carried by that person; the carrier leaving the camera -> in transit;
 *  - a box appearing where a unit went missing -> the same unit ("parked");
 *  - a new box near a carrier -> put down; a new box in a later camera within the travel window -> arrived;
 *  - with the shared map, a box seen by two cameras at the same spot -> the same unit.
 * Several candidates -> the most likely one, flagged uncertain with the others listed (never a silent guess).
 * A code (QR / order label) read on a box overrides all of this.
 */
class Custody(private val e: Engine) {
    private val p = e.p
    val boxCls = e.site.classes.closedContainer + if (e.site.assembly.mode == "in_container") e.site.classes.base else emptyList()
    private val cams = e.site.cameras.associateBy { it.name }
    private val boxT = cams.mapValues { ByteTrack(p, it.value.fps) }
    private val personT = cams.mapValues { ByteTrack(p, it.value.fps) }
    private val maps = cams.mapValues { c -> c.value.map?.let { Homography(it.image, it.plan) } }

    /** Latest confirmed tracks per camera (for the UI). */
    val boxes = mutableMapOf<String, List<TrackOut>>()
    val people = mutableMapOf<String, List<TrackOut>>()
    private val bound = mutableMapOf<Pair<String, Int>, OrderUnit>()
    private val history = mutableMapOf<Pair<String, Int>, ArrayDeque<Pair<Double, Box>>>()
    private val personLast = mutableMapOf<Pair<String, Int>, Pair<Double, Box>>()
    private val announced = mutableSetOf<Pair<String, Int>>()
    private val unknownCodes = mutableSetOf<String>()

    private val firstSeen = mutableMapOf<Pair<String, Int>, Double>()
    private val apart = mutableMapOf<Triple<String, Int, Int>, Double>()
    private val crossing = mutableSetOf<Triple<String, Int, Int>>()

    /** Called when a unit is bound with several candidates, before the unit is updated (the referee's trigger). */
    var onUncertain: ((unit: OrderUnit, candidates: List<OrderUnit>, camera: String, track: TrackOut, t: Double) -> Unit)? = null
    /** Called when two bound boxes separate after being close under one person (the referee's other trigger). */
    var onCrossed: ((camera: String, a: Int, ua: OrderUnit, b: Int, ub: OrderUnit, apartT: Double, t: Double) -> Unit)? = null

    fun active() = boxCls.isNotEmpty()

    fun onFrame(f: Frame) {
        val c = f.camera
        val t = f.t
        val bt = boxT[c]!!.update(f.dets.filter { it.cls in boxCls }, t)
        val pt = personT[c]!!.update(f.dets.filter { e.cls(e.site.classes.person, it) }, t)
        boxes[c] = bt; people[c] = pt
        for (tr in bt) history.getOrPut(c to tr.id) { ArrayDeque() }.apply {
            addLast(t to tr.box); while (first().first < t - p.historyS) removeFirst()
        }
        for (tr in bt) firstSeen.getOrPut(c to tr.id) { t - tr.ageS }
        for (pp in pt) personLast[c to pp.id] = t to pp.box
        history.entries.removeAll { it.key.first == c && it.value.last().first < t - p.historyS }
        firstSeen.keys.removeAll { it.first == c && it !in history }
        val seenIds = bt.map { it.id }.toSet()

        // 1. Bound boxes seen this frame.
        for (tr in bt) bound[c to tr.id]?.let { seen(it, c, tr.box, t) }
        onCrossed?.let { crossings(c, bt, pt, t, it) }
        // 2. Bound boxes not seen: missing (unless another camera still sees the unit).
        for ((key, u) in bound.entries.filter { it.key.first == c && it.key.second !in seenIds }.map { it.key to it.value }) {
            if (u.state == UState.SURFACE && u.camera == c && !visibleElsewhere(u, c, t)) {
                u.state = UState.MISSING; u.lostT = u.lastSeenT
                u.coveredBy = pt.filter { touches(it.box, u.lastBox!!) }.maxByOrNull { iou(it.box, u.lastBox!!) }?.id
            }
            if ((history[key]?.last()?.first ?: 0.0) < t - p.trackMaxLostS - 1e-6) { bound.remove(key); u.tracks.remove(c) }
        }
        // 3. Codes read on boxes override everything.
        for ((cb, code) in f.codes) bt.firstOrNull { inside(cb.cx, cb.cy, it.box) }?.let { identify(c, it, code, t) }
        // 4. Unbound boxes: find which unit they are.
        for (tr in bt.filter { (c to it.id) !in bound }) resolve(c, tr, t)
        // 5. Missing and carried units in this camera.
        for (u in e.units.filter { it.camera == c }) evolve(u, c, pt, t)
        // 6. Units in transit for too long.
        for (u in e.units.filter { it.state == UState.TRANSIT && t - it.transitT!! > maxTravel(it) }) {
            u.state = UState.LOST
            e.log.add(t, "unit_lost", t, "unit" to u.id, "order" to u.orderId, "last_seen" to u.camera, "since" to u.transitT)
        }
    }

    private fun seen(u: OrderUnit, c: String, box: Box, t: Double) {
        if (u.state == UState.HANDED) return
        u.state = UState.SURFACE; u.camera = c; u.lastBox = box; u.lastSeenT = t; u.coveredBy = null; u.carrier = null
        maps[c]?.let { m -> u.mapPos = m.map(box.cx, box.cy); u.mapT = t }
        val z = zoneAt(c, box)
        if (z != null && e.flowIndex(z.name) > e.flowIndex(u.zone)) enter(u, z, t)
    }

    private fun enter(u: OrderUnit, z: Zone, t: Double) {
        u.zone = z.name; u.leftT = t
        e.log.add(t, "unit_entered", t, "unit" to u.id, "order" to u.orderId, "zone" to z.name)
        if (z.role == Role.CHECKOUT) {
            u.atCheckout = true
            e.log.add(t, "unit_at_checkout", t, "unit" to u.id, "order" to u.orderId, "recipe" to u.recipe,
                "contents" to u.contents, "missing" to u.missing, "extra" to u.extra, "certain" to u.candidates.isEmpty(),
                "candidates" to u.candidates)
            val us = e.units.filter { it.orderId == u.orderId }
            val o = e.orders.firstOrNull { it.id == u.orderId }
            if (o != null && us.size == o.items.size && us.all { it.atCheckout })
                e.log.add(t, "order_complete", t, "order" to o.id, "units" to us.map { it.id }, "zone" to z.name)
        }
    }

    private fun evolve(u: OrderUnit, c: String, pt: List<TrackOut>, t: Double) {
        when (u.state) {
            UState.MISSING -> {
                val cust = u.atCheckout && e.customerNear(u.zone, t)
                if (cust && t - u.lostT!! >= p.handoverS) return handOver(u, t)
                if (cust) return  // at the checkout with a customer there: wait, it's probably being handed over
                val cov = u.coveredBy ?: return
                val person = pt.firstOrNull { it.id == cov }
                if (person == null || !touches(person.box, u.lastBox!!)) {
                    u.state = UState.CARRIED; u.carrier = cov
                    e.log.add(u.lostT!!, "unit_carried", t, "unit" to u.id, "order" to u.orderId, "camera" to c, "person" to cov)
                }
            }
            UState.CARRIED -> {
                val last = personLast[c to u.carrier!!]
                if (last == null || last.first < t - p.trackMaxLostS) {
                    if (u.atCheckout && !e.hasCustomerZone(u.zone)) return handOver(u, t)
                    u.state = UState.TRANSIT; u.transitT = last?.first ?: t
                    e.log.add(u.transitT!!, "unit_transit", t, "unit" to u.id, "order" to u.orderId, "from" to c, "zone" to u.zone)
                }
            }
            else -> {}
        }
    }

    private fun handOver(u: OrderUnit, t: Double) {
        u.state = UState.HANDED; u.handedOver = true
        e.log.add(u.lostT ?: t, "unit_handed_over", t, "unit" to u.id, "order" to u.orderId, "zone" to u.zone)
        val o = e.orders.firstOrNull { it.id == u.orderId } ?: return
        val us = e.units.filter { it.orderId == o.id }
        if (us.size == o.items.size && us.all { it.handedOver }) {
            e.log.add(u.lostT ?: t, "handed_over", t, "order" to o.id, "units" to us.map { it.id }, "zone" to u.zone,
                "certain" to us.all { it.candidates.isEmpty() })
            e.log.add(t, "order_summary", t, "order" to o.id, "items" to us.map {
                mapOf("unit" to it.id, "recipe" to it.recipe, "contents" to it.contents, "missing" to it.missing, "extra" to it.extra)
            })
        }
    }

    /** Which unit is this unbound box? Candidates by rule, strongest rule first (spec §4.2-4.3). */
    private fun resolve(c: String, tr: TrackOut, t: Double) {
        val first = t - tr.ageS
        val open = e.units.filter { it.state != UState.HANDED && !(it.state == UState.SURFACE && it.camera == c) }
        val parked = open.filter { it.camera == c && it.state in setOf(UState.PENDING, UState.MISSING, UState.CARRIED) && matchesLast(it, c, tr) }
            .sortedBy { dist(it.lastBox!!, tr.box) }
        val carried = open.filter { u -> u.state == UState.CARRIED && u.camera == c && first >= u.lostT!! - 0.5 &&
            personLast[c to u.carrier!!]?.let { inside(tr.box.cx, tr.box.cy, grow(it.second, p.carryGrow)) } == true }
        val viaMap = maps[c]?.let { m ->
            val (x, y) = m.map(tr.box.cx, tr.box.cy)
            open.filter { u -> u.state == UState.SURFACE && u.camera != c && u.mapPos != null && t - u.mapT <= p.mapTolS &&
                kotlin.math.hypot(u.mapPos!!.first - x, u.mapPos!!.second - y) <= p.mapTolM }
        }.orEmpty()
        val transit = open.filter { u -> (u.state == UState.TRANSIT) && first >= u.transitT!! - 1.0 && arrivesIn(u, c, tr, t) }
            .sortedBy { it.transitT }
        val (how, cands) = listOf("parked" to parked, "carried" to carried, "map" to viaMap, "transit" to transit)
            .firstOrNull { it.second.isNotEmpty() } ?: return unknown(c, tr, t)
        bind(cands.first(), c, tr, t, how, cands)
    }

    private fun bind(u: OrderUnit, c: String, tr: TrackOut, t: Double, how: String, cands: List<OrderUnit>) {
        val was = u.state
        bound[c to tr.id] = u; u.tracks[c] = tr.id
        if (cands.size > 1) {
            u.candidates = cands.mapNotNull { it.orderId }.distinct()
            if (u.candidates.size > 1) {
                e.log.add(t, "unit_uncertain", t, "unit" to u.id, "candidates" to u.candidates, "camera" to c, "how" to how)
                onUncertain?.invoke(u, cands, c, tr, t)
            } else u.candidates = emptyList()  // all candidates belong to the same order: the order is still certain
        }
        when {
            was == UState.PENDING -> e.log.add(t, "unit_tracked", t, "unit" to u.id, "order" to u.orderId, "camera" to c)
            how == "carried" || was == UState.CARRIED && how == "parked" ->
                e.log.add(t, "unit_put_down", t, "unit" to u.id, "order" to u.orderId, "camera" to c)
            how == "transit" -> e.log.add(t, "unit_arrived", t, "unit" to u.id, "order" to u.orderId, "camera" to c,
                "travel_s" to t - u.transitT!!)
            how == "map" -> e.log.add(t, "unit_seen", t, "unit" to u.id, "order" to u.orderId, "camera" to c, "how" to "map")
        }
        seen(u, c, tr.box, t)
    }

    /** A code (QR / order label) read on a box: the box belongs to that order, whatever the rules said. */
    private fun identify(c: String, tr: TrackOut, code: String, t: Double) {
        val cur = bound[c to tr.id]
        if (cur?.orderId == code) { cur.candidates = emptyList(); return }
        val v = e.units.filter { it.orderId == code && it.state != UState.HANDED }
            .minByOrNull { if (it.state == UState.SURFACE && it.camera != null) 1 else 0 }
        if (v == null) {
            if (unknownCodes.add(code)) e.log.add(t, "unknown_code", t, "code" to code, "camera" to c)
            return
        }
        reassign(c, mapOf(tr.id to v), t) {}
        if (cur != null && cur.tracks[c] != null) cur.candidates = listOf(cur.orderId, v.orderId).filterNotNull()
        v.candidates = emptyList()
        e.log.add(t, "unit_identified", t, "unit" to v.id, "order" to code, "camera" to c, "corrected" to (cur != null),
            "was" to cur?.orderId)
    }

    /**
     * Give box tracks to units (track id -> unit) in camera c. The unit that had a track takes the new owner's old
     * track if it had one in this camera (a swap); otherwise `restore` puts it back to what it was (default: missing
     * where it was last seen). Returns whether any binding changed.
     */
    fun reassign(c: String, assign: Map<Int, OrderUnit>, t: Double, restore: (OrderUnit) -> Unit): Boolean {
        var changed = false
        for ((tid, v) in assign) {
            val cur = bound[c to tid]
            if (cur === v) continue
            val box = boxAt(c, tid, t) ?: continue  // the track is gone
            changed = true
            val vOld = v.tracks[c]?.takeIf { bound[c to it] === v }
            if (cur != null && vOld != null) {
                bound[c to vOld] = cur; cur.tracks[c] = vOld
                boxAt(c, vOld, t)?.let { seen(cur, c, it, t) }
            } else if (cur != null) {
                cur.tracks.remove(c); restore(cur)
                if (cur.state == UState.SURFACE && cur.camera == c) { cur.state = UState.MISSING; cur.lostT = t; cur.coveredBy = null }
            }
            vOld?.takeIf { cur == null }?.let { bound.remove(c to it) }
            bound[c to tid] = v; v.tracks[c] = tid
            seen(v, c, box, t)
        }
        return changed
    }

    /** Where track `id` in camera c was at time t (nearest sample within 0.5 s), from the recent history. */
    fun boxAt(c: String, id: Int, t: Double): Box? =
        history[c to id]?.minByOrNull { kotlin.math.abs(it.first - t) }?.takeIf { kotlin.math.abs(it.first - t) <= 0.5 }?.second

    /** When track `id` in camera c was first seen (while it's in the recent history). */
    fun trackStart(c: String, id: Int): Double? = firstSeen[c to id]

    /** Pairs of bound boxes that came close while one person touched both, and have now separated. */
    private fun crossings(c: String, bt: List<TrackOut>, pt: List<TrackOut>, t: Double,
                          cb: (String, Int, OrderUnit, Int, OrderUnit, Double, Double) -> Unit) {
        val bb = bt.filter { (c to it.id) in bound }.sortedBy { it.id }
        val live = mutableSetOf<Triple<String, Int, Int>>()
        for (i in bb.indices) for (j in i + 1 until bb.size) {
            val a = bb[i]; val b = bb[j]
            val key = Triple(c, a.id, b.id); live += key
            val d = dist(a.box, b.box) / maxOf(a.box.size, b.box.size)
            val far = d > 1.5 * p.refereeClose
            when {
                key in crossing -> if (far) {
                    crossing.remove(key)
                    apart[key]?.let { at -> cb(c, a.id, bound[c to a.id]!!, b.id, bound[c to b.id]!!, at, t) }
                    apart[key] = t
                }
                d <= p.refereeClose && pt.any { touches(it.box, a.box) && touches(it.box, b.box) } -> crossing += key
                far -> apart[key] = t
            }
        }
        apart.keys.removeAll { it.first == c && it !in live }
        crossing.removeAll { it.first == c && it !in live }
    }

    private fun unknown(c: String, tr: TrackOut, t: Double) {
        val z = zoneAt(c, tr.box)
        if (z?.role == Role.ASSEMBLY || z?.role == Role.PACKING) return  // boxes being made up; packing binds them
        if (announced.add(c to tr.id)) e.log.add(t, "unknown_box", t, "camera" to c, "zone" to z?.name, "box" to tr.box.toList())
    }

    private fun matchesLast(u: OrderUnit, c: String, tr: TrackOut): Boolean {
        val lb = u.lastBox ?: return false
        if (near(lb, tr.box)) return true
        val then = history[c to tr.id]?.minByOrNull { kotlin.math.abs(it.first - (u.lostT ?: 0.0)) }
        return then != null && kotlin.math.abs(then.first - (u.lostT ?: 0.0)) <= 1.0 && near(lb, then.second)
    }

    private fun arrivesIn(u: OrderUnit, c: String, tr: TrackOut, t: Double): Boolean {
        val target = zoneAt(c, tr.box) ?: e.site.zones.firstOrNull { it.camera == c && e.flowIndex(it.name) > e.flowIndex(u.zone) }
            ?: return false
        if (e.flowIndex(target.name) <= e.flowIndex(u.zone)) return false
        val (lo, hi) = e.travel(u.zone, target.name)
        val dt = t - u.transitT!!
        return dt >= lo - 1e-6 && dt <= hi
    }

    private fun maxTravel(u: OrderUnit): Double {
        val later = e.site.flow.drop(e.flowIndex(u.zone) + 1)
        return later.maxOfOrNull { e.travel(u.zone, it).second } ?: p.defaultTravelMaxS
    }

    private fun visibleElsewhere(u: OrderUnit, c: String, t: Double) =
        u.tracks.keys.any { it != c && (history[it to u.tracks[it]!!]?.last()?.first ?: 0.0) >= t - p.mapTolS }

    private fun zoneAt(c: String, b: Box): Zone? = e.site.zones
        .filter { it.camera == c && it.role != Role.CUSTOMER && e.flowIndex(it.name) >= 0 && e.polys[it.name]!!.contains(b) }
        .maxByOrNull { e.flowIndex(it.name) }

    private fun near(a: Box, b: Box) = iou(a, b) >= p.parkIou || dist(a, b) <= p.parkDist * maxOf(a.size, b.size)
    private fun dist(a: Box, b: Box) = kotlin.math.hypot(a.cx - b.cx, a.cy - b.cy)
    private fun touches(a: Box, b: Box) = a.x0 < b.x1 && a.x1 > b.x0 && a.y0 < b.y1 && a.y1 > b.y0
    private fun inside(x: Double, y: Double, b: Box) = x >= b.x0 && x <= b.x1 && y >= b.y0 && y <= b.y1
    private fun grow(b: Box, f: Double) = Box(b.x0 - f * b.w, b.y0 - f * b.h, b.x1 + f * b.w, b.y1 + f * b.h)
}
