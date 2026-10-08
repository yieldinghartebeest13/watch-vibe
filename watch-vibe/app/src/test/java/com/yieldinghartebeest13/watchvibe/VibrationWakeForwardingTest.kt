package com.yieldinghartebeest13.watchvibe

import android.os.Build
import com.google.android.gms.wearable.MessageEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class VibrationWakeForwardingTest {
    private lateinit var service: VibrationDataLayerService

    @Before
    fun setUp() {
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
        VibrationDataLayerService.resetControlWakeDeduperForTesting()
        service = Robolectric.buildService(VibrationDataLayerService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
        VibrationDataLayerService.resetControlWakeDeduperForTesting()
    }

    @Test
    fun `new control message forwards session identity into wake intent`() {
        service.onMessageReceived(message("4,1,70,123,456"))
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(intent)
        assertEquals(4, intent.getIntExtra(VibrationDataLayerService.EXTRA_MODE, -1))
        assertEquals(123L, intent.getLongExtra(VibrationDataLayerService.EXTRA_TIMESTAMP, -1L))
        assertEquals(456L, intent.getLongExtra(VibrationDataLayerService.EXTRA_SESSION_ID, -1L))
    }

    @Test
    fun `legacy control wake still forwards without requiring a session ID`() {
        service.onMessageReceived(message("0,1,100,123"))
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(intent)
        assertEquals(0L, intent.getLongExtra(VibrationDataLayerService.EXTRA_SESSION_ID, -1L))
    }

    @Test
    fun `stop message never wakes watch UI`() {
        service.onMessageReceived(message("-2,0,0,123,456"))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    private fun message(body: String) = object : MessageEvent {
        override fun getPath() = AppConstants.PATH_CONTROL
        override fun getData() = body.toByteArray()
        override fun getSourceNodeId() = "phone"
        override fun getRequestId() = 1
    }
}
