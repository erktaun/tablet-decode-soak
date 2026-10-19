package com.ald.soak

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Status screen + entry point for adb:
 *   am start -n com.ald.soak/.MainActivity --es mode caps --es run_id X     (Experiment 0 only)
 *   am start -n com.ald.soak/.MainActivity --es source rtsp --es urls ...   (start a soak run)
 *   am start -n com.ald.soak/.MainActivity --es mode stop
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var grid: GridLayout
    private val previews = ArrayList<ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        // Creates files/clips and files/runs so adb can push into / pull from them.
        Config.clipsDir(this); Config.runsDir(this)
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val extras = intent?.extras ?: return
        when (extras.getString("mode")) {
            "caps" -> runCaps(extras.getString("run_id") ?: Config.newRunId("caps"))
            "stop" -> startService(Intent(this, SoakService::class.java).setAction(SoakService.ACTION_STOP))
            else -> if (extras.containsKey("source")) startRun(extras)
        }
    }

    private fun startRun(extras: Bundle) {
        val i = Intent(this, SoakService::class.java).setAction(SoakService.ACTION_START).putExtras(extras)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun runCaps(runId: String) {
        status.text = "Querying decoders…"
        Thread {
            val dir = File(Config.runsDir(this), runId).apply { mkdirs() }
            val text = try {
                Caps.summary(Caps.write(this, File(dir, "caps.json")))
            } catch (e: Exception) {
                "caps failed: $e"
            }
            File(dir, "caps_done").writeText("ok")
            ui.post { status.text = "Experiment 0 — decoder limits\n$text\nSaved to ${dir.absolutePath}" }
        }.start()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.BLACK)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
            buttons.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        button("Start (local clips)") {
            if (Config.localClips(this).isEmpty()) status.text = "No clips in ${Config.clipsDir(this)} — push them with run_test.py"
            else startRun(Bundle().apply { putString("source", "file") })
        }
        button("Stop") { startService(Intent(this, SoakService::class.java).setAction(SoakService.ACTION_STOP)) }
        button("Decoder limits") { runCaps(Config.newRunId("caps")) }
        root.addView(buttons)

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.WHITE)
            text = "Idle. Start a run from the laptop (run_test.py) or with the button above."
        }
        root.addView(status)

        grid = GridLayout(this).apply { columnCount = 2 }
        for (i in 0 until 4) {
            val iv = ImageView(this).apply { adjustViewBounds = true }
            previews.add(iv)
            grid.addView(iv, GridLayout.LayoutParams().apply {
                width = 0; height = WRAP_CONTENT
                columnSpec = GridLayout.spec(i % 2, 1f); rowSpec = GridLayout.spec(i / 2)
                setMargins(4, 4, 4, 4)
            })
        }
        root.addView(grid)
        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })
    }

    private val refresh = object : Runnable {
        override fun run() {
            val s = SoakService.session
            if (s != null) {
                val events = s.log.tail().joinToString("\n")
                status.text = (if (s.finished) "FINISHED\n" else "") + s.status + "\n\n" + events
                s.workers.forEachIndexed { i, w -> w.preview?.let { previews.getOrNull(i)?.setImageBitmap(it) } }
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        SoakService.session?.workers?.forEach { it.previewWanted = true }
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        SoakService.session?.workers?.forEach { it.previewWanted = false }
        ui.removeCallbacks(refresh)
    }
}
