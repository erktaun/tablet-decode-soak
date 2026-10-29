package com.ald.ra.engine

/**
 * One analysed frame from one camera. `occ` is the per-zone occupancy (fraction of pixels differing from the
 * empty background frame); only needed for the fallback when closed containers aren't detected. `crop` makes a
 * JPEG of a box with other boxes greyed out, for ingredient checks; null in replays that use recorded answers.
 */
class Frame(
    val camera: String,
    val t: Double,
    val dets: List<Det>,
    val occ: Map<String, Double> = emptyMap(),
    val crop: ((box: Box, mask: List<Box>) -> ByteArray?)? = null,
    /** Codes (QR / order labels) read in this frame, with where they were read. */
    val codes: List<Pair<Box, String>> = emptyList(),
)

/** Runs ingredient checks. The replay runs them inline; the app runs them on a worker thread. */
fun interface CheckRunner {
    fun submit(req: CheckRequest, done: (CheckResult?) -> Unit)
}

/**
 * A unit: one dish (later in its container) carrying its order and contents from assembly on. The order assignment
 * can still change until the unit is packed (see [Engine.assignOrders]).
 */
class OrderUnit(
    val id: Int,
    val contents: List<String>,
    var zone: String,
    var leftT: Double,
) {
    var orderId: String? = null
    var item: Int? = null
    var recipe: String? = null
    var missing: List<String> = emptyList()
    var extra: List<String> = emptyList()
    var packedT: Double? = null
    var atCheckout = false
    var handedOver = false
    // custody (spec §4.2)
    var state = UState.PENDING
    var camera: String? = null
    var lastBox: Box? = null
    var lastSeenT = 0.0
    var lostT: Double? = null
    var coveredBy: Int? = null
    var carrier: Int? = null
    var transitT: Double? = null
    var mapPos: Pair<Double, Double>? = null
    var mapT = 0.0
    /** Order ids this unit might belong to, when custody isn't sure; empty = certain. */
    var candidates: List<String> = emptyList()
    val tracks = mutableMapOf<String, Int>()

    /** Custody starts: the unit was last seen at `box` in `camera` (packing, or a finished base in in_container mode). */
    fun pending(camera: String, box: Box, t: Double) {
        state = UState.PENDING; this.camera = camera; lastBox = box; lostT = t; lastSeenT = t
    }
}

/**
 * The restaurant-analytics engine: feed it frames (any camera, in time order); it emits events.
 * Stations are built from the site's zones and roles (docs/analytics/spec.md §3–5).
 */
class Engine(
    val site: Site,
    val menu: Menu,
    orders: List<Order>,
    private val runner: CheckRunner,
    val log: EventLog = EventLog(),
    refereeRunner: RefereeRunner? = null,
    painter: Painter? = null,
) {
    val p = site.params
    val orders = orders.sortedBy { it.t }.toMutableList()
    private val zonesByName = site.zones.associateBy { it.name }
    val polys = site.zones.associate { it.name to Poly(it.poly) }
    private val ignores = site.cameras.associate { c -> c.name to c.ignore.map { Poly(it) } }

    val units = mutableListOf<OrderUnit>()

    private val assembly = site.zones.filter { it.role == Role.ASSEMBLY }.map { AssemblyStation(this, it) }
    private val packing = site.zones.filter { it.role == Role.PACKING }.map { PackingStation(this, it) }
    private val checkout = site.zones.filter { it.role == Role.CHECKOUT }.map { z ->
        CheckoutStation(this, z, site.zones.firstOrNull { it.role == Role.CUSTOMER && (it.forZone == z.name || it.forZone == null) })
    }
    val custody = Custody(this)
    /** The track referee, when `params.referee` is on and the host can draw frames and make calls. */
    val referee = if (p.referee && refereeRunner != null && painter != null) RefereeDesk(this, refereeRunner, painter).also { d ->
        custody.onUncertain = d::uncertain; custody.onCrossed = d::crossed
    } else null

    fun customerNear(checkoutZone: String, t: Double) = checkout.firstOrNull { it.zone.name == checkoutZone }?.customerNear(t) == true
    fun hasCustomerZone(checkoutZone: String) = checkout.firstOrNull { it.zone.name == checkoutZone }?.hasCustomerZone == true

    private class Ready(val readyT: Double, val apply: () -> Unit)
    private val ready = mutableListOf<Ready>()
    private var now = 0.0

    fun flowIndex(zone: String) = site.flow.indexOf(zone)

    fun travel(from: String, to: String): Pair<Double, Double> =
        site.links.firstOrNull { it.from == from && it.to == to }?.let { it.travelS[0] to it.travelS[1] }
            ?: (0.0 to p.defaultTravelMaxS)

    fun cls(role: List<String>, d: Det) = d.cls in role

    fun onFrame(f: Frame) {
        now = f.t
        applyReady(f.t)
        val ign = ignores[f.camera].orEmpty()
        val dets = if (ign.isEmpty()) f.dets else f.dets.filter { d -> ign.none { it.contains(d.box) } }
        val ff = Frame(f.camera, f.t, dets, f.occ, f.crop, f.codes)
        for (s in assembly) if (s.zone.camera == f.camera) s.onFrame(ff)
        for (s in packing) if (s.zone.camera == f.camera) s.onFrame(ff)
        for (s in checkout) if (s.zone.camera == f.camera) s.onFrame(ff)
        if (custody.active()) custody.onFrame(ff)
    }

    /** Schedule a check result to be applied once the stream reaches the time a live system would have it. */
    fun submit(req: CheckRequest, apply: (CheckResult?) -> Unit) {
        runner.submit(req) { res ->
            synchronized(ready) { ready += Ready(req.t + (res?.latencyS ?: 0.0)) { apply(res) } }
        }
    }

    /** Same for a referee call. */
    fun later(runner: RefereeRunner, req: RefereeRequest, apply: (RefereeResult?) -> Unit) {
        runner.submit(req) { res ->
            synchronized(ready) { ready += Ready(req.t + (res?.latencyS ?: 0.0)) { apply(res) } }
        }
    }

    private fun applyReady(t: Double) {
        val due = synchronized(ready) {
            val d = ready.filter { it.readyT <= t }.sortedBy { it.readyT }
            ready.removeAll(d); d
        }
        for (r in due) r.apply()
    }

    /** End of stream: apply outstanding results, close open episodes, report orders that never left. */
    fun finish(t: Double) {
        applyReady(Double.MAX_VALUE)
        for (s in assembly) s.finish(t)
        for (s in packing) s.finish(t)
        for (s in checkout) s.finish(t)
        for (o in orders) {
            val us = units.filter { it.orderId == o.id }
            if (us.isNotEmpty() && us.any { !it.handedOver })
                log.add(t, "order_uncertain", t, "order" to o.id, "note" to "no hand-over seen by the end of the stream")
        }
    }

    /** Expected ingredients of one order item, with "no X" / "+X" modifiers applied. */
    fun expected(item: OrderItem): List<String> {
        val r = menu.recipes.firstOrNull { it.name == item.recipe }?.ingredients.orEmpty().toMutableList()
        for (m in item.modifiers) {
            if (m.startsWith("no ")) r.remove(m.removePrefix("no ").trim())
            if (m.startsWith("+")) m.removePrefix("+").trim().let { if (it !in r) r += it }
        }
        return r
    }

    /**
     * Assign finished dishes to order items (spec §3.7): every unpacked unit is (re)matched together with the new one,
     * trying every pairing, to maximise the total score (matching ingredients - extra ingredients). Ties go to older
     * orders, then earlier items. Packed units keep their order. Emits the new unit's verdict, and a
     * `dish_reassigned` event for any earlier unit whose order changed.
     */
    fun assignOrders(newUnit: OrderUnit, t: Double, known: Double) {
        val fixed = units.filter { it.packedT != null && it.orderId != null }.map { it.orderId to it.item }.toSet()
        val slots = orders.flatMapIndexed { oi, o -> o.items.indices.map { i -> Triple(oi, o, i) } }
            .filter { (_, o, i) -> (o.id to i) !in fixed }
        // Dishes with no confirmed ingredient are no evidence for any order (often a detection fragment): left out.
        val free = units.filter { it.packedT == null && !it.handedOver && it.contents.isNotEmpty() }
        val score = { u: OrderUnit, s: Triple<Int, Order, Int> ->
            val exp = expected(s.second.items[s.third]).toSet(); val f = u.contents.toSet()
            (exp intersect f).size - (f - exp).size
        }
        // Exhaustive search over injective unit -> slot maps (a unit may also get no slot when slots run out).
        var best: List<Int>? = null
        var bestKey = Pair(Int.MIN_VALUE, Int.MAX_VALUE)
        fun rec(k: Int, used: BooleanArray, cur: IntArray, sc: Int, age: Int) {
            if (k == free.size) {
                if (sc > bestKey.first || (sc == bestKey.first && age < bestKey.second)) { bestKey = sc to age; best = cur.toList() }
                return
            }
            var any = false
            for (j in slots.indices) if (!used[j]) {
                any = true
                used[j] = true; cur[k] = j
                rec(k + 1, used, cur, sc + score(free[k], slots[j]), age + slots[j].first * 100 + slots[j].third)
                used[j] = false
            }
            if (!any) { cur[k] = -1; rec(k + 1, used, cur, sc, age) }
        }
        if (free.size <= 6 && slots.size <= 12) rec(0, BooleanArray(slots.size), IntArray(free.size), 0, 0)
        else best = free.map { u -> slots.indices.maxByOrNull { score(u, slots[it]) } ?: -1 }  // too big: greedy
        if (newUnit.contents.isEmpty()) log.add(t, "dish_verdict", known, "dish" to newUnit.id, "order" to null, "recipe" to null,
            "found" to emptyList<String>(), "missing" to emptyList<String>(), "extra" to emptyList<String>(),
            "note" to "no ingredient confirmed; not matched to an order")
        for ((k, u) in free.withIndex()) {
            val j = best!![k]
            val s = slots.getOrNull(j)
            val (oid, item) = (s?.second?.id) to s?.third
            val changed = u !== newUnit && (oid != u.orderId || item != u.item)
            val exp = s?.let { expected(it.second.items[it.third]) }.orEmpty()
            u.orderId = oid; u.item = item; u.recipe = s?.let { it.second.items[it.third].recipe }
            u.missing = exp.filter { it !in u.contents }; u.extra = u.contents.filter { it !in exp }
            if (u === newUnit || changed)
                log.add(t, if (u === newUnit) "dish_verdict" else "dish_reassigned", known, "dish" to u.id, "order" to oid,
                    "recipe" to u.recipe, "found" to u.contents.sorted(), "missing" to u.missing, "extra" to u.extra)
        }
    }

    /** Units that left an earlier zone and could arrive in `zone` at time t (oldest first). */
    fun pendingFor(zone: String, t: Double, filter: (OrderUnit) -> Boolean = { true }): List<OrderUnit> {
        val zi = flowIndex(zone)
        return units.filter { u ->
            val ui = flowIndex(u.zone)
            val (lo, hi) = travel(u.zone, zone)
            ui in 0 until zi && filter(u) && t - u.leftT >= lo - 1e-6 && t - u.leftT <= hi
        }.sortedBy { it.leftT }
    }
}

/** Ingredient tracking at one assembly zone (spec §3). */
class AssemblyStation(private val e: Engine, val zone: Zone) {
    private val p = e.p
    private val poly = e.polys[zone.name]!!
    private val baseName = e.site.classes.base.firstOrNull() ?: "dish"

    inner class Base(var box: Box, val first: Double) {
        var id: Int? = null
        var last = first
        var anchor = box
        var stillSince = first
        var nextCheck = 0.0
        var bestConf = 0.0
        var inFlight = 0
        var done = false
        val streak = mutableMapOf<String, Int>()
        val confirmed = linkedMapOf<String, Double>()
        val links = mutableListOf<Double>()
    }

    val bases = mutableListOf<Base>()
    private var nextId = 1

    fun onFrame(f: Frame) {
        val dets = f.dets.filter { e.cls(e.site.classes.base, it) && poly.contains(it.box) }
        val allBases = f.dets.filter { e.cls(e.site.classes.base, it) }.map { it.box }
        val active = bases.filter { !it.done }
        val seen = mutableListOf<Pair<Base, Det>>()
        for (d in dets) {
            val best = active.maxByOrNull { iou(it.box, d.box) }
            val b = if (best != null && iou(best.box, d.box) >= p.baseIou) best else Base(d.box, f.t).also { bases += it }
            b.box = d.box; b.last = f.t
            if (kotlin.math.hypot(d.box.cx - b.anchor.cx, d.box.cy - b.anchor.cy) > p.checkStillShift * d.box.size) {
                b.anchor = d.box; b.stillSince = f.t
            }
            seen += b to d
        }
        for (b in bases.filter { it.id == null && !it.done }) {
            if (b.last - b.first >= p.baseMinS) start(b, f.t)
        }
        // A candidate that was merged into a moved base is gone from the list; drop stale candidates.
        bases.removeAll { it.id == null && f.t - it.last > p.baseGoneS }
        for ((b, d) in seen) maybeCheck(b, d, f, allBases)
        if (e.site.assembly.done == "leaves_zone") {
            for (b in bases.filter { it.id != null && !it.done && it.inFlight == 0 && f.t - it.last > p.baseGoneS }) done(b, f.t)
        }
    }

    /** A base seen long enough: either the continuation of a base that moved along the line, or a new dish. */
    private fun start(b: Base, t: Double) {
        // A finished dish that reappears at the same spot, before it was packed or moved on: the detector lost it for a
        // while (bare dough is hard to see). Resume it and withdraw its verdict.
        val back = bases.filter { o -> o.id != null && o.done && b.first - o.last <= p.baseResumeS && near(o.box, b.box) &&
            e.units.firstOrNull { it.id == o.id }?.let { it.packedT == null && it.zone == zone.name && !it.atCheckout } == true }
            .maxByOrNull { it.last }
        if (back != null) {
            back.done = false; back.box = b.box; back.last = b.last; back.anchor = b.anchor; back.stillSince = b.stillSince
            back.bestConf = maxOf(back.bestConf, b.bestConf)
            bases.remove(b)
            e.units.removeAll { it.id == back.id }
            e.log.add(b.first, "dish_resumed", t, "dish" to back.id, "zone" to zone.name, "note" to "verdict withdrawn")
            return
        }
        val (nx, ny) = nextZoneCentroid()
        val dist = { x: Box -> kotlin.math.hypot(x.cx - nx, x.cy - ny) }
        val prev = bases.filter { it.id != null && !it.done && it !== b && it.last < b.first && dist(b.box) < dist(it.box) }
            .maxByOrNull { it.last }
        if (prev != null) {
            prev.box = b.box; prev.last = b.last; prev.anchor = b.anchor; prev.stillSince = b.stillSince
            prev.links += b.first
            bases.remove(b)
            return
        }
        b.id = nextId++
        e.log.add(b.first, "dish_started", t, "dish" to b.id, "zone" to zone.name)
    }

    private fun near(a: Box, c: Box) = iou(a, c) >= p.baseIou || kotlin.math.hypot(a.cx - c.cx, a.cy - c.cy) <= 0.5 * maxOf(a.size, c.size)

    private fun nextZoneCentroid(): Pair<Double, Double> {
        val cam = e.site.cameras.first { it.name == zone.camera }
        val i = e.flowIndex(zone.name)
        val next = e.site.flow.getOrNull(i + 1)?.let { n -> e.site.zones.first { it.name == n } }
        return if (next != null && next.camera == zone.camera) e.polys[next.name]!!.centroid(cam.width.toDouble(), cam.height.toDouble())
        else poly.centroid(cam.width.toDouble(), cam.height.toDouble())
    }

    private fun maybeCheck(b: Base, d: Det, f: Frame, allBases: List<Box>) {
        b.bestConf = maxOf(b.bestConf, d.conf)
        val gate = if (p.checkRelConf > 0) minOf(p.checkMinConf, p.checkRelConf * b.bestConf) else p.checkMinConf
        if (b.id == null || b.done || f.t < b.nextCheck || d.conf < gate) return
        if (p.checkStillS > 0 && f.t - b.stillSince < p.checkStillS) return
        b.nextCheck = f.t + p.checkEveryS
        val jpeg = f.crop?.invoke(d.box, allBases.filter { it != d.box })
        b.inFlight++
        val id = b.id!!
        e.submit(CheckRequest(id, f.t, jpeg)) { res ->
            b.inFlight--
            if (res == null) return@submit
            val known = f.t + res.latencyS
            e.log.add(f.t, "ingredient_check", known, "dish" to id, "probs" to res.probs, "latency_s" to res.latencyS,
                "cost_usd" to res.costUsd)
            for ((name, prob) in res.probs) {
                b.streak[name] = if (prob >= p.ingredientP) (b.streak[name] ?: 0) + 1 else 0
                if (b.streak[name]!! >= p.ingredientVotes && name !in b.confirmed) {
                    b.confirmed[name] = known
                    e.log.add(f.t, "ingredient_confirmed", known, "dish" to id, "ingredient" to name, "p" to prob)
                }
            }
        }
    }

    /** The dish is finished: hand it on as a unit and match it to an order (with every other unpacked dish). */
    fun done(b: Base, t: Double) {
        b.done = true
        val known = maxOf(t, b.confirmed.values.maxOrNull() ?: 0.0)
        e.log.add(b.last, "dish_done", known, "dish" to b.id, "zone" to zone.name, "moved_at" to b.links)
        val u = OrderUnit(b.id!!, b.confirmed.keys.sorted(), zone.name, b.last)
        if (e.site.assembly.mode == "in_container") u.pending(zone.camera, b.box, b.last)
        e.units += u
        e.assignOrders(u, b.last, known)
    }

    fun finish(t: Double) {
        for (b in bases.filter { it.id != null && !it.done }) done(b, t)
    }
}

/**
 * Packing (spec §4.1): a base inside an open container for long enough = dish in container; the episode ending
 * (the dish disappears under the lid) = unit packed. Units are taken first in, first out from those in transit.
 */
class PackingStation(private val e: Engine, val zone: Zone) {
    private val p = e.p
    private val poly = e.polys[zone.name]!!

    private inner class Ep(val start: Double, var last: Double) { var unit: OrderUnit? = null; var box: Box? = null }
    private var ep: Ep? = null

    fun onFrame(f: Frame) {
        val opens = f.dets.filter { e.cls(e.site.classes.openContainer, it) }.map { it.box }
        val inZone = f.dets.filter { e.cls(e.site.classes.base, it) && poly.contains(it.box) }
        // Starting an episode needs the base inside an open container; once started, the base in the zone is enough
        // (the open box is often missed while the food is slid in and the lid folded).
        val inBox = inZone.any { d -> e.site.classes.openContainer.isEmpty() ||
            opens.any { o -> grow(o, 0.1).let { it.x0 <= d.box.cx && d.box.cx <= it.x1 && it.y0 <= d.box.cy && d.box.cy <= it.y1 } } }
        val cur = ep
        if (cur != null && f.t - cur.last > p.packGapS) { close(cur, f.t); ep = null }
        val flag = if (ep == null) inBox else inZone.isNotEmpty()
        if (flag) {
            val c = ep ?: Ep(f.t, f.t).also { ep = it }
            c.last = f.t
            c.box = inZone.maxByOrNull { it.conf }?.box ?: c.box
            if (c.unit == null && c.last - c.start >= p.packMinS) {
                val u = e.pendingFor(zone.name, c.start) { it.packedT == null && it.zone != zone.name }.firstOrNull()
                if (u == null) e.log.add(c.start, "packing_unmatched", f.t, "zone" to zone.name,
                    "note" to "a dish went into a container, but no finished dish was waiting")
                else {
                    c.unit = u
                    u.zone = zone.name
                    e.log.add(c.start, "dish_in_container", f.t, "unit" to u.id, "order" to u.orderId, "zone" to zone.name)
                }
            }
        }
    }

    private fun close(c: Ep, t: Double) {
        val u = c.unit ?: return
        u.packedT = c.last; u.leftT = c.last
        if (e.custody.active()) c.box?.let { u.pending(zone.camera, it, c.last) }
        e.log.add(c.last, "unit_packed", t, "unit" to u.id, "order" to u.orderId, "zone" to zone.name)
    }

    fun finish(t: Double) { ep?.let { close(it, t) }; ep = null }

    private fun grow(b: Box, f: Double) = Box(b.x0 - f * b.w, b.y0 - f * b.h, b.x1 + f * b.w, b.y1 + f * b.h)
}

/**
 * Checkout (spec §5). With closed containers detected, units are followed into the zone (custody, day 2).
 * Fallback (no closed-container class): the zone's occupancy drops while a customer is present, and an order whose
 * units are all packed is handed over, oldest first. Events from the fallback are marked `inferred`.
 */
class CheckoutStation(private val e: Engine, val zone: Zone, private val customer: Zone?) {
    private val p = e.p
    private val cam = e.site.cameras.first { it.name == zone.camera }
    private val w = maxOf(1, Math.round(p.occWindowS * cam.fps).toInt())
    private val ts = mutableListOf<Double>()
    private val occ = mutableListOf<Double>()
    private var run: Pair<Int, Double>? = null  // index of the biggest step so far, its value
    private val custT = mutableListOf<Double>()
    private var custEp: Pair<Double, Double>? = null
    private var custAnnounced = false
    private val drops = mutableListOf<Double>()

    fun onFrame(f: Frame) {
        customerFrame(f)
        if (e.site.classes.closedContainer.isNotEmpty()) return  // tracked path: day 2
        val o = f.occ[zone.name] ?: return
        ts += f.t; occ += o
        val i = occ.size - w  // centre of the window that just completed
        if (i >= w) {
            val step = median(occ.subList(i - w, i)) - median(occ.subList(i, i + w))
            val r = run
            if (step >= p.occDrop) { if (r == null || step > r.second) run = i to step }
            else if (r != null) { drops += ts[r.first]; run = null }
        }
        decideDrops(f.t)
    }

    private fun customerFrame(f: Frame) {
        val cz = customer ?: return
        val poly = e.polys[cz.name]!!
        val zb = bbox(cz) ?: return
        val present = f.dets.any { d -> e.cls(e.site.classes.person, d) &&
            (if (poly.whole) true else d.box.x0 < zb.x1 && d.box.x1 > zb.x0 && d.box.y0 < zb.y1 && d.box.y1 > zb.y0) }
        val ep = custEp
        if (ep != null && f.t - ep.second > p.customerGoneS) {
            if (custAnnounced) e.log.add(ep.second, "customer_left", f.t, "zone" to cz.name)
            custEp = null; custAnnounced = false
        }
        if (present) {
            custT += f.t
            val c = custEp ?: (f.t to f.t)
            custEp = c.first to f.t
            if (!custAnnounced && f.t - c.first >= p.customerMinS) {
                custAnnounced = true
                e.log.add(c.first, "customer_arrived", f.t, "zone" to cz.name)
            }
        }
    }

    private fun decideDrops(t: Double) {
        val it = drops.iterator()
        while (it.hasNext()) {
            val td = it.next()
            val near = custT.any { kotlin.math.abs(it - td) <= p.customerWindowS }
            if (!near && t < td + p.customerWindowS) continue  // a customer may still turn up
            it.remove()
            val order = e.orders.firstOrNull { o ->
                val us = e.units.filter { u -> u.orderId == o.id }
                us.size == o.items.size && us.all { u -> !u.handedOver && (u.packedT ?: Double.MAX_VALUE) <= td }
            }
            if (near && order != null) {
                val us = e.units.filter { u -> u.orderId == order.id }
                us.forEach { u -> u.handedOver = true; u.zone = zone.name }
                e.log.add(td, "handed_over", t, "order" to order.id, "units" to us.map { u -> u.id }, "zone" to zone.name, "inferred" to true)
            } else {
                e.log.add(td, "checkout_changed", t, "zone" to zone.name, "note" to "something left the checkout" +
                    (if (near) "" else " (no customer)") + (if (order == null) " (no order fully packed yet)" else ""))
            }
        }
    }

    val hasCustomerZone get() = customer != null
    fun customerNear(t: Double) = custT.any { kotlin.math.abs(it - t) <= p.customerWindowS }

    fun finish(t: Double) {
        run?.let { drops += ts[it.first] }; run = null
        decideDrops(Double.MAX_VALUE)
        custEp?.let { if (custAnnounced) e.log.add(it.second, "customer_left", t, "zone" to customer!!.name) }
    }

    private fun bbox(z: Zone): Box? {
        val pts = z.poly ?: return Box(0.0, 0.0, 1e9, 1e9)
        return Box(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] })
    }

    private fun median(l: List<Double>): Double {
        val s = l.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
