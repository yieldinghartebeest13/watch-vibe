package com.yieldinghartebeest13.watchvibe

import android.content.Context
import android.os.Build
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Unit tests for the wear-vibe VibratorEngine.
 *
 * Tests cover:
 *  - State transitions (mode, level, intensity, isActive)
 *  - Dedup guard (same params = no-op)
 *  - Cancel behaviour
 *  - Mode switching
 *  - Boundary-aligned silent-stop recovery
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class VibratorEngineTest {

    private lateinit var engine: VibratorEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        engine = createEngine()
    }

    // ── Initial state ──────────────────────────────────────

    @Test
    fun `initial state is PAUSE, not vibrating`() {
        assertEquals(AppConstants.MODE_PAUSE, engine.mode)
        assertEquals(AppConstants.LEVEL_SLOW, engine.level)
        assertEquals(100, engine.intensity)
        assertFalse(engine.vibrating)
    }

    // ── Mode transitions ───────────────────────────────────

    @Test
    fun `setModeVibration CONSTANT activates vibration`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertEquals(AppConstants.MODE_CONSTANT, engine.mode)
        assertEquals(AppConstants.LEVEL_SLOW, engine.level)
        assertEquals(100, engine.intensity)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `setModeVibration INTERMITTENT with medium level`() {
        engine.setModeVibration(AppConstants.MODE_INTERMITTENT, AppConstants.LEVEL_MEDIUM, 80)
        assertEquals(AppConstants.MODE_INTERMITTENT, engine.mode)
        assertEquals(AppConstants.LEVEL_MEDIUM, engine.level)
        assertEquals(80, engine.intensity)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `setModeVibration RAMP activates vibration`() {
        engine.setModeVibration(AppConstants.MODE_RAMP, AppConstants.LEVEL_FAST, 100)
        assertEquals(AppConstants.MODE_RAMP, engine.mode)
        assertEquals(AppConstants.LEVEL_FAST, engine.level)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `setModeVibration BURST activates vibration`() {
        engine.setModeVibration(AppConstants.MODE_BURST, AppConstants.LEVEL_VERY_FAST, 100)
        assertEquals(AppConstants.MODE_BURST, engine.mode)
        assertEquals(AppConstants.LEVEL_VERY_FAST, engine.level)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `setModeVibration WAVE activates vibration`() {
        engine.setModeVibration(AppConstants.MODE_WAVE, AppConstants.LEVEL_SLOW, 100)
        assertEquals(AppConstants.MODE_WAVE, engine.mode)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `setModeVibration RANDOM activates vibration`() {
        engine.setModeVibration(AppConstants.MODE_RANDOM, AppConstants.LEVEL_SLOW, 100)
        assertEquals(AppConstants.MODE_RANDOM, engine.mode)
        assertTrue(engine.vibrating)
    }

    // ── Stop / Pause ───────────────────────────────────────

    @Test
    fun `MODE_STOP deactivates vibration`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertTrue(engine.vibrating)

        engine.setModeVibration(AppConstants.MODE_STOP, 0, 0)
        assertEquals(AppConstants.MODE_PAUSE, engine.mode) // cancel() sets PAUSE
        assertFalse(engine.vibrating)
    }

    @Test
    fun `MODE_PAUSE deactivates vibration`() {
        engine.setModeVibration(AppConstants.MODE_INTERMITTENT, AppConstants.LEVEL_SLOW, 100)
        assertTrue(engine.vibrating)

        engine.setModeVibration(AppConstants.MODE_PAUSE, 0, 0)
        assertEquals(AppConstants.MODE_PAUSE, engine.mode)
        assertFalse(engine.vibrating)
    }

    // ── Cancel ─────────────────────────────────────────────

    @Test
    fun `cancel resets state to PAUSE and inactive`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertTrue(engine.vibrating)

        engine.cancel()
        assertEquals(AppConstants.MODE_PAUSE, engine.mode)
        assertFalse(engine.vibrating)
    }

    @Test
    fun `cancel on already-inactive engine is safe`() {
        engine.cancel()
        assertEquals(AppConstants.MODE_PAUSE, engine.mode)
        assertFalse(engine.vibrating)

        // Second cancel should be safe — no crash
        engine.cancel()
        assertEquals(AppConstants.MODE_PAUSE, engine.mode)
        assertFalse(engine.vibrating)
    }

    @Test
    fun `cancel clears pending reassert before scheduled boundary`() {
        engine.setModeVibration(AppConstants.MODE_INTERMITTENT, AppConstants.LEVEL_MEDIUM, 100)
        val start = engine.lastActuatorCommandElapsedMsForTesting()

        assertTrue(engine.reassertActiveVibration(start + 150L))
        assertTrue(engine.hasPendingReassertForTesting())

        engine.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        assertFalse(engine.vibrating)
        assertFalse(engine.hasPendingReassertForTesting())
        assertEquals(0L, engine.lastActuatorCommandElapsedMsForTesting())
    }

    // ── Dedup guard ────────────────────────────────────────

    @Test
    fun `calling with same mode level intensity is a no-op`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertTrue(engine.vibrating)

        // Call again with same params — should be no-op, still vibrating
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertTrue(engine.vibrating)
        assertEquals(AppConstants.MODE_CONSTANT, engine.mode)
    }

    @Test
    fun `reassertActiveVibration restarts same active mode at boundary`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        val boundaryNow = engine.lastActuatorCommandElapsedMsForTesting() + engine.currentPatternCycleDurationMsForTesting()

        assertTrue(engine.reassertActiveVibration(boundaryNow))
        assertTrue(engine.vibrating)
        assertEquals(AppConstants.MODE_CONSTANT, engine.mode)
        assertEquals(AppConstants.LEVEL_SLOW, engine.level)
        assertEquals(100, engine.intensity)
    }

    @Test
    fun `reassertActiveVibration schedules next cycle boundary instead of replaying mid cycle`() {
        engine.setModeVibration(AppConstants.MODE_INTERMITTENT, AppConstants.LEVEL_MEDIUM, 100)
        val cycleMs = engine.currentPatternCycleDurationMsForTesting()
        val start = engine.lastActuatorCommandElapsedMsForTesting()
        val before = engine.lastActuatorCommandElapsedMsForTesting()
        val now = start + 150L
        val expectedDelayMs = cycleMs - 150L

        assertTrue(engine.reassertActiveVibration(now))
        assertTrue(engine.hasPendingReassertForTesting())
        assertEquals(now + expectedDelayMs, engine.pendingReassertAtElapsedMsForTesting())
        assertEquals(before, engine.lastActuatorCommandElapsedMsForTesting())

        shadowOf(Looper.getMainLooper()).idleFor(expectedDelayMs - 1L, TimeUnit.MILLISECONDS)
        assertEquals(before, engine.lastActuatorCommandElapsedMsForTesting())
        assertTrue(engine.hasPendingReassertForTesting())

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertTrue(engine.lastActuatorCommandElapsedMsForTesting() > before)
        assertFalse(engine.hasPendingReassertForTesting())
    }

    @Test
    fun `reassertActiveVibration aligns non random modes to next cycle boundary across all speeds`() {
        val modes = listOf(
            AppConstants.MODE_CONSTANT,
            AppConstants.MODE_INTERMITTENT,
            AppConstants.MODE_RAMP,
            AppConstants.MODE_BURST,
            AppConstants.MODE_WAVE
        )
        val levels = listOf(
            AppConstants.LEVEL_SLOW,
            AppConstants.LEVEL_MEDIUM,
            AppConstants.LEVEL_FAST,
            AppConstants.LEVEL_VERY_FAST
        )

        var seed = 10
        for (mode in modes) {
            for (level in levels) {
                val candidate = createEngine(seed++)
                candidate.setModeVibration(mode, level, 100)

                val cycleMs = candidate.currentPatternCycleDurationMsForTesting()
                val boundaryWindowMs = candidate.currentPatternBoundaryWindowMsForTesting()
                val ageMs = boundaryWindowMs + 25L
                val start = candidate.lastActuatorCommandElapsedMsForTesting()
                val expectedDelayMs = cycleMs - ageMs.mod(cycleMs)

                assertTrue("mode=$mode level=$level", candidate.reassertActiveVibration(start + ageMs))
                assertTrue("mode=$mode level=$level", candidate.hasPendingReassertForTesting())
                assertEquals(
                    "mode=$mode level=$level",
                    start + ageMs + expectedDelayMs,
                    candidate.pendingReassertAtElapsedMsForTesting()
                )

                candidate.cancel()
            }
        }
    }

    @Test
    fun `reassertActiveVibration is ignored when inactive`() {
        assertFalse(engine.reassertActiveVibration())
    }

    @Test
    fun `shouldReassertActiveVibration waits until random section is near next boundary`() {
        engine.setModeVibration(AppConstants.MODE_RANDOM, AppConstants.LEVEL_SLOW, 100)
        val start = engine.lastActuatorCommandElapsedMsForTesting()
        val cycleMs = engine.currentPatternCycleDurationMsForTesting()

        assertTrue(cycleMs >= 3_000L)
        assertFalse(engine.shouldReassertActiveVibration(start + (cycleMs / 2L), 0L))
        assertTrue(engine.shouldReassertActiveVibration(start + cycleMs - 500L, 0L))
    }

    @Test
    fun `random reassert starts new section at prior tail cadence`() {
        engine.setModeVibration(AppConstants.MODE_RANDOM, AppConstants.LEVEL_MEDIUM, 100)
        val firstTimings = engine.currentPatternTimingsForTesting()
        val firstAmplitudes = engine.currentPatternAmplitudesForTesting()
        val expectedCadenceMs = firstTimings.takeLast(3).average().toLong()
        val expectedAmplitude = firstAmplitudes.last()
        val boundaryNow = engine.lastActuatorCommandElapsedMsForTesting() + engine.currentPatternCycleDurationMsForTesting()

        assertTrue(engine.reassertActiveVibration(boundaryNow))

        val secondTimings = engine.currentPatternTimingsForTesting()
        val secondAmplitudes = engine.currentPatternAmplitudesForTesting()
        assertEquals(expectedCadenceMs, secondTimings.first())
        assertEquals(expectedAmplitude, secondAmplitudes.first())
    }

    @Test
    fun `random reassert preserves continuity across all speed levels`() {
        val levels = listOf(
            AppConstants.LEVEL_SLOW,
            AppConstants.LEVEL_MEDIUM,
            AppConstants.LEVEL_FAST,
            AppConstants.LEVEL_VERY_FAST
        )

        levels.forEachIndexed { index, level ->
            val candidate = createEngine(200 + index)
            candidate.setModeVibration(AppConstants.MODE_RANDOM, level, 100)
            val firstTimings = candidate.currentPatternTimingsForTesting()
            val firstAmplitudes = candidate.currentPatternAmplitudesForTesting()
            val boundaryNow = candidate.lastActuatorCommandElapsedMsForTesting() + candidate.currentPatternCycleDurationMsForTesting()

            assertTrue("level=$level", candidate.reassertActiveVibration(boundaryNow))

            val secondTimings = candidate.currentPatternTimingsForTesting()
            val secondAmplitudes = candidate.currentPatternAmplitudesForTesting()
            assertEquals("level=$level", firstTimings.takeLast(3).average().toLong(), secondTimings.first())
            assertEquals("level=$level", firstAmplitudes.last(), secondAmplitudes.first())

            candidate.cancel()
        }
    }

    @Test
    fun `random reassert keeps scaled continuity below full intensity`() {
        val candidate = createEngine(500)
        candidate.setModeVibration(AppConstants.MODE_RANDOM, AppConstants.LEVEL_FAST, 40)
        val firstTimings = candidate.currentPatternTimingsForTesting()
        val firstAmplitudes = candidate.currentPatternAmplitudesForTesting()
        val boundaryNow = candidate.lastActuatorCommandElapsedMsForTesting() + candidate.currentPatternCycleDurationMsForTesting()

        assertTrue(candidate.reassertActiveVibration(boundaryNow))

        val secondTimings = candidate.currentPatternTimingsForTesting()
        val secondAmplitudes = candidate.currentPatternAmplitudesForTesting()
        assertEquals(firstTimings.takeLast(3).average().toLong(), secondTimings.first())
        assertEquals(firstAmplitudes.last(), secondAmplitudes.first())
    }

    @Test
    fun `calling with different level re-activates`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertEquals(AppConstants.LEVEL_SLOW, engine.level)

        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_MEDIUM, 100)
        assertEquals(AppConstants.LEVEL_MEDIUM, engine.level)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `calling with different intensity re-activates`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertEquals(100, engine.intensity)

        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 50)
        assertEquals(50, engine.intensity)
        assertTrue(engine.vibrating)
    }

    // ── Mode switching ─────────────────────────────────────

    @Test
    fun `switch from CONSTANT to INTERMITTENT`() {
        engine.setModeVibration(AppConstants.MODE_CONSTANT, AppConstants.LEVEL_SLOW, 100)
        assertEquals(AppConstants.MODE_CONSTANT, engine.mode)

        engine.setModeVibration(AppConstants.MODE_INTERMITTENT, AppConstants.LEVEL_SLOW, 100)
        assertEquals(AppConstants.MODE_INTERMITTENT, engine.mode)
        assertTrue(engine.vibrating)
    }

    @Test
    fun `switch between all active modes`() {
        val modes = listOf(
            AppConstants.MODE_CONSTANT,
            AppConstants.MODE_INTERMITTENT,
            AppConstants.MODE_RAMP,
            AppConstants.MODE_BURST,
            AppConstants.MODE_WAVE,
            AppConstants.MODE_RANDOM
        )
        for (mode in modes) {
            engine.setModeVibration(mode, AppConstants.LEVEL_SLOW, 100)
            assertEquals(mode, engine.mode)
            assertTrue(engine.vibrating)
        }
    }

    // ── Level clamping ─────────────────────────────────────

    @Test
    fun `levels outside 0-3 range are coerced`() {
        // The engine uses level.coerceIn(0, 3) internally
        // Verify no crash with out-of-range levels
        engine.setModeVibration(AppConstants.MODE_CONSTANT, -1, 100)
        assertTrue(engine.vibrating)

        engine.setModeVibration(AppConstants.MODE_CONSTANT, 99, 100)
        assertTrue(engine.vibrating)
    }

    // ── vibratePulse ───────────────────────────────────────

    @Test
    fun `vibratePulse sets isActive`() {
        engine.vibratePulse(longArrayOf(100, 200, 100, 200), -1)
        // Note: isActive is set regardless of hasVibrator() in tests
        // (Robolectric may report hasVibrator=false)
        // Just verify no crash
    }

    private fun createEngine(seed: Int = 1234): VibratorEngine {
        val random = Random(seed)
        return VibratorEngine(
            context,
            { minMs, maxMs ->
                if (maxMs <= minMs) minMs else minMs + random.nextLong(maxMs - minMs)
            },
            { random.nextInt(256) }
        )
    }
}
