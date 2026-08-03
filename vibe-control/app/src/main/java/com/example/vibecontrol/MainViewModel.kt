package com.example.vibecontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    companion object {
        const val MODE_STOP = -2
        const val MODE_PAUSE = -3
        const val MODE_CONSTANT = 0
        const val MODE_INTERMITTENT = 1
        const val MODE_RAMP = 2
        const val MODE_BURST = 3
        const val MODE_WAVE = 4
        const val MODE_RANDOM = 5
    }

    private val wearDataLayer = WearDataLayer(application)

    private val _mode = MutableStateFlow(MODE_PAUSE)
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

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
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
                // Send wake-up in case the watch app was killed
                wearDataLayer.sendWakeUp()
            }
        }

        // Listener for real-time changes
        viewModelScope.launch {
            try {
                val capClient = Wearable.getCapabilityClient(getApplication())
                capClient.addListener(
                    { capInfo ->
                        val connected = capInfo.nodes.isNotEmpty()
                        _watchConnected.value = connected
                    },
                    "vibration_control"
                ).await()
            } catch (e: Exception) {
                capabilityListenerRegistered = false
            }
        }
    }

    fun stopConnectionMonitor() {
        // Listener stays registered for the app lifetime — no need to remove
        // unless we want explicit cleanup. For now, leave it.
    }

    fun checkWearConnection() {
        viewModelScope.launch {
            _watchConnected.value = wearDataLayer.isWearConnected()
        }
    }

    fun modeConstant() {
        setMode(MODE_CONSTANT)
    }

    fun modeIntermittent() {
        setMode(MODE_INTERMITTENT)
    }

    fun modeRamp() {
        setMode(MODE_RAMP)
    }

    fun modeBurst() {
        setMode(MODE_BURST)
    }

    fun modeWave() {
        setMode(MODE_WAVE)
    }

    fun modeRandom() {
        setMode(MODE_RANDOM)
    }

    fun modeStop() {
        setMode(MODE_STOP)
    }

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

        // Send to Wear only (phone doesn't vibrate locally)
        if (currentMode == MODE_STOP || currentMode == MODE_PAUSE) {
            _isVibrating.value = false
            _statusText.value = "Ready"
        } else {
            _isVibrating.value = true

            val modeLabel = when (currentMode) {
                MODE_CONSTANT -> "Constant"
                MODE_INTERMITTENT -> "Intermittent"
                MODE_RAMP -> "Ramp"
                MODE_BURST -> "Burst"
                MODE_WAVE -> "Wave"
                MODE_RANDOM -> "Random"
                else -> "Unknown"
            }

            val speedLabel = arrayOf("Slow", "Medium", "Fast", "Very Fast")[currentLevel]
            _statusText.value = "$modeLabel - $speedLabel"
        }

        viewModelScope.launch {
            wearDataLayer.sendControl(currentMode, currentLevel, 100)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            wearDataLayer.sendControl(MODE_STOP, 0, 0)
        }
    }
}
