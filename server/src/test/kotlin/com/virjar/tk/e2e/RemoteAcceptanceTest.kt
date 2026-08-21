package com.virjar.tk.e2e

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.rpc.gen.ChatRpcContract
import com.virjar.tk.rpc.gen.ContactRpcContract
import com.virjar.tk.rpc.gen.ConversationRpcContract
import com.virjar.tk.rpc.gen.DeviceRpcContract
import com.virjar.tk.rpc.gen.MessageRpcContract
import com.virjar.tk.rpc.gen.UserRpcContract
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.model.*
import com.virjar.tk.protocol.*
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.repository.FileRepository
import com.virjar.tk.repository.FileOps
import com.virjar.tk.repository.GroupFileRepository
import com.virjar.tk.repository.DocumentRepository
import com.virjar.tk.rpc.gen.ChatRpcProxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID

/**
 * 已部署服务器的协议级 E2E 业务验收。
 *
 * 通过 `./gradlew :server:acceptanceTest` 执行；该任务会注入仓库的部署配置。
 * 普通 `:server:test` 不启用本类，避免本地安全网依赖外部站点。
 * 用真实 [com.virjar.tk.client.ImClient] + [com.virjar.tk.client.RpcClient] 直连
 * `im.virjar.com:5100`（明文 TCP，无需 TLS），覆盖核心 IM 流程：
 * 注册 / 登录 / RPC / 好友 / 建群 / 发消息 / 订阅投递。
 *
 * 与 [ProtocolE2eTest] 互补：后者连 in-process 服务端（CI 常规 job），
 * 本类连接真实部署（验证端到端可达性，含真实 PG/RocksDB/文件存储）。
 *
 * 标准运行：
 * ```
 * ./gradlew :server:acceptanceTest
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class RemoteAcceptanceTest {

    private suspend fun upload(session: RemoteAcceptanceSupport.Session, bytes: ByteArray, fileName: String): Attachment {
        val baseUrl = System.getProperty("tk.e2e.server") ?: "https://${RemoteAcceptanceSupport.host}"
        val repository = FileRepository(baseUrl, session.uid, session.userSession::httpCredentialsSnapshot)
        return try {
            repository.uploadSmallBytes(bytes, fileName, "application/octet-stream").getOrThrow()
        } finally {
            repository.close()
        }
    }

    private suspend fun download(
        session: RemoteAcceptanceSupport.Session,
        attachment: Attachment,
    ): ByteArray {
        val repository = FileRepository(baseUrl(), session.uid, session.userSession::httpCredentialsSnapshot)
        return try {
            repository.downloadSmall(attachment).getOrThrow()
        } finally {
            repository.close()
        }
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("[RemoteAcceptance] target = ${RemoteAcceptanceSupport.host}:${RemoteAcceptanceSupport.port}")
        // 发布门禁先做一次真实认证。若服务器不可达或协议版本落后，
        // 在所有业务 case 前立即终止，避免产生数十个没有诊断价值的超时。
        try {
            withTimeout(8_000) {
                RemoteAcceptanceSupport.registerUser("readiness").close()
            }
        } catch (cause: Exception) {
            throw AssertionError(
                "Deployment readiness failed: ${RemoteAcceptanceSupport.host}:${RemoteAcceptanceSupport.port}, " +
                    "client protocol=${PacketCodec.PROTOCOL_VERSION}. " +
                    "Deploy the matching server before running business acceptance.",
                cause,
            )
        }
    }

    @AfterAll
    fun teardown() {
        RemoteAcceptanceSupport.shutdown()
    }

    // ── 认证流程 ──

    @Test
    fun `register via TCP and receive uid`() = runBlocking {
        val session = RemoteAcceptanceSupport.registerUser("reg")
        assertTrue(session.uid.isNotEmpty(), "注册后应拿到 uid")
        session.close()
    }

    @Test
    fun `login via TCP after register`() = runBlocking {
        // 动态注册一个账号，再用同账号登录
        val username = "e2e-login-" + UUID.randomUUID().toString().take(8)
        val regPassword = "pass123"
        val regSession = RemoteAcceptanceSupport.createSession()
        regSession.imClient.register(username, regPassword, "TestUser", "e2e-device", "E2E")
        withTimeout(10_000) { regSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        val uid = regSession.uid
        regSession.close()

        // 用新连接登录
        val loginSession = RemoteAcceptanceSupport.loginUser(username, regPassword)
        assertEquals(uid, loginSession.uid, "登录后 uid 应与注册时一致")
        loginSession.close()
    }

    @Test
    fun `login rejects wrong password`() = runBlocking {
        val username = "e2e-wrongpw-" + UUID.randomUUID().toString().take(8)
        val regSession = RemoteAcceptanceSupport.createSession()
        regSession.imClient.register(username, "pass123", "TestUser", "e2e-device", "E2E")
        withTimeout(10_000) { regSession.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        regSession.close()

        // 错误密码应认证失败
        val loginSession = RemoteAcceptanceSupport.createSession()
        loginSession.imClient.login(username, "wrong_password", "e2e-device", "E2E")
        withTimeout(10_000) { loginSession.imClient.state.first { it == ConnectionState.AUTH_FAILED } }
        loginSession.close()
    }

    // ── RPC 调用 ──

    @Test
    fun `get own profile via RPC`() = runBlocking {
        val session = RemoteAcceptanceSupport.registerUser("profile")
        val resp = session.invoke("user", UserRpcContract.M_GET_PROFILE,
            ProtoCodec.encodePayload { writeString(null) })
        assertEquals(0, resp.status, "GET_PROFILE 应成功")
        val user = ProtoCodec.decode(User, resp.payload!!)
        assertEquals(session.uid, user.uid)
        session.close()
    }

    @Test
    fun `list conversations via RPC`() = runBlocking {
        val session = RemoteAcceptanceSupport.registerUser("conv")
        val resp = session.invoke("conversation", ConversationRpcContract.M_LIST)
        assertEquals(0, resp.status, "会话列表 RPC 应成功")
        session.close()
    }

    @Test
    fun `list devices via RPC`() = runBlocking {
        val session = RemoteAcceptanceSupport.registerUser("device")
        val resp = session.invoke("device", DeviceRpcContract.M_LIST_DEVICES)
        assertEquals(0, resp.status, "设备列表 RPC 应成功")
        session.close()
    }

    // ── 社交关系 ──

    @Test
    fun `contact apply and accept via RPC`() = runBlocking {
        val user1 = RemoteAcceptanceSupport.registerUser("contact1")
        val user2 = RemoteAcceptanceSupport.registerUser("contact2")

        // user1 申请加 user2
        val applyResp = user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hello") })
        assertEquals(0, applyResp.status)
        val apply = ProtoCodec.decode(ContactApply, applyResp.payload!!)
        assertNull(apply.token, "发件人 apply 响应不应包含处理 token")

        // user2 接受
        val pendingToken = user2.pendingApplyToken(user1.uid)
        val acceptResp = user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload { writeString(pendingToken) })
        assertEquals(0, acceptResp.status)

        // 验证好友列表
        val listResp = user1.invoke("contact", ContactRpcContract.M_LIST)
        assertEquals(0, listResp.status)
        val friends = ProtoCodec.decodeList(Contact, listResp.payload!!)
        assertTrue(friends.any { it.friendUid == user2.uid }, "user2 应出现在 user1 好友列表")

        user1.close()
        user2.close()
    }

    @Test
    fun `create group chat via RPC`() = runBlocking {
        val user1 = RemoteAcceptanceSupport.registerUser("grp1")
        val user2 = RemoteAcceptanceSupport.registerUser("grp2")

        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_GROUP,
            ProtoCodec.encodePayload {
                writeString("TestGroup")
                writeString(null) // avatar
                writeVarInt(1)    // member count
                writeString(user2.uid)
            })
        assertEquals(0, chatResp.status, "建群应成功")
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)
        assertEquals(2, chat.chatType, "chatType 应为 group")
        assertEquals("TestGroup", chat.name)

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
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = buildRichTextBody("Hello from remote E2E"),
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
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = buildRichTextBody("Deliver me"),
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

    @Test
    fun `complex markdown source survives ack delivery and decode`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("markdown")
        try {
            val token = "TT-MARKDOWN-${UUID.randomUUID()}"
            val markdown = """
                # $token

                > 引用中的 **重点**

                ```kotlin
                fun greet(name: String) {
                    println("Hello, ${'$'}name")
                }
                ```

                | 能力 | 状态 |
                | :--- | ---: |
                | 源码 | ✅ |
                | 预览 | ✅ |

                - [x] Markdown 原文
                - [ ] 后续任务

                @[接收者](mention://${user2.uid})
            """.trimIndent()
            val message = Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = buildRichTextBody(markdown),
            )

            val ack = user1.imClient.sendAndWaitAck(message)
            assertEquals(0, ack.code, "复杂 Markdown ACK 应成功: ${ack.reason}")
            assertTrue(ack.serverSeq > 0)

            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val received = ProtoCodec.decode(Message, notify.payload!!)
            val body = assertInstanceOf(RichTextBody::class.java, received.body)
            assertEquals(markdown, body.markdown, "服务端不得改写客户端的权威 Markdown 源码")
            assertEquals(listOf(user2.uid), body.mentions.map { it.uid })
            assertTrue(body.plainText.contains(token))
        } finally {
            user1.close()
            user2.close()
        }
    }

    // ── 多 body 类型消息往返 ──

    @Test
    fun `group file space keeps versions and member download ACL`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("group-file-owner")
        val member = RemoteAcceptanceSupport.registerUser("group-file-member")
        val outsider = RemoteAcceptanceSupport.registerUser("group-file-outsider")
        try {
            val chat = ChatRpcProxy(owner.rpc)
                .createGroup("远程群文件验收", null, listOf(member.uid))
            val ownerFiles = GroupFileRepository(owner.rpc)
            val memberFiles = GroupFileRepository(member.rpc)
            val outsiderFiles = GroupFileRepository(outsider.rpc)

            val folder = ownerFiles.createFolder(chat.chatId, null, "项目资料").getOrThrow()
            val v1Bytes = "# Remote acceptance v1".encodeToByteArray()
            val v1Attachment = upload(owner, v1Bytes, "readme-v1.md")
            val file = ownerFiles.createFile(chat.chatId, folder.entryId, "README.md", v1Attachment).getOrThrow()

            assertEquals(listOf("README.md"), memberFiles.list(chat.chatId, folder.entryId).getOrThrow().map { it.name })
            assertArrayEquals(v1Bytes, download(member, v1Attachment))
            assertTrue(outsiderFiles.list(chat.chatId, null) is Outcome.Failure, "非群成员不能读取群文件目录")

            val v2Bytes = "# Remote acceptance v2".encodeToByteArray()
            val v2Attachment = upload(member, v2Bytes, "readme-v2.md")
            val v2 = memberFiles.addVersion(chat.chatId, file.entryId, v2Attachment, file.revision).getOrThrow()
            assertEquals(2L, v2.contentVersion)
            assertEquals(listOf(2L, 1L), ownerFiles.listVersions(chat.chatId, file.entryId).getOrThrow().map { it.version })

            ownerFiles.delete(chat.chatId, v2.entryId, v2.revision).getOrThrow()
            assertDownloadRejected(v1Attachment, member.userSession.accessToken, 403)
        } finally {
            owner.close(); member.close(); outsider.close()
        }
    }

    @Test
    fun `document spaces keep tree revisions conflicts and live ACL`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("document-owner")
        val member = RemoteAcceptanceSupport.registerUser("document-member")
        val outsider = RemoteAcceptanceSupport.registerUser("document-outsider")
        try {
            val ownerDocs = DocumentRepository(owner.rpc)
            val memberDocs = DocumentRepository(member.rpc)
            val outsiderDocs = DocumentRepository(outsider.rpc)

            val space = ownerDocs.createSpace("远程产品空间", "独立于群聊的企业资产").getOrThrow()
            assertEquals(DocumentSpace.ROLE_OWNER, space.myRole)
            assertTrue(memberDocs.listSpaces().getOrThrow().isEmpty())
            ownerDocs.upsertGrant(
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member.uid,
                DocumentSpace.ROLE_EDITOR,
                false,
            ).getOrThrow()
            assertEquals(listOf(space.spaceId), memberDocs.listSpaces().getOrThrow().map { it.spaceId })
            assertTrue(outsiderDocs.listSpaces().getOrThrow().isEmpty(), "未授权用户看不到空间")

            val folder = ownerDocs.createFolder(space.spaceId, null, "产品资料").getOrThrow()
            val created = ownerDocs.createDocument(
                space.spaceId,
                folder.nodeId,
                "远程产品说明",
                "# 第一版\n由群主创建。",
            ).getOrThrow()
            assertEquals(1L, created.revision)
            assertEquals(
                listOf(created.documentId),
                ownerDocs.listRecentDocuments(10).getOrThrow().map { it.documentId },
                "创建文档与创建者最近访问必须同一提交可见",
            )
            val memberCreated = memberDocs.listRecentlyCreatedDocuments(10).getOrThrow().single()
            assertEquals(created.documentId, memberCreated.documentId)
            assertEquals(space.name, memberCreated.spaceName)
            assertEquals("第一版", memberCreated.excerpt)
            assertTrue(memberDocs.listRecentDocuments(10).getOrThrow().isEmpty())
            assertEquals(
                listOf("远程产品说明"),
                memberDocs.listNodes(space.spaceId, folder.nodeId).getOrThrow().map { it.name },
            )
            assertTrue(
                outsiderDocs.getDocument(space.spaceId, created.documentId) is Outcome.Failure,
                "未授权用户不能读取文档",
            )
            memberDocs.getDocument(space.spaceId, created.documentId).getOrThrow()
            assertEquals(
                listOf(created.documentId),
                memberDocs.listRecentDocuments(10).getOrThrow().map { it.documentId },
            )

            val updated = memberDocs.updateDocument(
                space.spaceId,
                created.documentId,
                "远程产品说明 v2",
                "# 第二版\n由空间编辑者修订。",
                created.revision,
            ).getOrThrow()
            assertEquals(2L, updated.revision)
            assertEquals(
                "第二版",
                ownerDocs.listNodes(space.spaceId, folder.nodeId).getOrThrow().single().excerpt,
                "目录投影摘要必须随正文保存更新",
            )
            assertTrue(
                ownerDocs.updateDocument(
                    space.spaceId,
                    created.documentId,
                    "过期覆盖",
                    "不应成功",
                    created.revision,
                ) is Outcome.Failure,
                "旧 revision 不能覆盖其他成员的新版本",
            )
            assertEquals(
                listOf(2L, 1L),
                ownerDocs.listRevisions(space.spaceId, created.documentId)
                    .getOrThrow().map { it.revision },
            )
            assertEquals(
                "# 第一版\n由群主创建。",
                ownerDocs.getRevision(space.spaceId, created.documentId, 1)
                    .getOrThrow().markdown,
            )

            ownerDocs.removeGrant(space.spaceId, DocumentSpaceGrant.PRINCIPAL_USER, member.uid).getOrThrow()
            assertTrue(
                memberDocs.getDocument(space.spaceId, created.documentId) is Outcome.Failure,
                "撤销空间授权后不能继续读取文档",
            )
            assertTrue(memberDocs.listRecentDocuments(10).getOrThrow().isEmpty(), "撤权后最近访问也必须过滤")
            assertTrue(memberDocs.listRecentlyCreatedDocuments(10).getOrThrow().isEmpty(), "撤权后最近创建也必须过滤")
            ownerDocs.deleteNode(space.spaceId, created.documentId, updated.revision).getOrThrow()
            assertTrue(ownerDocs.listRecentDocuments(10).getOrThrow().isEmpty(), "删除后最近访问不可见")
            assertTrue(ownerDocs.listRecentlyCreatedDocuments(10).getOrThrow().isEmpty(), "删除后最近创建不可见")
            ownerDocs.deleteNode(space.spaceId, folder.nodeId, folder.revision).getOrThrow()
            assertTrue(ownerDocs.listNodes(space.spaceId, null).getOrThrow().isEmpty())
        } finally {
            owner.close(); member.close(); outsider.close()
        }
    }

    @Test
    fun `file message round-trip enforces attachment access`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("file")
        val stranger = RemoteAcceptanceSupport.registerUser("file-stranger")
        try {
            val bytes = ByteArray(524288) { (it % 251).toByte() }
            val attachment = upload(user1, bytes, "report.pdf")

            // 上传者可以在消息发送前读取；未认证请求和无关用户不能把随机路径当作授权。
            assertArrayEquals(
                bytes,
                download(user1, attachment),
            )
            assertDownloadRejected(attachment, null, 401)
            assertDownloadRejected(attachment, stranger.userSession.accessToken, 403)

            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.FILE.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = FileBody(attachment),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "文件消息 ACK 应成功: ${ack.reason}")

            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            assertEquals(MessageType.FILE.code, recv.messageType)
            val body = recv.body as FileBody
            assertEquals("report.pdf", body.attachment.name)
            assertEquals(524288L, body.attachment.size)

            // 消息成功落库后，附件 ACL 从反向索引解析到会话成员；非成员仍无权访问。
            assertArrayEquals(
                bytes,
                download(user2, body.attachment),
            )
            assertDownloadRejected(body.attachment, stranger.userSession.accessToken, 403)
        } finally {
            user1.close(); user2.close(); stranger.close()
        }
    }

    @Test
    fun `voice message round-trip`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("voice")
        try {
            val bytes = ByteArray(32768) { (it % 127).toByte() }
            val attachment = upload(user1, bytes, "voice.amr")
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.VOICE.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = VoiceBody(attachment, duration = 15),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code, "语音消息 ACK 应成功: ${ack.reason}")

            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            val body = recv.body as VoiceBody
            assertEquals(15, body.duration)
            assertEquals(32768L, body.attachment.size)
        } finally {
            user1.close(); user2.close()
        }
    }

    @Test
    fun `image message round-trip`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("image")
        try {
            val bytes = ByteArray(4096) { (it % 193).toByte() }
            val attachment = upload(user1, bytes, "picture.png")
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.IMAGE.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = ImageBody(attachment, width = 1080, height = 1920),
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
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = buildRichTextBody("to be forwarded"),
            )
            val ack = user1.imClient.sendAndWaitAck(src)
            assertEquals(0, ack.code)

            // FORWARD RPC: srcChatId, srcSeq, targetChatId（转发回同一会话）
            val fwdResp = user1.invoke("message", MessageRpcContract.M_FORWARD,
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
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = buildRichTextBody("will be revoked"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)

            // REVOKE RPC: chatId, serverSeq
            val revokeResp = user1.invoke("message", MessageRpcContract.M_REVOKE,
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
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = buildRichTextBody("original content"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)

            // EDIT RPC: 完整 Message 编码（含 serverSeq + 新 body）
            val edited = msg.copy(serverSeq = ack.serverSeq, body = buildRichTextBody("edited content"))
            val editResp = user1.invoke("message", MessageRpcContract.M_EDIT, ProtoCodec.encode(edited))
            assertEquals(0, editResp.status, "编辑 RPC 应成功: status=${editResp.status}")

            // 先消费掉原始消息投递，再取编辑后的重新投递
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000) // 原始消息
            val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000) // 编辑后重新投递
            val recv = ProtoCodec.decode(Message, notify.payload!!)
            assertEquals(ack.serverSeq, recv.serverSeq, "应是同一条消息")
            assertTrue(recv.flags and 2 != 0, "flags bit1 应置位（已编辑）: flags=${recv.flags}")
            val newBody = recv.body as RichTextBody
            assertEquals("edited content", newBody.markdown, "body 应为编辑后的内容")
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── 个人 profile ──

    @Test
    fun `get and update profile`() = runBlocking {
        val session = RemoteAcceptanceSupport.registerUser("prof-up")
        try {
            // GET_PROFILE
            val getResp = session.invoke("user", UserRpcContract.M_GET_PROFILE,
                ProtoCodec.encodePayload { writeString(null) })
            assertEquals(0, getResp.status)
            val user = ProtoCodec.decode(User, getResp.payload!!)
            assertEquals(session.uid, user.uid)

            // UPDATE_PROFILE: 只提交明确 present 的昵称字段。
            val updatedName = "NewName-${UUID.randomUUID().toString().take(4)}"
            val patch = ProfilePatch(name = ProfilePatchValue.Set(updatedName))
            val updResp = session.invoke("user", UserRpcContract.M_UPDATE_PROFILE, ProtoCodec.encode(patch))
            assertEquals(0, updResp.status, "更新 profile 应成功")

            // 再查确认
            val getResp2 = session.invoke("user", UserRpcContract.M_GET_PROFILE,
                ProtoCodec.encodePayload { writeString(null) })
            val user2 = ProtoCodec.decode(User, getResp2.payload!!)
            assertEquals(updatedName, user2.name, "昵称应已更新")
        } finally {
            session.close()
        }
    }

    // ── 群消息广播 ──

    @Test
    fun `group message broadcasts to all members`() = runBlocking {
        val user1 = RemoteAcceptanceSupport.registerUser("grpbc-1")
        val user2 = RemoteAcceptanceSupport.registerUser("grpbc-2")
        val user3 = RemoteAcceptanceSupport.registerUser("grpbc-3")
        try {
            // 建 3 人群（user1 建群，加 user2 + user3）
            val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_GROUP,
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
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = buildRichTextBody("hello group"),
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
                    messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                    senderUid = "", body = buildRichTextBody("history-$i"),
                ))
            }

            // GET_HISTORY: chatId + fromSeq + limit（3 字段；服务端单页上限为 10）
            val resp = user1.invoke("message", MessageRpcContract.M_GET_HISTORY,
                ProtoCodec.encodePayload { writeString(chat.chatId); writeVarLong(0); writeVarInt(10) })
            assertEquals(0, resp.status, "GET_HISTORY 应成功: status=${resp.status}")
            val messages = ProtoCodec.decodeList(Message, resp.payload!!)
            assertTrue(messages.size >= 2, "应至少返回 2 条历史消息，实际 ${messages.size}")
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── helper：建立好友关系 + 创建私聊（消息类测试前置） ──

    private suspend fun createFriendPersonalChat(tag: String): Triple<RemoteAcceptanceSupport.Session, RemoteAcceptanceSupport.Session, Chat> {
        val user1 = RemoteAcceptanceSupport.registerUser("$tag-1")
        val user2 = RemoteAcceptanceSupport.registerUser("$tag-2")

        // 申请 + 接受好友
        user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val pendingToken = user2.pendingApplyToken(user1.uid)
        user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload { writeString(pendingToken) })

        // 创建私聊
        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        assertEquals(0, chatResp.status, "创建私聊应成功")
        val chat = ProtoCodec.decode(Chat, chatResp.payload!!)

        return Triple(user1, user2, chat)
    }

    private fun baseUrl(): String =
        System.getProperty("tk.e2e.server") ?: "https://${RemoteAcceptanceSupport.host}"

    private suspend fun assertDownloadRejected(attachment: Attachment, accessToken: String?, expectedCode: Int) {
        val outcome: Outcome<ByteArray> = if (accessToken == null) {
            val code = withContext(Dispatchers.IO) {
                val connection = java.net.URL(FileOps.resolveUrl(baseUrl(), attachment))
                    .openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = false
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }
            Outcome.Failure(AppError.Business(code, "download rejected HTTP $code"))
        } else {
            val testOwner = "download-rejection-check"
            val repository = FileRepository(baseUrl(), testOwner) {
                SessionHttpCredentials(uid = testOwner, accessToken = accessToken)
            }
            try {
                repository.downloadSmall(attachment)
            } finally {
                repository.close()
            }
        }
        assertTrue(outcome is Outcome.Failure, "下载应被拒绝，实际结果: $outcome")
        val error = (outcome as Outcome.Failure).error
        assertTrue(error is AppError.Business, "HTTP 拒绝应保留业务状态码，实际错误: $error")
        assertEquals(expectedCode, (error as AppError.Business).code)
    }
}
