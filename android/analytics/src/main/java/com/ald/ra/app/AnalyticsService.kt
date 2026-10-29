package com.ald.ra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps a run going with the screen off (foreground service + wake lock), like the soak app. */
class AnalyticsService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("run", "Analytics run", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "run").setContentTitle("Restaurant analytics running")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1, n)
        if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ra:run").apply { acquire() }
        when (intent?.getStringExtra("mode")) {
            "stop" -> { current?.stop(); current = null; stopSelf() }
            else -> if (current == null || current?.state == "finished" || current?.state?.startsWith("failed") == true) {
                current?.stop()
                current = Session(this, RunCfg.from(intent?.extras)).also { s -> s.onUpdate = { listener?.invoke() }; s.start() }
                listener?.invoke()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        @Volatile var current: Session? = null
        @Volatile var listener: (() -> Unit)? = null
    }
}
