package com.virjar.tk.agent

import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentApiTest {

    @Test
    fun `every agent endpoint requires the exact bearer token`() {
        assertTrue(isValidAgentAuthorization("Bearer secret", "secret"))
        assertFalse(isValidAgentAuthorization(null, "secret"))
        assertFalse(isValidAgentAuthorization("secret", "secret"))
        assertFalse(isValidAgentAuthorization("Bearer other", "secret"))
        assertFalse(isValidAgentAuthorization("Bearer ", ""))
    }

    @Test
    fun `only successful ack is exposed as ok`() {
        val (status, body) = agentAckResponse(MessageAckPayload("client-1", 7, 0, null))

        assertEquals(200, status)
        assertEquals("true", body["ok"]?.jsonPrimitive?.content)
        assertEquals("7", body["data"]?.jsonObject?.get("serverSeq")?.jsonPrimitive?.content)
    }

    @Test
    fun `rejected and timed out ack are exposed as errors`() {
        val (rejectedStatus, rejected) = agentAckResponse(
            MessageAckPayload("client-1", 0, 400, "Markdown 正文过长"),
        )
        assertEquals(400, rejectedStatus)
        assertEquals("false", rejected["ok"]?.jsonPrimitive?.content)
        assertEquals("Markdown 正文过长", rejected["error"]?.jsonPrimitive?.content)

        val (timeoutStatus, timeout) = agentAckResponse(
            MessageAckPayload("client-2", 0, -1, "ACK timeout"),
        )
        assertEquals(502, timeoutStatus)
        assertEquals("false", timeout["ok"]?.jsonPrimitive?.content)
    }
}
