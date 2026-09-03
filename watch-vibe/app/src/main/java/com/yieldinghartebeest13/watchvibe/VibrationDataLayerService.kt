package com.yieldinghartebeest13.watchvibe

import android.content.Intent
import android.os.SystemClock
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
 * Active /control commands are forwarded as Intent extras so MainActivity can
 * defer application until it is truly foreground. STOP/PAUSE commands do not
 * wake the UI anymore; they are only for an already-visible activity.
 */
class VibrationDataLayerService : WearableListenerService() {

    internal enum class ActiveControlWakeDecision {
        LAUNCH,
        SKIP_DUPLICATE,
        SKIP_ACTIVITY_FOREGROUND
    }

    companion object {
        private const val TAG = "VibeWake"
        private const val DUPLICATE_CONTROL_WINDOW_MS = 2_000L

        private data class WakeControlCommand(
            val mode: Int,
            val level: Int,
            val intensity: Int,
            val timestamp: Long
        )

        private var lastWakeControlCommand: WakeControlCommand? = null
        private var lastWakeControlElapsedMs: Long = 0L

        // Intent extras for forwarding control commands
        const val EXTRA_MODE = "vibe.wake.mode"
        const val EXTRA_LEVEL = "vibe.wake.level"
        const val EXTRA_INTENSITY = "vibe.wake.intensity"
        const val EXTRA_TIMESTAMP = "vibe.wake.timestamp"

        @Synchronized
        internal fun shouldLaunchForControl(
            mode: Int,
            level: Int,
            intensity: Int,
            timestamp: Long,
            nowElapsedMs: Long = SystemClock.elapsedRealtime()
        ): Boolean {
            if (timestamp <= 0L) return true

            val command = WakeControlCommand(mode, level, intensity, timestamp)
            val withinDuplicateWindow =
                lastWakeControlCommand == command &&
                    nowElapsedMs >= lastWakeControlElapsedMs &&
                    nowElapsedMs - lastWakeControlElapsedMs <= DUPLICATE_CONTROL_WINDOW_MS
            if (withinDuplicateWindow) {
                return false
            }

            lastWakeControlCommand = command
            lastWakeControlElapsedMs = nowElapsedMs
            return true
        }

        @Synchronized
        internal fun decideActiveControlWake(
            mode: Int,
            level: Int,
            intensity: Int,
            timestamp: Long,
            activityUiForeground: Boolean = MainActivity.isUiForegroundForActiveControlWake(),
            nowElapsedMs: Long = SystemClock.elapsedRealtime()
        ): ActiveControlWakeDecision {
            if (!shouldLaunchForControl(mode, level, intensity, timestamp, nowElapsedMs)) {
                return ActiveControlWakeDecision.SKIP_DUPLICATE
            }
            if (activityUiForeground) {
                return ActiveControlWakeDecision.SKIP_ACTIVITY_FOREGROUND
            }
            return ActiveControlWakeDecision.LAUNCH
        }

        @Synchronized
        internal fun resetControlWakeDeduperForTesting() {
            lastWakeControlCommand = null
            lastWakeControlElapsedMs = 0L
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var hasLaunchPath = false
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
                    hasLaunchPath = true
                }
                AppConstants.PATH_CONTROL -> {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    launchMode = map.getInt(AppConstants.KEY_MODE, AppConstants.MODE_PAUSE)
                    launchLevel = map.getInt(AppConstants.KEY_LEVEL, 0)
                    launchIntensity = map.getInt(AppConstants.KEY_INTENSITY, 100)
                    launchTs = map.getLong(AppConstants.KEY_TIMESTAMP, 0L)
                    hasControl = launchMode != AppConstants.MODE_STOP && launchMode != AppConstants.MODE_PAUSE
                }
            }
        }
        dataEvents.release()

        val controlWakeDecision =
            if (hasControl) decideActiveControlWake(launchMode, launchLevel, launchIntensity, launchTs)
            else null
        val shouldLaunchControl = controlWakeDecision == ActiveControlWakeDecision.LAUNCH
        if (!hasLaunchPath && !shouldLaunchControl) {
            when (controlWakeDecision) {
                ActiveControlWakeDecision.SKIP_DUPLICATE ->
                    Log.d(TAG, "Skipping duplicate /control DataItem wake-up: ts=$launchTs")
                ActiveControlWakeDecision.SKIP_ACTIVITY_FOREGROUND ->
                    Log.d(TAG, "Skipping /control DataItem wake-up because MainActivity is already foreground: ts=$launchTs")
                null,
                ActiveControlWakeDecision.LAUNCH -> Unit
            }
            return
        }

        if (hasLaunchPath || shouldLaunchControl) {
            Log.d(
                TAG,
                "Wake-up via DataItem → launching MainActivity" +
                    if (shouldLaunchControl) " (mode=$launchMode level=$launchLevel)" else ""
            )
            startMainActivity(if (shouldLaunchControl) {
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
                    2 -> {
                        mode = parts[0].toIntOrNull() ?: return
                        level = parts[1].toIntOrNull() ?: return
                        intensity = 100
                        ts = 0L
                    }
                    3 -> {
                        mode = parts[0].toIntOrNull() ?: return
                        level = parts[1].toIntOrNull() ?: return
                        intensity = parts[2].toIntOrNull() ?: 100
                        ts = 0L
                    }
                    4 -> {
                        mode = parts[0].toIntOrNull() ?: return
                        level = parts[1].toIntOrNull() ?: return
                        intensity = parts[2].toIntOrNull() ?: 100
                        ts = parts[3].toLongOrNull() ?: 0L
                    }
                    else -> return
                }
                if (mode == AppConstants.MODE_STOP || mode == AppConstants.MODE_PAUSE) {
                    Log.d(TAG, "Ignoring wake-up for non-active /control message: mode=$mode")
                    return
                }
                when (decideActiveControlWake(mode, level, intensity, ts)) {
                    ActiveControlWakeDecision.SKIP_DUPLICATE -> {
                        Log.d(TAG, "Skipping duplicate /control message wake-up: ts=$ts")
                        return
                    }
                    ActiveControlWakeDecision.SKIP_ACTIVITY_FOREGROUND -> {
                        Log.d(TAG, "Skipping /control message wake-up because MainActivity is already foreground: ts=$ts")
                        return
                    }
                    ActiveControlWakeDecision.LAUNCH -> Unit
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
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
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
