package com.virjar.tk.server.api

import com.virjar.tk.server.domain.bot.BotAuthorizationException
import com.virjar.tk.server.domain.bot.BotCredentialCommandConflictException
import com.virjar.tk.server.domain.bot.BotCredentialCommandTerminalException
import com.virjar.tk.server.domain.bot.BotResourceNotFoundException
import com.virjar.tk.server.domain.bot.GroupBotManagement
import com.virjar.tk.protocol.http.GroupBotCommandReceipt
import com.virjar.tk.protocol.http.GroupBotSummary
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupBotRoutesTest {
    @Test
    fun `group bot management uses user access token and stable paths`() = testApplication {
        val accessToken = "member-access-token"
        val tokens = TestAccessTokenValidator.single(accessToken, "member-1", "device-1")
        val service = FakeGroupBotManagement()
        application {
            this.install(ContentNegotiation) { json() }
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
            setBody(
                """{"operationId":"$CREATE_OPERATION_ID","name":"构建机器人","webhookToken":"$CREATE_TOKEN"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertFalse(CREATE_TOKEN in created.bodyAsText(), "server response must never echo the client credential")
        assertTrue(CREATE_OPERATION_ID in created.bodyAsText())
        assertEquals("构建机器人", service.lastName)
        assertEquals(CREATE_OPERATION_ID, service.lastOperationId)

        val rotated = client.post("/api/v1/groups/chat-1/bots/bot-1/rotate-token") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"operationId":"$ROTATE_OPERATION_ID","webhookToken":"$ROTATE_TOKEN"}""")
        }
        assertEquals(HttpStatusCode.OK, rotated.status)
        assertFalse(ROTATE_TOKEN in rotated.bodyAsText(), "server response must never echo the client credential")
        assertTrue(ROTATE_OPERATION_ID in rotated.bodyAsText())
        assertEquals("bot-1", service.lastBotId)

        val removed = client.delete("/api/v1/groups/chat-1/bots/bot-1") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertTrue(service.removed)
    }

    @Test
    fun `domain authorization rejection is returned as forbidden`() = testApplication {
        val accessToken = "outsider-access-token"
        val tokens = TestAccessTokenValidator.single(accessToken, "outsider", "device-1")
        val service = FakeGroupBotManagement(deny = true)
        application {
            this.install(ContentNegotiation) { json() }
            this.routing { groupBotRoutes(service, tokens) }
        }

        val response = client.get("/api/v1/groups/chat-1/bots") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertFalse(service.removed)
    }

    @Test
    fun `credential operation conflict is returned as conflict without echoing secret`() = testApplication {
        val accessToken = "member-access-token"
        val service = FakeGroupBotManagement(conflict = true)
        application {
            this.install(ContentNegotiation) { json() }
            this.routing {
                groupBotRoutes(service, TestAccessTokenValidator.single(accessToken, "member-1", "device-1"))
            }
        }

        val response = client.post("/api/v1/groups/chat-1/bots") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"operationId":"$CREATE_OPERATION_ID","name":"构建机器人","webhookToken":"$CREATE_TOKEN"}""",
            )
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertFalse(CREATE_TOKEN in response.bodyAsText())
    }

    @Test
    fun `missing rotation target is an explicit terminal not found response`() = testApplication {
        val accessToken = "member-access-token"
        val service = FakeGroupBotManagement(notFound = true)
        application {
            this.install(ContentNegotiation) { json() }
            this.routing {
                groupBotRoutes(service, TestAccessTokenValidator.single(accessToken, "member-1", "device-1"))
            }
        }

        val response = client.post("/api/v1/groups/chat-1/bots/missing/rotate-token") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"operationId":"$ROTATE_OPERATION_ID","webhookToken":"$ROTATE_TOKEN"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertFalse(ROTATE_TOKEN in response.bodyAsText())
    }

    @Test
    fun `superseded exact credential is gone while payload conflict remains conflict`() = testApplication {
        val accessToken = "member-access-token"
        val service = FakeGroupBotManagement(terminal = true)
        application {
            this.install(ContentNegotiation) { json() }
            this.routing {
                groupBotRoutes(service, TestAccessTokenValidator.single(accessToken, "member-1", "device-1"))
            }
        }

        val response = client.post("/api/v1/groups/chat-1/bots/bot-1/rotate-token") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"operationId":"$ROTATE_OPERATION_ID","webhookToken":"$ROTATE_TOKEN"}""")
        }

        assertEquals(HttpStatusCode.Gone, response.status)
        assertFalse(ROTATE_TOKEN in response.bodyAsText())
    }

    private companion object {
        const val CREATE_OPERATION_ID = "00000000-0000-4000-8000-000000000041"
        const val ROTATE_OPERATION_ID = "00000000-0000-4000-8000-000000000042"
        const val CREATE_TOKEN = "ttb_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val ROTATE_TOKEN = "ttb_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA"
    }
}

private class FakeGroupBotManagement(
    private val deny: Boolean = false,
    private val conflict: Boolean = false,
    private val notFound: Boolean = false,
    private val terminal: Boolean = false,
) : GroupBotManagement {
    var lastActorUid: String? = null
    var lastChatId: String? = null
    var lastBotId: String? = null
    var lastName: String? = null
    var lastOperationId: String? = null
    var removed = false

    override suspend fun listForGroup(actorUid: String, chatId: String): List<GroupBotSummary> {
        capture(actorUid, chatId)
        return listOf(summary())
    }

    override suspend fun createForGroup(
        actorUid: String,
        chatId: String,
        operationId: String,
        name: String,
        webhookToken: String,
    ): GroupBotCommandReceipt {
        capture(actorUid, chatId)
        if (conflict) throw BotCredentialCommandConflictException("command conflict")
        if (terminal) throw BotCredentialCommandTerminalException("credential retired")
        lastName = name
        lastOperationId = operationId
        return GroupBotCommandReceipt(operationId, summary())
    }

    override suspend fun rotateTokenForGroup(
        actorUid: String,
        chatId: String,
        botId: String,
        operationId: String,
        webhookToken: String,
    ): GroupBotCommandReceipt {
        capture(actorUid, chatId)
        if (conflict) throw BotCredentialCommandConflictException("command conflict")
        if (terminal) throw BotCredentialCommandTerminalException("credential retired")
        if (notFound) throw BotResourceNotFoundException("bot not found")
        lastBotId = botId
        lastOperationId = operationId
        return GroupBotCommandReceipt(operationId, summary())
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
