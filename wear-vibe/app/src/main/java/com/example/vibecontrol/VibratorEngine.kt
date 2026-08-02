package com.example.vibecontrol

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context

/**
 * Core vibration engine matching the original Happy Rumble patterns.
 *
 * Modes:
 *   MODE_STOP (-2)      = cancel vibration
 *   MODE_PAUSE (-3)      = cancel (pause behavior)
 *   MODE_CONSTANT (0)    = continuous vibration  [wait 1ms, on 5000ms] loop
 *   MODE_INTERMITTENT (1)= pulsing patterns at 4 levels
 *   MODE_WAVE (2)        = escalating pulses that ramp up then reset
 *   MODE_BURST (3)       = triple-tap throb with variable pause
 *
 * Intermittent levels:
 *   Level 0 (slow):      [wait 500ms, on 500ms] loop
 *   Level 1 (medium):    [wait 250, on 250, off 250, on 250] loop
 *   Level 2 (fast):      [wait 125, on 125, off 125, on 125] loop
 *   Level 3 (very fast): [wait 75, on 75, off 75, on 75] loop
 *
 * Wave: level (0-3) controls speed → gap ranges from 200ms (slow) to 50ms (fast).
 *   Pattern [50,gap, 100,gap, 150,gap, 200,gap, 250,gap, 300,gap].
 *   Intensity (0-100) scales ON-duration power.
 *
 * Burst: level (0-3) controls speed → pause ranges from 1000ms (slow) to 350ms (fast).
 *   Pattern [30,30, 30,30, 30, pause].
 *   Intensity (0-100) scales ON-duration power.
 *
 * Dedup: calling setModeVibration with identical mode+level+intensity is a no-op,
 * preventing stutter from multiple listener callbacks.
 */
class VibratorEngine(context: Context) {

    companion object {
        const val MODE_CONSTANT = 0
        const val MODE_INTERMITTENT = 1
        const val MODE_RAMP = 2
        const val MODE_BURST = 3
        const val MODE_WAVE = 4
        const val MODE_RANDOM = 5
        const val MODE_STOP = -2
        const val MODE_PAUSE = -3

        const val LEVEL_SLOW = 0
        const val LEVEL_MEDIUM = 1
        const val LEVEL_FAST = 2
        const val LEVEL_VERY_FAST = 3
    }

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

    private var currentMode: Int = MODE_PAUSE
    private var currentLevel: Int = LEVEL_SLOW
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
        currentMode = MODE_PAUSE

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
            MODE_STOP, MODE_PAUSE -> {
                cancel()
                return
            }

            MODE_CONSTANT -> {
                // [wait 1ms, on 5000ms] looped — effectively continuous
                // (the 1ms gap between 5s cycles is imperceptible)
                val pattern = longArrayOf(1, 5000)
                isActive = true
                vibrate(scalePower(pattern, currentIntensity))
            }

            MODE_INTERMITTENT -> {
                val pattern = when (level) {
                    LEVEL_SLOW -> longArrayOf(500, 500)
                    LEVEL_MEDIUM -> longArrayOf(250, 250, 250, 250)
                    LEVEL_FAST -> longArrayOf(125, 125, 125, 125)
                    LEVEL_VERY_FAST -> longArrayOf(75, 75, 75, 75)
                    else -> longArrayOf(500, 500)
                }
                isActive = true
                vibrate(scalePower(pattern, currentIntensity))
            }

            MODE_RAMP -> {
                // level (0-3) controls speed via gap size
                val gap = when (level.coerceIn(0, 3)) {
                    0 -> 200L
                    1 -> 130L
                    2 -> 80L
                    3 -> 50L
                    else -> 200L
                }
                val pattern = longArrayOf(
                    50, gap,
                    100, gap,
                    150, gap,
                    200, gap,
                    250, gap,
                    300, gap
                )
                isActive = true
                vibrate(scalePower(pattern, currentIntensity))
            }

            MODE_WAVE -> {
                val pattern = generateWavePattern(currentLevel, currentIntensity)
                isActive = true
                vibrate(pattern)
            }

            MODE_RANDOM -> {
                val pattern = generateRandomPattern(currentLevel, currentIntensity)
                isActive = true
                vibrate(pattern)
            }

            MODE_BURST -> {
                // level (0-3) controls speed via pause between bursts
                val pause = when (level.coerceIn(0, 3)) {
                    0 -> 1000L
                    1 -> 700L
                    2 -> 500L
                    3 -> 350L
                    else -> 1000L
                }
                val pattern = longArrayOf(30, 30, 30, 30, 30, pause)
                isActive = true
                vibrate(scalePower(pattern, currentIntensity))
            }
        }
    }

    /**
     * Play a one-shot custom waveform for real-time manual control.
     * Does NOT loop — plays once and stops. Does not change currentMode.
     * Use for external pattern editors / live control surfaces.
     *
     * @param timings alternating [wait, on, off, on, ...] in ms (same format
     *                as Android VibrationEffect timings)
     * @param repeat index into timings to loop from, or -1 to play once
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
     * Scale odd-index (ON) durations by intensity factor.
     * In Android VibrationEffect timings, even indices are OFF durations,
     * odd indices are ON durations. At 100% intensity = full power,
     * at 50% = half-duration pulses, at 0% = 1ms (barely perceptible).
     */
    private fun scalePower(pattern: LongArray, intensity: Int): LongArray {
        if (intensity >= 100) return pattern
        val factor = intensity / 100f
        return LongArray(pattern.size) { i ->
            if (i % 2 == 1) (pattern[i] * factor).toLong().coerceAtLeast(1)
            else pattern[i]
        }
    }

    private fun generateWavePattern(level: Int, intensity: Int): LongArray {
        val steps: Int
        val minOn: Long
        val maxOn: Long
        val gap: Long

        when (level.coerceIn(0, 3)) {
            0 -> { steps = 5; minOn = 40; maxOn = 350; gap = 120 }
            1 -> { steps = 4; minOn = 30; maxOn = 250; gap = 80 }
            2 -> { steps = 4; minOn = 20; maxOn = 180; gap = 50 }
            3 -> { steps = 3; minOn = 15; maxOn = 120; gap = 30 }
            else -> { steps = 5; minOn = 40; maxOn = 350; gap = 120 }
        }

        val pattern = mutableListOf<Long>()
        // Rising phase
        for (i in 0 until steps) {
            val on = minOn + ((maxOn - minOn) * i / (steps - 1).coerceAtLeast(1))
            pattern.add(gap)
            pattern.add(on)
        }
        // Peak
        pattern.add(gap)
        pattern.add(maxOn)
        // Falling phase
        for (i in steps - 1 downTo 0) {
            val on = minOn + ((maxOn - minOn) * i / (steps - 1).coerceAtLeast(1))
            pattern.add(gap)
            pattern.add(on)
        }

        return scalePower(pattern.toLongArray(), intensity)
    }

    private fun generateRandomPattern(level: Int, intensity: Int): LongArray {
        val onMin: Long
        val onMax: Long
        val gapMin: Long
        val gapMax: Long
        val count: Int

        when (level.coerceIn(0, 3)) {
            0 -> { onMin = 100; onMax = 500; gapMin = 100; gapMax = 400; count = 8 }
            1 -> { onMin = 60; onMax = 350; gapMin = 60; gapMax = 250; count = 10 }
            2 -> { onMin = 30; onMax = 200; gapMin = 30; gapMax = 150; count = 12 }
            3 -> { onMin = 15; onMax = 120; gapMin = 15; gapMax = 80; count = 14 }
            else -> { onMin = 100; onMax = 500; gapMin = 100; gapMax = 400; count = 8 }
        }

        val pattern = LongArray(count * 2)
        for (i in 0 until count) {
            val on = onMin + (Math.random() * (onMax - onMin + 1)).toLong()
            val gap = gapMin + (Math.random() * (gapMax - gapMin + 1)).toLong()
            pattern[i * 2] = gap      // wait
            pattern[i * 2 + 1] = on   // on
        }
        return scalePower(pattern, intensity)
    }

    private fun vibrate(pattern: LongArray) {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0) // 0 = loop indefinitely
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // USAGE_ALARM bypasses ambient-mode restrictions on Wear OS
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vibrator.vibrate(effect)
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }
}
