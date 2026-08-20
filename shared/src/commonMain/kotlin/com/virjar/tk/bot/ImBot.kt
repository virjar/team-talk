package com.virjar.tk.bot

import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.AuthenticationFailureKind
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.MessageSender
import com.virjar.tk.client.PendingBotMessage
import com.virjar.tk.client.SessionBoundaryReentrantCloseException
import com.virjar.tk.client.SessionEndReason
import com.virjar.tk.client.SessionResourceCloseException
import com.virjar.tk.client.SessionWorkGate
import com.virjar.tk.client.SessionWorkGateReentrantCloseException
import com.virjar.tk.client.UserSession
import com.virjar.tk.client.createSession
import com.virjar.tk.client.releaseAllSessionResources
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.VideoBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.body.MessageBody
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.model.Attachment
import com.virjar.tk.repository.FileRepository
import com.virjar.tk.repository.UploadSource
import com.virjar.tk.repository.asSmallUploadSource
import com.virjar.tk.util.PlatformOnlyTkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** 非权威提示缓冲发生的丢弃计数；消费者收到提示后应从 Repository 重拉详情。 */
data class ImBotEventBufferOverflow(
    val contactEventsDropped: Long = 0L,
    val chatEventsDropped: Long = 0L,
)

/** A typed server rejection, distinct from transport and timeout failures. */
internal class ImBotAuthenticationRejectedException(
    val kind: AuthenticationFailureKind,
    reason: String,
) : IllegalStateException("authentication rejected: $reason") {
    val requiresOperatorIntervention: Boolean
        get() = kind !in setOf(
            AuthenticationFailureKind.SERVER_MAINTENANCE,
            AuthenticationFailureKind.TOO_MANY_CONNECTIONS,
        )
}

/** Durably admits rotated credentials before atomically publishing the live identity. */
internal fun admitImBotAuthentication(
    userSession: UserSession,
    uid: String,
    username: String,
    displayName: String?,
    refreshToken: String,
    accessToken: String?,
    onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)?,
) {
    userSession.onAuthSuccess(
        uid = uid,
        username = username,
        name = displayName,
        refreshToken = refreshToken,
        accessToken = accessToken,
        durableCommit = {
            onRefreshCredentials?.invoke(uid, username, refreshToken)
        },
    )
}

/** Drains an admitted durable credential commit before the bot owner can finish shutdown. */
internal class ImBotAuthResultAdmission {
    private val gate = SessionWorkGate("ImBot authentication result")
    private val lease = gate.lease()

    fun <T> use(block: () -> T): T = gate.use(lease, block)

    fun runIfActive(block: () -> Unit): Boolean = gate.runIfActive(lease, block)

    fun close(): Boolean = gate.close()
}

/** Joins every shutdown caller to one cleanup and one terminal outcome without lock inversion. */
internal class ImBotShutdownLifecycle(
    private val authResultAdmission: ImBotAuthResultAdmission,
) {
    private val lock = Any()
    private var phase = Phase.OPEN
    private var terminalFailure: Throwable? = null

    fun shutdown(vararg releases: Pair<String, () -> Unit>) {
        // Never wait for the auth gate while holding [lock]. A durable hook is allowed to call
        // shutdown reentrantly, so lock -> gate would deadlock against that hook's gate -> lock.
        var boundaryFailure: SessionWorkGateReentrantCloseException? = null
        try {
            authResultAdmission.close()
        } catch (failure: SessionWorkGateReentrantCloseException) {
            boundaryFailure = failure
        }

        synchronized(lock) {
            when (phase) {
                Phase.CLOSED -> {
                    terminalFailure?.let { throw it }
                    return
                }
                Phase.CLOSING -> throw SessionBoundaryReentrantCloseException(
                    "ImBot shutdown cannot reenter its resource cleanup",
                )
                Phase.OPEN -> phase = Phase.CLOSING
            }

            val failures = releaseAllSessionResources(*releases)
            terminalFailure = boundaryFailure
                ?: failures.firstOrNull { it.second is SessionBoundaryReentrantCloseException }?.second
                ?: if (failures.isNotEmpty()) {
                    SessionResourceCloseException("ImBot", failures.map { failure -> failure.second })
                } else {
                    null
                }
            phase = Phase.CLOSED
            terminalFailure?.let { throw it }
        }
    }

    private enum class Phase { OPEN, CLOSING, CLOSED }
}

/** contact/chat 是有界 wake-up，presence 只保留最新值；权威事实始终在 LocalCache/RPC。 */
internal class ImBotEventBuffers {
    private val contactChannel = Channel<Unit>(CONTACT_CAPACITY)
    private val chatChannel = Channel<Pair<NotifyType, Chat>>(CHAT_CAPACITY)
    private val presenceChannel = Channel<PresencePayload>(Channel.CONFLATED)
    private val _overflow = MutableStateFlow(ImBotEventBufferOverflow())
    val overflow: StateFlow<ImBotEventBufferOverflow> = _overflow.asStateFlow()

    fun offerContact() {
        if (contactChannel.trySend(Unit).isFailure) {
            _overflow.update { it.copy(contactEventsDropped = it.contactEventsDropped + 1L) }
        }
    }

    fun offerChat(event: Pair<NotifyType, Chat>) {
        if (chatChannel.trySend(event).isFailure) {
            _overflow.update { it.copy(chatEventsDropped = it.chatEventsDropped + 1L) }
        }
    }

    fun offerPresence(event: PresencePayload) {
        presenceChannel.trySend(event)
    }

    suspend fun receiveContact(): Unit = contactChannel.receive()
    suspend fun receiveChat(): Pair<NotifyType, Chat> = chatChannel.receive()
    suspend fun receivePresence(): PresencePayload = presenceChannel.receive()

    fun close() {
        contactChannel.close()
        chatChannel.close()
        presenceChannel.close()
    }

    companion object {
        const val CONTACT_CAPACITY = 32
        const val CHAT_CAPACITY = 64
    }
}

/**
 * 无头 IM 客户端（AI bot / CLI / 服务器端集成入口）。
 *
 * 复用 SDK 完整闭环（ImClient 连接 + ClientSession 组装 + EventProcessor 事件同步），
 * LocalCache 与可靠 inbox 均由调用方明确提供 owner，无任何 UI 依赖。
 *
 * 典型用法：
 * ```kotlin
 * val bot = ImBot.register("im.virjar.com", 5100, "my-bot", cacheOwner)
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
    private val messageSender: MessageSender,
    private val messageInbox: ImBotMessageInbox,
    private val fileRepository: FileRepository?,
    private val authResultAdmission: ImBotAuthResultAdmission,
) {
    private val shutdownLifecycle = ImBotShutdownLifecycle(authResultAdmission)

    /** 当前用户 uid（认证成功后有效）。 */
    val uid: String get() = userSession.uid
    /** 当前认证用户名的只读快照。 */
    val username: String? get() = userSession.username
    /** Raw transport stays private; SDK consumers observe only its read-only state flow. */
    val connectionState: StateFlow<ConnectionState> get() = imClient.state

    /** Narrow integration-test hook; production callers cannot mutate the raw transport. */
    internal fun simulateNetworkDropForTest() = imClient.simulateNetworkDrop()

    /** Narrow integration-test projection read; the owned ClientSession remains private. */
    internal fun cachedConversationsForTest(): List<Conversation> =
        session.localCache.getConversations()

    // ── 事件流（EventProcessor 契约解码后转发；next* 为带缓冲的便捷取用） ──

    /** 入站实时消息流（可合并的广播）；需可靠 backlog 时使用 [nextMessageDelivery]。 */
    val messages: SharedFlow<Message> get() = session.eventProcessor.messageEvents
    /** 联系人关系变更流（申请/接受/删除）。 */
    val contactEvents get() = session.eventProcessor.contactEvents
    /** 群/成员变更流：(变更类型, Chat)。 */
    val chatEvents get() = session.eventProcessor.chatEvents
    /** 好友在线状态流。 */
    val presenceEvents get() = session.eventProcessor.presenceEvents
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

    init {
        // 这些 flow 是刷新提示而非权威事实：有限 wake-up 满时记数，presence 只保留最新值。
        scope.launch { contactEvents.collect { eventBuffers.offerContact() } }
        scope.launch { chatEvents.collect { eventBuffers.offerChat(it) } }
        scope.launch { presenceEvents.collect { eventBuffers.offerPresence(it) } }
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

    /** 发送富文本（markdown/mention）消息。 */
    suspend fun sendRichText(chatId: String, markdown: String): MessageAckPayload =
        sendText(chatId, markdown)

    /** 发送交互卡片（一期静态展示；二期按钮回调）。 */
    suspend fun sendCard(chatId: String, card: com.virjar.tk.body.CardPayload): MessageAckPayload =
        send(chatId, com.virjar.tk.body.InteractiveCardBody.of(card))

    // ── 媒体消息（调用方先 uploadFile；相对 path/文件端点 URL 均可，SDK 统一归一化） ──

    suspend fun sendImage(chatId: String, attachment: Attachment, width: Int, height: Int): MessageAckPayload =
        send(chatId, ImageBody(attachment, width, height))

    suspend fun sendFile(chatId: String, attachment: Attachment): MessageAckPayload =
        send(chatId, FileBody(attachment))

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

    /** 发送 typing 指示（不等 ACK——服务端对 TYPING 消息只广播不回执）。 */
    fun sendTyping(chatId: String) {
        session.sendTransient(
            Message(
                chatId = chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = uid,
                messageType = MessageType.TYPING.code,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    // ── 消息操作 ──

    suspend fun revoke(chatId: String, serverSeq: Long) = session.messageRepo.revokeMessage(chatId, serverSeq).getOrThrow()
    suspend fun forward(srcChatId: String, srcSeq: Long, targetChatId: String): Message =
        session.messageRepo.forwardMessage(srcChatId, srcSeq, targetChatId).getOrThrow()
    suspend fun markRead(chatId: String, readSeq: Long) = session.messageRepo.markRead(chatId, readSeq).getOrThrow()
    suspend fun getHistory(chatId: String, fromSeq: Long = 0, limit: Int = 10): List<Message> =
        session.messageRepo.getHistory(chatId, fromSeq, limit).getOrThrow()

    // ── 群组 ──

    suspend fun createGroup(name: String, memberUids: List<String>): Chat =
        session.chatRepo.createGroup(name, memberUids = memberUids).getOrThrow()

    suspend fun inviteMembers(chatId: String, uids: List<String>) = session.chatRepo.addMembers(chatId, uids).getOrThrow()
    suspend fun groupMembers(chatId: String): List<Member> = session.chatRepo.getMembers(chatId).getOrThrow()

    // ── 社交 ──

    suspend fun applyFriend(targetUid: String, remark: String? = null) = session.contactRepo.apply(targetUid, remark).getOrThrow()
    suspend fun deleteFriend(friendUid: String) = session.contactRepo.deleteFriend(friendUid).getOrThrow()
    suspend fun searchUsers(keyword: String): List<User> = session.userRepo.search(keyword).getOrThrow()

    /** 发送任意 body 消息并等待服务端 ACK；消息类型由 body 唯一推导，调用方不能错配。 */
    suspend fun send(chatId: String, body: MessageBody): MessageAckPayload {
        val messageType = MessageBodyPolicy.typeOf(body)
        val message = MessageBodyPolicy.canonicalize(Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = uid,
            messageType = messageType.code,
            timestamp = System.currentTimeMillis(),
            body = body,
        ))
        return messageSender.sendAndWaitAck(message)
    }

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
     * [nextMessageDelivery] + [ackMessage]，并以 `(chatId, serverSeq)` 做业务幂等。
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
            awaitAuthenticatedWithProgress(imClient.state, imClient.eventSyncCursor, timeoutMs)
        } else {
            withTimeout(timeoutMs) { imClient.state.first { it == state } }
        }
    }

    /** 级联销毁会话（owner-driven：bot 是 session 所有者）。 */
    fun shutdown() {
        shutdownLifecycle.shutdown(
            "bot scope" to { scope.cancel() },
            // ClientSession stops the producer and closes its cache before the inbox detaches.
            "client session" to { session.close(reason = SessionEndReason.SHUTDOWN) },
            "file repository" to { fileRepository?.close() },
            "message inbox" to messageInbox::close,
            "event buffers" to eventBuffers::close,
            "transport" to imClient::destroy,
        )
    }

    companion object {
        /**
         * 注册新账号并登录。
         * @param usernamePrefix 用户名前缀（自动追加随机后缀防冲突）
         */
        suspend fun register(
            host: String,
            port: Int,
            usernamePrefix: String,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
            password: String = "password123",
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
         * Registers one caller-owned exact identity. Durable headless bootstrap uses this narrow
         * entry after persisting username/password/deviceId, so a crash can retry the same account.
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

        /** Password-free restart authentication with one caller-owned durable refresh identity. */
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
            val authResult = CompletableDeferred<Boolean>()
            val userSession = UserSession()
            val authResultAdmission = ImBotAuthResultAdmission()
            lateinit var imClient: ImClient
            imClient = ImClient(
                onAuthResult = authCallback@{ success, uid, uname, dispName, refreshToken, access, failureReason ->
                    if (
                        success && !uid.isNullOrBlank() &&
                        !uname.isNullOrBlank() && !refreshToken.isNullOrBlank()
                    ) {
                        try {
                            // Headless installs use this low-frequency, size-bounded commit gate.
                            // It is synchronous by design: rotated refresh material is durable
                            // before AuthSyncCoordinator may enter synchronization and then ready.
                            authResultAdmission.use {
                                admitImBotAuthentication(
                                    userSession = userSession,
                                    uid = uid,
                                    username = uname,
                                    displayName = dispName,
                                    refreshToken = refreshToken,
                                    accessToken = access,
                                    onRefreshCredentials = onRefreshCredentials,
                                )
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
                        val admitted = authResultAdmission.runIfActive {
                            userSession.onAuthFailed(reason)
                        }
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
                withTimeout(AUTH_NO_PROGRESS_TIMEOUT_MS) { authResult.await() }
                fileRepository = fileServerUrl?.let { baseUrl ->
                    FileRepository(
                        serverUrl = baseUrl,
                        ownerUid = userSession.uid,
                        credentialsProvider = userSession::httpCredentialsSnapshot,
                    )
                }
                session = createSession(
                    imClient,
                    userSession,
                    createCache = { uid ->
                        val cache = cacheOwner.open(uid)
                        try {
                            messageInbox.bind(cache)
                            cache
                        } catch (failure: Throwable) {
                            cache.close()
                            throw failure
                        }
                    },
                    deviceId,
                    logUploadEnabled = false,
                    durableMessageSink = messageInbox::publish,
                )
                // Construct the owner before awaiting SYNC_READY. Replay may start immediately
                // after createSession installs EventProcessor, so waiting first would lose backlog.
                val connectedBot = ImBot(
                    imClient,
                    session,
                    userSession,
                    session.messageSender,
                    messageInbox,
                    fileRepository,
                    authResultAdmission,
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
)

/**
 * 等待认证完成，但把超时定义成“没有任何同步进展”。一个健康的大历史回放可以超过
 * [noProgressTimeoutMs] 总时长；每次 SYNCHRONIZING cursor 前进都会续期。普通连接状态
 * 抖动不算持久进展，不能靠反复重连无限延长等待。
 */
internal suspend fun awaitAuthenticatedWithProgress(
    state: StateFlow<ConnectionState>,
    syncCursor: StateFlow<Long>,
    noProgressTimeoutMs: Long,
) {
    require(noProgressTimeoutMs > 0L) { "noProgressTimeoutMs must be positive" }
    var lastProgressCursor = syncCursor.value
    while (true) {
        val observed = withTimeout(noProgressTimeoutMs) {
            combine(state, syncCursor) { currentState, cursor ->
                AuthenticationProgress(currentState, cursor)
            }.first { next ->
                next.state == ConnectionState.AUTHENTICATED ||
                    next.state == ConnectionState.AUTH_FAILED ||
                    (next.state == ConnectionState.SYNCHRONIZING && next.cursor != lastProgressCursor)
            }
        }
        when (observed.state) {
            ConnectionState.AUTHENTICATED -> return
            ConnectionState.AUTH_FAILED -> error("authentication failed")
            ConnectionState.SYNCHRONIZING -> lastProgressCursor = observed.cursor
            else -> error("unexpected authentication progress state: ${observed.state}")
        }
    }
}

/** 等待 [ImClient] 认证完成（连接层状态轮次）。 */
private suspend fun ImClient.awaitAuthenticated(noProgressTimeoutMs: Long) {
    awaitAuthenticatedWithProgress(state, eventSyncCursor, noProgressTimeoutMs)
}
