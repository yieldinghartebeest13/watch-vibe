package com.yieldinghartebeest13.watchvibe

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VibrationCancellationTest {
    @Test
    fun `cancel submits valid silent nonrepeating waveforms to both pipelines`() {
        val engine = VibratorEngine(RuntimeEnvironment.getApplication())
        val vibrator = mockk<Vibrator>(relaxed = true)
        ReflectionHelpers.setField(engine, "vibrator", vibrator)
        val effects = mutableListOf<VibrationEffect>()
        every { vibrator.vibrate(capture(effects), any<VibrationAttributes>()) } just Runs
        every { vibrator.vibrate(capture(effects)) } just Runs
        engine.cancel()
        verify { vibrator.cancel() }
        assertEquals(2, effects.size)
        val silent = VibrationEffect.createWaveform(longArrayOf(1), intArrayOf(0), -1)
        // Compare public effect semantics, not SDK-specific internal segment fields.
        effects.forEach { assertEquals(silent, it) }
        assertFalse(engine.vibrating)
    }
}
