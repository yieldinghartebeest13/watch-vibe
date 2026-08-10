package com.yieldinghartebeest13.watchvibe

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsDbTest {

    private lateinit var db: StatsDb
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("watchvibe_stats.db")
        db = StatsDb(context)
    }

    @Test
    fun `empty database returns zero stats`() {
        val stats = db.mergedQuery(now - 7 * 24 * 3600_000L)
        assertEquals(0, stats.sessionCount)
        assertEquals(0L, stats.totalDurationMs)
        assertTrue(stats.modeBreakdown.isEmpty())
        assertTrue(stats.sessions.isEmpty())
    }

    @Test
    fun `single run produces one session`() {
        db.insert(AppConstants.MODE_CONSTANT, 1, 30_000L, now)

        val stats = db.mergedQuery(now - 7 * 24 * 3600_000L)
        assertEquals(1, stats.sessionCount)
        assertEquals(30_000L, stats.totalDurationMs)
        assertEquals(1, stats.sessions.size)
        assertEquals(AppConstants.MODE_CONSTANT, stats.sessions[0].dominantMode)
        assertEquals(1, stats.sessions[0].runCount)
    }

    @Test
    fun `time window filter excludes old sessions`() {
        db.insert(AppConstants.MODE_RAMP, 0, 60_000L, now - 10 * 24 * 3600_000L)
        db.insert(AppConstants.MODE_RAMP, 0, 30_000L, now)

        val weekStats = db.mergedQuery(now - 7 * 24 * 3600_000L)
        assertEquals(1, weekStats.sessionCount)
        assertEquals(30_000L, weekStats.totalDurationMs)
    }

    @Test
    fun `merges runs within 15 minute gap into one session`() {
        val t1 = now - 300_000  // first run at T-5min
        val t2 = now             // second run at now (5min gap)
        db.insert(AppConstants.MODE_BURST, 0, 10_000L, t1)
        db.insert(AppConstants.MODE_BURST, 2, 20_000L, t2)

        val stats = db.mergedQuery(now - 30 * 24 * 3600_000L)
        assertEquals(1, stats.sessionCount)
        assertEquals(1, stats.sessions.size)
        assertEquals(2, stats.sessions[0].runCount)
        assertEquals(AppConstants.MODE_BURST, stats.sessions[0].dominantMode)
    }

    @Test
    fun `splits runs more than 15 minutes apart into separate sessions`() {
        val t1 = now - 20 * 60_000  // 20 minutes ago
        val t2 = now                  // now
        db.insert(AppConstants.MODE_WAVE, 0, 10_000L, t1)
        db.insert(AppConstants.MODE_WAVE, 3, 10_000L, t2)

        val stats = db.mergedQuery(now - 30 * 24 * 3600_000L)
        assertEquals(2, stats.sessionCount)
        assertEquals(2, stats.sessions.size)
        assertEquals(1, stats.sessions[0].runCount)
        assertEquals(1, stats.sessions[1].runCount)
    }

    @Test
    fun `includes short pauses in session duration`() {
        val t1 = now - 3 * 60_000   // run ends at T-3min + 10s
        val t2 = now                 // starts at now, gap = ~2m50s
        db.insert(AppConstants.MODE_CONSTANT, 0, 10_000L, t1)
        db.insert(AppConstants.MODE_CONSTANT, 0, 20_000L, t2)

        val stats = db.mergedQuery(now - 30 * 24 * 3600_000L)
        assertEquals(1, stats.sessionCount)
        // active: 10s + 20s = 30s. gap ~= 170s which is < 5min, so included.
        assertTrue(stats.totalDurationMs > 30_000L)
    }

    @Test
    fun `dominant mode is the one with most active time`() {
        val t1 = now - 60_000
        val t2 = now
        db.insert(AppConstants.MODE_CONSTANT, 0, 50_000L, t1)  // 50s of Constant
        db.insert(AppConstants.MODE_WAVE, 0, 10_000L, t2)      // 10s of Wave

        val stats = db.mergedQuery(now - 30 * 24 * 3600_000L)
        // Gap is < 1min so it's one session
        assertEquals(1, stats.sessionCount)
        assertEquals(AppConstants.MODE_CONSTANT, stats.sessions[0].dominantMode)
    }

    @Test
    fun `recent sessions returns in descending id order`() {
        db.insert(AppConstants.MODE_CONSTANT, 0, 10_000L, now - 2000)
        db.insert(AppConstants.MODE_BURST, 2, 20_000L, now - 1000)
        db.insert(AppConstants.MODE_WAVE, 3, 30_000L, now)

        val recent = db.recentSessions(10)
        assertEquals(3, recent.size)
        assertEquals(AppConstants.MODE_WAVE, recent[0].mode)
        assertEquals(AppConstants.MODE_BURST, recent[1].mode)
        assertEquals(AppConstants.MODE_CONSTANT, recent[2].mode)
    }
}
