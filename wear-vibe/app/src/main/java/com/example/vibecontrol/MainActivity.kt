package com.example.vibecontrol

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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
        private const val LONG_PRESS_MS = 2000L
    }

    private lateinit var modeText: TextView
    private lateinit var levelText: TextView

    private var stemPressStart: Long = 0
    private var exitRequested: Boolean = false
    private val statusReceiver = StatusReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        setContentView(R.layout.activity_main)

        modeText = findViewById(R.id.modeText)
        levelText = findViewById(R.id.levelText)

        setupKioskMode()
        startForegroundService()

        Log.d(TAG, "onCreate done — kiosk active, touch disabled")
    }

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver,
                IntentFilter(VibrationForegroundService.BROADCAST_STATUS),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver,
                IntentFilter(VibrationForegroundService.BROADCAST_STATUS))
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Kiosk mode active (touch/back blocked, no screen pinning)")
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true
    override fun onTouchEvent(event: MotionEvent?): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3 -> {
                if (stemPressStart == 0L) {
                    stemPressStart = System.currentTimeMillis()
                    Log.d(TAG, "Stem press started — hold ${LONG_PRESS_MS}ms to exit")
                }
                true
            }
            else -> true
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
                    Log.d(TAG, "Exit requested via long stem press")
                    exitKioskAndFinish()
                } else {
                    Log.d(TAG, "Short press — ignored ($duration ms)")
                }
                true
            }
            else -> true
        }
    }

    override fun onBackPressed() {
        Log.d(TAG, "Back blocked")
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
        Log.d(TAG, "Foreground service start requested")
    }

    // ── Display update from service broadcasts ─────────────

    private fun updateDisplay(mode: Int, level: Int, active: Boolean, phoneConnected: Boolean) {
        modeText.text = when {
            !phoneConnected -> "Waiting..."
            active -> AppConstants.MODE_LABELS[mode] ?: "Unknown"
            else -> "Ready"
        }
        levelText.text = when {
            !active -> ""
            mode == AppConstants.MODE_CONSTANT -> ""
            else -> AppConstants.SPEED_LABELS[level.coerceIn(0, 3)]
        }
    }

    // ── Broadcast receiver for service status ──────────────

    inner class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VibrationForegroundService.BROADCAST_STATUS) return
            val mode = intent.getIntExtra(VibrationForegroundService.EXTRA_MODE, AppConstants.MODE_PAUSE)
            val level = intent.getIntExtra(VibrationForegroundService.EXTRA_LEVEL, 0)
            val active = intent.getBooleanExtra(VibrationForegroundService.EXTRA_ACTIVE, false)
            val phoneConnected = intent.getBooleanExtra(VibrationForegroundService.EXTRA_PHONE_CONNECTED, false)
            updateDisplay(mode, level, active, phoneConnected)
        }
    }
}
