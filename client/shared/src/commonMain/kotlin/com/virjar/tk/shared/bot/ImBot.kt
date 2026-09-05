package com.virjar.tk.shared.bot

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.EventProcessor
import com.virjar.tk.shared.client.FriendPresence
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.UserSession
import com.virjar.tk.shared.client.createSession
import com.virjar.tk.shared.client.releaseAllSessionResources
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.body.VideoBody
import com.virjar.tk.protocol.body.VoiceBody
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.repository.FileRepository
import com.virjar.tk.shared.repository.UploadSource
import com.virjar.tk.shared.repository.asSmallUploadSource
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * 无头 IM 客户端（AI bot / CLI / 服务器端集成入口）。
 *
 * 复用 SDK 完整闭环（ImClient 连接 + ClientSession 组装 + EventProcessor 事件同步），
 * LocalCache 与可靠 inbox 均由调用方明确提供 owner，无任何 UI 依赖。
 *
 * 注册口令必须由生产调用方使用安全随机源生成，并随机器人身份持久保存到自己的秘密存储中；
 * SDK 不提供默认口令，也不应在源码或配置模板中硬编码固定值。
 *
 * 典型用法：
 * ```kotlin
 * val password = secretStore.generateAndPersistHighEntropyPassword("my-bot")
 * val bot = ImBot.register(
 *     host = "im.virjar.com",
 *     port = 5100,
 *     usernamePrefix = "my-bot",
 *     password = password,
 *     cacheOwner = cacheOwner,
 * )
 * while (true) {
 *     val delivery = bot.nextMessageDelivery { it.senderUid != bot.uid }
 *     bot.sendText(delivery.message.chatId, "echo: ${delivery.message.body}")
 *     bot.ackMessage(delivery)
 * }
 * ```
 *
 * 本类只公开只读身份/连接状态和领域操作；raw transport、mutable identity 与 session
 * ownership 都保持封装。[shutdown] 级联销毁 session（owner-driven）。
 */
class ImBot private constructor(
    private val imClient: ImClient,
    private val session: ClientSession,
    private val userSession: UserSession,
    private val messageInbox: ImBotMessageInbox,
    private val fileRepository: FileRepository?,
    private val authResultAdmission: ImBotAuthResultAdmission,
    private val authenticationLifecycle: ImBotAuthenticationLifecycle,
) {
    private val shutdownLifecycle = ImBotShutdownLifecycle(authResultAdmission)

    /** 当前用户 uid（认证成功后有效）。 */
    val uid: String get() = userSession.uid
    /** 当前认证用户名的只读快照。 */
    val username: String? get() = userSession.username
    /** Raw transport 保持私有；SDK 使用者只能观察其只读状态流。 */
    val connectionState: StateFlow<ConnectionState> get() = imClient.state
    /** 与图形客户端共用的协商事实；不会向调用方暴露 transport owner。 */
    val protocolCompatibility: StateFlow<com.virjar.tk.shared.client.ProtocolCompatibility?>
        get() = imClient.protocolCompatibility
    /** 一个不带 bearer 的终局信号，只在 AUTH_REVOKED 清理全部排空后才发布。 */
    val authenticationTerminal: StateFlow<ImBotAuthenticationTerminal?>
        get() = authenticationLifecycle.terminal

    // ── 事件流（EventProcessor 契约解码后转发；next* 为带缓冲的便捷取用） ──

    /** 入站实时消息流（可合并的广播）；需可靠 backlog 时使用 [nextMessageDelivery]。 */
    val messages: SharedFlow<Message> get() = session.eventProcessor.messageEvents
    /** 联系人关系变更流（申请/接受/删除）。 */
    val contactEvents get() = session.eventProcessor.contactEvents
    /** 群/成员变更流：(变更类型, Chat)。 */
    val chatEvents get() = session.eventProcessor.chatEvents
    /** 好友在线状态流。 */
    val presenceEvents get() = session.eventProcessor.presenceEvents
    /** 当前好友在线状态完整视图；断线时为空，重新认证后由快照恢复。 */
    val friendPresenceByUid: StateFlow<Map<String, FriendPresence>>
        get() = session.friendPresenceByUid
    /** typing 流：(chatId, senderUid)。 */
    val typingEvents get() = session.eventProcessor.typingEvents
    /** 非权威 contact/chat wake-up 被有界缓冲丢弃的累计计数。 */
    val eventBufferOverflow: StateFlow<ImBotEventBufferOverflow> get() = eventBuffers.overflow

    /**
     * [messageInbox] 在会话开始同步前即绑定到 EventProcessor 的可靠投影边界。
     * 它使用账号 SQLite 持久行和 CONFLATED wake-up，因此首次登录 backlog 不依赖
     * SharedFlow 订阅时序，也不会形成无界内存队列。
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val eventBuffers = ImBotEventBuffers()
    private val outgoing = ImBotOutgoing(session) { uid }
    private val httpAuthExpiredBinding = session.bindHttpAuthExpiredHandler(
        authenticationLifecycle::reportHttpUnauthorized,
    )

    init {
        // 这些 flow 是刷新提示而非权威事实：有限 wake-up 满时记数，presence 只保留最新值。
        scope.launch { contactEvents.collect { eventBuffers.offerContact() } }
        scope.launch { chatEvents.collect { eventBuffers.offerChat(it) } }
        scope.launch { presenceEvents.collect { eventBuffers.offerPresence(it) } }
        authenticationLifecycle.bindTerminalHandler(::handleAuthenticationTerminal)
        httpAuthExpiredBinding.activate()
    }

    /** 等待下一个联系人事件（好友申请/接受/删除）。 */
    suspend fun nextContactEvent(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) { eventBuffers.receiveContact() }

    /** 等待下一个群/成员变更事件。 */
    suspend fun nextChatEvent(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) { eventBuffers.receiveChat() }

    /** 等待下一个在线状态事件。 */
    suspend fun nextPresenceEvent(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) { eventBuffers.receivePresence() }

    // ── 消息 ──

    /** 发送 Markdown 文本消息并等待服务端 ACK（普通文本是 Markdown 的自然子集）。 */
    suspend fun sendText(chatId: String, text: String): MessageAckPayload =
        send(chatId, buildRichTextBody(text))

    /** 稳定 id 的持久准入；一旦 SQLite 接管该请求即返回。 */
    suspend fun enqueueText(chatId: String, clientMsgId: String, text: String): OutgoingMessage =
        enqueueMessage(chatId, clientMsgId, buildRichTextBody(text))

    /** 发送富文本（markdown/mention）消息。 */
    suspend fun sendRichText(chatId: String, markdown: String): MessageAckPayload =
        sendText(chatId, markdown)

    suspend fun enqueueRichText(chatId: String, clientMsgId: String, markdown: String): OutgoingMessage =
        enqueueText(chatId, clientMsgId, markdown)

    /** 发送交互卡片（一期静态展示；二期按钮回调）。 */
    suspend fun sendCard(chatId: String, card: com.virjar.tk.protocol.body.CardPayload): MessageAckPayload =
        send(chatId, com.virjar.tk.protocol.body.InteractiveCardBody.of(card))

    // ── 媒体消息（调用方先 uploadFile；相对 path/文件端点 URL 均可，SDK 统一归一化） ──

    suspend fun sendImage(chatId: String, attachment: Attachment, width: Int, height: Int): MessageAckPayload =
        send(chatId, ImageBody(attachment, width, height))

    suspend fun sendFile(chatId: String, attachment: Attachment): MessageAckPayload =
        send(chatId, FileBody(attachment))

    suspend fun enqueueFile(
        chatId: String,
        clientMsgId: String,
        attachment: Attachment,
    ): OutgoingMessage = enqueueMessage(chatId, clientMsgId, FileBody(attachment))

    internal suspend fun enqueueFile(
        chatId: String,
        clientMsgId: String,
        attachment: Attachment,
        requestFingerprint: ByteArray,
    ): OutgoingMessage = enqueueMessage(chatId, clientMsgId, FileBody(attachment), requestFingerprint)

    suspend fun sendVoice(chatId: String, attachment: Attachment, duration: Int): MessageAckPayload =
        send(chatId, VoiceBody(attachment, duration))

    suspend fun sendVideo(
        chatId: String,
        attachment: Attachment,
        duration: Int,
        width: Int,
        height: Int,
        thumbnail: Attachment? = null,
    ): MessageAckPayload = send(
        chatId,
        VideoBody(attachment, duration, width, height, thumbnail),
    )

    /** 流式上传文件到本会话固定的 HTTP 文件服务器。 */
    suspend fun uploadFile(source: UploadSource, fileName: String, contentType: String): Attachment =
        requireNotNull(fileRepository) {
            "ImBot 未配置 fileServerUrl，不能执行文件上传"
        }.upload(source, fileName, contentType).getOrThrow()

    /** 仅供明确的小 payload 使用；大附件必须传入 [UploadSource]。 */
    suspend fun uploadSmallFile(bytes: ByteArray, fileName: String, contentType: String): Attachment =
        uploadFile(bytes.asSmallUploadSource(), fileName, contentType)

    /**
     * 上传并发送一步到位。消息只携带强类型附件描述符，path 为 FileStore 相对路径；
     * 服务端发送时重新核验整个描述符，客户端下载时才绑定当前服务器地址。
     */
    suspend fun uploadAndSendFile(
        chatId: String,
        source: UploadSource,
        fileName: String,
        contentType: String,
    ): MessageAckPayload {
        val attachment = uploadFile(source, fileName, contentType)
        return sendFile(chatId, attachment)
    }

    /** 小 payload 的显式一步上传发送便利入口。 */
    suspend fun uploadAndSendSmallFile(
        chatId: String,
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): MessageAckPayload = uploadAndSendFile(chatId, bytes.asSmallUploadSource(), fileName, contentType)

    /** 尽力而为的 typing 信号；返回 false 表示当前会话的精确 transport 未准入。 */
    fun trySendTyping(chatId: String): Boolean = session.trySendTyping(chatId)

    /** 发送 typing 指示（不等 ACK——服务端对 TYPING 消息只广播不回执）。 */
    fun sendTyping(chatId: String) {
        check(trySendTyping(chatId)) { "Session transport is not available" }
    }

    // ── 消息操作 ──

    suspend fun revoke(chatId: String, serverSeq: Long) = session.messageRepo.revokeMessage(chatId, serverSeq).getOrThrow()
    suspend fun forward(srcChatId: String, srcSeq: Long, targetChatId: String): Message =
        session.messageRepo.forwardMessage(srcChatId, srcSeq, targetChatId).getOrThrow()
    suspend fun markRead(chatId: String, readSeq: Long) = session.messageRepo.markRead(chatId, readSeq).getOrThrow()
    suspend fun getHistory(chatId: String, fromSeq: Long = 0, limit: Int = 10): List<Message> =
        session.messageRepo.getHistory(chatId, fromSeq, limit).getOrThrow()

    // ── 群组 ──

    /** 对未知结果的创建做重试时，必须传入相同的 [operationId]。 */
    suspend fun createGroup(
        operationId: String,
        name: String,
        memberUids: List<String>,
    ): Chat = session.chatRepo.createGroup(
        operationId = operationId,
        name = name,
        memberUids = memberUids,
    ).getOrThrow()

    suspend fun inviteMembers(chatId: String, uids: List<String>) = session.chatRepo.addMembers(chatId, uids).getOrThrow()
    suspend fun groupMembers(chatId: String): List<Member> = session.chatRepo.getMembers(chatId).getOrThrow()

    // ── 社交 ──

    suspend fun applyFriend(targetUid: String, remark: String? = null) = session.contactRepo.apply(targetUid, remark).getOrThrow()
    suspend fun deleteFriend(friendUid: String) = session.contactRepo.deleteFriend(friendUid).getOrThrow()
    suspend fun searchUsers(keyword: String): List<User> = session.userRepo.search(keyword).getOrThrow()

    /** 读取活跃、失败或成功的持久状态，且不改变队列的所有权。 */
    fun outgoingReceipt(chatId: String, clientMsgId: String): OutgoingMessage? =
        outgoing.receipt(chatId, clientMsgId)

    internal fun outgoingReceipt(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray,
    ): OutgoingMessage? = outgoing.receipt(chatId, clientMsgId, requestFingerprint)

    /** 发送任意 body 并等待 durable queue 到达终态；超时后队列仍继续补发。 */
    suspend fun send(chatId: String, body: MessageBody): MessageAckPayload = outgoing.send(chatId, body)

    /** 供无头自动化与可安全重试的集成方使用的稳定 id 入队。 */
    suspend fun enqueueMessage(
        chatId: String,
        clientMsgId: String,
        body: MessageBody,
    ): OutgoingMessage = outgoing.enqueue(chatId, clientMsgId, body)

    private suspend fun enqueueMessage(
        chatId: String,
        clientMsgId: String,
        body: MessageBody,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = outgoing.enqueue(chatId, clientMsgId, body, requestFingerprint)

    /**
     * 等待一条未确认的持久 delivery。匹配 [predicate] 的消息不会自动删除；业务副作用
     * 成功后必须调用 [ackMessage]。被 predicate 排除的消息视为有意忽略并自动确认。
     */
    suspend fun nextMessageDelivery(
        timeoutMs: Long = 10_000,
        predicate: (Message) -> Boolean = { true },
    ): PendingBotMessage =
        withTimeout(timeoutMs) {
            while (true) {
                val pending = messageInbox.receivePending()
                if (predicate(pending.message)) return@withTimeout pending
                messageInbox.ack(pending.eventId)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /** 确认 [nextMessageDelivery] 已完成业务处理；未确认 delivery 会在重启后再次出现。 */
    fun ackMessage(delivery: PendingBotMessage) {
        messageInbox.ack(delivery.eventId)
    }

    /**
     * at-most-once 便利 API：在返回消息前自动确认。执行外部副作用的 bot 应改用
     * [nextMessageDelivery] + [ackMessage]，并以 delivery eventId 或包含操作类型的业务键做幂等。
     */
    suspend fun nextMessage(timeoutMs: Long = 10_000, predicate: (Message) -> Boolean = { true }): Message {
        val delivery = nextMessageDelivery(timeoutMs, predicate)
        ackMessage(delivery)
        return delivery.message
    }

    // ── 社交 / 会话（直通 Repository） ──

    suspend fun createPersonalChat(targetUid: String): String =
        session.chatRepo.createPersonalChat(targetUid).getOrThrow().chatId

    suspend fun listConversations(): List<Conversation> =
        session.conversationRepo.listConversations().getOrThrow()

    suspend fun listFriends(): List<Contact> =
        session.contactRepo.listFriends().getOrThrow()

    suspend fun acceptFriendApply(token: String) =
        session.contactRepo.accept(token).getOrThrow()

    suspend fun pendingApplies() =
        session.contactRepo.listPendingApplies().getOrThrow()

    suspend fun friendApplyRecords(beforeId: Long = 0, limit: Int = 50) =
        session.contactRepo.listApplyRecords(beforeId, limit).getOrThrow()

    suspend fun pendingApplyWith(targetUid: String) =
        session.contactRepo.getPendingApply(targetUid).getOrThrow()

    // ── 生命周期 ──

    /**
     * 等待连接进入指定状态（默认已认证）。等待 AUTHENTICATED 时 [timeoutMs] 表示
     * “无进展窗口”：SYNCHRONIZING 的持久 cursor 每次推进都会续期，而不是限制总同步时长。
     */
    suspend fun awaitState(state: ConnectionState = ConnectionState.AUTHENTICATED, timeoutMs: Long = 15_000) {
        if (state == ConnectionState.AUTHENTICATED) {
            awaitAuthenticatedWithProgress(
                imClient.state,
                imClient.eventSyncCursor,
                imClient.eventSyncProgress,
                timeoutMs,
            )
        } else {
            withTimeout(timeoutMs) { imClient.state.first { it == state } }
        }
    }

    /** 等待某个已建立的认证 owner 被权威性地退场。 */
    suspend fun awaitAuthenticationTerminal(): ImBotAuthenticationTerminal =
        checkNotNull(authenticationTerminal.first { it != null })

    /** 级联销毁会话（owner-driven：bot 是 session 所有者）。 */
    fun shutdown() = closeOwnedResources(SessionEndReason.SHUTDOWN)

    private fun handleAuthenticationTerminal(terminal: ImBotAuthenticationTerminal) {
        if (terminal is ImBotAuthenticationTerminal.HttpUnauthorized) {
            // FileRepository 只在其已准入的 HTTP 操作退出后上报，因此此边界可以
            // 同步阻止任何调用方观察到仍然存活的 bot。
            closeAfterAuthenticationTerminal()
            return
        }
        // AUTH 回调在 ImClient 的精确 attempt 准入内部执行。必须先退出该回调
        // 再销毁 ImClient，否则 teardown 会重入它本应排空的门禁。
        scope.launch { closeAfterAuthenticationTerminal() }
    }

    private fun closeAfterAuthenticationTerminal() {
        try {
            closeOwnedResources(SessionEndReason.AUTH_REVOKED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 生命周期会保留并重放此失败。为 HTTP 调用方保留原始的 AuthExpired；
            // agent 最终运行时的关闭只记录普通的清理诊断。
        }
    }

    private fun closeOwnedResources(requestedReason: SessionEndReason) {
        try {
            shutdownLifecycle.shutdown(
                "HTTP auth expiry binding" to httpAuthExpiredBinding::close,
                "bot scope" to { scope.cancel() },
                "file repository" to { fileRepository?.close() },
                // 在 shutdown 已让 AUTH 退场后再解析。如果某个精确的 401 赢得了这场竞争，
                // 持久化发送队列会在 AUTH_REVOKED 下被取消，而不是被保留。
                "client session" to {
                    session.close(reason = authenticationLifecycle.effectiveEndReason(requestedReason))
                },
                "message inbox" to messageInbox::close,
                "event buffers" to eventBuffers::close,
                "transport" to imClient::destroy,
            )
        } finally {
            authenticationLifecycle.publishClaimedTerminalAfterCleanup()
        }
    }

    companion object {
        /**
         * 注册新账号并登录。
         * @param usernamePrefix 用户名前缀（自动追加随机后缀防冲突）
         * @param password 调用方生成并安全持久化的高熵秘密；SDK 不提供共享默认口令
         */
        suspend fun register(
            host: String,
            port: Int,
            usernamePrefix: String,
            password: String,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
            deviceId: String = "bot-${UUID.randomUUID()}",
            fileServerUrl: String? = null,
            onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)? = null,
        ): ImBot = registerExact(
            host = host,
            port = port,
            username = "$usernamePrefix-${UUID.randomUUID().toString().take(8)}",
            password = password,
            deviceId = deviceId,
            cacheOwner = cacheOwner,
            messageInbox = messageInbox,
            fileServerUrl = fileServerUrl,
            onRefreshCredentials = onRefreshCredentials,
        )

        /**
         * 注册一个由调用方持有的精确身份。持久化的无头启动在持久化 username/password/deviceId
         * 后使用这个窄入口，因此崩溃后可以用同一个账号重试。
         */
        suspend fun registerExact(
            host: String,
            port: Int,
            username: String,
            password: String,
            deviceId: String,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
            fileServerUrl: String? = null,
            onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)? = null,
        ): ImBot = connect(
            host, port, mode = AuthMode.REGISTER,
            username = username,
            password = password, deviceId = deviceId, name = null,
            cacheOwner = cacheOwner, messageInbox = messageInbox, fileServerUrl = fileServerUrl,
            onRefreshCredentials = onRefreshCredentials,
        )

        /** 已有账号登录，常驻调用方必须提供按账号持久的 [cacheOwner]。 */
        suspend fun login(
            host: String,
            port: Int,
            username: String,
            password: String,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
            deviceId: String = "bot-${UUID.randomUUID()}",
            fileServerUrl: String? = null,
            onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)? = null,
        ): ImBot = connect(
            host = host,
            port = port,
            mode = AuthMode.LOGIN,
            username = username,
            password = password,
            deviceId = deviceId,
            name = null,
            cacheOwner = cacheOwner,
            messageInbox = messageInbox,
            fileServerUrl = fileServerUrl,
            onRefreshCredentials = onRefreshCredentials,
        )

        /** 无密码的重启认证，使用调用方持有的单一持久 refresh 身份。 */
        suspend fun authenticate(
            host: String,
            port: Int,
            uid: String,
            refreshToken: String,
            deviceId: String,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
            fileServerUrl: String? = null,
            onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)? = null,
        ): ImBot = connect(
            host = host,
            port = port,
            mode = AuthMode.REFRESH,
            username = null,
            password = null,
            uid = uid,
            refreshToken = refreshToken,
            deviceId = deviceId,
            name = null,
            cacheOwner = cacheOwner,
            messageInbox = messageInbox,
            fileServerUrl = fileServerUrl,
            onRefreshCredentials = onRefreshCredentials,
        )

        private suspend fun connect(
            host: String, port: Int, mode: AuthMode,
            username: String?, password: String?, deviceId: String, name: String?,
            uid: String? = null,
            refreshToken: String? = null,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox,
            fileServerUrl: String?,
            onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)?,
        ): ImBot {
            when (mode) {
                AuthMode.REGISTER -> AuthRules.validateRegister(
                    requireNotNull(username),
                    requireNotNull(password),
                    name ?: requireNotNull(username),
                )
                AuthMode.LOGIN -> AuthRules.validateLogin(requireNotNull(username), requireNotNull(password))
                AuthMode.REFRESH -> Unit
            }
            AuthRules.validateDevice(
                deviceId = deviceId,
                deviceName = "ImBot",
                deviceModel = null,
                deviceFlag = AuthRules.DEVICE_FLAG_UNKNOWN,
            )
            val deploymentIdentity = fileServerUrl?.let { serverUrl ->
                DeploymentIdentity.from(host, port, serverUrl)
            } ?: DeploymentIdentity.fromTcpWithDefaultHttp(host, port)
            val authResult = CompletableDeferred<Boolean>()
            val userSession = UserSession()
            val authResultAdmission = ImBotAuthResultAdmission()
            var authenticationLifecycle: ImBotAuthenticationLifecycle? = null
            lateinit var imClient: ImClient
            imClient = ImClient(
                onAuthResult = authCallback@{
                        success, uid, uname, dispName, refreshToken, access, datasetId, failureReason ->
                    if (
                        success && !uid.isNullOrBlank() &&
                        !uname.isNullOrBlank() && !refreshToken.isNullOrBlank() && !datasetId.isNullOrBlank()
                    ) {
                        try {
                            // 无头安装使用这个低频、有界大小的提交门禁。
                            // 它按设计是同步的：确认过的 refresh 材料必须在
                            // AuthSyncCoordinator 进入同步并变为 ready 之前就已持久化。
                            authResultAdmission.use {
                                admitImBotAuthentication(
                                    userSession = userSession,
                                    uid = uid,
                                    username = uname,
                                    displayName = dispName,
                                    refreshToken = refreshToken,
                                    accessToken = access,
                                    datasetId = datasetId,
                                    onRefreshCredentials = onRefreshCredentials,
                                )
                                if (authenticationLifecycle == null) {
                                    authenticationLifecycle = ImBotAuthenticationLifecycle(
                                        userSession,
                                        authResultAdmission,
                                    )
                                }
                            }
                            authResult.complete(true)
                        } catch (failure: Throwable) {
                            authResult.completeExceptionally(failure)
                            throw failure
                        }
                    } else {
                        val reason = failureReason ?: "auth response did not contain uid"
                        val failureKind = imClient.authenticationFailure.value?.kind
                            ?: AuthenticationFailureKind.REJECTED
                        val establishedLifecycle = authenticationLifecycle
                        if (establishedLifecycle != null) {
                            establishedLifecycle.reportAuthenticationFailure(failureKind, reason)
                            return@authCallback
                        }
                        val admitted = authResultAdmission.runIfActive { userSession.onAuthFailed(reason) }
                        if (!admitted) return@authCallback
                        authResult.completeExceptionally(ImBotAuthenticationRejectedException(failureKind, reason))
                    }
                },
            )
            var session: ClientSession? = null
            var bot: ImBot? = null
            var fileRepository: FileRepository? = null
            try {
                when (mode) {
                    AuthMode.REGISTER -> imClient.register(
                        requireNotNull(username), requireNotNull(password), name ?: requireNotNull(username),
                        deviceId, "ImBot", host, port)
                    AuthMode.LOGIN -> imClient.login(
                        requireNotNull(username), requireNotNull(password), deviceId, "ImBot", host, port)
                    AuthMode.REFRESH -> imClient.authenticate(
                        requireNotNull(uid), requireNotNull(refreshToken), deviceId, "ImBot", host, port)
                }
                // 注册路径在这里之前没有 uid，因而不会创建临时/错误账号目录。
                coroutineScope {
                    val transportFailureWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        val failure = requireNotNull(
                            imClient.authenticationAttemptFailure.first { it != null },
                        )
                        authResult.completeExceptionally(
                            ImBotAuthenticationTransportException(failure.kind, failure.reason),
                        )
                    }
                    try {
                        withTimeout(AUTH_NO_PROGRESS_TIMEOUT_MS) { authResult.await() }
                    } finally {
                        transportFailureWatcher.cancelAndJoin()
                    }
                }
                fileRepository = fileServerUrl?.let { baseUrl ->
                    val lifecycle = checkNotNull(authenticationLifecycle)
                    FileRepository(
                        serverUrl = baseUrl,
                        ownerUid = userSession.uid,
                        credentialsProvider = userSession::httpCredentialsSnapshot,
                        onAuthExpired = lifecycle::reportHttpUnauthorized,
                    )
                }
                session = createSession(
                    imClient,
                    userSession,
                    createCache = { identity, datasetId, uid ->
                        val cache = cacheOwner.open(identity, datasetId, uid)
                        try {
                            messageInbox.bind(cache)
                            cache
                        } catch (failure: Throwable) {
                            cache.close()
                            throw failure
                        }
                    },
                    deploymentIdentity = deploymentIdentity,
                    deviceId = deviceId,
                    logUploadEnabled = false,
                    durableMessageSink = messageInbox::publish,
                    durableChatTombstoneSink = messageInbox::applyChatTombstone,
                )
                // 在等待 SYNC_READY 之前先构造 owner。createSession 装入 EventProcessor 后
                // 回放可能立即开始，因此先等待会丢失 backlog。
                val connectedBot = ImBot(
                    imClient,
                    session,
                    userSession,
                    messageInbox,
                    fileRepository,
                    authResultAdmission,
                    checkNotNull(authenticationLifecycle),
                )
                bot = connectedBot
                imClient.awaitAuthenticated(AUTH_NO_PROGRESS_TIMEOUT_MS)
                logger.trace("session ready uid=${userSession.uid}")
                return connectedBot
            } catch (failure: Throwable) {
                if (bot != null) {
                    bot.shutdown()
                } else {
                    releaseAllSessionResources(
                        "authentication result admission" to { authResultAdmission.close() },
                        "file repository" to { fileRepository?.close() },
                        "client session" to { session?.close() },
                        "message inbox" to messageInbox::close,
                        "transport" to imClient::destroy,
                    )
                }
                throw failure
            }
        }

        private const val AUTH_NO_PROGRESS_TIMEOUT_MS = 15_000L
        private val logger = PlatformOnlyTkLogger("ImBot")
    }

    private enum class AuthMode { REGISTER, LOGIN, REFRESH }
}

private data class AuthenticationProgress(
    val state: ConnectionState,
    val cursor: Long,
    val progressRevision: Long,
)

/**
 * 等待认证完成，但把超时定义成“没有任何同步进展”。一个健康的大历史回放可以超过
 * [noProgressTimeoutMs] 总时长；每次 SYNCHRONIZING cursor 前进都会续期。普通连接状态
 * 抖动不算持久进展，不能靠反复重连无限延长等待。
 */
internal suspend fun awaitAuthenticatedWithProgress(
    state: StateFlow<ConnectionState>,
    syncCursor: StateFlow<Long>,
    syncProgress: StateFlow<Long>,
    noProgressTimeoutMs: Long,
) {
    require(noProgressTimeoutMs > 0L) { "noProgressTimeoutMs must be positive" }
    var cursorHighWatermark = syncCursor.value
    var progressHighWatermark = syncProgress.value
    while (true) {
        val observed = withTimeout(noProgressTimeoutMs) {
            combine(state, syncCursor, syncProgress) { currentState, cursor, progressRevision ->
                AuthenticationProgress(currentState, cursor, progressRevision)
            }.first { next ->
                next.state == ConnectionState.AUTHENTICATED ||
                    next.state == ConnectionState.AUTH_FAILED ||
                    (
                        next.state == ConnectionState.SYNCHRONIZING &&
                            (
                                next.cursor > cursorHighWatermark ||
                                    next.progressRevision > progressHighWatermark
                            )
                    )
            }
        }
        when (observed.state) {
            ConnectionState.AUTHENTICATED -> return
            ConnectionState.AUTH_FAILED -> error("authentication failed")
            ConnectionState.SYNCHRONIZING -> {
                cursorHighWatermark = maxOf(cursorHighWatermark, observed.cursor)
                progressHighWatermark = maxOf(progressHighWatermark, observed.progressRevision)
            }
            else -> error("unexpected authentication progress state: ${observed.state}")
        }
    }
}

/** 等待 [ImClient] 认证完成（连接层状态轮次）。 */
private suspend fun ImClient.awaitAuthenticated(noProgressTimeoutMs: Long) {
    awaitAuthenticatedWithProgress(state, eventSyncCursor, eventSyncProgress, noProgressTimeoutMs)
}
