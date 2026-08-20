package com.virjar.tk.integration

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.http.BOT_IDEMPOTENCY_KEY_HEADER
import com.virjar.tk.api.targetBoundBotMessageRoutes
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals

class BotWebhookIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `target webhook authenticates authorizes and applies optional idempotency header`() = testApplication {
        val owner = ctx.registerUser(uniqueUsername("webhook-owner"))
        val creator = ctx.registerUser(uniqueUsername("webhook-creator"))
        val group = ctx.chatService.createGroup("目标群", null, owner, listOf(creator))
        val otherGroup = ctx.chatService.createGroup("非目标群", null, owner, listOf(creator))
        val created = ctx.botService.createForGroup(creator, group.chatId, "Webhook 集成机器人")

        application {
            install(ContentNegotiation) { json() }
            routing { targetBoundBotMessageRoutes(ctx.botService) }
        }

        val bodyRoutingAttempt = client.post(created.bot.apiPath) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${created.webhookToken}")
            setBody(
                """{"chatId":"${otherGroup.chatId}","markdown":"must be rejected","idempotencyKey":"ignored-body-key"}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, bodyRoutingAttempt.status)

        repeat(2) {
            val response = client.post(created.bot.apiPath) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${created.webhookToken}")
                header(BOT_IDEMPOTENCY_KEY_HEADER, "same-event")
                setBody("""{"markdown":"explicit idempotency"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"ok":true}""", response.bodyAsText())
        }

        repeat(2) {
            val response = client.post(created.bot.apiPath) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${created.webhookToken}")
                setBody("""{"markdown":"server generated idempotency"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        val invalidKey = client.post(created.bot.apiPath) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${created.webhookToken}")
            header(
                BOT_IDEMPOTENCY_KEY_HEADER,
                "x".repeat(com.virjar.tk.domain.bot.BotService.MAX_IDEMPOTENCY_KEY_LENGTH + 1),
            )
            setBody("""{"markdown":"invalid idempotency key"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidKey.status)

        val targetMessages = ctx.messageService.getHistory(owner, group.chatId, Long.MAX_VALUE, 10)
        assertEquals(
            1,
            targetMessages.count { (it.body as? RichTextBody)?.markdown == "explicit idempotency" },
            "同一 Idempotency-Key 的重试只能形成一条消息",
        )
        assertEquals(
            2,
            targetMessages.count { (it.body as? RichTextBody)?.markdown == "server generated idempotency" },
            "未提供 Idempotency-Key 时每次调用都应生成独立消息身份",
        )
        assertEquals(
            0,
            ctx.messageService.getHistory(owner, otherGroup.chatId, Long.MAX_VALUE, 10).size,
            "JSON chatId 不能覆盖 URL 目标",
        )

        val wrongTarget = client.post(
            "/api/v1/groups/${otherGroup.chatId}/bots/${created.bot.botId}/messages",
        ) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${created.webhookToken}")
            setBody("""{"markdown":"must be rejected"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, wrongTarget.status)

        val wrongToken = client.post(created.bot.apiPath) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer wrong-token")
            setBody("""{"markdown":"must be rejected"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)
    }
}
