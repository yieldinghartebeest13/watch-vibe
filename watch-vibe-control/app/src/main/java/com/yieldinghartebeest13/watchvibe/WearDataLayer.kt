package com.yieldinghartebeest13.watchvibe

import android.util.Log
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WearDataLayer private constructor(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    // Shared by Activity/ViewModel and every service instance in this process.
    internal val pingSequence = PingSequence()
    internal val heartbeat = PhoneHeartbeat()
    internal var onHeartbeatFailure: (() -> Unit)? = null

    // Incoming message listener from watch (crown exit, etc.)
    private var messageListener: MessageClient.OnMessageReceivedListener? = null

    companion object {
        private const val TAG = "VibeWearDL"
        private const val SEND_TIMEOUT_MS = 1_000L
        @Volatile private var instance: WearDataLayer? = null

        internal fun controlPayload(mode: Int, level: Int, intensity: Int, timestamp: Long, sessionId: Long) =
            "$mode,$level,$intensity,$timestamp,$sessionId".toByteArray()

        fun getInstance(context: Context): WearDataLayer = instance ?: synchronized(this) {
            instance ?: WearDataLayer(context.applicationContext).also { instance = it }
        }
    }

    suspend fun sendControl(mode: Int, level: Int, intensity: Int) {
        // Assign ordering before dispatch: an older active intent must not get
        // a newer timestamp simply because IO scheduling delayed it past STOP.
        val ts = pingSequence.nextCommandTimestamp()
        val sessionId = pingSequence.sessionId
        withContext(Dispatchers.IO) {
            // STOP must reach the real-time channel even if DataClient hangs.
            sendIndependentChannels(SEND_TIMEOUT_MS,
                onFailure = { channel, e -> Log.e(TAG, "Control $channel failed", e) },
                sendData = {
                    retryWithDelay(2, 300) {
                        val request = PutDataMapRequest.create(AppConstants.PATH_CONTROL).apply {
                            dataMap.putInt(AppConstants.KEY_MODE, mode)
                            dataMap.putInt(AppConstants.KEY_LEVEL, level)
                            dataMap.putInt(AppConstants.KEY_INTENSITY, intensity)
                            dataMap.putLong(AppConstants.KEY_TIMESTAMP, ts)
                            dataMap.putLong("sessionId", sessionId)
                        }
                        request.setUrgent()
                        dataClient.putDataItem(request.asPutDataRequest()).await()
                        Log.d(TAG, "Control DataItem: mode=$mode level=$level ts=$ts sid=$sessionId")
                    }
                },
                sendMessage = {
                    val nodes = nodeClient.connectedNodes.await()
                    val payload = controlPayload(mode, level, intensity, ts, sessionId)
                    for (node in nodes) {
                        retryWithDelay(2, 300) {
                            messageClient.sendMessage(node.id, AppConstants.PATH_CONTROL, payload).await()
                            Log.d(TAG, "Control Message: mode=$mode level=$level ts=$ts sid=$sessionId")
                        }
                    }
                }
            )
        }
    }

    suspend fun sendPing() {
        withContext(Dispatchers.IO) {
            val count = pingSequence.next()
            val ts = System.currentTimeMillis()
            val sessionId = pingSequence.sessionId
            sendIndependentChannels(SEND_TIMEOUT_MS,
                onFailure = { channel, e -> Log.d(TAG, "Ping $channel failed: ${e.message}") },
                sendData = {
                    val request = PutDataMapRequest.create(AppConstants.PATH_PING).apply {
                        dataMap.putLong("timestamp", ts)
                        dataMap.putLong("counter", count)
                        dataMap.putLong("sessionId", sessionId)
                    }
                    request.setUrgent()
                    dataClient.putDataItem(request.asPutDataRequest()).await()
                },
                sendMessage = {
                    val nodes = nodeClient.connectedNodes.await()
                    val payload = "$count,$ts,$sessionId".toByteArray()
                    for (node in nodes) {
                        try {
                            messageClient.sendMessage(node.id, AppConstants.PATH_PING, payload).await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Ping message to ${node.displayName} failed: ${e.message}")
                        }
                    }
                }
            )
        }
    }

    suspend fun sendWakeUp() = sendMessageToNodes(AppConstants.PATH_LAUNCH, "Wake-up")
    suspend fun sendMinimize() = sendMessageToNodes(AppConstants.PATH_MINIMIZE, "Minimize")

    private suspend fun sendMessageToNodes(path: String, label: String) {
        withContext(Dispatchers.IO) {
            try {
                val completed = withTimeoutOrNull(2_000L) {
                    for (node in nodeClient.connectedNodes.await()) {
                        messageClient.sendMessage(node.id, path, ByteArray(0)).await()
                        Log.d(TAG, "$label sent to ${node.displayName}")
                    }
                    true
                } ?: false
                if (!completed) Log.w(TAG, "$label deadline exceeded")
                Unit
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "$label failed", e)
            }
        }
    }

    // ── Incoming message listener (watch → phone) ──────────

    /** Timestamp (ms) of the last /alive message from the watch. */
    @Volatile var lastWatchAliveMs: Long = 0
        private set

    /**
     * Request the watch's battery; reply is handled by [startMessageListener].
     */
    suspend fun requestBattery() = sendMessageToNodes(AppConstants.PATH_BATTERY_REQUEST, "Battery request")

    /**
     * Start listening for messages from the watch.
     * Handles [AppConstants.PATH_CROWN_EXIT] and [AppConstants.PATH_BATTERY].
     */
    fun startMessageListener(
        onCrownExit: () -> Unit,
        onBatteryUpdate: (Int) -> Unit = {},
        onWatchAlive: () -> Unit = {}
    ) {
        stopMessageListener()
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
                    lastWatchAliveMs = SystemClock.elapsedRealtime()
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
        return last > 0 && SystemClock.elapsedRealtime() - last < AppConstants.ALIVE_TIMEOUT_MS
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
                withTimeoutOrNull(2_000L) {
                    capabilityClient.getCapability(AppConstants.CAPABILITY_VIBRATION,
                        CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()
                } ?: false
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == attempts - 1) throw e
                Log.w(TAG, "Attempt ${attempt + 1} failed, retrying in ${delayMs}ms: ${e.message}")
                delay(delayMs)
            }
        }
    }
}
