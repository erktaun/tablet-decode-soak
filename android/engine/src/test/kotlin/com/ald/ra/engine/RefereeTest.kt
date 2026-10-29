package com.ald.ra.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Track referee (spec §4.5) on scripted scenes. The fake referee "sees" the truth through `truth(order, box)`, or
 * can't tell (0.5 everywhere), like the Decisions API on identical boxes.
 */
class RefereeTest {
    private val painted = mutableListOf<Sketch>()
    private val painter = Painter { s -> painted += s; "frame".toByteArray() }
    private var calls = 0

    private fun referee(latencyS: Double, truth: ((String?, Box) -> Boolean)?) = RefereeRunner { req, done ->
        calls++
        val probs = req.units.flatMap { (u, order) -> req.boxes.map { (b, box) ->
            "$u=$b" to when { truth == null -> 0.5; truth(order, box) -> 0.9; else -> 0.1 }
        } }.toMap()
        done(RefereeResult(probs, latencyS, 1000, 0.0001, cached = false))
    }

    private fun on(site: Site) = site.copy(params = site.params.copy(referee = true))

    /** A leaves first, B second; in cam1, B's box (x = 600) shows up first, then A's (x = 800). */
    private fun swapOutOfView(): Scene {
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 100.0)).at(3.0, null)
        s.box().at(0.0, Scene.sq(300.0, 100.0)).at(4.0, null)
        s.person().at(1.0, Scene.over(100.0, 100.0)).at(3.0, Scene.over(100.0, 100.0)).at(5.0, Scene.over(1250.0, 100.0)).at(5.2, null)
        s.person().at(2.0, Scene.over(300.0, 100.0)).at(4.0, Scene.over(300.0, 100.0)).at(6.0, Scene.over(1250.0, 300.0)).at(6.2, null)
        s.box("cam1").at(9.0, Scene.sq(600.0, 300.0))
        s.box("cam1").at(10.0, Scene.sq(800.0, 300.0))
        return s
    }
    private val swapTruth = { order: String?, b: Box -> (order == "B") == (b.x0 < 700) }

    private fun engine(site: Site, latencyS: Double, truth: ((String?, Box) -> Boolean)?) =
        CustodyTest.engine(site, "A" to Scene.sq(100.0, 100.0), "B" to Scene.sq(300.0, 100.0),
            referee = referee(latencyS, truth), painter = painter)

    @Test
    fun `out-of-view swap - a referee that can tell fixes it, before or after the second box arrives`() {
        for (lat in listOf(0.6, 2.5)) {
            painted.clear()
            val e = engine(on(CustodyTest.site()), lat, swapTruth)
            swapOutOfView().run(e, 14.0)
            val msg = "latency $lat: ${e.log.events}"
            assertTrue(near(e.unit("B").lastBox, 600.0, 300.0), "B at ${e.unit("B").lastBox} $msg")
            assertTrue(near(e.unit("A").lastBox, 800.0, 300.0), "A at ${e.unit("A").lastBox} $msg")
            assertTrue(e.units.all { it.candidates.isEmpty() && it.atCheckout }, msg)
            val r = e.log.events.single { it.kind == "referee" }
            assertEquals(true, r.data["accepted"], msg); assertEquals(true, r.data["corrected"], msg)
            assertEquals("uncertain", r.data["trigger"])
            assertTrue("unknown_box" !in e.kinds() && "unit_lost" !in e.kinds(), msg)
            // BEFORE frames from cam0 with letters, the AFTER frame from cam1 with numbers
            assertTrue(painted.first().camera == "cam0" && painted.first().labels.isNotEmpty())
            assertTrue(painted.last().camera == "cam1" && painted.last().labels.map { it.first } == listOf("1"))
        }
    }

    @Test
    fun `out-of-view swap - a referee that can't tell leaves it uncertain`() {
        val e = engine(on(CustodyTest.site()), 0.6, null)
        swapOutOfView().run(e, 14.0)
        val r = e.log.events.single { it.kind == "referee" }
        assertEquals(false, r.data["accepted"])
        assertTrue(e.units.any { it.candidates.toSet() == setOf("A", "B") }, e.log.events.toString())
    }

    @Test
    fun `referee is off by default`() {
        val e = engine(CustodyTest.site(), 0.6, swapTruth)
        swapOutOfView().run(e, 14.0)
        assertEquals(0, calls)
        assertTrue("referee" !in e.kinds() && "unit_uncertain" in e.kinds())
    }

    @Test
    fun `boxes pushed together under one person - referee answer swaps the tracks`() {
        // Both boxes slide together under one person and back apart. The tracker keeps its ids; in this fake world the
        // referee saw that they actually swapped places, so A ends up on the right and B on the left.
        val s = Scene()
        s.box().at(0.0, Scene.sq(100.0, 300.0)).at(2.0, Scene.sq(100.0, 300.0)).at(4.0, Scene.sq(185.0, 300.0))
            .at(5.0, Scene.sq(185.0, 300.0)).at(7.0, Scene.sq(100.0, 300.0))
        s.box().at(0.0, Scene.sq(300.0, 300.0)).at(2.0, Scene.sq(300.0, 300.0)).at(4.0, Scene.sq(215.0, 300.0))
            .at(5.0, Scene.sq(215.0, 300.0)).at(7.0, Scene.sq(300.0, 300.0))
        s.person().at(1.5, Box(120.0, 150.0, 340.0, 520.0)).at(7.5, Box(120.0, 150.0, 340.0, 520.0)).at(8.0, null)
        val e = CustodyTest.engine(on(CustodyTest.site()), "A" to Scene.sq(100.0, 300.0), "B" to Scene.sq(300.0, 300.0),
            referee = referee(0.6) { order, b -> (order == "A") == (b.x0 > 200) }, painter = painter)
        s.run(e, 10.0)
        val r = e.log.events.single { it.kind == "referee" }
        assertEquals("crossed", r.data["trigger"], e.log.events.toString())
        assertEquals(true, r.data["corrected"])
        assertTrue(near(e.unit("A").lastBox, 300.0, 300.0), "A at ${e.unit("A").lastBox}")
        assertTrue(near(e.unit("B").lastBox, 100.0, 300.0), "B at ${e.unit("B").lastBox}")
        // DURING frame has no labels; BEFORE shows both boxes apart
        assertEquals(3, painted.size)
        assertTrue(painted[0].labels.size == 2 && painted[1].labels.isEmpty() && painted[2].labels.size == 2)
    }

    @Test
    fun `every one-to-one pairing is considered`() {
        assertEquals(2, RefereeDesk.assignments(listOf("A", "B"), listOf("1", "2")).size)
        assertEquals(2, RefereeDesk.assignments(listOf("A", "B"), listOf("1")).size)
        assertEquals(6, RefereeDesk.assignments(listOf("A", "B", "C"), listOf("1", "2")).size)
        // more boxes than units: one box stays unassigned
        assertEquals(setOf(mapOf("1" to "A"), mapOf("2" to "A")), RefereeDesk.assignments(listOf("A"), listOf("1", "2")).toSet())
    }
}
