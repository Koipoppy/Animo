package com.animo.ar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process alive while Lux3D is modelling / rigging / retargeting. The work itself stays
 * in AppState's ViewModel scope; this service only raises the process priority so a long run is not
 * killed when the user switches apps or locks the screen.
 */
class GenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "手办生成",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "建模、绑骨和动作进行中的常驻提醒"
                    setShowBadge(false)
                }
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Animo 正在制作手办")
            .setContentText("建模、绑骨和动作还在进行，切到别的应用也不会中断")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "animo_generation"
        const val NOTIFICATION_ID = 4201
    }
}
