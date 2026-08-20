package com.virjar.tk.agent

import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    @Test
    fun `cleanup failure cannot replace an authoritative remote success`() = runBlocking {
        val response = preserveRemoteResultDuringCleanup(
            cleanup = { throw IllegalStateException("private-path-must-not-escape") },
            block = { 200 to "remote-success" },
        )

        assertEquals(200 to "remote-success", response)
    }

    @Test
    fun `cleanup failure cannot mask the original remote failure`(): Unit = runBlocking {
        assertFailsWith<RemoteFailure> {
            preserveRemoteResultDuringCleanup(
                cleanup = { throw IllegalStateException("cleanup-failure") },
                block = { throw RemoteFailure() },
            )
        }
    }

    @Test
    fun `request bodies are rejected at a fixed preflight and streaming limit`() {
        val maximum = ByteArray(MAX_AGENT_REQUEST_BODY_BYTES) { 'a'.code.toByte() }
        assertEquals(
            MAX_AGENT_REQUEST_BODY_BYTES,
            readAgentRequestBody(ByteArrayInputStream(maximum), maximum.size.toString()).length,
        )

        val declared = assertFailsWith<AgentRequestBodyException> {
            readAgentRequestBody(ByteArrayInputStream(byteArrayOf()), (maximum.size + 1).toString())
        }
        assertEquals(413, declared.status)
        assertEquals("request body is too large", declared.safeMessage)

        val streamed = assertFailsWith<AgentRequestBodyException> {
            readAgentRequestBody(ByteArrayInputStream(maximum + byteArrayOf(1)), declaredLength = null)
        }
        assertEquals(413, streamed.status)
        assertTrue("path" !in streamed.safeMessage)
    }

    private class RemoteFailure : RuntimeException()
}
