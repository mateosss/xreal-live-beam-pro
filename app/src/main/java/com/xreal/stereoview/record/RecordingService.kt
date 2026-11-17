package com.xreal.stereoview.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.MainActivity
import com.xreal.stereoview.R

/**
 * Keeps a recording alive while the screen is locked or the app is in the background: a
 * foreground service (camera and USB device types, so Android keeps camera access, sensor
 * events and the glasses connection) holding a partial wake lock so the CPU keeps running.
 * The recording itself stays in the activity; this only keeps the process in the foreground.
 * The notification's Stop action calls [onStopRequested].
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "dataset"
        val glasses = intent?.getBooleanExtra(EXTRA_USB, false) ?: false
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a dataset is being recorded"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("Recording $name")
            .setContentText("Keeps recording with the screen locked")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - (intent?.getLongExtra(EXTRA_START_MS, 0L) ?: 0L)).coerceAtLeast(0))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (glasses) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        try {
            startForeground(NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            // E.g. a missing prerequisite for a type: still keep the CPU awake.
            Log.w(TAG, "startForeground with types $types failed", e)
            runCatching { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StereoView:recording")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.xreal.stereoview.STOP_RECORDING"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_USB = "usb"
        private const val EXTRA_START_MS = "start_elapsed_ms"

        /** Set by the activity while it records; called on the main thread. */
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, name: String, usbDevice: Boolean) {
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_USB, usbDevice)
                .putExtra(EXTRA_START_MS, SystemClock.elapsedRealtime())
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start the recording service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
