package com.yieldinghartebeest13.watchvibe

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wake-up service. Play Services starts this to deliver data/messages
 * when the app process isn't running. Launches MainActivity directly —
 * no foreground service intermediary.
 *
 * For /control commands received while the app isn't running, the
 * mode/level/intensity/timestamp are extracted and passed as Intent
 * extras so MainActivity can apply the command immediately on startup
 * (prevents lost commands in the launch window).
 */
class VibrationDataLayerService : WearableListenerService() {

    companion object {
        private const val TAG = "VibeWake"

        // Intent extras for forwarding control commands
        const val EXTRA_MODE = "vibe.wake.mode"
        const val EXTRA_LEVEL = "vibe.wake.level"
        const val EXTRA_INTENSITY = "vibe.wake.intensity"
        const val EXTRA_TIMESTAMP = "vibe.wake.timestamp"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var shouldLaunch = false
        var launchMode = 0
        var launchLevel = 0
        var launchIntensity = 100
        var launchTs = 0L
        var hasControl = false

        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path
            when (path) {
                AppConstants.PATH_LAUNCH -> {
                    shouldLaunch = true
                }
                AppConstants.PATH_CONTROL -> {
                    shouldLaunch = true
                    hasControl = true
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    launchMode = map.getInt(AppConstants.KEY_MODE, AppConstants.MODE_PAUSE)
                    launchLevel = map.getInt(AppConstants.KEY_LEVEL, 0)
                    launchIntensity = map.getInt(AppConstants.KEY_INTENSITY, 100)
                    launchTs = map.getLong(AppConstants.KEY_TIMESTAMP, 0L)
                }
            }
        }
        dataEvents.release()

        if (shouldLaunch) {
            Log.d(TAG, "Wake-up via DataItem → launching MainActivity"
                + if (hasControl) " (mode=$launchMode level=$launchLevel)" else "")
            startMainActivity(if (hasControl) {
                Intent().apply {
                    putExtra(EXTRA_MODE, launchMode)
                    putExtra(EXTRA_LEVEL, launchLevel)
                    putExtra(EXTRA_INTENSITY, launchIntensity)
                    putExtra(EXTRA_TIMESTAMP, launchTs)
                }
            } else null)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            AppConstants.PATH_LAUNCH -> {
                Log.d(TAG, "Wake-up via /launch → launching MainActivity")
                startMainActivity(null)
            }
            AppConstants.PATH_CONTROL -> {
                val body = String(event.data)
                val parts = body.split(",")
                val mode: Int
                val level: Int
                val intensity: Int
                val ts: Long
                when (parts.size) {
                    2 -> { mode = parts[0].toIntOrNull() ?: return; level = parts[1].toIntOrNull() ?: return; intensity = 100; ts = 0L }
                    3 -> { mode = parts[0].toIntOrNull() ?: return; level = parts[1].toIntOrNull() ?: return; intensity = parts[2].toIntOrNull() ?: 100; ts = 0L }
                    4 -> { mode = parts[0].toIntOrNull() ?: return; level = parts[1].toIntOrNull() ?: return; intensity = parts[2].toIntOrNull() ?: 100; ts = parts[3].toLongOrNull() ?: 0L }
                    else -> return
                }
                Log.d(TAG, "Wake-up via /control message → launching MainActivity (mode=$mode level=$level)")
                startMainActivity(Intent().apply {
                    putExtra(EXTRA_MODE, mode)
                    putExtra(EXTRA_LEVEL, level)
                    putExtra(EXTRA_INTENSITY, intensity)
                    putExtra(EXTRA_TIMESTAMP, ts)
                })
            }
            else -> {
                Log.d(TAG, "Ignoring wake-up message: ${event.path}")
            }
        }
    }

    private fun startMainActivity(extras: Intent?) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (extras != null) {
                    putExtras(extras)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity: ${e.message}")
        }
    }
}
