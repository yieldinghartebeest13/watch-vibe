package com.yieldinghartebeest13.watchvibe

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Main activity for the watch app — kiosk mode, blocks all touch/back/swipe.
 *
 * Vibration is now hard-gated on true foreground visibility. If the activity is
 * not started + resumed + window-focused, the runtime is forced inert: leases
 * are cleared, vibration is cancelled, /alive stops, and the phone is told to
 * stop its active session state.
 */
open class MainActivity : Activity() {

    companion object {
        private const val TAG = "VibeAct"

        /** Broadcast action to request this Activity minimize itself. */
        const val ACTION_MINIMIZE = "com.yieldinghartebeest13.watchvibe.MINIMIZE"

        /** Intent action used by the ongoing notification emergency stop. */
        const val ACTION_STOP_FROM_NOTIFICATION = "com.yieldinghartebeest13.watchvibe.STOP_FROM_NOTIFICATION"

        internal const val POST_NOTIFICATIONS_REQUEST_CODE = 1002

        private const val EXTRA_STOP_FROM_NOTIFICATION = "stop_from_notification"
        private const val NOTIFICATION_CHANNEL_ID = "active_vibration_exit_v2"
        private const val ACTIVE_NOTIFICATION_ID = 1001

        /** Grace period to confirm a leave is a real dismissal before the
         *  emergency stop. Transient covers that fire onUserLeaveHint are
         *  ignored within this window; actual hidden-state stopping is enforced
         *  separately by the foreground gate. */
        private const val EMERGENCY_STOP_GRACE_MS = 2_000L

        /** We cannot query real HAL vibration state, so while an active lease is
         *  still being renewed we periodically reassert the current mode as the
         *  strongest practical recovery for silent actuator stoppage. */
        private const val ACTIVE_VIBRATION_REASSERT_MS = 5_000L

        @Volatile
        private var isUiForegroundForActiveControlWake: Boolean = false

        private fun publishUiForegroundForActiveControlWake(isForeground: Boolean) {
            isUiForegroundForActiveControlWake = isForeground
        }

        internal fun isUiForegroundForActiveControlWake(): Boolean =
            isUiForegroundForActiveControlWake

        internal fun setUiForegroundForActiveControlWakeForTesting(isForeground: Boolean) {
            publishUiForegroundForActiveControlWake(isForeground)
        }
    }

    private data class PendingCommand(
        val mode: Int,
        val level: Int,
        val intensity: Int,
        val timestamp: Long,
        val source: String
    )

    protected data class ControlCommandSnapshot(
        val mode: Int,
        val level: Int,
        val intensity: Int,
        val timestamp: Long
    )

    private data class PendingCommandValidation(
        val command: PendingCommand?,
        val supersedingTimestamp: Long = 0L
    )

    // ── UI ────────────────────────────────────────────────

    private lateinit var modeText: TextView
    private lateinit var levelText: TextView
    private var exitRequested: Boolean = false
    private var minimizeInProgress: Boolean = false
    private var listenersStarted: Boolean = false

    @Volatile private var isStartedState: Boolean = false
    @Volatile private var isResumedState: Boolean = false
    @Volatile private var hasWindowFocusState: Boolean = false
    @Volatile private var wasForegroundState: Boolean = false
    private var pendingWakeCommand: PendingCommand? = null
    private var pendingWakeValidationJob: Job? = null
    private var notificationPermissionRequestInFlight: Boolean = false
    private var notificationSettingsLaunchInFlight: Boolean = false
    private var notificationAccessRequired: Boolean = false

    // ── Emergency-stop confirmation ───────────────────────

    private var emergencyStopJob: Job? = null

    // ── Vibration engine ──────────────────────────────────

    private lateinit var vibratorEngine: VibratorEngine
    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Dual-lease model (fails-safe) ─────────────────────

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
        publishUiForegroundForActiveControlWake(false)
        Log.d(TAG, "onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        setTurnScreenOn(true)
        setContentView(R.layout.activity_main)

        modeText = findViewById(R.id.modeText)
        levelText = findViewById(R.id.levelText)

        setupKioskMode()
        createNotificationChannel()

        vibratorEngine = VibratorEngine(this)
        if (!vibratorEngine.hasVibrator()) {
            Log.w(TAG, "No vibrator — activity will run but won't vibrate")
        }

        handleIntent(intent)
        startHeartbeatMonitor()
        startBatteryMonitor()
        Log.d(TAG, "onCreate done")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent")
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        exitRequested = false
        minimizeInProgress = false
        isStartedState = true

        val filter = IntentFilter(ACTION_MINIMIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(minimizeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(minimizeReceiver, filter)
        }

        startListeners()
        updateDisplay()
        refreshForegroundState("onStart")
    }

    override fun onResume() {
        super.onResume()
        emergencyStopJob?.cancel()
        emergencyStopJob = null
        notificationSettingsLaunchInFlight = false
        isResumedState = true
        refreshForegroundState("onResume")
    }

    override fun onPause() {
        isResumedState = false
        refreshForegroundState("onPause")
        super.onPause()
    }

    override fun onStop() {
        refreshForegroundState("onStop")
        isStartedState = false
        hasWindowFocusState = false
        stopListeners()
        try { unregisterReceiver(minimizeReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocusState = hasFocus
        refreshForegroundState("windowFocus=$hasFocus")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (minimizeInProgress) {
            minimizeInProgress = false
            return
        }
        if (shouldIgnoreTransientForegroundLossDuringNotificationPermissionPrompt()) {
            emergencyStopJob?.cancel()
            emergencyStopJob = null
            exitRequested = false
            return
        }
        if (!exitRequested) {
            exitRequested = true
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
        if (isUiForeground() || shouldIgnoreTransientForegroundLossDuringNotificationPermissionPrompt()) {
            exitRequested = false
            return
        }
        Log.w(TAG, "User dismissed — EMERGENCY STOP")
        performEmergencyStop("userLeaveHint", notifyPhone = true)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != POST_NOTIFICATIONS_REQUEST_CODE) return

        notificationPermissionRequestInFlight = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            maybeApplyPendingCommand("notificationPermissionGranted")
            return
        }

        val hadPendingCommand = pendingWakeCommand != null
        pendingWakeCommand = null
        if (hadPendingCommand) {
            Log.w(TAG, "Notification permission denied; aborting pending vibration start")
            showNotificationAccessRequiredMessage()
            return
        }
        updateDisplay()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up")
        heartbeatChecker?.cancel()
        emergencyStopJob?.cancel()
        pendingWakeValidationJob?.cancel()
        cancelActiveNotification()
        if (this::vibratorEngine.isInitialized) {
            vibratorEngine.cancel()
        }
        stopListeners()
        stopBatteryMonitor()
        activityScope.cancel()
        publishUiForegroundForActiveControlWake(false)
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

    // ═══════════════════════════════════════════════════════
    // Foreground gate
    // ═══════════════════════════════════════════════════════

    private fun isUiForeground(): Boolean =
        isStartedState && isResumedState && hasWindowFocusState && !isFinishing && !isDestroyed

    private fun shouldIgnoreTransientForegroundLossDuringNotificationPermissionPrompt(): Boolean =
        notificationPermissionRequestInFlight &&
            pendingWakeCommand != null &&
            !vibratorEngine.vibrating &&
            isStartedState

    private fun refreshForegroundState(reason: String) {
        val isForegroundNow = isUiForeground()
        publishUiForegroundForActiveControlWake(isForegroundNow)
        if (!isForegroundNow && shouldIgnoreTransientForegroundLossDuringNotificationPermissionPrompt()) {
            Log.d(TAG, "Ignoring foreground loss via $reason while notification permission prompt is in flight")
            return
        }
        if (isForegroundNow == wasForegroundState) {
            if (isForegroundNow) {
                maybeApplyPendingCommand(reason)
            } else {
                cancelActiveNotification()
            }
            return
        }

        wasForegroundState = isForegroundNow
        if (isForegroundNow) {
            Log.d(TAG, "Foreground gate OPEN via $reason")
            updateDisplay()
            maybeApplyPendingCommand(reason)
            sendAliveToPhone()
        } else {
            Log.w(TAG, "Foreground gate CLOSED via $reason")
            handleForegroundLoss(reason)
        }
    }

    private fun handleForegroundLoss(reason: String) {
        emergencyStopJob?.cancel()
        emergencyStopJob = null
        pendingWakeValidationJob?.cancel()
        pendingWakeValidationJob = null
        cancelMinimize()

        val now = System.currentTimeMillis()
        val hadActiveSession = vibratorEngine.vibrating || vibrationLeaseExpiry > now
        connectionLeaseExpiry = 0
        vibrationLeaseExpiry = 0
        phoneConnected = false

        if (vibratorEngine.vibrating) {
            Log.w(TAG, "Stopping vibration because UI is not foreground ($reason)")
            vibratorEngine.cancel()
        }

        updateDisplay()
        if (hadActiveSession) {
            sendCrownExitToPhone()
        }
    }

    private fun performEmergencyStop(reason: String, notifyPhone: Boolean) {
        Log.w(TAG, "Emergency stop: $reason")
        pendingWakeValidationJob?.cancel()
        pendingWakeValidationJob = null
        pendingWakeCommand = null
        cancelMinimize()
        connectionLeaseExpiry = 0
        vibrationLeaseExpiry = 0
        lastCommandTimestamp = 0
        phoneConnected = false
        val hadActiveSession = vibratorEngine.vibrating
        vibratorEngine.cancel()
        updateDisplay()
        if (notifyPhone && (hadActiveSession || exitRequested)) {
            sendCrownExitToPhone()
        }
        exitRequested = false
    }

    // ═══════════════════════════════════════════════════════
    // Incoming commands / wake intents
    // ═══════════════════════════════════════════════════════

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_STOP_FROM_NOTIFICATION
            || intent.getBooleanExtra(EXTRA_STOP_FROM_NOTIFICATION, false)) {
            performEmergencyStop("notification action", notifyPhone = true)
            return
        }
        if (!intent.hasExtra(VibrationDataLayerService.EXTRA_MODE)) return

        val mode = intent.getIntExtra(VibrationDataLayerService.EXTRA_MODE, AppConstants.MODE_PAUSE)
        val level = intent.getIntExtra(VibrationDataLayerService.EXTRA_LEVEL, 0)
        val intensity = intent.getIntExtra(VibrationDataLayerService.EXTRA_INTENSITY, 100)
        val ts = intent.getLongExtra(VibrationDataLayerService.EXTRA_TIMESTAMP, 0L)

        handleControlCommand(mode, level, intensity, ts, "wake-up intent")
    }

    private fun handleControlCommand(
        mode: Int,
        level: Int,
        intensity: Int,
        timestamp: Long,
        source: String,
        onStopHandled: (() -> Unit)? = null
    ) {
        if (isStaleCommand(timestamp)) {
            Log.d(TAG, "Ignoring stale $source command: mode=$mode")
            if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
                onStopHandled?.invoke()
            }
            return
        }

        if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
            pendingWakeValidationJob?.cancel()
            pendingWakeValidationJob = null
            pendingWakeCommand = null
            if (timestamp > 0) {
                lastCommandTimestamp = timestamp
            }
            vibratorEngine.setModeVibration(mode, level, intensity)
            renewLeaseIfActive(mode)
            updateDisplay()
            onStopHandled?.invoke()
            return
        }

        val command = PendingCommand(mode, level, intensity, timestamp, source)
        if (!isUiForeground()) {
            Log.w(TAG, "Deferring $source command until UI is truly foreground: mode=$mode")
            pendingWakeCommand = command
            return
        }

        if (!ensureEmergencySurfaceAvailable(command)) return
        applyForegroundCommand(command)
    }

    private fun maybeApplyPendingCommand(reason: String) {
        if (!isUiForeground()) return
        val pending = pendingWakeCommand ?: return
        if (pendingWakeValidationJob?.isActive == true) return

        pendingWakeValidationJob = activityScope.launch {
            val validation = validatePendingCommand(pending)
            withContext(Dispatchers.Main) {
                pendingWakeValidationJob = null
                applyValidatedPendingCommand(pending, validation, reason)
            }
        }
    }

    private suspend fun validatePendingCommand(pending: PendingCommand): PendingCommandValidation {
        if (isStaleCommand(pending.timestamp)) {
            Log.d(TAG, "Dropping deferred command after foreground restore; command is stale")
            return PendingCommandValidation(command = null, supersedingTimestamp = pending.timestamp)
        }

        val latest = readLatestControlCommandForValidation() ?: return PendingCommandValidation(command = pending)
        if (!latest.supersedes(pending)) {
            return PendingCommandValidation(command = pending)
        }
        if (isStaleCommand(latest.timestamp)) {
            Log.d(TAG, "Dropping deferred command after foreground restore; latest /control snapshot is stale")
            return PendingCommandValidation(command = null, supersedingTimestamp = latest.timestamp)
        }
        if (latest.mode == AppConstants.MODE_STOP || latest.mode == AppConstants.MODE_PAUSE) {
            Log.d(
                TAG,
                "Dropping deferred command after foreground restore; superseded by later mode=${latest.mode}"
            )
            return PendingCommandValidation(command = null, supersedingTimestamp = latest.timestamp)
        }

        Log.d(TAG, "Refreshing deferred command from latest /control snapshot: mode=${latest.mode}")
        return PendingCommandValidation(
            command = PendingCommand(
                latest.mode,
                latest.level,
                latest.intensity,
                latest.timestamp,
                "${pending.source} (revalidated)"
            )
        )
    }

    private fun applyValidatedPendingCommand(
        originalPending: PendingCommand,
        validation: PendingCommandValidation,
        reason: String
    ) {
        if (!isUiForeground()) return
        if (pendingWakeCommand !== originalPending) return
        if (validation.supersedingTimestamp > lastCommandTimestamp) {
            lastCommandTimestamp = validation.supersedingTimestamp
        }

        val command = validation.command ?: run {
            pendingWakeCommand = null
            updateDisplay()
            return
        }

        if (!ensureEmergencySurfaceAvailable(command)) return
        pendingWakeCommand = null
        Log.d(TAG, "Applying deferred command after foreground restore ($reason): mode=${command.mode}")
        applyForegroundCommand(command)
    }

    private fun ControlCommandSnapshot.supersedes(pending: PendingCommand): Boolean =
        timestamp > pending.timestamp ||
            (timestamp == pending.timestamp && (
                mode != pending.mode ||
                    level != pending.level ||
                    intensity != pending.intensity
            ))

    private fun applyForegroundCommand(command: PendingCommand) {
        notificationAccessRequired = false
        if (command.timestamp > 0) {
            lastCommandTimestamp = command.timestamp
        }
        Log.d(
            TAG,
            "Applying ${command.source}: mode=${command.mode} level=${command.level} intensity=${command.intensity}"
        )
        vibratorEngine.setModeVibration(command.mode, command.level, command.intensity)
        renewLeaseIfActive(command.mode)
        updateDisplay()
    }

    protected open fun reassertCurrentVibration(reason: String): Boolean =
        vibratorEngine.reassertActiveVibration()

    private fun maybeRecoverSilentStop(
        reason: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        if (!isUiForeground()) return false
        if (vibrationLeaseExpiry <= System.currentTimeMillis()) return false
        if (!vibratorEngine.shouldReassertActiveVibration(nowElapsedMs, ACTIVE_VIBRATION_REASSERT_MS)) {
            return false
        }
        Log.w(TAG, "Reasserting active vibration after likely silent stop ($reason)")
        val restarted = reassertCurrentVibration(reason)
        if (restarted) {
            updateDisplay()
        }
        return restarted
    }

    // ═══════════════════════════════════════════════════════
    // Display + notification
    // ═══════════════════════════════════════════════════════

    private fun updateDisplay() {
        val mode = vibratorEngine.mode
        val active = vibratorEngine.vibrating
        val connected = isConnected()
        val showNotificationAccessRequired = shouldShowNotificationAccessRequiredMessage(active)

        modeText.text = when {
            showNotificationAccessRequired -> getString(R.string.notification_access_required_title)
            !connected -> "Waiting..."
            active -> AppConstants.MODE_LABELS[mode] ?: "Unknown"
            else -> "Ready"
        }
        levelText.text = when {
            showNotificationAccessRequired -> getString(R.string.notification_access_required_message)
            !active -> ""
            mode == AppConstants.MODE_CONSTANT -> ""
            else -> AppConstants.SPEED_LABELS[vibratorEngine.level.coerceIn(0, 3)]
        }

        syncActiveNotification()
    }

    private fun shouldShowNotificationAccessRequiredMessage(active: Boolean): Boolean {
        if (!notificationAccessRequired || active) return false
        if (canShowEmergencyNotification()) {
            notificationAccessRequired = false
            return false
        }
        return true
    }

    private fun showNotificationAccessRequiredMessage() {
        notificationAccessRequired = true
        updateDisplay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.active_vibration_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.active_vibration_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        try {
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create notification channel: ${e.message}")
        }
    }

    private fun syncActiveNotification() {
        if (!vibratorEngine.vibrating || !isUiForeground()) {
            cancelActiveNotification()
            return
        }
        if (!canShowEmergencyNotification()) {
            Log.w(TAG, "Emergency notification unavailable during active vibration; stopping session")
            performEmergencyStop("emergency notification unavailable", notifyPhone = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
                if (!notificationPermissionRequestInFlight) {
                    notificationPermissionRequestInFlight = true
                    requestNotificationPermission()
                }
            } else if (!notificationSettingsLaunchInFlight) {
                notificationSettingsLaunchInFlight = true
                openNotificationSettings()
            }
            return
        }
        if (!showActiveNotification()) {
            Log.w(TAG, "Emergency notification post failed during active vibration; stopping session")
            performEmergencyStop("emergency notification post failed", notifyPhone = true)
        }
    }

    protected open fun showActiveNotification(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        return try {
            manager.notify(ACTIVE_NOTIFICATION_ID, buildActiveNotification())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show active notification: ${e.message}")
            false
        }
    }

    private fun cancelActiveNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.cancel(ACTIVE_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel active notification: ${e.message}")
        }
    }

    private fun buildActiveNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_STOP_FROM_NOTIFICATION
                putExtra(EXTRA_STOP_FROM_NOTIFICATION, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = AppConstants.MODE_LABELS[vibratorEngine.mode] ?: getString(R.string.active_vibration_notification_unknown)
        val detail = if (vibratorEngine.mode == AppConstants.MODE_CONSTANT) {
            modeLabel
        } else {
            "$modeLabel • ${AppConstants.SPEED_LABELS[vibratorEngine.level.coerceIn(0, 3)]}"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vibration)
            .setContentTitle(getString(R.string.active_vibration_notification_title))
            .setContentText(detail)
            .setContentIntent(openIntent)
            .addAction(
                R.drawable.ic_vibration,
                getString(R.string.active_vibration_notification_stop),
                stopIntent
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureEmergencySurfaceAvailable(command: PendingCommand): Boolean {
        if (canShowEmergencyNotification()) return true

        pendingWakeCommand = command
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            Log.w(TAG, "POST_NOTIFICATIONS unavailable; requesting permission before starting vibration")
            notificationAccessRequired = false
            if (!notificationPermissionRequestInFlight) {
                notificationPermissionRequestInFlight = true
                requestNotificationPermission()
            }
            return false
        }

        Log.w(TAG, "Notifications disabled/blocked; not starting vibration")
        pendingWakeCommand = null
        showNotificationAccessRequiredMessage()
        return false
    }

    private fun canShowEmergencyNotification(): Boolean =
        hasNotificationPermission() && areNotificationsEnabledForEmergencySurface()

    protected open fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    protected open fun areNotificationsEnabledForEmergencySurface(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    protected open fun requestNotificationPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            POST_NOTIFICATIONS_REQUEST_CODE
        )
    }

    protected open fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, NOTIFICATION_CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open notification settings: ${e.message}")
        }
    }

    protected open suspend fun readLatestControlCommandForValidation(): ControlCommandSnapshot? {
        return try {
            val items = Wearable.getDataClient(this).getDataItems().await()
            try {
                for (item in items) {
                    if (item.uri.path != AppConstants.PATH_CONTROL) continue
                    val map = DataMapItem.fromDataItem(item).dataMap
                    return ControlCommandSnapshot(
                        mode = map.getInt(AppConstants.KEY_MODE, AppConstants.MODE_PAUSE),
                        level = map.getInt(AppConstants.KEY_LEVEL, 0),
                        intensity = map.getInt(AppConstants.KEY_INTENSITY, 100),
                        timestamp = map.getLong(AppConstants.KEY_TIMESTAMP, 0L)
                    )
                }
            } finally {
                items.release()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read latest control snapshot for validation: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    // Listeners (DataClient, MessageClient, CapabilityClient)
    // ═══════════════════════════════════════════════════════

    protected open fun startListeners() {
        if (listenersStarted) return
        startDataListener()
        startMessageListener()
        startCapabilityListener()
        listenersStarted = true
    }

    protected open fun stopListeners() {
        if (!listenersStarted) return
        dataListener?.let { Wearable.getDataClient(this).removeListener(it) }
        messageListener?.let { Wearable.getMessageClient(this).removeListener(it) }
        capabilityListener?.let { Wearable.getCapabilityClient(this).removeListener(it) }
        listenersStarted = false
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

                        handleControlCommand(
                            mode,
                            level,
                            intensity,
                            ts,
                            "DataItem"
                        ) {
                            try {
                                Wearable.getDataClient(this@MainActivity).deleteDataItems(item.uri)
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
                        if (!isUiForeground()) {
                            Log.w(TAG, "Ignoring ping while UI is not foreground")
                            continue
                        }
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
                            intensity = 100
                            ts = 0L
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

                    handleControlCommand(mode, level, intensity, ts, "message")
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
                    if (isUiForeground() && lastBatteryLevel >= 0) {
                        Log.d(TAG, "Battery request from phone")
                        sendBatteryToPhone(lastBatteryLevel)
                    }
                }
                AppConstants.PATH_PING -> {
                    if (!isUiForeground()) {
                        Log.w(TAG, "Ignoring ping message while UI is not foreground")
                        return@OnMessageReceivedListener
                    }
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

            if (phoneConnected && isUiForeground()) {
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
        if (!isUiForeground()) {
            Log.w(TAG, "Ignoring lease extension while UI is not foreground")
            return
        }
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
            return
        }

        maybeRecoverSilentStop("lease-extend")
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

    protected open fun currentCommandTimeMillis(): Long = System.currentTimeMillis()

    private fun isStaleCommand(ts: Long): Boolean {
        if (ts <= 0) return false
        val age = currentCommandTimeMillis() - ts
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

    protected open fun startBatteryMonitor() {
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

    protected open fun stopBatteryMonitor() {
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
    protected open fun sendAliveToPhone() {
        if (!isUiForeground()) return
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

    protected open fun sendCrownExitToPhone() {
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

    internal fun isVibratingForTesting(): Boolean = vibratorEngine.vibrating

    internal fun recoverSilentStopForTesting(nowElapsedMs: Long): Boolean =
        maybeRecoverSilentStop("test", nowElapsedMs)

    internal fun vibrationCommandElapsedMsForTesting(): Long =
        vibratorEngine.lastActuatorCommandElapsedMsForTesting()

    internal fun vibrationCycleDurationMsForTesting(): Long =
        vibratorEngine.currentPatternCycleDurationMsForTesting()

    internal fun pendingReassertElapsedMsForTesting(): Long =
        vibratorEngine.pendingReassertAtElapsedMsForTesting()
}
