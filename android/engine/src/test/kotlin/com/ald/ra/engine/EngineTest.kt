package com.ald.ra.engine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteTrackTest {
    @Test
    fun `carried box keeps its id at 5 fps`() {
        // A 60 px box moving 40 px per frame: consecutive boxes overlap by IoU 0.2, below the match threshold,
        // so only the Kalman prediction keeps the id once the velocity is learned.
        val bt = ByteTrack(Params(), fps = 5.0)
        val ids = (0 until 15).map { i ->
            val x = 100.0 + i * 40
            bt.update(listOf(Det("box", Box(x, 100.0, x + 60, 160.0), 0.9)), i / 5.0).map { it.id }
        }
        val later = ids.drop(5).flatten().toSet()
        assertEquals(1, later.size, "ids after warm-up: $ids")
    }

    @Test
    fun `two still boxes keep separate ids through a short miss`() {
        val bt = ByteTrack(Params(), fps = 5.0)
        val a = Box(100.0, 100.0, 160.0, 160.0); val b = Box(300.0, 100.0, 360.0, 160.0)
        var t = 0.0
        repeat(5) { bt.update(listOf(Det("box", a, 0.9), Det("box", b, 0.9)), t); t += 0.2 }
        repeat(3) { bt.update(listOf(Det("box", b, 0.9)), t); t += 0.2 }  // a hidden for 0.6 s
        val out = bt.update(listOf(Det("box", a, 0.9), Det("box", b, 0.9)), t)
        assertEquals(setOf(1, 2), out.map { it.id }.toSet())
    }
}

/** The pizza clip replayed on Python's recorded detections and Decisions answers must give the Python results. */
class PizzaParityTest {
    /** Python's check gate and timings, so the recorded answers line up with the checks the engine asks for. */
    private val PYTHON_PARAMS = """{"checkEveryS":10,"checkStillS":0,"checkRelConf":0,"baseGoneS":6}"""

    private val repo = File("../..").absoluteFile.normalize()  // tablet-soak/ (tests run in android/engine)
    private val res = File("src/test/resources/pizza_trailer")

    @Test
    fun `pizza trailer matches python`() = expectPythonEvents("pizza_trailer")

    /** The same clip played as two cameras (sites/pizza_trailer_2cam): the dish crosses from camA to camB. */
    @Test
    fun `pizza trailer as two cameras gives the same events`() = expectPythonEvents("pizza_trailer_2cam")

    private fun expectPythonEvents(site: String) {
        val out = File(repo, "out/test_$site")
        main(arrayOf("--site", File(repo, "sites/$site").path, "--detections", File(res, "detections.json").path,
            "--checker", "recorded:${File(res, "decision_calls.jsonl").path}", "--out", out.path,
            "--params", PYTHON_PARAMS))
        val ev = File(out, "events.jsonl").readLines()
        fun has(kind: String, t: Double, vararg parts: String) = assertTrue(
            ev.any { l -> "\"kind\":\"$kind\"" in l && parts.all { it in l } &&
                abs(Regex("\"t\":([0-9.]+)").find(l)!!.groupValues[1].toDouble() - t) <= 1.0 }, "$kind at $t ${parts.toList()}")
        has("dish_verdict", 224.6, "\"recipe\":\"Prosciutto cotto\"", "\"missing\":[]", "\"extra\":[]")
        has("dish_verdict", 259.0, "\"recipe\":\"Margherita\"", "\"missing\":[]", "\"extra\":[]")
        has("unit_packed", 317.0, "\"unit\":2")
        has("unit_packed", 360.8, "\"unit\":1")
        has("handed_over", 373.0, "\"order\":\"A-2041\"")
        assertTrue(ev.none { "order_uncertain" in it })
    }
}

/** Small sites built in code, for scenario tests. */
object Fixtures {
    val menu = Menu(
        listOf(Ingredient("tomato sauce", "red sauce"), Ingredient("mozzarella", "white cheese"), Ingredient("cooked ham", "pink slices"),
            Ingredient("mushrooms", "sliced mushrooms")),
        listOf(Recipe("Prosciutto cotto", listOf("tomato sauce", "mozzarella", "cooked ham")),
            Recipe("Margherita", listOf("tomato sauce", "mozzarella")),
            Recipe("Funghi", listOf("tomato sauce", "mozzarella", "mushrooms"))))

    fun site(vararg zones: Zone, flow: List<String> = zones.filter { it.role != Role.CUSTOMER }.map { it.name },
             closed: List<String> = emptyList(), cams: List<String> = listOf("cam0")) = Site(
        site = "test", cameras = cams.map { Camera(it, "file:none", 1280, 720) }, zones = zones.toList(), flow = flow,
        classes = Classes(base = listOf("pizza"), openContainer = listOf("open box"), closedContainer = closed))

    val noChecks = CheckRunner { _, done -> done(null) }
}

class AssemblyTest {
    @Test
    fun `a dish slid along the line keeps its id`() {
        // The phone run of 2026-10-29 (pizza_trailer, 233.8-235.4 s): the Margherita slid into the spot the finished
        // Prosciutto had left, overlapping its own last box by IoU 0.29 in one frame. It must stay the same dish, not
        // become a new base that "resumes" the Prosciutto.
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY)), Fixtures.menu,
            listOf(Order("A-1", listOf(OrderItem("Prosciutto cotto"), OrderItem("Margherita")))), Fixtures.noChecks)
        val s = Scene()
        Scene.Actor("pizza", "cam0").also { s.actors += it }
            .at(0.0, Box(440.0, 400.0, 520.0, 455.0)).at(20.0, null)
        Scene.Actor("pizza", "cam0").also { s.actors += it }
            .at(5.0, Box(386.0, 488.0, 479.0, 561.0)).at(30.0, Box(383.0, 485.0, 477.0, 548.0))
            .at(30.2, Box(409.0, 464.0, 498.0, 522.0)).at(31.6, Box(441.0, 398.0, 507.0, 441.0)).at(60.0, Box(441.0, 398.0, 507.0, 441.0))
        s.run(e, 60.0)
        assertEquals(2, e.kinds().count { it == "dish_started" })
        assertTrue("dish_resumed" !in e.kinds(), e.log.events.joinToString("\n") { "${it.t} ${it.kind} ${it.data}" })
    }
}

class PackingTest {
    @Test
    fun `an unmatched packing is reported once`() {
        // A pizza sits in an open box in the boxing zone for 10 s, with no finished dish waiting.
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY, listOf(listOf(0.0, 0.0), listOf(400.0, 0.0), listOf(400.0, 720.0), listOf(0.0, 720.0))),
            Zone("b", "cam0", Role.PACKING, listOf(listOf(600.0, 0.0), listOf(1280.0, 0.0), listOf(1280.0, 720.0), listOf(600.0, 720.0)))),
            Fixtures.menu, emptyList(), Fixtures.noChecks)
        val s = Scene()
        Scene.Actor("open box", "cam0").also { s.actors += it }.at(0.0, Box(700.0, 300.0, 820.0, 420.0)).at(10.0, Box(700.0, 300.0, 820.0, 420.0))
        Scene.Actor("pizza", "cam0").also { s.actors += it }.at(0.0, Box(720.0, 320.0, 800.0, 400.0)).at(10.0, Box(720.0, 320.0, 800.0, 400.0))
        s.run(e, 10.0)
        assertEquals(1, e.kinds().count { it == "packing_unmatched" })
    }
}

class OrderMatchingTest {
    @Test
    fun `a tie goes to the recipe with nothing missing`() {
        // Sauce + mozzarella scores the same on both items; it is a Margherita, not a Prosciutto without ham
        // (the phone run of 2026-10-29 got this wrong when it was the only unpacked dish).
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY)), Fixtures.menu,
            listOf(Order("A-1", listOf(OrderItem("Prosciutto cotto"), OrderItem("Margherita")))), Fixtures.noChecks)
        val d1 = OrderUnit(1, listOf("mozzarella", "tomato sauce"), "a", 10.0).also { e.units += it }
        e.assignOrders(d1, 10.0, 10.0)
        assertEquals("Margherita", d1.recipe)
        assertEquals(emptyList(), d1.missing + d1.extra)
    }

    @Test
    fun `a later dish can fix an earlier tie`() {
        // Dish 1 (sauce + mozzarella) finishes first: it ties between both items (one missing ingredient each) and
        // takes the first (Prosciutto). Dish 2 (with ham) then only fits Prosciutto, so dish 1 moves to Funghi.
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY)), Fixtures.menu,
            listOf(Order("A-1", listOf(OrderItem("Prosciutto cotto"), OrderItem("Funghi")))), Fixtures.noChecks)
        val d1 = OrderUnit(1, listOf("mozzarella", "tomato sauce"), "a", 10.0).also { e.units += it }
        e.assignOrders(d1, 10.0, 10.0)
        assertEquals("Prosciutto cotto", d1.recipe)
        val d2 = OrderUnit(2, listOf("cooked ham", "mozzarella", "tomato sauce"), "a", 20.0).also { e.units += it }
        e.assignOrders(d2, 20.0, 20.0)
        assertEquals("Funghi", d1.recipe)
        assertEquals("Prosciutto cotto", d2.recipe)
        assertTrue(e.log.events.any { it.kind == "dish_reassigned" && it.data["dish"] == 1 })
        assertEquals(listOf("mushrooms"), d1.missing)
        assertEquals(emptyList(), d1.extra + d2.missing + d2.extra)
    }

    @Test
    fun `a packed dish keeps its order`() {
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY)), Fixtures.menu,
            listOf(Order("A-1", listOf(OrderItem("Prosciutto cotto"), OrderItem("Funghi")))), Fixtures.noChecks)
        val d1 = OrderUnit(1, listOf("mozzarella", "tomato sauce"), "a", 10.0).also { e.units += it }
        e.assignOrders(d1, 10.0, 10.0)
        d1.packedT = 15.0
        val d2 = OrderUnit(2, listOf("cooked ham", "mozzarella", "tomato sauce"), "a", 20.0).also { e.units += it }
        e.assignOrders(d2, 20.0, 20.0)
        assertEquals("Prosciutto cotto", d1.recipe)  // already in its box: not moved
        assertEquals("Funghi", d2.recipe)
        assertEquals(listOf("cooked ham"), d2.extra)
    }
}

class CheckoutCodeTest {
    private val sq = listOf(listOf(0.0, 0.0), listOf(400.0, 0.0), listOf(400.0, 400.0), listOf(0.0, 400.0))
    private val wait = listOf(listOf(600.0, 0.0), listOf(1000.0, 0.0), listOf(1000.0, 400.0), listOf(600.0, 400.0))

    /** Two packed orders at a checkout without closed-box detection; a box leaves at 10 s with a customer waiting. */
    private fun handOver(code: String?): Engine {
        val e = Engine(Fixtures.site(Zone("pass", "cam0", Role.CHECKOUT, sq), Zone("door", "cam0", Role.CUSTOMER, wait, forZone = "pass")),
            Fixtures.menu, listOf(Order("A-1", listOf(OrderItem("Margherita")), 0.0), Order("A-2", listOf(OrderItem("Margherita")), 1.0)), Fixtures.noChecks)
        for ((i, o) in listOf("A-1", "A-2").withIndex())
            e.units += OrderUnit(i + 1, listOf("tomato sauce", "mozzarella"), "boxing", 0.0).apply { orderId = o; item = 0; packedT = 0.0 }
        var t = 0.0
        while (t <= 20.0) {
            val codes = if (code != null && t in 6.0..9.0) listOf(Box(150.0, 150.0, 210.0, 210.0) to code) else emptyList()
            e.onFrame(Frame("cam0", t, listOf(Det("person", Box(700.0, 100.0, 800.0, 380.0), 0.9)), mapOf("pass" to if (t < 10.0) 0.5 else 0.0),
                codes = codes))
            t = Math.round((t + 0.2) * 10) / 10.0
        }
        e.finish(t)
        return e
    }

    @Test
    fun `a code read at checkout names the order handed over`() {
        val e = handOver("A-2")
        val h = e.log.events.single { it.kind == "handed_over" }
        assertEquals("A-2", h.data["order"])
        assertEquals("code", h.data["by"])
        assertEquals(1, e.kinds().count { it == "code_read" }, "logged when the code shows up, not every frame")
    }

    @Test
    fun `without a code the oldest packed order is handed over`() {
        val h = handOver(null).log.events.single { it.kind == "handed_over" }
        assertEquals("A-1", h.data["order"])
        assertEquals("oldest packed", h.data["by"])
    }
}
