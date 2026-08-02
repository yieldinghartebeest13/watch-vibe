package com.example.vibecontrol

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Local vibration engine for the phone.
 * Same vibration patterns as the original Happy Rumble app.
 */
class VibratorEngine(context: Context) {

    companion object {
        const val MODE_CONSTANT = 0
        const val MODE_INTERMITTENT = 1
        const val MODE_STOP = -2
        const val MODE_PAUSE = -3

        const val LEVEL_SLOW = 0
        const val LEVEL_MEDIUM = 1
        const val LEVEL_FAST = 2
        const val LEVEL_VERY_FAST = 3
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var currentMode: Int = MODE_PAUSE
    private var currentLevel: Int = LEVEL_SLOW

    val mode: Int get() = currentMode
    val level: Int get() = currentLevel

    fun hasVibrator(): Boolean = vibrator.hasVibrator()

    fun cancel() {
        vibrator.cancel()
    }

    fun setModeVibration(mode: Int, level: Int) {
        currentMode = mode
        currentLevel = level

        when (mode) {
            MODE_STOP, MODE_PAUSE -> {
                vibrator.cancel()
                return
            }
            MODE_CONSTANT -> {
                // [1ms on, 5000ms off] — effectively continuous
                vibrate(longArrayOf(1, 5000))
            }
            MODE_INTERMITTENT -> {
                val pattern = when (level) {
                    LEVEL_SLOW -> longArrayOf(500, 500)
                    LEVEL_MEDIUM -> longArrayOf(250, 250, 250, 250)
                    LEVEL_FAST -> longArrayOf(125, 125, 125, 125)
                    LEVEL_VERY_FAST -> longArrayOf(75, 75, 75, 75)
                    else -> longArrayOf(500, 500)
                }
                vibrate(pattern)
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }
}
