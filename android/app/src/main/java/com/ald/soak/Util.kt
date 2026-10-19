package com.ald.soak

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable event log (decoder picked, errors, restarts) -> events.log, plus a tail for the UI. */
class EventLog(file: File) {
    private val out = FileWriter(file, true)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val tail = ArrayDeque<String>()

    @Synchronized
    fun log(stream: Int?, what: String, detail: String = "") {
        val line = "${fmt.format(Date())} ${stream?.let { "s$it" } ?: "--"} $what $detail".trimEnd()
        Log.i("soak", line)
        out.write(line + "\n"); out.flush()
        tail.addLast(line); if (tail.size > 6) tail.removeFirst()
    }

    @Synchronized
    fun tail(): List<String> = tail.toList()

    @Synchronized
    fun close() = out.close()
}

/**
 * Maps device time to the laptop's clock and holds each sender's start time, both fetched from the
 * laptop's /info endpoint (run_test.py). Used only to turn the frame barcode into stream latency.
 */
class HostClock(private val infoUrl: String?, private val log: EventLog) {
    @Volatile private var offsetMs: Long? = null   // host - device
    @Volatile private var t0: LongArray = LongArray(0)
    @Volatile var rttMs: Long? = null
        private set

    fun refresh() {
        if (infoUrl == null) return
        var best = Long.MAX_VALUE
        repeat(5) {
            try {
                val t1 = System.currentTimeMillis()
                val conn = URL(infoUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000; conn.readTimeout = 2000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val t2 = System.currentTimeMillis()
                conn.disconnect()
                val j = JSONObject(body)
                if (t2 - t1 < best) {
                    best = t2 - t1
                    offsetMs = j.getLong("now_ms") - (t1 + t2) / 2
                    val a = j.getJSONArray("t0_ms")
                    t0 = LongArray(a.length()) { a.getLong(it) }
                }
            } catch (e: Exception) {
                if (it == 4 && best == Long.MAX_VALUE) log.log(null, "clock_error", e.toString())
            }
        }
        if (best != Long.MAX_VALUE) rttMs = best
    }

    fun toHostMs(deviceMs: Long): Long? = offsetMs?.let { deviceMs + it }
    fun t0(stream: Int): Long? = t0.getOrNull(stream)?.takeIf { it > 0 }
    fun offset(): Long? = offsetMs
}
