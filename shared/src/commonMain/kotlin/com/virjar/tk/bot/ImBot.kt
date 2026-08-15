package com.virjar.tk.bot

import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.SessionContext
import com.virjar.tk.client.MessageSender
import com.virjar.tk.client.UserSession
import com.virjar.tk.client.createSession
import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.TextBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.model.MessageBody
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.repository.FileRepository
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * 无头 IM 客户端（AI bot / CLI / 服务器端集成入口）。
 *
 * 复用 SDK 完整闭环（ImClient 连接 + ClientSession 组装 + EventProcessor 事件同步），
 * 内存 LocalCache（[FakeLocalCache]，零持久化），无任何 UI 依赖。
 *
 * 典型用法：
 * ```kotlin
 * val bot = ImBot.register("im.virjar.com", 5100, "my-bot")
 * bot.messages.collect { msg ->                      // 收消息
 *     bot.sendText(msg.chatId, "echo: ${msg.body}")  // 回消息
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
) {
    /** 当前用户 uid（认证成功后有效）。 */
    val uid: String get() = userSession.uid

    // ── 事件流（EventProcessor 契约解码后转发；next* 为带缓冲的便捷取用） ──

    /** 入站消息流（所有会话）。 */
    val messages: SharedFlow<Message> get() = session.eventProcessor.messageEvents
    /** 联系人关系变更流（申请/接受/删除）。 */
    val contactEvents get() = session.eventProcessor.contactEvents
    /** 群/成员变更流：(变更类型, Chat)。 */
    val chatEvents get() = session.eventProcessor.chatEvents
    /** 好友在线状态流。 */
    val presenceEvents get() = session.eventProcessor.presenceEvents
    /** typing 流：(chatId, senderUid)。 */
    val typingEvents get() = session.eventProcessor.typingEvents

    /**
     * 消息缓冲：ImBot 创建即订阅 [messages] 转入 Channel（UNLIMITED），
     * 保证 [nextMessage] 在消息到达之后调用也不丢（SharedFlow 无 replay，
     * 晚订阅会错过 emit）。
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val messageChannel = Channel<Message>(Channel.UNLIMITED)
    private val contactChannel = Channel<Unit>(Channel.UNLIMITED)
    private val chatChannel = Channel<Pair<NotifyType, Chat>>(Channel.UNLIMITED)
    private val presenceChannel = Channel<PresencePayload>(Channel.UNLIMITED)

    init {
        // 各流启动即缓冲（SharedFlow 无 replay，晚订阅会错过 emit——见 lessons C2）
        scope.launch { messages.collect { messageChannel.send(it) } }
        scope.launch { contactEvents.collect { contactChannel.send(it) } }
        scope.launch { chatEvents.collect { chatChannel.send(it) } }
        scope.launch { presenceEvents.collect { presenceChannel.send(it) } }
    }

    /** 等待下一个联系人事件（好友申请/接受/删除）。 */
    suspend fun nextContactEvent(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) { contactChannel.receive() }

    /** 等待下一个群/成员变更事件。 */
    suspend fun nextChatEvent(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) { chatChannel.receive() }

    /** 等待下一个在线状态事件。 */
    suspend fun nextPresenceEvent(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) { presenceChannel.receive() }

    // ── 消息 ──

    /** 发送文本消息并等待服务端 ACK。 */
    suspend fun sendText(chatId: String, text: String): MessageAckPayload =
        send(chatId, TextBody(text))

    // ── 媒体消息（URL 模式：调用方先行 uploadFile 上传） ──

    suspend fun sendImage(chatId: String, url: String, width: Int, height: Int, size: Long): MessageAckPayload =
        send(chatId, ImageBody(url, width, height, size), MessageType.IMAGE)

    suspend fun sendFile(chatId: String, url: String, fileName: String, size: Long): MessageAckPayload =
        send(chatId, FileBody(url, fileName, size), MessageType.FILE)

    suspend fun sendVoice(chatId: String, url: String, duration: Int, size: Long): MessageAckPayload =
        send(chatId, VoiceBody(url, duration, size), MessageType.VOICE)

    suspend fun sendVideo(chatId: String, url: String, duration: Int, width: Int, height: Int, size: Long, thumbnailUrl: String? = null): MessageAckPayload =
        send(chatId, VideoBody(url, duration, width, height, size, thumbnailUrl), MessageType.VIDEO)

    /** 上传文件到服务器（HTTP），返回相对 path。 */
    suspend fun uploadFile(serverUrl: String, bytes: ByteArray, fileName: String, contentType: String): String =
        FileRepository(serverUrl, userSession.accessToken ?: SessionContext.accessToken).upload(bytes, fileName, contentType).getOrThrow()

    /** 上传并发送一步到位。 */
    suspend fun uploadAndSendFile(serverUrl: String, chatId: String, bytes: ByteArray, fileName: String, contentType: String): MessageAckPayload {
        val path = uploadFile(serverUrl, bytes, fileName, contentType)
        return sendFile(chatId, path, fileName, bytes.size.toLong())
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
    suspend fun getHistory(chatId: String, fromSeq: Long = 0, limit: Int = 50): List<Message> =
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

    /** 发送任意 body 消息并等待服务端 ACK。 */
    suspend fun send(chatId: String, body: MessageBody, messageType: MessageType = MessageType.TEXT): MessageAckPayload {
        val message = Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = uid,
            messageType = messageType.code,
            timestamp = System.currentTimeMillis(),
            body = body,
        )
        return messageSender.sendAndWaitAck(message)
    }

    /**
     * 等待下一条入站消息（从启动即缓冲的 Channel 取，不丢历史）。
     *
     * 注意：服务端 MESSAGE_RECV 推给全部成员（含发送者，UI 客户端靠
     * LocalCache 幂等覆盖消化），bot 侧通常要用 [predicate] 过滤自己：
     * `nextMessage { it.senderUid != uid }`。
     */
    suspend fun nextMessage(timeoutMs: Long = 10_000, predicate: (Message) -> Boolean = { true }): Message =
        withTimeout(timeoutMs) {
            while (true) {
                val m = messageChannel.receive()
                if (predicate(m)) return@withTimeout m
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
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
        session.contactRepo.listApplies().getOrThrow()

    // ── 生命周期 ──

    /** 等待连接进入指定状态（默认已认证）。 */
    suspend fun awaitState(state: ConnectionState = ConnectionState.AUTHENTICATED, timeoutMs: Long = 15_000) {
        withTimeout(timeoutMs) { imClient.state.first { it == state } }
    }

    /** 级联销毁会话（owner-driven：bot 是 session 所有者）。 */
    fun shutdown() {
        SessionContext.accessToken = null
        scope.cancel()
        messageChannel.close()
        session.close()
        imClient.destroy()
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
            password: String = "password123",
            deviceId: String = "bot-${UUID.randomUUID()}",
        ): ImBot = connect(
            host, port, mode = AuthMode.REGISTER,
            username = "$usernamePrefix-${UUID.randomUUID().toString().take(8)}",
            password = password, deviceId = deviceId, name = null,
        )

        /** 已有账号登录。 */
        suspend fun login(
            host: String,
            port: Int,
            username: String,
            password: String,
            deviceId: String = "bot-${UUID.randomUUID()}",
        ): ImBot = connect(host, port, AuthMode.LOGIN, username, password, deviceId, null)

        private suspend fun connect(
            host: String, port: Int, mode: AuthMode,
            username: String, password: String, deviceId: String, name: String?,
        ): ImBot {
            val authResult = CompletableDeferred<Boolean>()
            val userSession = UserSession()
            val imClient = ImClient(
                onAuthResult = { success, uid, uname, dispName, refreshToken, access, failureReason ->
                    if (success) {
                        userSession.onAuthSuccess(uid ?: "", uname, dispName, refreshToken, access)
                        authResult.complete(true)
                    } else {
                        userSession.onAuthFailed(failureReason)
                        authResult.completeExceptionally(IllegalStateException("auth failed: $failureReason"))
                    }
                },
            )
            when (mode) {
                AuthMode.REGISTER -> imClient.register(
                    username, password, name ?: username, deviceId, "ImBot", host, port)
                AuthMode.LOGIN -> imClient.login(
                    username, password, deviceId, "ImBot", host, port)
            }
            // 等认证结果（15s），成功后组装完整会话
            withTimeout(15_000) { authResult.await() }
            imClient.awaitAuthenticated()

            val session = createSession(
                imClient, userSession, createCache = { FakeLocalCache() }, deviceId,
                logUploadEnabled = false,  // 无头场景 serverUrl 未知；日志本地 buffer 照常
            )
            val sender = MessageSender { msg -> imClient.sendAndWaitAck(msg) }
            SessionContext.accessToken = userSession.accessToken
            AppLog.trace("ImBot", "session ready uid=${userSession.uid}")
            return ImBot(imClient, session, userSession, sender)
        }
    }

    private enum class AuthMode { REGISTER, LOGIN }
}

/** 等待 [ImClient] 认证完成（连接层状态轮次）。 */
private suspend fun ImClient.awaitAuthenticated() {
    withTimeout(15_000) { state.first { it == ConnectionState.AUTHENTICATED } }
}
