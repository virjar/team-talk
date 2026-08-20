package com.virjar.tk.viewmodel

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.repository.ContactRepository
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ContactViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `apply exposes repository business failure instead of reporting optimistic success`() = runTest(testDispatcher) {
        val (viewModel, rpc) = createViewModel()
        advanceUntilIdle()
        rpc.enqueueError(status = 409, msg = "黑名单关系下不能发起好友申请")

        viewModel.apply("u2")
        advanceUntilIdle()

        assertEquals("申请好友失败: 黑名单关系下不能发起好友申请", viewModel.error.value)
    }

    @Test
    fun `delete failure keeps local contact and is observable`() = runTest(testDispatcher) {
        val cache = FakeLocalCache().apply {
            upsertContact(Contact(uid = "me", friendUid = "u2"))
        }
        val (viewModel, rpc) = createViewModel(cache)
        advanceUntilIdle()
        rpc.enqueueError(status = 503, msg = "服务暂时不可用")

        viewModel.deleteFriend("u2")
        advanceUntilIdle()

        assertEquals("删除好友失败: 服务暂时不可用", viewModel.error.value)
        assertEquals(listOf("u2"), cache.getContacts().map { it.friendUid })
    }

    @Test
    fun `remark failure is observable`() = runTest(testDispatcher) {
        val (viewModel, rpc) = createViewModel()
        advanceUntilIdle()
        rpc.enqueueError(status = 500, msg = "备注保存失败")

        viewModel.updateRemark("u2", "同事")
        advanceUntilIdle()

        assertEquals("修改备注失败: 备注保存失败", viewModel.error.value)
    }

    private fun createViewModel(
        cache: FakeLocalCache = FakeLocalCache(),
    ): Pair<ContactViewModel, FakeRpcInvoker> {
        val rpc = FakeRpcInvoker().apply {
            // The initial refresh is authoritative. Mirror the seeded cache in the
            // server snapshot so individual action tests do not accidentally test
            // stale-contact reconciliation instead.
            enqueueOk(ProtoCodec.encodeList(cache.getContacts()))
            enqueueOk(ProtoCodec.encodeList(emptyList<ContactApply>()))
        }
        val repository = ContactRepository(rpc, cache)
        return ContactViewModel(
            localCache = cache,
            contactRepo = repository,
            myUid = "me",
            dispatcher = testDispatcher,
        ) to rpc
    }
}
