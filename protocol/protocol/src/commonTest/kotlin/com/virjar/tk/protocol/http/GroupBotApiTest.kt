package com.virjar.tk.protocol.http

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class GroupBotApiTest {
    @Test
    fun `target webhook body contains only markdown and rejects routing fields`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<GroupBotMessageRequest>(
                """{"chatId":"must-not-route","markdown":"hello","idempotencyKey":"must-not-win"}""",
            )
        }

        val decoded = Json.decodeFromString<GroupBotMessageRequest>("""{"markdown":"hello"}""")
        assertEquals("""{"markdown":"hello"}""", Json.encodeToString(decoded))
    }

    @Test
    fun `target webhook acknowledgement is minimal`() {
        assertEquals("""{"ok":true}""", Json.encodeToString(GroupBotMessageResponse(ok = true)))
    }

    @Test
    fun `credential command carries stable identity while receipt never carries secret`() {
        val request = CreateGroupBotRequest(
            operationId = "00000000-0000-4000-8000-000000000031",
            name = "构建机器人",
            webhookToken = "ttb_0123456789012345678901234567890123456789012",
        )
        assertEquals(request, Json.decodeFromString(Json.encodeToString(request)))
        assertFalse(request.webhookToken in request.toString())
        val rotation = RotateGroupBotTokenRequest(request.operationId, request.webhookToken)
        assertEquals(rotation, Json.decodeFromString(Json.encodeToString(rotation)))
        assertFalse(rotation.webhookToken in rotation.toString())

        val receipt = GroupBotCommandReceipt(
            operationId = request.operationId,
            bot = GroupBotSummary(
                botId = "bot-1",
                name = request.name,
                status = 1,
                lastUsedAt = null,
                createdAt = 7,
                apiPath = "/api/v1/groups/chat-1/bots/bot-1/messages",
                groupManaged = true,
                createdByMe = true,
                canRotateToken = true,
                canRemove = true,
            ),
        )
        val encodedReceipt = Json.encodeToString(receipt)
        assertEquals(receipt, Json.decodeFromString(encodedReceipt))
        assertFalse(request.webhookToken in encodedReceipt)
        assertFalse(
            request.webhookToken in GroupBotCredentials(
                bot = receipt.bot,
                webhookToken = request.webhookToken,
                operationId = request.operationId,
            ).toString(),
        )
    }
}
