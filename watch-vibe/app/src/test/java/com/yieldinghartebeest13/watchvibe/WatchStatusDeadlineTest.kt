package com.yieldinghartebeest13.watchvibe

import android.app.Activity
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class WatchStatusDeadlineTest {
    @Test
    fun `hung status transport does not accumulate activity coroutine jobs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Wearable::class)
        val nodes = mockk<NodeClient>()
        val pending = TaskCompletionSource<List<Node>>()
        every { nodes.connectedNodes } returns pending.task
        every { Wearable.getNodeClient(any<Activity>()) } returns nodes
        val controller = Robolectric.buildActivity(StatusDeadlineActivity::class.java)
        try {
            val activity = controller.create().start().resume().get()
            activity.onWindowFocusChanged(true)
            advanceTimeBy(120_000)
            runCurrent()
            val scope = ReflectionHelpers.getField<CoroutineScope>(activity, "activityScope")
            assertTrue(scope.coroutineContext[Job]!!.children.count { it.isActive } <= 3)
            verify(atLeast = 50) { nodes.connectedNodes }
            assertFalse(pending.task.isComplete)
        } finally {
            controller.pause().stop().destroy()
            runCurrent()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }
}

class StatusDeadlineActivity : MainActivity() {
    override fun startListeners() {}
    override fun stopListeners() {}
    override fun startBatteryMonitor() {}
    override fun stopBatteryMonitor() {}
}
