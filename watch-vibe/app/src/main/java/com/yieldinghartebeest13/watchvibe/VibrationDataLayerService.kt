package com.yieldinghartebeest13.watchvibe

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wake-up service. Play Services starts this to deliver data/messages
 * when the app process isn't running. Launches MainActivity directly —
 * no foreground service intermediary.
 *
 * Only launches for /launch (explicit open) and /control (vibration
 * command) paths. Pings are ignored here — they are handled by
 * MainActivity's own listeners once the activity is visible.
 */
class VibrationDataLayerService : WearableListenerService() {

    companion object {
        private const val TAG = "VibeWake"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var shouldLaunch = false
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == AppConstants.PATH_LAUNCH || path == AppConstants.PATH_CONTROL) {
                    shouldLaunch = true
                    break
                }
            }
        }
        dataEvents.release()
        if (shouldLaunch) {
            Log.d(TAG, "Wake-up via DataItem → launching MainActivity")
            startMainActivity()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            AppConstants.PATH_LAUNCH -> {
                Log.d(TAG, "Wake-up via /launch message → launching MainActivity")
                startMainActivity()
            }
            else -> {
                Log.d(TAG, "Ignoring wake-up message: ${event.path}")
            }
        }
    }

    private fun startMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity: ${e.message}")
        }
    }
}
