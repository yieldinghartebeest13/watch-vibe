package com.yieldinghartebeest13.watchvibe

import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelHeartbeatTest {
    private val dispatcher = StandardTestDispatcher()
    private val heartbeat = PhoneHeartbeat()
    private lateinit var transport: WearDataLayer
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(WearDataLayer.Companion)
        transport = mockk(relaxed = true)
        every { transport.heartbeat } returns heartbeat
        every { WearDataLayer.getInstance(any()) } returns transport
    }

    @After
    fun tearDown() {
        store.clear()
        coVerify(timeout = 1000) { transport.sendControl(AppConstants.MODE_STOP, 0, 0) }
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(state: SavedStateHandle = SavedStateHandle()): MainViewModel {
        val vm = spyk(MainViewModel(RuntimeEnvironment.getApplication(), state))
        // Connection monitoring is orthogonal to the heartbeat ownership policy.
        every { vm.startConnectionMonitor() } just Runs
        every { vm.stopConnectionMonitor() } just Runs
        store.put("test", vm)
        return vm
    }

    @Test
    fun `idle pings follow foreground while active pings belong only to service`() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.NONE))
        vm.onForeground()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND))
        vm.modeConstant()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE))
        vm.onBackground()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE))
        vm.onForeground()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE))
        vm.modeStop()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND))
        vm.onBackground()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.NONE))
        runCurrent()
        coVerify { transport.sendControl(AppConstants.MODE_STOP, 0, 100) }
    }

    @Test
    fun `internal activity suppresses minimize but not idle background heartbeat cleanup`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onForeground()
        vm.suppressNextMinimize()
        vm.onBackground()
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.NONE))
        runCurrent()
        coVerify(exactly = 0) { transport.sendMinimize() }
    }

    @Test
    fun `cleanup enters final STOP synchronously before a new ViewModel can issue commands`() {
        viewModel()
        store.clear()
        coVerify(exactly = 1) { transport.sendControl(AppConstants.MODE_STOP, 0, 0) }
    }

    @Test
    fun `new active command cancels a pending idle minimize`() = runTest(dispatcher) {
        var minimizeCancelled = false
        coEvery { transport.sendMinimize() } coAnswers {
            try { awaitCancellation() } finally { minimizeCancelled = true }
        }
        val vm = viewModel()
        vm.onForeground()
        vm.onBackground()
        runCurrent()
        vm.modeConstant()
        runCurrent()
        assertTrue(minimizeCancelled)
        assertTrue(vm.isVibrating.value)
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE))
    }

    @Test
    fun `foreground service rejection stops rather than leaving an active UI`() = runTest(dispatcher) {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.startForegroundService(any(), any()) } throws SecurityException("test restriction")
        val vm = viewModel()
        try {
            vm.onForeground()
            vm.modeConstant()
            runCurrent()
            assertFalse(vm.isVibrating.value)
            assertEquals(AppConstants.MODE_STOP, vm.mode.value)
            assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.IDLE_FOREGROUND))
            coVerify(exactly = 0) { transport.sendControl(AppConstants.MODE_CONSTANT, any(), any()) }
            coVerify { transport.sendControl(AppConstants.MODE_STOP, 0, 100) }
        } finally {
            // Cancel the external ViewModel's recurring timer before runTest
            // drains virtual time; JUnit @After would run too late.
            vm.onBackground()
        }
    }

    @Test
    fun `saved mode level and intensity are validated and intensity is sent`() = runTest(dispatcher) {
        val vm = viewModel(SavedStateHandle(mapOf("saved_mode" to 999, "saved_level" to 99,
            "saved_intensity" to -10)))
        assertEquals(AppConstants.MODE_PAUSE, vm.mode.value)
        assertEquals(3, vm.level.value)
        assertEquals(0, vm.intensity.value)
        vm.modeWave()
        vm.setIntensity(35)
        runCurrent()
        coVerify { transport.sendControl(AppConstants.MODE_WAVE, 3, 35) }
    }

    @Test
    fun `restored active selection waits for authenticated foreground before starting`() = runTest(dispatcher) {
        val vm = viewModel(SavedStateHandle(mapOf("saved_mode" to AppConstants.MODE_CONSTANT)))
        assertFalse(vm.isVibrating.value)
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.NONE))
        runCurrent()
        coVerify(exactly = 0) { transport.sendControl(any(), any(), any()) }
        vm.onForeground()
        assertTrue(vm.isVibrating.value)
        assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.SERVICE))
        runCurrent()
        coVerify { transport.sendControl(AppConstants.MODE_CONSTANT, 0, 100) }
    }
}
