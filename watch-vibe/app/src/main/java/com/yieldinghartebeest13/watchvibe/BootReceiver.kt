package com.yieldinghartebeest13.watchvibe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-starts the VibrationForegroundService after device boot.
 * Ensures the watch app is ready to receive commands without
 * manual launch after a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VibeBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — starting foreground service")

        val serviceIntent = Intent(context, VibrationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
