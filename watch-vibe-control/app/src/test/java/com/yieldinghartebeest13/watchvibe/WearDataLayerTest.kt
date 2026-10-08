package com.yieldinghartebeest13.watchvibe

import android.content.Context
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WearDataLayerTest {
    private lateinit var data: DataClient
    private lateinit var messages: MessageClient
    private lateinit var nodes: NodeClient
    private lateinit var transport: WearDataLayer

    private fun resetInstance() {
        WearDataLayer::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Before
    fun setUp() {
        resetInstance()
        mockkStatic(Wearable::class)
        data = mockk()
        messages = mockk()
        nodes = mockk()
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getNodeClient(any<Context>()) } returns nodes
        every { Wearable.getCapabilityClient(any<Context>()) } returns mockk<CapabilityClient>()
        val node = mockk<Node>()
        every { node.id } returns "watch"
        every { node.displayName } returns "Watch"
        every { nodes.connectedNodes } returns Tasks.forResult(listOf(node))
        every { data.putDataItem(any()) } returns Tasks.forResult(mockk<DataItem>())
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        transport = WearDataLayer.getInstance(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        resetInstance()
        unmockkAll()
    }

    @Test
    fun `service restarts reuse application transport session and counter`() {
        val session = transport.pingSequence.sessionId
        val counter = transport.pingSequence.next()
        repeat(2) {
            val service = Robolectric.buildService(PingForegroundService::class.java).create()
            val same = WearDataLayer.getInstance(service.get())
            assertSame(transport, same)
            assertEquals(session, same.pingSequence.sessionId)
            assertEquals(counter + it + 1, same.pingSequence.next())
            service.destroy()
        }
    }

    @Test
    fun `ping data and message share session and counter`() = runBlocking {
        val request = slot<PutDataRequest>()
        val payload = slot<ByteArray>()
        every { data.putDataItem(capture(request)) } returns Tasks.forResult(mockk<DataItem>())
        every { messages.sendMessage("watch", AppConstants.PATH_PING, capture(payload)) } returns Tasks.forResult(1)
        transport.sendPing()
        val map = DataMap.fromByteArray(request.captured.data!!)
        val fields = String(payload.captured).split(",").map { it.toLong() }
        assertEquals(3, fields.size)
        assertEquals(map.getLong("counter"), fields[0])
        assertEquals(map.getLong("timestamp"), fields[1])
        assertEquals(transport.pingSequence.sessionId, fields[2])
        assertEquals(map.getLong("sessionId"), fields[2])
    }

    @Test
    fun `control carries shared session in data and fifth message field including STOP`() = runBlocking {
        val request = slot<PutDataRequest>()
        val payload = slot<ByteArray>()
        every { data.putDataItem(capture(request)) } returns Tasks.forResult(mockk<DataItem>())
        every { messages.sendMessage("watch", AppConstants.PATH_CONTROL, capture(payload)) } returns Tasks.forResult(1)
        for (mode in listOf(AppConstants.MODE_CONSTANT, AppConstants.MODE_STOP)) {
            transport.sendControl(mode, 2, 100)
            val map = DataMap.fromByteArray(request.captured.data!!)
            val fields = String(payload.captured).split(",").map { it.toLong() }
            assertEquals(5, fields.size)
            assertEquals(listOf(mode.toLong(), 2L, 100L), fields.take(3))
            assertEquals(map.getLong(AppConstants.KEY_TIMESTAMP), fields[3])
            assertEquals(transport.pingSequence.sessionId, fields[4])
            assertEquals(map.getLong("sessionId"), fields[4])
        }
    }

    @Test
    fun `hanging DataClient does not block ping or explicit STOP messages`() = runBlocking {
        every { data.putDataItem(any()) } returns TaskCompletionSource<DataItem>().task
        transport.sendPing()
        verify(exactly = 1) { messages.sendMessage("watch", AppConstants.PATH_PING, any()) }
        transport.sendControl(AppConstants.MODE_STOP, 0, 0)
        verify(exactly = 1) { messages.sendMessage("watch", AppConstants.PATH_CONTROL, any()) }
    }
}
