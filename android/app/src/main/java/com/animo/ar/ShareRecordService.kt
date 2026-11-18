package com.animo.ar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * Captures a short, UI-free clip of the AR view for sharing. MediaProjection mirrors the display
 * into a MediaRecorder surface; PlayScreen hides its overlays while [ShareCenter.recording].
 */
class ShareRecordService : Service() {

    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var stopping = false
    private var outputPath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopRecording() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        val output = intent?.getStringExtra(EXTRA_OUTPUT)
        if (data == null || output.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        outputPath = output
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mediaProjection
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() = stopRecording()
            },
            handler,
        )

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels and 1.inv()
        val height = metrics.heightPixels and 1.inv()
        val record = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = record
        runCatching {
            record.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            record.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            record.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            record.setVideoSize(width, height)
            record.setVideoFrameRate(30)
            record.setVideoEncodingBitRate(8_000_000)
            record.setOutputFile(output)
            record.prepare()
            display = mediaProjection.createVirtualDisplay(
                "animo-share",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                record.surface,
                null,
                handler,
            )
            record.start()
            handler.postDelayed(stopRunnable, DURATION_MS)
        }.onFailure { stopRecording() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channelId = "animo-share"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "动态照片", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("正在录制动态照片")
            .setContentText("约 6 秒，完成后可直接分享")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(stopRunnable)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        display?.release()
        display = null
        val active = projection
        projection = null
        runCatching { active?.stop() }
        outputPath?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) ShareCenter.lastVideo = file
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_OUTPUT = "output"
        private const val DURATION_MS = 6_000L
        private const val NOTIFICATION_ID = 41
    }
}
