package com.ald.ra.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ald.ra.engine.Order
import com.ald.ra.engine.OrderItem

/**
 * Live screen (site picker, setup, start/stop, order entry) + entry point for adb:
 *   am start -n com.ald.ra/com.ald.ra.app.MainActivity --es mode start --es site pizza_trailer [--es speed 2] [--es params '{...}']
 *   am start -n com.ald.ra/com.ald.ra.app.MainActivity --es mode stop
 * Site packs live in files/sites/<site>/ (pushed by tools/check_device.py or by hand).
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var cams: LinearLayout
    private lateinit var orders: TextView
    private lateinit var feed: TextView
    private val views = mutableMapOf<String, ImageView>()
    private var pending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getExternalFilesDir("sites"); getExternalFilesDir("runs"); getExternalFilesDir("clips")  // so adb can push/pull
        buildUi()
        AnalyticsService.listener = { if (!pending) { pending = true; ui.postDelayed({ pending = false; refresh() }, 200) } }
        if (savedInstanceState == null) handle(intent)
        refresh()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    override fun onDestroy() { AnalyticsService.listener = null; super.onDestroy() }

    private fun handle(i: Intent?) {
        val mode = i?.getStringExtra("mode") ?: return
        startForegroundService(Intent(this, AnalyticsService::class.java).putExtras(i.extras ?: Bundle()).putExtra("mode", mode))
    }

    private fun buildUi() {
        fun text(size: Float, mono: Boolean = true) = TextView(this).apply {
            textSize = size; setTextColor(Color.WHITE); if (mono) typeface = Typeface.MONOSPACE; setPadding(8, 4, 8, 4)
        }
        status = text(12f)
        fun btn(t: String, on: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { on() } }
        siteBtn = btn("Site: $siteName") { pickSite() }
        val start = btn("Start") {
            startForegroundService(Intent(this@MainActivity, AnalyticsService::class.java).putExtra("mode", "start").putExtra("site", siteName))
        }
        val stop = btn("Stop") { startForegroundService(Intent(this@MainActivity, AnalyticsService::class.java).putExtra("mode", "stop")) }
        val setup = btn("Setup") { startActivity(Intent(this, SetupActivity::class.java).putExtra("site", siteName)) }
        val order = btn("New order") { newOrder() }
        val key = btn("API key") { apiKey() }
        val top = LinearLayout(this).apply { listOf(siteBtn, setup, start, stop, order, key).forEach { addView(it) }
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        cams = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        orders = text(15f)
        feed = text(11f)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = "ORDERS"; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(8, 8, 8, 0) })
            addView(orders)
            addView(TextView(this@MainActivity).apply { text = "EVENTS"; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(8, 16, 8, 0) })
            addView(feed)
        }
        val body = LinearLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(cams) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 3f))
            addView(ScrollView(this@MainActivity).apply { addView(right) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 2f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 20, 24))
            addView(top)
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
    }

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private var siteName: String
        get() = prefs.getString("site", null) ?: sites().firstOrNull() ?: "pizza_trailer"
        set(v) { prefs.edit().putString("site", v).apply(); siteBtn.text = "Site: $v" }
    private lateinit var siteBtn: Button

    private fun sites() = getExternalFilesDir("sites")!!.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

    private fun pickSite() {
        val names = sites()
        AlertDialog.Builder(this).setTitle("Site").setItems((names + "+ New site").toTypedArray()) { _, i ->
            if (i < names.size) { siteName = names[i]; return@setItems }
            val name = EditText(this).apply { hint = "site name, e.g. client_kitchen"; isSingleLine = true; imeOptions = noFullscreenIme }
            AlertDialog.Builder(this).setTitle("New site").setView(name).setPositiveButton("OK") { _, _ ->
                val n = name.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]+"), "_")
                if (n.isEmpty()) return@setPositiveButton
                java.io.File(getExternalFilesDir("sites"), n).mkdirs()
                siteName = n
                startActivity(Intent(this, SetupActivity::class.java).putExtra("site", n))
            }.setNegativeButton("Cancel", null).show()
        }.show()
    }

    /**
     * The Decisions API key, kept in the app's private storage (files/openai.key): never in the site pack, the logs or
     * the report. Takes effect at the next Start.
     */
    private fun apiKey() {
        val f = java.io.File(filesDir, "openai.key")
        val field = EditText(this).apply {
            hint = if (f.exists()) "a key is set; paste a new one to replace it" else "paste the key (sk-…)"
            isSingleLine = true; imeOptions = noFullscreenIme
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Decisions API key").setView(field).setPositiveButton("Save") { _, _ ->
            val k = field.text.toString().trim()
            if (k.isEmpty()) return@setPositiveButton
            f.writeText(k)
            Toast.makeText(this, "key saved; it is used from the next Start", Toast.LENGTH_LONG).show()
        }.setNeutralButton("Remove") { _, _ -> f.delete(); Toast.makeText(this, "key removed: checks are off", Toast.LENGTH_LONG).show() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Type an order (spec §1.5): an id and one or more recipes, each with optional modifiers ("no basil, +olives"). */
    private fun newOrder() {
        val s = AnalyticsService.current?.takeIf { it.state == "running" }
            ?: return Toast.makeText(this, "start a run first: orders go to the running site", Toast.LENGTH_LONG).show()
        val ids = s.orderIds
        val id = EditText(this).apply { setText("T-%03d".format(ids.size + 1)); isSingleLine = true; imeOptions = noFullscreenIme }
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = mutableListOf<Pair<String, EditText>>()
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in s.recipes) menu.addView(Button(this).apply { text = "+ $r"; isAllCaps = false; setOnClickListener {
            val mods = EditText(this@MainActivity).apply { hint = "modifiers: no basil, +olives"; isSingleLine = true; imeOptions = noFullscreenIme }
            rows += r to mods
            items.addView(TextView(this@MainActivity).apply { text = r; textSize = 16f })
            items.addView(mods)
        } })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0)
            addView(TextView(this@MainActivity).apply { text = "Order id" }); addView(id)
            addView(TextView(this@MainActivity).apply { text = "Add dishes" }); addView(menu); addView(items) }
        AlertDialog.Builder(this).setTitle("New order").setView(ScrollView(this).apply { addView(body) }).setPositiveButton("Add order") { _, _ ->
            val oid = id.text.toString().trim()
            when {
                oid.isEmpty() || rows.isEmpty() -> Toast.makeText(this, "an order needs an id and at least one dish", Toast.LENGTH_LONG).show()
                oid in ids -> Toast.makeText(this, "order $oid already exists", Toast.LENGTH_LONG).show()
                else -> s.addOrder(Order(oid, rows.map { (r, m) -> OrderItem(r, m.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }) }))
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val s = AnalyticsService.current
        if (s == null) {
            status.text = "idle — no run · app ${packageManager.getPackageInfo(packageName, 0).versionName} · API key " +
                if (java.io.File(filesDir, "openai.key").exists()) "set" else "NOT SET (checks off)"
            return
        }
        status.text = "${s.cfg.site} · ${s.cfg.runId}\n${s.statusText.ifEmpty { s.state }}"
        for ((name, bm) in s.previews) {
            val v = views.getOrPut(name) {
                ImageView(this).apply { adjustViewBounds = true; setPadding(4, 4, 4, 4) }.also { cams.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            }
            v.setImageBitmap(bm)
        }
        orders.text = s.orderText
        feed.text = synchronized(s.feed) { s.feed.joinToString("\n") }
    }
}
