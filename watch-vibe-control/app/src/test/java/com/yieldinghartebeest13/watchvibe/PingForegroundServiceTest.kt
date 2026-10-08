package com.yieldinghartebeest13.watchvibe

import android.app.Notification
import android.app.Service
import android.content.Intent
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PingForegroundServiceTest {
    private val heartbeat = PhoneHeartbeat()
    private lateinit var transport: WearDataLayer

    @Before
    fun setUp() {
        mockkObject(WearDataLayer.Companion)
        transport = mockk(relaxed = true)
        every { transport.heartbeat } returns heartbeat
        every { WearDataLayer.getInstance(any()) } returns transport
    }

    @After
    fun tearDown() { unmockkAll() }

    @Test
    fun `delayed service start after STOP cannot promote or restart heartbeat`() {
        val controller = Robolectric.buildService(RejectedPromotionService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(Intent(), 0, 1))
            assertEquals(0, controller.get().promotions)
            assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.NONE))
            coVerify(exactly = 0) { transport.sendPing() }
        } finally { controller.destroy() }
    }

    @Test
    fun `failed promotion ends ownership reports failure and sends bounded STOP`() {
        var failures = 0
        every { transport.onHeartbeatFailure } returns { failures++ }
        heartbeat.setOwner(PhoneHeartbeat.Owner.SERVICE)
        val controller = Robolectric.buildService(RejectedPromotionService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(Intent(), 0, 1))
            assertEquals(1, failures)
            assertEquals(1, controller.get().promotions)
            assertTrue(heartbeat.isOwner(PhoneHeartbeat.Owner.NONE))
            coVerify(timeout = 2000) { transport.sendControl(AppConstants.MODE_STOP, 0, 0) }
            coVerify(exactly = 0) { transport.sendPing() }
        } finally { controller.destroy() }
    }
}

class RejectedPromotionService : PingForegroundService() {
    var promotions = 0
    override fun startForegroundCompat(id: Int, notification: Notification) {
        promotions++
        throw SecurityException("test promotion rejection")
    }
}
