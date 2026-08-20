package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationRepositoryTest {
    @Test
    fun `authoritative snapshot removes stale conversation and pending draft but preserves chat cache`() = runBlocking {
        val cache = FakeLocalCache().apply {
            upsertChat(Chat(chatId = "stale", chatType = 2, name = "still-safe-to-cache"))
            upsertChat(Chat(chatId = "profile-only", chatType = 1, name = "profile"))
            upsertConversation(Conversation(chatId = "stale", chatType = 2, draft = "unsent"))
            setConversationDraft("stale", "pending")
        }
        val rpc = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encodeList(listOf(Conversation(chatId = "real", chatType = 2))))
        }
        val repository = ConversationRepository(rpc, cache)

        val result = repository.listConversations()

        assertIs<Outcome.Success<List<Conversation>>>(result)
        assertEquals(listOf("real"), cache.getConversations().map(Conversation::chatId))
        assertTrue(cache.getPendingConversationDrafts().isEmpty(), "snapshot removal must clear draft outbox")
        assertNotNull(cache.getChat("stale"), "conversation omission alone does not prove lost membership")
        assertNotNull(cache.getChat("profile-only"), "profile-only chat cache must not be deleted")
        Unit
    }

    @Test
    fun `late snapshot cannot remove conversation created while rpc is in flight`() = runBlocking {
        val existing = Conversation(chatId = "existing", chatType = 2)
        val cache = FakeLocalCache().apply { upsertConversation(existing) }
        val created = Conversation(chatId = "just-created", chatType = 2)
        val rpc = BlockingConversationListRpc(
            firstConversations = listOf(existing),
            retryConversations = listOf(existing, created),
        )
        val repository = ConversationRepository(rpc, cache)

        val request = async { repository.listConversations() }
        rpc.started.await()
        cache.upsertConversation(created)
        rpc.release.complete(Unit)

        val result = assertIs<Outcome.Success<List<Conversation>>>(request.await())
        val expectedIds = setOf("existing", "just-created")
        assertEquals(
            expectedIds,
            cache.getConversations().map(Conversation::chatId).toSet(),
        )
        assertEquals(expectedIds, result.value.map(Conversation::chatId).toSet())
        assertEquals(2, rpc.invocationCount, "conflicted snapshot must be fetched again")
    }

    @Test
    fun `chat created notify forces a second snapshot even before a conversation row exists`() = runBlocking {
        val created = Conversation(chatId = "new-chat", chatType = 2)
        val cache = FakeLocalCache()
        val rpc = BlockingConversationListRpc(
            firstConversations = emptyList(),
            retryConversations = listOf(created),
        )
        val repository = ConversationRepository(rpc, cache)

        val request = async { repository.listConversations() }
        rpc.started.await()
        // EventProcessor 在 CHAT_CREATED 阶段先提交 Chat，Conversation 要到 READY 后刷新。
        cache.upsertChat(Chat(chatId = created.chatId, chatType = 2, name = "new group"))
        rpc.release.complete(Unit)

        val result = assertIs<Outcome.Success<List<Conversation>>>(request.await())
        assertEquals(listOf(created.chatId), cache.getConversations().map(Conversation::chatId))
        assertEquals(listOf(created.chatId), result.value.map(Conversation::chatId))
        assertEquals(2, rpc.invocationCount)
    }

    @Test
    fun `late snapshot cannot resurrect conversation deleted while rpc is in flight`() = runBlocking {
        val stale = Conversation(chatId = "just-deleted", chatType = 2)
        val cache = FakeLocalCache().apply { upsertConversation(stale) }
        val rpc = BlockingConversationListRpc(
            firstConversations = listOf(stale),
            retryConversations = emptyList(),
        )
        val repository = ConversationRepository(rpc, cache)

        val request = async { repository.listConversations() }
        rpc.started.await()
        cache.deleteConversation(stale.chatId)
        rpc.release.complete(Unit)

        val result = assertIs<Outcome.Success<List<Conversation>>>(request.await())
        assertFalse(cache.getConversations().any { it.chatId == stale.chatId })
        assertFalse(result.value.any { it.chatId == stale.chatId })
        assertEquals(2, rpc.invocationCount)
    }

    @Test
    fun `continuously conflicted snapshots fail after a bounded number of retries`() = runBlocking {
        lateinit var cache: FakeLocalCache
        var calls = 0
        val rpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                calls += 1
                cache.upsertConversation(Conversation(chatId = "notify-$calls", chatType = 2))
                return ResponsePayload(
                    requestId = calls,
                    status = 0,
                    payload = ProtoCodec.encodeList(emptyList<Conversation>()),
                )
            }
        }
        cache = FakeLocalCache()
        val repository = ConversationRepository(rpc, cache)

        val result = repository.listConversations()

        assertIs<Outcome.Failure>(result)
        assertEquals(3, calls)
        assertEquals(listOf("notify-3"), cache.getConversations().map(Conversation::chatId))
    }

    @Test
    fun `最终清空在远端请求完成前已同步写入本地`() = runBlocking {
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val rpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                remoteStarted.complete(Unit)
                releaseRemote.await()
                return ResponsePayload(requestId = 1, status = 0, payload = null)
            }
        }
        val cache = FakeLocalCache().apply {
            upsertConversation(Conversation(chatId = "chat-1", chatType = 1, draft = "待清空"))
        }
        val repository = ConversationRepository(rpc, cache)

        val clearDraft = launch { repository.setDraft("chat-1", null) }
        remoteStarted.await()
        assertEquals(null, cache.getConversations().single().draft)

        releaseRemote.complete(Unit)
        clearDraft.join()
    }

    @Test
    fun `取消已发送草稿请求也不会让后续清空请求越过它`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val calls = mutableListOf<ByteArray?>()
        val rpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                calls += payload
                if (calls.size == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                } else {
                    secondStarted.complete(Unit)
                }
                return ResponsePayload(requestId = calls.size, status = 0, payload = null)
            }
        }
        val cache = FakeLocalCache()
        val repository = ConversationRepository(rpc, cache)

        val oldGeneration = repository.setDraftLocal("chat-1", "尚未发送的旧草稿")
        val oldDraft = launch { repository.mirrorDraft("chat-1", oldGeneration) }
        firstStarted.await()
        oldDraft.cancel()
        val clearGeneration = repository.setDraftLocal("chat-1", null)
        val clearDraft = launch { repository.mirrorDraft("chat-1", clearGeneration) }
        yield()
        assertEquals(1, calls.size, "清空请求不得越过已发出的旧草稿请求")

        releaseFirst.complete(Unit)
        oldDraft.cancelAndJoin()
        secondStarted.await()
        clearDraft.join()
        assertEquals(2, calls.size)
    }

    @Test
    fun `启动恢复会重试持久化 null tombstone 并按 generation 确认`() = runBlocking {
        val calls = mutableListOf<ByteArray?>()
        val rpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                calls += payload
                return ResponsePayload(requestId = calls.size, status = 0, payload = null)
            }
        }
        val cache = FakeLocalCache().apply {
            upsertConversation(Conversation(chatId = "chat-1", chatType = 1, draft = "已发送正文"))
            setConversationDraft("chat-1", null)
        }
        val repository = ConversationRepository(rpc, cache)

        val result = repository.retryPendingDrafts()

        assertTrue(result is com.virjar.tk.Outcome.Success)
        assertEquals(1, calls.size)
        assertTrue(cache.getPendingConversationDrafts().isEmpty(), "RPC 成功后应进入 ACKED，不再重试")
        // 尚未看到匹配快照前，旧服务端值仍不能复活。
        cache.upsertConversation(Conversation(chatId = "chat-1", chatType = 1, draft = "已发送正文"))
        assertEquals(null, cache.getConversations().single().draft)
    }

    @Test
    fun `服务端匹配事件先于 RPC ACK 到达时仓库仍能收敛`() = runBlocking {
        lateinit var cache: FakeLocalCache
        val rpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                cache.upsertConversation(Conversation(chatId = "chat-1", chatType = 1, draft = null))
                return ResponsePayload(requestId = 1, status = 0, payload = null)
            }
        }
        cache = FakeLocalCache().apply {
            upsertConversation(Conversation(chatId = "chat-1", chatType = 1, draft = "待清空"))
        }
        val repository = ConversationRepository(rpc, cache)

        val result = repository.setDraft("chat-1", null)

        assertTrue(result is com.virjar.tk.Outcome.Success)
        assertTrue(cache.getPendingConversationDrafts().isEmpty())
        cache.upsertConversation(Conversation(chatId = "chat-1", chatType = 1, draft = "跨设备新草稿"))
        assertEquals("跨设备新草稿", cache.getConversations().single().draft)
    }

    private class BlockingConversationListRpc(
        private val firstConversations: List<Conversation>,
        private val retryConversations: List<Conversation> = firstConversations,
    ) : RpcInvoker {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var invocationCount = 0
            private set

        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            invocationCount += 1
            val response = if (invocationCount == 1) {
                started.complete(Unit)
                release.await()
                firstConversations
            } else {
                retryConversations
            }
            return ResponsePayload(
                requestId = invocationCount,
                status = 0,
                payload = ProtoCodec.encodeList(response),
            )
        }
    }
}
