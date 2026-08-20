package com.virjar.tk.bot

import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessageSender
import com.virjar.tk.client.PendingBotMessage
import com.virjar.tk.client.UserSession
import com.virjar.tk.client.createSession
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
import com.virjar.tk.util.AppLog
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

/**
 * ImBot 的账号缓存打开策略。
 *
 * 调用方必须明确选择持久缓存或测试缓存；[open] 只会在认证成功并取得服务端 uid 后调用，
 * 返回的缓存所有权随即移交给 ClientSession，并由 ImBot.shutdown 级联关闭。
 */
fun interface ImBotCacheOwner {
    fun open(uid: String): LocalCache
}

/**
 * ImBot 的单消费者可靠收件箱。
 *
 * 消息主体落在账号 LocalCache 的磁盘表；进程内只用 CONFLATED wake-up，因此初始 replay
 * 不依赖消费者启动时序、不会形成内存 backlog。eventId 与 `(chatId, serverSeq)` 都是
 * INSERT OR IGNORE 幂等边界。
 */
class ImBotMessageInbox {
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val stateLock = Any()
    private var localCache: LocalCache? = null
    private var closed = false

    /** Cache lifecycle remains owned by ClientSession; inbox only borrows it until [close]. */
    internal fun bind(cache: LocalCache) {
        synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            check(localCache == null) { "ImBot inbox is already bound" }
            localCache = cache
        }
        // Wake a consumer which started before authentication/cache creation.
        wakeUp.trySend(Unit)
    }

    internal suspend fun publish(eventId: Long, message: Message) {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        cache.enqueueBotMessage(eventId, message)
        wakeUp.trySend(Unit)
    }

    /** 读取但不删除最早一条持久消息；业务接受后必须显式 [ack]。 */
    suspend fun receivePending(): PendingBotMessage =
        checkNotNull(receivePendingOrNull()) { "ImBot inbox is closed" }

    /** inbox 关闭时返回 null；未绑定或暂时为空时等待 CONFLATED wake-up。 */
    suspend fun receivePendingOrNull(): PendingBotMessage? {
        while (true) {
            val state = synchronized(stateLock) { localCache to closed }
            if (state.second) return null
            state.first?.peekBotMessage()?.let { return it }
            if (wakeUp.receiveCatching().isClosed) return null
        }
    }

    /** 确认业务已经接受该 delivery；崩溃前未调用则重启后会再次收到。 */
    fun ack(eventId: Long) {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        cache.deleteBotMessage(eventId)
        // There may already be a following durable row.
        wakeUp.trySend(Unit)
    }

    /** tt-agent recent/history 的磁盘事实源；不依赖进程内 ring。 */
    fun recentMessages(chatId: String?, afterSeq: Long, limit: Int): List<Message> {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        return cache.getRecentMessages(chatId, afterSeq, limit)
    }

    /**
     * at-most-once 便利读取：在返回前自动 ack。需要跨业务处理重试时使用
     * [receivePending] + [ack]，不要调用此方法。
     */
    suspend fun receive(): Message {
        val pending = receivePending()
        ack(pending.eventId)
        return pending.message
    }

    /** at-most-once 便利读取；inbox 关闭时返回 null。 */
    suspend fun receiveOrNull(): Message? {
        val pending = receivePendingOrNull() ?: return null
        ack(pending.eventId)
        return pending.message
    }

    internal fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            localCache = null
        }
        wakeUp.close()
    }
}

/** 非权威提示缓冲发生的丢弃计数；消费者收到提示后应从 Repository 重拉详情。 */
data class ImBotEventBufferOverflow(
    val contactEventsDropped: Long = 0L,
    val chatEventsDropped: Long = 0L,
)

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
 * 三级状态说明：uid 在 [UserSession]（用户层），本类是会话所有者——
 * [shutdown] 级联销毁 session（owner-driven）。
 */
class ImBot private constructor(
    val imClient: ImClient,
    val session: ClientSession,
    val userSession: UserSession,
    private val messageSender: MessageSender,
    private val messageInbox: ImBotMessageInbox,
) {
    /** 当前用户 uid（认证成功后有效）。 */
    val uid: String get() = userSession.uid

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

    /** 上传文件到服务器（HTTP），返回服务端权威附件描述符。 */
    suspend fun uploadFile(serverUrl: String, bytes: ByteArray, fileName: String, contentType: String): Attachment =
        FileRepository(serverUrl, requireImBotAccessToken(userSession))
            .upload(bytes, fileName, contentType)
            .getOrThrow()

    /**
     * 上传并发送一步到位。消息只携带强类型附件描述符，path 为 FileStore 相对路径；
     * 服务端发送时重新核验整个描述符，客户端下载时才绑定当前服务器地址。
     */
    suspend fun uploadAndSendFile(serverUrl: String, chatId: String, bytes: ByteArray, fileName: String, contentType: String): MessageAckPayload {
        val attachment = uploadFile(serverUrl, bytes, fileName, contentType)
        return sendFile(chatId, attachment)
    }

    /** 发送 typing 指示（不等 ACK——服务端对 TYPING 消息只广播不回执）。 */
    fun sendTyping(chatId: String) {
        imClient.send(
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
        val shouldShutdown = synchronized(shutdownLock) {
            if (shutdown) false else {
                shutdown = true
                true
            }
        }
        if (!shouldShutdown) return
        scope.cancel()
        // Stop the producer first, then detach inbox consumers while its borrowed cache is open.
        session.eventProcessor.stop()
        messageInbox.close()
        eventBuffers.close()
        session.close()
        imClient.destroy()
    }

    private val shutdownLock = Any()
    private var shutdown = false

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
        ): ImBot = connect(
            host, port, mode = AuthMode.REGISTER,
            username = "$usernamePrefix-${UUID.randomUUID().toString().take(8)}",
            password = password, deviceId = deviceId, name = null,
            cacheOwner = cacheOwner, messageInbox = messageInbox,
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
        ): ImBot = connect(
            host, port, AuthMode.LOGIN, username, password, deviceId, null,
            cacheOwner, messageInbox,
        )

        private suspend fun connect(
            host: String, port: Int, mode: AuthMode,
            username: String, password: String, deviceId: String, name: String?,
            cacheOwner: ImBotCacheOwner,
            messageInbox: ImBotMessageInbox,
        ): ImBot {
            val authResult = CompletableDeferred<Boolean>()
            val userSession = UserSession()
            val imClient = ImClient(
                onAuthResult = { success, uid, uname, dispName, refreshToken, access, failureReason ->
                    if (success && !uid.isNullOrBlank()) {
                        userSession.onAuthSuccess(uid, uname, dispName, refreshToken, access)
                        authResult.complete(true)
                    } else {
                        val reason = failureReason ?: "auth response did not contain uid"
                        userSession.onAuthFailed(reason)
                        authResult.completeExceptionally(IllegalStateException("auth failed: $reason"))
                    }
                },
            )
            var session: ClientSession? = null
            var bot: ImBot? = null
            try {
                when (mode) {
                    AuthMode.REGISTER -> imClient.register(
                        username, password, name ?: username, deviceId, "ImBot", host, port)
                    AuthMode.LOGIN -> imClient.login(
                        username, password, deviceId, "ImBot", host, port)
                }
                // 注册路径在这里之前没有 uid，因而不会创建临时/错误账号目录。
                withTimeout(AUTH_NO_PROGRESS_TIMEOUT_MS) { authResult.await() }
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
                val sender = MessageSender { msg -> imClient.sendAndWaitAck(msg) }
                // Construct the owner before awaiting SYNC_READY. Replay may start immediately
                // after createSession installs EventProcessor, so waiting first would lose backlog.
                val connectedBot = ImBot(imClient, session, userSession, sender, messageInbox)
                bot = connectedBot
                imClient.awaitAuthenticated(AUTH_NO_PROGRESS_TIMEOUT_MS)
                AppLog.trace("ImBot", "session ready uid=${userSession.uid}")
                return connectedBot
            } catch (failure: Throwable) {
                if (bot != null) {
                    bot.shutdown()
                } else {
                    session?.close()
                    messageInbox.close()
                    imClient.destroy()
                }
                throw failure
            }
        }

        private const val AUTH_NO_PROGRESS_TIMEOUT_MS = 15_000L
    }

    private enum class AuthMode { REGISTER, LOGIN }
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

/** ImBot 的 HTTP 凭据严格属于当前 UserSession，不允许回退到 UI 进程全局 token。 */
internal fun requireImBotAccessToken(userSession: UserSession): String =
    requireNotNull(userSession.accessToken) { "ImBot session has no access token" }
