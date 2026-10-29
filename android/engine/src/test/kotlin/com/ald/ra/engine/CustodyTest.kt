package com.ald.ra.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scripted scenes for custody (spec §4.4 table). Actors are boxes and people with keyframed positions per camera;
 * a null keyframe hides the actor (covered, carried, or out of view) until its next keyframe.
 */
class Scene {
    class Actor(val cls: String, val cam: String) {
        val keys = mutableListOf<Pair<Double, Box?>>()
        fun at(t: Double, b: Box?) = apply { keys += t to b }

        fun boxAt(t: Double): Box? {
            val i = keys.indexOfLast { it.first <= t + 1e-9 }
            if (i < 0) return null
            val (t0, b0) = keys[i]
            b0 ?: return null
            val next = keys.getOrNull(i + 1) ?: return b0
            val b1 = next.second ?: return b0
            val f = (t - t0) / (next.first - t0)
            return Box(b0.x0 + f * (b1.x0 - b0.x0), b0.y0 + f * (b1.y0 - b0.y0), b0.x1 + f * (b1.x1 - b0.x1), b0.y1 + f * (b1.y1 - b0.y1))
        }
    }

    val actors = mutableListOf<Actor>()
    val codes = mutableListOf<Triple<Double, String, Actor>>()  // from t, the code is readable on this box
    fun box(cam: String = "cam0") = Actor("box", cam).also { actors += it }
    fun person(cam: String = "cam0") = Actor("person", cam).also { actors += it }

    fun run(e: Engine, until: Double, fps: Double = 5.0) {
        val cams = e.site.cameras.map { it.name }
        var k = 0
        while (k / fps <= until + 1e-9) {
            val t = Math.round(k / fps * 1000) / 1000.0
            for (c in cams) {
                val dets = actors.filter { it.cam == c }.mapNotNull { a -> a.boxAt(t)?.let { Det(a.cls, it, 0.9) } }
                val cs = codes.filter { it.third.cam == c && t >= it.first }.mapNotNull { (_, code, a) -> a.boxAt(t)?.let { it to code } }
                e.onFrame(Frame(c, t, dets, codes = cs))
            }
            k++
        }
    }

    companion object {
        fun sq(x: Double, y: Double, s: Double = 60.0) = Box(x, y, x + s, y + s)
        /** A person standing over a 60 px box at (x, y). */
        fun over(x: Double, y: Double) = Box(x - 30, y - 80, x + 90, y + 160)
    }
}

fun Engine.unit(order: String) = units.first { it.orderId == order }
fun Engine.kinds() = log.events.map { it.kind }
fun near(b: Box?, x: Double, y: Double) = b != null && kotlin.math.hypot(b.x0 - x, b.y0 - y) < 15

class CustodyTest {

    @Test
    fun `person walks in front of a still box - parked, same unit`() {
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(2.4, null).at(3.6, Scene.sq(100.0, 100.0))
        s.person().at(1.5, Scene.over(-200.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(4.5, Scene.over(400.0, 100.0)).at(5.0, null)
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0))
        s.run(e, 8.0)
        val a = e.unit("A")
        assertEquals(UState.SURFACE, a.state, e.log.events.toString())
        assertTrue(near(a.lastBox, 100.0, 100.0))
        assertTrue(a.candidates.isEmpty())
        assertTrue("unknown_box" !in e.kinds() && "unit_uncertain" !in e.kinds(), e.kinds().toString())
    }

    @Test
    fun `identical boxes swapped in view, one at a time - both correct`() {
        // A (left) is carried to a spare spot, B (middle) is carried to A's old spot, then A to B's old spot.
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null).at(6.0, Scene.sq(700.0, 400.0)).at(16.0, null).at(19.0, Scene.sq(300.0, 100.0))
        s.box().at(0.0, Scene.sq(300.0, 100.0)).at(9.0, null).at(12.0, Scene.sq(100.0, 100.0))
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(6.0, Scene.over(700.0, 400.0))
            .at(7.0, Scene.over(300.0, 100.0)).at(9.0, Scene.over(300.0, 100.0)).at(12.0, Scene.over(100.0, 100.0))
            .at(14.0, Scene.over(700.0, 400.0)).at(16.0, Scene.over(700.0, 400.0)).at(19.0, Scene.over(300.0, 100.0))
            .at(21.0, Scene.over(900.0, 100.0)).at(22.0, null)
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0), "B" to Scene.sq(300.0, 100.0))
        s.run(e, 25.0)
        assertTrue(near(e.unit("A").lastBox, 300.0, 100.0), "A at ${e.unit("A").lastBox}  ${e.log.events}")
        assertTrue(near(e.unit("B").lastBox, 100.0, 100.0), "B at ${e.unit("B").lastBox}")
        assertTrue(e.units.all { it.candidates.isEmpty() && it.state == UState.SURFACE })
        assertTrue(e.kinds().count { it == "unit_carried" } >= 3 && e.kinds().count { it == "unit_put_down" } >= 3, e.kinds().toString())
    }

    @Test
    fun `two people move two identical boxes at the same time, not crossing - both correct`() {
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null).at(6.0, Scene.sq(100.0, 450.0))
        s.box().at(0.0, Scene.sq(400.0, 100.0)).at(3.0, null).at(6.0, Scene.sq(400.0, 450.0))
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(6.0, Scene.over(100.0, 450.0))
            .at(8.0, Scene.over(-100.0, 600.0)).at(9.0, null)
        s.person().at(1.0, Scene.over(400.0, 100.0)).at(3.0, Scene.over(400.0, 100.0)).at(6.0, Scene.over(400.0, 450.0))
            .at(8.0, Scene.over(600.0, 600.0)).at(9.0, null)
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0), "B" to Scene.sq(400.0, 100.0))
        s.run(e, 12.0)
        assertTrue(near(e.unit("A").lastBox, 100.0, 450.0), "A at ${e.unit("A").lastBox}")
        assertTrue(near(e.unit("B").lastBox, 400.0, 450.0), "B at ${e.unit("B").lastBox}")
        assertTrue(e.units.all { it.candidates.isEmpty() })
    }

    @Test
    fun `one box carried to another camera - arrives, at checkout, order complete`() {
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null)
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(5.0, Scene.over(1250.0, 100.0)).at(5.2, null)
        s.box("cam1").at(9.0, Scene.sq(600.0, 300.0))
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0))
        s.run(e, 12.0)
        val a = e.unit("A")
        assertEquals("cam1", a.camera, e.log.events.toString())
        assertTrue(a.atCheckout && a.candidates.isEmpty())
        assertTrue(listOf("unit_carried", "unit_transit", "unit_arrived", "unit_at_checkout", "order_complete").all { it in e.kinds() }, e.kinds().toString())
    }

    @Test
    fun `two identical boxes swapped out of view between cameras - flagged uncertain, not guessed`() {
        // A leaves first, B second; in the next camera B's box shows up first. Order of arrival can't tell them apart.
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null)
        s.box().at(0.0, Scene.sq(300.0, 100.0)).at(4.0, null)
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(5.0, Scene.over(1250.0, 100.0)).at(5.2, null)
        s.person().at(2.0, Scene.over(300.0, 100.0)).at(4.0, Scene.over(300.0, 100.0)).at(6.0, Scene.over(1250.0, 300.0)).at(6.2, null)
        s.box("cam1").at(9.0, Scene.sq(600.0, 300.0))
        s.box("cam1").at(10.0, Scene.sq(800.0, 300.0))
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0), "B" to Scene.sq(300.0, 100.0))
        s.run(e, 13.0)
        val unc = e.log.events.filter { it.kind == "unit_uncertain" }
        assertTrue(unc.isNotEmpty(), e.kinds().toString())
        assertEquals(setOf("A", "B"), (unc.first().data["candidates"] as List<*>).toSet())
        // the unit at checkout says so too
        assertTrue(e.log.events.any { it.kind == "unit_at_checkout" && it.data["certain"] == false })
    }

    @Test
    fun `QR code read at checkout fixes the swap`() {
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null)
        s.box().at(0.0, Scene.sq(300.0, 100.0)).at(4.0, null)
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(5.0, Scene.over(1250.0, 100.0)).at(5.2, null)
        s.person().at(2.0, Scene.over(300.0, 100.0)).at(4.0, Scene.over(300.0, 100.0)).at(6.0, Scene.over(1250.0, 300.0)).at(6.2, null)
        val first = s.box("cam1").at(9.0, Scene.sq(600.0, 300.0))   // this is really B
        val second = s.box("cam1").at(10.0, Scene.sq(800.0, 300.0)) // this is really A
        s.codes += Triple(11.0, "B", first)
        s.codes += Triple(11.0, "A", second)
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0), "B" to Scene.sq(300.0, 100.0))
        s.run(e, 13.0)
        assertTrue(near(e.unit("B").lastBox, 600.0, 300.0), "B at ${e.unit("B").lastBox} ${e.log.events}")
        assertTrue(near(e.unit("A").lastBox, 800.0, 300.0), "A at ${e.unit("A").lastBox}")
        assertTrue(e.units.all { it.candidates.isEmpty() })
        assertTrue(e.log.events.any { it.kind == "unit_identified" && it.data["corrected"] == true })
    }

    @Test
    fun `box taken at checkout while a customer is there - handed over`() {
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null)
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(5.0, Scene.over(1250.0, 100.0)).at(5.2, null)
        s.box("cam1").at(9.0, Scene.sq(600.0, 300.0)).at(15.0, null)
        s.person("cam1").at(12.0, Box(560.0, 250.0, 700.0, 700.0)).at(18.0, Box(560.0, 250.0, 700.0, 700.0)).at(19.0, null)
        val e = engine(site(), "A" to Scene.sq(100.0, 100.0))
        s.run(e, 24.0)
        assertTrue(e.unit("A").handedOver, e.kinds().toString())
        assertTrue("handed_over" in e.kinds() && "order_summary" in e.kinds())
    }

    @Test
    fun `shared map - a box seen by two overlapping cameras is one unit`() {
        // cam0 x 960-1280 and cam1 x 0-320 cover the same 1 m of counter (see site(maps = true)).
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 300.0)).at(3.0, null).at(6.0, Scene.sq(1100.0, 300.0)).at(10.0, Scene.sq(1100.0, 300.0)).at(10.2, null)
        s.person().at(1.0, Scene.over(100.0, 300.0)).at(3.0, Scene.over(100.0, 300.0)).at(6.0, Scene.over(1100.0, 300.0))
            .at(7.0, Scene.over(700.0, 600.0)).at(8.0, null)
        s.box("cam1").at(8.0, Scene.sq(140.0, 300.0))
        val e = engine(site(maps = true), "A" to Scene.sq(100.0, 300.0))
        s.run(e, 14.0)
        val a = e.unit("A")
        assertEquals("cam1", a.camera, e.log.events.toString())
        assertTrue(a.atCheckout && a.candidates.isEmpty())
        assertTrue(e.log.events.any { it.kind == "unit_seen" && it.data["how"] == "map" }, e.kinds().toString())
        assertTrue("unknown_box" !in e.kinds())
    }

    companion object {
        fun site(maps: Boolean = false, customer: Boolean = true): Site {
            val m0 = MapPoints(listOf(listOf(0.0, 0.0), listOf(1280.0, 0.0), listOf(1280.0, 720.0), listOf(0.0, 720.0)),
                listOf(listOf(0.0, 0.0), listOf(4.0, 0.0), listOf(4.0, 2.25), listOf(0.0, 2.25)))
            val m1 = MapPoints(m0.image, listOf(listOf(3.0, 0.0), listOf(7.0, 0.0), listOf(7.0, 2.25), listOf(3.0, 2.25)))
            val zones = mutableListOf(
                Zone("pack", "cam0", Role.PACKING, listOf(listOf(0.0, 0.0), listOf(500.0, 0.0), listOf(500.0, 720.0), listOf(0.0, 720.0))),
                Zone("shelf", "cam0", Role.HOLD, listOf(listOf(500.0, 0.0), listOf(1280.0, 0.0), listOf(1280.0, 720.0), listOf(500.0, 720.0))),
                Zone("out", "cam1", Role.CHECKOUT, null))
            if (customer) zones += Zone("cust", "cam1", Role.CUSTOMER,
                listOf(listOf(0.0, 600.0), listOf(1280.0, 600.0), listOf(1280.0, 720.0), listOf(0.0, 720.0)), forZone = "out")
            return Site(site = "custody", zones = zones, flow = listOf("pack", "shelf", "out"),
                cameras = listOf(Camera("cam0", "file:none", 1280, 720, 5.0, map = if (maps) m0 else null),
                    Camera("cam1", "file:none", 1280, 720, 5.0, map = if (maps) m1 else null)),
                links = listOf(Link("shelf", "out", listOf(0.0, 30.0)), Link("pack", "out", listOf(0.0, 30.0))),
                classes = Classes(base = listOf("pizza"), closedContainer = listOf("box")))
        }

        /** An engine with one packed unit per order, waiting where its box is at t = 0. */
        fun engine(site: Site, vararg units: Pair<String, Box>, referee: RefereeRunner? = null, painter: Painter? = null): Engine {
            val orders = units.map { Order(it.first, listOf(OrderItem("Margherita"))) }
            val e = Engine(site, Fixtures.menu, orders, Fixtures.noChecks, refereeRunner = referee, painter = painter)
            units.forEachIndexed { i, (oid, b) ->
                e.units += OrderUnit(i + 1, listOf("mozzarella", "tomato sauce"), "pack", 0.0).apply {
                    orderId = oid; item = 0; recipe = "Margherita"; packedT = 0.0; pending("cam0", b, 0.0)
                }
            }
            return e
        }
    }
}
