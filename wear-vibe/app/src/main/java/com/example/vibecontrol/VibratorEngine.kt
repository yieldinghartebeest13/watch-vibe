package com.example.vibecontrol

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context

/**
 * Core vibration engine using amplitude-based VibrationEffect APIs.
 *
 * Each mode generates a waveform as paired (timings, amplitudes) arrays:
 *   - timings: duration in ms for each step
 *   - amplitudes: per-step amplitude 0-255 (0=off, 255=max)
 *
 * Modes:
 *   MODE_STOP (-2)      = cancel vibration
 *   MODE_PAUSE (-3)     = cancel (pause behavior)
 *   MODE_CONSTANT (0)   = continuous vibration at amplitude 255
 *   MODE_INTERMITTENT (1)= binary on/off pulsing via amplitudes
 *   MODE_RAMP (2)       = staircase ascending amplitudes
 *   MODE_BURST (3)      = triple full-amplitude taps with pause
 *   MODE_WAVE (4)       = smooth sine amplitude envelope
 *   MODE_RANDOM (5)     = random amplitudes and durations
 *
 * Level (0=slow .. 3=very fast) controls timing speed.
 * Intensity (0-100) scales amplitudes via scalePower.
 *
 * Dedup: calling setModeVibration with identical mode+level+intensity is a no-op,
 * preventing stutter from multiple listener callbacks.
 */
class VibratorEngine(context: Context) {

    private val vibratorManager: VibratorManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        } else null

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        vibratorManager!!.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var currentMode: Int = AppConstants.MODE_PAUSE
    private var currentLevel: Int = AppConstants.LEVEL_SLOW
    private var currentIntensity: Int = 100
    private var isActive: Boolean = false

    val mode: Int get() = currentMode
    val level: Int get() = currentLevel
    val intensity: Int get() = currentIntensity
    val vibrating: Boolean get() = isActive

    fun hasVibrator(): Boolean = vibrator.hasVibrator()

    @Synchronized
    fun cancel() {
        isActive = false
        currentMode = AppConstants.MODE_PAUSE

        // 1. Cancel via VibratorManager (API 31+) — cancels ALL vibrators
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { vibratorManager?.cancel() } catch (_: Exception) {}
        }

        // 2. Cancel via individual Vibrator
        try { vibrator.cancel() } catch (_: Exception) {}

        // 3. Flush the alarm-class pipeline with a zero-amplitude effect.
        //    Our vibrate() uses USAGE_ALARM which routes to a separate
        //    pipeline on Wear OS. Sending a 1ms/amplitude-0 effect with
        //    the SAME usage class flushes that pipeline and kills the
        //    looping waveform. Without this, cancel() appears to work
        //    initially but fails after the effect has been running a while.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(1, 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                )
            } catch (_: Exception) {}
        }

        // 4. Flush the default pipeline too.
        //    On Wear OS 5+ this covers the case where the HAL has taken
        //    ownership of the effect and the ALARM-path flush alone is not enough.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                vibrator.vibrate(VibrationEffect.createOneShot(1, 0))
            } catch (_: Exception) {}
        }
    }

    /**
     * Set vibration mode and level. No-op if mode+level unchanged (prevents
     * stutter from multiple listener callbacks arriving in quick succession).
     * Thread-safe — all listener callbacks synchronize on the engine instance.
     */
    @Synchronized
    fun setModeVibration(mode: Int, level: Int, intensity: Int = 100) {
        if (mode == currentMode && level == currentLevel && intensity == currentIntensity && isActive) return

        currentMode = mode
        currentLevel = level
        currentIntensity = intensity

        when (mode) {
            AppConstants.MODE_STOP, AppConstants.MODE_PAUSE -> {
                cancel()
                return
            }

            AppConstants.MODE_CONSTANT -> {
                val timings = longArrayOf(5000)
                val amplitudes = intArrayOf(255)
                val (t, a) = scalePower(timings, amplitudes, currentIntensity)
                isActive = true
                vibrate(t, a)
            }

            AppConstants.MODE_INTERMITTENT -> {
                // 70% on / 30% off duty cycle. Cycle target: 1000ms (level 0)
                val (on, off) = when (level.coerceIn(0, 3)) {
                    0 -> 700L to 300L
                    1 -> 228L to 97L
                    2 -> 140L to 60L
                    3 -> 88L to 37L
                    else -> 700L to 300L
                }
                val timings = longArrayOf(on, off)
                val amplitudes = intArrayOf(255, 0)
                val (t, a) = scalePower(timings, amplitudes, currentIntensity)
                isActive = true
                vibrate(t, a)
            }

            AppConstants.MODE_RAMP -> {
                val count = 5
                val stepMs = when (level.coerceIn(0, 3)) {
                    0 -> 200L; 1 -> 130L; 2 -> 80L; 3 -> 50L; else -> 200L
                }
                val timings = LongArray(count) { stepMs }
                val amplitudes = IntArray(count) { i -> ((i + 1) * 255 / count) }
                val (t, a) = scalePower(timings, amplitudes, currentIntensity)
                isActive = true
                vibrate(t, a)
            }

            AppConstants.MODE_BURST -> {
                val (tap, pause) = when (level.coerceIn(0, 3)) {
                    0 -> 140L to 300L
                    1 -> 90L to 195L
                    2 -> 55L to 120L
                    3 -> 35L to 75L
                    else -> 140L to 300L
                }
                val timings = longArrayOf(tap, tap, tap, tap, tap, pause)
                val amplitudes = intArrayOf(255, 0, 255, 0, 255, 0)
                val (t, a) = scalePower(timings, amplitudes, currentIntensity)
                isActive = true
                vibrate(t, a)
            }

            AppConstants.MODE_WAVE -> {
                // Cycle target: 1000ms (level 0), 20 steps
                val steps = 20
                val stepMs = when (level.coerceIn(0, 3)) {
                    0 -> 50L; 1 -> 32L; 2 -> 20L; 3 -> 12L; else -> 50L
                }
                val timings = LongArray(steps) { stepMs }
                val amplitudes = IntArray(steps) { i ->
                    val angle = -Math.PI / 2 + i * 2.0 * Math.PI / steps
                    ((Math.sin(angle) * 127 + 128).toInt()).coerceIn(0, 255)
                }
                val (t, a) = scalePower(timings, amplitudes, currentIntensity)
                isActive = true
                vibrate(t, a)
            }

            AppConstants.MODE_RANDOM -> {
                // Long pattern (30 steps) — loop is long enough to feel unpredictable.
                val count = 30
                val (minMs, maxMs) = when (level.coerceIn(0, 3)) {
                    0 -> 100L to 500L
                    1 -> 60L to 350L
                    2 -> 30L to 200L
                    3 -> 15L to 120L
                    else -> 100L to 500L
                }
                val timings = LongArray(count) { minMs + (Math.random() * (maxMs - minMs)).toLong() }
                val amplitudes = IntArray(count) { (Math.random() * 256).toInt() }
                val (t, a) = scalePower(timings, amplitudes, currentIntensity)
                isActive = true
                vibrate(t, a)
            }
        }
    }

    /**
     * Play a one-shot custom waveform for real-time manual control.
     * Does NOT loop — plays once and stops. Does not change currentMode.
     * Use for external pattern editors / live control surfaces.
     */
    fun vibratePulse(timings: LongArray, repeat: Int = -1) {
        if (!vibrator.hasVibrator()) return
        isActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, repeat)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vibrator.vibrate(effect)
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, repeat)
        }
    }

    /**
     * Scale amplitudes by intensity factor (0-100).
     * Timings are left unchanged — only amplitudes are attenuated.
     */
    private fun scalePower(timings: LongArray, amplitudes: IntArray, intensity: Int): Pair<LongArray, IntArray> {
        if (intensity >= 100) return Pair(timings, amplitudes)
        val factor = intensity / 100f
        val scaledAmps = IntArray(amplitudes.size) { i ->
            (amplitudes[i] * factor).toInt().coerceIn(0, 255)
        }
        return Pair(timings, scaledAmps)
    }

    /**
     * Play a looping waveform using amplitude-based API.
     *
     * Uses VibrationEffect.createWaveform(timings, amplitudes, repeat)
     * where each step has its own duration and amplitude (0-255).
     */
    private fun vibrate(timings: LongArray, amplitudes: IntArray) {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, 0) // 0 = loop
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vibrator.vibrate(effect)
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0), -1)
        }
    }

    /** Legacy vibrate using binary on/off pattern (no amplitudes). */
    private fun vibrateLegacy(pattern: LongArray) {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vibrator.vibrate(effect)
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    /** Scale ON durations (odd indices) for legacy binary patterns. */
    private fun scalePowerLegacy(pattern: LongArray, intensity: Int): LongArray {
        if (intensity >= 100) return pattern
        val factor = intensity / 100f
        return LongArray(pattern.size) { i ->
            if (i % 2 == 1) (pattern[i] * factor).toLong().coerceAtLeast(1)
            else pattern[i]
        }
    }
}
