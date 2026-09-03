package com.yieldinghartebeest13.watchvibe

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.widget.TextView
import androidx.core.content.getSystemService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MainActivityForegroundSafetyTest {

    private lateinit var controller: ActivityController<TestMainActivity>
    private lateinit var activity: TestMainActivity
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildActivity(TestMainActivity::class.java)
        activity = controller.create().get()
        notificationManager = activity.getSystemService<NotificationManager>()!!
    }

    @After
    fun tearDown() {
        if (this::controller.isInitialized) {
            controller.pause().stop().destroy()
        }
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
    }

    @Test
    fun `active command waits for real foreground before vibrating`() {
        controller.start()
        controller.newIntent(activeCommandIntent())

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)

        activity.expectValidationRead()
        controller.resume().visible()
        activity.onWindowFocusChanged(true)
        assertTrue(activity.awaitValidationRead())
        assertTrue(waitForCondition {
            shadowOf(Looper.getMainLooper()).idle()
            activity.isVibratingForTesting() && notificationManager.activeNotifications.size == 1
        })
    }

    @Test
    fun `active control wake flag tracks true foreground lifecycle`() {
        assertFalse(MainActivity.isUiForegroundForActiveControlWake())

        controller.start().resume().visible()
        assertFalse(MainActivity.isUiForegroundForActiveControlWake())

        activity.onWindowFocusChanged(true)
        assertTrue(MainActivity.isUiForegroundForActiveControlWake())

        activity.onWindowFocusChanged(false)
        assertFalse(MainActivity.isUiForegroundForActiveControlWake())
    }

    @Test
    fun `losing foreground stops vibration clears notification and notifies phone`() {
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        assertTrue(activity.isVibratingForTesting())
        assertEquals(1, notificationManager.activeNotifications.size)

        activity.onWindowFocusChanged(false)

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.crownExitSignals)
    }

    @Test
    fun `notification stop action is an emergency exit`() {
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        val notification = notificationManager.activeNotifications.single().notification
        val stopAction = notification.actions.firstOrNull()
        assertNotNull(stopAction)

        val stopIntent = shadowOf(stopAction!!.actionIntent).savedIntent
        assertEquals(MainActivity.ACTION_STOP_FROM_NOTIFICATION, stopIntent.action)

        controller.newIntent(stopIntent)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.crownExitSignals)
    }

    @Test
    fun `missing notification permission blocks vibration until granted`() {
        activity.notificationPermissionGranted = false
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.notificationPermissionRequests)
        assertEquals(0, activity.notificationSettingsLaunches)

        activity.notificationPermissionGranted = true
        activity.expectValidationRead()
        activity.onRequestPermissionsResult(
            MainActivity.POST_NOTIFICATIONS_REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )
        assertTrue(activity.awaitValidationRead())
        assertTrue(waitForCondition {
            shadowOf(Looper.getMainLooper()).idle()
            activity.isVibratingForTesting() && notificationManager.activeNotifications.size == 1
        })
    }

    @Test
    fun `notification permission prompt loss does not exit and grant still starts vibration`() {
        activity.notificationPermissionGranted = false
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        simulateNotificationPermissionPromptTransientLoss()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.notificationPermissionRequests)
        assertEquals(0, activity.crownExitSignals)

        activity.notificationPermissionGranted = true
        activity.expectValidationRead()
        activity.onRequestPermissionsResult(
            MainActivity.POST_NOTIFICATIONS_REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)

        assertTrue(activity.awaitValidationRead())
        assertTrue(waitForCondition {
            shadowOf(Looper.getMainLooper()).idle()
            activity.isVibratingForTesting() && notificationManager.activeNotifications.size == 1
        })
        assertEquals(1, activity.notificationPermissionRequests)
        assertEquals(0, activity.crownExitSignals)
    }

    @Test
    fun `notification permission denial after prompt loss stays in app and shows access message`() {
        activity.notificationPermissionGranted = false
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        simulateNotificationPermissionPromptTransientLoss()

        activity.onRequestPermissionsResult(
            MainActivity.POST_NOTIFICATIONS_REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.notificationPermissionRequests)
        assertEquals(0, activity.notificationSettingsLaunches)
        assertEquals(0, activity.crownExitSignals)
        assertEquals(activity.getString(R.string.notification_access_required_title), activity.modeLabelText())
        assertEquals(activity.getString(R.string.notification_access_required_message), activity.levelLabelText())
    }

    @Test
    fun `notification permission denial blocks vibration and shows access message`() {
        activity.notificationPermissionGranted = false
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        activity.onRequestPermissionsResult(
            MainActivity.POST_NOTIFICATIONS_REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.notificationPermissionRequests)
        assertEquals(0, activity.notificationSettingsLaunches)
        assertEquals(0, activity.crownExitSignals)
        assertEquals(activity.getString(R.string.notification_access_required_title), activity.modeLabelText())
        assertEquals(activity.getString(R.string.notification_access_required_message), activity.levelLabelText())
    }

    @Test
    fun `disabled notifications block vibration and show access message`() {
        activity.notificationsEnabled = false
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(0, activity.notificationPermissionRequests)
        assertEquals(0, activity.notificationSettingsLaunches)
        assertEquals(0, activity.crownExitSignals)
        assertEquals(activity.getString(R.string.notification_access_required_title), activity.modeLabelText())
        assertEquals(activity.getString(R.string.notification_access_required_message), activity.levelLabelText())
    }

    @Test
    fun `granted permission still blocks vibration when notifications remain disabled`() {
        activity.notificationPermissionGranted = false
        activity.notificationsEnabled = false
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        activity.notificationPermissionGranted = true
        activity.expectValidationRead()
        activity.onRequestPermissionsResult(
            MainActivity.POST_NOTIFICATIONS_REQUEST_CODE,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )
        assertTrue(activity.awaitValidationRead())
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.notificationPermissionRequests)
        assertEquals(0, activity.notificationSettingsLaunches)
        assertEquals(0, activity.crownExitSignals)
        assertEquals(activity.getString(R.string.notification_access_required_title), activity.modeLabelText())
        assertEquals(activity.getString(R.string.notification_access_required_message), activity.levelLabelText())
    }

    @Test
    fun `deferred stale command is dropped before foreground execution`() {
        activity.commandTimeMillis = 1_000_000L
        controller.start()
        controller.newIntent(activeCommandIntent(timestamp = activity.commandTimeMillis))
        activity.commandTimeMillis += AppConstants.COMMAND_TTL_MS + 1L

        controller.resume().visible()
        activity.onWindowFocusChanged(true)
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
    }

    @Test
    fun `deferred command is cancelled when latest control snapshot is stop`() {
        controller.start()
        val commandTimestamp = System.currentTimeMillis()
        controller.newIntent(activeCommandIntent(timestamp = commandTimestamp))
        activity.setLatestControlCommandForValidation(
            mode = AppConstants.MODE_STOP,
            level = AppConstants.LEVEL_MEDIUM,
            intensity = 100,
            timestamp = commandTimestamp + 1L
        )

        activity.expectValidationRead()
        controller.resume().visible()
        activity.onWindowFocusChanged(true)
        assertTrue(activity.awaitValidationRead())
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(0, activity.crownExitSignals)
    }

    @Test
    fun `active notification stays low priority`() {
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        val notification = notificationManager.activeNotifications.single().notification
        assertEquals(NotificationManager.IMPORTANCE_LOW, notificationManager.getNotificationChannel(notification.channelId).importance)
        assertEquals(android.app.Notification.CATEGORY_SERVICE, notification.category)
        assertEquals(android.app.Notification.PRIORITY_LOW, notification.priority)
    }

    @Test
    fun `notification post failure triggers emergency stop`() {
        activity.failActiveNotificationPost = true
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.crownExitSignals)
        assertEquals(0, activity.notificationPermissionRequests)
        assertEquals(0, activity.notificationSettingsLaunches)
    }

    @Test
    fun `active lease reasserts vibration to recover likely silent stop`() {
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())

        assertTrue(activity.isVibratingForTesting())
        val staleBoundaryNow = activity.vibrationCommandElapsedMsForTesting() + 5_000L
        assertTrue(activity.recoverSilentStopForTesting(staleBoundaryNow))
        assertEquals(1, activity.reassertSignals)
        assertTrue(activity.isVibratingForTesting())
        assertEquals(1, notificationManager.activeNotifications.size)
    }

    @Test
    fun `pending silent stop recovery is cancelled when foreground is lost`() {
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent(AppConstants.MODE_INTERMITTENT, AppConstants.LEVEL_MEDIUM))

        shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS)
        val staleNow = activity.vibrationCommandElapsedMsForTesting() + 5_150L

        assertTrue(activity.recoverSilentStopForTesting(staleNow))
        assertEquals(1, activity.reassertSignals)
        assertTrue(activity.pendingReassertElapsedMsForTesting() > 0L)

        activity.onWindowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        assertFalse(activity.isVibratingForTesting())
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(1, activity.crownExitSignals)
    }

    @Test
    fun `silent stop recovery never reasserts after foreground loss`() {
        controller.start().resume().visible()
        activity.onWindowFocusChanged(true)
        controller.newIntent(activeCommandIntent())
        activity.onWindowFocusChanged(false)

        assertFalse(activity.recoverSilentStopForTesting(Long.MAX_VALUE))
        assertEquals(0, activity.reassertSignals)
        assertFalse(activity.isVibratingForTesting())
    }

    private fun simulateNotificationPermissionPromptTransientLoss() {
        activity.simulateUserLeaveHintForTesting()
        controller.pause().stop()
        activity.onWindowFocusChanged(false)
        Thread.sleep(2_100L)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitForCondition(timeoutMs: Long = 1_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun activeCommandIntent(
        mode: Int = AppConstants.MODE_CONSTANT,
        level: Int = AppConstants.LEVEL_MEDIUM,
        intensity: Int = 100,
        timestamp: Long = System.currentTimeMillis()
    ): Intent = Intent(activity, TestMainActivity::class.java).apply {
        putExtra(VibrationDataLayerService.EXTRA_MODE, mode)
        putExtra(VibrationDataLayerService.EXTRA_LEVEL, level)
        putExtra(VibrationDataLayerService.EXTRA_INTENSITY, intensity)
        putExtra(VibrationDataLayerService.EXTRA_TIMESTAMP, timestamp)
    }

    class TestMainActivity : MainActivity() {
        var crownExitSignals: Int = 0
        var reassertSignals: Int = 0
        var notificationPermissionGranted: Boolean = true
        var notificationsEnabled: Boolean = true
        var failActiveNotificationPost: Boolean = false
        var commandTimeMillis: Long = System.currentTimeMillis()
        var notificationPermissionRequests: Int = 0
        var notificationSettingsLaunches: Int = 0
        private var latestControlCommandForValidation: ControlCommandSnapshot? = null
        private var validationReadLatch = CountDownLatch(0)

        fun expectValidationRead() {
            validationReadLatch = CountDownLatch(1)
        }

        fun awaitValidationRead(): Boolean = validationReadLatch.await(1, TimeUnit.SECONDS)

        fun setLatestControlCommandForValidation(
            mode: Int,
            level: Int,
            intensity: Int,
            timestamp: Long
        ) {
            latestControlCommandForValidation = ControlCommandSnapshot(mode, level, intensity, timestamp)
        }

        fun modeLabelText(): String = findViewById<TextView>(R.id.modeText).text.toString()

        fun levelLabelText(): String = findViewById<TextView>(R.id.levelText).text.toString()

        fun simulateUserLeaveHintForTesting() = onUserLeaveHint()

        override fun startListeners() = Unit
        override fun stopListeners() = Unit
        override fun startBatteryMonitor() = Unit
        override fun stopBatteryMonitor() = Unit
        override fun sendAliveToPhone() = Unit
        override fun sendCrownExitToPhone() {
            crownExitSignals++
        }

        override fun reassertCurrentVibration(reason: String): Boolean {
            reassertSignals++
            return super.reassertCurrentVibration(reason)
        }

        override fun currentCommandTimeMillis(): Long = commandTimeMillis
        override fun hasNotificationPermission(): Boolean = notificationPermissionGranted
        override fun areNotificationsEnabledForEmergencySurface(): Boolean = notificationsEnabled
        override fun showActiveNotification(): Boolean =
            !failActiveNotificationPost && super.showActiveNotification()

        override suspend fun readLatestControlCommandForValidation(): ControlCommandSnapshot? {
            validationReadLatch.countDown()
            return latestControlCommandForValidation
        }

        override fun requestNotificationPermission() {
            notificationPermissionRequests++
        }

        override fun openNotificationSettings() {
            notificationSettingsLaunches++
        }
    }
}
