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
    private val repo = File("../..").absoluteFile.normalize()  // tablet-soak/ (tests run in android/engine)
    private val res = File("src/test/resources/pizza_trailer")

    @Test
    fun `pizza trailer matches python`() {
        val out = File(repo, "out/test_parity")
        main(arrayOf("--site", File(repo, "sites/pizza_trailer").path, "--detections", File(res, "detections.json").path,
            "--checker", "recorded:${File(res, "decision_calls.jsonl").path}", "--out", out.path,
            "--params", """{"checkEveryS":10,"checkStillS":0}"""))
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
