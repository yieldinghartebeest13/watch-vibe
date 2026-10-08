package com.yieldinghartebeest13.watchvibe

/** Records commanded mode/level segments without confusing wall time with duration. */
internal class SessionRecorder {
    data class Run(val mode: Int, val level: Int, val durationMs: Long, val startedAt: Long)
    private var mode: Int? = null
    private var level = 0
    private var startedAt = 0L
    private var startedElapsed = 0L

    fun transition(nextMode: Int, nextLevel: Int, wallTime: Long, elapsedTime: Long): Run? {
        val active = nextMode in AppConstants.MODE_CONSTANT..AppConstants.MODE_RANDOM
        if (active && mode == nextMode && level == nextLevel) return null
        val oldMode = mode
        val duration = (elapsedTime - startedElapsed).coerceAtLeast(0L)
        val completed = if (oldMode != null && duration >= 500L) {
            Run(oldMode, level, duration, startedAt)
        } else null
        mode = if (active) nextMode else null
        level = nextLevel
        startedAt = wallTime
        startedElapsed = elapsedTime
        return completed
    }
}
