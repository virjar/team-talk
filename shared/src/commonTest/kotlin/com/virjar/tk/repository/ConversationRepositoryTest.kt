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
        val repository = ConversationRepository(rpc, FakeLocalCache())

        val oldDraft = launch { repository.mirrorDraft("chat-1", "尚未发送的旧草稿") }
        firstStarted.await()
        oldDraft.cancel()
        val clearDraft = launch { repository.mirrorDraft("chat-1", null) }
        yield()
        assertEquals(1, calls.size, "清空请求不得越过已发出的旧草稿请求")

        releaseFirst.complete(Unit)
        oldDraft.cancelAndJoin()
        secondStarted.await()
        clearDraft.join()
        assertEquals(2, calls.size)
    }
}
