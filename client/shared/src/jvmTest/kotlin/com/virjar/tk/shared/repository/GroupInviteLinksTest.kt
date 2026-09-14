package com.virjar.tk.shared.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.RpcStatusException
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCacheImpl
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupInviteLinksTest {
    @Test
    fun `shared text and legacy clipboard codes identify a single invitation`() {
        val link = "$BASE/invite#$TOKEN"
        listOf(link, "请加入群聊：$link。", "[$link]", "邀请：<$link>", "$link\n$link").forEach {
            assertEquals(link, GroupInviteLinks.find(it), it)
        }
        assertEquals(TOKEN, GroupInviteLinks.find(" \n${TOKEN.uppercase()}\t"))
        assertEquals(link, GroupInviteLinks.find("HTTPS://IM.EXAMPLE.TEST:443/invite#${TOKEN.uppercase()}"))
        assertEquals("$BASE/team-talk/invite#$TOKEN", GroupInviteLinks.find("$BASE/team-talk/invite#$TOKEN"))
    }

    @Test
    fun `clipboard recognition ignores unrelated and ambiguous text without guessing a UUID`() {
        listOf(
            "", "普通文字", "https://example.test/article", "taskId=$TOKEN", "$BASE/files/$TOKEN",
            "$BASE/invite#bad", "$BASE/invite?token=$TOKEN", "$BASE/invite#${TOKEN}extra",
            "https://user@im.example.test/invite#$TOKEN", "$BASE/invite#$TOKEN\nhttps://other.test/invite#$TOKEN",
            "x".repeat(16_385) + "$BASE/invite#$TOKEN",
        ).forEach { assertNull(GroupInviteLinks.find(it), it) }
    }

    @Test
    fun `recognized foreign invitation keeps its origin for the preview deployment check`() {
        val foreign = "https://other.test/invite#$TOKEN"
        assertEquals(foreign, GroupInviteLinks.find("请加入：$foreign。"))
        assertFailsWith<IllegalArgumentException> { GroupInviteLinks.parse(GroupInviteLinks.find(foreign)!!, BASE) }
    }

    @Test
    fun `complete URL contains the canonical deployment and full token`() {
        val url = GroupInviteLinks.create(" HTTPS://IM.EXAMPLE.TEST:443/ ", TOKEN.uppercase())
        assertEquals("https://im.example.test/invite#$TOKEN", url)
        assertEquals(TOKEN, GroupInviteLinks.parse(url, "https://im.example.test"))
    }

    @Test
    fun `legacy code and pasted surrounding whitespace preserve the same identity`() {
        listOf(TOKEN, TOKEN.uppercase(), " \n$TOKEN\t ").forEach { input ->
            assertEquals(TOKEN, GroupInviteLinks.parse(input, BASE), input)
        }
    }

    @Test
    fun `scheme host default ports and trailing base slash use one deployment identity`() {
        listOf(
            "https://IM.EXAMPLE.TEST:443/invite#${TOKEN.uppercase()}" to "HTTPS://im.example.test/",
            "HTTP://IM.EXAMPLE.TEST:80/invite#$TOKEN" to "http://im.example.test",
            "https://im.example.test:8443/invite#$TOKEN" to "https://IM.EXAMPLE.TEST:8443/",
            "https://IM.EXAMPLE.TEST:443/team-talk/invite#$TOKEN" to "https://im.example.test/team-talk/",
            " https://im.example.test/team-talk/invite#$TOKEN\n" to "https://im.example.test/team-talk",
        ).forEach { (input, base) ->
            assertEquals(TOKEN, GroupInviteLinks.parse(input, base), input)
        }
        assertEquals("$BASE/team-talk/invite#$TOKEN", GroupInviteLinks.create("$BASE/team-talk/", TOKEN))
    }

    @Test
    fun `different scheme host port or base path is rejected before yielding a token`() {
        listOf(
            "http://im.example.test/invite#$TOKEN",
            "https://other.example.test/invite#$TOKEN",
            "https://im.example.test.other.example.test/invite#$TOKEN",
            "https://im.example.test:8443/invite#$TOKEN",
            "$BASE/another/invite#$TOKEN",
            "$BASE/team-talk/../invite#$TOKEN",
        ).forEach { input ->
            assertFailsWith<IllegalArgumentException>(input) { GroupInviteLinks.parse(input, BASE) }
        }
        assertFailsWith<IllegalArgumentException> {
            GroupInviteLinks.parse("$BASE/invite#$TOKEN", "$BASE/team-talk")
        }
        assertFailsWith<IllegalArgumentException> {
            GroupInviteLinks.parse("$BASE/Team-Talk/invite#$TOKEN", "$BASE/team-talk")
        }
    }

    @Test
    fun `malformed links credentials query and noncanonical token encodings are rejected`() {
        listOf(
            "", " ", "1234", "1-1-1-1-1", "x".repeat(2049),
            "$BASE/invite", "$BASE/invite#", "$BASE/invite#not-a-code",
            "$BASE/invite/${TOKEN}", "$BASE/INVITE#$TOKEN", "$BASE/invite/#$TOKEN",
            "$BASE/invite?token=$TOKEN", "$BASE/invite?extra=1#$TOKEN",
            "https://user@im.example.test/invite#$TOKEN",
            "https://im.example.test@other.example.test/invite#$TOKEN",
            "//im.example.test/invite#$TOKEN", "ftp://im.example.test/invite#$TOKEN",
            "https:///invite#$TOKEN", "https://im.example.test\\@other.example.test/invite#$TOKEN",
            "$BASE/invite#%64${TOKEN.drop(1)}", "$BASE/invite#$TOKEN#extra",
            "$BASE/invite#$TOKEN?extra=1", "$BASE/invite#${TOKEN.replace('-', '_')}",
        ).forEach { input ->
            assertFailsWith<IllegalArgumentException>(input) { GroupInviteLinks.parse(input, BASE) }
        }
    }

    @Test
    fun `builder rejects malformed token instead of producing a broken share link`() {
        listOf("", "1-1-1-1-1", "$TOKEN?extra=1", "$TOKEN ").forEach { token ->
            assertFailsWith<IllegalArgumentException> { GroupInviteLinks.create(BASE, token) }
        }
    }

    @Test
    fun `old server receives no preview RPC and reports an actionable upgrade message`() = runTest {
        val cache = newCache()
        try {
            var invoked = false
            val oldServer = object : RpcInvoker {
                override val negotiatedProtocolVersion = com.virjar.tk.protocol.ProtocolVersion(0, 2)
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    invoked = true
                    error("旧服务端不应收到邀请预览 RPC")
                }
            }
            val result = ChatRepository(oldServer, cache).previewInvite(TOKEN)
            val error = assertIs<AppError.Business>(assertIs<Outcome.Failure>(result).error)
            assertEquals(426, error.code)
            assertTrue(error.message.contains("联系管理员升级"))
            assertEquals(false, invoked)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `join publishes the current authorized snapshot instead of its earlier ACK`() = runTest {
        val cache = newCache()
        try {
            val current = CHAT.copy(name = "当前群名", memberCount = 3)
            val calls = mutableListOf<Int>()
            val repository = ChatRepository(invoker { method ->
                calls += method
                when (method) {
                    ChatRpcContract.M_JOIN_BY_INVITE -> response(CHAT)
                    ChatRpcContract.M_GET -> {
                        assertNull(cache.getChat(CHAT.chatId), "加入 ACK 不能先发布旧投影")
                        response(current)
                    }
                    else -> error("unexpected method $method")
                }
            }, cache)
            assertEquals(current, repository.joinByInvite(TOKEN).getOrThrow())
            assertEquals(current, cache.getChat(CHAT.chatId))
            assertEquals(listOf(ChatRpcContract.M_JOIN_BY_INVITE, ChatRpcContract.M_GET), calls)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `removal before delayed join ACK cannot recreate the deleted chat`() = runTest {
        val cache = newCache()
        try {
            cache.upsertChat(CHAT)
            val joining = CompletableDeferred<Unit>()
            val releaseJoin = CompletableDeferred<Unit>()
            val repository = ChatRepository(invoker { method ->
                when (method) {
                    ChatRpcContract.M_JOIN_BY_INVITE -> {
                        joining.complete(Unit)
                        releaseJoin.await()
                        response(CHAT)
                    }
                    ChatRpcContract.M_GET -> {
                        assertNull(cache.getChat(CHAT.chatId), "回读前不能让旧 ACK 复活删除的群")
                        throw RpcStatusException(403, "你已不是群成员")
                    }
                    else -> error("unexpected method $method")
                }
            }, cache)
            val result = async { repository.joinByInvite(TOKEN) }
            joining.await()
            // CHAT_DELETED 的本地应用同样通过 deleteChat 失效投影。
            cache.deleteChat(CHAT.chatId)
            releaseJoin.complete(Unit)
            assertEquals(403, assertIs<AppError.Business>(assertIs<Outcome.Failure>(result.await()).error).code)
            assertNull(cache.getChat(CHAT.chatId))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `removal while the post join snapshot is in flight rejects the old page`() = runTest {
        val cache = newCache()
        try {
            cache.upsertChat(CHAT)
            val reading = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val repository = ChatRepository(invoker { method ->
                when (method) {
                    ChatRpcContract.M_JOIN_BY_INVITE -> response(CHAT)
                    ChatRpcContract.M_GET -> {
                        reading.complete(Unit)
                        releaseRead.await()
                        response(CHAT)
                    }
                    else -> error("unexpected method $method")
                }
            }, cache)
            val result = async { repository.joinByInvite(TOKEN) }
            reading.await()
            cache.deleteChat(CHAT.chatId)
            releaseRead.complete(Unit)
            assertEquals(503, assertIs<AppError.Business>(assertIs<Outcome.Failure>(result.await()).error).code)
            assertNull(cache.getChat(CHAT.chatId))
        } finally {
            cache.close()
        }
    }

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun invoker(respond: suspend (Int) -> ResponsePayload) = object : RpcInvoker {
        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            assertEquals(ChatRpcContract.SERVICE, service)
            return respond(methodId)
        }
    }

    private fun response(chat: Chat) = ResponsePayload(1, 0, ProtoCodec.encode(chat))

    companion object {
        private const val BASE = "https://im.example.test"
        private const val TOKEN = "d11e1122-1234-4567-89ab-123456789abc"
        private val CHAT = Chat("00000000-0000-4000-8000-000000000001", 2, "加入时的群名", memberCount = 2)
    }
}
