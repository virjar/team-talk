package com.virjar.tk.bot

import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessageSender
import com.virjar.tk.client.UserSession
import com.virjar.tk.client.createSession
import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Message
import com.virjar.tk.body.TextBody
import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.MessageAckPayload
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

    /** 入站消息流（所有会话）。由 EventProcessor 按契约解码后转发。 */
    val messages: SharedFlow<Message> get() = session.eventProcessor.messageEvents

    /**
     * 消息缓冲：ImBot 创建即订阅 [messages] 转入 Channel（UNLIMITED），
     * 保证 [nextMessage] 在消息到达之后调用也不丢（SharedFlow 无 replay，
     * 晚订阅会错过 emit）。
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val messageChannel = Channel<Message>(Channel.UNLIMITED)

    init {
        scope.launch { messages.collect { messageChannel.send(it) } }
    }

    // ── 消息 ──

    /** 发送文本消息并等待服务端 ACK。 */
    suspend fun sendText(chatId: String, text: String): MessageAckPayload =
        send(chatId, TextBody(text))

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
                onAuthResult = { success, uid, uname, dispName, refreshToken, failureReason ->
                    if (success) {
                        userSession.onAuthSuccess(uid ?: "", uname, dispName, refreshToken)
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

            val session = createSession(imClient, userSession, createCache = { FakeLocalCache() }, deviceId)
            val sender = MessageSender { msg -> imClient.sendAndWaitAck(msg) }
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
