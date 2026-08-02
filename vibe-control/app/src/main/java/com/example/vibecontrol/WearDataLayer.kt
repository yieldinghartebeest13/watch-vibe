package com.example.vibecontrol

import android.util.Log
import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class WearDataLayer(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    companion object {
        private const val TAG = "VibeWearDL"
        const val PATH_CONTROL = "/control"
        const val KEY_MODE = "wear_mode"
        const val KEY_LEVEL = "wear_level"
        const val KEY_INTENSITY = "wear_intensity"
    }

    suspend fun sendControl(mode: Int, level: Int, intensity: Int) {
        withContext(Dispatchers.IO) {
            try {
                // Check connected nodes first
                val nodes = nodeClient.connectedNodes.await()
                Log.e(TAG, "Nodes connected: ${nodes.size}")
                for (node in nodes) {
                    Log.e(TAG, "  Node: ${node.displayName} (${node.id})")
                }
                if (nodes.isEmpty()) {
                    Log.e(TAG, "NO WEAR NODES CONNECTED — data won't reach watch!")
                }

                val request = PutDataMapRequest.create(PATH_CONTROL).apply {
                    dataMap.putInt(KEY_MODE, mode)
                    dataMap.putInt(KEY_LEVEL, level)
                    dataMap.putInt(KEY_INTENSITY, intensity)
                }
                request.setUrgent()
                val result = dataClient.putDataItem(request.asPutDataRequest()).await()
                Log.e(TAG, "Sent DataItem: mode=$mode level=$level intensity=$intensity uri=${result.uri}")

                // Also send via Message API (more reliable for real-time)
                val payload = "$mode,$level,$intensity".toByteArray()
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, PATH_CONTROL, payload).await()
                        Log.e(TAG, "Message sent to ${node.displayName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Message to ${node.displayName} failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
            }
        }
    }

    suspend fun isWearConnected(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                nodeClient.connectedNodes.await().isNotEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Node check failed", e)
                false
            }
        }
    }
}
