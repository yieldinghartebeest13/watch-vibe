package com.yieldinghartebeest13.watchvibe

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class StatsDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "watchvibe_stats.db"
        private const val DB_VERSION = 1

        // Sessions: runs < 15 min apart are merged into one session.
        const val MERGE_GAP_MS = 15 * 60_000L
        // Gaps <= 5 min count as part of session duration.
        const val SHORT_PAUSE_MS = 5 * 60_000L
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mode INTEGER NOT NULL,
                level INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                started_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(mode: Int, level: Int, durationMs: Long, startedAt: Long) {
        writableDatabase.execSQL(
            "INSERT INTO sessions (mode, level, duration_ms, started_at) VALUES (?,?,?,?)",
            arrayOf(mode, level, durationMs, startedAt)
        )
    }

    // ── Raw query (no merging) ─────────────────────────────

    data class SessionEntry(
        val id: Long,
        val mode: Int,
        val level: Int,
        val durationMs: Long,
        val startedAt: Long
    )

    fun recentSessions(limit: Int = 20): List<SessionEntry> {
        val list = mutableListOf<SessionEntry>()
        readableDatabase.rawQuery(
            "SELECT id, mode, level, duration_ms, started_at FROM sessions ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    SessionEntry(
                        id = cursor.getLong(0),
                        mode = cursor.getInt(1),
                        level = cursor.getInt(2),
                        durationMs = cursor.getLong(3),
                        startedAt = cursor.getLong(4)
                    )
                )
            }
        }
        return list
    }

    // ── Session-aware merging ──────────────────────────────

    data class MergedSession(
        val dominantMode: Int,
        val runCount: Int,
        val activeDurationMs: Long,      // sum of run durations only
        val totalDurationMs: Long,        // active + gaps <= SHORT_PAUSE_MS
        val startedAt: Long,
        val endedAt: Long
    )

    data class MergedStats(
        val sessionCount: Int,
        val totalDurationMs: Long,
        val modeBreakdown: List<ModeBreakdown>,
        val sessions: List<MergedSession>
    )

    data class ModeBreakdown(
        val mode: Int,
        val sessionCount: Int,
        val totalDurationMs: Long
    )

    /**
     * Query runs within [since] and merge them into sessions.
     * Consecutive runs whose start times are within [MERGE_GAP_MS] of
     * the previous run's end time form one session. Pauses <= [SHORT_PAUSE_MS]
     * are included in the session's total duration.
     */
    fun mergedQuery(since: Long): MergedStats {
        // Fetch raw runs, oldest first
        val runs = mutableListOf<SessionEntry>()
        readableDatabase.rawQuery(
            "SELECT id, mode, level, duration_ms, started_at FROM sessions WHERE started_at >= ? ORDER BY started_at ASC",
            arrayOf(since.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runs.add(
                    SessionEntry(
                        id = cursor.getLong(0),
                        mode = cursor.getInt(1),
                        level = cursor.getInt(2),
                        durationMs = cursor.getLong(3),
                        startedAt = cursor.getLong(4)
                    )
                )
            }
        }

        if (runs.isEmpty()) return MergedStats(0, 0, emptyList(), emptyList())

        val sessions = mutableListOf<MergedSession>()
        var currentRuns = mutableListOf<SessionEntry>()

        for (run in runs) {
            if (currentRuns.isEmpty()) {
                currentRuns.add(run)
                continue
            }
            val lastEnd = currentRuns.last().startedAt + currentRuns.last().durationMs
            if (run.startedAt - lastEnd <= MERGE_GAP_MS) {
                currentRuns.add(run)
            } else {
                sessions.add(buildSession(currentRuns))
                currentRuns = mutableListOf(run)
            }
        }
        if (currentRuns.isNotEmpty()) {
            sessions.add(buildSession(currentRuns))
        }

        // Mode breakdown: aggregate raw run durations by mode
        // (not dominant-per-session — that loses detail when modes switch
        // within a session)
        val modeMap = mutableMapOf<Int, ModeBreakdown>()
        for (run in runs) {
            val entry = modeMap.getOrPut(run.mode) {
                ModeBreakdown(run.mode, 0, 0)
            }
            modeMap[run.mode] = entry.copy(
                sessionCount = entry.sessionCount + 1,
                totalDurationMs = entry.totalDurationMs + run.durationMs
            )
        }

        val totalMs = sessions.sumOf { it.totalDurationMs }
        return MergedStats(
            sessionCount = sessions.size,
            totalDurationMs = totalMs,
            modeBreakdown = modeMap.values.toList().sortedByDescending { it.totalDurationMs },
            sessions = sessions.toList().reversed()  // newest first
        )
    }

    private fun buildSession(runs: List<SessionEntry>): MergedSession {
        var activeMs = runs.sumOf { it.durationMs }
        var totalMs = activeMs

        // Add gaps <= SHORT_PAUSE_MS
        for (i in 0 until runs.size - 1) {
            val gap = runs[i + 1].startedAt - (runs[i].startedAt + runs[i].durationMs)
            if (gap in 1..SHORT_PAUSE_MS) {
                totalMs += gap
            }
        }

        // Dominant mode = mode with most active time in this session
        val modeTime = runs.groupBy { it.mode }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } }
        val dominantMode = modeTime.maxByOrNull { it.value }?.key ?: runs.first().mode

        return MergedSession(
            dominantMode = dominantMode,
            runCount = runs.size,
            activeDurationMs = activeMs,
            totalDurationMs = totalMs,
            startedAt = runs.first().startedAt,
            endedAt = runs.last().startedAt + runs.last().durationMs
        )
    }
}
