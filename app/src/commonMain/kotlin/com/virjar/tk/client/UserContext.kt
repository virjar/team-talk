package com.virjar.tk.client

import com.virjar.tk.database.AppDatabase
import com.virjar.tk.database.LocalCache
import com.virjar.tk.dto.DeviceDto
import com.virjar.tk.dto.UserDto
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.MessageBody
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.repository.*
import com.virjar.tk.storage.TokenStorage
import com.virjar.tk.util.AppLog
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class HistoryLoadResult(val hasMore: Boolean)
data class SendResult(val messageId: String, val serverSeq: Long)

class UserContext(
    val token: String,
    val uid: String,
    val user: UserDto,
    private val apiClient: ApiClient,
    private val tokenStorage: TokenStorage,
) : ImStateListener {
    var onForceLogout: (() -> Unit)? = null

    // 用户级协程作用域（跨页面，登出/踢登时 cancel）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 连接状态 ──
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ── IM 客户端 ──
    private var imClient: ImClient? = null

    // ── 本地数据库 ──
    private val appDatabase = AppDatabase()
    val localCache = LocalCache(appDatabase.queries)

    // ── 消息监听器 ──
    private val messageListeners = mutableListOf<(Message) -> Unit>()

    fun addMessageListener(listener: (Message) -> Unit): () -> Unit {
        synchronized(messageListeners) { messageListeners.add(listener) }
        return { synchronized(messageListeners) { messageListeners.remove(listener) } }
    }

    // ── 在线状态（PRESENCE 实时更新） ──
    private val _onlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val onlineStatus: StateFlow<Map<String, Boolean>> = _onlineStatus.asStateFlow()

    fun mergeOnlineStatus(statusMap: Map<String, Boolean>) {
        val current = _onlineStatus.value.toMutableMap()
        current.putAll(statusMap)
        _onlineStatus.value = current
    }

    // ── 输入状态监听器 ──
    private val typingListeners = mutableListOf<(String, String, Byte) -> Unit>()

    fun addTypingListener(listener: (channelId: String, senderUid: String, action: Byte) -> Unit): () -> Unit {
        synchronized(typingListeners) { typingListeners.add(listener) }
        return { synchronized(typingListeners) { typingListeners.remove(listener) } }
    }

    // ── 编辑消息监听器 ──
    private val editListeners = mutableListOf<(String, String, String, Long) -> Unit>()

    fun addEditListener(listener: (channelId: String, targetMessageId: String, newPayload: String, editedAt: Long) -> Unit): () -> Unit {
        synchronized(editListeners) { editListeners.add(listener) }
        return { synchronized(editListeners) { editListeners.remove(listener) } }
    }

    // ── 频道事件监听器（禁言/角色变更） ──
    private val channelEventListeners = mutableListOf<(String, String) -> Unit>()

    fun addChannelEventListener(listener: (channelId: String, eventType: String) -> Unit): () -> Unit {
        synchronized(channelEventListeners) { channelEventListeners.add(listener) }
        return { synchronized(channelEventListeners) { channelEventListeners.remove(listener) } }
    }

    private fun dispatchChannelEvent(channelId: String, eventType: String) {
        synchronized(channelEventListeners) {
            channelEventListeners.forEach { it(channelId, eventType) }
        }
    }

    // ── HTTP 辅助 ──
    val httpClient: HttpClient get() = apiClient.client
    val baseUrl: String get() = apiClient.baseUrl
    val tcpHost: String get() = apiClient.tcpHost
    val tcpPort: Int get() = apiClient.tcpPort
    fun authHeader(): String = "Bearer $token"

    // ── Repositories（在 UserContext 内创建） ──
    val chatRepo = ChatRepository(this)
    val conversationRepo = ConversationRepository(this)
    val contactRepo = ContactRepository(this)
    val channelRepo = ChannelRepository(this)
    val userRepo = UserRepository(this)
    val fileRepo = FileRepository(this)

    // ── HTTP 401 拦截 ──

    init {
        apiClient.onUnauthorized = {
            AppLog.w("UserContext", "HTTP 401 received, forcing logout")
            onForceLogout?.invoke()
        }
    }

    // ── TCP 连接 ──

    fun connectTcp() {
        val im = ImClient(tcpHost, tcpPort, uid, token, "cmp-${System.currentTimeMillis()}", this)
        imClient = im
        im.connect()
    }

    fun disconnectTcp() {
        imClient?.disconnect()
        imClient = null
    }

    fun subscribeChannel(channelId: String, lastSeq: Long) {
        imClient?.subscribe(channelId, lastSeq)
    }

    fun unsubscribeChannel(channelId: String) {
        imClient?.unsubscribe(channelId)
    }

    suspend fun loadHistory(channelId: String, beforeSeq: Long, limit: Int = 50): HistoryLoadResult {
        val im = imClient ?: throw RuntimeException("IM not connected")
        val deferred = CompletableDeferred<HistoryLoadResult>()
        synchronized(historyLoadWaiters) {
            historyLoadWaiters[channelId] = deferred
        }
        im.loadHistory(channelId, beforeSeq, limit)
        return withTimeoutOrNull(10_000L) {
            deferred.await()
        } ?: run {
            synchronized(historyLoadWaiters) { historyLoadWaiters.remove(channelId) }
            throw RuntimeException("History load timeout for channel $channelId")
        }
    }

    // ── ImStateListener 实现 ──

    override fun onConnectionStateChanged(state: ImClient.State) {
        _connectionState.value = when (state) {
            ImClient.State.DISCONNECTED -> ConnectionState.DISCONNECTED
            ImClient.State.CONNECTING -> ConnectionState.CONNECTING
            ImClient.State.CONNECTED -> ConnectionState.CONNECTED
        }
    }

    override fun onMessageReceived(msg: Message) {
        // 将 TCP 推送的消息写入本地 DB
        try {
            localCache.insertMessage(msg)
            // 更新会话表的 lastMessage
            val previewText = Message.extractPreviewText(msg.body)
            localCache.updateLastMessage(msg.channelId, previewText, msg.timestamp, msg.serverSeq)
            localCache.incrementUnread(msg.channelId)
        } catch (e: Exception) {
            AppLog.w("UserContext", "Failed to persist incoming message to DB", e)
        }
        synchronized(messageListeners) {
            messageListeners.forEach { it(msg) }
        }
    }

    override fun onSendAck(clientMsgNo: String, messageId: String, serverSeq: Long) {
        AppLog.i("UserContext", "SENDACK: clientMsgNo=$clientMsgNo messageId=$messageId serverSeq=$serverSeq")
        synchronized(sendAckWaiters) {
            sendAckWaiters.remove(clientMsgNo)?.complete(SendResult(messageId, serverSeq))
        }
    }

    override fun onSendFailed(clientMsgNo: String) {
        AppLog.w("UserContext", "SEND FAILED: clientMsgNo=$clientMsgNo")
    }

    override fun onCmdReceived(cmdType: String, payload: String) {
        AppLog.i("UserContext", "CMD: cmdType=$cmdType payload=$payload")
        when (cmdType) {
            "read_sync" -> {
                try {
                    val json = Json.parseToJsonElement(payload).jsonObject
                    val channelId = json["channelId"]?.jsonPrimitive?.content ?: return
                    val readSeq = json["readSeq"]?.jsonPrimitive?.long ?: return
                    localCache.updateReadSeq(channelId, readSeq)
                    AppLog.i("UserContext", "read_sync: channelId=$channelId readSeq=$readSeq")
                } catch (e: Exception) {
                    AppLog.w("UserContext", "Failed to parse read_sync", e)
                }
            }
        }
    }

    override fun onPresenceReceived(uid: String, online: Boolean, lastSeenAt: Long) {
        val current = _onlineStatus.value.toMutableMap()
        current[uid] = online
        _onlineStatus.value = current
        AppLog.i("UserContext", "PRESENCE: uid=$uid online=$online")
    }

    override fun onTypingReceived(channelId: String, senderUid: String, action: Byte) {
        synchronized(typingListeners) {
            typingListeners.forEach { it(channelId, senderUid, action) }
        }
    }

    override fun onEditReceived(channelId: String, targetMessageId: String, newPayload: String, editedAt: Long) {
        // 更新本地 DB 中对应消息的 payload
        try {
            localCache.updateMessagePayload(targetMessageId, newPayload)
        } catch (e: Exception) {
            AppLog.w("UserContext", "Failed to update edited message in DB", e)
        }
        synchronized(editListeners) {
            editListeners.forEach { it(channelId, targetMessageId, newPayload, editedAt) }
        }
    }

    override fun onMemberMuted(channelId: String, memberUid: String, operatorUid: String, duration: Long) {
        AppLog.i("UserContext", "MEMBER_MUTED: channelId=$channelId memberUid=$memberUid duration=$duration")
        dispatchChannelEvent(channelId, "member_muted")
    }

    override fun onMemberUnmuted(channelId: String, memberUid: String, operatorUid: String) {
        AppLog.i("UserContext", "MEMBER_UNMUTED: channelId=$channelId memberUid=$memberUid")
        dispatchChannelEvent(channelId, "member_unmuted")
    }

    override fun onMemberRoleChanged(channelId: String, memberUid: String, operatorUid: String, oldRole: Byte, newRole: Byte) {
        AppLog.i("UserContext", "MEMBER_ROLE_CHANGED: channelId=$channelId memberUid=$memberUid oldRole=$oldRole newRole=$newRole")
        dispatchChannelEvent(channelId, "member_role_changed")
    }

    override fun onHistoryLoadEnd(channelId: String, beforeSeq: Long, hasMore: Boolean) {
        AppLog.i("UserContext", "HISTORY_LOAD_END: channelId=$channelId beforeSeq=$beforeSeq hasMore=$hasMore")
        synchronized(historyLoadWaiters) {
            historyLoadWaiters.remove(channelId)?.complete(HistoryLoadResult(hasMore))
        }
    }

    override fun onAuthFailed(code: Byte, reason: String) {
        AppLog.w("UserContext", "TCP auth failed, forcing logout: code=$code reason=$reason")
        onForceLogout?.invoke()
    }

    // ── 消息发送 ──

    private val sendAckWaiters = mutableMapOf<String, CompletableDeferred<SendResult>>()
    private val historyLoadWaiters = mutableMapOf<String, CompletableDeferred<HistoryLoadResult>>()

    /** 发送文本消息 */
    suspend fun sendMessage(channelId: String, channelType: ChannelType, text: String): SendResult {
        return enqueueAndWaitAck(channelId, channelType, TextBody(text, emptyList()))
    }

    /** 发送任意类型的消息 */
    suspend fun sendMessage(channelId: String, channelType: ChannelType, body: MessageBody): SendResult {
        return enqueueAndWaitAck(channelId, channelType, body)
    }

    private suspend fun enqueueAndWaitAck(
        channelId: String,
        channelType: ChannelType,
        body: MessageBody,
    ): SendResult {
        val im = imClient ?: throw RuntimeException("IM not connected")
        val deferred = CompletableDeferred<SendResult>()

        val clientMsgNo = im.enqueueMessage(channelId, channelType, body)

        synchronized(sendAckWaiters) {
            sendAckWaiters[clientMsgNo] = deferred
        }

        return withTimeoutOrNull(10_000L) {
            deferred.await()
        } ?: run {
            synchronized(sendAckWaiters) { sendAckWaiters.remove(clientMsgNo) }
            throw RuntimeException("Send timeout for $clientMsgNo")
        }
    }

    // ── 输入状态发送 ──

    fun sendTyping(channelId: String, channelType: ChannelType) {
        imClient?.sendTyping(channelId, channelType)
    }

    // ── 设备管理 ──

    suspend fun getDevices(): List<DeviceDto> {
        return httpClient.get("$baseUrl/api/v1/devices") {
            header("Authorization", authHeader())
        }.body<List<DeviceDto>>()
    }

    suspend fun kickDevice(deviceId: String) {
        httpClient.delete("$baseUrl/api/v1/devices/$deviceId") {
            header("Authorization", authHeader())
        }
    }

    // ── Session 持久化 ──

    fun persistSession() {
        val userJson = kotlinx.serialization.json.Json.encodeToString(UserDto.serializer(), user)
        tokenStorage.save(token, uid, userJson)
        AppLog.i("UserContext", "Session persisted: uid=$uid")
    }

    // ── 销毁 ──

    fun destroy() {
        disconnectTcp()
        tokenStorage.clear()
        scope.cancel()
        messageListeners.clear()
        typingListeners.clear()
        editListeners.clear()
        channelEventListeners.clear()
        sendAckWaiters.clear()
        historyLoadWaiters.clear()
        try {
            appDatabase.close()
        } catch (_: Exception) {
        }
    }
}
