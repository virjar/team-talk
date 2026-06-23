package com.virjar.tk.viewmodel

import app.cash.turbine.test
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.protocol.ConversationMethod
import com.virjar.tk.repository.ConversationRepository
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads conversations from local cache`() = runTest(testDispatcher) {
        val cache = FakeLocalCache().apply {
            upsertConversation(Conversation(chatId = "c1", chatType = 1, chatName = "Test", unreadCount = 0))
        }
        val rpc = FakeRpcInvoker().apply { enqueueOk() } // init 中 refresh 的响应
        val repo = ConversationRepository(rpc, cache)

        val vm = ConversationViewModel(cache, repo)
        advanceUntilIdle()

        assertEquals(1, vm.conversations.value.size)
        assertEquals("c1", vm.conversations.value[0].chatId)
    }

    @Test
    fun `init triggers listConversations RPC to sync with server`() = runTest(testDispatcher) {
        val cache = FakeLocalCache()
        val rpc = FakeRpcInvoker().apply { enqueueOk() }
        val repo = ConversationRepository(rpc, cache)

        ConversationViewModel(cache, repo)
        advanceUntilIdle()

        // 验证 init 时调用了 LIST RPC
        assertEquals(1, rpc.calls.size)
        assertEquals(ServiceId.CONVERSATION, rpc.calls[0].first)
        assertEquals(ConversationMethod.LIST.id, rpc.calls[0].second)
    }

    @Test
    fun `conversations StateFlow emits updates from LocalCache`() = runTest(testDispatcher) {
        val cache = FakeLocalCache()
        val rpc = FakeRpcInvoker().apply { enqueueOk() }
        val repo = ConversationRepository(rpc, cache)

        val vm = ConversationViewModel(cache, repo)
        advanceUntilIdle()

        vm.conversations.test {
            // 初始为空
            assertEquals(0, awaitItem().size)

            // LocalCache 更新后触发 Flow emit
            cache.upsertConversation(Conversation(chatId = "c2", chatType = 1, chatName = "New", unreadCount = 0))
            assertEquals(1, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
