package com.yieldinghartebeest13.watchvibe

import org.junit.Assert.*
import org.junit.Test

class WatchSessionStateTest {
    @Test
    fun `heartbeat after stop renews connection but not vibration`() {
        val state = WatchSessionState()
        state.acceptCommand(AppConstants.MODE_STOP, 0, 100, 42L, 1_000L)
        state.renewConnection(2_000L)
        assertTrue(state.isConnected(2_001L))
        assertFalse(state.hasVibrationLease(2_001L))
        assertNull(state.desiredCommand)
    }

    @Test
    fun `duplicate ping is ignored without resetting command timestamp`() {
        val state = activeState()
        assertTrue(state.acceptPing(5L))
        assertFalse(state.acceptPing(5L))
        assertFalse(state.acceptPing(4L))
        assertEquals(42L, state.lastCommandTimestamp)
        assertEquals(WatchSessionState.SessionChange.SAME, state.observeSession(10L))
        assertEquals(42L, state.lastCommandTimestamp)
    }

    @Test
    fun `first session identification preserves a just received legacy command`() {
        val state = WatchSessionState()
        state.acceptCommand(AppConstants.MODE_WAVE, 1, 70, 42L, 1_000L)
        assertEquals(WatchSessionState.SessionChange.FIRST, state.observeSession(10L))
        assertEquals(42L, state.lastCommandTimestamp)
        assertNotNull(state.desiredCommand)
    }

    @Test
    fun `retired session cannot switch state back or reset current timestamp`() {
        val state = activeState()
        assertEquals(WatchSessionState.SessionChange.NEW, state.observeSession(20L))
        state.acceptCommand(AppConstants.MODE_RAMP, 1, 70, 50L, 2_000L)
        assertEquals(WatchSessionState.SessionChange.RETIRED, state.observeSession(10L))
        assertEquals(20L, state.sessionId)
        assertEquals(50L, state.lastCommandTimestamp)
        assertEquals(AppConstants.MODE_RAMP, state.desiredCommand!!.mode)
    }

    @Test
    fun `timeout preserves desired mode and starts recovery age at lease boundary`() {
        val state = activeState()
        assertFalse(state.expireConnection(3_999L))
        assertTrue(state.expireConnection(4_500L))
        assertEquals(4_000L, state.interruptedAt)
        assertFalse(state.isConnected(4_500L))
        assertFalse(state.hasVibrationLease(4_500L))
        state.renewConnection(5_000L)
        assertEquals(AppConstants.MODE_WAVE, state.recoveryCommand(5_000L)!!.mode)
    }

    @Test
    fun `repeated loss cannot slide recovery deadline`() {
        val state = activeState()
        state.interrupt(2_000L)
        state.interrupt(20_000L)
        assertEquals(2_000L, state.interruptedAt)
        state.renewConnection(32_000L)
        assertNull(state.recoveryCommand(32_000L))
        assertNull(state.desiredCommand)
    }

    @Test
    fun `return strictly before deadline can recover and clear interruption`() {
        val state = activeState()
        state.interrupt(2_000L)
        state.renewConnection(31_999L)
        assertNotNull(state.recoveryCommand(31_999L))
        state.markResumed()
        assertNull(state.interruptedAt)
        assertNull(state.recoveryCommand(32_000L))
        assertNotNull(state.desiredCommand)
    }

    @Test
    fun `ending session preserves ordering watermark but erases recovery`() {
        val state = activeState()
        state.interrupt(2_000L)
        state.endSession()
        state.renewConnection(2_500L)
        assertEquals(42L, state.lastCommandTimestamp)
        assertNull(state.recoveryCommand(2_500L))
        assertFalse(state.hasVibrationLease(2_500L))
    }

    @Test
    fun `new process session clears old recovery and allows counter restart`() {
        val state = activeState()
        assertTrue(state.acceptPing(100L))
        state.interrupt(2_000L)
        assertEquals(WatchSessionState.SessionChange.NEW, state.observeSession(20L))
        assertTrue(state.acceptPing(1L))
        assertEquals(0L, state.lastCommandTimestamp)
        state.renewConnection(2_500L)
        assertNull(state.recoveryCommand(2_500L))
    }

    private fun activeState() = WatchSessionState().apply {
        observeSession(10L)
        acceptCommand(AppConstants.MODE_WAVE, 1, 70, 42L, 1_000L)
    }
}
