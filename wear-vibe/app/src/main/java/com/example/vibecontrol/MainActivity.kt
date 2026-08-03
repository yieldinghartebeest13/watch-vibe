package com.example.vibecontrol

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Wear OS activity in kiosk/pinned mode:
 *  - All touch is consumed (no touch interaction)
 *  - Back gesture is blocked (only physical crown/button exits)
 *  - Displays current vibration status from the foreground service
 *  - Long-press the physical STEM button (KEYCODE_STEM_PRIMARY) to exit
 *
 * The VibrationForegroundService handles all Data Layer communication
 * and vibration control. This Activity is purely a status display.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "VibeAct"

        private val MODE_LABELS = mapOf(
            VibratorEngine.MODE_STOP to "Stopped",
            VibratorEngine.MODE_PAUSE to "Paused",
            VibratorEngine.MODE_CONSTANT to "Constant",
            VibratorEngine.MODE_INTERMITTENT to "Intermittent",
            VibratorEngine.MODE_RAMP to "Ramp",
            VibratorEngine.MODE_BURST to "Burst",
            VibratorEngine.MODE_WAVE to "Wave",
            VibratorEngine.MODE_RANDOM to "Random"
        )
        private val LEVEL_LABELS = arrayOf("Slow", "Medium", "Fast", "Very Fast")

        // Long-press threshold to exit kiosk mode
        private const val LONG_PRESS_MS = 2000L
    }

    private lateinit var modeText: TextView
    private lateinit var levelText: TextView

    private var stemPressStart: Long = 0
    private var exitRequested: Boolean = false
    private val statusReceiver = StatusReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "onCreate")

        setContentView(R.layout.activity_main)

        modeText = findViewById(R.id.modeText)
        levelText = findViewById(R.id.levelText)

        // ── Kiosk/pinned mode ──
        setupKioskMode()

        // ── Start (or keep alive) the foreground service ──
        startForegroundService()

        // ── Listen for status broadcasts from the service ──
        // Registered in onStart/onStop so updates work even after
        // activity is paused/resumed (e.g. ambient mode transitions).
        Log.e(TAG, "onCreate done — kiosk active, touch disabled")
    }

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, IntentFilter(VibrationForegroundService.BROADCAST_STATUS), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, IntentFilter(VibrationForegroundService.BROADCAST_STATUS))
        }
        // Request current status immediately so display is never stale
        val query = Intent(this, VibrationForegroundService::class.java).apply {
            action = VibrationForegroundService.ACTION_QUERY_STATUS
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(query)
        } else {
            startService(query)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    // ── Kiosk mode: disable all touch and gestures ─────────

    private fun setupKioskMode() {
        // Keep screen on while this activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // startLockTask() removed — requires device owner on Wear OS 5
        // Kiosk behavior is maintained via touch/key blocking below
        Log.e(TAG, "Kiosk mode active (touch/back blocked, no screen pinning)")
    }

    /**
     * Consume ALL touch events — nothing gets through.
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Swallow everything
        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return true
    }

    /**
     * Only the physical STEM (crown) button can exit.
     * Long-press (~2 seconds) triggers exit from kiosk mode.
     * Single press does nothing (consumed).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3 -> {
                if (stemPressStart == 0L) {
                    stemPressStart = System.currentTimeMillis()
                    Log.e(TAG, "Stem press started — hold ${LONG_PRESS_MS}ms to exit")
                }
                true // consumed
            }
            else -> true // block everything else (KEYCODE_BACK etc.)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3 -> {
                val duration = System.currentTimeMillis() - stemPressStart
                stemPressStart = 0
                if (duration >= LONG_PRESS_MS && !exitRequested) {
                    exitRequested = true
                    Log.e(TAG, "Exit requested via long stem press")
                    exitKioskAndFinish()
                } else {
                    Log.e(TAG, "Short press — ignored ($duration ms)")
                }
                true
            }
            else -> true
        }
    }

    override fun onBackPressed() {
        // Blocked — back gesture/swipe does nothing
        Log.e(TAG, "Back blocked")
    }

    private fun exitKioskAndFinish() {
        try {
            val intent = Intent(this, VibrationForegroundService::class.java).apply {
                action = VibrationForegroundService.ACTION_STOP_VIBRATION
            }
            startService(intent)
        } catch (_: Exception) {}
        finish()
    }

    // ── Foreground service ─────────────────────────────────

    private fun startForegroundService() {
        val intent = Intent(this, VibrationForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.e(TAG, "Foreground service start requested")
    }

    // ── Display update from service broadcasts ─────────────

    private fun updateDisplay(mode: Int, level: Int, active: Boolean, phoneConnected: Boolean) {
        // Mode text is the primary status — replaces old statusText
        modeText.text = when {
            !phoneConnected -> "Waiting..."
            active -> MODE_LABELS[mode] ?: "Unknown"
            else -> "Ready"
        }
        levelText.text = when {
            !active -> ""
            mode == VibratorEngine.MODE_CONSTANT -> ""
            else -> LEVEL_LABELS[level.coerceIn(0, 3)]
        }
    }

    // ── Broadcast receiver for service status ──────────────

    inner class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VibrationForegroundService.BROADCAST_STATUS) return
            val mode = intent.getIntExtra(VibrationForegroundService.EXTRA_MODE, VibratorEngine.MODE_PAUSE)
            val level = intent.getIntExtra(VibrationForegroundService.EXTRA_LEVEL, 0)
            val active = intent.getBooleanExtra(VibrationForegroundService.EXTRA_ACTIVE, false)
            val phoneConnected = intent.getBooleanExtra(VibrationForegroundService.EXTRA_PHONE_CONNECTED, false)
            updateDisplay(mode, level, active, phoneConnected)
        }
    }
}
