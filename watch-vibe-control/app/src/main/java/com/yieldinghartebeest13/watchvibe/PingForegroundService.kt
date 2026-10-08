package com.yieldinghartebeest13.watchvibe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Sole active-mode heartbeat owner, both foreground and background.
 * Without this, Android freezes the ViewModel's coroutine and the watch's
 * 3-second vibration lease expires → vibration stops.
 *
 * Starts when vibration is active, stops when vibration is STOPPED/PAUSED.
 * Holds a PARTIAL_WAKE_LOCK so the CPU stays awake even with the screen off.
 */
open class PingForegroundService : Service() {

    companion object {
        private const val TAG = "VibePingSvc"
        const val CHANNEL_ID = "ping_heartbeat"
        const val NOTIFICATION_ID = 2

        // Intent extras for updating notification text
        const val EXTRA_MODE_LABEL = "modeLabel"
        const val EXTRA_SPEED_LABEL = "speedLabel"

        // Intent action to update the notification text from the ViewModel
        const val ACTION_UPDATE_STATUS = "com.yieldinghartebeest13.watchvibe.PING_UPDATE_STATUS"
    }

    private lateinit var wearDataLayer: WearDataLayer
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var wakeLockJob: Job? = null
    private var wakeLockMaintenanceEnabled = true
    private var currentTitle: String = "WatchVibe"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        wearDataLayer = WearDataLayer.getInstance(this)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VibePingSvc:wakelock"
        )
        wakeLock.setReferenceCounted(false)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        if (!wearDataLayer.heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE)) {
            // A delayed start must never restart pings after an explicit STOP.
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (intent?.action == ACTION_UPDATE_STATUS) {
                val modeLabel = intent.getStringExtra(EXTRA_MODE_LABEL)
                val speedLabel = intent.getStringExtra(EXTRA_SPEED_LABEL)
                if (modeLabel != null && speedLabel != null) currentTitle = "$modeLabel — $speedLabel"
            }
            startForegroundCompat(NOTIFICATION_ID, buildNotification())
            if (wakeLockJob?.isActive != true) {
                acquireWakeLock(WAKE_LOCK_TIMEOUT_MS)
                wakeLockJob = serviceScope.launch { maintainWakeLock(::acquireWakeLock) }
            }
            startHeartbeat()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Foreground promotion failed; stopping session", e)
            wearDataLayer.heartbeat.setOwner(PhoneHeartbeat.Owner.NONE)
            wearDataLayer.onHeartbeatFailure?.invoke()
            // This bounded STOP must survive immediate service destruction.
            CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
                wearDataLayer.sendControl(AppConstants.MODE_STOP, 0, 0)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — stopping heartbeat")
        heartbeatJob?.cancel()
        heartbeatJob = null
        wakeLockJob?.cancel()
        wakeLockJob = null
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Heartbeat ──────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob = wearDataLayer.heartbeat.start(PhoneHeartbeat.Owner.SERVICE, serviceScope) {
            wearDataLayer.sendPing()
        }
    }

    // ── Wake Lock ──────────────────────────────────────────

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            synchronized(wakeLock) {
                if (wakeLockMaintenanceEnabled && wearDataLayer.heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE)) {
                    // Non-reference-counted acquire renews the timeout while held.
                    wakeLock.acquire(timeoutMs)
                    Log.d(TAG, "WakeLock acquired/renewed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            synchronized(wakeLock) {
                wakeLockMaintenanceEnabled = false
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (_: Exception) {}
    }

    // ── Notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vibration Heartbeat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while vibration is active to keep the connection alive"
                setSound(null, null)
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText("Vibration active — connection kept alive")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(currentTitle)
                .setContentText("Vibration active — connection kept alive")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    // ── Helpers ────────────────────────────────────────────

    protected open fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }
}
