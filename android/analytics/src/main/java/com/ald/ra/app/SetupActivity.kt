package com.ald.ra.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ald.ra.engine.Camera
import com.ald.ra.engine.Link
import com.ald.ra.engine.MapPoints
import com.ald.ra.engine.RefImage
import com.ald.ra.engine.Refs
import com.ald.ra.engine.Role
import com.ald.ra.engine.Site
import com.ald.ra.engine.SitePack
import com.ald.ra.engine.Zone
import com.ald.ra.engine.checkSite
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Site setup on the tablet (spec §1.1-1.3, §4.3): cameras, zones, flow and travel times, reference rectangles,
 * background frames and the counter map, saved to files/sites/<site>/ as the site pack. The model is then built on
 * the laptop (tools/site_model_device.py). Every change is saved at once.
 *   am start -n com.ald.ra/com.ald.ra.app.SetupActivity --es site <name>
 */
class SetupActivity : Activity() {
    private lateinit var pack: SitePack
    private lateinit var site: Site
    private lateinit var refs: Refs
    private lateinit var live: LiveView
    private lateinit var editor: FrameEditor
    private lateinit var panel: LinearLayout
    private lateinit var tabs: LinearLayout
    private var tab = "Cameras"
    private var cam: String? = null        // selected camera
    private var cls: String? = null        // selected class (Objects)
    private var refFrame: String? = null   // reference frame shown in the editor (Objects); null = live
    private val mapDraft = mutableListOf<Pair<List<Double>, List<Double>>>()  // image point -> plan point (m)

    private val roleColours = mapOf(Role.ASSEMBLY to Color.rgb(255, 196, 0), Role.PACKING to Color.rgb(0, 200, 255),
        Role.HOLD to Color.rgb(200, 120, 255), Role.CHECKOUT to Color.rgb(120, 255, 120), Role.CUSTOMER to Color.rgb(255, 140, 140))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val name = intent.getStringExtra("site")?.takeIf { it.isNotBlank() } ?: "new_site"
        pack = SitePack(File(getExternalFilesDir("sites"), name))
        site = pack.site()
        refs = pack.refs()
        cam = site.cameras.firstOrNull()?.name
        live = LiveView(this)
        editor = FrameEditor(this)
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        tabs = LinearLayout(this)
        val tabBar = android.widget.HorizontalScrollView(this).apply { addView(tabs) }
        val stage = FrameLayout(this).apply { addView(live); addView(editor) }
        val body = LinearLayout(this).apply {
            addView(stage, LinearLayout.LayoutParams(0, MATCH_PARENT, 3f))
            addView(ScrollView(this@SetupActivity).apply { addView(panel) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 2f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 20, 24))
            addView(tabBar)
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        show(intent.getStringExtra("tab") ?: "Cameras")
    }

    override fun onPause() { live.stop(); super.onPause() }
    override fun onResume() { super.onResume(); if (::panel.isInitialized) show(tab) }

    // ---------------------------------------------------------------- helpers

    private fun saveSite(s: Site) { site = s; pack.save(s) }
    private fun saveRefs(r: Refs) { refs = r; pack.save(r) }
    private fun btn(t: String, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(18, 14, 18, 14); setOnClickListener { on() }
    }
    private fun label(t: String, size: Float = 14f, colour: Int = Color.WHITE) =
        TextView(this).apply { text = t; textSize = size; setTextColor(colour); setPadding(4, 6, 4, 6) }
    /** Views side by side sharing the width (labels twice as wide as buttons and fields), so rows fit a phone too. */
    private fun row(vararg v: View) = LinearLayout(this).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, if (it is TextView && it !is Button && it !is EditText) 2f else 1f)) }
    }
    private fun edit(hint: String, value: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); isSingleLine = true
        imeOptions = noFullscreenIme
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun add(vararg v: View) = v.forEach { panel.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun ask(title: String, body: View?, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).apply { body?.let { setView(it) } }
            .setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }
    private fun radios(options: List<String>, selected: String?) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        options.forEach { o -> addView(RadioButton(this@SetupActivity).apply { text = o; id = View.generateViewId(); isChecked = o == selected }) }
    }
    private fun RadioGroup.choice(): String? = findViewById<RadioButton>(checkedRadioButtonId)?.text?.toString()
    private fun clean(s: String) = s.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_")
    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toInt().toString() else d.toString()
    /** Dialogs are light: their fields get dark text. */
    private fun dialogBody(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0)
        v.forEach { if (it is EditText) it.setTextColor(Color.BLACK); addView(it) } }

    private fun showLive(c: Camera?) {
        editor.visibility = View.GONE; live.visibility = View.VISIBLE
        if (c == null || c.url.isBlank()) { live.stop(); return }
        try { live.play(c.url, videoFile(this, pack.dir, c.url)) } catch (e: Exception) { toast(e.message ?: "$e") }
    }

    private fun showStill(f: File?, shapes: List<FrameEditor.Shape>, mode: FrameEditor.Mode) {
        live.stop()
        live.visibility = View.GONE; editor.visibility = View.VISIBLE
        val bm: Bitmap? = f?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        editor.image = bm
        editor.shapes = shapes
        editor.mode = mode
        editor.draft.clear()
        editor.onPoint = null; editor.onRect = null
    }

    private fun zoneShapes(c: String) = site.zones.filter { it.camera == c }.map { z ->
        val pts = z.poly ?: site.cameras.first { it.name == c }.let { listOf(listOf(2.0, 2.0), listOf(it.width - 2.0, 2.0), listOf(it.width - 2.0, it.height - 2.0), listOf(2.0, it.height - 2.0)) }
        FrameEditor.Shape(pts, roleColours[z.role]!!, "${z.name} (${z.role.name.lowercase()})")
    } + site.cameras.first { it.name == c }.ignore.map { FrameEditor.Shape(it, Color.RED, "ignored", dashed = true) }

    /** Buttons to pick the camera a page works on. */
    private fun cameraPicker() {
        if (site.cameras.isEmpty()) { add(label("Add a camera first (Cameras).", colour = Color.YELLOW)); return }
        if (site.cameras.none { it.name == cam }) cam = site.cameras.first().name
        add(row(*site.cameras.map { c -> btn((if (c.name == cam) "● " else "") + c.name) { cam = c.name; show(tab) } }.toTypedArray()))
    }

    /** Seek buttons for a recorded video (setup from a clip: references need frames at different stages). */
    private fun fileControls() {
        add(label("Recording controls (file: sources only)", 12f, Color.LTGRAY))
        add(row(btn("−30 s") { live.seekBy(-30.0) }, btn("−5 s") { live.seekBy(-5.0) }, btn("Pause/Play") { live.togglePause() },
            btn("+5 s") { live.seekBy(5.0) }, btn("+30 s") { live.seekBy(30.0) }))
        val at = edit("go to second", "", number = true)
        add(row(at, btn("Go") { at.text.toString().toDoubleOrNull()?.let { live.seekTo(it) } }))
    }

    private fun show(t: String) {
        tab = t
        tabs.removeAllViews()
        tabs.addView(label("Setup: ${pack.name}", 16f).apply { setPadding(16, 16, 24, 16) })
        for (x in listOf("Cameras", "Zones", "Flow", "Objects", "Map", "Check"))
            tabs.addView(btn(if (x == t) "[$x]" else x) { if (editor.draft.isEmpty() || x == tab) show(x) else ask("Discard the points you tapped?", null) { show(x) } })
        tabs.addView(btn("Close") { finish() })
        panel.removeAllViews()
        editor.mode = FrameEditor.Mode.NONE
        when (t) {
            "Cameras" -> cameras(); "Zones" -> zones(); "Flow" -> flow(); "Objects" -> objects(); "Map" -> map(); else -> check()
        }
    }

    // ---------------------------------------------------------------- cameras

    private fun cameras() {
        val c = site.cameras.firstOrNull { it.name == cam }
        add(label("Cameras", 18f))
        for (x in site.cameras) add(btn("${if (x.name == cam) "● " else ""}${x.name}   ${x.width}×${x.height}, ${fmt(x.fps)} fps\n${redact(x.url)}") { cam = x.name; show(tab) })
        add(btn("+ New camera") { cam = null; show(tab) })
        add(label(if (c == null) "New camera" else "Camera ${c.name}", 16f))
        val name = edit("name, e.g. cam_assembly", c?.name ?: "cam${site.cameras.size + 1}")
        val url = edit("rtsp://user:pass@192.168.1.64:554/Streaming/Channels/102", c?.url ?: "")
        val fps = edit("detector frames per second", fmt(c?.fps ?: 5.0), number = true)
        add(label("Name"), name, label("Stream URL (RTSP), or file:<video> for a recording"), url, label("Detector fps (5; 10 where boxes get carried)"), fps)
        add(row(btn("Connect") { connect(url.text.toString().trim()) }, btn("Freeze as still") { freeze(c, name, url, fps) },
            btn("Save as background") { background(c) }))
        add(label("Freeze as still: the frame zones, map points and checks are drawn on. Background: a frame with the counters empty (checkout fallback).", 12f, Color.LTGRAY))
        fileControls()
        val clips = (getExternalFilesDir("clips")!!.listFiles().orEmpty().toList() + pack.dir.listFiles().orEmpty().toList())
            .filter { it.extension in setOf("mp4", "mkv", "mov") }.map { it.name }.distinct().sorted()
        if (clips.isNotEmpty()) {
            add(label("Recordings on this device (clips/ or the site folder):", 12f, Color.LTGRAY))
            for (n in clips) add(btn("file:$n") { url.setText("file:$n") })
        }
        add(label("Substream paths: Hikvision /Streaming/Channels/102 · Dahua /cam/realmonitor?channel=1&subtype=1 · " +
            "Reolink /h264Preview_01_sub · Tapo /stream2. Use 720p substreams; the checkout camera may use 1080p to read QR codes.", 12f, Color.LTGRAY))
        if (c != null) add(btn("Delete camera ${c.name}") {
            ask("Delete ${c.name} and its zones?", null) {
                val gone = site.zones.filter { it.camera == c.name }.map { it.name }.toSet()
                saveSite(site.copy(cameras = site.cameras - c, zones = site.zones.filter { it.camera != c.name },
                    flow = site.flow - gone, links = site.links.filter { it.from !in gone && it.to !in gone }))
                cam = site.cameras.firstOrNull()?.name; show(tab)
            }
        })
        if (c != null) showLive(c) else if (live.url == null) showLive(null) else { editor.visibility = View.GONE; live.visibility = View.VISIBLE }
    }

    private fun connect(url: String) {
        if (url.isBlank()) return toast("enter a stream URL first")
        try { editor.visibility = View.GONE; live.visibility = View.VISIBLE; live.play(url, videoFile(this, pack.dir, url)) } catch (e: Exception) { toast(e.message ?: "$e") }
    }

    private fun freeze(old: Camera?, nameV: EditText, urlV: EditText, fpsV: EditText) {
        val name = clean(nameV.text.toString())
        val url = urlV.text.toString().trim()
        if (name.isEmpty() || url.isEmpty()) return toast("a camera needs a name and a URL")
        if (live.url != url) return toast("press Connect first")
        val bm = live.grab() ?: return toast("no picture yet: wait for the video")
        if (old?.name != name && site.cameras.any { it.name == name }) return toast("there is already a camera named $name")
        val fps = fpsV.text.toString().toDoubleOrNull() ?: 5.0
        if (old != null && (old.width != bm.width || old.height != bm.height) && site.zones.any { it.camera == old.name })
            toast("the picture is now ${bm.width}×${bm.height} (was ${old.width}×${old.height}): check the zones")
        pack.still(name).parentFile!!.mkdirs()
        pack.still(name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val cNew = Camera(name, url, bm.width, bm.height, fps, ignore = old?.ignore.orEmpty(), map = old?.map, floor = old?.floor)
        var s = site.copy(cameras = if (old == null) site.cameras + cNew else site.cameras.map { if (it === old) cNew else it })
        if (old != null && old.name != name) {  // renamed: move its zones, still, background and references along
            s = s.copy(zones = s.zones.map { if (it.camera == old.name) it.copy(camera = name) else it })
            pack.still(old.name).delete()
            pack.background(old.name)?.let { it.renameTo(File(it.parentFile, "$name.${it.extension}")) }
            saveRefs(refs.copy(visual = refs.visual.mapValues { (_, l) -> l.map { if (it.camera == old.name) it.copy(camera = name) else it } }))
        }
        saveSite(s)
        cam = name
        toast("$name: still saved (${bm.width}×${bm.height})")
        show(tab)
    }

    private fun background(c: Camera?) {
        if (c == null) return toast("freeze a still first: that saves the camera")
        if (live.url != c.url) return toast("press Connect first")
        val bm = live.grab() ?: return toast("no picture yet")
        val f = File(pack.dir, "background/${c.name}.png").apply { parentFile!!.mkdirs() }
        pack.background(c.name)?.delete()
        f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast("${c.name}: background saved")
    }

    // ---------------------------------------------------------------- zones

    private fun zones() {
        add(label("Zones", 18f))
        cameraPicker()
        val c = site.cameras.firstOrNull { it.name == cam } ?: return
        showStill(pack.still(c.name), zoneShapes(c.name), FrameEditor.Mode.POINTS)
        add(label("Tap the corners of a zone on the picture, then Finish zone. Or use the whole view.", 12f, Color.LTGRAY))
        add(row(btn("Finish zone") { if (editor.draft.size < 3) toast("tap at least 3 corners") else zoneDialog(c, editor.draft.toList()) },
            btn("Undo point") { if (editor.draft.isNotEmpty()) { editor.draft.removeAt(editor.draft.size - 1); editor.invalidate() } },
            btn("Whole view is one zone") { zoneDialog(c, null) }))
        for (z in site.zones.filter { it.camera == c.name })
            add(row(label("${z.name}: ${z.role.name.lowercase()}${z.forZone?.let { " for $it" } ?: ""}${if (z.poly == null) ", whole view" else ""}"),
                btn("Delete") { saveSite(site.copy(zones = site.zones - z, flow = site.flow - z.name,
                    links = site.links.filter { it.from != z.name && it.to != z.name })); show(tab) }))
        c.ignore.forEachIndexed { i, poly -> add(row(label("ignored area ${i + 1}"), btn("Delete") {
            saveSite(site.copy(cameras = site.cameras.map { if (it.name == c.name) it.copy(ignore = it.ignore - listOf(poly)) else it })); show(tab) })) }
        add(label("Roles: assembly = the dish is built here (ingredient checks) · packing = food goes into its box · hold = oven, shelf, rack · " +
            "checkout = the order leaves here · customer = where customers wait, next to a checkout · ignore = the detector skips it (e.g. a menu photo).", 12f, Color.LTGRAY))
    }

    private fun zoneDialog(c: Camera, poly: List<List<Double>>?) {
        val roles = listOf("assembly", "packing", "hold", "checkout", "customer") + if (poly != null) listOf("ignore") else emptyList()
        val name = edit("zone name, e.g. make_line", "")
        val role = radios(roles, null)
        val checkouts = site.zones.filter { it.role == Role.CHECKOUT }.map { it.name }
        val forZ = radios(checkouts, checkouts.firstOrNull())
        val body = dialogBody(label("Name", colour = Color.BLACK), name, label("Role", colour = Color.BLACK), role)
        if (checkouts.size > 1) { body.addView(label("Customer zone: for which checkout?", colour = Color.BLACK)); body.addView(forZ) }
        ask("New zone on ${c.name}", ScrollView(this).apply { addView(body) }) {
            val r = role.choice() ?: return@ask toast("pick a role")
            if (r == "ignore") {
                saveSite(site.copy(cameras = site.cameras.map { if (it.name == c.name) it.copy(ignore = it.ignore + listOf(poly!!)) else it }))
                return@ask show(tab)
            }
            val n = clean(name.text.toString()).ifEmpty { r + (site.zones.count { it.role.name.lowercase() == r } + 1) }
            if (site.zones.any { it.name == n }) return@ask toast("there is already a zone named $n")
            val roleE = Role.valueOf(r.uppercase())
            val z = Zone(n, c.name, roleE, poly, if (roleE == Role.CUSTOMER) forZ.choice() else null)
            // New zones join the end of the flow; reorder them in Flow.
            saveSite(site.copy(zones = site.zones + z, flow = if (roleE == Role.CUSTOMER) site.flow else site.flow + n))
            show(tab)
        }
    }

    // ---------------------------------------------------------------- flow

    private fun flow() {
        add(label("Flow", 18f))
        add(label("The order a dish passes through the zones. Dishes only move forward and may skip zones.", 12f, Color.LTGRAY))
        val zs = site.zones.associateBy { it.name }
        site.flow.forEachIndexed { i, n ->
            val z = zs[n]
            fun move(d: Int) { val l = site.flow.toMutableList(); val j = i + d; if (j in l.indices) { l[i] = l[j]; l[j] = n; saveSite(site.copy(flow = l)); show(tab) } }
            add(row(label("${i + 1}. $n (${z?.role?.name?.lowercase()}, ${z?.camera})"), btn("↑") { move(-1) }, btn("↓") { move(1) },
                btn("Remove") { saveSite(site.copy(flow = site.flow - n)); show(tab) }))
        }
        for (z in site.zones.filter { it.role != Role.CUSTOMER && it.name !in site.flow })
            add(row(label("not in the flow: ${z.name}"), btn("Add") { saveSite(site.copy(flow = site.flow + z.name)); show(tab) }))
        val pairs = site.flow.mapNotNull { zs[it] }.zipWithNext().filter { (a, b) -> a.camera != b.camera }
        if (pairs.isNotEmpty()) add(label("Between cameras: how long the move takes (seconds), if the cameras don't overlap. Empty = 0 to ${fmt(site.params.defaultTravelMaxS)} s.", 12f, Color.LTGRAY))
        for ((a, b) in pairs) {
            val l = site.links.firstOrNull { it.from == a.name && it.to == b.name }
            val lo = edit("min s", l?.travelS?.get(0)?.let(::fmt) ?: "", number = true)
            val hi = edit("max s", l?.travelS?.get(1)?.let(::fmt) ?: "", number = true)
            add(label("${a.name} (${a.camera}) → ${b.name} (${b.camera})"))
            add(row(lo, hi, btn("Save") {
                val x = lo.text.toString().toDoubleOrNull(); val y = hi.text.toString().toDoubleOrNull()
                val rest = site.links.filter { !(it.from == a.name && it.to == b.name) }
                if (x == null || y == null) { saveSite(site.copy(links = rest)); toast("travel time cleared") }
                else if (y <= x) toast("max must be more than min")
                else { saveSite(site.copy(links = rest + Link(a.name, b.name, listOf(x, y)))); toast("saved") }
            }))
        }
        add(label("Assembly", 16f))
        val mode = radios(listOf("packed_after", "in_container"), site.assembly.mode)
        val done = radios(listOf("leaves_zone", "packed"), site.assembly.done)
        add(label("packed_after: food goes into a box in a packing zone · in_container: the dish is built in its container (bowl)", 12f, Color.LTGRAY), mode,
            label("A dish is finished when it leaves the assembly zone (leaves_zone) or when it is packed (packed)", 12f, Color.LTGRAY), done)
        mode.setOnCheckedChangeListener { g, _ -> saveSite(site.copy(assembly = site.assembly.copy(mode = g.choice()!!))) }
        done.setOnCheckedChangeListener { g, _ -> saveSite(site.copy(assembly = site.assembly.copy(done = g.choice()!!))) }
        cameraPicker()
        site.cameras.firstOrNull { it.name == cam }?.let { showStill(pack.still(it.name), zoneShapes(it.name), FrameEditor.Mode.NONE) }
    }

    // ---------------------------------------------------------------- objects (references)

    private val roleNames = listOf("base", "open container", "closed container")
    private fun roleOf(c: String) = when (c) { in site.classes.base -> "base"; in site.classes.openContainer -> "open container"
        in site.classes.closedContainer -> "closed container"; else -> "no role" }

    private fun objects() {
        add(label("Objects", 18f))
        add(label("The detector learns each class from rectangles you draw: 3-5 frames per class, from the camera that sees it, at different " +
            "stages (bare, half-built, finished). person needs none.", 12f, Color.LTGRAY))
        val classes = (site.classes.base + site.classes.openContainer + site.classes.closedContainer + refs.visual.keys).distinct()
        if (cls !in classes) cls = classes.firstOrNull()
        for (c in classes) {
            val imgs = refs.visual[c].orEmpty()
            add(row(btn("${if (c == cls) "● " else ""}$c · ${roleOf(c)} · ${imgs.size} frames, ${imgs.sumOf { it.boxes.size }} boxes") { cls = c; show(tab) },
                btn("Delete") { ask("Delete class $c and its rectangles?", null) {
                    saveRefs(refs.copy(visual = refs.visual - c))
                    saveSite(site.copy(classes = site.classes.copy(base = site.classes.base - c, openContainer = site.classes.openContainer - c,
                        closedContainer = site.classes.closedContainer - c)))
                    show(tab) } }))
        }
        add(btn("+ Add class") { classDialog() })
        val c = cls ?: return showLive(null)
        cameraPicker()
        val camera = site.cameras.firstOrNull { it.name == cam } ?: return
        add(row(btn("Freeze frame for references") { freezeRef(camera) }, btn("Back to live") { refFrame = null; show(tab) }))
        val frames = File(pack.dir, "refs").listFiles().orEmpty().filter { it.extension == "png" && it.name.startsWith("${camera.name}_") }.map { it.name }.sorted()
        for (f in frames) {
            val n = refs.visual[c].orEmpty().firstOrNull { it.image == f }?.boxes?.size ?: 0
            add(row(btn("${if (f == refFrame) "● " else ""}$f: $n box(es) of $c") { refFrame = f; show(tab) },
                btn("Delete frame") { File(pack.dir, "refs/$f").delete()
                    saveRefs(refs.copy(visual = refs.visual.mapValues { (_, l) -> l.filter { it.image != f } }))
                    if (refFrame == f) refFrame = null; show(tab) }))
        }
        val shown = refFrame?.takeIf { it in frames }
        if (shown == null) { showLive(camera); fileControls(); return }
        add(label("Drag a rectangle around each $c in this frame (tight, the whole object).", 12f, Color.YELLOW))
        add(btn("Undo last box") {
            val l = refs.visual[c].orEmpty().map { if (it.image == shown && it.boxes.isNotEmpty()) it.copy(boxes = it.boxes.dropLast(1)) else it }.filter { it.boxes.isNotEmpty() }
            saveRefs(refs.copy(visual = refs.visual + (c to l))); show(tab)
        })
        showStill(File(pack.dir, "refs/$shown"), refs.visual.flatMap { (k, l) -> l.filter { it.image == shown }.flatMap { it.boxes }.map { b ->
            FrameEditor.Shape(listOf(listOf(b[0], b[1]), listOf(b[2], b[1]), listOf(b[2], b[3]), listOf(b[0], b[3])),
                if (k == c) Color.YELLOW else Color.CYAN, k) } }, FrameEditor.Mode.RECT)
        editor.onRect = { b ->
            val l = refs.visual[c].orEmpty()
            val nl = if (l.any { it.image == shown }) l.map { if (it.image == shown) it.copy(boxes = it.boxes + listOf(b)) else it }
                else l + RefImage(shown, listOf(b), camera.name)
            saveRefs(refs.copy(visual = refs.visual + (c to nl)))
            show(tab)
        }
    }

    private fun classDialog() {
        val name = edit("class name, e.g. pizza, open box, closed box", "")
        val role = radios(roleNames, null)
        ask("New class", dialogBody(label("Name", colour = Color.BLACK), name, label("Role", colour = Color.BLACK), role)) {
            val n = name.text.toString().trim().lowercase()
            val r = role.choice() ?: return@ask toast("pick a role")
            if (n.isEmpty() || n == "person") return@ask toast("pick another name")
            val k = site.classes
            saveSite(site.copy(classes = when (r) {
                "base" -> k.copy(base = k.base + n); "open container" -> k.copy(openContainer = k.openContainer + n)
                else -> k.copy(closedContainer = k.closedContainer + n)
            }))
            if (n !in refs.visual) saveRefs(refs.copy(visual = refs.visual + (n to emptyList())))
            cls = n; show(tab)
        }
    }

    private fun freezeRef(c: Camera) {
        if (live.url != c.url) return toast("wait for the live picture")
        val bm = live.grab() ?: return toast("no picture yet")
        val n = "${c.name}_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}" + (if (live.isFile) "_t${live.positionS.toInt()}" else "") + ".png"
        File(pack.dir, "refs/$n").apply { parentFile!!.mkdirs() }.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        refFrame = n
        show(tab)
    }

    // ---------------------------------------------------------------- shared map

    private fun map() {
        add(label("Counter map (optional)", 18f))
        add(label("For cameras that overlap: tap 4 or more points on the counter top (corners, tile marks) and type where each one is on " +
            "a floor plan, in metres, with the same origin for every camera. Boxes seen at the same plan spot by two cameras are then the same box.", 12f, Color.LTGRAY))
        cameraPicker()
        val c = site.cameras.firstOrNull { it.name == cam } ?: return
        val saved = c.map?.let { m -> m.image.zip(m.plan) }.orEmpty()
        val pts = if (mapDraft.isNotEmpty()) mapDraft else saved
        showStill(pack.still(c.name), pts.mapIndexed { i, (p, q) -> FrameEditor.Shape(listOf(p), Color.GREEN, "${i + 1}: ${fmt(q[0])}, ${fmt(q[1])} m") }, FrameEditor.Mode.POINTS)
        editor.onPoint = { p ->
            val xy = edit("x, y in metres, e.g. 1.2, 0.6", "")
            AlertDialog.Builder(this).setTitle("Point ${pts.size + 1} on the floor plan").setView(dialogBody(xy))
                .setPositiveButton("OK") { _, _ ->
                    val v = xy.text.toString().split(Regex("[,; ]+")).mapNotNull { it.toDoubleOrNull() }
                    if (v.size != 2) toast("type two numbers: x, y") else { if (mapDraft.isEmpty()) mapDraft += saved; mapDraft += p to v }
                    show(tab)
                }.setNegativeButton("Cancel") { _, _ -> show(tab) }.show()
        }
        for ((i, pq) in pts.withIndex()) add(label("${i + 1}. picture ${fmt(pq.first[0])}, ${fmt(pq.first[1])} → plan ${fmt(pq.second[0])}, ${fmt(pq.second[1])} m"))
        if (mapDraft.isNotEmpty()) add(row(btn("Save map (${mapDraft.size} points)") {
            if (mapDraft.size < 4) return@btn toast("at least 4 points")
            saveSite(site.copy(cameras = site.cameras.map { if (it.name == c.name) it.copy(map = MapPoints(mapDraft.map { it.first }, mapDraft.map { it.second })) else it }))
            mapDraft.clear(); show(tab)
        }, btn("Discard") { mapDraft.clear(); show(tab) }))
        if (c.map != null && mapDraft.isEmpty()) add(btn("Delete this camera's map") {
            saveSite(site.copy(cameras = site.cameras.map { if (it.name == c.name) it.copy(map = null) else it })); show(tab)
        })
    }

    // ---------------------------------------------------------------- check

    private fun check() {
        val f = checkSite(pack)
        val errors = f.count { it.error }
        add(label(if (errors == 0) "Ready to run" else "$errors thing(s) to fix before a run", 18f, if (errors == 0) Color.GREEN else Color.rgb(255, 120, 120)))
        for (x in f.sortedBy { !it.error }) add(label((if (x.error) "✖ " else "⚠ ") + x.text, 13f, if (x.error) Color.rgb(255, 120, 120) else Color.YELLOW))
        if (!pack.menuFile.exists()) {
            val others = getExternalFilesDir("sites")!!.listFiles().orEmpty().filter { it.name != pack.name && File(it, "menu.json").exists() }
            for (o in others) add(btn("Copy menu from ${o.name}") { File(o, "menu.json").copyTo(pack.menuFile); show(tab) })
        }
        add(label("Model: with the tablet plugged into the laptop, run\n  python3 tools/site_model_device.py --site ${pack.name}\n" +
            "It pulls this site, builds the detector from the rectangles (about 5 min) and puts it back.", 12f, Color.LTGRAY))
        add(label("Orders: ${runCatching { pack.orders().size }.getOrDefault(0)} in orders.json; type more on the live screen.", 12f, Color.LTGRAY))
        if (errors == 0) add(btn("Start ${pack.name}") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("mode", "start").putExtra("site", pack.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        })
        site.cameras.firstOrNull()?.let { showStill(pack.still(it.name), zoneShapes(it.name), FrameEditor.Mode.NONE) } ?: showLive(null)
    }
}
