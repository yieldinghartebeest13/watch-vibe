package com.yieldinghartebeest13.watchvibe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VibrationDataLayerServiceTest {

    @Before
    fun setUp() {
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
        VibrationDataLayerService.resetControlWakeDeduperForTesting()
    }

    @After
    fun tearDown() {
        MainActivity.setUiForegroundForActiveControlWakeForTesting(false)
        VibrationDataLayerService.resetControlWakeDeduperForTesting()
    }

    @Test
    fun `same timestamped control is deduped across duplicate wake delivery`() {
        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                nowElapsedMs = 1_000L
            )
        )

        assertFalse(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                nowElapsedMs = 1_500L
            )
        )
    }

    @Test
    fun `same timestamped control can launch again after duplicate window`() {
        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                nowElapsedMs = 1_000L
            )
        )

        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                nowElapsedMs = 3_100L
            )
        )
    }

    @Test
    fun `legacy untimestamped control is never deduped`() {
        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                0L,
                nowElapsedMs = 1_000L
            )
        )

        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                0L,
                nowElapsedMs = 1_500L
            )
        )
    }

    @Test
    fun `different timestamped control is not deduped`() {
        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                nowElapsedMs = 1_000L
            )
        )

        assertTrue(
            VibrationDataLayerService.shouldLaunchForControl(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1235L,
                nowElapsedMs = 1_500L
            )
        )
    }

    @Test
    fun `active control wake is skipped while shared activity flag says foreground`() {
        MainActivity.setUiForegroundForActiveControlWakeForTesting(true)

        assertEquals(
            VibrationDataLayerService.ActiveControlWakeDecision.SKIP_ACTIVITY_FOREGROUND,
            VibrationDataLayerService.decideActiveControlWake(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                nowElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun `active control wake still launches when activity is not foreground`() {
        assertEquals(
            VibrationDataLayerService.ActiveControlWakeDecision.LAUNCH,
            VibrationDataLayerService.decideActiveControlWake(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                activityUiForeground = false,
                nowElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun `foreground-skipped timestamped control is still deduped on later wake attempt`() {
        assertEquals(
            VibrationDataLayerService.ActiveControlWakeDecision.SKIP_ACTIVITY_FOREGROUND,
            VibrationDataLayerService.decideActiveControlWake(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                activityUiForeground = true,
                nowElapsedMs = 1_000L
            )
        )

        assertEquals(
            VibrationDataLayerService.ActiveControlWakeDecision.SKIP_DUPLICATE,
            VibrationDataLayerService.decideActiveControlWake(
                AppConstants.MODE_CONSTANT,
                AppConstants.LEVEL_MEDIUM,
                100,
                1234L,
                activityUiForeground = false,
                nowElapsedMs = 1_500L
            )
        )
    }
}
