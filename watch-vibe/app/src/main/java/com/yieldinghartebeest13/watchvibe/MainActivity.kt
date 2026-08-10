package com.yieldinghartebeest13.watchvibe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val TAG = "VibeAct"
    }

    private lateinit var modeText: TextView
    private lateinit var levelText: TextView
    private var exitRequested: Boolean = false
    private val statusReceiver = StatusReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        setTurnScreenOn(true)
        setContentView(R.layout.activity_main)

        modeText = findViewById(R.id.modeText)
        levelText = findViewById(R.id.levelText)

        setupKioskMode()
        startForegroundService()
        Log.d(TAG, "onCreate done")
    }

    override fun onStart() {
        super.onStart()
        exitRequested = false
        val filter = IntentFilter().apply {
            addAction(VibrationForegroundService.BROADCAST_STATUS)
            addAction(VibrationForegroundService.ACTION_MINIMIZE)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
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
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!exitRequested) {
            exitRequested = true
            Log.d(TAG, "User dismissed — emergency stop")
            try {
                val intent = Intent(this, VibrationForegroundService::class.java).apply {
                    action = VibrationForegroundService.ACTION_EMERGENCY_STOP
                }
                startService(intent)
            } catch (_: Exception) {}
        }
    }

    private fun setupKioskMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
    override fun onBackPressed() { Log.d(TAG, "Back blocked") }

    private fun startForegroundService() {
        val intent = Intent(this, VibrationForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

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

    inner class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VibrationForegroundService.BROADCAST_STATUS -> {
                    val mode = intent.getIntExtra(VibrationForegroundService.EXTRA_MODE, AppConstants.MODE_PAUSE)
                    val level = intent.getIntExtra(VibrationForegroundService.EXTRA_LEVEL, 0)
                    val active = intent.getBooleanExtra(VibrationForegroundService.EXTRA_ACTIVE, false)
                    val phoneConnected = intent.getBooleanExtra(VibrationForegroundService.EXTRA_PHONE_CONNECTED, false)
                    updateDisplay(mode, level, active, phoneConnected)
                }
                VibrationForegroundService.ACTION_MINIMIZE -> {
                    Log.d(TAG, "Minimize")
                    moveTaskToBack(true)
                }
            }
        }
    }
}
