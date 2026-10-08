package com.yieldinghartebeest13.watchvibe

import android.content.Context
import io.mockk.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityCleanupTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.getApplication().getSharedPreferences("stealth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        unmockkAll()
    }

    private fun activityWith(viewModel: MainViewModel) = run {
        // Skip UI setup (and its real ViewModel/network observers). Exercise
        // the actual onDestroy lifecycle with an injected ViewModel instead.
        RuntimeEnvironment.getApplication().getSharedPreferences("stealth_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("stealth_enabled", true).putString("pin_hash", "test").commit()
        Robolectric.buildActivity(MainActivity::class.java).create().also {
            ReflectionHelpers.setField(it.get(), "viewModel", viewModel)
        }
    }

    @Test
    fun `configuration destruction does not stop vibration or monitors`() {
        val viewModel = mockk<MainViewModel>(relaxed = true)
        val activity = activityWith(viewModel)
        ReflectionHelpers.setField(activity.get(), "mChangingConfigurations", true)
        activity.destroy()
        verify(exactly = 0) { viewModel.modeStop() }
        verify(exactly = 0) { viewModel.stopHeartbeat() }
        verify(exactly = 0) { viewModel.stopConnectionMonitor() }
    }

    @Test
    fun `explicit finish still sends STOP and cleans up`() {
        val viewModel = mockk<MainViewModel>(relaxed = true)
        val activity = activityWith(viewModel)
        activity.get().finish()
        activity.destroy()
        verify(exactly = 1) { viewModel.modeStop() }
        verify(exactly = 1) { viewModel.stopHeartbeat() }
        verify(exactly = 1) { viewModel.stopConnectionMonitor() }
    }
}
