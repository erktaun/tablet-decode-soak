package com.ald.ra.engine

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The site pack the setup screens write, and the check that says what's missing before a run. */
class SitePackTest {
    private val repo = File("../..").absoluteFile.normalize()

    /** A copy of the pizza site with a stand-in model built for its references. */
    private fun pizzaPack(): SitePack {
        val dir = createTempDirectory("site").toFile().resolve("pizza")
        val src = File(repo, "sites/pizza_trailer")
        for (f in listOf("site.json", "menu.json", "orders.json", "refs/refs.json", "background/cam0.png"))
            File(src, f).copyTo(File(dir, f))
        val p = SitePack(dir)
        File(dir, "model.json").writeText("""{"classes": ${p.refs().modelClasses.joinToString(",", "[", "]") { "\"$it\"" }}}""")
        File(dir, "model.tflite").writeText("")
        return p
    }

    @Test
    fun `the pizza site has no errors`() {
        val f = checkSite(pizzaPack())
        assertTrue(f.none { it.error }, f.joinToString("\n"))
        assertTrue(f.any { "1 reference frame" in it.text }, "one reference frame per class is warned about: $f")
    }

    @Test
    fun `an empty site lists what to do`() {
        val f = checkSite(SitePack(createTempDirectory("site").toFile().resolve("new"))).filter { it.error }.map { it.text }
        for (want in listOf("no cameras", "no flow", "no base class", "no model yet", "no menu.json"))
            assertTrue(f.any { want in it }, "missing '$want' in $f")
    }

    @Test
    fun `a model built for other classes is out of date`() {
        val p = pizzaPack()
        p.save(p.refs().let { it.copy(visual = it.visual + ("closed box" to listOf(RefImage("x.png", listOf(listOf(1.0, 1.0, 9.0, 9.0)))))) })
        p.save(p.site().let { it.copy(classes = it.classes.copy(closedContainer = listOf("closed box"))) })
        assertTrue(checkSite(p).any { it.error && "out of date" in it.text })
    }

    @Test
    fun `references always name the person class`() {
        val p = SitePack(createTempDirectory("site").toFile().resolve("new"))
        p.save(Refs(mapOf("pizza" to listOf(RefImage("a.png", listOf(listOf(1.0, 1.0, 9.0, 9.0)))))))
        assertTrue("\"person\"" in p.refsFile.readText(), p.refsFile.readText())
    }

    @Test
    fun `saving keeps the site and leaves defaults out`() {
        val p = pizzaPack()
        val s = p.site().copy(links = listOf(Link("make_line", "boxing", listOf(2.0, 30.0))))
        p.save(s)
        assertEquals(s, p.site())
        val text = p.siteFile.readText()
        assertTrue("params" !in text && "\"fps\"" !in text, text)
    }
}
