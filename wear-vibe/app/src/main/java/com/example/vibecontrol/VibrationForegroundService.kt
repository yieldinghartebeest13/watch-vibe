package com.example.vibecontrol

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
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the vibration listener alive even with the
 * screen off or the app in the background.
 *
 * Responsibilities:
 *  - DataClient.addListener()   → persistent control (survives disconnects)
 *  - MessageClient.addListener() → real-time control (lowest latency)
 *  - CapabilityClient.addListener() → detect phone disconnect → stop vibration
 *  - Holds the single VibratorEngine instance
 *  - Acquires a partial WAKE_LOCK so the CPU stays awake
 */
class VibrationForegroundService : Service() {

    companion object {
        private const val TAG = "VibeSvc"
        const val CHANNEL_ID = "vibration_control"
        const val NOTIFICATION_ID = 1

        const val PATH_CONTROL = "/control"
        const val KEY_MODE = "wear_mode"
        const val KEY_LEVEL = "wear_level"
        const val KEY_INTENSITY = "wear_intensity"
        const val CAPABILITY_VIBRATION = "vibration_control"
        const val PATH_LAUNCH = "/launch"

        // Actions for binding from Activity
        const val ACTION_STOP_VIBRATION = "com.example.vibecontrol.STOP"
        const val ACTION_QUERY_STATUS = "com.example.vibecontrol.QUERY_STATUS"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_ACTIVE = "active"

        // Broadcast for status updates to Activity
        const val BROADCAST_STATUS = "com.example.vibecontrol.STATUS"
        const val HEARTBEAT_TIMEOUT_MS = 2_000L
        const val EXTRA_PHONE_CONNECTED = "phoneConnected"
    }

    private lateinit var vibratorEngine: VibratorEngine
    private lateinit var wakeLock: PowerManager.WakeLock
    private var dataListener: DataClient.OnDataChangedListener? = null
    private var messageListener: MessageClient.OnMessageReceivedListener? = null
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var lastMode: Int = VibratorEngine.MODE_PAUSE
    private var lastLevel: Int = 0
    private var lastIntensity: Int = 100
    private var lastPingTime: Long = 0
    private var lastPingCounter: Long = -1
    private var heartbeatChecker: Job? = null
    private var phoneConnected: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "onCreate")

        vibratorEngine = VibratorEngine(this)
        if (!vibratorEngine.hasVibrator()) {
            Log.e(TAG, "No vibrator — service will run but won't vibrate")
        }

        // Partial wake lock keeps CPU on when screen is off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VibeSvc:wakelock"
        )
        wakeLock.setReferenceCounted(false)

        createNotificationChannel()
        startListeners()
        startHeartbeatMonitor()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "onStartCommand")

        // Handle actions from Activity
        when (intent?.action) {
            ACTION_STOP_VIBRATION -> {
                vibratorEngine.cancel()
                broadcastStatus()
            }
            ACTION_QUERY_STATUS -> {
                broadcastStatus()
            }
        }

        startForegroundCompat(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.e(TAG, "onDestroy — cleaning up")
        heartbeatChecker?.cancel()
        vibratorEngine.cancel()
        stopListeners()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Listeners ──────────────────────────────────────────

    private fun startListeners() {
        startDataListener()
        startMessageListener()
        startCapabilityListener()
    }

    private fun stopListeners() {
        dataListener?.let { Wearable.getDataClient(this).removeListener(it) }
        messageListener?.let { Wearable.getMessageClient(this).removeListener(it) }
        capabilityListener?.let { Wearable.getCapabilityClient(this).removeListener(it) }
    }

    private fun startDataListener() {
        val listener = DataClient.OnDataChangedListener { dataEvents: DataEventBuffer ->
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                val path = item.uri.path ?: continue

                when (path) {
                    PATH_CONTROL -> {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val mode = map.getInt(KEY_MODE, VibratorEngine.MODE_PAUSE)
                        val level = map.getInt(KEY_LEVEL, 0)
                        val intensity = map.getInt(KEY_INTENSITY, 100)

                        Log.e(TAG, "DataItem: mode=$mode level=$level intensity=$intensity")
                        lastMode = mode; lastLevel = level; lastIntensity = intensity
                        vibratorEngine.setModeVibration(mode, level, intensity)
                        broadcastStatus()

                        // Delete STOP/PAUSE data items to prevent stale re-delivery.
                        // On Wear OS 5, old DataItems can persist and be re-synced
                        // from the phone, restarting vibration after cancellation.
                        if (mode == VibratorEngine.MODE_STOP || mode == VibratorEngine.MODE_PAUSE) {
                            try {
                                Wearable.getDataClient(this@VibrationForegroundService)
                                    .deleteDataItems(item.uri)
                                Log.e(TAG, "Deleted STOP/PAUSE data item to prevent re-delivery")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to delete data item: ${e.message}")
                            }
                        }
                    }
                    PATH_LAUNCH -> {
                        Log.e(TAG, "Launch request from phone")
                        startMainActivity()
                    }
                    "/ping" -> {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val counter = map.getLong("counter", -1)
                        if (counter > lastPingCounter) {
                            lastPingCounter = counter
                            lastPingTime = System.currentTimeMillis()
                        }
                        // else: stale re-delivery, ignore
                    }
                }
            }
            dataEvents.release()
        }
        dataListener = listener
        Wearable.getDataClient(this).addListener(listener)
        Log.e(TAG, "DataClient listener registered")
    }

    private fun startMessageListener() {
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            when (event.path) {
                PATH_CONTROL -> {
                    val body = String(event.data)
                    val parts = body.split(",")
                    // Format: mode,level,intensity (3-part)
                    // Legacy: mode,level (2-part) — intensity defaults to 100
                    val mode: Int
                    val level: Int
                    val intensity: Int
                    when (parts.size) {
                        2 -> {
                            mode = parts[0].toIntOrNull() ?: return@OnMessageReceivedListener
                            level = parts[1].toIntOrNull() ?: return@OnMessageReceivedListener
                            intensity = 100
                        }
                        3 -> {
                            mode = parts[0].toIntOrNull() ?: return@OnMessageReceivedListener
                            level = parts[1].toIntOrNull() ?: return@OnMessageReceivedListener
                            intensity = parts[2].toIntOrNull() ?: 100
                        }
                        else -> return@OnMessageReceivedListener
                    }

                    Log.e(TAG, "Message: mode=$mode level=$level intensity=$intensity")
                    lastMode = mode; lastLevel = level; lastIntensity = intensity
                    vibratorEngine.setModeVibration(mode, level, intensity)
                    broadcastStatus()
                }
                PATH_LAUNCH -> {
                    Log.e(TAG, "Launch message from phone")
                    startMainActivity()
                }
            }
        }
        messageListener = listener
        Wearable.getMessageClient(this).addListener(listener)
        Log.e(TAG, "MessageClient listener registered")
    }

    private fun startCapabilityListener() {
        val capClient = Wearable.getCapabilityClient(this)
        val listener = CapabilityClient.OnCapabilityChangedListener { capInfo ->
            val nodes = capInfo.nodes
            val wasConnected = phoneConnected
            phoneConnected = nodes.isNotEmpty()
            Log.e(TAG, "Capability changed: ${nodes.size} nodes (was=$wasConnected now=$phoneConnected)")

            if (phoneConnected) {
                lastPingTime = System.currentTimeMillis()
            }

            if (wasConnected && !phoneConnected) {
                // Phone disconnected — stop vibration immediately
                Log.e(TAG, "Phone disconnected → stopping vibration")
                vibratorEngine.cancel()
                broadcastStatus()
            }
        }
        capabilityListener = listener

        capClient.addListener(listener, CAPABILITY_VIBRATION)
            .addOnSuccessListener {
                Log.e(TAG, "Capability listener registered")
                // Also advertise our own capability so phone can see us
                capClient.addLocalCapability(CAPABILITY_VIBRATION)
            }
            .addOnFailureListener {
                Log.e(TAG, "Capability listener FAIL: ${it.message}")
            }
    }

    private fun startHeartbeatMonitor() {
        lastPingTime = System.currentTimeMillis()
        heartbeatChecker = CoroutineScope(Dispatchers.IO).launch {
            delay(2000) // give initial connection time
            Log.e(TAG, "Heartbeat monitor started (timeout=${HEARTBEAT_TIMEOUT_MS}ms)")
            while (isActive) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - lastPingTime
                if (elapsed > HEARTBEAT_TIMEOUT_MS && phoneConnected) {
                    phoneConnected = false
                    Log.e(TAG, "Heartbeat timeout! (${elapsed}ms) → stopping vibration")
                    vibratorEngine.cancel()
                    broadcastStatus()
                } else if (elapsed <= HEARTBEAT_TIMEOUT_MS && !phoneConnected) {
                    phoneConnected = true
                    Log.e(TAG, "Phone reconnected — resuming vibration mode=$lastMode")
                    if (lastMode != VibratorEngine.MODE_STOP && lastMode != VibratorEngine.MODE_PAUSE) {
                        vibratorEngine.setModeVibration(lastMode, lastLevel, lastIntensity)
                    }
                    broadcastStatus()
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ — use specialUse (vibration type is API 35+ only)
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun startMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity: ${e.message}")
        }
    }

    private fun broadcastStatus() {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_MODE, vibratorEngine.mode)
            putExtra(EXTRA_LEVEL, vibratorEngine.level)
            putExtra(EXTRA_INTENSITY, vibratorEngine.intensity)
            putExtra(EXTRA_ACTIVE, vibratorEngine.vibrating)
            putExtra(EXTRA_PHONE_CONNECTED, phoneConnected)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire(60 * 60 * 1000L) // 1 hour timeout
                Log.e(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.e(TAG, "WakeLock released")
            }
        } catch (_: Exception) {}
    }

    // ── Notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vibration Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps vibration control active with your phone"
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
                .setContentTitle("Vibration Control")
                .setContentText(if (phoneConnected) "Connected to phone" else "Waiting for phone")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Vibration Control")
                .setContentText(if (phoneConnected) "Connected to phone" else "Waiting for phone")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}
