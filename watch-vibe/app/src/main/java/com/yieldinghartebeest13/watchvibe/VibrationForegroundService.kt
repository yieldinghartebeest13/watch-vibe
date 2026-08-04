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
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

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

        // Actions for binding from Activity
        const val ACTION_STOP_VIBRATION = "com.yieldinghartebeest13.watchvibe.STOP"
        const val ACTION_QUERY_STATUS = "com.yieldinghartebeest13.watchvibe.QUERY_STATUS"
        const val ACTION_MINIMIZE = "com.yieldinghartebeest13.watchvibe.MINIMIZE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_ACTIVE = "active"

        // Broadcast for status updates to Activity
        const val BROADCAST_STATUS = "com.yieldinghartebeest13.watchvibe.STATUS"
        const val EXTRA_PHONE_CONNECTED = "phoneConnected"
    }

    private lateinit var vibratorEngine: VibratorEngine
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dataListener: DataClient.OnDataChangedListener? = null
    private var messageListener: MessageClient.OnMessageReceivedListener? = null
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var lastMode: Int = AppConstants.MODE_PAUSE
    private var lastLevel: Int = 0
    private var lastIntensity: Int = 100
    // Dual-lease model (fails-safe):
    // - connectionLeaseExpiry: extended by every ping & command. Drives UI status.
    //   NEVER zeroed by STOP — only by disconnect or natural expiry.
    // - vibrationLeaseExpiry: extended by pings, set by non-STOP commands,
    //   zeroed by STOP/PAUSE. Drives vibration cancellation.
    // If connection lease expires → vibration lease also expires → cancel.
    @Volatile private var connectionLeaseExpiry: Long = 0
    @Volatile private var vibrationLeaseExpiry: Long = 0
    @Volatile private var lastPingCounter: Long = -1
    @Volatile private var lastSessionId: Long = 0
    @Volatile private var lastCommandTimestamp: Long = 0
    private var heartbeatChecker: Job? = null
    @Volatile private var phoneConnected: Boolean = false
    private var minimizeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        vibratorEngine = VibratorEngine(this)
        if (!vibratorEngine.hasVibrator()) {
            Log.w(TAG, "No vibrator — service will run but won't vibrate")
        }

        // Mark foreground service as running for VibrationDataLayerService
        VibrationDataLayerService.isForegroundServiceRunning = true

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
        Log.d(TAG, "onStartCommand")

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
        Log.d(TAG, "onDestroy — cleaning up")
        heartbeatChecker?.cancel()
        vibratorEngine.cancel()
        stopListeners()
        releaseWakeLock()
        serviceScope.cancel()
        VibrationDataLayerService.isForegroundServiceRunning = false
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
                    AppConstants.PATH_CONTROL -> {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val mode = map.getInt(AppConstants.KEY_MODE, AppConstants.MODE_PAUSE)
                        val level = map.getInt(AppConstants.KEY_LEVEL, 0)
                        val intensity = map.getInt(AppConstants.KEY_INTENSITY, 100)
                        val ts = map.getLong(AppConstants.KEY_TIMESTAMP, 0L)

                        if (isStaleCommand(ts)) {
                            Log.d(TAG, "Ignoring stale DataItem: mode=$mode (${System.currentTimeMillis() - ts}ms old)")
                            if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
                                try {
                                    Wearable.getDataClient(this@VibrationForegroundService)
                                        .deleteDataItems(item.uri)
                                } catch (_: Exception) {}
                            }
                            continue
                        }

                        Log.d(TAG, "DataItem: mode=$mode level=$level intensity=$intensity")
                        lastMode = mode; lastLevel = level; lastIntensity = intensity
                        lastCommandTimestamp = ts
                        vibratorEngine.setModeVibration(mode, level, intensity)
                        renewLeaseIfActive(mode)
                        broadcastStatus()

                        // Delete STOP/PAUSE data items to prevent stale re-delivery.
                        if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
                            try {
                                Wearable.getDataClient(this@VibrationForegroundService)
                                    .deleteDataItems(item.uri)
                                Log.d(TAG, "Deleted STOP/PAUSE data item to prevent re-delivery")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to delete data item: ${e.message}")
                            }
                        }
                    }
                    AppConstants.PATH_LAUNCH -> {
                        Log.d(TAG, "Launch request from phone")
                        startMainActivity()
                    }
                    AppConstants.PATH_MINIMIZE -> {
                        Log.d(TAG, "Minimize request from phone")
                        sendBroadcast(Intent(ACTION_MINIMIZE).apply { setPackage(packageName) })
                    }
                    AppConstants.PATH_PING -> {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val counter = map.getLong("counter", -1)
                        val sid = map.getLong("sessionId", 0L)
                        if (handlePing(counter, sid)) {
                            extendLease()
                        }
                    }
                }
            }
            dataEvents.release()
        }
        dataListener = listener
        Wearable.getDataClient(this).addListener(listener)
        Log.d(TAG, "DataClient listener registered")
    }

    private fun startMessageListener() {
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            when (event.path) {
                AppConstants.PATH_CONTROL -> {
                    val body = String(event.data)
                    val parts = body.split(",")
                    val mode: Int
                    val level: Int
                    val intensity: Int
                    val ts: Long
                    when (parts.size) {
                        2 -> {
                            mode = parts[0].toIntOrNull() ?: run {
                                Log.w(TAG, "Malformed message (non-numeric mode): $body")
                                return@OnMessageReceivedListener
                            }
                            level = parts[1].toIntOrNull() ?: run {
                                Log.w(TAG, "Malformed message (non-numeric level): $body")
                                return@OnMessageReceivedListener
                            }
                            intensity = 100; ts = 0L  // legacy: no timestamp
                        }
                        3 -> {
                            mode = parts[0].toIntOrNull() ?: run {
                                Log.w(TAG, "Malformed message (non-numeric mode): $body")
                                return@OnMessageReceivedListener
                            }
                            level = parts[1].toIntOrNull() ?: run {
                                Log.w(TAG, "Malformed message (non-numeric level): $body")
                                return@OnMessageReceivedListener
                            }
                            intensity = parts[2].toIntOrNull() ?: 100
                            ts = 0L  // legacy: no timestamp
                        }
                        4 -> {
                            mode = parts[0].toIntOrNull() ?: run {
                                Log.w(TAG, "Malformed message (non-numeric mode): $body")
                                return@OnMessageReceivedListener
                            }
                            level = parts[1].toIntOrNull() ?: run {
                                Log.w(TAG, "Malformed message (non-numeric level): $body")
                                return@OnMessageReceivedListener
                            }
                            intensity = parts[2].toIntOrNull() ?: 100
                            ts = parts[3].toLongOrNull() ?: 0L
                        }
                        else -> {
                            Log.w(TAG, "Malformed message (unexpected parts=${parts.size}): $body")
                            return@OnMessageReceivedListener
                        }
                    }

                    if (isStaleCommand(ts)) {
                        Log.d(TAG, "Ignoring stale message: mode=$mode (${System.currentTimeMillis() - ts}ms old)")
                        return@OnMessageReceivedListener
                    }

                    Log.d(TAG, "Message: mode=$mode level=$level intensity=$intensity")
                    lastMode = mode; lastLevel = level; lastIntensity = intensity
                    lastCommandTimestamp = ts
                    vibratorEngine.setModeVibration(mode, level, intensity)
                    renewLeaseIfActive(mode)
                    broadcastStatus()
                }
                AppConstants.PATH_LAUNCH -> {
                    Log.d(TAG, "Launch message from phone")
                    startMainActivity()
                }
                AppConstants.PATH_MINIMIZE -> {
                    Log.d(TAG, "Minimize message from phone")
                    sendBroadcast(Intent(ACTION_MINIMIZE).apply { setPackage(packageName) })
                }
                AppConstants.PATH_PING -> {
                    val body = String(event.data)
                    val parts = body.split(",")
                    val counter = parts.getOrNull(0)?.toLongOrNull() ?: -1
                    val sid = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    if (handlePing(counter, sid)) {
                        extendLease()
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown message path: ${event.path}")
                }
            }
        }
        messageListener = listener
        Wearable.getMessageClient(this).addListener(listener)
        Log.d(TAG, "MessageClient listener registered")
    }

    private fun startCapabilityListener() {
        val capClient = Wearable.getCapabilityClient(this)
        val listener = CapabilityClient.OnCapabilityChangedListener { capInfo ->
            val nodes = capInfo.nodes
            val wasConnected = phoneConnected
            phoneConnected = nodes.isNotEmpty()
            Log.d(TAG, "Capability changed: ${nodes.size} nodes (was=$wasConnected now=$phoneConnected)")

            if (phoneConnected) {
                // Phone just appeared — give leases a fresh start
                // so pings have time to arrive before expiry.
                extendLease()
            }

            if (wasConnected && !phoneConnected) {
                // Fast-path: phone gone → kill both leases immediately.
                // The lease expiry is the safety net if this doesn't fire.
                Log.d(TAG, "Phone disconnected → stopping vibration")
                connectionLeaseExpiry = 0
                vibrationLeaseExpiry = 0
                vibratorEngine.cancel()
                broadcastStatus()
            }
        }
        capabilityListener = listener

        capClient.addListener(listener, AppConstants.CAPABILITY_VIBRATION)
            .addOnSuccessListener {
                Log.d(TAG, "Capability listener registered")
                capClient.addLocalCapability(AppConstants.CAPABILITY_VIBRATION)
            }
            .addOnFailureListener {
                Log.e(TAG, "Capability listener FAIL: ${it.message}")
            }
    }

    private fun startHeartbeatMonitor() {
        heartbeatChecker = serviceScope.launch {
            delay(2000) // give initial connection time
            Log.d(TAG, "Lease monitor started (lease=${AppConstants.VIBRATION_LEASE_MS}ms)")
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                // Dead man's switch: if the connection lease expired,
                // the phone is gone. Zero both leases, cancel vibration,
                // and update UI. No explicit disconnect signal needed.
                if (connectionLeaseExpiry > 0 && now > connectionLeaseExpiry) {
                    Log.w(TAG, "Connection LEASE EXPIRED → cancelling everything")
                    connectionLeaseExpiry = 0
                    vibrationLeaseExpiry = 0
                    val wasVibrating = vibratorEngine.vibrating
                    if (wasVibrating) {
                        vibratorEngine.cancel()
                    }
                    broadcastStatus()
                    // If not actively vibrating when phone disconnects,
                    // schedule a delayed minimize — cancel it if connection
                    // restores or vibration starts within the grace period.
                    if (!wasVibrating) {
                        scheduleMinimize()
                    }
                }
            }
        }
    }

    /** Extend both leases — called on every heartbeat ping. */
    private fun extendLease() {
        cancelMinimize()
        val wasExpired = vibrationLeaseExpiry <= System.currentTimeMillis()
        val now = System.currentTimeMillis()
        connectionLeaseExpiry = now + AppConstants.VIBRATION_LEASE_MS
        vibrationLeaseExpiry = now + AppConstants.VIBRATION_LEASE_MS
        if (wasExpired) {
            // Vibration lease just revived — auto-resume if we were active
            // before the disconnect AND the disconnect was recent enough.
            val disconnectedMs = if (lastCommandTimestamp > 0) now - lastCommandTimestamp else Long.MAX_VALUE
            Log.d(TAG, "Vibration lease revived — was expired, now current (disconnected ${disconnectedMs}ms)")
            if (disconnectedMs < AppConstants.COMMAND_TTL_MS
                && lastMode != AppConstants.MODE_STOP
                && lastMode != AppConstants.MODE_PAUSE) {
                Log.d(TAG, "Auto-resuming vibration mode=$lastMode")
                vibratorEngine.setModeVibration(lastMode, lastLevel, lastIntensity)
            }
            broadcastStatus()
        }
    }

    /**
     * Process an incoming ping. Returns true if the lease should be extended.
     * Handles session changes: a new sessionId resets the counter baseline
     * and suppresses auto-resume (new session = don't restart old vibration).
     */
    private fun handlePing(counter: Long, sessionId: Long): Boolean {
        if (sessionId > 0 && sessionId != lastSessionId) {
            // New phone session — reset expectations
            Log.d(TAG, "New session detected (sid=$sessionId, was=$lastSessionId) — resetting state")
            lastSessionId = sessionId
            lastPingCounter = -1
            lastCommandTimestamp = 0  // suppress auto-resume for old vibration
        }
        if (counter > lastPingCounter) {
            lastPingCounter = counter
            return true
        }
        return false
    }

    /** Returns true if the command timestamp is too old to process. */
    private fun isStaleCommand(ts: Long): Boolean {
        if (ts <= 0) return false  // legacy command without timestamp — accept
        val age = System.currentTimeMillis() - ts
        if (age > AppConstants.COMMAND_TTL_MS) return true
        // Also reject if older than the last processed command (out-of-order delivery)
        if (ts <= lastCommandTimestamp) return true
        return false
    }

    /**
     * Called on every control command. Connection lease always gets a fresh
     * extension (a command arriving proves the phone is connected). Vibration
     * lease is set for modes that start vibration, zeroed for STOP/PAUSE.
     */
    private fun renewLeaseIfActive(mode: Int) {
        connectionLeaseExpiry = System.currentTimeMillis() + AppConstants.VIBRATION_LEASE_MS
        vibrationLeaseExpiry = if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
            0L
        } else {
            cancelMinimize()  // vibration started — don't minimize anymore
            System.currentTimeMillis() + AppConstants.VIBRATION_LEASE_MS
        }
    }

    // ── Delayed minimize ──────────────────────────────────

    private fun scheduleMinimize() {
        minimizeJob?.cancel()
        minimizeJob = serviceScope.launch {
            delay(30_000L)  // 30s grace period before minimizing
            Log.d(TAG, "Minimize grace period expired — returning to watch face")
            sendBroadcast(Intent(ACTION_MINIMIZE).apply { setPackage(packageName) })
        }
    }

    private fun cancelMinimize() {
        minimizeJob?.cancel()
        minimizeJob = null
    }

    // ── Helpers ────────────────────────────────────────────

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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

    /** Whether the connection lease is current (pings are flowing). */
    private fun isConnected(): Boolean =
        connectionLeaseExpiry > System.currentTimeMillis()

    private fun broadcastStatus() {
        val connected = isConnected()
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_MODE, vibratorEngine.mode)
            putExtra(EXTRA_LEVEL, vibratorEngine.level)
            putExtra(EXTRA_INTENSITY, vibratorEngine.intensity)
            putExtra(EXTRA_ACTIVE, vibratorEngine.vibrating)
            putExtra(EXTRA_PHONE_CONNECTED, connected)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire(60 * 60 * 1000L) // 1 hour timeout
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "WakeLock released")
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
                .setContentText(if (isConnected()) "Connected to phone" else "Waiting for phone")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Vibration Control")
                .setContentText(if (isConnected()) "Connected to phone" else "Waiting for phone")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}
