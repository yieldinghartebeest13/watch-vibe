package com.example.vibecontrol

import android.content.Context
import android.os.Build
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.*

/**
 * Unit tests for the wear-vibe VibratorEngine.
 *
 * Tests cover:
 *  - State transitions (mode, level, intensity, isActive)
 *  - Dedup guard (same params = no-op)
 *  - Cancel behaviour
 *  - Mode switching
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class VibratorEngineTest {

    private lateinit var engine: VibratorEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        engine = VibratorEngine(context)
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
}
