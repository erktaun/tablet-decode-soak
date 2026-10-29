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
    fun `pizza trailer matches python`() {
        val out = File(repo, "out/test_parity")
        main(arrayOf("--site", File(repo, "sites/pizza_trailer").path, "--detections", File(res, "detections.json").path,
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
        listOf(Ingredient("tomato sauce", "red sauce"), Ingredient("mozzarella", "white cheese"), Ingredient("cooked ham", "pink slices")),
        listOf(Recipe("Prosciutto cotto", listOf("tomato sauce", "mozzarella", "cooked ham")),
            Recipe("Margherita", listOf("tomato sauce", "mozzarella"))))

    fun site(vararg zones: Zone, flow: List<String> = zones.filter { it.role != Role.CUSTOMER }.map { it.name },
             closed: List<String> = emptyList(), cams: List<String> = listOf("cam0")) = Site(
        site = "test", cameras = cams.map { Camera(it, "file:none", 1280, 720) }, zones = zones.toList(), flow = flow,
        classes = Classes(base = listOf("pizza"), openContainer = listOf("open box"), closedContainer = closed))

    val noChecks = CheckRunner { _, done -> done(null) }
}

class OrderMatchingTest {
    @Test
    fun `a later dish can fix an earlier tie`() {
        // Dish 1 (margherita) finishes first: it ties between both recipes and takes the first item (Prosciutto).
        // Dish 2 (with ham) then only fits Prosciutto, so the exhaustive match moves dish 1 to Margherita.
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY)), Fixtures.menu,
            listOf(Order("A-1", listOf(OrderItem("Prosciutto cotto"), OrderItem("Margherita")))), Fixtures.noChecks)
        val d1 = OrderUnit(1, listOf("mozzarella", "tomato sauce"), "a", 10.0).also { e.units += it }
        e.assignOrders(d1, 10.0, 10.0)
        assertEquals("Prosciutto cotto", d1.recipe)
        val d2 = OrderUnit(2, listOf("cooked ham", "mozzarella", "tomato sauce"), "a", 20.0).also { e.units += it }
        e.assignOrders(d2, 20.0, 20.0)
        assertEquals("Margherita", d1.recipe)
        assertEquals("Prosciutto cotto", d2.recipe)
        assertTrue(e.log.events.any { it.kind == "dish_reassigned" && it.data["dish"] == 1 })
        assertEquals(emptyList(), d1.missing + d1.extra + d2.missing + d2.extra)
    }

    @Test
    fun `a packed dish keeps its order`() {
        val e = Engine(Fixtures.site(Zone("a", "cam0", Role.ASSEMBLY)), Fixtures.menu,
            listOf(Order("A-1", listOf(OrderItem("Prosciutto cotto"), OrderItem("Margherita")))), Fixtures.noChecks)
        val d1 = OrderUnit(1, listOf("mozzarella", "tomato sauce"), "a", 10.0).also { e.units += it }
        e.assignOrders(d1, 10.0, 10.0)
        d1.packedT = 15.0
        val d2 = OrderUnit(2, listOf("cooked ham", "mozzarella", "tomato sauce"), "a", 20.0).also { e.units += it }
        e.assignOrders(d2, 20.0, 20.0)
        assertEquals("Prosciutto cotto", d1.recipe)  // already in its box: not moved
        assertEquals("Margherita", d2.recipe)
        assertEquals(listOf("cooked ham"), d2.extra)
    }
}
