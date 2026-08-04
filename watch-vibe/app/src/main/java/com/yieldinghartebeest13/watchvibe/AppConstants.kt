package com.yieldinghartebeest13.watchvibe

/**
 * Shared constants across the vibe-control (phone) and wear-vibe (watch) projects.
 * This file must remain IDENTICAL in both projects to prevent value drift.
 */
object AppConstants {
    // ── Vibration modes ────────────────────────────────────
    const val MODE_STOP = -2
    const val MODE_PAUSE = -3
    const val MODE_CONSTANT = 0
    const val MODE_INTERMITTENT = 1
    const val MODE_RAMP = 2
    const val MODE_BURST = 3
    const val MODE_WAVE = 4
    const val MODE_RANDOM = 5

    // ── Speed levels ───────────────────────────────────────
    const val LEVEL_SLOW = 0
    const val LEVEL_MEDIUM = 1
    const val LEVEL_FAST = 2
    const val LEVEL_VERY_FAST = 3

    // ── Wear Data Layer paths ──────────────────────────────
    const val PATH_CONTROL = "/control"
    const val PATH_PING = "/ping"
    const val PATH_LAUNCH = "/launch"

    // ── Wear Data Layer keys ───────────────────────────────
    const val KEY_MODE = "wear_mode"
    const val KEY_LEVEL = "wear_level"
    const val KEY_INTENSITY = "wear_intensity"

    // ── Capability ─────────────────────────────────────────
    const val CAPABILITY_VIBRATION = "vibration_control"

    // ── Heartbeat ──────────────────────────────────────────
    const val HEARTBEAT_INTERVAL_MS = 1_000L
    const val HEARTBEAT_TIMEOUT_MS = 2_000L

    // ── Safety lease ───────────────────────────────────────
    // Vibration runs on a renewable lease: each ping extends
    // the lease by this amount. If pings stop, vibration
    // cancels itself automatically — no explicit cancel needed.
    const val VIBRATION_LEASE_MS = 3_000L

    // ── Command expiry ─────────────────────────────────────
    // Commands older than this are discarded on arrival.
    // Prevents rapid mode-cycling on reconnect when the user
    // mashed buttons while disconnected.
    const val COMMAND_TTL_MS = 30_000L
    const val KEY_TIMESTAMP = "ts"

    // ── Labels ─────────────────────────────────────────────
    val SPEED_LABELS = arrayOf("Slow", "Medium", "Fast", "Very Fast")

    val MODE_LABELS = mapOf(
        MODE_STOP to "Stopped",
        MODE_PAUSE to "Paused",
        MODE_CONSTANT to "Constant",
        MODE_INTERMITTENT to "Intermittent",
        MODE_RAMP to "Ramp",
        MODE_BURST to "Burst",
        MODE_WAVE to "Wave",
        MODE_RANDOM to "Random"
    )
}
