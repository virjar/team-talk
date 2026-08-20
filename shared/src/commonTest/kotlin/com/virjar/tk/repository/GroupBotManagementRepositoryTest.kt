package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupBotManagementRepositoryTest {
    @Test
    fun `contract builds only bounded identifier paths`() {
        assertEquals("/api/v1/groups/chat-1/bots", GroupBotHttpContract.listPath("chat-1"))
        assertEquals("/api/v1/groups/chat-1/bots/bot_2", GroupBotHttpContract.botPath("chat-1", "bot_2"))
        assertEquals(
            "/api/v1/groups/chat-1/bots/bot_2/rotate-token",
            GroupBotHttpContract.rotatePath("chat-1", "bot_2"),
        )

        assertFailsWith<IllegalArgumentException> { GroupBotHttpContract.listPath("../admin") }
        assertFailsWith<IllegalArgumentException> { GroupBotHttpContract.botPath("chat-1", "bot/other") }
    }

    @Test
    fun `contract decodes safe metadata without requiring credentials`() {
        val bots = GroupBotHttpContract.decodeList(
            """[{"botId":"bot-1","name":"构建通知","status":1,"lastUsedAt":null,"createdAt":7,"apiPath":"/api/v1/groups/chat-1/bots/bot-1/messages","groupManaged":true,"createdByMe":false,"canRotateToken":false,"canRemove":true,"futureField":"ignored"}]""",
        )

        assertEquals(1, bots.size)
        assertEquals("构建通知", bots.single().name)
        assertEquals("/api/v1/groups/chat-1/bots/bot-1/messages", bots.single().apiPath)
        assertTrue(bots.single().canRemove)
    }

    @Test
    fun `contract carries server business error text`() {
        assertEquals("不是当前群成员", GroupBotHttpContract.errorMessage("""{"error":"不是当前群成员"}""", "fallback"))
        assertEquals("fallback", GroupBotHttpContract.errorMessage("not-json", "fallback"))
    }

    @Test
    fun `session owner sees token rotation but rejects a different uid`() = runBlocking {
        val session = UserSession().apply {
            onAuthSuccess("uid-a", "a", "A", "refresh-a1", "access-a1")
        }
        val transport = RecordingGroupBotTransport()
        val repository = HttpGroupBotManagementRepository(
            serverUrl = "https://im.example.test/",
            ownerUid = "uid-a",
            credentialsProvider = session::httpCredentialsSnapshot,
            transport = transport,
        )

        repository.list("chat-1").getOrThrow()
        session.onAuthSuccess("uid-a", "a", "A", "refresh-a2", "access-a2")
        repository.list("chat-1").getOrThrow()
        assertEquals(listOf("access-a1", "access-a2"), transport.tokens)

        session.onAuthFailed("retired")
        session.onAuthSuccess("uid-b", "b", "B", "refresh-b", "access-b")
        assertIs<Outcome.Failure>(repository.list("chat-1"))
        assertEquals(listOf("access-a1", "access-a2"), transport.tokens)
    }

    @Test
    fun `same uid replacement epoch cannot lend its token to an old repository`() = runBlocking {
        var credentials = com.virjar.tk.client.SessionHttpCredentials("uid-a", "old", identityEpoch = 4L)
        val transport = RecordingGroupBotTransport()
        val repository = HttpGroupBotManagementRepository(
            serverUrl = "https://im.example.test",
            ownerUid = "uid-a",
            credentialsProvider = { credentials },
            transport = transport,
        )
        repository.list("chat-1").getOrThrow()

        credentials = com.virjar.tk.client.SessionHttpCredentials("uid-a", "replacement", identityEpoch = 5L)

        assertIs<Outcome.Failure>(repository.list("chat-1"))
        assertEquals(listOf("old"), transport.tokens)
    }

    @Test
    fun `close aborts a blocked request and rejects its late response publication`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val lateResponse = CompletableDeferred<String>()
        val transport = object : PlatformGroupBotHttpTransport {
            var closeCount = 0

            override suspend fun request(
                method: String,
                url: String,
                bearerToken: String,
                jsonBody: String?,
            ): String {
                entered.complete(Unit)
                return lateResponse.await()
            }

            override fun close() {
                closeCount += 1
            }
        }
        val repository = HttpGroupBotManagementRepository(
            serverUrl = "https://im.example.test",
            ownerUid = "uid-a",
            credentialsProvider = { com.virjar.tk.client.SessionHttpCredentials("uid-a", "access-a") },
            transport = transport,
        )
        val request = async(start = CoroutineStart.UNDISPATCHED) { repository.list("chat-1") }
        entered.await()

        repository.close()
        repository.close()
        assertEquals(1, transport.closeCount, "close 必须幂等且立即下沉到活跃 HTTP transport")
        lateResponse.complete("[]")

        assertIs<Outcome.Failure>(request.await(), "close 后到达的合法 JSON 也不得发布")
        Unit
    }

    private class RecordingGroupBotTransport : PlatformGroupBotHttpTransport {
        val tokens = mutableListOf<String>()

        override suspend fun request(
            method: String,
            url: String,
            bearerToken: String,
            jsonBody: String?,
        ): String {
            tokens += bearerToken
            return "[]"
        }

        override fun close() = Unit
    }
}
