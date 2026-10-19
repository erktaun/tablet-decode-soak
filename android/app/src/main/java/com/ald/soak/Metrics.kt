package com.ald.soak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/** Small readers for /sys and /proc; each returns null where the OS denies access to apps. */
object Sys {
    private fun cpuDirs(): List<File> =
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.sortedBy { it.name.removePrefix("cpu").toInt() } ?: emptyList()

    private fun readLong(f: File): Long? = try { f.readText().trim().toLong() } catch (e: Exception) { null }

    fun cpuMaxFreqs(): List<Long?> = cpuDirs().map { readLong(File(it, "cpufreq/cpuinfo_max_freq")) }
    fun cpuCurFreqs(): List<Long?> = cpuDirs().map { readLong(File(it, "cpufreq/scaling_cur_freq")) }
}

/**
 * Every period: per-stream frame stats and device health -> one JSON line in metrics.jsonl.
 * System-wide CPU and thermal-zone temperatures are not readable by apps on modern Android;
 * sampler.sh (run over adb as the shell user) logs those separately.
 */
class Metrics(
    private val ctx: Context,
    private val cfg: Config,
    private val workers: List<StreamWorker>,
    private val clock: HostClock,
    file: File,
) {
    private val out = FileWriter(file, true)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val am = ctx.getSystemService(ActivityManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()
    private val startMs = SystemClock.elapsedRealtime()
    private var lastMs = startMs
    private var lastCpuMs = Process.getElapsedCpuTime()
    private val prev = HashMap<String, Long>()

    @Volatile var status: String = "starting"
        private set

    private fun delta(key: String, now: Long): Long {
        val d = now - (prev[key] ?: 0L); prev[key] = now; return d
    }

    fun sample() {
        val nowMs = SystemClock.elapsedRealtime()
        val dt = (nowMs - lastMs).coerceAtLeast(1) / 1000.0
        lastMs = nowMs
        val j = JSONObject()
            .put("t_ms", System.currentTimeMillis())
            .put("elapsed_s", (nowMs - startMs) / 1000.0)

        val streams = JSONArray()
        val lines = StringBuilder()
        for (w in workers) {
            val k = "s${w.id}."
            val frames = delta(k + "frames", w.frames.get())
            val processed = delta(k + "processed", w.processed.get())
            val procNs = delta(k + "procNs", w.procNs.get())
            val lat = w.drainLatencies().also { it.sort() }
            val s = JSONObject()
                .put("id", w.id)
                .put("fps", frames / dt)
                .put("proc_fps", processed / dt)
                .put("proc_ms", if (processed > 0) procNs / processed / 1e6 else JSONObject.NULL)
                .put("frames", frames)
                .put("gaps", delta(k + "gaps", w.gaps.get()))
                .put("repeats", delta(k + "repeats", w.repeats.get()))
                .put("unreadable", delta(k + "unreadable", w.unreadable.get()))
                .put("dropped", delta(k + "dropped", w.dropped.get()))
                .put("frame_errors", delta(k + "frameErrors", w.frameErrors.get()))
                .put("errors_total", w.errors.get())
                .put("restarts_total", w.restarts.get())
                .put("stalls_total", w.stalls.get())
                .put("catchups_total", w.catchups.get())
                .put("buffer_ms", w.bufferedMs)
                .put("speed", w.speed.toDouble())
                .put("decoder", w.decoder)
                .put("size", w.size)
                .put("state", w.state)
                .put("last_error", w.lastError)
            if (lat.isNotEmpty()) {
                s.put("lat_p50_ms", lat[lat.size / 2]).put("lat_p95_ms", lat[(lat.size * 95) / 100])
                    .put("lat_max_ms", lat.last())
            }
            streams.put(s)
            lines.append(String.format("s%d %5.1f fps  drop %d  gap %d  %s  %s\n", w.id, frames / dt,
                w.dropped.get(), w.gaps.get(), w.state, w.decoder ?: "-"))
        }
        j.put("streams", streams)

        // Thermal: status 0 none .. 6 shutdown; headroom 1.0 = throttling threshold.
        var thermal = -1
        if (Build.VERSION.SDK_INT >= 29) { thermal = pm.currentThermalStatus; j.put("thermal_status", thermal) }
        if (Build.VERSION.SDK_INT >= 30) {
            val h = pm.getThermalHeadroom(10)
            j.put("thermal_headroom", if (h.isNaN()) JSONObject.NULL else h.toDouble())
        }

        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val plugged = when (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"; BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; 0 -> "none"; else -> "unknown"
        }
        j.put("battery_temp_c", tempC ?: JSONObject.NULL)
            .put("battery_level", batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("plugged", plugged)
            .put("battery_current_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("power_save", pm.isPowerSaveMode)

        val cpuMs = Process.getElapsedCpuTime()
        val appCpu = (cpuMs - lastCpuMs) / (dt * 1000.0 * cores) * 100.0
        lastCpuMs = cpuMs
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dmi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        j.put("app_cpu_pct", appCpu)
            .put("cores", cores)
            .put("cpu_cur_khz", JSONArray(Sys.cpuCurFreqs()))
            .put("mem_avail_mb", mi.availMem / (1024 * 1024))
            .put("mem_low", mi.lowMemory)
            .put("app_pss_mb", dmi.totalPss / 1024)
            .put("clock_offset_ms", clock.offset() ?: JSONObject.NULL)
            .put("clock_rtt_ms", clock.rttMs ?: JSONObject.NULL)

        out.write(j.toString() + "\n"); out.flush()

        val el = (nowMs - startMs) / 1000
        status = String.format("run %s  %02d:%02d:%02d  %s\n", cfg.runId, el / 3600, el / 60 % 60, el % 60, cfg.source) +
            lines +
            String.format("thermal %s  headroom %s  battery %s°C %s  app CPU %.0f%%  avail %d MB",
                if (thermal >= 0) thermal.toString() else "n/a",
                j.opt("thermal_headroom")?.let { if (it is Double) String.format("%.2f", it) else "n/a" } ?: "n/a",
                tempC?.toString() ?: "?", plugged, appCpu, mi.availMem / (1024 * 1024))
    }

    fun close() = out.close()
}
