package com.yieldinghartebeest13.watchvibe

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_MODE = "saved_mode"
        private const val KEY_LEVEL = "saved_level"
    }

    private val wearDataLayer = WearDataLayer(application)
    private val statsDb = StatsDb(application)

    // Restore mode/level from saved state (survives process death).
    // isVibrating always starts false — the watch state is authoritative.
    private val _mode = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_MODE) ?: AppConstants.MODE_PAUSE
    )
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _level = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_LEVEL) ?: 0
    )
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _intensity = MutableStateFlow(50)
    val intensity: StateFlow<Int> = _intensity.asStateFlow()

    private val _isVibrating = MutableStateFlow(false)
    val isVibrating: StateFlow<Boolean> = _isVibrating.asStateFlow()

    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _watchBatteryLevel = MutableStateFlow(-1)
    val watchBatteryLevel: StateFlow<Int> = _watchBatteryLevel.asStateFlow()

    // Battery is pending until we get the first reply from the watch
    private val _watchBatteryPending = MutableStateFlow(true)
    val watchBatteryPending: StateFlow<Boolean> = _watchBatteryPending.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // One-shot event: true when watch crown-exited → phone should minimize
    private val _crownExitRequested = MutableStateFlow(false)
    val crownExitRequested: StateFlow<Boolean> = _crownExitRequested.asStateFlow()

    // ── Session stats ────────────────────────────────────

    private val _weeklyStats = MutableStateFlow(StatsDb.MergedStats(0, 0, emptyList(), emptyList()))
    val weeklyStats: StateFlow<StatsDb.MergedStats> = _weeklyStats.asStateFlow()

    private val _monthlyStats = MutableStateFlow(StatsDb.MergedStats(0, 0, emptyList(), emptyList()))
    val monthlyStats: StateFlow<StatsDb.MergedStats> = _monthlyStats.asStateFlow()

    private val _yearlyStats = MutableStateFlow(StatsDb.MergedStats(0, 0, emptyList(), emptyList()))
    val yearlyStats: StateFlow<StatsDb.MergedStats> = _yearlyStats.asStateFlow()

    private val _recentSessions = MutableStateFlow<List<StatsDb.SessionEntry>>(emptyList())
    val recentSessions: StateFlow<List<StatsDb.SessionEntry>> = _recentSessions.asStateFlow()

    private var sessionStartMs: Long = 0
    private var sessionMode: Int = 0
    private var sessionLevel: Int = 0

    init {
        // If the UI was showing an active mode before process death,
        // re-send it so the watch state and UI state are consistent.
        val restoredMode = _mode.value
        if (restoredMode != AppConstants.MODE_STOP && restoredMode != AppConstants.MODE_PAUSE) {
            applyVibration()
        }
        refreshStats()
    }

    private var heartbeatJob: Job? = null
    private var capabilityListenerRegistered = false
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var isInForeground: Boolean = false
    private var suppressMinimize: Boolean = false

    /** Call before opening an internal activity to prevent the watch minimizing. */
    fun suppressNextMinimize() { suppressMinimize = true }

    /** Called when the activity comes to the foreground. */
    fun onForeground() {
        isInForeground = true
        startHeartbeat()
        startConnectionMonitor()
        refreshStats()
    }

    /**
     * Called when the activity goes to the background.
     * Only stops the heartbeat if no vibration is active — if the user
     * put the phone away while vibrating, pings must continue so the
     * watch doesn't trigger a disconnect.
     */
    fun onBackground() {
        isInForeground = false
        if (suppressMinimize) {
            suppressMinimize = false
            return
        }
        if (_mode.value == AppConstants.MODE_STOP || _mode.value == AppConstants.MODE_PAUSE) {
            stopHeartbeat()
            stopConnectionMonitor()
            // Not vibrating — tell the watch to minimize so the user
            // doesn't have to manually dismiss it.
            viewModelScope.launch { wearDataLayer.sendMinimize() }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(AppConstants.HEARTBEAT_INTERVAL_MS)
                wearDataLayer.sendPing()
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun startConnectionMonitor() {
        if (capabilityListenerRegistered) return
        capabilityListenerRegistered = true

        // Initial check + wake-up
        viewModelScope.launch {
            val connected = wearDataLayer.isWearConnected()
            _watchConnected.value = connected
            if (connected) {
                wearDataLayer.sendWakeUp()
            }
        }

        // Listener for crown exit from watch (emergency stop) and battery updates
        wearDataLayer.startMessageListener(
            onCrownExit = { onCrownExit() },
            onBatteryUpdate = { level ->
                _watchBatteryLevel.value = level
                _watchBatteryPending.value = false
            }
        )

        // Actively request current battery level from the watch
        viewModelScope.launch {
            wearDataLayer.requestBattery()
        }

        // Listener for real-time changes
        val listener = CapabilityClient.OnCapabilityChangedListener { capInfo ->
            val connected = capInfo.nodes.isNotEmpty()
            _watchConnected.value = connected
        }
        capabilityListener = listener

        viewModelScope.launch {
            try {
                val capClient = Wearable.getCapabilityClient(getApplication<Application>())
                capClient.addListener(listener, AppConstants.CAPABILITY_VIBRATION).await()
            } catch (e: Exception) {
                capabilityListenerRegistered = false
            }
        }
    }

    fun stopConnectionMonitor() {
        val listener = capabilityListener ?: return
        capabilityListener = null
        capabilityListenerRegistered = false
        viewModelScope.launch {
            try {
                val capClient = Wearable.getCapabilityClient(getApplication<Application>())
                capClient.removeListener(listener).await()
            } catch (_: Exception) {
                // Listener may already be unregistered — safe to ignore
            }
        }
        wearDataLayer.stopMessageListener()
    }

    fun checkWearConnection() {
        viewModelScope.launch {
            _watchConnected.value = wearDataLayer.isWearConnected()
        }
    }

    fun modeConstant() { setMode(AppConstants.MODE_CONSTANT) }
    fun modeIntermittent() { setMode(AppConstants.MODE_INTERMITTENT) }
    fun modeRamp() { setMode(AppConstants.MODE_RAMP) }
    fun modeBurst() { setMode(AppConstants.MODE_BURST) }
    fun modeWave() { setMode(AppConstants.MODE_WAVE) }
    fun modeRandom() { setMode(AppConstants.MODE_RANDOM) }
    fun modeStop() { setMode(AppConstants.MODE_STOP) }

    fun setIntensity(value: Int) {
        _intensity.value = value.coerceIn(0, 100)
        applyVibration()
    }

    fun moreCadence() {
        _level.value = (_level.value + 1).coerceIn(0, 3)
        savedStateHandle[KEY_LEVEL] = _level.value
        applyVibration()
    }

    fun minusCadence() {
        _level.value = (_level.value - 1).coerceIn(0, 3)
        savedStateHandle[KEY_LEVEL] = _level.value
        applyVibration()
    }

    private fun setMode(mode: Int) {
        _mode.value = mode
        savedStateHandle[KEY_MODE] = mode
        applyVibration()
    }

    private fun applyVibration() {
        val currentMode = _mode.value
        val currentLevel = _level.value

        if (currentMode == AppConstants.MODE_STOP || currentMode == AppConstants.MODE_PAUSE) {
            val wasVibrating = _isVibrating.value
            _isVibrating.value = false
            _statusText.value = "Ready"
            if (wasVibrating) recordSessionEnd()
            // If the user stops vibration while the app is in the background,
            // there's no reason to keep the heartbeat alive.
            if (!isInForeground) {
                stopHeartbeat()
                stopConnectionMonitor()
            }
            // Stop the foreground service — no vibration to protect.
            stopPingService()
        } else {
            val wasVibrating = _isVibrating.value
            _isVibrating.value = true
            if (!wasVibrating) recordSessionStart(currentMode, currentLevel)
            val modeLabel = AppConstants.MODE_LABELS[currentMode] ?: "Unknown"
            val speedLabel = AppConstants.SPEED_LABELS[currentLevel]
            _statusText.value = "$modeLabel - $speedLabel"
            // Ensure heartbeat runs even if the user activated this from
            // a notification or the app is otherwise backgrounded.
            if (!isInForeground) {
                startHeartbeat()
            }
            // Start a foreground service that keeps the ping heartbeat
            // alive when the app is backgrounded. Android's background
            // execution limits would otherwise freeze the ViewModel
            // coroutine and the watch's lease expires → vibration stops.
            startPingService(modeLabel, speedLabel)
        }

        viewModelScope.launch {
            wearDataLayer.sendControl(currentMode, currentLevel, 100)
        }
    }

    // ── Session recording ─────────────────────────────────

    private fun recordSessionStart(mode: Int, level: Int) {
        sessionStartMs = System.currentTimeMillis()
        sessionMode = mode
        sessionLevel = level
    }

    private fun recordSessionEnd() {
        if (sessionStartMs == 0L) return
        val durationMs = System.currentTimeMillis() - sessionStartMs
        sessionStartMs = 0
        // Ignore ultra-short sessions (<500ms) as accidental taps
        if (durationMs < 500) return
        viewModelScope.launch {
            statsDb.insert(sessionMode, sessionLevel, durationMs, System.currentTimeMillis())
            refreshStats()
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _weeklyStats.value = statsDb.mergedQuery(now - 7 * 24 * 3600_000L)
            _monthlyStats.value = statsDb.mergedQuery(now - 30 * 24 * 3600_000L)
            _yearlyStats.value = statsDb.mergedQuery(now - 365 * 24 * 3600_000L)
            _recentSessions.value = statsDb.recentSessions(20)
        }
    }

    // ── Foreground service management ────────────────────

    private fun startPingService(modeLabel: String, speedLabel: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, PingForegroundService::class.java).apply {
            action = PingForegroundService.ACTION_UPDATE_STATUS
            putExtra(PingForegroundService.EXTRA_MODE_LABEL, modeLabel)
            putExtra(PingForegroundService.EXTRA_SPEED_LABEL, speedLabel)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopPingService() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, PingForegroundService::class.java))
    }

    fun onCrownExitHandled() {
        _crownExitRequested.value = false
    }

    /**
     * Called when the watch sends a crown-exit message (emergency stop).
     * Resets UI state, stops services, and signals the Activity to minimize.
     */
    private fun onCrownExit() {
        viewModelScope.launch {
            _mode.value = AppConstants.MODE_STOP
            _isVibrating.value = false
            _statusText.value = "Ready"
            savedStateHandle[KEY_MODE] = AppConstants.MODE_STOP
            stopHeartbeat()
            stopConnectionMonitor()
            stopPingService()
            _crownExitRequested.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        stopConnectionMonitor()
        stopPingService()
        viewModelScope.launch {
            wearDataLayer.sendControl(AppConstants.MODE_STOP, 0, 0)
        }
    }
}
