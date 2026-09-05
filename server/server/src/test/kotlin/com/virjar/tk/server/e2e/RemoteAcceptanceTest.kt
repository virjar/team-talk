package com.virjar.tk.server.e2e

import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.server.api.AdminLoginRequest
import com.virjar.tk.server.api.AdminTokenResponse
import com.virjar.tk.server.api.OrganizationMemberRequest
import com.virjar.tk.server.api.OrganizationUnitRequest
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import com.virjar.tk.protocol.rpc.gen.ConversationRpcContract
import com.virjar.tk.protocol.rpc.gen.DeviceRpcContract
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.server.infra.storage.Core02ProcessCrashBoundary
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.body.VoiceBody
import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.shared.repository.FileRepository
import com.virjar.tk.shared.repository.FileOps
import com.virjar.tk.shared.repository.asSmallUploadSource
import com.virjar.tk.shared.repository.GroupFileRepository
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.shared.repository.OrganizationRepository
import com.virjar.tk.protocol.rpc.gen.ChatRpcProxy
import com.virjar.tk.protocol.rpc.gen.ConversationRpcProxy
import com.virjar.tk.protocol.rpc.gen.MessageRpcProxy
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 已部署服务器的协议级 E2E 业务验收。
 *
 * 通过 `./gradlew :server:server:acceptanceTest` 执行；该任务会注入仓库的部署配置。
 * 普通 `:server:server:test` 不启用本类，避免本地安全网依赖外部站点。
 * 用真实 [com.virjar.tk.shared.client.ImClient] + [com.virjar.tk.shared.client.RpcClient] 直连
 * `deployment.json` 指向的公网 TLS/TCP 端点，覆盖核心 IM 流程：
 * 注册 / 登录 / RPC / 好友 / 建群 / 发消息 / 订阅投递。
 *
 * 与 [ProtocolE2eTest] 互补：后者连 in-process 服务端（CI 常规 job），
 * 本类连接真实部署（验证端到端可达性，含真实 PG/RocksDB/文件存储）。
 *
 * 标准运行：
 * ```
 * ./gradlew :server:server:acceptanceTest
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class RemoteAcceptanceTest {

    private suspend fun upload(
        session: RemoteAcceptanceSupport.Session,
        bytes: ByteArray,
        fileName: String,
        contentType: String = "application/octet-stream",
    ): Attachment {
        val baseUrl = System.getProperty("tk.e2e.server") ?: "https://${RemoteAcceptanceSupport.host}"
        val repository = FileRepository(baseUrl, session.uid, session.userSession::httpCredentialsSnapshot)
        return try {
            repository.uploadSmallBytes(bytes, fileName, contentType).getOrThrow()
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

    private suspend fun downloadOutcome(
        session: RemoteAcceptanceSupport.Session,
        attachment: Attachment,
    ): Outcome<ByteArray> {
        val repository = FileRepository(baseUrl(), session.uid, session.userSession::httpCredentialsSnapshot)
        return try {
            repository.downloadSmall(attachment)
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
                    "client protocol=${ProtocolVersions.CURRENT}. " +
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

    @Tag("preview-smoke")
    @Test
    fun `login via TCP after register`() = runBlocking {
        // 动态注册一个账号，再用同账号登录
        val regPassword = "pass123"
        val regSession = RemoteAcceptanceSupport.registerUser("login", regPassword, "TestUser")
        val username = requireNotNull(regSession.registeredUsername)
        val uid = regSession.uid
        regSession.close()

        // 用新连接登录
        val loginSession = RemoteAcceptanceSupport.loginUser(username, regPassword)
        assertEquals(uid, loginSession.uid, "登录后 uid 应与注册时一致")
        loginSession.close()
    }

    @Test
    fun `login rejects wrong password`() = runBlocking {
        val regSession = RemoteAcceptanceSupport.registerUser("wrongpw", "pass123", "TestUser")
        val username = requireNotNull(regSession.registeredUsername)
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
        val resp = session.invoke(
            "conversation",
            ConversationRpcContract.M_LIST_PAGE,
            ProtoCodec.encode(ConversationPageRequest()),
        )
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
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            })
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
                writeString(UUID.randomUUID().toString())
                writeString("TestGroup")
                writeString(null) // 头像
                writeVarInt(1)    // 成员数
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

    @Tag("preview-smoke")
    @Test
    fun `scoped rich text asset delivers manifest bytes and live chat ACL`() = runBlocking {
        val (sender, recipient, chat) = createFriendPersonalChat("rich-asset")
        val outsider = RemoteAcceptanceSupport.registerUser("rich-asset-outsider")
        try {
            val bytes = ByteArray(96 * 1024) { index -> ((index * 31 + 17) % 251).toByte() }
            val attachment = upload(sender, bytes, "remote-rich-asset.bin")
            val asset = EmbeddedAsset(
                assetId = UUID.randomUUID().toString(),
                attachment = attachment,
            )
            val markdown = "[远程验收附件](${EmbeddedAsset.uri(asset.assetId)})"
            val body = buildRichTextBody(markdown, listOf(asset))

            assertEquals(listOf(asset), body.assets, "发送声明应保留 canonical scoped manifest")
            assertTrue("teamtalk-asset" !in body.plainText, "搜索纯文本不能泄露内部资产地址")
            val ack = sender.imClient.sendAndWaitAck(
                Message(
                    chatId = chat.chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = System.currentTimeMillis(),
                    senderUid = "",
                    body = body,
                ),
            )
            assertEquals(0, ack.code, "scoped 富消息 ACK 应成功: ${ack.reason}")

            val notify = recipient.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val received = ProtoCodec.decode(Message, requireNotNull(notify.payload))
            val receivedBody = assertInstanceOf(RichTextBody::class.java, received.body)
            assertEquals(markdown, receivedBody.markdown, "通知正文必须保留资产 placement")
            assertEquals(listOf(asset), receivedBody.assets, "通知必须携带完整 scoped manifest")
            assertArrayEquals(bytes, download(recipient, receivedBody.assets.single().attachment))

            assertBusinessFailure(
                downloadOutcome(outsider, receivedBody.assets.single().attachment),
                expectedCode = 403,
                message = "无关用户的 SDK 下载必须返回 Outcome.Failure",
            )
        } finally {
            sender.close(); recipient.close(); outsider.close()
        }
    }

    @Test
    fun `attachment upload replays exact result after TeamTalk service restart`() = runBlocking {
        val session = RemoteAcceptanceSupport.registerUser("core07-upload-replay")
        try {
            val repository = FileRepository(
                baseUrl(),
                session.uid,
                session.userSession::httpCredentialsSnapshot,
            )
            try {
                val originalBytes = ByteArray(128 * 1024) { index ->
                    ((index * 37 + 19) % 251).toByte()
                }
                val changedBytes = originalBytes.copyOf().also { bytes ->
                    bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x5A).toByte()
                }
                val originalSource = originalBytes.asSmallUploadSource()
                val changedSource = changedBytes.asSmallUploadSource()
                val identity = AttachmentUploadIdentity(
                    uploadId = UUID.randomUUID().toString(),
                    issuedAt = System.currentTimeMillis(),
                )
                val fileName = "core07-idempotent-upload.bin"
                val contentType = "application/octet-stream"

                val first = repository.uploadWithMeta(
                    source = originalSource,
                    fileName = fileName,
                    contentType = contentType,
                    identity = identity,
                ).getOrThrow()

                val authenticationBeforeRestart = session.authenticationCount
                val restartEvidence = withContext(Dispatchers.IO) {
                    RemoteTeamTalkServiceRestart().restart()
                }
                assertNotEquals(
                    restartEvidence.beforeInvocationId,
                    restartEvidence.afterInvocationId,
                    "上传重放验收必须经过 TeamTalk unit 的真实进程重启",
                )
                assertEquals(
                    authenticationBeforeRestart + 1,
                    session.awaitAuthenticationAfter(
                        authenticationBeforeRestart,
                        MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                    ),
                    "TeamTalk 重启后 SDK 会话应完成一次重新认证",
                )

                val replayed = repository.uploadWithMeta(
                    source = originalSource,
                    fileName = fileName,
                    contentType = contentType,
                    identity = identity,
                ).getOrThrow()
                assertEquals(first, replayed, "同一上传 identity 的完整 UploadResult 必须精确重放")
                assertEquals(first.file.path, replayed.file.path, "重放不能分配新的原文件路径")
                assertEquals(
                    first.thumbnail?.path,
                    replayed.thumbnail?.path,
                    "重放不能分配新的缩略图路径",
                )

                assertBusinessFailure(
                    repository.uploadWithMeta(
                        source = changedSource,
                        fileName = fileName,
                        contentType = contentType,
                        identity = identity,
                    ),
                    expectedCode = 409,
                    message = "同一上传 identity 改变 payload 必须返回冲突",
                )
                assertArrayEquals(
                    originalBytes,
                    repository.downloadSmall(first.file).getOrThrow(),
                    "冲突重放不能改写首次提交的附件字节",
                )
            } finally {
                repository.close()
            }
        } finally {
            session.close()
        }
    }

    // ── 多 body 类型消息往返 ──

    @Tag("preview-smoke")
    @Test
    fun `group file space keeps versions and member download ACL`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("group-file-owner")
        val member = RemoteAcceptanceSupport.registerUser("group-file-member")
        val outsider = RemoteAcceptanceSupport.registerUser("group-file-outsider")
        try {
            val chat = ChatRpcProxy(owner.rpc)
                .createGroup(UUID.randomUUID().toString(), "远程群文件验收", null, listOf(member.uid))
            val ownerFiles = GroupFileRepository(owner.rpc, null)
            val memberFiles = GroupFileRepository(member.rpc, null)
            val outsiderFiles = GroupFileRepository(outsider.rpc, null)

            val folder = ownerFiles.createFolder(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                chat.chatId,
                null,
                "项目资料",
            ).getOrThrow()
            val v1Bytes = "# Remote acceptance v1".encodeToByteArray()
            val v1Attachment = upload(owner, v1Bytes, "readme-v1.md")
            val file = ownerFiles.createFile(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                chat.chatId,
                folder.entryId,
                "README.md",
                v1Attachment,
            ).getOrThrow()

            assertEquals(listOf("README.md"), memberFiles.list(chat.chatId, folder.entryId).getOrThrow().map { it.name })
            assertArrayEquals(v1Bytes, download(member, v1Attachment))
            val memberRange = downloadHttpRange(
                attachment = v1Attachment,
                accessToken = member.userSession.accessToken,
                range = "bytes=2-8",
            )
            assertEquals(206, memberRange.status)
            assertEquals("bytes 2-8/${v1Bytes.size}", memberRange.contentRange)
            assertArrayEquals(v1Bytes.copyOfRange(2, 9), memberRange.body)
            assertTrue(outsiderFiles.list(chat.chatId, null) is Outcome.Failure, "非群成员不能读取群文件目录")

            val v2Bytes = "# Remote acceptance v2".encodeToByteArray()
            val v2Attachment = upload(member, v2Bytes, "readme-v2.md")
            val v2 = memberFiles.addVersion(
                UUID.randomUUID().toString(),
                chat.chatId,
                file.entryId,
                v2Attachment,
                file.revision,
            ).getOrThrow()
            assertEquals(2L, v2.contentVersion)
            assertEquals(listOf(2L, 1L), ownerFiles.listVersions(chat.chatId, file.entryId).getOrThrow().map { it.version })

            ownerFiles.delete(
                UUID.randomUUID().toString(),
                chat.chatId,
                v2.entryId,
                v2.revision,
            ).getOrThrow()
            assertDownloadRejected(v1Attachment, member.userSession.accessToken, 403)
            val revokedRange = downloadHttpRange(
                attachment = v1Attachment,
                accessToken = member.userSession.accessToken,
                range = "bytes=0-0",
            )
            assertEquals(403, revokedRange.status, "删除最后一个活动引用后，新的 Range 下载请求必须拒绝")
            assertNull(revokedRange.contentRange, "无权请求不能通过 Content-Range 探测对象大小")
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
            val ownerDocs = DocumentRepository(owner.rpc, FakeLocalCache())
            val memberDocs = DocumentRepository(member.rpc, FakeLocalCache())
            val outsiderDocs = DocumentRepository(outsider.rpc, FakeLocalCache())

            val space = requireNotNull(ownerDocs.createSpace(
                UUID.randomUUID().toString(),
                "远程产品空间",
                "独立于群聊的企业资产",
            ).getOrThrow().space)
            assertEquals(DocumentSpace.ROLE_OWNER, space.myRole)
            assertTrue(memberDocs.refreshAllDocumentSpacesForAcceptance().isEmpty())
            val memberGrant = ownerDocs.upsertGrant(
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member.uid,
                DocumentSpace.ROLE_EDITOR,
                false,
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            assertEquals(
                listOf(space.spaceId),
                memberDocs.refreshAllDocumentSpacesForAcceptance().map { it.spaceId },
            )
            assertTrue(
                outsiderDocs.refreshAllDocumentSpacesForAcceptance().isEmpty(),
                "未授权用户看不到空间",
            )

            val overview = ownerDocs.createDocument(
                UUID.randomUUID().toString(),
                space.spaceId,
                null,
                "产品资料",
                "# 产品资料\n空间内文档的综述入口。",
            ).getOrThrow().projection
            requireNotNull(overview) { "首次创建综述文档必须返回当前投影" }
            val created = ownerDocs.createDocument(
                UUID.randomUUID().toString(),
                space.spaceId,
                overview.documentId,
                "远程产品说明",
                "# 第一版\n由群主创建。",
            ).getOrThrow().projection
            requireNotNull(created) { "首次创建子文档必须返回当前投影" }
            assertEquals(1L, created.revision)
            assertEquals(
                listOf(created.documentId, overview.documentId),
                ownerDocs.listRecentDocuments(10).getOrThrow().map { it.documentId },
                "创建文档与创建者最近访问必须同一提交可见",
            )
            val rootNode = ownerDocs.listNodes(space.spaceId, null).getOrThrow().single()
            assertEquals(overview.documentId, rootNode.nodeId)
            assertTrue(rootNode.hasChildren)
            assertEquals(
                "# 产品资料\n空间内文档的综述入口。",
                ownerDocs.getDocument(space.spaceId, overview.documentId).getOrThrow().markdown,
            )
            val memberCreated = memberDocs.listRecentlyCreatedDocuments(10).getOrThrow()
                .single { it.documentId == created.documentId }
            assertEquals(created.documentId, memberCreated.documentId)
            assertEquals(space.name, memberCreated.spaceName)
            assertEquals("第一版", memberCreated.excerpt)
            assertTrue(memberDocs.listRecentDocuments(10).getOrThrow().isEmpty())
            assertEquals(
                listOf("远程产品说明"),
                memberDocs.listNodes(space.spaceId, overview.documentId).getOrThrow().map { it.name },
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
                "# 第二版\n由空间编辑者修订。",
                created.revision,
            ).getOrThrow().projection
            requireNotNull(updated) { "当前权限世代内的文档更新必须发布投影" }
            assertEquals(2L, updated.revision)
            assertEquals(
                "第二版",
                ownerDocs.listNodes(space.spaceId, overview.documentId).getOrThrow().single().excerpt,
                "目录投影摘要必须随正文保存更新",
            )
            assertTrue(
                ownerDocs.updateDocument(
                    space.spaceId,
                    created.documentId,
                    "不应成功",
                    created.revision,
                ) is Outcome.Failure,
                "旧 revision 不能覆盖其他成员的新版本",
            )
            assertEquals(
                listOf(2L, 1L),
                ownerDocs.listRevisions(space.spaceId, created.documentId)
                    .getOrThrow().items.map { it.revision },
            )
            assertEquals(
                "# 第一版\n由群主创建。",
                ownerDocs.getRevision(space.spaceId, created.documentId, 1)
                    .getOrThrow().markdown,
            )

            ownerDocs.removeGrant(
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member.uid,
                expectedPolicyRevision = memberGrant.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            assertTrue(
                memberDocs.getDocument(space.spaceId, created.documentId) is Outcome.Failure,
                "撤销空间授权后不能继续读取文档",
            )
            assertTrue(memberDocs.listRecentDocuments(10).getOrThrow().isEmpty(), "撤权后最近访问也必须过滤")
            assertTrue(memberDocs.listRecentlyCreatedDocuments(10).getOrThrow().isEmpty(), "撤权后最近创建也必须过滤")
            ownerDocs.deleteNode(
                space.spaceId,
                created.documentId,
                updated.revision,
                UUID.randomUUID().toString(),
            ).getOrThrow()
            assertEquals(
                listOf(overview.documentId),
                ownerDocs.listRecentDocuments(10).getOrThrow().map { it.documentId },
                "删除子文档后父综述仍可见",
            )
            assertEquals(
                listOf(overview.documentId),
                ownerDocs.listRecentlyCreatedDocuments(10).getOrThrow().map { it.documentId },
            )
            assertTrue(!ownerDocs.listNodes(space.spaceId, null).getOrThrow().single().hasChildren)
            ownerDocs.deleteNode(
                space.spaceId,
                overview.documentId,
                overview.revision,
                UUID.randomUUID().toString(),
            ).getOrThrow()
            assertTrue(ownerDocs.listNodes(space.spaceId, null).getOrThrow().isEmpty())
        } finally {
            owner.close(); member.close(); outsider.close()
        }
    }

    @Test
    fun `document scoped assets retain revision manifest and follow live space ACL`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("document-asset-owner")
        val member = RemoteAcceptanceSupport.registerUser("document-asset-member")
        val ownerCache = FakeLocalCache()
        val memberCache = FakeLocalCache()
        val ownerDocs = DocumentRepository(owner.rpc, ownerCache)
        val memberDocs = DocumentRepository(member.rpc, memberCache)
        var liveSpaceId: String? = null
        try {
            val space = requireNotNull(
                ownerDocs.createSpace(
                    UUID.randomUUID().toString(),
                    "远程文档资产验收",
                    "scoped embedded asset acceptance",
                ).getOrThrow().space,
            )
            liveSpaceId = space.spaceId
            val memberGrant = ownerDocs.upsertGrant(
                spaceId = space.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = member.uid,
                role = DocumentSpace.ROLE_VIEWER,
                includeDescendants = false,
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()

            val firstBytes = ByteArray(80 * 1024) { index -> ((index * 13 + 29) % 241).toByte() }
            val firstAsset = EmbeddedAsset(
                assetId = UUID.randomUUID().toString(),
                attachment = upload(owner, firstBytes, "document-asset-v1.bin"),
            )
            val firstMarkdown = "[第一版附件](${EmbeddedAsset.uri(firstAsset.assetId)})"
            val created = requireNotNull(
                ownerDocs.createDocument(
                    documentId = UUID.randomUUID().toString(),
                    spaceId = space.spaceId,
                    parentId = null,
                    title = "含附件的远程文档",
                    markdown = firstMarkdown,
                    assets = listOf(firstAsset),
                ).getOrThrow().projection,
            )
            assertEquals(listOf(firstAsset), created.assets, "createDocument 响应必须携带 scoped manifest")

            val memberProjection = memberDocs.getDocument(space.spaceId, created.documentId).getOrThrow()
            assertEquals(firstMarkdown, memberProjection.markdown)
            assertEquals(listOf(firstAsset), memberProjection.assets, "授权成员 getDocument 必须得到 sidecar")
            assertArrayEquals(firstBytes, download(member, memberProjection.assets.single().attachment))

            val secondBytes = ByteArray(72 * 1024) { index -> ((index * 19 + 7) % 239).toByte() }
            val secondAsset = EmbeddedAsset(
                assetId = UUID.randomUUID().toString(),
                attachment = upload(owner, secondBytes, "document-asset-v2.bin"),
            )
            val secondMarkdown = "[第二版附件](${EmbeddedAsset.uri(secondAsset.assetId)})"
            val updated = requireNotNull(
                ownerDocs.updateDocument(
                    spaceId = space.spaceId,
                    documentId = created.documentId,
                    markdown = secondMarkdown,
                    expectedRevision = created.revision,
                    assets = listOf(secondAsset),
                ).getOrThrow().projection,
            )
            assertEquals(2L, updated.revision)
            assertEquals(listOf(secondAsset), updated.assets)

            val firstRevision = memberDocs.getRevision(space.spaceId, created.documentId, 1).getOrThrow()
            val secondRevision = memberDocs.getRevision(space.spaceId, created.documentId, 2).getOrThrow()
            assertEquals(firstMarkdown, firstRevision.markdown)
            assertEquals(listOf(firstAsset), firstRevision.assets, "历史 revision 必须保留不可变 manifest")
            assertEquals(secondMarkdown, secondRevision.markdown)
            assertEquals(listOf(secondAsset), secondRevision.assets)
            assertArrayEquals(firstBytes, download(member, firstRevision.assets.single().attachment))
            assertArrayEquals(secondBytes, download(member, secondRevision.assets.single().attachment))

            ownerDocs.removeGrant(
                spaceId = space.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = member.uid,
                expectedPolicyRevision = memberGrant.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            assertBusinessFailure(
                memberDocs.getDocument(space.spaceId, created.documentId),
                expectedCode = 403,
                message = "撤权后成员不能继续读取文档",
            )
            assertBusinessFailure(
                downloadOutcome(member, firstRevision.assets.single().attachment),
                expectedCode = 403,
                message = "撤权后历史 revision 附件下载必须失败",
            )
            assertBusinessFailure(
                downloadOutcome(member, secondRevision.assets.single().attachment),
                expectedCode = 403,
                message = "撤权后当前 revision 附件下载必须失败",
            )
        } finally {
            liveSpaceId?.let { bestEffortArchiveSpace(it, ownerDocs) }
            ownerCache.close()
            memberCache.close()
            owner.close()
            member.close()
        }
    }

    @Test
    fun `document custody transfer preserves provenance and exact replay after authority loss`() = runBlocking {
        val creator = RemoteAcceptanceSupport.registerUser("custody-creator")
        val nextSteward = RemoteAcceptanceSupport.registerUser("custody-steward")
        val creatorCache = FakeLocalCache()
        val stewardCache = FakeLocalCache()
        val creatorDocs = DocumentRepository(creator.rpc, creatorCache)
        val stewardDocs = DocumentRepository(nextSteward.rpc, stewardCache)
        var liveSpaceId: String? = null
        try {
            val createSpaceId = UUID.randomUUID().toString()
            val createName = "远程归属交接空间"
            val createDescription = "验证不可变创建来源与可靠交接收据"
            val created = requireNotNull(creatorDocs.createSpace(
                createSpaceId,
                createName,
                createDescription,
            ).getOrThrow().space)
            liveSpaceId = created.spaceId
            assertEquals(creator.uid, created.createdBy)
            assertEquals(DocumentSpaceGrant.PRINCIPAL_USER, created.ownerPrincipalType)
            assertEquals(creator.uid, created.ownerPrincipalId)
            assertEquals(creator.uid, created.stewardUid)
            assertEquals(1L, created.custodyRevision)

            val createDocumentId = UUID.randomUUID().toString()
            val createDocumentTitle = "远程可靠创建文档"
            val createDocumentMarkdown = "# 首次提交\n响应丢失后必须可精确确认。"
            val createdDocument = requireNotNull(
                creatorDocs.createDocument(
                    documentId = createDocumentId,
                    spaceId = created.spaceId,
                    parentId = null,
                    title = createDocumentTitle,
                    markdown = createDocumentMarkdown,
                ).getOrThrow().projection,
            )
            assertEquals(createDocumentId, createdDocument.documentId)

            val operationId = UUID.randomUUID().toString()
            val transferred = creatorDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = nextSteward.uid,
                stewardUid = nextSteward.uid,
                expectedCustodyRevision = created.custodyRevision,
                operationId = operationId,
            ).getOrThrow()
            assertEquals(created.spaceId, transferred.spaceId)
            assertEquals(DocumentSpaceGrant.PRINCIPAL_USER, transferred.ownerPrincipalType)
            assertEquals(nextSteward.uid, transferred.ownerPrincipalId)
            assertEquals(nextSteward.uid, transferred.stewardUid)
            assertEquals(2L, transferred.custodyRevision)

            val stewardProjection = stewardDocs.refreshAllDocumentSpacesForAcceptance()
                .single { it.spaceId == created.spaceId }
            assertEquals(creator.uid, stewardProjection.createdBy, "交接后 createdBy 必须保持不变")
            assertEquals(DocumentSpace.ROLE_OWNER, stewardProjection.myRole)
            assertEquals(nextSteward.uid, stewardProjection.ownerPrincipalId)
            assertEquals(nextSteward.uid, stewardProjection.stewardUid)
            assertEquals(transferred.custodyRevision, stewardProjection.custodyRevision)
            assertTrue(
                creatorDocs.refreshAllDocumentSpacesForAcceptance().none { it.spaceId == created.spaceId },
                "没有剩余 grant 的旧 steward 必须失去空间可见性",
            )
            assertTrue(
                creatorDocs.listGrants(created.spaceId) is Outcome.Failure,
                "没有剩余 grant 的旧 steward 必须失去管理权限",
            )
            val documentReplayAfterAuthorityLoss = creatorDocs.createDocument(
                documentId = createDocumentId,
                spaceId = created.spaceId,
                parentId = null,
                title = createDocumentTitle,
                markdown = createDocumentMarkdown,
            ).getOrThrow()
            assertNull(
                documentReplayAfterAuthorityLoss.projection,
                "文档首次提交后即使创建者失权，精确重放仍须 Success(null)",
            )

            val exactReplay = creatorDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = nextSteward.uid,
                stewardUid = nextSteward.uid,
                expectedCustodyRevision = created.custodyRevision,
                operationId = operationId,
            ).getOrThrow()
            assertEquals(transferred, exactReplay, "旧 steward 失权后的精确重放必须返回同一收据")

            assertBusinessFailure(
                creatorDocs.transferSpaceCustody(
                    spaceId = created.spaceId,
                    ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                    ownerPrincipalId = nextSteward.uid,
                    stewardUid = nextSteward.uid,
                    expectedCustodyRevision = transferred.custodyRevision,
                    operationId = operationId,
                ),
                expectedCode = 409,
                message = "复用交接 operationId 改写 payload 必须被拒绝",
            )
            assertBusinessFailure(
                stewardDocs.transferSpaceCustody(
                    spaceId = created.spaceId,
                    ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                    ownerPrincipalId = nextSteward.uid,
                    stewardUid = nextSteward.uid,
                    expectedCustodyRevision = created.custodyRevision,
                    operationId = UUID.randomUUID().toString(),
                ),
                expectedCode = 409,
                message = "新的交接命令携带陈旧 custodyRevision 必须被拒绝",
            )

            val createReplayAfterAuthorityLoss = creatorDocs.createSpace(
                createSpaceId,
                createName,
                createDescription,
            ).getOrThrow()
            assertEquals(createSpaceId, createReplayAfterAuthorityLoss.spaceId)
            assertNull(
                createReplayAfterAuthorityLoss.space,
                "原创建者失去 steward 身份后，创建重放只能确认提交，不能复活 Owner 投影",
            )

            val transferredBack = stewardDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = creator.uid,
                stewardUid = creator.uid,
                expectedCustodyRevision = transferred.custodyRevision,
                operationId = UUID.randomUUID().toString(),
            ).getOrThrow()
            assertEquals(3L, transferredBack.custodyRevision)
            creatorDocs.archiveSpace(created.spaceId, UUID.randomUUID().toString()).getOrThrow()
            liveSpaceId = null

            val documentReplayAfterArchive = creatorDocs.createDocument(
                documentId = createDocumentId,
                spaceId = created.spaceId,
                parentId = null,
                title = createDocumentTitle,
                markdown = createDocumentMarkdown,
            ).getOrThrow()
            assertNull(
                documentReplayAfterArchive.projection,
                "空间归档后，已提交文档创建的精确重放仍须 Success(null)",
            )

            val replayAfterLaterTransferAndArchive = creatorDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = nextSteward.uid,
                stewardUid = nextSteward.uid,
                expectedCustodyRevision = created.custodyRevision,
                operationId = operationId,
            ).getOrThrow()
            assertEquals(
                transferred,
                replayAfterLaterTransferAndArchive,
                "后续交接和归档不能改写原命令的不可变收据",
            )
        } finally {
            liveSpaceId?.let { bestEffortArchiveSpace(it, stewardDocs, creatorDocs) }
            creatorCache.close()
            stewardCache.close()
            creator.close()
            nextSteward.close()
        }
    }

    @Test
    fun `organization ACL inheritance and custody lifecycle work on the deployed server`(): Unit = runBlocking {
        val adminToken = adminLogin(remoteAdminCredentials())
        val creator = RemoteAcceptanceSupport.registerUser("org-custody-creator")
        val nextSteward = RemoteAcceptanceSupport.registerUser("org-custody-steward")
        val directMember = RemoteAcceptanceSupport.registerUser("org-acl-direct")
        val descendantMember = RemoteAcceptanceSupport.registerUser("org-acl-descendant")
        val creatorCache = FakeLocalCache()
        val stewardCache = FakeLocalCache()
        val directMemberCache = FakeLocalCache()
        val descendantMemberCache = FakeLocalCache()
        val creatorDocs = DocumentRepository(creator.rpc, creatorCache)
        val creatorOrganization = OrganizationRepository(creator.rpc, creatorCache)
        val stewardDocs = DocumentRepository(nextSteward.rpc, stewardCache)
        val directMemberDocs = DocumentRepository(directMember.rpc, directMemberCache)
        val descendantMemberDocs = DocumentRepository(descendantMember.rpc, descendantMemberCache)
        var rootToArchive: String? = null
        var unitToArchive: String? = null
        var descendantUnitToArchive: String? = null
        var liveSpaceId: String? = null
        try {
            val existingRoot = adminListUnits(adminToken).singleOrNull { it.parentId == null }
            val root = existingRoot ?: adminCreateUnit(
                adminToken,
                OrganizationUnitRequest(name = "远程验收组织-${UUID.randomUUID().toString().take(8)}"),
            ).also { rootToArchive = it.unitId }
            val unitsBeforeMutation = creatorOrganization.refreshUnits().getOrThrow()
            assertTrue(
                unitsBeforeMutation.any { it.unitId == root.unitId },
                "组织目录二进制 RPC 必须先收敛到当前根节点",
            )
            val revisionBeforeMutation = creatorOrganization.cachedUnitProjection().revision
            val owningUnit = adminCreateUnit(
                adminToken,
                OrganizationUnitRequest(
                    parentId = root.unitId,
                    name = "远程验收资产部门-${UUID.randomUUID().toString().take(8)}",
                ),
            )
            unitToArchive = owningUnit.unitId
            val changedRevision = awaitOrganizationChangedAfter(creator, revisionBeforeMutation)
            val unitsAfterMutation = creatorOrganization.refreshUnits().getOrThrow()
            assertTrue(
                unitsAfterMutation.any { it.unitId == owningUnit.unitId },
                "OrganizationRepository 必须通过二进制分页 RPC 收集完整快照，不能只假设新节点在首页",
            )
            assertTrue(
                creatorOrganization.cachedUnitProjection().revision >= changedRevision,
                "二进制 RPC 快照 revision 不能落后于已收到的组织变更通知",
            )
            val descendantUnit = adminCreateUnit(
                adminToken,
                OrganizationUnitRequest(
                    parentId = owningUnit.unitId,
                    name = "远程验收下级部门-${UUID.randomUUID().toString().take(8)}",
                ),
            )
            descendantUnitToArchive = descendantUnit.unitId
            adminAssignMember(
                adminToken,
                owningUnit.unitId,
                OrganizationMemberRequest(directMember.uid, title = "直属成员"),
            )
            adminAssignMember(
                adminToken,
                descendantUnit.unitId,
                OrganizationMemberRequest(descendantMember.uid, title = "下级成员"),
            )

            val createSpaceId = UUID.randomUUID().toString()
            val createName = "远程组织归属空间"
            val created = requireNotNull(creatorDocs.createSpace(
                createSpaceId,
                createName,
                null,
            ).getOrThrow().space)
            liveSpaceId = created.spaceId
            val document = requireNotNull(creatorDocs.createDocument(
                documentId = UUID.randomUUID().toString(),
                spaceId = created.spaceId,
                parentId = null,
                title = "远程组织授权文档",
                markdown = "# 组织授权验收",
            ).getOrThrow().projection)
            val organizationOwnership = creatorDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                ownerPrincipalId = owningUnit.unitId,
                stewardUid = creator.uid,
                expectedCustodyRevision = created.custodyRevision,
                operationId = UUID.randomUUID().toString(),
            ).getOrThrow()
            assertEquals(2L, organizationOwnership.custodyRevision)

            val replayWhileCreatorStillSteward = creatorDocs.createSpace(
                createSpaceId,
                createName,
                null,
            ).getOrThrow()
            val currentOrganizationProjection = requireNotNull(replayWhileCreatorStillSteward.space)
            assertEquals(owningUnit.unitId, currentOrganizationProjection.ownerPrincipalId)
            assertEquals(creator.uid, currentOrganizationProjection.stewardUid)
            assertEquals(organizationOwnership.custodyRevision, currentOrganizationProjection.custodyRevision)
            assertEquals(DocumentSpace.ROLE_OWNER, currentOrganizationProjection.myRole)

            val organizationCustody = creatorDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                ownerPrincipalId = owningUnit.unitId,
                stewardUid = nextSteward.uid,
                expectedCustodyRevision = organizationOwnership.custodyRevision,
                operationId = UUID.randomUUID().toString(),
            ).getOrThrow()
            assertEquals(3L, organizationCustody.custodyRevision)

            assertTrue(
                directMemberDocs.refreshAllDocumentSpacesForAcceptance().none { it.spaceId == created.spaceId },
                "组织 owner 本身不能把空间权限隐式授给直属成员",
            )
            assertTrue(
                descendantMemberDocs.refreshAllDocumentSpacesForAcceptance().none { it.spaceId == created.spaceId },
                "组织 owner 本身不能把空间权限隐式授给下级成员",
            )
            assertBusinessFailure(
                directMemberDocs.getDocument(created.spaceId, document.documentId),
                expectedCode = 403,
                message = "没有显式 grant 时组织 owner 的直属成员不能读取文档",
            )

            val directUnitGrant = stewardDocs.upsertGrant(
                spaceId = created.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                principalId = owningUnit.unitId,
                role = DocumentSpace.ROLE_VIEWER,
                includeDescendants = false,
                expectedPolicyRevision = created.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            assertEquals(
                DocumentSpace.ROLE_VIEWER,
                directMemberDocs.refreshAllDocumentSpacesForAcceptance()
                    .single { it.spaceId == created.spaceId }.myRole,
                "组织 direct grant 必须授予直属成员",
            )
            assertEquals(
                document.documentId,
                directMemberDocs.getDocument(created.spaceId, document.documentId).getOrThrow().documentId,
            )
            assertTrue(
                descendantMemberDocs.refreshAllDocumentSpacesForAcceptance().none { it.spaceId == created.spaceId },
                "includeDescendants=false 不能授予下级部门成员",
            )
            assertBusinessFailure(
                descendantMemberDocs.getDocument(created.spaceId, document.documentId),
                expectedCode = 403,
                message = "includeDescendants=false 时下级部门成员不能读取文档",
            )

            val inheritedUnitGrant = stewardDocs.upsertGrant(
                spaceId = created.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                principalId = owningUnit.unitId,
                role = DocumentSpace.ROLE_VIEWER,
                includeDescendants = true,
                expectedPolicyRevision = directUnitGrant.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            assertEquals(
                DocumentSpace.ROLE_VIEWER,
                descendantMemberDocs.refreshAllDocumentSpacesForAcceptance()
                    .single { it.spaceId == created.spaceId }.myRole,
                "includeDescendants=true 必须把授权继承给下级部门成员",
            )
            assertEquals(
                document.documentId,
                descendantMemberDocs.getDocument(created.spaceId, document.documentId).getOrThrow().documentId,
            )

            val blockedStatus = adminArchiveUnit(adminToken, owningUnit.unitId)
            assertEquals(409, blockedStatus, "持有活动文档空间的组织节点必须稳定返回冲突")
            val stillActiveUnit = adminListUnits(adminToken).singleOrNull {
                it.unitId == owningUnit.unitId
            }
            assertNotNull(stillActiveUnit, "归档拒绝后组织节点必须仍在活动目录中")
            assertEquals(
                OrganizationUnit.STATUS_ACTIVE,
                requireNotNull(stillActiveUnit).status,
                "归档拒绝后组织节点必须仍保持活动状态",
            )

            val returnedToUser = stewardDocs.transferSpaceCustody(
                spaceId = created.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = nextSteward.uid,
                stewardUid = nextSteward.uid,
                expectedCustodyRevision = organizationCustody.custodyRevision,
                operationId = UUID.randomUUID().toString(),
            ).getOrThrow()
            assertEquals(4L, returnedToUser.custodyRevision)
            stewardDocs.removeGrant(
                created.spaceId,
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                owningUnit.unitId,
                expectedPolicyRevision = inheritedUnitGrant.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            stewardDocs.archiveSpace(created.spaceId, UUID.randomUUID().toString()).getOrThrow()
            liveSpaceId = null
            assertEquals(200, adminRemoveMember(adminToken, descendantUnit.unitId, descendantMember.uid))
            assertEquals(200, adminRemoveMember(adminToken, owningUnit.unitId, directMember.uid))
            assertEquals(200, adminArchiveUnit(adminToken, descendantUnit.unitId))
            descendantUnitToArchive = null
            assertEquals(200, adminArchiveUnit(adminToken, owningUnit.unitId))
            unitToArchive = null
            rootToArchive?.let { rootId ->
                if (adminArchiveUnit(adminToken, rootId) == 200) rootToArchive = null
            }
        } finally {
            liveSpaceId?.let { spaceId ->
                bestEffortArchiveSpace(spaceId, stewardDocs, creatorDocs)
            }
            descendantUnitToArchive?.let { unitId ->
                runCatching { adminRemoveMember(adminToken, unitId, descendantMember.uid) }
                runCatching { adminArchiveUnit(adminToken, unitId) }
            }
            unitToArchive?.let { unitId ->
                runCatching { adminRemoveMember(adminToken, unitId, directMember.uid) }
                runCatching { adminArchiveUnit(adminToken, unitId) }
            }
            rootToArchive?.let { runCatching { adminArchiveUnit(adminToken, it) } }
            creatorCache.close()
            stewardCache.close()
            directMemberCache.close()
            descendantMemberCache.close()
            creator.close()
            nextSteward.close()
            directMember.close()
            descendantMember.close()
        }
    }

    @Tag("preview-smoke")
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
            val ownerRange = downloadHttpRange(
                attachment = attachment,
                accessToken = user1.userSession.accessToken,
                range = "bytes=1024-2047",
            )
            assertEquals(206, ownerRange.status)
            assertEquals("bytes", ownerRange.acceptRanges)
            assertEquals("bytes 1024-2047/${bytes.size}", ownerRange.contentRange)
            assertEquals(1024L, ownerRange.contentLength)
            assertArrayEquals(bytes.copyOfRange(1024, 2048), ownerRange.body)

            val invalidRange = downloadHttpRange(
                attachment = attachment,
                accessToken = user1.userSession.accessToken,
                range = "bytes=${bytes.size}-",
            )
            assertEquals(416, invalidRange.status)
            assertEquals("bytes */${bytes.size}", invalidRange.contentRange)
            assertEquals(0, invalidRange.body.size)
            assertDownloadRejected(attachment, null, 401)
            assertDownloadRejected(attachment, stranger.userSession.accessToken, 403)
            val strangerRange = downloadHttpRange(
                attachment = attachment,
                accessToken = stranger.userSession.accessToken,
                range = "bytes=0-0",
            )
            assertEquals(403, strangerRange.status)
            assertNull(strangerRange.contentRange, "无关用户不能通过 Range 响应获知附件大小")

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
            val recipientSuffix = downloadHttpRange(
                attachment = body.attachment,
                accessToken = user2.userSession.accessToken,
                range = "bytes=-37",
            )
            assertEquals(206, recipientSuffix.status)
            assertEquals("bytes ${bytes.size - 37}-${bytes.size - 1}/${bytes.size}", recipientSuffix.contentRange)
            assertArrayEquals(bytes.copyOfRange(bytes.size - 37, bytes.size), recipientSuffix.body)
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
    fun `forward message via RPC`(): Unit = runBlocking {
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

    @Tag("preview-smoke")
    @Test
    fun `saved messages chat keeps private copies with idempotent replay`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("saved")
        try {
            val keyword = "savedneedle${UUID.randomUUID().toString().replace("-", "")}"
            val searchableText = "$keyword save this across restarts"
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = buildRichTextBody(searchableText),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)

            // saved 会话幂等取回（保存者是 user2）
            val savedChat1 = user2.invoke("chat", ChatRpcContract.M_GET_OR_CREATE_SAVED_CHAT, null)
            assertEquals(0, savedChat1.status)
            val savedChat = ProtoCodec.decode(Chat, savedChat1.payload!!)
            assertEquals(3, savedChat.chatType)
            val savedChat2 = user2.invoke("chat", ChatRpcContract.M_GET_OR_CREATE_SAVED_CHAT, null)
            assertEquals(
                savedChat.chatId,
                ProtoCodec.decode(Chat, savedChat2.payload!!).chatId,
                "saved 会话必须幂等复用",
            )
            val conversationRpc = ConversationRpcProxy(user2.rpc)
            val emptySaved = conversationRpc.listPage(ConversationPageRequest()).items
                .single { it.chatId == savedChat.chatId }
            assertEquals(0L, emptySaved.lastSeq, "空 saved 会话尚不应满足客户端展示条件")
            assertNull(emptySaved.lastMsgTimestamp)
            assertFalse(emptySaved.isPinned, "保存的消息不能默认置顶")

            // 保存 + 同 operationId 重放
            fun savePayload(seq: Long, operationId: String) = ProtoCodec.encodePayload {
                writeString(chat.chatId); writeVarLong(seq); writeString(operationId)
            }
            val save1 = user2.invoke("message", MessageRpcContract.M_SAVE_MESSAGE, savePayload(ack.serverSeq, "save-e2e-1"))
            assertEquals(0, save1.status)
            val savedMsg = ProtoCodec.decode(Message, save1.payload!!)
            assertEquals(savedChat.chatId, savedMsg.chatId, "副本必须落在保存者的 saved 会话")
            assertEquals("save-e2e-1", savedMsg.clientMsgId)
            val source = user2.awaitMessage(chat.chatId, ack.serverSeq)
            assertTrue(savedMsg.timestamp > source.timestamp, "副本使用本次保存时间，而非源消息时间")
            val replay = user2.invoke("message", MessageRpcContract.M_SAVE_MESSAGE, savePayload(ack.serverSeq, "save-e2e-1"))
            assertEquals(0, replay.status)
            val replayed = ProtoCodec.decode(Message, replay.payload!!)
            assertEquals(
                savedMsg.serverSeq,
                replayed.serverSeq,
                "重放必须返回原副本",
            )
            assertEquals(savedMsg.timestamp, replayed.timestamp, "重放不能改变首次保存时间")

            // 保存者收到副本投递；saved 会话的展示名为固定"保存的消息"（过滤旧私聊事件）
            val recv = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val delivered = ProtoCodec.decode(Message, recv.payload!!)
            assertEquals(savedMsg.serverSeq, delivered.serverSeq)
            val savedConversation = user2.awaitConversation(savedChat.chatId) { it.lastSeq == savedMsg.serverSeq }
            assertEquals("保存的消息", savedConversation.chatName)
            assertEquals(savedMsg.timestamp, savedConversation.lastMsgTimestamp, "Notify需携带真实消息时间")
            assertEquals(0, savedConversation.unreadCount, "本人保存的消息不新增未读")
            assertFalse(savedConversation.isPinned)

            // Saved复用普通会话的置顶设置；Notify和重新拉取都保留最后消息时间。
            for (pinned in listOf(true, false)) {
                conversationRpc.setPin(savedChat.chatId, pinned)
                val changed = user2.awaitConversation(savedChat.chatId) { it.isPinned == pinned }
                assertEquals(savedMsg.timestamp, changed.lastMsgTimestamp)
                val snapshot = conversationRpc.listPage(ConversationPageRequest()).items
                    .single { it.chatId == savedChat.chatId }
                assertEquals(pinned, snapshot.isPinned)
                assertEquals(savedMsg.timestamp, snapshot.lastMsgTimestamp)
            }

            // 原消息撤回后副本保留
            user1.invoke("message", MessageRpcContract.M_REVOKE, ProtoCodec.encodePayload {
                writeString(chat.chatId); writeVarLong(ack.serverSeq)
            }).let { assertEquals(0, it.status) }
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            val history = user2.invoke(
                "message", MessageRpcContract.M_GET_HISTORY,
                ProtoCodec.encodePayload {
                    writeString(savedChat.chatId); writeVarLong(0); writeVarInt(10)
                },
            )
            assertEquals(0, history.status)
            val savedHistory = ProtoCodec.decodeList(Message, history.payload!!)
            assertEquals(
                0,
                savedHistory.count { it.serverSeq == savedMsg.serverSeq && it.flags and 1 != 0 },
                "源撤回不得影响已保存副本",
            )

            // 源消息已撤回：唯一关键词仍能精确找到保存者的副本，重放不能复制搜索命中。
            val savedHits = MessageRpcProxy(user2.rpc).search(savedChat.chatId, keyword, 10)
            assertEquals(
                listOf(savedChat.chatId to savedMsg.serverSeq),
                savedHits.map { it.chatId to it.serverSeq },
                "保存的消息搜索必须只返回当前用户的唯一副本",
            )
            assertEquals(searchableText, (savedHits.single().body as RichTextBody).markdown)
            // 空 chatId 按服务端计算的可访问会话搜索，原发送者不能搜到他人的私有副本。
            assertTrue(
                MessageRpcProxy(user1.rpc).search("", keyword, 10).isEmpty(),
                "源撤回后，原发送者的全局搜索不得泄露他人保存的消息",
            )

            val newerAck = user1.imClient.sendAndWaitAck(msg.copy(
                clientMsgId = UUID.randomUUID().toString(),
                body = buildRichTextBody("new conversation activity after saving"),
            ))
            assertEquals(0, newerAck.code)
            val newerMessage = user2.awaitMessage(chat.chatId, newerAck.serverSeq)
            val newerConversation = user2.awaitConversation(chat.chatId) { it.lastSeq == newerAck.serverSeq }
            assertEquals(newerMessage.timestamp, newerConversation.lastMsgTimestamp)
            assertTrue(
                newerConversation.lastMsgTimestamp!! > savedMsg.timestamp,
                "取消置顶后，后来的私聊消息必须拥有更晚的会话排序时间",
            )
        } finally {
            user1.close(); user2.close()
        }
    }

    @Tag("preview-smoke")
    @Test
    fun `message reactions converge idempotently across both members`() = runBlocking {
        val (user1, user2, chat) = createFriendPersonalChat("reaction")
        try {
            val msg = Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = buildRichTextBody("react to me"),
            )
            val ack = user1.imClient.sendAndWaitAck(msg)
            assertEquals(0, ack.code)
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)

            fun addReactionPayload(seq: Long, emoji: String) = ProtoCodec.encodePayload {
                writeString(chat.chatId); writeVarLong(seq); writeString(emoji)
            }

            // 两个成员各自添加；重复添加幂等成功
            val seq = ack.serverSeq
            repeat(2) {
                assertEquals(
                    0,
                    user1.invoke("message", MessageRpcContract.M_ADD_REACTION, addReactionPayload(seq, "👍")).status,
                )
            }
            assertEquals(
                0,
                user2.invoke("message", MessageRpcContract.M_ADD_REACTION, addReactionPayload(seq, "👍")).status,
            )
            user2.invoke("message", MessageRpcContract.M_ADD_REACTION, addReactionPayload(seq, "🎉")).let {
                assertEquals(0, it.status)
            }

            // 双方都收到三个 add 事件；payload 行级 delta 与 actor 一致
            listOf(user1, user2).forEach { member ->
                repeat(3) {
                    val notify = member.awaitNotify(NotifyType.MESSAGE_REACTION.code, 10_000)
                    val payload = ProtoCodec.decode(MessageReactionEventPayload, notify.payload!!)
                    assertEquals(seq, payload.serverSeq)
                    assertEquals(chat.chatId, payload.chatId)
                    assertTrue(payload.added)
                }
            }

            // 权威聚合：👍 = 2 人，🎉 = 1 人
            val listResp = user1.invoke(
                "message",
                MessageRpcContract.M_LIST_REACTIONS,
                ProtoCodec.encodePayload {
                    writeString(chat.chatId); writeVarLong(seq); writeVarLong(seq)
                },
            )
            assertEquals(0, listResp.status)
            val summaries = ProtoCodec.decodeList(MessageReactionSummary, listResp.payload!!)
            assertEquals(1, summaries.size)
            val groups = summaries.single().groups.associateBy { it.emoji }
            assertEquals(2, groups.getValue("👍").reactorUids.size)
            assertEquals(1, groups.getValue("🎉").reactorUids.size)

            // 移除 + 重复移除幂等；对端收到 remove delta
            user2.invoke("message", MessageRpcContract.M_REMOVE_REACTION, addReactionPayload(seq, "🎉")).let {
                assertEquals(0, it.status)
            }
            user2.invoke("message", MessageRpcContract.M_REMOVE_REACTION, addReactionPayload(seq, "🎉")).let {
                assertEquals(0, it.status)
            }
            val removed = user1.awaitNotify(NotifyType.MESSAGE_REACTION.code, 10_000)
            val removedPayload = ProtoCodec.decode(MessageReactionEventPayload, removed.payload!!)
            assertEquals("🎉", removedPayload.emoji)
            assertTrue(!removedPayload.added)

            // 移除后聚合只剩 👍
            val afterResp = user1.invoke(
                "message",
                MessageRpcContract.M_LIST_REACTIONS,
                ProtoCodec.encodePayload {
                    writeString(chat.chatId); writeVarLong(seq); writeVarLong(seq)
                },
            )
            assertEquals(0, afterResp.status)
            val after = ProtoCodec.decodeList(MessageReactionSummary, afterResp.payload!!)
            assertEquals(listOf("👍"), after.single().groups.map { it.emoji })

            // 撤回清空权威聚合；对端撤回投递后不能再回应
            user1.invoke("message", MessageRpcContract.M_REVOKE, ProtoCodec.encodePayload {
                writeString(chat.chatId); writeVarLong(seq)
            }).let { assertEquals(0, it.status) }
            user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 10_000)
            user2.invoke("message", MessageRpcContract.M_ADD_REACTION, addReactionPayload(seq, "👍")).let {
                assertTrue(it.status != 0, "已撤回消息不能再回应")
            }
            user1.invoke(
                "message",
                MessageRpcContract.M_LIST_REACTIONS,
                ProtoCodec.encodePayload {
                    writeString(chat.chatId); writeVarLong(seq); writeVarLong(seq)
                },
            ).let {
                assertEquals(0, it.status)
                assertTrue(ProtoCodec.decodeList(MessageReactionSummary, it.payload!!).isEmpty())
            }
        } finally {
            user1.close(); user2.close()
        }
    }

    @Tag("preview-smoke")
    @Test
    fun `same uid devices converge message and conversation state after one connection reconnects`(): Unit =
        runBlocking {
            createMultiDevicePersonalChat("core04").use { fixture ->
                val chatId = fixture.chat.chatId
                val primaryConversationRpc = ConversationRpcProxy(fixture.primary.rpc)
                val secondaryConversationRpc = ConversationRpcProxy(fixture.secondary.rpc)
                val primaryMessageRpc = MessageRpcProxy(fixture.primary.rpc)
                val secondaryMessageRpc = MessageRpcProxy(fixture.secondary.rpc)

                // CHAT_CREATED 持久化 Chat 并把生产会话列表标记为脏；
                // 这个窄投影有意没有仓库刷新回调。下面首次类型的会话变更
                // 会发布这个收敛测试在同 uid 两条连接上所需的权威快照。
                coroutineScope {
                    val pin = async { primaryConversationRpc.setPin(chatId, true) }
                    val mute = async { secondaryConversationRpc.setMute(chatId, true) }
                    pin.await()
                    mute.await()
                }
                primaryConversationRpc.setDraft(chatId, MULTI_DEVICE_INITIAL_DRAFT)
                val primarySettings = fixture.primary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation ->
                    conversation.isPinned &&
                        conversation.isMuted &&
                        conversation.draft == MULTI_DEVICE_INITIAL_DRAFT
                }
                val secondarySettings = fixture.secondary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation ->
                    conversation.isPinned &&
                        conversation.isMuted &&
                        conversation.draft == MULTI_DEVICE_INITIAL_DRAFT
                }
                assertEquals(primarySettings, secondarySettings, "两个设备应收敛到相同会话设置与草稿")
                val initialPrimaryAuthority = primaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                val initialSecondaryAuthority = secondaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                assertEquals(MULTI_DEVICE_INITIAL_DRAFT, initialPrimaryAuthority.draft)
                assertEquals(initialPrimaryAuthority, initialSecondaryAuthority, "初始草稿的权威状态不应按设备分叉")
                assertEquals(initialPrimaryAuthority, primarySettings, "主设备应投影初始权威草稿")
                assertEquals(initialSecondaryAuthority, secondarySettings, "第二设备应投影初始权威草稿")

                val editable = Message(
                    chatId = chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = System.currentTimeMillis(),
                    senderUid = "",
                    body = buildRichTextBody("multi-device-original"),
                )
                val editableAck = fixture.primary.imClient.sendAndWaitAck(editable)
                assertEquals(0, editableAck.code, "多设备编辑基线消息应发送成功: ${editableAck.reason}")
                val editableSeq = editableAck.serverSeq
                listOf(fixture.primary, fixture.secondary, fixture.peer).forEach { session ->
                    session.awaitMessage(chatId, editableSeq, MULTI_DEVICE_TIMEOUT_MS)
                }

                val editFromPrimary = editable.copy(
                    serverSeq = editableSeq,
                    senderUid = fixture.primary.uid,
                    body = buildRichTextBody("multi-device-edit-primary"),
                )
                val editFromSecondary = editFromPrimary.copy(
                    body = buildRichTextBody("multi-device-edit-secondary"),
                )
                coroutineScope {
                    val firstEdit = async { primaryMessageRpc.edit(editFromPrimary) }
                    val secondEdit = async { secondaryMessageRpc.edit(editFromSecondary) }
                    firstEdit.await()
                    secondEdit.await()
                }

                val authoritativeEdited = primaryMessageRpc
                    .getHistory(chatId, 0L, Message.MAX_QUERY_PAGE_SIZE)
                    .single { message -> message.serverSeq == editableSeq }
                assertTrue(authoritativeEdited.flags and Message.FLAG_EDITED != 0)
                val authoritativeEditedMarkdown = (authoritativeEdited.body as? RichTextBody)?.markdown
                assertTrue(
                    authoritativeEditedMarkdown != null &&
                        authoritativeEditedMarkdown in MULTI_DEVICE_EDIT_CANDIDATES,
                    "并发编辑的最终正文必须来自一个已提交候选",
                )
                val authoritativeEditedBytes = ProtoCodec.encode(authoritativeEdited)
                val editedMessages = listOf(fixture.primary, fixture.secondary, fixture.peer).map { session ->
                    session.awaitMessage(chatId, editableSeq, MULTI_DEVICE_TIMEOUT_MS) { message ->
                        ProtoCodec.encode(message).contentEquals(authoritativeEditedBytes)
                    }
                }
                assertMessagesEqual(
                    editedMessages + authoritativeEdited,
                    "并发编辑后各连接与权威历史的最终消息应一致",
                )

                coroutineScope {
                    val firstRevoke = async { primaryMessageRpc.revoke(chatId, editableSeq) }
                    val secondRevoke = async { secondaryMessageRpc.revoke(chatId, editableSeq) }
                    firstRevoke.await()
                    secondRevoke.await()
                }
                val revokedMessages = listOf(fixture.primary, fixture.secondary, fixture.peer).map { session ->
                    session.awaitMessage(chatId, editableSeq, MULTI_DEVICE_TIMEOUT_MS) { message ->
                        message.flags and Message.FLAG_EDITED != 0 &&
                            message.flags and Message.FLAG_REVOKED != 0
                    }
                }
                assertMessagesEqual(revokedMessages, "幂等撤回后各连接的最终消息应一致")

                val peerSequences = mutableListOf<Long>()
                repeat(3) { index ->
                    val message = Message(
                        chatId = chatId,
                        clientMsgId = UUID.randomUUID().toString(),
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = System.currentTimeMillis(),
                        senderUid = "",
                        body = buildRichTextBody("multi-device-peer-$index"),
                    )
                    val ack = fixture.peer.imClient.sendAndWaitAck(message)
                    assertEquals(0, ack.code, "对端第 $index 条消息应发送成功: ${ack.reason}")
                    peerSequences += ack.serverSeq
                }
                val lowReadSeq = peerSequences.first()
                val highReadSeq = peerSequences[1]
                val lastReadSeq = peerSequences.last()
                fixture.primary.awaitMessage(chatId, lastReadSeq, MULTI_DEVICE_TIMEOUT_MS)
                fixture.secondary.awaitMessage(chatId, lastReadSeq, MULTI_DEVICE_TIMEOUT_MS)

                coroutineScope {
                    val highRead = async { primaryMessageRpc.markRead(chatId, highReadSeq) }
                    val lowRead = async { secondaryMessageRpc.markRead(chatId, lowReadSeq) }
                    highRead.await()
                    lowRead.await()
                }
                // 迟到的较低水位是显式 no-op，与上述并发顺序无关。
                secondaryMessageRpc.markRead(chatId, lowReadSeq)
                val primaryPartiallyRead = fixture.primary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation -> conversation.readSeq == highReadSeq && conversation.unreadCount == 1 }
                val secondaryPartiallyRead = fixture.secondary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation -> conversation.readSeq == highReadSeq && conversation.unreadCount == 1 }
                assertEquals(primaryPartiallyRead, secondaryPartiallyRead, "低水位不能使任一设备的已读状态回退")
                fixture.peer.awaitConversation(chatId, MULTI_DEVICE_TIMEOUT_MS) { conversation ->
                    conversation.peerReadSeq == highReadSeq
                }

                val authenticationBeforeDrop = fixture.secondary.authenticationCount
                fixture.secondary.imClient.simulateNetworkDropAndPauseReconnect()
                withTimeout(5_000) {
                    fixture.secondary.imClient.state.first { state -> state == ConnectionState.DISCONNECTED }
                }

                coroutineScope {
                    val unpin = async { primaryConversationRpc.setPin(chatId, false) }
                    val unmute = async { primaryConversationRpc.setMute(chatId, false) }
                    val finalDraft = async {
                        primaryConversationRpc.setDraft(chatId, MULTI_DEVICE_FINAL_DRAFT)
                    }
                    val finalRead = async { primaryMessageRpc.markRead(chatId, lastReadSeq) }
                    unpin.await()
                    unmute.await()
                    finalDraft.await()
                    finalRead.await()
                }
                assertEquals(
                    authenticationBeforeDrop,
                    fixture.secondary.authenticationCount,
                    "第二设备必须在全部断线期写入提交后才恢复认证",
                )
                val primaryFinal = fixture.primary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation ->
                    !conversation.isPinned &&
                        !conversation.isMuted &&
                        conversation.readSeq == lastReadSeq &&
                        conversation.unreadCount == 0 &&
                        conversation.draft == MULTI_DEVICE_FINAL_DRAFT
                }

                fixture.secondary.imClient.resumeReconnectAfterSimulatedDrop()
                fixture.secondary.awaitAuthenticationAfter(
                    authenticationBeforeDrop,
                    MULTI_DEVICE_RECONNECT_TIMEOUT_MS,
                )
                val requiredCursor = fixture.primary.syncCursor()
                fixture.secondary.awaitSyncCursorAtLeast(requiredCursor, MULTI_DEVICE_RECONNECT_TIMEOUT_MS)
                val secondaryFinal = fixture.secondary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_RECONNECT_TIMEOUT_MS,
                ) { conversation ->
                    !conversation.isPinned &&
                        !conversation.isMuted &&
                        conversation.readSeq == lastReadSeq &&
                        conversation.unreadCount == 0 &&
                        conversation.draft == MULTI_DEVICE_FINAL_DRAFT
                }
                assertEquals(primaryFinal, secondaryFinal, "重连设备应通过同一事件流追平最终 Conversation")
                fixture.peer.awaitConversation(chatId, MULTI_DEVICE_TIMEOUT_MS) { conversation ->
                    conversation.peerReadSeq == lastReadSeq
                }

                val primaryAuthority = primaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                val secondaryAuthority = secondaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                assertEquals(primaryAuthority, secondaryAuthority, "同一 uid 的权威 Conversation 不应按设备分叉")
                assertEquals(MULTI_DEVICE_FINAL_DRAFT, primaryAuthority.draft, "断线期间更新的草稿应成为最终权威值")
                assertEquals(primaryAuthority, primaryFinal, "主设备观察到的最终投影应等于服务端权威状态")
                assertEquals(secondaryAuthority, secondaryFinal, "重连设备观察到的最终投影应等于服务端权威状态")
                assertEquals(
                    primaryAuthority,
                    requireNotNull(fixture.primary.conversation(chatId)),
                    "同步完成后重新读取的主设备 cache 必须保持最终权威状态",
                )
                assertEquals(
                    secondaryAuthority,
                    requireNotNull(fixture.secondary.conversation(chatId)),
                    "同步完成后重新读取的重连设备 cache 必须保持最终权威状态",
                )

                val sessions = listOf(fixture.primary, fixture.secondary, fixture.peer)
                val histories = sessions.map { session ->
                    MessageRpcProxy(session.rpc).getHistory(chatId, 0L, Message.MAX_QUERY_PAGE_SIZE)
                }
                histories.forEach { history ->
                    assertEquals(
                        history.size,
                        history.map { message -> message.serverSeq }.distinct().size,
                        "服务端权威历史不应包含重复 serverSeq",
                    )
                }
                assertHistoryEqual(histories[0], histories[1], "同 uid 两设备的历史应一致")
                assertHistoryEqual(histories[0], histories[2], "双方看到的聊天历史应一致")
                sessions.forEachIndexed { index, session ->
                    val localHistory = session.messages(chatId)
                    assertEquals(
                        localHistory.size,
                        localHistory.map { message -> message.serverSeq }.distinct().size,
                        "客户端 $index 本地投影不应因实时投递或重放产生重复 serverSeq",
                    )
                    assertEquals(
                        localHistory.size,
                        localHistory.map { message -> message.clientMsgId }.distinct().size,
                        "客户端 $index 本地投影不应因实时投递或重放产生重复 clientMsgId",
                    )
                    assertHistoryEqual(
                        histories[index],
                        localHistory,
                        "客户端 $index 的最终本地历史必须逐条等于权威历史",
                    )
                }
                val authoritativeRevoked = histories.first().single { message -> message.serverSeq == editableSeq }
                assertTrue(authoritativeRevoked.flags and Message.FLAG_EDITED != 0)
                assertTrue(authoritativeRevoked.flags and Message.FLAG_REVOKED != 0)
                assertMessagesEqual(
                    revokedMessages + authoritativeRevoked,
                    "本地与权威撤回消息应保持同一最终表示",
                )
            }
        }

    @Test
    fun `same uid devices and peer converge after exact TeamTalk service restart`(): Unit =
        runBlocking {
            createMultiDevicePersonalChat("core04-restart").use { fixture ->
                val chatId = fixture.chat.chatId
                val sessions = listOf(fixture.primary, fixture.secondary, fixture.peer)
                val primaryConversationRpc = ConversationRpcProxy(fixture.primary.rpc)
                val secondaryConversationRpc = ConversationRpcProxy(fixture.secondary.rpc)
                val primaryMessageRpc = MessageRpcProxy(fixture.primary.rpc)
                val secondaryMessageRpc = MessageRpcProxy(fixture.secondary.rpc)

                coroutineScope {
                    val pin = async { primaryConversationRpc.setPin(chatId, true) }
                    val mute = async { secondaryConversationRpc.setMute(chatId, true) }
                    pin.await()
                    mute.await()
                }
                primaryConversationRpc.setDraft(chatId, MULTI_DEVICE_RESTART_INITIAL_DRAFT)

                val preRestartSequences = buildList {
                    repeat(2) { index ->
                        val message = Message(
                            chatId = chatId,
                            clientMsgId = UUID.randomUUID().toString(),
                            messageType = MessageType.RICH_TEXT.code,
                            timestamp = System.currentTimeMillis(),
                            senderUid = "",
                            body = buildRichTextBody("multi-device-restart-before-$index"),
                        )
                        val ack = fixture.peer.imClient.sendAndWaitAck(message)
                        assertEquals(0, ack.code, "服务重启前消息应发送成功: ${ack.reason}")
                        add(ack.serverSeq)
                    }
                }
                val partialReadSeq = preRestartSequences.first()
                val lastPreRestartSeq = preRestartSequences.last()
                sessions.forEach { session ->
                    session.awaitMessage(chatId, lastPreRestartSeq, MULTI_DEVICE_TIMEOUT_MS)
                }
                primaryMessageRpc.markRead(chatId, partialReadSeq)

                val primaryBeforeRestart = fixture.primary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation ->
                    conversation.isPinned &&
                        conversation.isMuted &&
                        conversation.draft == MULTI_DEVICE_RESTART_INITIAL_DRAFT &&
                        conversation.readSeq == partialReadSeq &&
                        conversation.unreadCount == 1
                }
                val secondaryBeforeRestart = fixture.secondary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_TIMEOUT_MS,
                ) { conversation -> conversation == primaryBeforeRestart }
                assertEquals(
                    primaryBeforeRestart,
                    secondaryBeforeRestart,
                    "服务重启前同 uid 两设备的 Conversation 必须一致",
                )
                fixture.peer.awaitConversation(chatId, MULTI_DEVICE_TIMEOUT_MS) { conversation ->
                    conversation.peerReadSeq == partialReadSeq
                }

                val authorityBeforeRestart = primaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                assertEquals(
                    authorityBeforeRestart,
                    secondaryConversationRpc.listPage(ConversationPageRequest())
                        .items.single { conversation -> conversation.chatId == chatId },
                    "服务重启前同 uid 的权威 Conversation 不能按设备分叉",
                )
                assertEquals(authorityBeforeRestart, primaryBeforeRestart)
                val historiesBeforeRestart = sessions.map { session ->
                    MessageRpcProxy(session.rpc).getHistory(
                        chatId,
                        0L,
                        Message.MAX_QUERY_PAGE_SIZE,
                    )
                }
                assertHistoryEqual(
                    historiesBeforeRestart[0],
                    historiesBeforeRestart[1],
                    "服务重启前同 uid 两设备的权威历史应一致",
                )
                assertHistoryEqual(
                    historiesBeforeRestart[0],
                    historiesBeforeRestart[2],
                    "服务重启前聊天双方的权威历史应一致",
                )

                val primaryAuthentication = fixture.primary.authenticationCount
                val secondaryAuthentication = fixture.secondary.authenticationCount
                val peerAuthentication = fixture.peer.authenticationCount
                val restartEvidence = withContext(Dispatchers.IO) {
                    RemoteTeamTalkServiceRestart().restart()
                }
                assertNotEquals(
                    restartEvidence.beforeInvocationId,
                    restartEvidence.afterInvocationId,
                    "systemd InvocationID 必须证明 TeamTalk 进程真实重启",
                )
                assertTrue(restartEvidence.beforeMainPid > 0L)
                assertTrue(restartEvidence.afterMainPid > 0L)

                val authenticationCounts = coroutineScope {
                    val primaryReconnect = async {
                        fixture.primary.awaitAuthenticationAfter(
                            primaryAuthentication,
                            MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                        )
                    }
                    val secondaryReconnect = async {
                        fixture.secondary.awaitAuthenticationAfter(
                            secondaryAuthentication,
                            MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                        )
                    }
                    val peerReconnect = async {
                        fixture.peer.awaitAuthenticationAfter(
                            peerAuthentication,
                            MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                        )
                    }
                    Triple(
                        primaryReconnect.await(),
                        secondaryReconnect.await(),
                        peerReconnect.await(),
                    )
                }
                assertEquals(primaryAuthentication + 1, authenticationCounts.first)
                assertEquals(secondaryAuthentication + 1, authenticationCounts.second)
                assertEquals(peerAuthentication + 1, authenticationCounts.third)
                assertEquals(primaryAuthentication + 1, fixture.primary.authenticationCount)
                assertEquals(secondaryAuthentication + 1, fixture.secondary.authenticationCount)
                assertEquals(peerAuthentication + 1, fixture.peer.authenticationCount)

                val primaryAuthorityAfterRestart = primaryConversationRpc
                    .listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                val secondaryAuthorityAfterRestart = secondaryConversationRpc
                    .listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                assertEquals(
                    authorityBeforeRestart,
                    primaryAuthorityAfterRestart,
                    "服务重启不能丢失 Conversation 权威状态",
                )
                assertEquals(
                    primaryAuthorityAfterRestart,
                    secondaryAuthorityAfterRestart,
                    "服务重启后同 uid 权威 Conversation 必须一致",
                )
                assertEquals(
                    primaryBeforeRestart,
                    fixture.primary.awaitConversation(
                        chatId,
                        MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                    ) { conversation -> conversation == primaryBeforeRestart },
                    "服务重启后主设备必须保留 pin、mute、draft 和 readSeq 投影",
                )
                assertEquals(
                    secondaryBeforeRestart,
                    fixture.secondary.awaitConversation(
                        chatId,
                        MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                    ) { conversation -> conversation == secondaryBeforeRestart },
                    "服务重启后第二设备必须保留 pin、mute、draft 和 readSeq 投影",
                )
                fixture.peer.awaitConversation(
                    chatId,
                    MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                ) { conversation -> conversation.peerReadSeq == partialReadSeq }
                val historiesImmediatelyAfterRestart = sessions.map { session ->
                    MessageRpcProxy(session.rpc).getHistory(
                        chatId,
                        0L,
                        Message.MAX_QUERY_PAGE_SIZE,
                    )
                }
                historiesImmediatelyAfterRestart.forEachIndexed { index, history ->
                    assertHistoryEqual(
                        historiesBeforeRestart[0],
                        history,
                        "服务重启不能丢失或重复既有历史",
                    )
                    assertHistoryEqual(
                        history,
                        sessions[index].messages(chatId),
                        "客户端 $index 服务重启后本地历史必须保持权威表示",
                    )
                }

                val postRestartMessage = Message(
                    chatId = chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = System.currentTimeMillis(),
                    senderUid = "",
                    body = buildRichTextBody("multi-device-restart-after"),
                )
                val postRestartAck = fixture.peer.imClient.sendAndWaitAck(postRestartMessage)
                assertEquals(0, postRestartAck.code, "服务重启后消息应发送成功: ${postRestartAck.reason}")
                assertEquals(
                    lastPreRestartSeq + 1L,
                    postRestartAck.serverSeq,
                    "服务重启后 serverSeq 必须连续",
                )
                sessions.forEach { session ->
                    session.awaitMessage(
                        chatId,
                        postRestartAck.serverSeq,
                        MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                    )
                }

                coroutineScope {
                    val unpin = async { primaryConversationRpc.setPin(chatId, false) }
                    val unmute = async { secondaryConversationRpc.setMute(chatId, false) }
                    val draft = async {
                        secondaryConversationRpc.setDraft(chatId, MULTI_DEVICE_RESTART_FINAL_DRAFT)
                    }
                    val read = async {
                        secondaryMessageRpc.markRead(chatId, postRestartAck.serverSeq)
                    }
                    unpin.await()
                    unmute.await()
                    draft.await()
                    read.await()
                }
                val primaryFinal = fixture.primary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                ) { conversation ->
                    !conversation.isPinned &&
                        !conversation.isMuted &&
                        conversation.draft == MULTI_DEVICE_RESTART_FINAL_DRAFT &&
                        conversation.readSeq == postRestartAck.serverSeq &&
                        conversation.unreadCount == 0
                }
                val secondaryFinal = fixture.secondary.awaitConversation(
                    chatId,
                    MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                ) { conversation -> conversation == primaryFinal }
                assertEquals(primaryFinal, secondaryFinal, "服务重启后同 uid 两设备必须收敛")
                val peerFinal = fixture.peer.awaitConversation(
                    chatId,
                    MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                ) { conversation -> conversation.peerReadSeq == postRestartAck.serverSeq }
                fixture.secondary.awaitSyncCursorAtLeast(
                    fixture.primary.syncCursor(),
                    MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS,
                )

                val primaryFinalAuthority = primaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                val secondaryFinalAuthority = secondaryConversationRpc.listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                val peerFinalAuthority = ConversationRpcProxy(fixture.peer.rpc)
                    .listPage(ConversationPageRequest())
                    .items.single { conversation -> conversation.chatId == chatId }
                assertEquals(primaryFinalAuthority, secondaryFinalAuthority)
                assertEquals(primaryFinalAuthority, primaryFinal)
                assertEquals(secondaryFinalAuthority, secondaryFinal)
                assertEquals(peerFinalAuthority, peerFinal)
                assertEquals(
                    primaryFinalAuthority,
                    requireNotNull(fixture.primary.conversation(chatId)),
                    "最终权威读取后主设备当前 Conversation cache 必须保持一致",
                )
                assertEquals(
                    secondaryFinalAuthority,
                    requireNotNull(fixture.secondary.conversation(chatId)),
                    "最终权威读取后第二设备当前 Conversation cache 必须保持一致",
                )
                assertEquals(
                    peerFinalAuthority,
                    requireNotNull(fixture.peer.conversation(chatId)),
                    "最终权威读取后对端当前 Conversation cache 必须保持完整一致",
                )

                val finalHistories = sessions.map { session ->
                    MessageRpcProxy(session.rpc).getHistory(
                        chatId,
                        0L,
                        Message.MAX_QUERY_PAGE_SIZE,
                    )
                }
                assertEquals(
                    historiesBeforeRestart[0].size + 1,
                    finalHistories[0].size,
                    "服务重启后的新消息只能增加一条权威历史",
                )
                assertHistoryEqual(finalHistories[0], finalHistories[1], "同 uid 两设备最终历史应一致")
                assertHistoryEqual(finalHistories[0], finalHistories[2], "聊天双方最终历史应一致")
                sessions.forEachIndexed { index, session ->
                    val authority = finalHistories[index]
                    assertEquals(
                        authority.size,
                        authority.map(Message::serverSeq).distinct().size,
                        "服务重启后权威历史不能重复 serverSeq",
                    )
                    val local = session.messages(chatId)
                    assertEquals(
                        local.size,
                        local.map(Message::clientMsgId).distinct().size,
                        "客户端 $index 重连重放后不能重复 clientMsgId",
                    )
                    assertHistoryEqual(
                        authority,
                        local,
                        "客户端 $index 的本地历史必须等于服务重启后的权威历史",
                    )
                }
                assertEquals(
                    primaryAuthentication + 1,
                    fixture.primary.authenticationCount,
                    "全部收敛断言完成后主设备不得发生第二次重新认证",
                )
                assertEquals(
                    secondaryAuthentication + 1,
                    fixture.secondary.authenticationCount,
                    "全部收敛断言完成后第二设备不得发生第二次重新认证",
                )
                assertEquals(
                    peerAuthentication + 1,
                    fixture.peer.authenticationCount,
                    "全部收敛断言完成后对端不得发生第二次重新认证",
                )
            }
        }

    @Test
    fun `rocks append recovers after exact process kill before projection and same identity retry`(): Unit =
        assertMessageProcessCrashBoundary(Core02ProcessCrashBoundary.ROCKS_COMMITTED_BEFORE_PROJECTION)

    @Test
    fun `postgres projection recovers after exact process kill before outbox delete and same identity retry`(): Unit =
        assertMessageProcessCrashBoundary(Core02ProcessCrashBoundary.POSTGRES_COMMITTED_BEFORE_OUTBOX_DELETE)

    @Test
    fun `outbox delete recovers after exact process kill before message ack and same identity retry`(): Unit =
        assertMessageProcessCrashBoundary(Core02ProcessCrashBoundary.OUTBOX_DELETED_BEFORE_MESSAGE_RETURN)

    private fun assertMessageProcessCrashBoundary(boundary: Core02ProcessCrashBoundary): Unit =
        runBlocking {
            val fixtureSuffix = when (boundary) {
                Core02ProcessCrashBoundary.ROCKS_COMMITTED_BEFORE_PROJECTION -> "core02-rocks"
                Core02ProcessCrashBoundary.POSTGRES_COMMITTED_BEFORE_OUTBOX_DELETE -> "core02-postgres"
                Core02ProcessCrashBoundary.OUTBOX_DELETED_BEFORE_MESSAGE_RETURN -> "core02-outbox"
            }
            val (sender, receiver, chat) = createFriendPersonalChat(fixtureSuffix)
            val crash = RemoteTeamTalkProcessCrash(boundary)
            val clientMsgId = "${boundary.clientMessagePrefix}${UUID.randomUUID()}"
            val keyword = "core02rocks${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val nextKeyword = "core02next${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val message = Message(
                chatId = chat.chatId,
                clientMsgId = clientMsgId,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                senderUid = "",
                body = buildRichTextBody(keyword),
            )
            var testFailure: Throwable? = null
            try {
                crash.arm(chat.chatId, clientMsgId)
                val senderAuthentication = sender.authenticationCount
                val receiverAuthentication = receiver.authenticationCount
                val (firstAttempt, crashEvidence) = coroutineScope {
                    val kill = async(Dispatchers.IO) {
                        crash.awaitHitKillAndRestart(clientMsgId)
                    }
                    val send = async {
                        runCatching {
                            sender.imClient.sendAndWaitAck(
                                message,
                                timeoutMs = CORE02_PROCESS_CRASH_TIMEOUT_MS,
                            )
                        }
                    }
                    send.await() to kill.await()
                }
                assertTrue(
                    firstAttempt.exceptionOrNull() is TransportUnavailableException,
                    "进程在 ACK 前死亡时首发必须明确失去 transport，不能返回成功或伪造业务失败: " +
                        firstAttempt.exceptionOrNull(),
                )
                assertEquals(chat.chatId, crashEvidence.chatId)
                assertEquals(clientMsgId, crashEvidence.clientMsgId)
                assertEquals(boundary.stage.name, crashEvidence.stage)
                assertNotEquals(crashEvidence.beforeInvocationId, crashEvidence.afterInvocationId)
                assertNotEquals(crashEvidence.beforeMainPid, crashEvidence.afterMainPid)

                coroutineScope {
                    val senderReconnect = async {
                        sender.awaitAuthenticationAfter(
                            senderAuthentication,
                            CORE02_PROCESS_CRASH_TIMEOUT_MS,
                        )
                    }
                    val receiverReconnect = async {
                        receiver.awaitAuthenticationAfter(
                            receiverAuthentication,
                            CORE02_PROCESS_CRASH_TIMEOUT_MS,
                        )
                    }
                    assertEquals(senderAuthentication + 1, senderReconnect.await())
                    assertEquals(receiverAuthentication + 1, receiverReconnect.await())
                }

                val senderMessages = MessageRpcProxy(sender.rpc).getHistory(
                    chat.chatId,
                    0L,
                    Message.MAX_QUERY_PAGE_SIZE,
                )
                val committed = senderMessages.single { candidate ->
                    candidate.clientMsgId == clientMsgId
                }
                assertEquals(1L, committed.serverSeq, "新会话的首条 durable Rocks 消息必须使用 seq=1")
                assertEquals(
                    1,
                    senderMessages.count { candidate -> candidate.clientMsgId == clientMsgId },
                    "客户端重连或 startup outbox 恢复不能复制权威消息",
                )
                val receiverMessages = MessageRpcProxy(receiver.rpc).getHistory(
                    chat.chatId,
                    0L,
                    Message.MAX_QUERY_PAGE_SIZE,
                )
                assertHistoryEqual(senderMessages, receiverMessages, "重试前双方权威历史必须已经恢复")
                sender.awaitMessage(chat.chatId, committed.serverSeq, CORE02_PROCESS_CRASH_TIMEOUT_MS)
                receiver.awaitMessage(chat.chatId, committed.serverSeq, CORE02_PROCESS_CRASH_TIMEOUT_MS)
                assertHistoryEqual(senderMessages, sender.messages(chat.chatId), "重试前发送端本地历史必须收敛")
                assertHistoryEqual(receiverMessages, receiver.messages(chat.chatId), "重试前接收端本地历史必须收敛")

                val receiverBeforeRetry = receiver.awaitConversation(
                    chat.chatId,
                    CORE02_PROCESS_CRASH_TIMEOUT_MS,
                ) { conversation ->
                    conversation.lastSeq == committed.serverSeq && conversation.unreadCount == 1
                }
                assertEquals(0L, receiverBeforeRetry.readSeq)
                listOf(sender, receiver).forEachIndexed { index, session ->
                    val searchHits = MessageRpcProxy(session.rpc).search(chat.chatId, keyword, 10)
                    assertEquals(
                        listOf(clientMsgId),
                        searchHits.map(Message::clientMsgId),
                        "客户端 $index 在重试前必须看到唯一 Lucene 投影",
                    )
                    assertEquals(
                        1,
                        session.observedMessageEvents().count { (_, observed) ->
                            observed.chatId == chat.chatId && observed.clientMsgId == clientMsgId
                        },
                        "客户端 $index 在重试前必须只投影一次 durable MESSAGE_RECV",
                    )
                }

                repeat(2) { retryIndex ->
                    val retryAck = sender.imClient.sendAndWaitAck(message)
                    assertEquals(0, retryAck.code, "同 identity 第 ${retryIndex + 1} 次重试应返回原结果")
                    assertEquals(
                        committed.serverSeq,
                        retryAck.serverSeq,
                        "同 chatId + clientMsgId 重试不能分配新 seq",
                    )
                }

                val nextMessage = Message(
                    chatId = chat.chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = System.currentTimeMillis(),
                    senderUid = "",
                    body = buildRichTextBody(nextKeyword),
                )
                val nextAck = sender.imClient.sendAndWaitAck(nextMessage)
                assertEquals(0, nextAck.code, "进程恢复后新消息应成功")
                assertEquals(
                    committed.serverSeq + 1L,
                    nextAck.serverSeq,
                    "进程死亡与同 identity 重试不能制造 seq 空洞",
                )
                sender.awaitMessage(chat.chatId, nextAck.serverSeq, CORE02_PROCESS_CRASH_TIMEOUT_MS)
                receiver.awaitMessage(chat.chatId, nextAck.serverSeq, CORE02_PROCESS_CRASH_TIMEOUT_MS)

                val finalAuthority = MessageRpcProxy(sender.rpc).getHistory(
                    chat.chatId,
                    0L,
                    Message.MAX_QUERY_PAGE_SIZE,
                )
                assertEquals(2, finalAuthority.size, "最终权威历史只能包含原消息和一条新消息")
                assertEquals(
                    setOf(clientMsgId, nextMessage.clientMsgId),
                    finalAuthority.mapTo(linkedSetOf(), Message::clientMsgId),
                )
                assertEquals(2, finalAuthority.map(Message::serverSeq).distinct().size)
                listOf(sender, receiver).forEachIndexed { index, session ->
                    val authority = MessageRpcProxy(session.rpc).getHistory(
                        chat.chatId,
                        0L,
                        Message.MAX_QUERY_PAGE_SIZE,
                    )
                    assertHistoryEqual(finalAuthority, authority, "客户端 $index 最终权威历史必须一致")
                    assertHistoryEqual(authority, session.messages(chat.chatId), "客户端 $index 本地历史必须一致")
                    assertEquals(
                        1,
                        session.observedMessageEvents().count { (_, observed) ->
                            observed.chatId == chat.chatId && observed.clientMsgId == clientMsgId
                        },
                        "同 identity ACK 重试不能产生第二个 durable MESSAGE_RECV",
                    )
                }
                assertEquals(
                    listOf(clientMsgId),
                    MessageRpcProxy(receiver.rpc).search(chat.chatId, keyword, 10)
                        .map(Message::clientMsgId),
                    "同 identity 重试不能复制搜索文档",
                )

                val receiverBeforeRead = receiver.awaitConversation(
                    chat.chatId,
                    CORE02_PROCESS_CRASH_TIMEOUT_MS,
                ) { conversation ->
                    conversation.lastSeq == nextAck.serverSeq && conversation.unreadCount == 2
                }
                assertEquals(0L, receiverBeforeRead.readSeq)
                MessageRpcProxy(receiver.rpc).markRead(chat.chatId, nextAck.serverSeq)
                receiver.awaitConversation(chat.chatId, CORE02_PROCESS_CRASH_TIMEOUT_MS) { conversation ->
                    conversation.readSeq == nextAck.serverSeq && conversation.unreadCount == 0
                }
                sender.awaitConversation(chat.chatId, CORE02_PROCESS_CRASH_TIMEOUT_MS) { conversation ->
                    conversation.peerReadSeq == nextAck.serverSeq
                }
                assertEquals(senderAuthentication + 1, sender.authenticationCount)
                assertEquals(receiverAuthentication + 1, receiver.authenticationCount)
            } catch (failure: Throwable) {
                testFailure = failure
                throw failure
            } finally {
                var releaseFailure: Throwable? = null
                fun release(action: () -> Unit) {
                    try {
                        action()
                    } catch (closeFailure: Throwable) {
                        val current = releaseFailure
                        if (current == null) {
                            releaseFailure = closeFailure
                        } else {
                            current.addSuppressed(closeFailure)
                        }
                    }
                }
                release { crash.cleanup(clientMsgId) }
                release(receiver::close)
                release(sender::close)
                val primary = testFailure
                if (primary == null) {
                    releaseFailure?.let { throw it }
                } else {
                    releaseFailure?.let(primary::addSuppressed)
                }
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
            assertEquals(user.revision + 1L, user2.revision, "资料变化必须推进 User revision")
        } finally {
            session.close()
        }
    }

    @Test
    fun `friend observes authenticated avatar lifecycle through product SDK`() = runBlocking {
        val (owner, friend) = createFriendPersonalChat("profile-avatar").let { (first, second) -> first to second }
        try {
            val firstBytes = pngPixel(0xFF3366CC.toInt())
            val first = upload(owner, firstBytes, "avatar-first.png", "image/png")
            val firstPatch = ProfilePatch(avatar = ProfilePatchValue.Set(first))
            val firstResponse = owner.invoke(
                "user",
                UserRpcContract.M_UPDATE_PROFILE,
                ProtoCodec.encode(firstPatch),
            )
            assertEquals(0, firstResponse.status, "首次头像更新应成功")

            val ownerFirstEvent = ProtoCodec.decode(
                User,
                requireNotNull(owner.awaitNotify(NotifyType.USER_UPDATED.code, 10_000).payload),
            )
            val friendFirstEvent = ProtoCodec.decode(
                User,
                requireNotNull(friend.awaitNotify(NotifyType.USER_UPDATED.code, 10_000).payload),
            )
            assertEquals(first, ownerFirstEvent.avatar, "本人设备必须收到完整头像描述符")
            assertEquals(ownerFirstEvent, friendFirstEvent, "好友必须收到同一完整 User 事实")
            assertEquals(first, getRemoteProfile(friend, owner.uid).avatar)
            assertArrayEquals(sha256(firstBytes), sha256(download(friend, first)))

            val secondBytes = pngPixel(0xFFCC6633.toInt())
            val second = upload(owner, secondBytes, "avatar-second.png", "image/png")
            val replaceResponse = owner.invoke(
                "user",
                UserRpcContract.M_UPDATE_PROFILE,
                ProtoCodec.encode(ProfilePatch(avatar = ProfilePatchValue.Set(second))),
            )
            assertEquals(0, replaceResponse.status, "替换头像应成功")
            ProtoCodec.decode(User, requireNotNull(owner.awaitNotify(NotifyType.USER_UPDATED.code, 10_000).payload))
            val replaced = ProtoCodec.decode(
                User,
                requireNotNull(friend.awaitNotify(NotifyType.USER_UPDATED.code, 10_000).payload),
            )
            assertEquals(second, replaced.avatar)
            assertEquals(friendFirstEvent.revision + 1L, replaced.revision)
            assertEquals(second, getRemoteProfile(friend, owner.uid).avatar)
            assertArrayEquals(sha256(secondBytes), sha256(download(friend, second)))
            assertBusinessFailure(
                downloadOutcome(friend, first),
                expectedCode = 403,
                message = "替换后旧头像必须失去认证用户读取权",
            )

            val clearResponse = owner.invoke(
                "user",
                UserRpcContract.M_UPDATE_PROFILE,
                ProtoCodec.encode(ProfilePatch(avatar = ProfilePatchValue.Set(null))),
            )
            assertEquals(0, clearResponse.status, "清除头像应成功")
            ProtoCodec.decode(User, requireNotNull(owner.awaitNotify(NotifyType.USER_UPDATED.code, 10_000).payload))
            val cleared = ProtoCodec.decode(
                User,
                requireNotNull(friend.awaitNotify(NotifyType.USER_UPDATED.code, 10_000).payload),
            )
            assertNull(cleared.avatar)
            assertEquals(replaced.revision + 1L, cleared.revision)
            assertNull(getRemoteProfile(friend, owner.uid).avatar)
            assertBusinessFailure(
                downloadOutcome(friend, second),
                expectedCode = 403,
                message = "清除后新头像必须失去认证用户读取权",
            )
        } finally {
            owner.close()
            friend.close()
        }
    }

    // ── 群消息广播 ──

    @Tag("preview-smoke")
    @Test
    fun `group message broadcasts to all members`() = runBlocking {
        val user1 = RemoteAcceptanceSupport.registerUser("grpbc-1")
        val user2 = RemoteAcceptanceSupport.registerUser("grpbc-2")
        val user3 = RemoteAcceptanceSupport.registerUser("grpbc-3")
        try {
            // 建 3 人群（user1 建群，加 user2 + user3）
            val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_GROUP,
                ProtoCodec.encodePayload {
                    writeString(UUID.randomUUID().toString())
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
            val sent = (0..1).map { i ->
                val message = Message(
                    chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                    messageType = MessageType.RICH_TEXT.code, timestamp = System.currentTimeMillis(),
                    senderUid = "", body = buildRichTextBody("history-$i"),
                )
                val ack = user1.imClient.sendAndWaitAck(message)
                assertEquals(0, ack.code, "历史基线消息应发送成功: ${ack.reason}")
                message.copy(serverSeq = ack.serverSeq)
            }

            val messageRpc = MessageRpcProxy(user1.rpc)
            val messages = messageRpc.getHistory(chat.chatId, 0L, 10)
            assertEquals(sent.reversed().map { it.clientMsgId }, messages.map { it.clientMsgId })
            assertEquals(sent.reversed().map { it.serverSeq }, messages.map { it.serverSeq })
            assertEquals(
                listOf(sent.last().serverSeq),
                messageRpc.getHistory(chat.chatId, 0L, 1).map { it.serverSeq },
                "最新历史必须遵守分页上限",
            )
            assertEquals(
                listOf(sent.first().serverSeq),
                messageRpc.getHistory(chat.chatId, sent.first().serverSeq, 10).map { it.serverSeq },
                "历史起点包含在倒序结果中，不能混入较新的消息",
            )
        } finally {
            user1.close(); user2.close()
        }
    }

    // ── helper：建立好友关系 + 创建私聊（消息类测试前置） ──

    private suspend fun createFriendPersonalChat(tag: String): Triple<RemoteAcceptanceSupport.Session, RemoteAcceptanceSupport.Session, Chat> {
        val user1 = RemoteAcceptanceSupport.registerUser("$tag-1")
        val user2 = RemoteAcceptanceSupport.registerUser("$tag-2")

        val chat = establishFriendPersonalChat(user1, user2)
        return Triple(user1, user2, chat)
    }

    private suspend fun getRemoteProfile(
        session: RemoteAcceptanceSupport.Session,
        targetUid: String,
    ): User {
        val response = session.invoke(
            "user",
            UserRpcContract.M_GET_PROFILE,
            ProtoCodec.encodePayload { writeString(targetUid) },
        )
        assertEquals(0, response.status, "读取远端用户资料应成功")
        return ProtoCodec.decode(User, requireNotNull(response.payload))
    }

    private fun pngPixel(argb: Int): ByteArray {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, argb) }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG writer is unavailable" }
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private suspend fun createMultiDevicePersonalChat(tag: String): MultiDeviceChatFixture {
        val password = "password123"
        var primary: RemoteAcceptanceSupport.Session? = null
        var secondary: RemoteAcceptanceSupport.Session? = null
        var peer: RemoteAcceptanceSupport.Session? = null
        try {
            primary = RemoteAcceptanceSupport.registerUser(
                suffix = "$tag-owner",
                password = password,
                displayName = "CORE-04 owner",
                deviceId = "e2e-$tag-primary",
                deviceName = "CORE-04 primary",
            )
            val username = requireNotNull(primary.registeredUsername)
            secondary = RemoteAcceptanceSupport.loginUser(
                username = username,
                password = password,
                deviceId = "e2e-$tag-secondary",
                deviceName = "CORE-04 secondary",
            )
            peer = RemoteAcceptanceSupport.registerUser(
                suffix = "$tag-peer",
                deviceId = "e2e-$tag-peer",
                deviceName = "CORE-04 peer",
            )
            assertEquals(primary.uid, secondary.uid, "两个设备必须属于同一 uid")
            assertNotEquals(primary.uid, peer.uid, "对端必须是独立 uid")
            val chat = establishFriendPersonalChat(primary, peer)
            return MultiDeviceChatFixture(primary, secondary, peer, chat)
        } catch (failure: Throwable) {
            closeSessionsPreservingFailure(failure, peer, secondary, primary)
            throw failure
        }
    }

    private suspend fun establishFriendPersonalChat(
        user1: RemoteAcceptanceSupport.Session,
        user2: RemoteAcceptanceSupport.Session,
    ): Chat {

        // 申请 + 接受好友
        user1.invoke("contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val pendingToken = user2.pendingApplyToken(user1.uid)
        user2.invoke("contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            })

        // 创建私聊
        val chatResp = user1.invoke("chat", ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(user2.uid) })
        assertEquals(0, chatResp.status, "创建私聊应成功")
        return ProtoCodec.decode(Chat, chatResp.payload!!)
    }

    private fun assertMessagesEqual(messages: List<Message>, reason: String) {
        val expected = ProtoCodec.encode(messages.first())
        messages.drop(1).forEach { message -> assertArrayEquals(expected, ProtoCodec.encode(message), reason) }
    }

    private fun assertHistoryEqual(expected: List<Message>, actual: List<Message>, reason: String) {
        assertEquals(
            expected.map { message -> message.serverSeq },
            actual.map { message -> message.serverSeq },
            reason,
        )
        expected.zip(actual).forEach { (expectedMessage, actualMessage) ->
            assertArrayEquals(ProtoCodec.encode(expectedMessage), ProtoCodec.encode(actualMessage), reason)
        }
    }

    private fun closeSessionsPreservingFailure(
        failure: Throwable,
        vararg sessions: RemoteAcceptanceSupport.Session?,
    ) {
        sessions.filterNotNull().forEach { session ->
            runCatching(session::close).exceptionOrNull()?.let(failure::addSuppressed)
        }
    }

    private data class MultiDeviceChatFixture(
        val primary: RemoteAcceptanceSupport.Session,
        val secondary: RemoteAcceptanceSupport.Session,
        val peer: RemoteAcceptanceSupport.Session,
        val chat: Chat,
    ) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            listOf(peer, secondary, primary).forEach { session ->
                try {
                    session.close()
                } catch (closeFailure: Throwable) {
                    val current = failure
                    if (current == null) failure = closeFailure else current.addSuppressed(closeFailure)
                }
            }
            failure?.let { throw it }
        }
    }

    private suspend fun bestEffortArchiveSpace(
        spaceId: String,
        vararg repositories: DocumentRepository,
    ) {
        repositories.forEach { repository ->
            val result = runCatching {
                repository.archiveSpace(spaceId, UUID.randomUUID().toString())
            }.getOrNull()
            if (result is Outcome.Success) return
        }
    }

    private suspend fun awaitOrganizationChangedAfter(
        session: RemoteAcceptanceSupport.Session,
        revisionExclusive: Long,
    ): Long = withTimeout(10_000) {
        while (true) {
            val notify = session.awaitNotify(NotifyType.ORGANIZATION_CHANGED.code, timeoutMs = 10_000)
            assertEquals(0L, notify.eventId, "组织变更是瞬时失效通知，eventId 必须为 0")
            val changed = ProtoCodec.decode(
                OrganizationChangedPayload,
                requireNotNull(notify.payload) { "组织变更通知必须携带 revision payload" },
            )
            if (changed.revision > revisionExclusive) return@withTimeout changed.revision
        }
        error("unreachable")
    }

    private fun remoteAdminCredentials(): RemoteAdminCredentials {
        val environmentUser = System.getenv("TK_E2E_ADMIN_USER")
        val environmentPassword = System.getenv("TK_E2E_ADMIN_PASSWORD")
        if (environmentUser != null || environmentPassword != null) {
            require(!environmentUser.isNullOrBlank() && !environmentPassword.isNullOrBlank()) {
                "TK_E2E_ADMIN_USER and TK_E2E_ADMIN_PASSWORD must be provided together"
            }
            return RemoteAdminCredentials(environmentUser, environmentPassword)
        }

        val secretsFile = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
            it.parentFile
        }.map { directory ->
            File(directory, "gradle/deployment.secrets")
        }.firstOrNull { candidate ->
            Files.exists(candidate.toPath(), NOFOLLOW_LINKS)
        } ?: throw AssertionError(
            "Remote organization ACL/custody acceptance requires admin credentials. " +
                "Create owner-only gradle/deployment.secrets with ADMIN_USER and ADMIN_PASSWORD, " +
                "or set TK_E2E_ADMIN_USER and TK_E2E_ADMIN_PASSWORD together.",
        )
        require(!Files.isSymbolicLink(secretsFile.toPath()) &&
            Files.isRegularFile(secretsFile.toPath(), NOFOLLOW_LINKS)
        ) { "Remote acceptance deployment secrets must be a regular non-symbolic-link file" }

        val properties = Properties()
        Files.newBufferedReader(secretsFile.toPath()).use(properties::load)
        val username = properties.getProperty("ADMIN_USER")
        val password = properties.getProperty("ADMIN_PASSWORD")
        require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
            "ADMIN_USER and ADMIN_PASSWORD are required in gradle/deployment.secrets for " +
                "remote organization ACL/custody acceptance"
        }
        return RemoteAdminCredentials(username, password)
    }

    private suspend fun adminLogin(credentials: RemoteAdminCredentials): String {
        val response = adminHttpRequest(
            method = "POST",
            path = "/api/admin/login",
            body = adminJson.encodeToString(AdminLoginRequest(credentials.username, credentials.password)),
        )
        assertEquals(200, response.status, "远程管理 fixture 登录失败")
        return adminJson.decodeFromString<AdminTokenResponse>(response.body).token
    }

    private suspend fun adminListUnits(token: String): List<OrganizationUnit> {
        val response = adminHttpRequest(
            method = "GET",
            path = "/api/admin/organization/units",
            bearerToken = token,
        )
        assertEquals(200, response.status, "远程管理 fixture 读取组织节点失败")
        return adminJson.decodeFromString(response.body)
    }

    private suspend fun adminCreateUnit(
        token: String,
        request: OrganizationUnitRequest,
    ): OrganizationUnit {
        val response = adminHttpRequest(
            method = "POST",
            path = "/api/admin/organization/units",
            bearerToken = token,
            body = adminJson.encodeToString(request),
        )
        assertEquals(200, response.status, "远程管理 fixture 创建组织节点失败")
        return adminJson.decodeFromString(response.body)
    }

    private suspend fun adminAssignMember(
        token: String,
        unitId: String,
        request: OrganizationMemberRequest,
    ): OrganizationMember {
        val response = adminHttpRequest(
            method = "POST",
            path = "/api/admin/organization/units/$unitId/members",
            bearerToken = token,
            body = adminJson.encodeToString(request),
        )
        assertEquals(200, response.status, "远程管理 fixture 分配组织成员失败")
        return adminJson.decodeFromString(response.body)
    }

    private suspend fun adminRemoveMember(token: String, unitId: String, uid: String): Int = adminHttpRequest(
        method = "DELETE",
        path = "/api/admin/organization/units/$unitId/members/$uid",
        bearerToken = token,
    ).status

    private suspend fun adminArchiveUnit(token: String, unitId: String): Int = adminHttpRequest(
        method = "DELETE",
        path = "/api/admin/organization/units/$unitId",
        bearerToken = token,
    ).status

    private suspend fun adminHttpRequest(
        method: String,
        path: String,
        bearerToken: String? = null,
        body: String? = null,
    ): AdminHttpResponse = withContext(Dispatchers.IO) {
        val requestUrl = URL("${baseUrl().trimEnd('/')}$path")
        require(requestUrl.protocol == "https") { "Remote admin fixture requires HTTPS" }
        val connection = requestUrl.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val requestBytes = body?.encodeToByteArray()
            if (requestBytes != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.outputStream.use { it.write(requestBytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use { it.readNBytes(MAX_ADMIN_RESPONSE_BYTES + 1) } ?: byteArrayOf()
            require(responseBytes.size <= MAX_ADMIN_RESPONSE_BYTES) {
                "Remote admin fixture response exceeded its bounded size"
            }
            AdminHttpResponse(status, responseBytes.decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    private fun baseUrl(): String =
        System.getProperty("tk.e2e.server") ?: "https://${RemoteAcceptanceSupport.host}"

    private suspend fun downloadHttpRange(
        attachment: Attachment,
        accessToken: String?,
        range: String?,
    ): RemoteFileResponse = withContext(Dispatchers.IO) {
        val connection = URL(FileOps.resolveUrl(baseUrl(), attachment)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            range?.let { connection.setRequestProperty("Range", it) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readNBytes(MAX_FILE_HTTP_RESPONSE_BYTES + 1) } ?: byteArrayOf()
            require(body.size <= MAX_FILE_HTTP_RESPONSE_BYTES) {
                "Remote file fixture response exceeded its bounded size"
            }
            RemoteFileResponse(
                status = status,
                acceptRanges = connection.getHeaderField("Accept-Ranges"),
                contentRange = connection.getHeaderField("Content-Range"),
                contentLength = connection.getHeaderFieldLong("Content-Length", -1L),
                body = body,
            )
        } finally {
            connection.disconnect()
        }
    }

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

    private fun assertBusinessFailure(
        outcome: Outcome<*>,
        expectedCode: Int,
        message: String,
    ) {
        assertTrue(outcome is Outcome.Failure, "$message，实际结果: $outcome")
        val error = (outcome as Outcome.Failure).error
        assertTrue(error is AppError.Business, "$message，实际错误: $error")
        assertEquals(expectedCode, (error as AppError.Business).code, message)
    }

    private data class RemoteAdminCredentials(val username: String, val password: String)

    private data class AdminHttpResponse(val status: Int, val body: String)

    private data class RemoteFileResponse(
        val status: Int,
        val acceptRanges: String?,
        val contentRange: String?,
        val contentLength: Long,
        val body: ByteArray,
    )

    private companion object {
        const val MAX_ADMIN_RESPONSE_BYTES = 64 * 1024
        const val MAX_FILE_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MULTI_DEVICE_TIMEOUT_MS = 20_000L
        const val MULTI_DEVICE_RECONNECT_TIMEOUT_MS = 30_000L
        const val MULTI_DEVICE_SERVICE_RESTART_TIMEOUT_MS = 60_000L
        const val CORE02_PROCESS_CRASH_TIMEOUT_MS = 90_000L
        const val MULTI_DEVICE_INITIAL_DRAFT = "multi-device-initial-draft"
        const val MULTI_DEVICE_FINAL_DRAFT = "multi-device-final-draft-after-reconnect"
        const val MULTI_DEVICE_RESTART_INITIAL_DRAFT = "multi-device-restart-initial-draft"
        const val MULTI_DEVICE_RESTART_FINAL_DRAFT = "multi-device-restart-final-draft"
        val MULTI_DEVICE_EDIT_CANDIDATES = setOf(
            "multi-device-edit-primary",
            "multi-device-edit-secondary",
        )
        val adminJson = Json { ignoreUnknownKeys = true }
    }

    // ── 多模块交互扩展：文档协同 / 群文件断网恢复 / OFFICE_REF 降级链路 ──

    @Tag("preview-smoke")
    @Test
    fun `document collaborators recover from save conflicts and grant revocation`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("doc-collab-owner")
        val member = RemoteAcceptanceSupport.registerUser("doc-collab-member")
        try {
            val ownerDocs = DocumentRepository(owner.rpc, FakeLocalCache())
            val memberDocs = DocumentRepository(member.rpc, FakeLocalCache())

            val space = requireNotNull(ownerDocs.createSpace(
                UUID.randomUUID().toString(), "协同冲突空间", "多人编辑与撤权清理",
            ).getOrThrow().space)
            val grant = ownerDocs.upsertGrant(
                space.spaceId, DocumentSpaceGrant.PRINCIPAL_USER, member.uid,
                DocumentSpace.ROLE_EDITOR, false,
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()

            val created = requireNotNull(ownerDocs.createDocument(
                UUID.randomUUID().toString(), space.spaceId, null, "协同文档", "# 第一版\n由群主创建。",
            ).getOrThrow().projection)
            assertEquals(1L, created.revision)

            // 成员基于第一版编辑成功（rev2）
            val memberEdit = requireNotNull(memberDocs.updateDocument(
                space.spaceId, created.documentId, "# 第二版\n成员改写。", created.revision,
            ).getOrThrow().projection)
            assertEquals(2L, memberEdit.revision)

            // 群主持过期 revision 并行提交 → 409 冲突，服务端正文不被旧内容覆盖
            val conflict = ownerDocs.updateDocument(
                space.spaceId, created.documentId, "# 过期的并行编辑", created.revision,
            )
            assertTrue(conflict is Outcome.Failure, "过期 revision 提交必须失败: $conflict")
            val conflictError = (conflict as Outcome.Failure).error
            assertTrue(conflictError is AppError.Business, "冲突必须是业务错误: $conflictError")
            assertEquals(409, (conflictError as AppError.Business).code)
            assertEquals(
                "# 第二版\n成员改写。",
                ownerDocs.getDocument(space.spaceId, created.documentId).getOrThrow().markdown,
                "冲突后服务端正文必须仍是成员的第二版",
            )

            // 双选恢复的"以服务端为准"分支：群主基于权威 rev2 重新编辑
            val ownerEdit = requireNotNull(ownerDocs.updateDocument(
                space.spaceId, created.documentId, "# 第三版\n群主基于成员版本续写。", 2L,
            ).getOrThrow().projection)
            assertEquals(3L, ownerEdit.revision)

            // 撤权：成员的编辑与读取立即失败，空间投影清空
            ownerDocs.removeGrant(
                space.spaceId, DocumentSpaceGrant.PRINCIPAL_USER, member.uid,
                expectedPolicyRevision = grant.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            val memberEditAfterRevoke = memberDocs.updateDocument(
                space.spaceId, created.documentId, "# 撤权后的编辑", 3L,
            )
            assertTrue(memberEditAfterRevoke is Outcome.Failure, "撤权后编辑必须失败: $memberEditAfterRevoke")
            val revokeError = (memberEditAfterRevoke as Outcome.Failure).error
            assertTrue(revokeError is AppError.Business, "撤权失败必须是业务错误: $revokeError")
            assertEquals(403, (revokeError as AppError.Business).code)
            assertTrue(memberDocs.getDocument(space.spaceId, created.documentId) is Outcome.Failure)
            assertTrue(
                memberDocs.refreshAllDocumentSpacesForAcceptance().isEmpty(),
                "撤权后成员空间投影必须清空",
            )
        } finally {
            owner.close()
            member.close()
        }
    }

    @Test
    fun `group file commands replay idempotently after disconnect`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("gf-recovery-owner")
        val member = RemoteAcceptanceSupport.registerUser("gf-recovery-member")
        try {
            val chat = ChatRpcProxy(owner.rpc)
                .createGroup(UUID.randomUUID().toString(), "群文件重连重放验收", null, listOf(member.uid))
            val cache = FakeLocalCache()
            val entryId = UUID.randomUUID().toString()
            val commandId = UUID.randomUUID().toString()

            val session1Files = GroupFileRepository(member.rpc, cache)
            val created = session1Files
                .createFolder(entryId, commandId, chat.chatId, null, "断网恢复资料")
                .getOrThrow()
            assertEquals(entryId, created.entryId)

            // 已确认成功后主动断开；这里验证跨连接重复提交，不模拟响应丢失。
            member.close()

            // 同账号重新登录并手动重放同一 commandId。内存缓存沿用，因此本场景不证明
            // SQLite outbox 的进程重启或自动补发；那些行为需独立的持久化/客户端验收。
            val reconnected = RemoteAcceptanceSupport.loginUser(
                requireNotNull(member.registeredUsername), "password123",
            )
            try {
                val reconnectedFiles = GroupFileRepository(reconnected.rpc, cache)
                val replay = reconnectedFiles
                    .createFolder(entryId, commandId, chat.chatId, null, "断网恢复资料")
                    .getOrThrow()
                assertEquals(entryId, replay.entryId, "同 commandId 重放必须命中收据返回原条目")

                val entries = reconnectedFiles.list(chat.chatId, null).getOrThrow()
                assertEquals(1, entries.size, "重放不得产生重复条目")
                assertEquals("断网恢复资料", entries.single().name)

                // 同 entryId 换新 commandId 必须按身份冲突拒绝，目录保持唯一
                assertTrue(
                    reconnectedFiles.createFolder(
                        entryId, UUID.randomUUID().toString(), chat.chatId, null, "断网恢复资料",
                    ) is Outcome.Failure,
                    "同 entryId 不同 commandId 必须冲突拒绝",
                )
                assertEquals(1, reconnectedFiles.list(chat.chatId, null).getOrThrow().size)

                // 恢复后的可用性：在恢复的目录内继续创建文件
                val bytes = "恢复后继续上传".encodeToByteArray()
                val attachment = upload(reconnected, bytes, "after-recovery.md")
                reconnectedFiles.createFile(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    chat.chatId, entryId, "after-recovery.md", attachment,
                ).getOrThrow()
                assertEquals(1, reconnectedFiles.list(chat.chatId, entryId).getOrThrow().size)
            } finally {
                reconnected.close()
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun `office ref message freezes authoritative snapshot and degrades after document deletion`() = runBlocking {
        val owner = RemoteAcceptanceSupport.registerUser("office-ref-owner")
        val member = RemoteAcceptanceSupport.registerUser("office-ref-member")
        try {
            val ownerDocs = DocumentRepository(owner.rpc, FakeLocalCache())
            val memberDocs = DocumentRepository(member.rpc, FakeLocalCache())
            val space = requireNotNull(ownerDocs.createSpace(
                UUID.randomUUID().toString(), "引用验收空间", "文档引用与降级链路",
            ).getOrThrow().space)
            ownerDocs.upsertGrant(
                space.spaceId, DocumentSpaceGrant.PRINCIPAL_USER, member.uid,
                DocumentSpace.ROLE_VIEWER, false,
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ).getOrThrow()
            val doc = requireNotNull(ownerDocs.createDocument(
                UUID.randomUUID().toString(), space.spaceId, null,
                "被引用的产品文档", "# 被引用的产品文档\n正文摘要。",
            ).getOrThrow().projection)
            val chat = ChatRpcProxy(owner.rpc)
                .createGroup(UUID.randomUUID().toString(), "引用验收群", null, listOf(member.uid))

            // 客户端声明的标题只是占位：服务端在发送时以权威快照覆盖
            val declared = OfficeRefBody(
                refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                spaceId = space.spaceId,
                targetId = doc.documentId,
                title = "客户端随便声明的标题",
            )
            val ack = owner.imClient.sendAndWaitAck(Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.OFFICE_REF.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = declared,
            ))
            assertEquals(0, ack.code, "引用有效文档的发送应成功")

            // 成员读取历史：冻结快照携带服务端权威标题（文档名），而非客户端声明
            suspend fun memberHistory(): List<Message> {
                val resp = member.invoke(
                    "message", MessageRpcContract.M_GET_HISTORY,
                    ProtoCodec.encodePayload { writeString(chat.chatId); writeVarLong(0); writeVarInt(10) },
                )
                assertEquals(0, resp.status)
                return ProtoCodec.decodeList(Message, resp.payload!!)
            }
            val frozen = memberHistory().single { it.serverSeq == ack.serverSeq }.body as OfficeRefBody
            assertEquals("被引用的产品文档", frozen.title, "标题必须是服务端权威快照")
            assertTrue(frozen.subtitle.isNotBlank(), "权威摘要必须随快照下发")

            // 引用不存在的文档：ACK 前拒绝
            val danglingAck = owner.imClient.sendAndWaitAck(Message(
                chatId = chat.chatId, clientMsgId = UUID.randomUUID().toString(),
                messageType = MessageType.OFFICE_REF.code, timestamp = System.currentTimeMillis(),
                senderUid = "", body = declared.copy(targetId = UUID.randomUUID().toString()),
            ))
            assertTrue(danglingAck.code != 0, "引用不存在文档必须在 ACK 前被拒绝")

            // 删除被引用文档：成员读取降级，但已下发快照仍是历史事实
            ownerDocs.deleteNode(space.spaceId, doc.documentId, doc.revision, UUID.randomUUID().toString())
                .getOrThrow()
            assertTrue(
                memberDocs.getDocument(space.spaceId, doc.documentId) is Outcome.Failure,
                "删除后成员读取必须降级",
            )
            val stillFrozen = memberHistory().single { it.serverSeq == ack.serverSeq }.body as OfficeRefBody
            assertEquals("被引用的产品文档", stillFrozen.title, "冻结快照不随正文删除变化")
        } finally {
            owner.close()
            member.close()
        }
    }
}
