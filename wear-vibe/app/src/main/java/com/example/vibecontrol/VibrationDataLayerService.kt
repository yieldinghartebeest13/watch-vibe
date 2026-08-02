package com.example.vibecontrol

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Listens for data layer changes from the phone app.
 * When the phone sends mode/level changes on path "/control",
 * this service applies them to the VibratorEngine.
 */
class VibrationDataLayerService : WearableListenerService() {

    companion object {
        private const val TAG = "VibeDataLayer"
        const val PATH_CONTROL = "/control"
        const val KEY_MODE = "wear_mode"
        const val KEY_LEVEL = "wear_level"
    }

    private val vibratorEngine by lazy { VibratorEngine(this) }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                val path = dataItem.uri.path ?: continue

                if (path == PATH_CONTROL) {
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                    val mode = dataMap.getInt(KEY_MODE, -3)
                    val level = dataMap.getInt(KEY_LEVEL, 0)

                    Log.d(TAG, "Received control: mode=$mode, level=$level")
                    vibratorEngine.setModeVibration(mode, level)
                }
            }
        }
        dataEvents.release()
    }
}
