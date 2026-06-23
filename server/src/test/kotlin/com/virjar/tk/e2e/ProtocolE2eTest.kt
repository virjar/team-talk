package com.virjar.tk.e2e

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.RpcClient
import com.virjar.tk.model.*
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtocolE2eTest {

    private lateinit var env: TcpE2eEnvironment
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeAll
    fun setup() {
        env = TcpE2eEnvironment()
    }

    @AfterAll
    fun teardown() {
        env.close()
        testScope.cancel()
    }

    // ── 辅助方法 ──

    /**
     * 轻量会话包装，复用真实 ImClient + RpcClient。
     */
    private class E2eSession(
        val imClient: ImClient,
        val rpc: RpcClient,
        val userSession: com.virjar.tk.client.UserSession,
    ) {
        private val notifyBuffer = mutableListOf<NotifyPayload>()
        private var collectJob: Job? = null

        val uid: String get() = userSession.uid

        fun startCollecting(scope: CoroutineScope) {
            collectJob = scope.launch {
                imClient.packets.collect { proto ->
                    if (proto is NotifyPayload) {
                        synchronized(notifyBuffer) { notifyBuffer.add(proto) }
                    }
                }
            }
        }

        suspend fun invoke(serviceId: ServiceId, methodId: Int, payload: ByteArray? = null): ResponsePayload =
            rpc.invoke(serviceId, methodId, payload)

        fun subscribe(chatId: String, lastSeq: Long = 0) {
            imClient.send(SubscribePayload(chatId, lastSeq))
        }

        suspend fun awaitNotify(notifyType: Int? = null, timeoutMs: Long = 5000): NotifyPayload =
            withTimeout(timeoutMs) {
                var found: NotifyPayload? = null
                while (found == null) {
                    found = synchronized(notifyBuffer) {
                        notifyBuffer.firstOrNull { notifyType == null || it.notifyType == notifyType }
                            ?.also { notifyBuffer.remove(it) }
                    }
                    if (found == null) delay(50)
                }
                found
            }

        fun close() {
            collectJob?.cancel()
            rpc.stop()
            // E2E 测试会话是一次性的，彻底销毁线程资源
            imClient.destroy()
        }
    }

    private suspend fun createSession(): E2eSession {
        val userSession = com.virjar.tk.client.UserSession()
        val imClient = ImClient(onAuthResult = { success, uid, username, name, refreshToken, failureReason ->
            if (success) userSession.onAuthSuccess(uid ?: "", username, name, refreshToken)
            else userSession.onAuthFailed(failureReason)
        })
        imClient.connect("127.0.0.1", env.tcpPort)
        withTimeout(5000) { imClient.state.first { it == ConnectionState.CONNECTED } }

        val rpc = RpcClient(imClient)
        rpc.start()

        val session = E2eSession(imClient, rpc, userSession)
        session.startCollecting(testScope)
        return session
    }

    private suspend fun registerUser(suffix: String): E2eSession {
        val session = createSession()
        session.imClient.register("e2e-$suffix", "password123", "User $suffix", "test-device", "TestDevice")
        withTimeout(5000) { session.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        return session
    }

    // ── 认证流程 ──

    @Test
    fun `register via TCP and receive uid`() = runBlocking {
        val session = createSession()
        session.imClient.register("e2e-reg-${UUID.randomUUID()}", "pass123", "TestUser", "dev1", "TestDevice")
        withTimeout(5000) { session.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        assertTrue(session.userSession.uid.isNotEmpty())
        session.close()
    }

    @Test
    fun `login via TCP after register`() = runBlocking {
        val username = "e2e-login-${UUID.randomUUID()}"
        // 先注册
        val regSession = createSession()
        regSession.imClient.register(username, "pass123", "TestUser", "dev1", "TestDevice")
        withTimeout(5000) { regSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val uid = regSession.userSession.uid
        regSession.close()

        // 再登录
        val loginSession = createSession()
        loginSession.imClient.login(username, "pass123", "dev2", "TestDevice")
        withTimeout(5000) { loginSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        assertEquals(uid, loginSession.userSession.uid)
        loginSession.close()
    }

    @Test
    fun `login rejects wrong password`() = runBlocking {
        val username = "e2e-wrongpw-${UUID.randomUUID()}"
        val regSession = createSession()
        regSession.imClient.register(username, "pass123", "TestUser", "dev1", "TestDevice")
        withTimeout(5000) { regSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        regSession.close()

        val loginSession = createSession()
        loginSession.imClient.login(username, "wrong_password", "dev2", "TestDevice")
        withTimeout(5000) { loginSession.imClient.state.first { it == ConnectionState.AUTH_FAILED } }
        loginSession.close()
    }

    // ── RPC 调用 ──

    @Test
    fun `get own profile via RPC`() = runBlocking {
        val session = registerUser("profile-${UUID.randomUUID()}")
        val resp = session.invoke(ServiceId.USER, UserMethod.GET_PROFILE.id,
            ProtoCodec.encodePayload { writeString(null) })
        assertEquals(0, resp.status)
        val user = ProtoCodec.decode(User, resp.payload!!)
        assertEquals(session.uid, user.uid)
        session.close()
    }

    @Test
    fun `update profile via RPC`() = runBlocking {
        val session = registerUser("update-${UUID.randomUUID()}")
        val updatedUser = User("", "", "NewName", null, null, 1)
        val resp = session.invoke(ServiceId.USER, UserMethod.UPDATE_PROFILE.id,
            ProtoCodec.encode(updatedUser))
        assertEquals(0, resp.status, "Update profile should succeed")

        // 验证更新
        val getResp = session.invoke(ServiceId.USER, UserMethod.GET_PROFILE.id,
            ProtoCodec.encodePayload { writeString(null) })
        val user = ProtoCodec.decode(User, getResp.payload!!)
        assertEquals("NewName", user.name)
        session.close()
    }

    @Test
    fun `contact apply and accept via RPC`() = runBlocking {
        val user1 = registerUser("contact1-${UUID.randomUUID()}")
        val user2 = registerUser("contact2-${UUID.randomUUID()}")

        // user1 申请加 user2 为好友
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hello") })
        assertEquals(0, applyResp.status)
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        assertNotNull(apply.token)

        // user2 接受
        val acceptResp = user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })
        assertEquals(0, acceptResp.status)

        // 验证好友列表（最多重试 3 次，应对 CI runner 时序差异）
        var friends: List<Contact> = emptyList()
        repeat(3) { attempt ->
            val listResp = user1.invoke(ServiceId.CONTACT, ContactMethod.LIST.id)
            assertEquals(0, listResp.status, "Contact LIST failed on attempt $attempt")
            friends = ProtoCodec.decodeList(Contact, listResp.payload!!)
            if (friends.any { it.friendUid == user2.uid }) return@repeat
            if (attempt < 2) delay(200)
        }
        assertTrue(friends.any { it.friendUid == user2.uid }, "user2 not found in friends list")

        user1.close()
        user2.close()
    }

    @Test
    fun `create personal chat via RPC`() = runBlocking {
        val user1 = registerUser("pchat1-${UUID.randomUUID()}")
        val user2 = registerUser("pchat2-${UUID.randomUUID()}")

        // 先成为好友
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })

        // 创建私聊（等待 accept 生效）
        delay(100)
        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_PERSONAL.id,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        assertEquals(0, chatResp.status)
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)
        assertEquals(1, chat.chatType) // personal
        assertNotNull(chat.chatId)

        user1.close()
        user2.close()
    }

    @Test
    fun `create group chat via RPC`() = runBlocking {
        val user1 = registerUser("grp1-${UUID.randomUUID()}")
        val user2 = registerUser("grp2-${UUID.randomUUID()}")

        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_GROUP.id,
            ProtoCodec.encodePayload {
                writeString("TestGroup")
                writeString(null) // avatar
                writeVarInt(1)    // member count
                writeString(user2.uid)
            })
        assertEquals(0, chatResp.status)
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)
        assertEquals(2, chat.chatType) // group
        assertEquals("TestGroup", chat.name)

        user1.close()
        user2.close()
    }

    @Test
    fun `list devices via RPC`() = runBlocking {
        val session = registerUser("device-${UUID.randomUUID()}")
        val resp = session.invoke(ServiceId.DEVICE, DeviceMethod.LIST.id)
        assertEquals(0, resp.status)
        session.close()
    }

    @Test
    fun `list conversations via RPC`() = runBlocking {
        val session = registerUser("conv-${UUID.randomUUID()}")
        val resp = session.invoke(ServiceId.CONVERSATION, ConversationMethod.LIST.id)
        assertEquals(0, resp.status)
        session.close()
    }

    // ── 消息投递 ──

    @Test
    fun `send message and receive ack`() = runBlocking {
        val user1 = registerUser("msg1-${UUID.randomUUID()}")
        val user2 = registerUser("msg2-${UUID.randomUUID()}")

        // 建立好友关系 + 创建私聊
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })

        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_PERSONAL.id,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        // 发送消息
        val msg = com.virjar.tk.model.Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.TEXT.code,
            timestamp = System.currentTimeMillis(),
            senderUid = "",
            body = com.virjar.tk.body.TextBody("Hello E2E"),
        )
        val ack = user1.imClient.sendAndWaitAck(msg)
        assertEquals(0, ack.code, "Message ACK code should be OK: ${ack.reason}")
        assertTrue(ack.serverSeq > 0, "Server seq should be positive")

        user1.close()
        user2.close()
    }

    @Test
    fun `message delivered to other user`() = runBlocking {
        val user1 = registerUser("deliver1-${UUID.randomUUID()}")
        val user2 = registerUser("deliver2-${UUID.randomUUID()}")

        // 建立好友关系 + 创建私聊
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })

        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_PERSONAL.id,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        // user1 发消息
        val msg = com.virjar.tk.model.Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.TEXT.code,
            timestamp = System.currentTimeMillis(),
            senderUid = "",
            body = com.virjar.tk.body.TextBody("Hello from user1"),
        )
        val ack = user1.imClient.sendAndWaitAck(msg)
        assertEquals(0, ack.code)

        // user2 应收到消息通知
        val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 5000)
        assertEquals(NotifyType.MESSAGE_RECV.code, notify.notifyType)

        user1.close()
        user2.close()
    }

    // ── 订阅与历史 ──

    @Test
    fun `subscribe delivers history`() = runBlocking {
        val user1 = registerUser("sub1-${UUID.randomUUID()}")
        val user2 = registerUser("sub2-${UUID.randomUUID()}")

        // 建立好友关系 + 创建私聊
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })

        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_PERSONAL.id,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        // user1 发两条消息
        for (i in 1..2) {
            val msg = com.virjar.tk.model.Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = com.virjar.tk.body.TextBody("msg$i"),
            )
            user1.imClient.sendAndWaitAck(msg)
        }

        // user2 订阅，应收到历史消息
        user2.subscribe(chat.chatId, 0)
        val notify1 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 5000)
        assertNotNull(notify1)
        val notify2 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 5000)
        assertNotNull(notify2)

        user1.close()
        user2.close()
    }
}
