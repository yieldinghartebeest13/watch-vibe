package com.yieldinghartebeest13.watchvibe

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Regression coverage for the device-confirmed crash and session-stop failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SessionStopDiagnosticsTest {
    private lateinit var controller: ActivityController<MainActivityForegroundSafetyTest.TestMainActivity>
    private lateinit var activity: MainActivityForegroundSafetyTest.TestMainActivity
    private var commandTimestamp = 0L
    private val sessionId = 10L

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildActivity(MainActivityForegroundSafetyTest.TestMainActivity::class.java)
        activity = controller.create().start().resume().visible().get()
        activity.onWindowFocusChanged(true)
        commandTimestamp = activity.commandTimeMillis
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
    }

    @Test
    fun `brief focus loss stops actuator but resumes on fresh ping without exiting phone`() {
        startVibration()
        activity.onWindowFocusChanged(false)
        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, activity.crownExitSignals)
        activity.receivePingForTesting(1L, sessionId)
        assertFalse(activity.isVibratingForTesting()) // Hidden pings cannot renew.

        activity.onWindowFocusChanged(true)
        assertFalse(activity.isVibratingForTesting()) // Focus alone isn't a lease.
        activity.receivePingForTesting(2L, sessionId)
        assertTrue(activity.isVibratingForTesting())
        shadowOf(Looper.getMainLooper()).idleFor(2_100, TimeUnit.MILLISECONDS)
        assertEquals(0, activity.crownExitSignals)
    }

    @Test
    fun `sustained focus loss ends session once and cannot recover from pings`() {
        startVibration()
        activity.onWindowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idleFor(2_100, TimeUnit.MILLISECONDS)
        assertEquals(1, activity.crownExitSignals)
        activity.onWindowFocusChanged(true)
        activity.receivePingForTesting(1L, sessionId)
        assertFalse(activity.isVibratingForTesting())
        shadowOf(Looper.getMainLooper()).idleFor(2_100, TimeUnit.MILLISECONDS)
        assertEquals(1, activity.crownExitSignals)
    }

    @Test
    fun `resume without window focus does not cancel dismissal confirmation`() {
        startVibration()
        activity.onWindowFocusChanged(false)
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idleFor(2_100, TimeUnit.MILLISECONDS)
        assertFalse(activity.isVibratingForTesting())
        assertEquals(1, activity.crownExitSignals)
    }

    @Test
    fun `real heartbeat monitor timeout updates views on main and recovers desired mode`() {
        startVibration(AppConstants.MODE_WAVE)
        activity.elapsedTimeMillis += AppConstants.VIBRATION_LEASE_MS + 1L
        // Exercise the actual periodic coroutine, not a mocked timeout handler.
        shadowOf(Looper.getMainLooper()).idleFor(1_100, TimeUnit.MILLISECONDS)
        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, activity.nonMainDisplayUpdates)
        activity.receivePingForTesting(1L, sessionId)
        assertTrue(activity.isVibratingForTesting())
        assertEquals("Wave", activity.modeLabelText())
        assertEquals(0, activity.crownExitSignals)
    }

    @Test
    fun `brief outage late in a long run recovers independent of command age`() {
        startVibration()
        for (counter in 1L..60L) {
            activity.elapsedTimeMillis += 1_000L
            activity.receivePingForTesting(counter, sessionId)
        }
        activity.commandTimeMillis += 60_000L
        expireLease()
        activity.elapsedTimeMillis += 1_000L
        activity.receivePingForTesting(61L, sessionId)
        assertTrue(activity.isVibratingForTesting())
    }

    @Test
    fun `outage at recovery deadline requires a fresh command`() {
        startVibration()
        expireLease()
        activity.elapsedTimeMillis += AppConstants.COMMAND_TTL_MS
        activity.receivePingForTesting(1L, sessionId)
        assertFalse(activity.isVibratingForTesting())
        controller.newIntent(commandIntent(timestamp = commandTimestamp + 1L))
        assertTrue(activity.isVibratingForTesting())
    }

    @Test
    fun `explicit stop during outage prevents resume`() {
        startVibration()
        expireLease()
        controller.newIntent(commandIntent(AppConstants.MODE_STOP, commandTimestamp + 1L))
        activity.receivePingForTesting(1L, sessionId)
        assertFalse(activity.isVibratingForTesting())
    }

    @Test
    fun `new phone session cannot resume old mode or accept retired session commands`() {
        startVibration()
        expireLease()
        activity.receivePingForTesting(1L, 20L)
        assertFalse(activity.isVibratingForTesting())
        controller.newIntent(commandIntent(timestamp = commandTimestamp + 1L, sid = sessionId))
        assertFalse(activity.isVibratingForTesting())
        controller.newIntent(commandIntent(timestamp = commandTimestamp + 2L, sid = 20L))
        assertTrue(activity.isVibratingForTesting())
        activity.receivePingForTesting(100L, sessionId)
        assertTrue(activity.isVibratingForTesting())
    }

    @Test
    fun `notification stop blocks cached active command and recovery`() {
        startVibration()
        controller.newIntent(Intent(activity, activity.javaClass).apply {
            action = MainActivity.ACTION_STOP_FROM_NOTIFICATION
        })
        controller.newIntent(commandIntent())
        activity.receivePingForTesting(1L, sessionId)
        assertFalse(activity.isVibratingForTesting())
        assertEquals(1, activity.crownExitSignals)
    }

    @Test
    fun `recovery cannot start without the emergency notification surface`() {
        startVibration()
        expireLease()
        activity.notificationsEnabled = false
        activity.receivePingForTesting(1L, sessionId)
        assertFalse(activity.isVibratingForTesting())
        assertEquals(1, activity.crownExitSignals)
    }

    private fun startVibration(mode: Int = AppConstants.MODE_CONSTANT) {
        controller.newIntent(commandIntent(mode))
        assertTrue(activity.isVibratingForTesting())
    }

    private fun expireLease() {
        activity.elapsedTimeMillis += AppConstants.VIBRATION_LEASE_MS + 1L
        activity.checkConnectionLeaseForTesting()
        assertFalse(activity.isVibratingForTesting())
    }

    private fun commandIntent(
        mode: Int = AppConstants.MODE_CONSTANT,
        timestamp: Long = commandTimestamp,
        sid: Long = sessionId
    ) = Intent(activity, activity.javaClass).apply {
        putExtra(VibrationDataLayerService.EXTRA_MODE, mode)
        putExtra(VibrationDataLayerService.EXTRA_LEVEL, AppConstants.LEVEL_SLOW)
        putExtra(VibrationDataLayerService.EXTRA_INTENSITY, 100)
        putExtra(VibrationDataLayerService.EXTRA_TIMESTAMP, timestamp)
        putExtra(VibrationDataLayerService.EXTRA_SESSION_ID, sid)
    }
}
