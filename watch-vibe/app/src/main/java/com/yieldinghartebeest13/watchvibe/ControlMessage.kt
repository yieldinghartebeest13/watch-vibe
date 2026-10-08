package com.yieldinghartebeest13.watchvibe

/** Shared decoder for live commands and wake-up forwarding. */
internal data class ControlMessage(
    val mode: Int,
    val level: Int,
    val intensity: Int = 100,
    val timestamp: Long = 0L,
    val sessionId: Long = 0L
) {
    companion object {
        fun decode(data: ByteArray): ControlMessage? {
            val fields = data.toString(Charsets.UTF_8).split(',')
            if (fields.size !in 2..5) return null
            val mode = fields[0].toIntOrNull() ?: return null
            if (mode !in AppConstants.MODE_CONSTANT..AppConstants.MODE_RANDOM &&
                mode != AppConstants.MODE_STOP && mode != AppConstants.MODE_PAUSE) return null
            val level = fields[1].toIntOrNull() ?: return null
            val intensity = fields.getOrNull(2)?.toIntOrNull() ?: 100
            val timestamp = if (fields.size >= 4) fields[3].toLongOrNull() ?: return null else 0L
            val sessionId = if (fields.size == 5) fields[4].toLongOrNull() ?: return null else 0L
            return ControlMessage(mode, level, intensity, timestamp, sessionId)
        }
    }
}
