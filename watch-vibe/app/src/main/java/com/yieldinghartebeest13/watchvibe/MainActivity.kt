package com.yieldinghartebeest13.watchvibe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Main activity for the watch app — kiosk mode, blocks all touch/back/swipe.
 *
 * Manages vibration directly. No foreground service — vibration only runs while
 * this activity is visible. Dismissing the activity (crown press, swipe) is the
 * sole escape route: it triggers an emergency stop that cancels all vibration
 * and sends a crown-exit message to the phone.
 *
 * Absorbs responsibilities previously split across VibrationForegroundService:
 *  - DataClient / MessageClient / CapabilityClient listeners
 *  - VibratorEngine management
 *  - Dual-lease model (dead-man's switch)
 *  - Battery monitoring + reporting to phone
 *  - Delayed minimize on disconnect
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "VibeAct"

        /** Broadcast action to request this Activity minimize itself. */
        const val ACTION_MINIMIZE = "com.yieldinghartebeest13.watchvibe.MINIMIZE"

        /** Grace period to confirm a leave is a real dismissal before the
         *  emergency stop. Transient covers (keyguard/charging screen) that
         *  fire onUserLeaveHint on remote wake are ignored within this window. */
        private const val EMERGENCY_STOP_GRACE_MS = 2_000L
    }

    // ── UI ────────────────────────────────────────────────

    private lateinit var modeText: TextView
    private lateinit var levelText: TextView
    private var exitRequested: Boolean = false
    private var minimizeInProgress: Boolean = false

    // ── Emergency-stop confirmation ───────────────────────

    private var emergencyStopJob: Job? = null

    // ── Vibration engine ──────────────────────────────────

    private lateinit var vibratorEngine: VibratorEngine
    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Dual-lease model (fails-safe) ─────────────────────

    // connectionLeaseExpiry: extended by every ping & command. Drives UI status.
    //   NEVER zeroed by STOP — only by disconnect or natural expiry.
    // vibrationLeaseExpiry: extended by pings, set by non-STOP commands,
    //   zeroed by STOP/PAUSE. Drives vibration cancellation.
    @Volatile private var connectionLeaseExpiry: Long = 0
    @Volatile private var vibrationLeaseExpiry: Long = 0
    @Volatile private var lastPingCounter: Long = -1
    @Volatile private var lastSessionId: Long = 0
    @Volatile private var lastCommandTimestamp: Long = 0
    private var heartbeatChecker: Job? = null
    @Volatile private var phoneConnected: Boolean = false
    private var minimizeJob: Job? = null

    // ── Battery ───────────────────────────────────────────

    private var batteryReceiver: BroadcastReceiver? = null
    @Volatile private var lastBatteryLevel: Int = -1

    // ── Data layer listeners ──────────────────────────────

    private var dataListener: DataClient.OnDataChangedListener? = null
    private var messageListener: MessageClient.OnMessageReceivedListener? = null
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null

    // ── Minimize broadcast ────────────────────────────────

    private val minimizeReceiver = MinimizeReceiver()

    // ═══════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        setTurnScreenOn(true)
        setContentView(R.layout.activity_main)

        modeText = findViewById(R.id.modeText)
        levelText = findViewById(R.id.levelText)

        setupKioskMode()

        vibratorEngine = VibratorEngine(this)
        if (!vibratorEngine.hasVibrator()) {
            Log.w(TAG, "No vibrator — activity will run but won't vibrate")
        }

        // Apply any control command forwarded by VibrationDataLayerService
        applyWakeExtras(intent)

        startListeners()
        startHeartbeatMonitor()
        startBatteryMonitor()
        sendAliveToPhone()
        Log.d(TAG, "onCreate done")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent")
        if (intent != null) {
            applyWakeExtras(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        exitRequested = false
        minimizeInProgress = false
        val filter = IntentFilter(ACTION_MINIMIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(minimizeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(minimizeReceiver, filter)
        }
        // Push current status to the display immediately
        updateDisplay()
        // Send immediate /alive so the phone knows we're here on resume
        sendAliveToPhone()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(minimizeReceiver) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (minimizeInProgress) {
            minimizeInProgress = false
            return
        }
        if (!exitRequested) {
            exitRequested = true
            // Cancel vibration immediately (safety); defer the emergency-stop
            // cascade until we've confirmed the activity actually left — the
            // watch OS keyguard can transiently fire onUserLeaveHint on a
            // remote wake without a real dismissal.
            vibratorEngine.cancel()
            scheduleEmergencyStop()
        }
    }

    /** Confirms the leave after the grace period, then fires the emergency stop. */
    private fun scheduleEmergencyStop() {
        emergencyStopJob?.cancel()
        emergencyStopJob = activityScope.launch {
            delay(EMERGENCY_STOP_GRACE_MS)
            withContext(Dispatchers.Main) { confirmEmergencyStop() }
        }
    }

    private fun confirmEmergencyStop() {
        if (isFinishing || isDestroyed) return
        if (hasWindowFocus()) {
            // Still on screen — transient cover, not a real dismissal.
            exitRequested = false
            return
        }
        Log.w(TAG, "User dismissed — EMERGENCY STOP")
        connectionLeaseExpiry = 0
        vibrationLeaseExpiry = 0
        lastCommandTimestamp = 0
        phoneConnected = false
        updateDisplay()
        sendCrownExitToPhone()
    }

    override fun onResume() {
        super.onResume()
        // Activity returned — cancel any pending emergency stop.
        emergencyStopJob?.cancel()
        emergencyStopJob = null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up")
        heartbeatChecker?.cancel()
        emergencyStopJob?.cancel()
        vibratorEngine.cancel()
        stopListeners()
        stopBatteryMonitor()
        activityScope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════
    // Kiosk mode
    // ═══════════════════════════════════════════════════════

    private fun setupKioskMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
    override fun onBackPressed() { Log.d(TAG, "Back blocked") }

    /**
     * Apply a control command forwarded by VibrationDataLayerService via Intent extras.
     * Prevents command loss during the launch window (command sent while
     * MainActivity was still starting).
     */
    private fun applyWakeExtras(intent: Intent?) {
        if (intent == null) return
        if (!intent.hasExtra(VibrationDataLayerService.EXTRA_MODE)) return

        val mode = intent.getIntExtra(VibrationDataLayerService.EXTRA_MODE, AppConstants.MODE_PAUSE)
        val level = intent.getIntExtra(VibrationDataLayerService.EXTRA_LEVEL, 0)
        val intensity = intent.getIntExtra(VibrationDataLayerService.EXTRA_INTENSITY, 100)
        val ts = intent.getLongExtra(VibrationDataLayerService.EXTRA_TIMESTAMP, 0L)

        if (isStaleCommand(ts)) {
            Log.d(TAG, "Ignoring stale wake-up command: mode=$mode")
            return
        }

        Log.d(TAG, "Applying wake-up command: mode=$mode level=$level intensity=$intensity")
        lastCommandTimestamp = ts
        vibratorEngine.setModeVibration(mode, level, intensity)
        renewLeaseIfActive(mode)
    }

    // ═══════════════════════════════════════════════════════
    // Display
    // ═══════════════════════════════════════════════════════

    private fun updateDisplay() {
        val mode = vibratorEngine.mode
        val active = vibratorEngine.vibrating
        val connected = isConnected()

        modeText.text = when {
            !connected -> "Waiting..."
            active -> AppConstants.MODE_LABELS[mode] ?: "Unknown"
            else -> "Ready"
        }
        levelText.text = when {
            !active -> ""
            mode == AppConstants.MODE_CONSTANT -> ""
            else -> AppConstants.SPEED_LABELS[vibratorEngine.level.coerceIn(0, 3)]
        }
    }

    // ═══════════════════════════════════════════════════════
    // Listeners (DataClient, MessageClient, CapabilityClient)
    // ═══════════════════════════════════════════════════════

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
                                    Wearable.getDataClient(this@MainActivity)
                                        .deleteDataItems(item.uri)
                                } catch (_: Exception) {}
                            }
                            continue
                        }

                        Log.d(TAG, "DataItem: mode=$mode level=$level intensity=$intensity")
                        vibratorEngine.setModeVibration(mode, level, intensity)
                        renewLeaseIfActive(mode)
                        updateDisplay()

                        if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
                            try {
                                Wearable.getDataClient(this@MainActivity)
                                    .deleteDataItems(item.uri)
                                Log.d(TAG, "Deleted STOP/PAUSE data item to prevent re-delivery")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to delete data item: ${e.message}")
                            }
                        }
                    }
                    AppConstants.PATH_LAUNCH -> {
                        Log.d(TAG, "Launch request from phone — already visible")
                    }
                    AppConstants.PATH_MINIMIZE -> {
                        Log.d(TAG, "Minimize request from phone")
                        minimizeInProgress = true
                        moveTaskToBack(true)
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
                            mode = parts[0].toIntOrNull() ?: return@OnMessageReceivedListener
                            level = parts[1].toIntOrNull() ?: return@OnMessageReceivedListener
                            intensity = 100; ts = 0L
                        }
                        3 -> {
                            mode = parts[0].toIntOrNull() ?: return@OnMessageReceivedListener
                            level = parts[1].toIntOrNull() ?: return@OnMessageReceivedListener
                            intensity = parts[2].toIntOrNull() ?: 100
                            ts = 0L
                        }
                        4 -> {
                            mode = parts[0].toIntOrNull() ?: return@OnMessageReceivedListener
                            level = parts[1].toIntOrNull() ?: return@OnMessageReceivedListener
                            intensity = parts[2].toIntOrNull() ?: 100
                            ts = parts[3].toLongOrNull() ?: 0L
                        }
                        else -> return@OnMessageReceivedListener
                    }

                    if (isStaleCommand(ts)) {
                        Log.d(TAG, "Ignoring stale message: mode=$mode (${System.currentTimeMillis() - ts}ms old)")
                        return@OnMessageReceivedListener
                    }

                    Log.d(TAG, "Message: mode=$mode level=$level intensity=$intensity")
                    vibratorEngine.setModeVibration(mode, level, intensity)
                    renewLeaseIfActive(mode)
                    updateDisplay()
                }
                AppConstants.PATH_LAUNCH -> {
                    Log.d(TAG, "Launch message from phone — already visible")
                }
                AppConstants.PATH_MINIMIZE -> {
                    Log.d(TAG, "Minimize message from phone")
                    minimizeInProgress = true
                    moveTaskToBack(true)
                }
                AppConstants.PATH_BATTERY_REQUEST -> {
                    Log.d(TAG, "Battery request from phone")
                    if (lastBatteryLevel >= 0) {
                        sendBatteryToPhone(lastBatteryLevel)
                    }
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
                extendLease()
                sendAliveToPhone()
                if (lastBatteryLevel >= 0) {
                    sendBatteryToPhone(lastBatteryLevel)
                }
            }

            if (wasConnected && !phoneConnected) {
                Log.d(TAG, "Phone disconnected → stopping vibration")
                connectionLeaseExpiry = 0
                vibrationLeaseExpiry = 0
                vibratorEngine.cancel()
                updateDisplay()
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

    // ═══════════════════════════════════════════════════════
    // Lease management
    // ═══════════════════════════════════════════════════════

    private fun startHeartbeatMonitor() {
        heartbeatChecker = activityScope.launch {
            Log.d(TAG, "Lease monitor + alive heartbeat started")
            var tick = 0L
            while (isActive) {
                delay(1000)
                tick++
                val now = System.currentTimeMillis()

                // Send /alive to phone every 2s so it knows we're still here.
                if (tick % 2 == 0L) {
                    sendAliveToPhone()
                }

                if (connectionLeaseExpiry > 0 && now > connectionLeaseExpiry) {
                    Log.w(TAG, "Connection LEASE EXPIRED → cancelling everything")
                    connectionLeaseExpiry = 0
                    vibrationLeaseExpiry = 0
                    val wasVibrating = vibratorEngine.vibrating
                    if (wasVibrating) {
                        vibratorEngine.cancel()
                    }
                    updateDisplay()
                    if (!wasVibrating) {
                        scheduleMinimize()
                    }
                }
            }
        }
    }

    private fun extendLease() {
        cancelMinimize()
        val wasExpired = vibrationLeaseExpiry <= System.currentTimeMillis()
        val now = System.currentTimeMillis()
        connectionLeaseExpiry = now + AppConstants.VIBRATION_LEASE_MS
        vibrationLeaseExpiry = now + AppConstants.VIBRATION_LEASE_MS
        if (wasExpired) {
            val disconnectedMs = if (lastCommandTimestamp > 0) now - lastCommandTimestamp else Long.MAX_VALUE
            Log.d(TAG, "Vibration lease revived — was expired, now current (disconnected ${disconnectedMs}ms)")
            if (disconnectedMs < AppConstants.COMMAND_TTL_MS
                && vibratorEngine.mode != AppConstants.MODE_STOP
                && vibratorEngine.mode != AppConstants.MODE_PAUSE) {
                Log.d(TAG, "Auto-resuming vibration")
                vibratorEngine.setModeVibration(
                    vibratorEngine.mode, vibratorEngine.level, vibratorEngine.intensity
                )
            }
            updateDisplay()
        }
    }

    private fun handlePing(counter: Long, sessionId: Long): Boolean {
        if (sessionId > 0 && sessionId != lastSessionId) {
            Log.d(TAG, "New session detected (sid=$sessionId, was=$lastSessionId) — resetting state")
            lastSessionId = sessionId
            lastPingCounter = -1
            lastCommandTimestamp = 0
        }
        if (counter > lastPingCounter) {
            lastPingCounter = counter
            return true
        }
        return false
    }

    private fun isStaleCommand(ts: Long): Boolean {
        if (ts <= 0) return false
        val age = System.currentTimeMillis() - ts
        if (age > AppConstants.COMMAND_TTL_MS) return true
        if (ts <= lastCommandTimestamp) return true
        return false
    }

    private fun renewLeaseIfActive(mode: Int) {
        connectionLeaseExpiry = System.currentTimeMillis() + AppConstants.VIBRATION_LEASE_MS
        vibrationLeaseExpiry = if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
            0L
        } else {
            cancelMinimize()
            System.currentTimeMillis() + AppConstants.VIBRATION_LEASE_MS
        }
    }

    /** Whether the connection lease is current (pings are flowing). */
    private fun isConnected(): Boolean =
        connectionLeaseExpiry > System.currentTimeMillis()

    // ═══════════════════════════════════════════════════════
    // Battery monitor
    // ═══════════════════════════════════════════════════════

    private fun startBatteryMonitor() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        lastBatteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Log.d(TAG, "Battery initial: $lastBatteryLevel%")
        sendBatteryToPhone(lastBatteryLevel)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (scale > 0) (level * 100) / scale else -1
                if (pct != lastBatteryLevel) {
                    lastBatteryLevel = pct
                    Log.d(TAG, "Battery level: $pct%")
                    sendBatteryToPhone(pct)
                }
            }
        }
        batteryReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Log.d(TAG, "Battery monitor registered")
    }

    private fun stopBatteryMonitor() {
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
    }

    private fun sendBatteryToPhone(level: Int) {
        activityScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity)
                    .connectedNodes.await()
                for (node in nodes) {
                    try {
                        val payload = level.toString().toByteArray()
                        Wearable.getMessageClient(this@MainActivity)
                            .sendMessage(node.id, AppConstants.PATH_BATTERY, payload).await()
                        Log.d(TAG, "Battery $level% sent to ${node.displayName}")
                    } catch (e: Exception) {
                        Log.d(TAG, "Battery send failed to ${node.displayName}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Battery send failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Alive heartbeat (watch → phone)
    // ═══════════════════════════════════════════════════════

    /** Periodic signal that proves this activity is alive and ready for commands. */
    private fun sendAliveToPhone() {
        activityScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity)
                    .connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, AppConstants.PATH_ALIVE, ByteArray(0)).await()
                }
            } catch (_: Exception) {
                // Silent — retry on next tick
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Crown exit → phone
    // ═══════════════════════════════════════════════════════

    private fun sendCrownExitToPhone() {
        activityScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity)
                    .connectedNodes.await()
                for (node in nodes) {
                    try {
                        Wearable.getMessageClient(this@MainActivity)
                            .sendMessage(node.id, AppConstants.PATH_CROWN_EXIT, ByteArray(0)).await()
                        Log.d(TAG, "Crown exit sent to ${node.displayName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send crown exit to ${node.displayName}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send crown exit: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Delayed minimize
    // ═══════════════════════════════════════════════════════

    private fun scheduleMinimize() {
        minimizeJob?.cancel()
        minimizeJob = activityScope.launch {
            delay(30_000L)
            Log.d(TAG, "Minimize grace period expired — returning to watch face")
            minimizeInProgress = true
            moveTaskToBack(true)
        }
    }

    private fun cancelMinimize() {
        minimizeJob?.cancel()
        minimizeJob = null
    }

    // ═══════════════════════════════════════════════════════
    // Minimize broadcast receiver
    // ═══════════════════════════════════════════════════════

    private inner class MinimizeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MINIMIZE) {
                Log.d(TAG, "Minimize broadcast received")
                minimizeInProgress = true
                moveTaskToBack(true)
            }
        }
    }
}
