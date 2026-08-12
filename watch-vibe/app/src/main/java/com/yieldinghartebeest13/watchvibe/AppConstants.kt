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
    const val PATH_MINIMIZE = "/minimize"
    const val PATH_CROWN_EXIT = "/crown_exit"
    const val PATH_BATTERY = "/battery"
    const val PATH_BATTERY_REQUEST = "/battery_request"
    const val PATH_ALIVE = "/alive"

    // ── Wear Data Layer keys ───────────────────────────────
    const val KEY_MODE = "wear_mode"
    const val KEY_LEVEL = "wear_level"
    const val KEY_INTENSITY = "wear_intensity"
    const val KEY_BATTERY_LEVEL = "battery_level"

    // ── Capability ─────────────────────────────────────────
    const val CAPABILITY_VIBRATION = "vibration_control"

    // ── Heartbeat ──────────────────────────────────────────
    const val HEARTBEAT_INTERVAL_MS = 1_000L
    const val HEARTBEAT_TIMEOUT_MS = 2_000L

    // ── Reversed heartbeat (watch → phone) ───────────────
    // Watch sends /alive every ALIVE_INTERVAL_MS. Phone shows
    // disconnected if no /alive received within ALIVE_TIMEOUT_MS.
    const val ALIVE_INTERVAL_MS = 2_000L
    const val ALIVE_TIMEOUT_MS = 5_000L

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
