package com.yieldinghartebeest13.watchvibe

import androidx.lifecycle.ViewModelStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class StatsIsolationTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RuntimeEnvironment.getApplication().deleteDatabase("watchvibe_stats.db")
        mockkObject(WearDataLayer.Companion)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `closing statistics cannot create transport or stop a vibration session`() {
        val controller = Robolectric.buildActivity(StatsActivity::class.java).create()
        controller.get().finish()
        controller.destroy()
        verify(exactly = 0) { WearDataLayer.getInstance(any()) }
    }

    @Test
    fun `read-only snapshot loads history and refreshes without transport`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        StatsDb(app).use { it.insert(AppConstants.MODE_WAVE, 2, 10_000, now) }
        val vm = StatsViewModel(app)
        val store = ViewModelStore().also { it.put("stats", vm) }
        try {
            vm.refreshStats()
            val first = withTimeout(3000) { vm.stats.first { it.week.sessionCount == 1 } }
            assertEquals(10_000L, first.week.totalDurationMs)
            assertEquals(1, first.month.sessionCount)
            assertEquals(1, first.year.sessionCount)
            StatsDb(app).use { it.insert(AppConstants.MODE_BURST, 0, 2000, now + 2_000_000) }
            vm.refreshStats()
            withTimeout(3000) { vm.stats.first { it.week.sessionCount == 2 } }
            verify(exactly = 0) { WearDataLayer.getInstance(any()) }
        } finally {
            store.clear()
        }
    }
}
