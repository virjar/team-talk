package com.virjar.tk.server.e2e

import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.AuthRpcContract
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import com.virjar.tk.protocol.rpc.gen.ConversationRpcContract
import com.virjar.tk.protocol.rpc.gen.DeviceRpcContract
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.RpcClient
import com.virjar.tk.protocol.model.*
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
        val userSession: com.virjar.tk.shared.client.UserSession,
        private val eventProjection: E2eEventProjection,
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

        suspend fun invoke(serviceId: String, methodId: Int, payload: ByteArray? = null): ResponsePayload =
            rpc.invoke(serviceId, methodId, payload)

        suspend fun pendingApplyToken(fromUid: String): String {
            val response = invoke("contact", ContactRpcContract.M_LIST_PENDING_APPLIES)
            require(response.status == 0 && response.payload != null) { "无法读取待处理好友申请" }
            return ProtoCodec.decodeList(ContactApply, response.payload!!)
                .single { it.fromUid == fromUid && it.status == 0 }
                .token ?: error("待处理申请缺少收件人 token")
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
            eventProjection.close()
            // E2E 测试会话是一次性的，彻底销毁线程资源
            imClient.destroy()
        }
    }

    private suspend fun createSession(): E2eSession {
        val userSession = com.virjar.tk.shared.client.UserSession()
        val imClient = ImClient(onAuthResult = {
                success, uid, username, name, refreshToken, accessToken, datasetId, failureReason ->
            if (success) {
                val authoritativeDatasetId = requireNotNull(datasetId) {
                    "Successful local AUTH omitted datasetId"
                }
                check(authoritativeDatasetId == env.syncDatasetId) {
                    "AUTH dataset differs from the E2E server authority"
                }
                userSession.onAuthSuccess(
                    uid ?: "", username, name, refreshToken, accessToken, authoritativeDatasetId,
                )
            }
            else userSession.onAuthFailed(failureReason)
        })
        val eventProjection = imClient.installE2eEventProjection(env.syncDatasetId)
        imClient.connect("127.0.0.1", env.tcpPort)
        withTimeout(5000) { imClient.state.first { it == ConnectionState.CONNECTED } }

        val rpc = RpcClient(imClient)
        rpc.start()

        val session = E2eSession(imClient, rpc, userSession, eventProjection)
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

    @Test
    fun `organization admin mutation broadcasts binary revision to every sync ready session`() = runBlocking {
        val first = registerUser("org-a-${UUID.randomUUID().toString().take(12)}")
        val second = registerUser("org-b-${UUID.randomUUID().toString().take(12)}")
        try {
            val existingRoot = env.adminService.listOrganizationUnits().singleOrNull { it.parentId == null }
            env.adminService.createOrganizationUnit(
                parentId = existingRoot?.unitId,
                name = "Organization notify ${UUID.randomUUID()}",
                leaderUid = null,
                sortOrder = 0,
                enableGroup = false,
            )

            val firstPayload = ProtoCodec.decode(
                OrganizationChangedPayload,
                requireNotNull(first.awaitNotify(NotifyType.ORGANIZATION_CHANGED.code).payload),
            )
            val secondPayload = ProtoCodec.decode(
                OrganizationChangedPayload,
                requireNotNull(second.awaitNotify(NotifyType.ORGANIZATION_CHANGED.code).payload),
            )

            assertTrue(firstPayload.revision > 0L)
            assertEquals(firstPayload, secondPayload)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `password change returns success before closing the rotated session`() = runBlocking {
        val username = "e2e-password-${UUID.randomUUID()}"
        val session = createSession()
        session.imClient.register(username, "oldpass123", "Password User", "password-device", "TestDevice")
        withTimeout(5_000) { session.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val oldAccess = requireNotNull(session.userSession.accessToken)
        val sibling = createSession()
        sibling.imClient.login(username, "oldpass123", "password-sibling-device", "SiblingDevice")
        withTimeout(5_000) { sibling.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val siblingAccess = requireNotNull(sibling.userSession.accessToken)

        val response = session.invoke(
            AuthRpcContract.SERVICE,
            AuthRpcContract.M_UPDATE_PASSWORD,
            ProtoCodec.encodePayload {
                writeString("oldpass123")
                writeString("newpass123")
            },
        )

        assertEquals(0, response.status, "the committed password change must flush its RPC response")
        withTimeout(10_000) {
            session.imClient.state.first {
                it == ConnectionState.DISCONNECTED || it == ConnectionState.AUTH_FAILED
            }
        }
        withTimeout(10_000) {
            sibling.imClient.state.first {
                it == ConnectionState.DISCONNECTED || it == ConnectionState.AUTH_FAILED
            }
        }
        assertNull(env.accessTokenValidator.validateAccessToken(oldAccess))
        assertNull(env.accessTokenValidator.validateAccessToken(siblingAccess))
        session.close()
        sibling.close()

        val oldPasswordSession = createSession()
        oldPasswordSession.imClient.login(username, "oldpass123", "password-device", "TestDevice")
        withTimeout(5_000) { oldPasswordSession.imClient.state.first { it == ConnectionState.AUTH_FAILED } }
        oldPasswordSession.close()

        val newPasswordSession = createSession()
        newPasswordSession.imClient.login(username, "newpass123", "password-device", "TestDevice")
        withTimeout(5_000) { newPasswordSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        newPasswordSession.close()
    }

    @Test
    fun `same-device credential rotation fences the old live connection before new auth succeeds`() = runBlocking {
        val username = "e2e-device-rotate-${UUID.randomUUID().toString().take(8)}"
        val first = createSession()
        first.imClient.register(username, "password123", "Device Rotation", "stable-device", "Device")
        withTimeout(5_000) { first.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val oldAccess = requireNotNull(first.userSession.accessToken)

        val second = createSession()
        second.imClient.login(username, "password123", "stable-device", "Device")
        withTimeout(5_000) { second.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        withTimeout(10_000) {
            first.imClient.state.first {
                it == ConnectionState.DISCONNECTED || it == ConnectionState.AUTH_FAILED
            }
        }

        assertNull(env.accessTokenValidator.validateAccessToken(oldAccess))
        assertNotNull(
            env.accessTokenValidator.validateAccessToken(requireNotNull(second.userSession.accessToken)),
        )
        first.close()
        second.close()
    }

    @Test
    fun `logout derives the device from the authenticated session and flushes before close`() = runBlocking {
        val username = "e2e-logout-${UUID.randomUUID().toString().take(8)}"
        val session = createSession()
        session.imClient.register(username, "password123", "Logout User", "logout-device", "Device")
        withTimeout(5_000) { session.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val oldAccess = requireNotNull(session.userSession.accessToken)

        val response = session.invoke(AuthRpcContract.SERVICE, AuthRpcContract.M_LOGOUT)

        assertEquals(0, response.status)
        withTimeout(10_000) {
            session.imClient.state.first {
                it == ConnectionState.DISCONNECTED || it == ConnectionState.AUTH_FAILED
            }
        }
        assertNull(env.accessTokenValidator.validateAccessToken(oldAccess))
        session.close()
    }

    // ── RPC 调用 ──

    @Test
    fun `get own profile via RPC`() = runBlocking {
        val session = registerUser("profile-${UUID.randomUUID()}")
        val resp = session.invoke("user", UserRpcContract.M_GET_PROFILE,
            ProtoCodec.encodePayload { writeString(null) })
        assertEquals(0, resp.status)
        val user = ProtoCodec.decode(User, resp.payload!!)
        assertEquals(session.uid, user.uid)
        session.close()
    }

    @Test
    fun `update profile via RPC`() = runBlocking {
        val session = registerUser("update-${UUID.randomUUID()}")
        val patch = ProfilePatch(
            name = ProfilePatchValue.Set("NewName"),
            sex = ProfilePatchValue.Set(1),
        )
        val resp = session.invoke("user", UserRpcContract.M_UPDATE_PROFILE,
            ProtoCodec.encode(patch))
        assertEquals(0, resp.status, "Update profile should succeed")

        // 验证更新
        val getResp = session.invoke("user", UserRpcContract.M_GET_PROFILE,
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
        val applyResp = user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hello") })
        assertEquals(0, applyResp.status)
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        assertNull(apply.token, "发件人 apply 响应不应包含处理 token")

        // user2 接受
        val pendingToken = user2.pendingApplyToken(user1.uid)
        val acceptResp = user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            })
        assertEquals(0, acceptResp.status)

        // 验证好友列表（最多重试 3 次，应对 CI runner 时序差异）
        var friends: List<Contact> = emptyList()
        repeat(3) { attempt ->
            val listResp = user1.invoke("contact", ContactRpcContract.M_LIST)
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
        user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val pendingToken = user2.pendingApplyToken(user1.uid)
        user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            })

        // 创建私聊（等待 accept 生效）
        delay(100)
        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        assertEquals(0, chatResp.status)
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)
        assertEquals(1, chat.chatType) // 私聊
        assertNotNull(chat.chatId)

        user1.close()
        user2.close()
    }

    @Test
    fun `create group chat via RPC`() = runBlocking {
        val user1 = registerUser("grp1-${UUID.randomUUID()}")
        val user2 = registerUser("grp2-${UUID.randomUUID()}")

        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_GROUP,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeString("TestGroup")
                writeString(null) // 头像
                writeVarInt(1)    // 成员数
                writeString(user2.uid)
            })
        assertEquals(0, chatResp.status)
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)
        assertEquals(2, chat.chatType) // 群聊
        assertEquals("TestGroup", chat.name)

        user1.close()
        user2.close()
    }

    @Test
    fun `list devices via RPC`() = runBlocking {
        val session = registerUser("device-${UUID.randomUUID()}")
        val resp = session.invoke("device", DeviceRpcContract.M_LIST_DEVICES)
        assertEquals(0, resp.status)
        session.close()
    }

    @Test
    fun `list conversations via RPC`() = runBlocking {
        val session = registerUser("conv-${UUID.randomUUID()}")
        val resp = session.invoke(
            "conversation",
            ConversationRpcContract.M_LIST_PAGE,
            ProtoCodec.encode(ConversationPageRequest()),
        )
        assertEquals(0, resp.status)
        session.close()
    }

    // ── 消息投递 ──

    @Test
    fun `send message and receive ack`() = runBlocking {
        val user1 = registerUser("msg1-${UUID.randomUUID()}")
        val user2 = registerUser("msg2-${UUID.randomUUID()}")

        // 建立好友关系 + 创建私聊
        user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val pendingToken = user2.pendingApplyToken(user1.uid)
        user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            })

        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        // 发送消息
        val msg = com.virjar.tk.protocol.model.Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            senderUid = "",
            body = com.virjar.tk.protocol.body.buildRichTextBody("Hello E2E"),
        )
        val ack = user1.imClient.sendAndWaitAck(msg)
        assertEquals(0, ack.code, "Message ACK code should be OK: ${ack.reason}")
        assertEquals(msg.chatId, ack.chatId)
        assertEquals(msg.clientMsgId, ack.clientMsgId)
        assertTrue(ack.serverSeq > 0, "Server seq should be positive")

        user1.close()
        user2.close()
    }

    @Test
    fun `message delivered to other user`() = runBlocking {
        val user1 = registerUser("deliver1-${UUID.randomUUID()}")
        val user2 = registerUser("deliver2-${UUID.randomUUID()}")

        // 建立好友关系 + 创建私聊
        user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val pendingToken = user2.pendingApplyToken(user1.uid)
        user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            })

        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        // user1 发消息
        val msg = com.virjar.tk.protocol.model.Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            senderUid = "",
            body = com.virjar.tk.protocol.body.buildRichTextBody("Hello from user1"),
        )
        val ack = user1.imClient.sendAndWaitAck(msg)
        assertEquals(0, ack.code)
        assertEquals(msg.chatId, ack.chatId)
        assertEquals(msg.clientMsgId, ack.clientMsgId)

        // user2 应收到消息通知
        val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 5000)
        assertEquals(NotifyType.MESSAGE_RECV.code, notify.notifyType)

        user1.close()
        user2.close()
    }
}
