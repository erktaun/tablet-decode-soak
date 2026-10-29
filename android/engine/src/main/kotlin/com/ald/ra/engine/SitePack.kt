package com.ald.ra.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reference rectangles for the detector (refs/refs.json, read by tools/make_site_model.py): per class, the frames
 * (files in refs/) and the boxes drawn on them, in that frame's pixels. Text classes (person) need no frames.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Refs(
    val visual: Map<String, List<RefImage>> = emptyMap(),
    /** Always written: make_site_model.py reads a missing list as "no text classes" (the model then has no person). */
    @EncodeDefault val text: List<String> = listOf("person"),
) {
    /** Class order in the model: visual classes, then text classes (as make_site_model.py builds it). */
    val modelClasses get() = visual.keys.toList() + text
}

@Serializable
data class RefImage(val image: String, val boxes: List<List<Double>>, val camera: String? = null)

/** Written by the setup screens: only what differs from the defaults, readable by hand. */
private val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

/** A site pack directory (spec §1): site.json, menu.json, orders.json, refs/, background/, model.tflite + model.json. */
class SitePack(val dir: File) {
    val name: String get() = dir.name
    val siteFile get() = File(dir, "site.json")
    val refsFile get() = File(dir, "refs/refs.json")
    val menuFile get() = File(dir, "menu.json")
    val ordersFile get() = File(dir, "orders.json")

    fun site(): Site = if (siteFile.exists()) loadSite(siteFile) else Site(name, emptyList(), emptyList(), emptyList(), classes = Classes(emptyList()))
    fun save(s: Site) { dir.mkdirs(); siteFile.writeText(pretty.encodeToString(Site.serializer(), s) + "\n") }
    fun refs(): Refs = if (refsFile.exists()) pretty.decodeFromString(Refs.serializer(), refsFile.readText()) else Refs()
    fun save(r: Refs) { refsFile.parentFile.mkdirs(); refsFile.writeText(pretty.encodeToString(Refs.serializer(), r) + "\n") }
    fun orders(): List<Order> = if (ordersFile.exists()) loadOrders(ordersFile) else emptyList()
    fun background(camera: String) = listOf("png", "jpg").map { File(dir, "background/$camera.$it") }.firstOrNull { it.exists() }
    /** The still frame the setup screens draw on (full stream resolution). */
    fun still(camera: String) = File(dir, "setup/$camera.png")

    /** Class names the model was built with (model.json), or null without a model. */
    fun modelClasses(): List<String>? {
        val f = File(dir, "model.json")
        if (!f.exists() || !File(dir, "model.tflite").exists()) return null
        return json.parseToJsonElement(f.readText()).jsonObject["classes"]?.jsonArray?.map { it.jsonPrimitive.content }
    }
}

data class Finding(val error: Boolean, val text: String)

/**
 * What's missing or wrong before a site can run, in the order the setup screens fill it in. Errors stop a run;
 * warnings are things that work but weaken results.
 */
fun checkSite(pack: SitePack): List<Finding> {
    val out = mutableListOf<Finding>()
    fun err(s: String) = out.add(Finding(true, s))
    fun warn(s: String) = out.add(Finding(false, s))
    val s = try { pack.site() } catch (e: Exception) { return listOf(Finding(true, "site.json can't be read: ${e.message}")) }
    val refs = try { pack.refs() } catch (e: Exception) { err("refs/refs.json can't be read: ${e.message}"); Refs() }

    // cameras
    if (s.cameras.isEmpty()) err("no cameras")
    for (c in s.cameras) {
        if (c.url.isBlank()) err("camera ${c.name}: no stream URL")
        if (c.width <= 0 || c.height <= 0) err("camera ${c.name}: no still frame yet (connect and freeze one)")
    }
    s.cameras.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { err("two cameras named $it") }

    // zones and flow
    val zones = s.zones.associateBy { it.name }
    s.zones.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { err("two zones named $it") }
    for (z in s.zones) if (s.cameras.none { it.name == z.camera }) err("zone ${z.name}: camera ${z.camera} doesn't exist")
    for (c in s.cameras) if (s.zones.none { it.camera == c.name }) warn("camera ${c.name} has no zones: it is decoded but nothing is watched")
    val checkouts = s.zones.filter { it.role == Role.CHECKOUT }
    for (z in s.zones.filter { it.role == Role.CUSTOMER }) {
        if (z.forZone == null && checkouts.size > 1) err("customer zone ${z.name}: say which checkout zone it belongs to")
        if (z.forZone != null && zones[z.forZone]?.role != Role.CHECKOUT) err("customer zone ${z.name}: ${z.forZone} is not a checkout zone")
    }
    if (s.flow.isEmpty()) err("no flow: put the zones in the order a dish passes through them")
    for (f in s.flow) when {
        f !in zones -> err("flow: zone $f doesn't exist")
        zones[f]!!.role == Role.CUSTOMER -> err("flow: customer zone $f doesn't belong in the flow")
    }
    if (s.flow.toSet().size < s.flow.size) err("flow: a zone appears twice")
    val inFlow = s.flow.mapNotNull { zones[it] }
    if (s.zones.isNotEmpty() && inFlow.none { it.role == Role.ASSEMBLY }) err("flow: no assembly zone")
    for (z in s.zones.filter { it.role != Role.CUSTOMER && it.name !in s.flow }) warn("zone ${z.name} is not in the flow")
    if (s.assembly.mode == "packed_after" && inFlow.none { it.role == Role.PACKING })
        warn("no packing zone: dishes are never bound to a box (or set assembly mode to in_container)")
    if (inFlow.none { it.role == Role.CHECKOUT }) warn("no checkout zone: orders are never shown as handed over")
    for ((a, b) in inFlow.zipWithNext()) if (a.camera != b.camera && s.links.none { it.from == a.name && it.to == b.name } &&
        s.cameras.firstOrNull { it.name == a.camera }?.map == null)
        warn("${a.name} → ${b.name} cross cameras with no travel time: any time up to ${s.params.defaultTravelMaxS.toInt()} s is accepted")

    // objects and model
    val roleClasses = s.classes.base + s.classes.openContainer + s.classes.closedContainer
    if (s.classes.base.isEmpty()) err("no base class (the dough, bun or bowl the dish is built on)")
    for (c in roleClasses) {
        val imgs = refs.visual[c].orEmpty().filter { it.boxes.isNotEmpty() }
        if (imgs.isEmpty()) err("class $c: no reference rectangles")
        else if (imgs.size < 3) warn("class $c: ${imgs.size} reference frame(s); 3-5 at different stages work better")
    }
    for (c in s.classes.closedContainer) for (cam in s.zones.filter { it.role in setOf(Role.PACKING, Role.HOLD, Role.CHECKOUT) }.map { it.camera }.distinct())
        if (refs.visual[c].orEmpty().none { it.camera == cam }) warn("class $c: no reference from camera $cam, which tracks it")
    for (c in refs.visual.keys - roleClasses.toSet()) warn("class $c has references but no role: the model detects it, nothing uses it")
    for (c in refs.text - s.classes.person.toSet()) warn("text class $c has no role")
    val built = pack.modelClasses()
    if (built == null) err("no model yet: build it on the laptop (python3 tools/site_model_device.py --site ${pack.name})")
    else if (built != refs.modelClasses) err("the model is out of date (built for $built): rebuild it on the laptop")
    else if (pack.refsFile.exists() && pack.refsFile.lastModified() > File(pack.dir, "model.tflite").lastModified())
        warn("references changed after the model was built: rebuild it on the laptop")

    // menu, orders, background
    if (!pack.menuFile.exists()) err("no menu.json (copy one from another site, or push it from the laptop)")
    else try { if (loadMenu(pack.menuFile).recipes.isEmpty()) err("menu.json has no recipes") } catch (e: Exception) { err("menu.json can't be read: ${e.message}") }
    if (pack.ordersFile.exists()) try { pack.orders() } catch (e: Exception) { err("orders.json can't be read: ${e.message}") }
    if (s.classes.closedContainer.isEmpty()) for (z in checkouts) if (pack.background(z.camera) == null)
        warn("camera ${z.camera}: no background frame; the first frame of each run is used (counter must be empty at start)")
    return out
}
