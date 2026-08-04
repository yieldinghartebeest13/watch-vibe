package com.example.vibecontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val wearDataLayer = WearDataLayer(application)

    private val _mode = MutableStateFlow(AppConstants.MODE_PAUSE)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _intensity = MutableStateFlow(50)
    val intensity: StateFlow<Int> = _intensity.asStateFlow()

    private val _isVibrating = MutableStateFlow(false)
    val isVibrating: StateFlow<Boolean> = _isVibrating.asStateFlow()

    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var heartbeatJob: Job? = null
    private var capabilityListenerRegistered = false
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var isInForeground: Boolean = false

    /** Called when the activity comes to the foreground. */
    fun onForeground() {
        isInForeground = true
        startHeartbeat()
        startConnectionMonitor()
    }

    /**
     * Called when the activity goes to the background.
     * Only stops the heartbeat if no vibration is active — if the user
     * put the phone away while vibrating, pings must continue so the
     * watch doesn't trigger a disconnect.
     */
    fun onBackground() {
        isInForeground = false
        if (_mode.value == AppConstants.MODE_STOP || _mode.value == AppConstants.MODE_PAUSE) {
            stopHeartbeat()
            stopConnectionMonitor()
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
        applyVibration()
    }

    fun minusCadence() {
        _level.value = (_level.value - 1).coerceIn(0, 3)
        applyVibration()
    }

    private fun setMode(mode: Int) {
        _mode.value = mode
        applyVibration()
    }

    private fun applyVibration() {
        val currentMode = _mode.value
        val currentLevel = _level.value

        if (currentMode == AppConstants.MODE_STOP || currentMode == AppConstants.MODE_PAUSE) {
            _isVibrating.value = false
            _statusText.value = "Ready"
            // If the user stops vibration while the app is in the background,
            // there's no reason to keep the heartbeat alive.
            if (!isInForeground) {
                stopHeartbeat()
                stopConnectionMonitor()
            }
        } else {
            _isVibrating.value = true
            val modeLabel = AppConstants.MODE_LABELS[currentMode] ?: "Unknown"
            val speedLabel = AppConstants.SPEED_LABELS[currentLevel]
            _statusText.value = "$modeLabel - $speedLabel"
            // Ensure heartbeat runs even if the user activated this from
            // a notification or the app is otherwise backgrounded.
            if (!isInForeground) {
                startHeartbeat()
            }
        }

        viewModelScope.launch {
            wearDataLayer.sendControl(currentMode, currentLevel, 100)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        stopConnectionMonitor()
        viewModelScope.launch {
            wearDataLayer.sendControl(AppConstants.MODE_STOP, 0, 0)
        }
    }
}
