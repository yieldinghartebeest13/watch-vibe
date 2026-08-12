package com.yieldinghartebeest13.watchvibe

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

    // Session ID changes on every app launch. The watch uses it to detect
    // fresh sessions: counter reset is expected, auto-resume is suppressed.
    private val sessionId: Long = System.currentTimeMillis()
    private var pingCounter: Long = 0

    // Incoming message listener from watch (crown exit, etc.)
    private var messageListener: MessageClient.OnMessageReceivedListener? = null

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
                    dataMap.putLong("sessionId", sessionId)
                }
                request.setUrgent()
                dataClient.putDataItem(request.asPutDataRequest()).await()
            } catch (e: Exception) {
                Log.d(TAG, "Ping DataItem failed: ${e.message}")
            }

            // Also send via Message (lower latency, real-time channel)
            try {
                val nodes = nodeClient.connectedNodes.await()
                val payload = "$count,$ts,$sessionId".toByteArray()
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

    suspend fun sendMinimize() {
        withContext(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, AppConstants.PATH_MINIMIZE, ByteArray(0)).await()
                        Log.d(TAG, "Minimize sent to ${node.displayName}")
                    } catch (e: Exception) {
                        Log.d(TAG, "Minimize to ${node.displayName} failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Minimize failed", e)
            }
        }
    }

    // ── Incoming message listener (watch → phone) ──────────

    /** Timestamp (ms) of the last /alive message from the watch. */
    @Volatile var lastWatchAliveMs: Long = 0
        private set

    /**
     * Request the watch to send its current battery level.
     * The reply arrives via the [startMessageListener] battery callback.
     */
    suspend fun requestBattery() {
        withContext(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, AppConstants.PATH_BATTERY_REQUEST, ByteArray(0)).await()
                        Log.d(TAG, "Battery request sent to ${node.displayName}")
                    } catch (e: Exception) {
                        Log.d(TAG, "Battery request failed to ${node.displayName}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Battery request failed: ${e.message}")
            }
        }
    }

    /**
     * Start listening for messages from the watch.
     * Handles [AppConstants.PATH_CROWN_EXIT] and [AppConstants.PATH_BATTERY].
     */
    fun startMessageListener(
        onCrownExit: () -> Unit,
        onBatteryUpdate: (Int) -> Unit = {},
        onWatchAlive: () -> Unit = {}
    ) {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            when (event.path) {
                AppConstants.PATH_CROWN_EXIT -> {
                    Log.d(TAG, "Crown exit received from watch")
                    onCrownExit()
                }
                AppConstants.PATH_BATTERY -> {
                    val level = String(event.data).toIntOrNull() ?: -1
                    Log.d(TAG, "Battery update from watch: $level%")
                    if (level in 0..100) onBatteryUpdate(level)
                }
                AppConstants.PATH_ALIVE -> {
                    lastWatchAliveMs = System.currentTimeMillis()
                    onWatchAlive()
                }
                else -> {
                    Log.d(TAG, "Unknown incoming message: ${event.path}")
                }
            }
        }
        messageListener = listener
        messageClient.addListener(listener)
        Log.d(TAG, "Message listener registered (incoming)")
    }

    /**
     * Whether the watch has sent a recent /alive signal.
     * Returns true if last /alive was within ALIVE_TIMEOUT_MS.
     */
    fun isWatchAlive(): Boolean {
        val last = lastWatchAliveMs
        return last > 0 && System.currentTimeMillis() - last < AppConstants.ALIVE_TIMEOUT_MS
    }
    fun stopMessageListener() {
        messageListener?.let {
            messageClient.removeListener(it)
            Log.d(TAG, "Message listener unregistered (incoming)")
        }
        messageListener = null
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
