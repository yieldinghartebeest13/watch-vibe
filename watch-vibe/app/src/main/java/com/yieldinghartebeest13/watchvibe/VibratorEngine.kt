package com.yieldinghartebeest13.watchvibe

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.random.Random

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
 * preventing stutter from multiple listener callbacks. Recovery code can still
 * force a same-mode reassert when the actuator likely stopped silently.
 */
class VibratorEngine(
    context: Context,
    private val nextRandomDuration: (Long, Long) -> Long = { minMs, maxMs ->
        if (maxMs <= minMs) minMs else minMs + Random.Default.nextLong(maxMs - minMs)
    },
    private val nextRandomAmplitude: () -> Int = { Random.Default.nextInt(256) }
) {

    companion object {
        private const val CONSTANT_CYCLE_MS = 1_000L
        private const val RANDOM_SECTION_STEPS = 30
        private const val RANDOM_CONTINUITY_SAMPLE_STEPS = 3
        private const val MAX_REASSERT_SCHEDULE_AHEAD_MS = 1_000L
    }

    private data class RandomContinuity(
        val cadenceMs: Long,
        val amplitude: Int
    )

    private data class PatternPlan(
        val timings: LongArray,
        val amplitudes: IntArray,
        val cycleDurationMs: Long,
        val boundaryWindowMs: Long,
        val randomContinuity: RandomContinuity? = null
    )

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

    private val reassertHandler = Handler(Looper.getMainLooper())

    private var currentMode: Int = AppConstants.MODE_PAUSE
    private var currentLevel: Int = AppConstants.LEVEL_SLOW
    private var currentIntensity: Int = 100
    private var isActive: Boolean = false
    private var lastActuatorCommandElapsedMs: Long = 0
    private var currentPatternPlan: PatternPlan? = null
    private var pendingReassertRunnable: Runnable? = null
    private var pendingReassertAtElapsedMs: Long = 0

    val mode: Int get() = currentMode
    val level: Int get() = currentLevel
    val intensity: Int get() = currentIntensity
    val vibrating: Boolean get() = isActive

    fun hasVibrator(): Boolean = vibrator.hasVibrator()

    @Synchronized
    fun cancel() {
        clearPendingReassertLocked()
        isActive = false
        currentMode = AppConstants.MODE_PAUSE
        currentPatternPlan = null
        lastActuatorCommandElapsedMs = 0

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
        applyModeVibration(mode, level, intensity, force = false)
    }

    @Synchronized
    fun reassertActiveVibration(): Boolean = reassertActiveVibration(SystemClock.elapsedRealtime())

    @Synchronized
    internal fun reassertActiveVibration(nowElapsedMs: Long): Boolean {
        if (!isActive || currentMode == AppConstants.MODE_STOP || currentMode == AppConstants.MODE_PAUSE) {
            return false
        }
        if (pendingReassertRunnable != null) return false

        val delayMs = computeReassertDelayLocked(nowElapsedMs)
        if (delayMs <= 0L) {
            applyModeVibration(currentMode, currentLevel, currentIntensity, force = true)
            return isActive
        }
        if (delayMs > MAX_REASSERT_SCHEDULE_AHEAD_MS) {
            return false
        }

        scheduleReassertLocked(delayMs, nowElapsedMs)
        return true
    }

    @Synchronized
    internal fun shouldReassertActiveVibration(nowElapsedMs: Long, staleAfterMs: Long): Boolean {
        if (!isActive || currentMode == AppConstants.MODE_STOP || currentMode == AppConstants.MODE_PAUSE) {
            return false
        }
        if (pendingReassertRunnable != null) return false
        if (staleAfterMs > 0L && lastActuatorCommandElapsedMs > 0L) {
            if (nowElapsedMs - lastActuatorCommandElapsedMs < staleAfterMs) {
                return false
            }
        }
        return computeReassertDelayLocked(nowElapsedMs) <= MAX_REASSERT_SCHEDULE_AHEAD_MS
    }

    private fun applyModeVibration(mode: Int, level: Int, intensity: Int, force: Boolean) {
        if (!force && mode == currentMode && level == currentLevel && intensity == currentIntensity && isActive) return

        clearPendingReassertLocked()
        val previousRandomContinuity = currentRandomContinuityLocked()

        currentMode = mode
        currentLevel = level
        currentIntensity = intensity

        when (mode) {
            AppConstants.MODE_STOP, AppConstants.MODE_PAUSE -> {
                cancel()
                return
            }

            AppConstants.MODE_CONSTANT -> {
                val timings = longArrayOf(CONSTANT_CYCLE_MS)
                val amplitudes = intArrayOf(255)
                activatePattern(mode, timings, amplitudes)
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
                activatePattern(mode, timings, amplitudes)
            }

            AppConstants.MODE_RAMP -> {
                val count = 5
                val stepMs = when (level.coerceIn(0, 3)) {
                    0 -> 200L; 1 -> 130L; 2 -> 80L; 3 -> 50L; else -> 200L
                }
                val timings = LongArray(count) { stepMs }
                val amplitudes = IntArray(count) { i -> ((i + 1) * 255 / count) }
                activatePattern(mode, timings, amplitudes)
            }

            AppConstants.MODE_BURST -> {
                // Burst base cycle is slowed vs other modes — the amplitude API
                // needs ~50ms minimum per step to avoid motor artifacts.
                // Level 0 = 1000ms, level 3 = 370ms (50ms tap floor).
                val (tap, pause) = when (level.coerceIn(0, 3)) {
                    0 -> 150L to 250L   // 5*150+250 = 1000ms
                    1 -> 100L to 200L   // 5*100+200 = 700ms
                    2 ->  70L to 150L   // 5*70+150  = 500ms
                    3 ->  50L to 120L   // 5*50+120  = 370ms
                    else -> 150L to 250L
                }
                val timings = longArrayOf(tap, tap, tap, tap, tap, pause)
                val amplitudes = intArrayOf(255, 0, 255, 0, 255, 0)
                activatePattern(mode, timings, amplitudes)
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
                activatePattern(mode, timings, amplitudes)
            }

            AppConstants.MODE_RANDOM -> {
                val (timings, amplitudes) = buildRandomPattern(level, previousRandomContinuity)
                activatePattern(mode, timings, amplitudes)
            }
        }
    }

    /**
     * Play a one-shot custom waveform for real-time manual control.
     * Does NOT loop — plays once and stops. Does not change currentMode.
     * Use for external pattern editors / live control surfaces.
     */
    fun vibratePulse(timings: LongArray, repeat: Int = -1) {
        lastActuatorCommandElapsedMs = SystemClock.elapsedRealtime()
        isActive = true
        if (!vibrator.hasVibrator()) return
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
        lastActuatorCommandElapsedMs = SystemClock.elapsedRealtime()
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

    private fun activatePattern(mode: Int, timings: LongArray, amplitudes: IntArray) {
        val randomContinuity = if (mode == AppConstants.MODE_RANDOM) {
            extractRandomContinuity(timings, amplitudes)
        } else {
            null
        }
        val (scaledTimings, scaledAmplitudes) = scalePower(timings, amplitudes, currentIntensity)
        currentPatternPlan = createPatternPlan(scaledTimings, scaledAmplitudes, randomContinuity)
        isActive = true
        vibrate(scaledTimings, scaledAmplitudes)
    }

    private fun createPatternPlan(
        timings: LongArray,
        amplitudes: IntArray,
        randomContinuity: RandomContinuity? = null
    ): PatternPlan {
        val cycleDurationMs = timings.sum().coerceAtLeast(1L)
        val boundaryWindowMs = computeBoundaryWindowMs(timings, cycleDurationMs)
        return PatternPlan(timings, amplitudes, cycleDurationMs, boundaryWindowMs, randomContinuity)
    }

    private fun buildRandomPattern(level: Int, continuity: RandomContinuity?): Pair<LongArray, IntArray> {
        val count = RANDOM_SECTION_STEPS
        val (minMs, maxMs) = when (level.coerceIn(0, 3)) {
            0 -> 100L to 500L
            1 -> 60L to 350L
            2 -> 30L to 200L
            3 -> 15L to 120L
            else -> 100L to 500L
        }
        val timings = LongArray(count) { nextRandomDuration(minMs, maxMs) }
        val amplitudes = IntArray(count) { nextRandomAmplitude().coerceIn(0, 255) }

        continuity?.let {
            timings[0] = it.cadenceMs.coerceIn(minMs, maxMs)
            amplitudes[0] = it.amplitude.coerceIn(0, 255)
            if (count > 1) {
                timings[1] = ((timings[0] + timings[1]) / 2L).coerceIn(minMs, maxMs)
                amplitudes[1] = ((amplitudes[0] + amplitudes[1]) / 2).coerceIn(0, 255)
            }
        }

        return Pair(timings, amplitudes)
    }

    private fun extractRandomContinuity(timings: LongArray, amplitudes: IntArray): RandomContinuity {
        val sampleCount = minOf(RANDOM_CONTINUITY_SAMPLE_STEPS, timings.size)
        val cadenceMs = if (sampleCount == 0) {
            1L
        } else {
            var total = 0L
            for (i in timings.size - sampleCount until timings.size) {
                total += timings[i]
            }
            (total / sampleCount).coerceAtLeast(1L)
        }
        val amplitude = amplitudes.lastOrNull()?.coerceIn(0, 255) ?: 0
        return RandomContinuity(cadenceMs, amplitude)
    }

    private fun computeBoundaryWindowMs(timings: LongArray, cycleDurationMs: Long): Long {
        val minStepMs = timings.minOrNull() ?: cycleDurationMs
        return maxOf(8L, minOf(60L, minStepMs / 2L)).coerceAtMost(cycleDurationMs)
    }

    private fun computeReassertDelayLocked(nowElapsedMs: Long): Long {
        val plan = currentPatternPlan ?: return 0L
        if (lastActuatorCommandElapsedMs <= 0L || plan.cycleDurationMs <= 0L) return 0L

        val ageMs = nowElapsedMs - lastActuatorCommandElapsedMs
        if (ageMs <= plan.boundaryWindowMs) return 0L

        val phaseMs = ageMs.mod(plan.cycleDurationMs)
        if (phaseMs <= plan.boundaryWindowMs) return 0L

        return plan.cycleDurationMs - phaseMs
    }

    private fun currentRandomContinuityLocked(): RandomContinuity? =
        if (currentMode == AppConstants.MODE_RANDOM) currentPatternPlan?.randomContinuity else null

    private fun scheduleReassertLocked(delayMs: Long, nowElapsedMs: Long) {
        clearPendingReassertLocked()
        val runnable = object : Runnable {
            override fun run() {
                synchronized(this@VibratorEngine) {
                    if (pendingReassertRunnable !== this) return
                    pendingReassertRunnable = null
                    pendingReassertAtElapsedMs = 0L
                    if (!isActive || currentMode == AppConstants.MODE_STOP || currentMode == AppConstants.MODE_PAUSE) {
                        return
                    }
                    applyModeVibration(currentMode, currentLevel, currentIntensity, force = true)
                }
            }
        }
        pendingReassertRunnable = runnable
        pendingReassertAtElapsedMs = nowElapsedMs + delayMs
        reassertHandler.postDelayed(runnable, delayMs)
    }

    private fun clearPendingReassertLocked() {
        pendingReassertRunnable?.let { reassertHandler.removeCallbacks(it) }
        pendingReassertRunnable = null
        pendingReassertAtElapsedMs = 0L
    }

    internal fun currentPatternTimingsForTesting(): LongArray =
        currentPatternPlan?.timings?.clone() ?: longArrayOf()

    internal fun currentPatternAmplitudesForTesting(): IntArray =
        currentPatternPlan?.amplitudes?.clone() ?: intArrayOf()

    internal fun currentPatternCycleDurationMsForTesting(): Long =
        currentPatternPlan?.cycleDurationMs ?: 0L

    internal fun currentPatternBoundaryWindowMsForTesting(): Long =
        currentPatternPlan?.boundaryWindowMs ?: 0L

    internal fun lastActuatorCommandElapsedMsForTesting(): Long = lastActuatorCommandElapsedMs

    internal fun pendingReassertAtElapsedMsForTesting(): Long = pendingReassertAtElapsedMs

    internal fun hasPendingReassertForTesting(): Boolean = pendingReassertRunnable != null
}
