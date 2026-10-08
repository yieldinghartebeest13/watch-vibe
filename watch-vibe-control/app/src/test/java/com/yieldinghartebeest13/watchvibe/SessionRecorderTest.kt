package com.yieldinghartebeest13.watchvibe

import org.junit.Assert.*
import org.junit.Test

class SessionRecorderTest {
    @Test
    fun `mode and speed changes close distinct segments using real start times`() {
        val recorder = SessionRecorder()
        assertNull(recorder.transition(AppConstants.MODE_CONSTANT, 0, 100_000, 1000))
        assertEquals(SessionRecorder.Run(AppConstants.MODE_CONSTANT, 0, 2000, 100_000),
            recorder.transition(AppConstants.MODE_WAVE, 0, 102_000, 3000))
        assertEquals(SessionRecorder.Run(AppConstants.MODE_WAVE, 0, 3000, 102_000),
            recorder.transition(AppConstants.MODE_WAVE, 2, 105_000, 6000))
        assertEquals(SessionRecorder.Run(AppConstants.MODE_WAVE, 2, 1000, 105_000),
            recorder.transition(AppConstants.MODE_STOP, 0, 106_000, 7000))
        assertNull(recorder.transition(AppConstants.MODE_STOP, 0, 107_000, 8000))
    }

    @Test
    fun `duplicate commands do not reset the start time`() {
        val recorder = SessionRecorder()
        recorder.transition(AppConstants.MODE_RAMP, 1, 100_000, 1000)
        assertNull(recorder.transition(AppConstants.MODE_RAMP, 1, 101_000, 2000))
        assertEquals(SessionRecorder.Run(AppConstants.MODE_RAMP, 1, 3000, 100_000),
            recorder.transition(AppConstants.MODE_PAUSE, 0, 103_000, 4000))
    }

    @Test
    fun `duration is monotonic even when wall clock moves backwards`() {
        val recorder = SessionRecorder()
        recorder.transition(AppConstants.MODE_BURST, 3, 100_000, 1000)
        assertEquals(SessionRecorder.Run(AppConstants.MODE_BURST, 3, 2000, 100_000),
            recorder.transition(AppConstants.MODE_STOP, 0, 90_000, 3000))
    }

    @Test
    fun `accidental short segments are ignored without contaminating the next run`() {
        val recorder = SessionRecorder()
        recorder.transition(AppConstants.MODE_CONSTANT, 0, 100_000, 1000)
        assertNull(recorder.transition(AppConstants.MODE_RANDOM, 2, 100_100, 1100))
        assertEquals(SessionRecorder.Run(AppConstants.MODE_RANDOM, 2, 500, 100_100),
            recorder.transition(AppConstants.MODE_STOP, 0, 100_600, 1600))
    }
}
