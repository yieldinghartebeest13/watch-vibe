package com.yieldinghartebeest13.watchvibe

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One identity and monotonically increasing counter for the lifetime of the process. */
internal class PingSequence(val sessionId: Long = System.currentTimeMillis()) {
    private val counter = AtomicLong(0)
    private val commandTimestamp = AtomicLong(0)
    fun next(): Long = counter.incrementAndGet()
    fun nextCommandTimestamp(now: Long = System.currentTimeMillis()): Long =
        commandTimestamp.updateAndGet { previous -> maxOf(now, previous + 1L) }
}

/** Serializes ownership handoff, including cancellation of an in-flight ping. */
internal class PhoneHeartbeat {
    enum class Owner { NONE, IDLE_FOREGROUND, SERVICE }

    private var owner = Owner.NONE
    private var job: Job? = null
    private val sendMutex = Mutex()

    @Synchronized
    fun setOwner(next: Owner) {
        if (owner == next) return
        owner = next
        job?.cancel()
    }

    @Synchronized
    fun isOwner(expected: Owner): Boolean = owner == expected

    @Synchronized
    fun start(expected: Owner, scope: CoroutineScope, sendPing: suspend () -> Unit): Job? {
        if (owner != expected) return null
        if (job?.isActive == true) return job
        val previous = job
        val next = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            while (isActive && isOwner(expected)) {
                delay(AppConstants.HEARTBEAT_INTERVAL_MS)
                sendMutex.withLock {
                    if (isOwner(expected)) sendPing()
                }
            }
        }
        job = next
        next.start()
        return next
    }
}

/** Each channel has its own deadline; timeouts are local, parent cancellation is not. */
internal suspend fun sendIndependentChannels(
    timeoutMs: Long,
    onFailure: (String, Exception) -> Unit,
    sendData: suspend () -> Unit,
    sendMessage: suspend () -> Unit
) = coroutineScope {
    suspend fun send(name: String, block: suspend () -> Unit) {
        try {
            val completed = withTimeoutOrNull(timeoutMs) { block(); true } ?: false
            if (!completed) onFailure(name, java.io.IOException("$name deadline exceeded (${timeoutMs}ms)"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(name, e)
        }
    }
    launch { send("DataItem", sendData) }
    launch { send("Message", sendMessage) }
}

internal const val WAKE_LOCK_TIMEOUT_MS = 60_000L
internal const val WAKE_LOCK_RENEWAL_MS = 30_000L

/** Separate from network work so a slow send cannot consume the wake-lock lease. */
internal suspend fun maintainWakeLock(acquire: (Long) -> Unit) {
    while (currentCoroutineContext().isActive) {
        acquire(WAKE_LOCK_TIMEOUT_MS)
        delay(WAKE_LOCK_RENEWAL_MS)
    }
}
