package com.virjar.tk.client

import com.virjar.tk.repository.*
import com.virjar.tk.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.LogBuffer
/**
 * 已认证会话的共享依赖容器。
 * 封装认证后创建的所有组件，统一生命周期管理。
 *
 * [userSession] 是用户层状态（uid/refreshToken），独立于 [imClient] 的 TCP 连接。
 */
class ClientSession(
    val deviceId: String,
    val imClient: ImClient,
    val userSession: UserSession,
    val localCache: LocalCache,
    val rpcClient: RpcClient,
    val eventProcessor: EventProcessor,
    /** 日志上传器（无头场景禁用为 null，close 判空）。 */
    val httpLogUploader: HttpLogUploader?,
    private val ownedTraceBuffer: LogBuffer,
    private val ownedFaultBuffer: LogBuffer,
    private val ownedFaultHandler: (() -> Unit)?,
    private val transportOwnerGeneration: Long,
    private val draftRecoveryScope: CoroutineScope,
    val conversationRepo: ConversationRepository,
    val contactRepo: ContactRepository,
    val messageRepo: MessageRepository,
    val chatRepo: ChatRepository,
    val deviceRepo: DeviceRepository,
    val userRepo: UserRepository,
    val organizationRepo: OrganizationRepository,
    val groupFileRepo: GroupFileRepository,
    val documentRepo: DocumentRepository,
    /** 发送队列（断线排队重连补发，状态机回写 localCache） */
    val sendQueue: SendQueue,
) {
    private val closeLock = Any()
    private var resourcesClosed = false

    /**
     * Release session-owned SDK components. During an immediate logout -> login transition the
     * retiring and new sessions briefly share [imClient]; the retiring owner must then release its
     * jobs without disconnecting the new transport.
     */
    fun close(disconnectTransport: Boolean = true) {
        val shouldCloseResources = synchronized(closeLock) {
            if (resourcesClosed) false else {
                resourcesClosed = true
                true
            }
        }
        if (shouldCloseResources) {
            draftRecoveryScope.cancel()
            sendQueue.close()
            httpLogUploader?.stop()
            rpcClient.stop()
            eventProcessor.stop()
            localCache.close()
            releaseAppLogOwnership(ownedTraceBuffer, ownedFaultBuffer, ownedFaultHandler)
        }
        if (disconnectTransport) imClient.disconnectIfOwned(transportOwnerGeneration)
    }
}

/** Install the global logging hooks atomically. */
internal fun installAppLogOwnership(
    traceBuffer: LogBuffer,
    faultBuffer: LogBuffer,
    faultHandler: (() -> Unit)?,
) = synchronized(AppLog) {
    AppLog.traceBuffer = traceBuffer
    AppLog.faultBuffer = faultBuffer
    AppLog.onFault = faultHandler
}

/** A retired session may only clear hooks that it still owns. */
internal fun releaseAppLogOwnership(
    traceBuffer: LogBuffer,
    faultBuffer: LogBuffer,
    faultHandler: (() -> Unit)?,
) = synchronized(AppLog) {
    if (AppLog.traceBuffer === traceBuffer) AppLog.traceBuffer = null
    if (AppLog.faultBuffer === faultBuffer) AppLog.faultBuffer = null
    if (AppLog.onFault === faultHandler) AppLog.onFault = null
}

/**
 * 创建完整会话。在认证成功后调用。
 * @param createCache 平台提供的 LocalCache 工厂 (uid) -> LocalCache
 * @param deviceId 设备 ID，用于日志上传标识
 */
fun createSession(
    imClient: ImClient,
    userSession: UserSession,
    createCache: (String) -> LocalCache,
    deviceId: String,
    logUploadEnabled: Boolean = true,  // 无头场景（serverUrl 未知）传 false 免噪音
    durableMessageSink: (suspend (Long, Message) -> Unit)? = null,
): ClientSession {
    val cache = createCache(userSession.uid)
    val rpcClient = RpcClient(imClient)
    val conversationRepo = ConversationRepository(rpcClient, cache)
    val ep = EventProcessor(
        imClient,
        cache,
        onConversationsDirty = { conversationRepo.listConversations().getOrThrow() },
        durableMessageSink = durableMessageSink,
    )
    val messageSender = MessageSender { msg -> imClient.sendAndWaitAck(msg) }

    // 发送队列：断线排队 → AUTHENTICATED 唤醒补发；状态机回写本地缓存驱动 UI
    val sendQueue = SendQueue(
        connectionState = imClient.state,
        sender = messageSender,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        onQueued = { msg -> cache.updateMessageStatus(msg.chatId, msg.clientMsgId, Message.SEND_STATUS_QUEUED) },
        onSent = { msg, ack -> cache.updateMessage(msg.chatId, msg.clientMsgId, ack.serverSeq) },
        onFailed = { msg, _ -> cache.updateMessageStatus(msg.chatId, msg.clientMsgId, Message.SEND_STATUS_FAILED) },
    )

    // 日志缓冲区（分级：trace + fault）
    val traceBuffer = LogBuffer(capacity = 2000)
    val faultBuffer = LogBuffer(capacity = 500)

    rpcClient.start()
    // EventProcessor 先订阅入站事件，再安装持久 cursor/批次投影 binding 并发起显式分页同步。
    ep.start()

    // outbox 在账号本地库中持久化；初始认证与每次重连认证完成后均重试。
    // StateFlow 会立即下发当前 AUTHENTICATED，因此同时覆盖进程冷启恢复。
    val draftRecoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    imClient.state
        .onEach { state ->
            if (state == ConnectionState.AUTHENTICATED) conversationRepo.retryPendingDrafts()
        }
        .launchIn(draftRecoveryScope)

    // HTTP 日志上传器 + crash 持久化
    val serverUrl = defaultServerConfig().serverUrl
    val dataDir = platformDataDir()
    val uploader: HttpLogUploader? = if (logUploadEnabled) {
        val logOwnerUid = userSession.uid
        check(logOwnerUid.isNotBlank()) { "Cannot create HTTP log uploader before authentication" }
        HttpLogUploader(
            traceBuffer = traceBuffer,
            faultBuffer = faultBuffer,
            serverUrl = serverUrl,
            ownerUid = logOwnerUid,
            credentialsProvider = userSession::httpCredentialsSnapshot,
            crashDumper = CrashDumper(dataDir),
        ).also {
            it.start()
        }
    } else null
    val faultHandler: (() -> Unit)? = uploader?.let { activeUploader ->
        { activeUploader.trigger() }
    }
    installAppLogOwnership(traceBuffer, faultBuffer, faultHandler)

    return ClientSession(
        deviceId = deviceId,
        imClient = imClient,
        userSession = userSession,
        localCache = cache,
        rpcClient = rpcClient,
        eventProcessor = ep,
        httpLogUploader = uploader,
        ownedTraceBuffer = traceBuffer,
        ownedFaultBuffer = faultBuffer,
        ownedFaultHandler = faultHandler,
        transportOwnerGeneration = imClient.currentTransportOwnerGeneration,
        draftRecoveryScope = draftRecoveryScope,
        conversationRepo = conversationRepo,
        contactRepo = ContactRepository(rpcClient, cache),
        messageRepo = MessageRepository(rpcClient, cache, messageSender),
        chatRepo = ChatRepository(rpcClient, cache),
        deviceRepo = DeviceRepository(rpcClient),
        userRepo = UserRepository(rpcClient, cache),
        organizationRepo = OrganizationRepository(rpcClient),
        groupFileRepo = GroupFileRepository(rpcClient),
        documentRepo = DocumentRepository(rpcClient),
        sendQueue = sendQueue,
    )
}
