package com.virjar.tk.agent

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.client.OutgoingMessage
import com.virjar.tk.client.OutgoingMessageState
import com.virjar.tk.client.PendingBotMessage
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AgentApiTest {

    @Test
    fun `delivery json preserves create edit and revoke semantics`() {
        val created = Message(
            chatId = "chat-1",
            clientMsgId = "message-1",
            serverSeq = 7L,
            senderUid = "peer",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1L,
            body = RichTextBody("hello", plainText = "hello"),
        )
        val payloads = listOf(
            pendingBotMessageJson(PendingBotMessage(21L, created)),
            pendingBotMessageJson(PendingBotMessage(22L, created.copy(flags = Message.FLAG_EDITED))),
            pendingBotMessageJson(
                PendingBotMessage(23L, created.copy(flags = Message.FLAG_REVOKED, body = null)),
            ),
        )

        assertEquals(listOf("21", "22", "23"), payloads.map { it["eventId"]?.jsonPrimitive?.content })
        assertEquals(
            listOf("0", Message.FLAG_EDITED.toString(), Message.FLAG_REVOKED.toString()),
            payloads.map { it["flags"]?.jsonPrimitive?.content },
        )
        assertEquals("1", payloads[1]["messageType"]?.jsonPrimitive?.content)
        assertEquals("true", payloads[1]["edited"]?.jsonPrimitive?.content)
        assertEquals("true", payloads[2]["revoked"]?.jsonPrimitive?.content)
        assertEquals("", payloads[2]["text"]?.jsonPrimitive?.content)
    }

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
    fun `outgoing receipt json preserves state server sequence and stable ids`() {
        val message = Message(
            chatId = "chat-receipt",
            clientMsgId = "client-receipt",
            serverSeq = 0L,
            senderUid = "bot-1",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1L,
            body = RichTextBody("hello", plainText = "hello"),
        )
        val base = OutgoingMessage(
            localOrdinal = 1L,
            message = message,
            state = OutgoingMessageState.PENDING,
            attemptCount = 0L,
            lastError = null,
            nextAttemptAt = 0L,
            createdAt = 2L,
            updatedAt = 3L,
        )
        val stateNames = mapOf(
            OutgoingMessageState.PENDING to "queued",
            OutgoingMessageState.IN_FLIGHT to "sending",
            OutgoingMessageState.RETRY_WAIT to "queued",
            OutgoingMessageState.TERMINAL_FAILED to "failed",
            OutgoingMessageState.SUCCESS to "sent",
        )

        stateNames.forEach { (state, expected) ->
            assertEquals(
                expected,
                outgoingReceiptJson(base.copy(state = state))["state"]?.jsonPrimitive?.content,
            )
        }
        val sent = outgoingReceiptJson(
            base.copy(state = OutgoingMessageState.SUCCESS, serverSeq = 91L),
        )
        assertEquals("chat-receipt", sent["chatId"]?.jsonPrimitive?.content)
        assertEquals("client-receipt", sent["clientMsgId"]?.jsonPrimitive?.content)
        assertEquals("91", sent["serverSeq"]?.jsonPrimitive?.content)
    }

    @Test
    fun `agent client and chat ids enforce their wire boundaries`() {
        val maximumClientId = "c".repeat(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH)
        val maximumChatId = "h".repeat(MessageBodyPolicy.MAX_CHAT_ID_LENGTH)
        assertEquals(maximumClientId, requireAgentClientMsgId(maximumClientId))
        assertEquals(maximumChatId, requireAgentChatId(maximumChatId))

        listOf(
            { requireAgentClientMsgId("") },
            { requireAgentClientMsgId("client\nmessage") },
            { requireAgentClientMsgId("c".repeat(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH + 1)) },
            { requireAgentChatId(" ") },
            { requireAgentChatId("chat\u0000id") },
            { requireAgentChatId("h".repeat(MessageBodyPolicy.MAX_CHAT_ID_LENGTH + 1)) },
        ).forEach { invalidRequest ->
            val failure = assertFailsWith<AgentRequestBodyException> { invalidRequest() }
            assertEquals(400, failure.status)
        }
    }

    @Test
    fun `invalid send file path response is fixed and contains no caller path`() {
        val privatePath = "/private/customer/secret.txt"
        val (status, response) = agentFileRequestErrorResponse()

        assertEquals(400, status)
        assertEquals("false", response["ok"]?.jsonPrimitive?.content)
        assertEquals("file path is not allowed", response["error"]?.jsonPrimitive?.content)
        assertFalse(privatePath in response.toString())
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

    @Test
    fun `message cursors and bounds reject malformed query values with 400`() {
        assertEquals(0L, parseAgentCursor(raw = null, default = 0L))
        assertNull(parseAgentCursor(raw = null, default = null))
        assertEquals(7L, parseAgentCursor(raw = "7", default = null))
        listOf("-1", "not-a-number").forEach { raw ->
            val failure = assertFailsWith<AgentRequestBodyException> {
                parseAgentCursor(raw = raw, default = null)
            }
            assertEquals(400, failure.status)
        }

        assertEquals(50, parseAgentBoundedInt(raw = null, default = 50, range = 1..1000, label = "limit"))
        assertEquals(60, parseAgentBoundedInt(raw = "60", default = 10, range = 1..60, label = "timeout"))
        listOf("0", "1001", "not-a-number").forEach { raw ->
            val failure = assertFailsWith<AgentRequestBodyException> {
                parseAgentBoundedInt(raw = raw, default = 50, range = 1..1000, label = "limit")
            }
            assertEquals(400, failure.status)
        }
    }

    @Test
    fun `raw query round trips percent plus and unicode stable ids exactly once`() {
        val chatId = "chat+\u96ea"
        val clientMsgId = "job%2F42+\u4e0a\u6d77"
        val raw = listOf(
            "chatId=${URLEncoder.encode(chatId, "UTF-8")}",
            "clientMsgId=${URLEncoder.encode(clientMsgId, "UTF-8")}",
        ).joinToString("&")

        val parsed = parseAgentQuery(raw)

        assertEquals(chatId, parsed["chatId"])
        assertEquals(clientMsgId, parsed["clientMsgId"])
        val malformed = assertFailsWith<AgentRequestBodyException> { parseAgentQuery("clientMsgId=%") }
        assertEquals(400, malformed.status)
    }

    private class RemoteFailure : RuntimeException()
}
