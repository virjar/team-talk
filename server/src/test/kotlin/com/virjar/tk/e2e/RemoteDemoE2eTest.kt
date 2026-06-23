package com.virjar.tk.e2e

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.model.*
import com.virjar.tk.protocol.*
import com.virjar.tk.body.TextBody
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.body.ImageBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID

/**
 * 远程 demo 服务器协议级 E2E 冒烟测试。
 *
 * **默认不执行**：仅当 `-Dtk.e2e.remote=true` 时启用（避免 CI 每次都连外部 demo）。
 * 用真实 [com.virjar.tk.client.ImClient] + [com.virjar.tk.client.RpcClient] 直连
 * `im.virjar.com:5100`（明文 TCP，无需 TLS），覆盖核心 IM 流程：
 * 注册 / 登录 / RPC / 好友 / 建群 / 发消息 / 订阅投递。
 *
 * 与 [ProtocolE2eTest] 互补：后者连 in-process 服务端（CI 常规 job），
 * 本类连真实部署的 demo（验证端到端可达性，含真实 PG/RocksDB/SSL 部署）。
 *
 * 本地运行：
 * ```
 * ./gradlew :server:test -Dtk.e2e.remote=true
 * # 指向其他 host（可选）
 * ./gradlew :server:test -Dtk.e2e.remote=true -Dtk.e2e.host=im.virjar.com -Dtk.e2e.port=5100
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class RemoteDemoE2eTest {

    @BeforeAll
    fun setup() {
        // 连通性前置检查：连不上直接失败，给出清晰提示而非一堆超时
        println("[RemoteDemoE2e] target = ${RemoteDemoSupport.host}:${RemoteDemoSupport.port}")
    }

    @AfterAll
    fun teardown() {
        RemoteDemoSupport.shutdown()
    }

    // ── 认证流程 ──

    @Test
    fun `register via TCP and receive uid`() = runBlocking {
        val session = RemoteDemoSupport.registerUser("reg")
        assertTrue(session.uid.isNotEmpty(), "注册后应拿到 uid")
        session.close()
    }

    @Test
    fun `login via TCP after register`() = runBlocking {
        // 动态注册一个账号，再用同账号登录
        val username = "zd-login-" + UUID.randomUUID().toString().take(8)
        val regPassword = "pass123"
        val regSession = RemoteDemoSupport.createSession()
        regSession.imClient.register(username, regPassword, "DemoUser", "e2e-device", "E2E")
        withTimeout(10_000) { regSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val uid = regSession.uid
        regSession.close()

        // 用新连接登录
        val loginSession = RemoteDemoSupport.loginUser(username, regPassword)
        assertEquals(uid, loginSession.uid, "登录后 uid 应与注册时一致")
        loginSession.close()
    }

    @Test
    fun `login rejects wrong password`() = runBlocking {
        val username = "zd-wrongpw-" + UUID.randomUUID().toString().take(8)
        val regSession = RemoteDemoSupport.createSession()
        regSession.imClient.register(username, "pass123", "DemoUser", "e2e-device", "E2E")
        withTimeout(10_000) { regSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        regSession.close()

        // 错误密码应认证失败
        val loginSession = RemoteDemoSupport.createSession()
        loginSession.imClient.login(username, "wrong_password", "e2e-device", "E2E")
        withTimeout(10_000) { loginSession.imClient.state.first { it == ConnectionState.AUTH_FAILED } }
        loginSession.close()
    }

    // ── RPC 调用 ──

    @Test
    fun `get own profile via RPC`() = runBlocking {
        val session = RemoteDemoSupport.registerUser("profile")
        val resp = session.invoke(ServiceId.USER, UserMethod.GET_PROFILE.id,
            ProtoCodec.encodePayload { writeString(null) })
        assertEquals(0, resp.status, "GET_PROFILE 应成功")
        val user = ProtoCodec.decode(User, resp.payload!!)
        assertEquals(session.uid, user.uid)
        session.close()
    }

    @Test
    fun `list conversations via RPC`() = runBlocking {
        val session = RemoteDemoSupport.registerUser("conv")
        val resp = session.invoke(ServiceId.CONVERSATION, ConversationMethod.LIST.id)
        assertEquals(0, resp.status, "会话列表 RPC 应成功")
        session.close()
    }

    @Test
    fun `list devices via RPC`() = runBlocking {
        val session = RemoteDemoSupport.registerUser("device")
        val resp = session.invoke(ServiceId.DEVICE, DeviceMethod.LIST.id)
        assertEquals(0, resp.status, "设备列表 RPC 应成功")
        session.close()
    }

    // ── 社交关系 ──

    @Test
    fun `contact apply and accept via RPC`() = runBlocking {
        val user1 = RemoteDemoSupport.registerUser("contact1")
        val user2 = RemoteDemoSupport.registerUser("contact2")

        // user1 申请加 user2
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hello") })
        assertEquals(0, applyResp.status)
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        assertNotNull(apply.token)

        // user2 接受
        val acceptResp = user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })
        assertEquals(0, acceptResp.status)

        // 验证好友列表
        val listResp = user1.invoke(ServiceId.CONTACT, ContactMethod.LIST.id)
        assertEquals(0, listResp.status)
        val friends = ProtoCodec.decodeList(Contact, listResp.payload!!)
        assertTrue(friends.any { it.friendUid == user2.uid }, "user2 应出现在 user1 好友列表")

        user1.close()
        user2.close()
    }

    @Test
    fun `create group chat via RPC`() = runBlocking {
        val user1 = RemoteDemoSupport.registerUser("grp1")
        val user2 = RemoteDemoSupport.registerUser("grp2")

        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_GROUP.id,
            ProtoCodec.encodePayload {
                writeString("DemoGroup")
                writeString(null) // avatar
                writeVarInt(1)    // member count
                writeString(user2.uid)
            })
        assertEquals(0, chatResp.status, "建群应成功")
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)
        assertEquals(2, chat.chatType, "chatType 应为 group")
        assertEquals("DemoGroup", chat.name)

        user1.close()
        user2.close()
    }

    // ── 消息投递 ──

    @Test
    fun `send message and receive ack`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("msg")
        try {
            val msg = Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = TextBody("Hello from remote E2E"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "消息 ACK 应成功: ${ack.reason}")
            assertTrue(ack.serverSeq > 0, "serverSeq 应为正数")
        } finally {
            user1.close()
            user2.close()
        }
    }

    @Test
    fun `message delivered to other user`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("deliver")
        try {
            val msg = Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = TextBody("Deliver me"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "发送应成功")

            // user2 应实时收到消息通知
            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            assertEquals(NotifyType.MESSAGE_RECV.code, notify.notifyType, "user2 应收到 MESSAGE_RECV 通知")
        } finally {
            user1.close()
            user2.close()
        }
    }

    // ── 订阅与历史 ──

    @Test
    fun `subscribe delivers history`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("sub")
        try {
            // user1 发两条消息
            repeat(2) { i ->
                val msg = Message(
                    chatId = chat.chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.TEXT.code,
                    timestamp = System.currentTimeMillis(),
                    senderUid = "",
                    body = TextBody("history-$i"),
                )
                user1.imClient.sendAndWaitAck(msg)
            }

            // user2 订阅，应收到历史消息
            user2.subscribe(chat.chatId, 0)
            val n1 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            assertNotNull(n1, "应收到第 1 条历史")
            val n2 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            assertNotNull(n2, "应收到第 2 条历史")
        } finally {
            user1.close()
            user2.close()
        }
    }

    // ── 多 body 类型消息往返 ──

    @Test
    fun `file message round-trip`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("file")
        try {
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.FILE.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = FileBody(url = "https://demo/r.pdf", fileName = "report.pdf", size = 524288L),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "文件消息 ACK 应成功: ${ack.reason}")

            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            assertEquals(MessageType.FILE.code, recv.messageType)
            val body = recv.body as FileBody
            assertEquals("report.pdf", body.fileName)
            assertEquals(524288L, body.size)
        } finally {
            user1.close(); user2.close()
        }
    }

    @Test
    fun `voice message round-trip`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("voice")
        try {
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.VOICE.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = VoiceBody(url = "https://demo/v.amr", duration = 15, size = 32768L),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "语音消息 ACK 应成功: ${ack.reason}")

            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            val body = recv.body as VoiceBody
            assertEquals(15, body.duration)
            assertEquals(32768L, body.size)
        } finally {
            user1.close(); user2.close()
        }
    }

    @Test
    fun `image message round-trip`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("image")
        try {
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.IMAGE.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = ImageBody(url = "https://demo/p.png", width = 1080, height = 1920, size = 2048576L),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "图片消息 ACK 应成功: ${ack.reason}")

            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            val body = recv.body as ImageBody
            assertEquals(1080, body.width)
            assertEquals(1920, body.height)
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── 消息操作：转发 / 撤回 / 编辑 ──

    @Test
    fun `forward message via RPC`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("fwd")
        try {
            // 先发一条源消息
            val src = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = TextBody("to be forwarded"),
            )
            val ack = user1.imClient.sendAndWaitAck(src)
            assertEquals(0, ack.code)

            // FORWARD RPC: srcChatId, srcSeq, targetChatId（转发回同一会话）
            val fwdResp = user1.invoke(ServiceId.MESSAGE, MessageMethod.FORWARD.id,
                ProtoCodec.encodePayload {
                    writeString(chat.chatId); writeVarLong(ack.serverSeq); writeString(chat.chatId)
                })
            assertEquals(0, fwdResp.status, "转发 RPC 应成功: status=${fwdResp.status}")
            val fwdMsg = ProtoCodec.decode(Message, fwdResp.payload!!)
            assertTrue(fwdMsg.serverSeq > ack.serverSeq, "转发消息应有新 serverSeq")

            // user2 应收到转发来的消息
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
        } finally {
            user1.close(); user2.close()
        }
    }

    @Test
    fun `revoke message via RPC`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("revoke")
        try {
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = TextBody("will be revoked"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)

            // REVOKE RPC: chatId, serverSeq
            val revokeResp = user1.invoke(ServiceId.MESSAGE, MessageMethod.REVOKE.id,
                ProtoCodec.encodePayload { writeString(chat.chatId); writeVarLong(ack.serverSeq) })
            assertEquals(0, revokeResp.status, "撤回 RPC 应成功: status=${revokeResp.status}")

            // 先消费掉原始消息投递（sendAndWaitAck 触发的），再取撤回后的重新投递
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000) // 原始消息 flags=0
            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000) // 撤回后重新投递
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            assertEquals(ack.serverSeq, recv.serverSeq, "应是同一条消息")
            assertTrue(recv.flags and 1 != 0, "flags bit0 应置位（已撤回）: flags=${recv.flags}")
        } finally {
            user1.close(); user2.close()
        }
    }

    @Test
    fun `edit message via RPC`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("edit")
        try {
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = TextBody("original content"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)

            // EDIT RPC: 完整 Message 编码（含 serverSeq + 新 body）
            val edited = msg.copy(serverSeq = ack.serverSeq, body = TextBody("edited content"))
            val editResp = user1.invoke(ServiceId.MESSAGE, MessageMethod.EDIT.id, ProtoCodec.encode(edited))
            assertEquals(0, editResp.status, "编辑 RPC 应成功: status=${editResp.status}")

            // 先消费掉原始消息投递，再取编辑后的重新投递
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000) // 原始消息
            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000) // 编辑后重新投递
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            assertEquals(ack.serverSeq, recv.serverSeq, "应是同一条消息")
            assertTrue(recv.flags and 2 != 0, "flags bit1 应置位（已编辑）: flags=${recv.flags}")
            val newBody = recv.body as TextBody
            assertEquals("edited content", newBody.text, "body 应为编辑后的内容")
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── 个人 profile ──

    @Test
    fun `get and update profile`() = runBlocking {
        val session = RemoteDemoSupport.registerUser("prof-up")
        try {
            // GET_PROFILE
            val getResp = session.invoke(ServiceId.USER, UserMethod.GET_PROFILE.id,
                ProtoCodec.encodePayload { writeString(null) })
            assertEquals(0, getResp.status)
            val user = ProtoCodec.decode(User, getResp.payload!!)
            assertEquals(session.uid, user.uid)

            // UPDATE_PROFILE: 修改昵称（payload = User 编码，只取 name/avatar/sex）
            val updated = user.copy(name = "NewName-${UUID.randomUUID().toString().take(4)}")
            val updResp = session.invoke(ServiceId.USER, UserMethod.UPDATE_PROFILE.id, ProtoCodec.encode(updated))
            assertEquals(0, updResp.status, "更新 profile 应成功")

            // 再查确认
            val getResp2 = session.invoke(ServiceId.USER, UserMethod.GET_PROFILE.id,
                ProtoCodec.encodePayload { writeString(null) })
            val user2 = ProtoCodec.decode(User, getResp2.payload!!)
            assertEquals(updated.name, user2.name, "昵称应已更新")
        } finally {
            session.close()
        }
    }

    // ── 群消息广播 ──

    @Test
    fun `group message broadcasts to all members`() = runBlocking {
        val user1 = RemoteDemoSupport.registerUser("grpbc-1")
        val user2 = RemoteDemoSupport.registerUser("grpbc-2")
        val user3 = RemoteDemoSupport.registerUser("grpbc-3")
        try {
            // 建 3 人群（user1 建群，加 user2 + user3）
            val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_GROUP.id,
                ProtoCodec.encodePayload {
                    writeString("BroadcastGroup")
                    writeString(null)
                    writeVarInt(2)
                    writeString(user2.uid); writeString(user3.uid)
                })
            assertEquals(0, chatResp.status, "建群应成功")
            val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

            // user1 发消息
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = TextBody("hello group"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)

            // user2 + user3 都应收到
            val n2 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val n3 = user3.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            assertNotNull(n2, "user2 应收到群消息")
            assertNotNull(n3, "user3 应收到群消息")
        } finally {
            user1.close(); user2.close(); user3.close()
        }
    }

    // ── 历史消息 ──

    @Test
    fun `get history via RPC`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("history")
        try {
            // 先发两条消息
            repeat(2) { i ->
                user1.imClient.sendAndWaitAck(Message(
                    chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                    senderUid = "", body = TextBody("history-$i"),
                ))
            }

            // GET_HISTORY: chatId + fromSeq + limit（3 字段，之前客户端漏了 limit 导致 500）
            val resp = user1.invoke(ServiceId.MESSAGE, MessageMethod.GET_HISTORY.id,
                ProtoCodec.encodePayload { writeString(chat.chatId); writeVarLong(0); writeVarInt(50) })
            assertEquals(0, resp.status, "GET_HISTORY 应成功: status=${resp.status}")
            val messages = ProtoCodec.decodeList(Message, resp.payload!!)
            assertTrue(messages.size >= 2, "应至少返回 2 条历史消息，实际 ${messages.size}")
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── 订阅增量 ──

    @Test
    fun `subscribe delivers only messages after lastSeq`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("incr")
        try {
            // 发 3 条消息
            val seqs = mutableListOf<Long>()
            repeat(3) { i ->
                val ack = user1.imClient.sendAndWaitAck(Message(
                    chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                    senderUid = "", body = TextBody("msg-$i"),
                ))
                seqs.add(ack.serverSeq)
            }

            // user2 订阅 lastSeq = 第1条的 seq，应只收到后 2 条
            user2.subscribe(chat.chatId, seqs[0])
            val n1 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val n2 = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            assertNotNull(n1, "应收到第 2 条")
            assertNotNull(n2, "应收到第 3 条")
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── helper：建立好友关系 + 创建私聊（消息类测试前置） ──

    private suspend fun createFriendPersonalChat(tag: String): Triple<RemoteDemoSupport.Session, RemoteDemoSupport.Session, Chat> {
        val user1 = RemoteDemoSupport.registerUser("$tag-1")
        val user2 = RemoteDemoSupport.registerUser("$tag-2")

        // 申请 + 接受好友
        val applyResp = user1.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        user2.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id,
            ProtoCodec.encodePayload { writeString(apply.token) })

        // 创建私聊
        val chatResp = user1.invoke(ServiceId.CHAT, ChatMethod.CREATE_PERSONAL.id,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        assertEquals(0, chatResp.status, "创建私聊应成功")
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        return Triple(user1, user2, chat)
    }
}
