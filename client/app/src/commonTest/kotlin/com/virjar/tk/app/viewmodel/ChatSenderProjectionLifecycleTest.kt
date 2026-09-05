package com.virjar.tk.app.viewmodel

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSenderProjectionLifecycleTest {
    @Test
    fun `window replacement releases departed senders and retirement cannot be reopened`() = runTest {
        val alice = MutableStateFlow<User?>(user("alice"))
        val bob = MutableStateFlow<User?>(user("bob"))
        val sources = mapOf("alice" to alice, "bob" to bob)
        val projections = ChatSenderProjections(backgroundScope) { sources.getValue(it) }

        projections.bind(listOf(message("alice"), message("alice"), message("bob")))
        runCurrent()
        assertEquals(setOf("alice", "bob"), projections.users.value.keys)
        assertEquals(1, alice.subscriptionCount.value, "same sender shares one subscription")

        projections.bind(listOf(message("bob")))
        runCurrent()
        alice.value = user("alice").copy(name = "迟到更新")
        bob.value = user("bob").copy(name = "新名称")
        runCurrent()
        assertEquals(mapOf("bob" to bob.value), projections.users.value)
        assertEquals(0, alice.subscriptionCount.value)

        projections.close()
        projections.close()
        projections.bind(listOf(message("alice")))
        runCurrent()
        assertTrue(projections.users.value.isEmpty())
        assertEquals(0, alice.subscriptionCount.value)
        assertEquals(0, bob.subscriptionCount.value)
    }

    @Test
    fun `retirement from an immediate window observer cancels unpublished subscriptions`() = runTest {
        val alice = MutableStateFlow<User?>(user("alice"))
        val observedUsers = mutableListOf<String>()
        val projections = ChatSenderProjections(backgroundScope) { uid ->
            observedUsers += uid
            alice
        }
        projections.bind(listOf(message("alice")))
        runCurrent()

        // 状态观察者可在 bind 发布空窗口的调用栈上直接关闭页面。
        // 尚未启动的新订阅也必须属于同一次关闭，不能在其返回后遗留。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            projections.users.collect { users ->
                if (users.isEmpty()) projections.close()
            }
        }
        projections.bind(listOf(message("bob")))
        runCurrent()

        assertTrue(projections.users.value.isEmpty())
        assertEquals(listOf("alice"), observedUsers)
        assertEquals(0, alice.subscriptionCount.value)
    }

    private fun user(uid: String) = User(uid = uid, username = uid, name = uid)

    private fun message(uid: String) = Message(
        chatId = "chat",
        clientMsgId = "message-$uid",
        senderUid = uid,
        messageType = 1,
        timestamp = 1L,
    )
}
