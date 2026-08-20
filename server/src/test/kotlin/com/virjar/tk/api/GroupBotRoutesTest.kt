package com.virjar.tk.api

import com.virjar.tk.domain.bot.BotAuthorizationException
import com.virjar.tk.domain.bot.GroupBotManagement
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.infra.storage.TokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupBotRoutesTest {
    @Test
    fun `group bot management uses user access token and stable paths`() = testApplication {
        val tokens = TokenStore(File("/tmp/tk-group-bot-route-${System.nanoTime()}").absolutePath)
        val (accessToken, _) = tokens.generateTokens("member-1", "device-1", 0)
        val service = FakeGroupBotManagement()
        application {
            this.install(ContentNegotiation) { json() }
            this.monitor.subscribe(ApplicationStopped) { tokens.close() }
            this.routing { groupBotRoutes(service, tokens) }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/groups/chat-1/bots").status)

        val listed = client.get("/api/v1/groups/chat-1/bots") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        val listedBody = listed.bodyAsText()
        assertFalse("ttb_" in listedBody, "list responses must never contain bot credentials")
        assertTrue("/api/v1/groups/chat-1/bots/bot-1/messages" in listedBody)
        assertEquals("member-1", service.lastActorUid)
        assertEquals("chat-1", service.lastChatId)

        val created = client.post("/api/v1/groups/chat-1/bots") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"构建机器人"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue("ttb_once" in created.bodyAsText())
        assertEquals("构建机器人", service.lastName)

        val rotated = client.post("/api/v1/groups/chat-1/bots/bot-1/rotate-token") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, rotated.status)
        assertTrue("ttb_rotated" in rotated.bodyAsText())
        assertEquals("bot-1", service.lastBotId)

        val removed = client.delete("/api/v1/groups/chat-1/bots/bot-1") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertTrue(service.removed)
    }

    @Test
    fun `domain authorization rejection is returned as forbidden`() = testApplication {
        val tokens = TokenStore(File("/tmp/tk-group-bot-route-denied-${System.nanoTime()}").absolutePath)
        val (accessToken, _) = tokens.generateTokens("outsider", "device-1", 0)
        val service = FakeGroupBotManagement(deny = true)
        application {
            this.install(ContentNegotiation) { json() }
            this.monitor.subscribe(ApplicationStopped) { tokens.close() }
            this.routing { groupBotRoutes(service, tokens) }
        }

        val response = client.get("/api/v1/groups/chat-1/bots") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertFalse(service.removed)
    }
}

private class FakeGroupBotManagement(private val deny: Boolean = false) : GroupBotManagement {
    var lastActorUid: String? = null
    var lastChatId: String? = null
    var lastBotId: String? = null
    var lastName: String? = null
    var removed = false

    override fun listForGroup(actorUid: String, chatId: String): List<GroupBotSummary> {
        capture(actorUid, chatId)
        return listOf(summary())
    }

    override suspend fun createForGroup(actorUid: String, chatId: String, name: String): GroupBotCredentials {
        capture(actorUid, chatId)
        lastName = name
        return GroupBotCredentials(summary(), "ttb_once")
    }

    override fun rotateTokenForGroup(actorUid: String, chatId: String, botId: String): GroupBotCredentials {
        capture(actorUid, chatId)
        lastBotId = botId
        return GroupBotCredentials(summary(), "ttb_rotated")
    }

    override suspend fun removeFromGroup(actorUid: String, chatId: String, botId: String) {
        capture(actorUid, chatId)
        lastBotId = botId
        removed = true
    }

    private fun capture(actorUid: String, chatId: String) {
        if (deny) throw BotAuthorizationException("not a member")
        lastActorUid = actorUid
        lastChatId = chatId
    }

    private fun summary() = GroupBotSummary(
        botId = "bot-1",
        name = "构建机器人",
        status = 1,
        lastUsedAt = null,
        createdAt = 1,
        apiPath = "/api/v1/groups/chat-1/bots/bot-1/messages",
        groupManaged = true,
        createdByMe = true,
        canRotateToken = true,
        canRemove = true,
    )
}
