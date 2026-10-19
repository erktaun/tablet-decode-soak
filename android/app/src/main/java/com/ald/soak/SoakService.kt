package com.ald.soak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/** One running test: N stream workers + the metrics loop, writing into runs/<run_id>/. */
class Session(private val ctx: Context, val cfg: Config, private val onFinished: () -> Unit) {
    val dir = File(Config.runsDir(ctx), cfg.runId).apply { mkdirs() }
    val log = EventLog(File(dir, "events.log"))
    private val clock = HostClock(cfg.infoUrl, log)
    val workers = cfg.uris.mapIndexed { i, u -> StreamWorker(ctx, i, u, cfg, log, clock) }
    private val metrics = Metrics(ctx, cfg, workers, clock, File(dir, "metrics.jsonl"))
    private val thread = HandlerThread("metrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val startMs = System.currentTimeMillis()
    @Volatile var finished = false
        private set

    val status: String get() = metrics.status

    fun start() {
        File(dir, "config.json").writeText(cfg.toJson().toString(2))
        log.log(null, "run_start", "${cfg.uris.size} streams, source=${cfg.source}, sink=${cfg.sink}")
        handler.post {
            // Experiment 0 data travels with every run; the probe releases its decoders before streams start.
            try { Caps.write(ctx, File(dir, "caps.json")) } catch (e: Exception) { log.log(null, "caps_error", e.toString()) }
            clock.refresh()
            workers.forEach { it.start() }
            handler.postDelayed(tick, cfg.periodS * 1000L)
        }
    }

    private var ticks = 0
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            try { metrics.sample() } catch (e: Exception) { log.log(null, "metrics_error", e.toString()) }
            ticks++
            // Off the metrics thread: during a Wi-Fi stall the HTTP calls can take seconds.
            if (cfg.infoUrl != null && ticks % (60 / cfg.periodS).coerceAtLeast(1) == 0 &&
                refreshing.compareAndSet(false, true)) {
                Thread { try { clock.refresh() } finally { refreshing.set(false) } }.start()
            }
            if (cfg.minutes > 0 && System.currentTimeMillis() - startMs >= cfg.minutes * 60_000) {
                stop("duration reached"); onFinished(); return
            }
            handler.postDelayed(this, cfg.periodS * 1000L)
        }
    }

    @Synchronized
    fun stop(reason: String) {
        if (finished) return
        finished = true
        workers.forEach { it.stop() }
        log.log(null, "run_end", reason)
        metrics.close()
        File(dir, "done.json").writeText(JSONObject()
            .put("reason", reason)
            .put("duration_s", (System.currentTimeMillis() - startMs) / 1000.0).toString())
        log.close()
        thread.quitSafely()
    }
}

/**
 * Foreground service so the test keeps running with the screen dimmed or the activity rotated.
 * Holds a partial wake lock (CPU) and, for RTSP, a Wi-Fi lock, like a production capture app would.
 */
class SoakService : Service() {
    companion object {
        const val ACTION_START = "com.ald.soak.START"
        const val ACTION_STOP = "com.ald.soak.STOP"
        @Volatile var session: Session? = null
            private set
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish("stopped"); return START_NOT_STICKY }
            ACTION_START -> {
                goForeground()
                session?.stop("replaced by a new run")
                val cfg = Config.from(this, intent.extras)
                acquireLocks(cfg)
                session = Session(this, cfg) { finish(null) }.also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(reason: String?) {
        reason?.let { session?.stop(it) }
        wake?.takeIf { it.isHeld }?.release(); wake = null
        wifi?.takeIf { it.isHeld }?.release(); wifi = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.stop("service destroyed")
        super.onDestroy()
    }

    private fun acquireLocks(cfg: Config) {
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soak:run").apply { acquire() }
        if (cfg.source == "rtsp") {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "soak:rtsp").apply { acquire() }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("soak", "Soak test", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "soak")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = builder.setContentTitle("Decode soak test running")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
    }
}
