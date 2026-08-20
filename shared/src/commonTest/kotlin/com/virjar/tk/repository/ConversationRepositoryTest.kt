package com.virjar.tk.repository

import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.testing.FakeLocalCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationRepositoryTest {
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
}
