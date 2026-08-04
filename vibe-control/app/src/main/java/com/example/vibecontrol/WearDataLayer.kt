package com.example.vibecontrol

import android.util.Log
import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class WearDataLayer(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private var pingCounter: Long = 0

    companion object {
        private const val TAG = "VibeWearDL"
    }

    suspend fun sendControl(mode: Int, level: Int, intensity: Int) {
        withContext(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                Log.d(TAG, "Nodes connected: ${nodes.size}")
                for (node in nodes) {
                    Log.d(TAG, "  Node: ${node.displayName} (${node.id})")
                }
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No Wear nodes connected — data won't reach watch!")
                }

                val ts = System.currentTimeMillis()

                // Send via DataItem with retry
                retryWithDelay(2, 300) {
                    val request = PutDataMapRequest.create(AppConstants.PATH_CONTROL).apply {
                        dataMap.putInt(AppConstants.KEY_MODE, mode)
                        dataMap.putInt(AppConstants.KEY_LEVEL, level)
                        dataMap.putInt(AppConstants.KEY_INTENSITY, intensity)
                        dataMap.putLong(AppConstants.KEY_TIMESTAMP, ts)
                    }
                    request.setUrgent()
                    val result = dataClient.putDataItem(request.asPutDataRequest()).await()
                    Log.d(TAG, "Sent DataItem: mode=$mode level=$level intensity=$intensity uri=${result.uri}")
                }

                // Also send via Message API (more reliable for real-time)
                val payload = "$mode,$level,$intensity,$ts".toByteArray()
                for (node in nodes) {
                    retryWithDelay(2, 300) {
                        messageClient.sendMessage(node.id, AppConstants.PATH_CONTROL, payload).await()
                        Log.d(TAG, "Message sent to ${node.displayName}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed after retries", e)
            }
        }
    }

    suspend fun sendPing() {
        withContext(Dispatchers.IO) {
            val count = ++pingCounter
            val ts = System.currentTimeMillis()

            // Send via DataItem (persistent, survives brief disconnects)
            try {
                val request = PutDataMapRequest.create(AppConstants.PATH_PING).apply {
                    dataMap.putLong("timestamp", ts)
                    dataMap.putLong("counter", count)
                }
                request.setUrgent()
                dataClient.putDataItem(request.asPutDataRequest()).await()
            } catch (e: Exception) {
                Log.d(TAG, "Ping DataItem failed: ${e.message}")
            }

            // Also send via Message (lower latency, real-time channel)
            try {
                val nodes = nodeClient.connectedNodes.await()
                val payload = "$count,$ts".toByteArray()
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, AppConstants.PATH_PING, payload).await()
                    } catch (e: Exception) {
                        Log.d(TAG, "Ping message to ${node.displayName} failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Ping message failed: ${e.message}")
            }
        }
    }

    suspend fun sendWakeUp() {
        withContext(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, AppConstants.PATH_LAUNCH, ByteArray(0)).await()
                        Log.d(TAG, "Wake-up sent to ${node.displayName}")
                    } catch (e: Exception) {
                        Log.d(TAG, "Wake-up to ${node.displayName} failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Wake-up failed", e)
            }
        }
    }

    suspend fun isWearConnected(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val capInfo = capabilityClient.getCapability(
                    AppConstants.CAPABILITY_VIBRATION, CapabilityClient.FILTER_REACHABLE
                ).await()
                capInfo.nodes.isNotEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Capability check failed", e)
                false
            }
        }
    }

    private suspend fun retryWithDelay(
        attempts: Int = 2,
        delayMs: Long = 300,
        block: suspend () -> Unit
    ) {
        repeat(attempts) { attempt ->
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt == attempts - 1) throw e
                Log.w(TAG, "Attempt ${attempt + 1} failed, retrying in ${delayMs}ms: ${e.message}")
                delay(delayMs)
            }
        }
    }
}
