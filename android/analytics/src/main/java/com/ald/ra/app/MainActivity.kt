package com.ald.ra.app

import android.app.Activity
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Live screen + entry point for adb:
 *   am start -n com.ald.ra/.MainActivity --es mode start --es site pizza_trailer [--es speed 2] [--es params '{...}']
 *   am start -n com.ald.ra/.MainActivity --es mode stop
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
        val start = Button(this).apply { text = "Start"; setOnClickListener {
            startForegroundService(Intent(this@MainActivity, AnalyticsService::class.java).putExtra("mode", "start")
                .putExtra("site", AnalyticsService.current?.cfg?.site ?: "pizza_trailer"))
        } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener {
            startForegroundService(Intent(this@MainActivity, AnalyticsService::class.java).putExtra("mode", "stop"))
        } }
        val top = LinearLayout(this).apply { addView(start); addView(stop); addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
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

    private fun refresh() {
        val s = AnalyticsService.current
        if (s == null) { status.text = "idle — no run"; return }
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
