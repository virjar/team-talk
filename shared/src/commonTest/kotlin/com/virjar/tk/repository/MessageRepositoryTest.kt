package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MessageRepository 单元测试。
 * 验证 Outcome 错误模型（Phase B）：
 * - 成功 → Success + 写入 LocalCache
 * - 服务端失败 → Failure(Business)，**不静默回退缓存**
 * - 网络异常 → Failure(Network)
 * - 认证失效 → Failure(AuthExpired)
 */
class MessageRepositoryTest {
    private val chatId = "chat-1"
    private val sampleMsg = Message(
        chatId = chatId,
        clientMsgId = "cmsg-1",
        serverSeq = 100L,
        senderUid = "user-1",
        messageType = 1,
        timestamp = 1000L,
    )

    @Test
    fun `getHistory success returns messages and writes to cache`() {
        val rpc = FakeRpcInvoker()
        val cache = FakeLocalCache()
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        val encoded = ProtoCodec.encodeList(listOf(sampleMsg))
        rpc.enqueueOk(encoded)

        val outcome = kotlinx.coroutines.runBlocking {
            repo.getHistory(chatId)
        }

        assertIs<Outcome.Success<List<Message>>>(outcome)
        assertEquals(1, outcome.value.size)
        assertEquals("cmsg-1", outcome.value[0].clientMsgId)
        // 验证写入 LocalCache
        assertEquals(1, cache.getMessages(chatId).size)
        // 验证调用了正确的 RPC 方法
        assertEquals(1, rpc.calls.size)
        assertEquals(ServiceId.MESSAGE, rpc.calls[0].first)
        assertEquals(MessageMethod.GET_HISTORY.id, rpc.calls[0].second)
    }

    @Test
    fun `getHistory business error does NOT silently fallback to cache`() {
        val rpc = FakeRpcInvoker()
        val cache = FakeLocalCache().apply { insertMessage(sampleMsg) } // 预置缓存
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        rpc.enqueueError(500, "Internal error")

        val outcome = kotlinx.coroutines.runBlocking {
            repo.getHistory(chatId)
        }

        // Phase B 核心改动：不静默回退缓存，返回 Failure
        assertIs<Outcome.Failure>(outcome)
        val err = outcome.error
        assertIs<AppError.Business>(err)
        assertEquals(500, err.code)
        // 缓存数据仍然存在（未被清空），但 Repository 不自动返回它
        assertEquals(1, cache.getMessages(chatId).size)
    }

    @Test
    fun `getHistory auth expired returns AuthExpired failure`() {
        val rpc = FakeRpcInvoker()
        val cache = FakeLocalCache()
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        rpc.enqueueError(401, "token expired")

        val outcome = kotlinx.coroutines.runBlocking {
            repo.getHistory(chatId)
        }

        assertIs<Outcome.Failure>(outcome)
        assertIs<AppError.AuthExpired>(outcome.error)
    }

    @Test
    fun `getHistory network exception returns Network failure`() {
        val rpc = FakeRpcInvoker().apply {
            throwOnInvoke = IllegalStateException("Not connected")
        }
        val cache = FakeLocalCache()
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        val outcome = kotlinx.coroutines.runBlocking {
            repo.getHistory(chatId)
        }

        assertIs<Outcome.Failure>(outcome)
        assertIs<AppError.Network>(outcome.error)
    }

    @Test
    fun `getHistory timeout status returns Timeout failure`() {
        val rpc = FakeRpcInvoker()
        val cache = FakeLocalCache()
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        rpc.enqueueError(504, "Request timeout")

        val outcome = kotlinx.coroutines.runBlocking {
            repo.getHistory(chatId)
        }

        assertIs<Outcome.Failure>(outcome)
        assertIs<AppError.Timeout>(outcome.error)
    }

    @Test
    fun `revokeMessage success returns Success Unit`() {
        val rpc = FakeRpcInvoker()
        val cache = FakeLocalCache()
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        rpc.enqueueOk()

        val outcome = kotlinx.coroutines.runBlocking {
            repo.revokeMessage(chatId, 100L)
        }

        assertIs<Outcome.Success<Unit>>(outcome)
        assertEquals(MessageMethod.REVOKE.id, rpc.calls[0].second)
    }

    @Test
    fun `recover falls back to cache when network fails`() {
        // 验证调用方可以主动降级（Phase B 设计目标）
        val rpc = FakeRpcInvoker().apply {
            throwOnInvoke = IllegalStateException("Not connected")
        }
        val cache = FakeLocalCache().apply { insertMessage(sampleMsg) }
        val repo = MessageRepository(rpc, cache, com.virjar.tk.client.MessageSender { throw IllegalStateException("not used") })

        val result = kotlinx.coroutines.runBlocking {
            repo.getHistory(chatId).recover { cache.getMessages(chatId) }
        }

        // 调用方通过 recover 主动降级到缓存
        assertEquals(1, result.size)
        assertEquals("cmsg-1", result[0].clientMsgId)
    }
}
