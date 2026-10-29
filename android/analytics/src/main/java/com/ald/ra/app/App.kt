package com.ald.ra.app

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any crash to files/crashes/crash-<time>.txt (and into the current run's folder), so it reaches us with the
 * logs (tools/pull_report.py) instead of only the system log, which is gone after a reboot.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val now = Date()
                val text = "crash at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(now)} in thread ${thread.name}\n" +
                    "run: ${AnalyticsService.current?.cfg?.runId} (state ${AnalyticsService.current?.state})\n\n" + e.stackTraceToString()
                val name = "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}.txt"
                File(getExternalFilesDir("crashes"), name).writeText(text)
                AnalyticsService.current?.dir?.let { File(it, name).writeText(text) }
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
