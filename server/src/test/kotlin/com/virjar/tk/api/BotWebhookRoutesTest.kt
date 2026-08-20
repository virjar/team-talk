package com.virjar.tk.api

import com.virjar.tk.domain.bot.BotAuthenticationException
import com.virjar.tk.domain.bot.BotDeliveryResult
import com.virjar.tk.domain.bot.BotMessageDelivery
import com.virjar.tk.http.BOT_IDEMPOTENCY_KEY_HEADER
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BotWebhookRoutesTest {
    @Test
    fun `target webhook binds destination to path and returns only ok`() = testApplication {
        val delivery = RecordingBotDelivery()
        application {
            install(ContentNegotiation) { json() }
            routing { targetBoundBotMessageRoutes(delivery) }
        }

        val response = client.post("/api/v1/groups/chat-path/bots/bot-1/messages") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ttb_secret")
            header(BOT_IDEMPOTENCY_KEY_HEADER, "deploy-42")
            setBody("""{"markdown":"## 完成"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
        assertEquals(
            DeliveryCall("bot-1", "ttb_secret", "chat-path", "## 完成", "deploy-42"),
            delivery.calls.single(),
        )
    }

    @Test
    fun `missing idempotency header creates a unique server key per request`() = testApplication {
        val delivery = RecordingBotDelivery()
        var generated = 0
        application {
            install(ContentNegotiation) { json() }
            routing { targetBoundBotMessageRoutes(delivery) { "generated-${++generated}" } }
        }

        repeat(2) {
            val response = client.post("/api/v1/groups/chat-1/bots/bot-1/messages") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ttb_secret")
                setBody("""{"markdown":"same event"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(2, delivery.calls.size)
        assertNotEquals(delivery.calls[0].idempotencyKey, delivery.calls[1].idempotencyKey)
        assertEquals(listOf("generated-1", "generated-2"), delivery.calls.map { it.idempotencyKey })
    }

    @Test
    fun `target webhook maps bot credential failure to unauthorized`() = testApplication {
        val delivery = RecordingBotDelivery(error = BotAuthenticationException())
        application {
            install(ContentNegotiation) { json() }
            routing { targetBoundBotMessageRoutes(delivery) }
        }

        val response = client.post("/api/v1/groups/chat-1/bots/bot-1/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"markdown":"hello"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `target webhook rejects body routing fields before delivery`() = testApplication {
        val delivery = RecordingBotDelivery()
        application {
            install(ContentNegotiation) { json() }
            routing { targetBoundBotMessageRoutes(delivery) }
        }

        val response = client.post("/api/v1/groups/chat-path/bots/bot-1/messages") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ttb_secret")
            setBody("""{"chatId":"chat-body","markdown":"hello"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(emptyList(), delivery.calls)
    }
}

private data class DeliveryCall(
    val botId: String,
    val token: String?,
    val chatId: String,
    val markdown: String,
    val idempotencyKey: String,
)

private class RecordingBotDelivery(
    private val error: IllegalArgumentException? = null,
) : BotMessageDelivery {
    val calls = mutableListOf<DeliveryCall>()

    override suspend fun deliver(
        botId: String,
        token: String?,
        chatId: String,
        markdown: String,
        idempotencyKey: String,
    ): BotDeliveryResult {
        error?.let { throw it }
        calls += DeliveryCall(botId, token, chatId, markdown, idempotencyKey)
        return BotDeliveryResult(chatId, serverSeq = 7, clientMsgId = "client-$idempotencyKey")
    }
}
