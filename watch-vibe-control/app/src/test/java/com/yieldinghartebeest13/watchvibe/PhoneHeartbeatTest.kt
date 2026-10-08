package com.yieldinghartebeest13.watchvibe

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneHeartbeatTest {
    @Test
    fun `concurrent callers share a strictly increasing counter and stable session`() {
        val sequence = PingSequence(1234)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val counts = (1..1000).map { executor.submit<Long> { sequence.next() } }.map { it.get() }
            assertEquals((1L..1000L).toSet(), counts.toSet())
            assertEquals(1234L, sequence.sessionId)
            assertEquals(1001L, sequence.next())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `control timestamps preserve ordering across equal and backward wall clocks`() {
        val sequence = PingSequence(1234)
        assertEquals(1000L, sequence.nextCommandTimestamp(1000L))
        assertEquals(1001L, sequence.nextCommandTimestamp(1000L))
        assertEquals(1002L, sequence.nextCommandTimestamp(900L))
        assertEquals(2000L, sequence.nextCommandTimestamp(2000L))
    }

    @Test
    fun `service is sole active owner and idle foreground resumes only after handoff`() = runTest {
        val heartbeat = PhoneHeartbeat()
        var idlePings = 0
        var servicePings = 0
        heartbeat.setOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND)
        val idle = heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) { idlePings++ }
        assertSame(idle, heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) { idlePings++ })
        advanceTimeBy(AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(1, idlePings)

        heartbeat.setOwner(PhoneHeartbeat.Owner.SERVICE)
        assertNull(heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) { idlePings++ })
        val service = heartbeat.start(PhoneHeartbeat.Owner.SERVICE, backgroundScope) { servicePings++ }
        assertSame(service, heartbeat.start(PhoneHeartbeat.Owner.SERVICE, backgroundScope) { servicePings++ })
        advanceTimeBy(AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(1, idlePings)
        assertEquals(1, servicePings)

        heartbeat.setOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND)
        heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) { idlePings++ }
        advanceTimeBy(AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(2, idlePings)
        assertEquals(1, servicePings)
        heartbeat.setOwner(PhoneHeartbeat.Owner.NONE)
        advanceTimeBy(10 * AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(2, idlePings)
        assertNull(heartbeat.start(PhoneHeartbeat.Owner.SERVICE, backgroundScope) { servicePings++ })
    }

    @Test
    fun `handoff waits for cancellation of an in-flight heartbeat`() = runTest {
        val heartbeat = PhoneHeartbeat()
        val cleanup = CompletableDeferred<Unit>()
        var servicePings = 0
        heartbeat.setOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND)
        heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        advanceTimeBy(AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        heartbeat.setOwner(PhoneHeartbeat.Owner.SERVICE)
        heartbeat.start(PhoneHeartbeat.Owner.SERVICE, backgroundScope) { servicePings++ }
        advanceTimeBy(10 * AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(0, servicePings)
        cleanup.complete(Unit)
        runCurrent()
        advanceTimeBy(AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(1, servicePings)
        heartbeat.setOwner(PhoneHeartbeat.Owner.NONE)
    }

    @Test
    fun `rapid ownership changes cannot bypass cancellation cleanup`() = runTest {
        val heartbeat = PhoneHeartbeat()
        val cleanup = CompletableDeferred<Unit>()
        var newPings = 0
        heartbeat.setOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND)
        heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        advanceTimeBy(AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        heartbeat.setOwner(PhoneHeartbeat.Owner.SERVICE)
        heartbeat.start(PhoneHeartbeat.Owner.SERVICE, backgroundScope) { newPings++ }
        runCurrent()
        heartbeat.setOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND)
        heartbeat.start(PhoneHeartbeat.Owner.IDLE_FOREGROUND, backgroundScope) { newPings++ }
        advanceTimeBy(10 * AppConstants.HEARTBEAT_INTERVAL_MS)
        runCurrent()
        assertEquals(0, newPings)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, newPings)
        heartbeat.setOwner(PhoneHeartbeat.Owner.NONE)
    }

    @Test
    fun `channel failure does not cancel the other send`() = runTest {
        var messageSent = false
        val failures = mutableListOf<String>()
        sendIndependentChannels(1000, { channel, _ -> failures += channel },
            sendData = { throw IllegalStateException("offline") },
            sendMessage = { messageSent = true })
        assertTrue(messageSent)
        assertEquals(listOf("DataItem"), failures)
    }

    @Test
    fun `hanging data cannot delay message and each channel times out independently`() = runTest {
        var messageSent = false
        var dataCancelled = false
        var failures = 0
        val send = launch {
            sendIndependentChannels(1000, { _, _ -> failures++ },
                sendData = {
                    try { awaitCancellation() } finally { dataCancelled = true }
                },
                sendMessage = { messageSent = true }
            )
        }
        runCurrent()
        assertTrue(messageSent)
        assertTrue(send.isActive)
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(send.isCompleted)
        assertTrue(dataCancelled)
        assertEquals(1, failures)

        var dataSent = false
        val reverse = launch {
            sendIndependentChannels(1000, { _, _ -> failures++ },
                sendData = { dataSent = true }, sendMessage = { awaitCancellation() })
        }
        runCurrent()
        assertTrue(dataSent)
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(reverse.isCompleted)
        assertEquals(2, failures)
    }

    @Test
    fun `parent cancellation cancels both channels without being reported as failure`() = runTest {
        var cancelled = 0
        var returned = false
        var failures = 0
        val send = launch {
            sendIndependentChannels(1000, { _, _ -> failures++ },
                sendData = { try { awaitCancellation() } finally { cancelled++ } },
                sendMessage = { try { awaitCancellation() } finally { cancelled++ } })
            returned = true
        }
        runCurrent()
        send.cancel(CancellationException("STOP"))
        runCurrent()
        assertTrue(send.isCancelled)
        assertEquals(2, cancelled)
        assertFalse(returned)
        assertEquals(0, failures)
    }

    @Test
    fun `bounded wake lock renews beyond an hour independently of hung network`() = runTest {
        val acquisitions = mutableListOf<Long>()
        var timeouts = true
        val maintenance = backgroundScope.launch {
            maintainWakeLock { timeout ->
                acquisitions += testScheduler.currentTime
                timeouts = timeouts && timeout == WAKE_LOCK_TIMEOUT_MS
            }
        }
        val network = backgroundScope.launch { awaitCancellation() }
        runCurrent()
        advanceTimeBy(3_660_000)
        runCurrent()
        assertTrue(timeouts)
        assertEquals(0L, acquisitions.first())
        assertEquals(3_660_000L, acquisitions.last())
        assertTrue(acquisitions.zipWithNext().all { (a, b) -> b - a < WAKE_LOCK_TIMEOUT_MS })
        maintenance.cancel()
        network.cancel()
        val count = acquisitions.size
        advanceTimeBy(2 * WAKE_LOCK_TIMEOUT_MS)
        runCurrent()
        assertEquals(count, acquisitions.size)
    }
}
