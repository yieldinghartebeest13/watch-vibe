package com.yieldinghartebeest13.watchvibe

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        private const val KEY_INTENSITY = "saved_intensity"
    }

    private val wearDataLayer = WearDataLayer.getInstance(application)
    private val sessionRecorder = SessionRecorder()

    // Restore selection, not permission to restart. Only onForeground after
    // the Activity's authentication gate may apply a restored active mode.
    private val _mode = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_MODE)?.takeIf {
            it in AppConstants.MODE_CONSTANT..AppConstants.MODE_RANDOM ||
                it == AppConstants.MODE_STOP || it == AppConstants.MODE_PAUSE
        } ?: AppConstants.MODE_PAUSE
    )
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _level = MutableStateFlow(
        (savedStateHandle.get<Int>(KEY_LEVEL) ?: 0).coerceIn(0, 3)
    )
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _intensity = MutableStateFlow(
        (savedStateHandle.get<Int>(KEY_INTENSITY) ?: 100).coerceIn(0, 100)
    )
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

    private var minimizeJob: Job? = null
    private var connectionMonitorGeneration = 0

    private var capabilityListenerRegistered = false
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var aliveChecker: Job? = null
    private var isInForeground: Boolean = false
    private var suppressMinimize: Boolean = false

    /** Call before opening an internal activity to prevent the watch minimizing. */
    fun suppressNextMinimize() { suppressMinimize = true }

    /** Called when the activity comes to the foreground. */
    fun onForeground() {
        isInForeground = true
        minimizeJob?.cancel()
        minimizeJob = null
        if (!_isVibrating.value && _mode.value in AppConstants.MODE_CONSTANT..AppConstants.MODE_RANDOM) {
            applyVibration()
        } else {
            updateHeartbeatOwnership()
        }
        startConnectionMonitor()
    }

    /**
     * Called when the activity goes to the background.
     * Idle status pings stop; the service alone protects active vibration.
     */
    fun onBackground() {
        isInForeground = false
        updateHeartbeatOwnership()
        if (suppressMinimize) {
            suppressMinimize = false
            return
        }
        if (_mode.value == AppConstants.MODE_STOP || _mode.value == AppConstants.MODE_PAUSE) {
            stopHeartbeat()
            stopConnectionMonitor()
            // Not vibrating — tell the watch to minimize so the user
            // doesn't have to manually dismiss it.
            minimizeJob?.cancel()
            minimizeJob = viewModelScope.launch { wearDataLayer.sendMinimize() }
        }
    }

    private fun updateHeartbeatOwnership() {
        val owner = when {
            _isVibrating.value -> PhoneHeartbeat.Owner.SERVICE
            isInForeground -> PhoneHeartbeat.Owner.IDLE_FOREGROUND
            else -> PhoneHeartbeat.Owner.NONE
        }
        wearDataLayer.heartbeat.setOwner(owner)
        if (owner == PhoneHeartbeat.Owner.IDLE_FOREGROUND) {
            wearDataLayer.heartbeat.start(owner, viewModelScope) { wearDataLayer.sendPing() }
        }
    }

    fun stopHeartbeat() {
        if (wearDataLayer.heartbeat.isOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND)) {
            wearDataLayer.heartbeat.setOwner(PhoneHeartbeat.Owner.NONE)
        }
    }

    fun startConnectionMonitor() {
        if (capabilityListenerRegistered) return
        capabilityListenerRegistered = true
        wearDataLayer.onHeartbeatFailure = {
            setMode(AppConstants.MODE_STOP)
            _statusText.value = "Unable to keep connection active"
        }
        val generation = ++connectionMonitorGeneration

        // Initial check — only used for wake-up. Don't set watchConnected yet;
        // the watch must explicitly signal readiness via /alive.
        viewModelScope.launch {
            val hasNode = wearDataLayer.isWearConnected()
            if (hasNode && generation == connectionMonitorGeneration) {
                wearDataLayer.sendWakeUp()
            }
        }

        // Listener for crown exit, battery updates, and alive heartbeat from watch
        wearDataLayer.startMessageListener(
            onCrownExit = { onCrownExit() },
            onBatteryUpdate = { level ->
                _watchBatteryLevel.value = level
                _watchBatteryPending.value = false
            },
            onWatchAlive = {
                val wasDisconnected = !_watchConnected.value
                if (wasDisconnected) {
                    _watchConnected.value = true
                    // Watch just came online — re-send the current command.
                    // This recovers from the race where a mode was tapped
                    // before the watch was fully launched and ready.
                    if (_isVibrating.value) {
                        viewModelScope.launch {
                            wearDataLayer.sendControl(_mode.value, _level.value, _intensity.value)
                        }
                    }
                }
            }
        )

        // Periodic check: if the watch stops sending /alive, show disconnected.
        // This handles the case where the watch activity was dismissed or
        // the connection dropped silently (capability listener may not fire).
        aliveChecker?.cancel()
        aliveChecker = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (_watchConnected.value && !wearDataLayer.isWatchAlive()) {
                    _watchConnected.value = false
                    _watchBatteryPending.value = true
                }
            }
        }

        // Actively request current battery level from the watch
        viewModelScope.launch {
            wearDataLayer.requestBattery()
        }

        // Listener for disconnect detection only — never sets connected=true.
        // The /ready handshake gates the connected state.
        val listener = CapabilityClient.OnCapabilityChangedListener { capInfo ->
            if (capInfo.nodes.isEmpty()) {
                _watchConnected.value = false
                _watchBatteryPending.value = true
            }
        }
        capabilityListener = listener

        val capClient = Wearable.getCapabilityClient(getApplication<Application>())
        capClient.addListener(listener, AppConstants.CAPABILITY_VIBRATION)
            .addOnSuccessListener {
                if (generation != connectionMonitorGeneration) capClient.removeListener(listener)
            }
            .addOnFailureListener {
                if (generation == connectionMonitorGeneration) stopConnectionMonitor()
            }
    }

    fun stopConnectionMonitor() {
        aliveChecker?.cancel()
        aliveChecker = null
        ++connectionMonitorGeneration
        val listener = capabilityListener
        capabilityListener = null
        capabilityListenerRegistered = false
        // Do not launch cleanup into viewModelScope: it is cancelled in onCleared.
        if (listener != null) {
            Wearable.getCapabilityClient(getApplication<Application>()).removeListener(listener)
        }
        wearDataLayer.stopMessageListener()
        wearDataLayer.onHeartbeatFailure = null
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
        savedStateHandle[KEY_INTENSITY] = _intensity.value
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
        recordTransition(currentMode, currentLevel)

        if (currentMode == AppConstants.MODE_STOP || currentMode == AppConstants.MODE_PAUSE) {
            _isVibrating.value = false
            _statusText.value = "Ready"
            updateHeartbeatOwnership()
            // If the user stops vibration while the app is in the background,
            // there's no reason to keep the heartbeat alive.
            if (!isInForeground) {
                stopHeartbeat()
                stopConnectionMonitor()
            }
            // Stop the foreground service — no vibration to protect.
            stopPingService()
        } else {
            _isVibrating.value = true
            minimizeJob?.cancel()
            minimizeJob = null
            updateHeartbeatOwnership()
            val modeLabel = AppConstants.MODE_LABELS[currentMode] ?: "Unknown"
            val speedLabel = AppConstants.SPEED_LABELS[currentLevel]
            _statusText.value = "$modeLabel - $speedLabel"
            // The service is the only active-mode heartbeat owner, including
            // while this Activity is in the foreground.
            if (!startPingService(modeLabel, speedLabel)) {
                setMode(AppConstants.MODE_STOP)
                _statusText.value = "Unable to keep connection active"
                return
            }
        }

        viewModelScope.launch {
            wearDataLayer.sendControl(currentMode, currentLevel, _intensity.value)
        }
    }

    // ── Session recording ─────────────────────────────────

    private fun recordTransition(mode: Int, level: Int) {
        val run = sessionRecorder.transition(mode, level, System.currentTimeMillis(),
            SystemClock.elapsedRealtime()) ?: return
        val application = getApplication<Application>()
        // Finite local write: Activity/ViewModel cleanup must not drop the final
        // segment. No control or UI state is touched by this independent operation.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                StatsDb(application).use { db ->
                    db.insert(run.mode, run.level, run.durationMs, run.startedAt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VibeStats", "Unable to record completed segment", e)
            }
        }
    }

    // ── Foreground service management ────────────────────

    private fun startPingService(modeLabel: String, speedLabel: String): Boolean {
        val context = getApplication<Application>()
        val intent = Intent(context, PingForegroundService::class.java).apply {
            action = PingForegroundService.ACTION_UPDATE_STATUS
            putExtra(PingForegroundService.EXTRA_MODE_LABEL, modeLabel)
            putExtra(PingForegroundService.EXTRA_SPEED_LABEL, speedLabel)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: RuntimeException) {
            Log.e("VibePingSvc", "Unable to start heartbeat service; ending session", e)
            false
        }
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
            val shouldMinimize = isInForeground
            setMode(AppConstants.MODE_STOP)
            stopHeartbeat()
            stopConnectionMonitor()
            stopPingService()
            _crownExitRequested.value = shouldMinimize
        }
    }

    override fun onCleared() {
        super.onCleared()
        minimizeJob?.cancel()
        recordTransition(AppConstants.MODE_STOP, 0)
        wearDataLayer.heartbeat.setOwner(PhoneHeartbeat.Owner.NONE)
        stopConnectionMonitor()
        stopPingService()
        // viewModelScope is already cancelled at this point. This final STOP
        // uses a finite, independently bounded transport operation.
        // Enter sendControl synchronously to stamp STOP before any newer intent.
        CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
            wearDataLayer.sendControl(AppConstants.MODE_STOP, 0, 0)
        }
    }
}
