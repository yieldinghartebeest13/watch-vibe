package com.yieldinghartebeest13.watchvibe

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.View
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import io.mockk.*
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PinLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("stealth_prefs", Context.MODE_PRIVATE)
    private lateinit var transport: WearDataLayer

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        mockkObject(WearDataLayer.Companion)
        transport = mockk(relaxed = true)
        every { transport.heartbeat } returns PhoneHeartbeat()
        every { WearDataLayer.getInstance(any()) } returns transport
        mockkStatic(Wearable::class)
        val capability = mockk<CapabilityClient>(relaxed = true)
        every { capability.addListener(any<CapabilityClient.OnCapabilityChangedListener>(), any()) } returns Tasks.forResult(null)
        every { capability.removeListener(any()) } returns Tasks.forResult(null)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        unmockkAll()
    }

    private fun enablePin() {
        // Synthetic test PIN, never a user credential.
        val hash = MessageDigest.getInstance("SHA-256").digest("4321".toByteArray())
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putBoolean("stealth_enabled", true).putString("pin_hash", hash).commit()
    }

    @Test
    fun `calculator returns success only after a matching PIN`() {
        enablePin()
        val controller = Robolectric.buildActivity(LockActivity::class.java).create()
        val activity = controller.get()
        try {
            repeat(4) { activity.findViewById<View>(R.id.key1).performClick() }
            assertFalse(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            listOf(R.id.key4, R.id.key3, R.id.key2, R.id.key1).forEach {
                activity.findViewById<View>(it).performClick()
            }
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        } finally { controller.destroy() }
    }

    @Test
    fun `resumption and singleTask relaunch cannot authenticate or initialize control`() {
        enablePin()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.pause().newIntent(Intent(app, MainActivity::class.java)).resume()
            assertFalse(ReflectionHelpers.getField(controller.get(), "unlockedInSession"))
            assertNull(ReflectionHelpers.getField<MainViewModel?>(controller.get(), "viewModel"))
            verify(exactly = 0) { WearDataLayer.getInstance(any()) }
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun `cancelled lock result does not authenticate or initialize control`() {
        enablePin()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val request = shadowOf(controller.get()).nextStartedActivityForResult
            assertEquals(LockActivity::class.java.name, request.intent.component!!.className)
            controller.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
            assertFalse(ReflectionHelpers.getField(controller.get(), "unlockedInSession"))
            assertNull(ReflectionHelpers.getField<MainViewModel?>(controller.get(), "viewModel"))
            verify(exactly = 0) { WearDataLayer.getInstance(any()) }
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun `explicit successful lock result initializes authenticated control`() {
        enablePin()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val request = shadowOf(controller.get()).nextStartedActivityForResult
            controller.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
            assertTrue(ReflectionHelpers.getField(controller.get(), "unlockedInSession"))
            assertNotNull(ReflectionHelpers.getField<MainViewModel?>(controller.get(), "viewModel"))
            assertEquals(View.VISIBLE, controller.get().window.decorView.visibility)
            verify(exactly = 1) { WearDataLayer.getInstance(any()) }
        } finally {
            controller.pause().stop().destroy()
            coVerify(timeout = 2000) { transport.sendControl(AppConstants.MODE_STOP, 0, 0) }
        }
    }

    @Test
    fun `cancelled PIN creation leaves stealth preference and alias disabled`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val toggle = controller.get().findViewById<SwitchCompat>(R.id.stealthSwitch)
            toggle.isChecked = true
            assertFalse(toggle.isChecked)
            assertFalse(prefs.getBoolean("stealth_enabled", false))
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertNull(prefs.getString("pin_hash", null))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                app.packageManager.getComponentEnabledSetting(ComponentName(app, "${app.packageName}.MainActivityStealth")))
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun `PIN confirmation commits stealth immediately rather than on destruction`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            controller.get().findViewById<SwitchCompat>(R.id.stealthSwitch).isChecked = true
            shadowOf(Looper.getMainLooper()).idle()
            val first = ShadowAlertDialog.getLatestAlertDialog()
            (shadowOf(first).view as EditText).setText("4321")
            first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val second = ShadowAlertDialog.getLatestAlertDialog()
            (shadowOf(second).view as EditText).setText("4321")
            second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertNotNull(prefs.getString("pin_hash", null))
            assertTrue(prefs.getBoolean("stealth_enabled", false))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                app.packageManager.getComponentEnabledSetting(ComponentName(app, "${app.packageName}.MainActivityStealth")))
        } finally { controller.pause().stop().destroy() }
    }
}
