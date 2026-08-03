package com.example.vibecontrol

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Minimal wake-up service. Play Services can start this service to deliver
 * DataItems or Messages even when the app process isn't running.
 *
 * This service does NOT control vibration directly — it only starts the
 * VibrationForegroundService, which takes over all listeners and vibration
 * control. This avoids the duplicate-VibratorEngine race condition we had before.
 */
class VibrationDataLayerService : WearableListenerService() {

    companion object {
        private const val TAG = "VibeWake"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "Wake-up via DataItem — starting foreground service")
        startForegroundService()
        dataEvents.release()
    }

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "Wake-up via Message: ${event.path} — starting foreground service")
        startForegroundService()
    }

    private fun startForegroundService() {
        // Only start if not already running — avoid redundant starts
        // from every ping/control message after the initial wake-up
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == VibrationForegroundService::class.java.name }
        if (running) {
            Log.d(TAG, "Foreground service already running, skipping wake-up")
            return
        }
        Log.d(TAG, "Starting foreground service")
        val intent = Intent(this, VibrationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
