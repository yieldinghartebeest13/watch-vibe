package com.yieldinghartebeest13.watchvibe

/** Main-thread-owned session intent, independent of the actuator's stopped state.
 * Lease and recovery times are monotonic; only transport command freshness uses
 * the phone's wall-clock timestamp (checked by MainActivity).
 */
internal class WatchSessionState(
    private val leaseMs: Long = AppConstants.VIBRATION_LEASE_MS,
    private val recoveryMs: Long = AppConstants.COMMAND_TTL_MS
) {
    data class Command(val mode: Int, val level: Int, val intensity: Int)
    enum class SessionChange { SAME, FIRST, NEW, RETIRED }

    var sessionId: Long = 0L
        private set
    var lastCommandTimestamp: Long = 0L
        private set
    var connectionLeaseExpiry: Long = 0L
        private set
    var vibrationLeaseExpiry: Long = 0L
        private set
    var desiredCommand: Command? = null
        private set
    var interruptedAt: Long? = null
        private set

    private var lastPingCounter = -1L
    private val retiredSessionIds = mutableSetOf<Long>()

    fun isRetired(id: Long): Boolean = id > 0L && id in retiredSessionIds

    fun observeSession(id: Long): SessionChange {
        // Legacy commands omit the ID. The first identified heartbeat must not
        // erase a legacy command just received before it.
        if (id <= 0L || id == sessionId) return SessionChange.SAME
        if (isRetired(id)) return SessionChange.RETIRED
        val first = sessionId == 0L
        if (!first) {
            retiredSessionIds.add(sessionId)
            endSession()
            lastCommandTimestamp = 0L
        }
        sessionId = id
        lastPingCounter = -1L
        return if (first) SessionChange.FIRST else SessionChange.NEW
    }

    fun acceptPing(counter: Long): Boolean {
        if (counter <= lastPingCounter) return false
        lastPingCounter = counter
        return true
    }

    fun rememberTimestamp(timestamp: Long) {
        if (timestamp > lastCommandTimestamp) lastCommandTimestamp = timestamp
    }

    fun acceptCommand(mode: Int, level: Int, intensity: Int, timestamp: Long, now: Long) {
        rememberTimestamp(timestamp)
        desiredCommand = if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
            null
        } else {
            Command(mode, level, intensity)
        }
        interruptedAt = null
        connectionLeaseExpiry = now + leaseMs
        vibrationLeaseExpiry = if (desiredCommand != null) now + leaseMs else 0L
    }

    fun interrupt(now: Long) {
        if (desiredCommand != null && interruptedAt == null) interruptedAt = now
        connectionLeaseExpiry = 0L
        vibrationLeaseExpiry = 0L
    }

    fun expireConnection(now: Long): Boolean {
        if (connectionLeaseExpiry == 0L || now < connectionLeaseExpiry) return false
        // Use the lease boundary, not a possibly late monitor tick, as the
        // beginning of the recoverable interruption.
        interrupt(connectionLeaseExpiry)
        return true
    }

    fun renewConnection(now: Long) {
        val interrupted = interruptedAt
        if (interrupted != null && now - interrupted >= recoveryMs) {
            desiredCommand = null
            interruptedAt = null
        }
        connectionLeaseExpiry = now + leaseMs
        vibrationLeaseExpiry = if (desiredCommand != null) now + leaseMs else 0L
    }

    fun recoveryCommand(now: Long): Command? {
        val interrupted = interruptedAt ?: return null
        if (now - interrupted >= recoveryMs) return null
        if (vibrationLeaseExpiry <= now) return null
        return desiredCommand
    }

    fun markResumed() { interruptedAt = null }

    fun isConnected(now: Long): Boolean = connectionLeaseExpiry > now
    fun hasVibrationLease(now: Long): Boolean = vibrationLeaseExpiry > now

    fun endSession() {
        desiredCommand = null
        interruptedAt = null
        connectionLeaseExpiry = 0L
        vibrationLeaseExpiry = 0L
        // Keep the command watermark: a cached pre-STOP command must not restart
        // an ended session. Only a genuinely new phone session resets it.
    }
}
