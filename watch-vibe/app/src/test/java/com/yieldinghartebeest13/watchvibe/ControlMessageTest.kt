package com.yieldinghartebeest13.watchvibe

import org.junit.Assert.*
import org.junit.Test

class ControlMessageTest {
    @Test
    fun `legacy payloads remain supported`() {
        assertEquals(ControlMessage(0, 1), decode("0,1"))
        assertEquals(ControlMessage(1, 2, 70), decode("1,2,70"))
        assertEquals(ControlMessage(2, 3, 80, 123L), decode("2,3,80,123"))
    }

    @Test
    fun `new command carries the same session identity as heartbeat`() {
        assertEquals(ControlMessage(4, 1, 50, 123L, 456L), decode("4,1,50,123,456"))
        assertEquals(ControlMessage(-2, 0, 0, 124L, 456L), decode("-2,0,0,124,456"))
    }

    @Test
    fun `malformed timestamp cannot become an untimestamped active command`() {
        assertNull(decode("0,1,100,bad,456"))
        assertNull(decode("0,1,100,123,bad"))
    }

    @Test
    fun `invalid mode shape and fields are rejected`() {
        assertNull(decode("99,1"))
        assertNull(decode("0"))
        assertNull(decode("0,bad"))
        assertNull(decode("0,1,100,123,456,extra"))
    }

    private fun decode(value: String) = ControlMessage.decode(value.toByteArray())
}
