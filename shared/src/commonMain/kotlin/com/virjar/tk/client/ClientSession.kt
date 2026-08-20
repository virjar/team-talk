package com.virjar.tk.client

import com.virjar.tk.Outcome
import com.virjar.tk.log.TkLogger
import com.virjar.tk.outcome
import com.virjar.tk.repository.*
import com.virjar.tk.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.AppLogOwner
import com.virjar.tk.util.LogBuffer
import com.virjar.tk.util.PlatformOnlyTkLogger
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.rpc.gen.AuthRpcProxy

/** Why an authenticated owner crossed its irreversible business-resource boundary. */
enum class SessionEndReason {
    USER_LOGOUT,
    AUTH_REVOKED,
    PROCESS_REPLACED,
    PROTOCOL_UPGRADE,
    SHUTDOWN,
}

enum class SessionLifecyclePhase { ACTIVE, QUIESCED, CLOSED }

/** Permanently retired on quiesce and held across the EventLoop's actual channel write. */
internal class SessionOutboundLease : WireSendAdmission {
    private val lock = Any()
    val ackOwner: Any = Any()
    @Volatile
    private var active = true

    override fun isActive(): Boolean = active
    /** Holds retirement out of the actual EventLoop write critical section. */
    override fun use(block: () -> Boolean): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        block()
    }

    fun retire() = synchronized(lock) { active = false }
}

/** Small linearization point shared by repository accessors and the gated RPC adapter. */
internal class SessionLifecycleGate {
    private val lock = Any()

    @Volatile
    var phase: SessionLifecyclePhase = SessionLifecyclePhase.ACTIVE
        private set

    @Volatile
    var endReason: SessionEndReason? = null
        private set

    fun beginQuiesce(
        reason: SessionEndReason,
        retireOutbound: () -> Unit = {},
    ): Boolean = synchronized(lock) {
        if (phase != SessionLifecyclePhase.ACTIVE) return@synchronized false
        // Retire the EventLoop-visible wire lease before publishing QUIESCED. There is no state in
        // which observers see the logical boundary crossed while an old write lease remains live.
        retireOutbound()
        endReason = reason
        phase = SessionLifecyclePhase.QUIESCED
        true
    }

    fun markClosed(): Boolean = synchronized(lock) {
        if (phase == SessionLifecyclePhase.CLOSED) return@synchronized false
        check(phase == SessionLifecyclePhase.QUIESCED) { "ClientSession must quiesce before close" }
        phase = SessionLifecyclePhase.CLOSED
        true
    }

    fun requireBusinessActive() = synchronized(lock) {
        check(phase == SessionLifecyclePhase.ACTIVE) {
            "ClientSession no longer accepts business work (${endReason ?: SessionEndReason.SHUTDOWN})"
        }
    }

    fun isBusinessActive(): Boolean = phase == SessionLifecyclePhase.ACTIVE

    fun <T> whileBusinessActive(block: () -> T): T = synchronized(lock) {
        requireBusinessActive()
        block()
    }
}

/**
 * The raw logout RPC is sealed inside this one-shot capability. It can only be minted by a
 * USER_LOGOUT-quiesced [ClientSession], and completion always closes the raw RPC owner exactly once.
 */
internal class UserLogoutRetirementCapability(
    private val logoutRpc: suspend () -> Outcome<Unit>,
    private val closeSession: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var completed = false

    suspend fun complete(disconnectTransport: () -> Boolean): Outcome<Unit> {
        synchronized(lock) {
            check(!completed) { "User logout retirement already completed" }
            completed = true
        }
        return try {
            logoutRpc()
        } finally {
            val disconnect = try {
                disconnectTransport()
            } catch (_: Throwable) {
                true
            }
            closeSession(disconnect)
        }
    }
}

/**
 * Every business repository uses this view rather than the raw RPC owner. It fences both request
 * admission and response publication while leaving the raw client available for the logout RPC.
 */
internal class SessionBusinessRpcInvoker(
    private val delegate: RpcInvoker,
    private val lifecycle: SessionLifecycleGate,
    private val outboundLease: SessionOutboundLease,
) : RpcInvoker {
    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
        lifecycle.requireBusinessActive()
        val response = if (delegate is RpcClient) {
            delegate.invokeWhileActive(service, methodId, payload, outboundLease)
        } else {
            delegate.invoke(service, methodId, payload)
        }
        lifecycle.requireBusinessActive()
        return response
    }
}

/**
 * 已认证会话的共享依赖容器。
 * 封装认证后创建的所有组件，统一生命周期管理。
 *
 * [userSession] 是用户层状态（uid/refreshToken），独立于 [imClient] 的 TCP 连接。
 */
class ClientSession internal constructor(
    val deviceId: String,
    /** Immutable authenticated identity for every cache, repository and outbound lease below. */
    val ownerUid: String,
    internal val imClient: ImClient,
    private val ownedUserSession: UserSession,
    private val ownedLocalCache: LocalCache,
    private val ownedRpcClient: RpcClient,
    private val ownedEventProcessor: EventProcessor,
    /** 日志上传器（无头场景禁用为 null，close 判空）。 */
    private val ownedHttpLogUploader: HttpLogUploader?,
    private val ownedLogOwner: AppLogOwner?,
    private val outboundLease: SessionOutboundLease,
    private val transportOwnerGeneration: Long,
    private val draftRecoveryScope: CoroutineScope,
    private val lifecycle: SessionLifecycleGate,
    private val ownedConversationRepo: ConversationRepository,
    private val ownedContactRepo: ContactRepository,
    private val ownedMessageRepo: MessageRepository,
    private val ownedChatRepo: ChatRepository,
    private val ownedDeviceRepo: DeviceRepository,
    private val ownedUserRepo: UserRepository,
    private val ownedOrganizationRepo: OrganizationRepository,
    private val ownedGroupFileRepo: GroupFileRepository,
    private val ownedDocumentRepo: DocumentRepository,
    private val ownedGroupBotManagementRepo: GroupBotManagementRepository,
    private val ownedMessageSender: MessageSender,
    /** 发送队列（断线排队重连补发，状态机回写 localCache） */
    private val ownedSendQueue: SendQueue,
) {
    private val resourceLifecycleLock = Any()
    private val retirementLock = Any()
    private var retirementIssued = false
    private val rawLogoutRpc = AuthRpcProxy(ownedRpcClient)

    val lifecyclePhase: SessionLifecyclePhase get() = lifecycle.phase
    val endReason: SessionEndReason? get() = lifecycle.endReason
    val isBusinessActive: Boolean get() = lifecycle.phase == SessionLifecyclePhase.ACTIVE
    val connectionState: StateFlow<ConnectionState> get() = imClient.state
    val userSession: UserSessionView get() = ownedUserSession

    /** Session-gated dynamic HTTP credentials for platform resources created by the shell. */
    fun httpCredentialsSnapshot(): SessionHttpCredentials = lifecycle.whileBusinessActive {
        ownedUserSession.httpCredentialsSnapshot()
    }

    /**
     * A platform resource may retain this logger without ever consulting the process-global owner
     * slot. Retirement closes its account buffer; the platform resource must still gate its own
     * asynchronous lifetime.
     */
    fun diagnosticLogger(name: String): TkLogger = lifecycle.whileBusinessActive {
        ownedLogOwner?.logger(name) ?: PlatformOnlyTkLogger(name)
    }

    val localCache: LocalCache get() = businessResource(ownedLocalCache)
    val eventProcessor: EventProcessor get() = businessResource(ownedEventProcessor)
    val httpLogUploader: HttpLogUploader? get() = businessResource(ownedHttpLogUploader)
    val conversationRepo: ConversationRepository get() = businessResource(ownedConversationRepo)
    val contactRepo: ContactRepository get() = businessResource(ownedContactRepo)
    val messageRepo: MessageRepository get() = businessResource(ownedMessageRepo)
    val chatRepo: ChatRepository get() = businessResource(ownedChatRepo)
    val deviceRepo: DeviceRepository get() = businessResource(ownedDeviceRepo)
    val userRepo: UserRepository get() = businessResource(ownedUserRepo)
    val organizationRepo: OrganizationRepository get() = businessResource(ownedOrganizationRepo)
    val groupFileRepo: GroupFileRepository get() = businessResource(ownedGroupFileRepo)
    val documentRepo: DocumentRepository get() = businessResource(ownedDocumentRepo)
    val groupBotManagementRepo: GroupBotManagementRepository get() = businessResource(ownedGroupBotManagementRepo)
    val messageSender: MessageSender get() = businessResource(ownedMessageSender)
    val sendQueue: SendQueue get() = businessResource(ownedSendQueue)

    private fun <T> businessResource(resource: T): T {
        lifecycle.requireBusinessActive()
        return resource
    }

    fun sendTransient(message: Message) {
        lifecycle.whileBusinessActive {
            check(
                imClient.sendSessionOwned(
                    expectedOwnerGeneration = transportOwnerGeneration,
                    sessionLease = outboundLease,
                    proto = message,
                ),
            ) { "Session transport is not available" }
        }
    }

    /**
     * First lifecycle phase: reject new business, terminally retire outbound/event/cache/HTTP
     * owners, and preserve only the raw RPC owner sealed inside user-logout retirement.
     */
    fun quiesce(reason: SessionEndReason) {
        synchronized(resourceLifecycleLock) {
            val beganQuiesce = lifecycle.beginQuiesce(reason) {
                    outboundLease.retire()
                    ownedEventProcessor.retireSyncWireAdmission()
                }
            if (!beganQuiesce) return
            val failures = releaseBestEffort(
                stage = "quiesce",
                "pending ACKs" to { imClient.retireSessionOutbound(outboundLease.ackOwner) },
                "draft recovery" to { draftRecoveryScope.cancel() },
                "send queue" to ownedSendQueue::close,
                "event processor" to ownedEventProcessor::stop,
                // The retirement RPC does not use LocalCache. Closing here fences repositories and
                // pagers captured by a retiring UI while preserving only the sealed raw RPC owner.
                "local cache" to ownedLocalCache::close,
                "group-bot HTTP" to ownedGroupBotManagementRepo::close,
                "log HTTP" to { ownedHttpLogUploader?.stop() },
                "AppLog owner" to { ownedLogOwner?.let(AppLog::release) },
            )
            failures.firstOrNull { it.second is SessionBoundaryReentrantCloseException }
                ?.second
                ?.let { throw it }
        }
    }

    /** Synchronous UI boundary; does not expose either the raw RPC owner or its capability. */
    fun beginUserLogoutRetirement() {
        quiesce(SessionEndReason.USER_LOGOUT)
    }

    /** The app's sole logout completion entry; the one-shot capability remains inside session. */
    suspend fun completeUserLogoutRetirement(disconnectTransport: () -> Boolean): Outcome<Unit> {
        beginUserLogoutRetirement()
        val capability = synchronized(retirementLock) {
            check(lifecycle.phase == SessionLifecyclePhase.QUIESCED) {
                "User logout retirement requires a quiesced session"
            }
            check(lifecycle.endReason == SessionEndReason.USER_LOGOUT) {
                "Raw logout RPC is only valid for USER_LOGOUT"
            }
            check(!retirementIssued) { "User logout retirement already issued" }
            retirementIssued = true
            UserLogoutRetirementCapability(
                logoutRpc = { outcome { rawLogoutRpc.logout() } },
                closeSession = { disconnect ->
                    close(SessionEndReason.USER_LOGOUT, disconnectTransport = disconnect)
                },
            )
        }
        return capability.complete(disconnectTransport)
    }

    /**
     * Release session-owned SDK components. During an immediate logout -> login transition the
     * retiring and new sessions briefly share [imClient]; the retiring owner must then release its
     * jobs without disconnecting the new transport.
     */
    fun close(
        reason: SessionEndReason = SessionEndReason.SHUTDOWN,
        disconnectTransport: Boolean = true,
    ) {
        synchronized(resourceLifecycleLock) {
            var boundaryFailure: Throwable? = null
            try {
                quiesce(reason)
            } catch (failure: SessionBoundaryReentrantCloseException) {
                // Finish raw RPC/transport retirement before unwinding the admitted callback. A
                // successful-looking reentrant close would let that callback publish after close.
                boundaryFailure = failure
            }
            val shouldCloseResources = lifecycle.markClosed()
            if (shouldCloseResources) {
                val closeFailures = releaseBestEffort(
                    stage = "close",
                    "raw RPC" to ownedRpcClient::stop,
                    "transport" to {
                        if (disconnectTransport) imClient.disconnectIfOwned(transportOwnerGeneration)
                    },
                )
                if (boundaryFailure == null) {
                    boundaryFailure = closeFailures
                        .firstOrNull { it.second is SessionBoundaryReentrantCloseException }
                        ?.second
                }
            }
            boundaryFailure?.let { throw it }
        }
    }

    private fun releaseBestEffort(
        stage: String,
        vararg releases: Pair<String, () -> Unit>,
    ): List<Pair<String, Throwable>> {
        val failures = releaseAllSessionResources(*releases)
        if (failures.isNotEmpty()) {
            val summary = failures.joinToString { (owner, failure) ->
                "$owner=${failure::class.simpleName}:${failure.message}"
            }
            ownedLogOwner?.recordCleanupFault(
                "ClientSession",
                "$stage released with ${failures.size} failure(s): $summary",
                failures.first().second,
            )
        }
        return failures
    }

    fun recordRetirementFailure(stage: String, failure: Throwable) {
        ownedLogOwner?.recordCleanupFault("ClientSession", stage, failure)
    }
}

/** Execute every retirement action in declaration order even when arbitrary close hooks fail. */
internal fun releaseAllSessionResources(
    vararg releases: Pair<String, () -> Unit>,
): List<Pair<String, Throwable>> {
    val failures = mutableListOf<Pair<String, Throwable>>()
    releases.forEach { (owner, release) ->
        try {
            release()
        } catch (failure: Throwable) {
            failures += owner to failure
        }
    }
    return failures
}

/** Transactional owner stack for resources created before a ClientSession can be returned. */
internal class SessionConstructionRollback {
    private val releases = mutableListOf<Pair<String, () -> Unit>>()
    private var handedOff = false

    fun own(owner: String, release: () -> Unit) {
        check(!handedOff) { "Session construction ownership already handed off" }
        releases += owner to release
    }

    fun handOff() {
        check(!handedOff) { "Session construction ownership already handed off" }
        handedOff = true
        releases.clear()
    }

    fun rollback(): List<Pair<String, Throwable>> {
        if (handedOff) return emptyList()
        handedOff = true
        val failures = releaseAllSessionResources(*releases.asReversed().toTypedArray())
        releases.clear()
        return failures
    }
}

/** Headless/disabled sessions must not replace a graphical client's process-global log owner. */
internal fun installAppLogOwnershipIfEnabled(
    enabled: Boolean,
    traceBuffer: LogBuffer,
    faultBuffer: LogBuffer,
    faultHandler: (() -> Unit)?,
    crashDumper: CrashDumper? = null,
    previousOwnerSink: ((AppLogOwner?) -> Unit)? = null,
): AppLogOwner? {
    if (!enabled) return null
    val owner = AppLogOwner(
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        onFault = faultHandler,
        crashSink = crashDumper?.let { dumper -> { _, content -> dumper.flushPending(content) } },
    )
    val previous = AppLog.installReturningPrevious(owner)
    previousOwnerSink?.invoke(previous)
    return owner
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
    durableMessageSink: ((Long, Message) -> Unit)? = null,
): ClientSession {
    val sessionOwnerUid = userSession.uid
    require(sessionOwnerUid.isNotBlank()) { "Cannot create a session without an authenticated uid" }
    val construction = SessionConstructionRollback()
    return try {
    val cache = createCache(sessionOwnerUid)
    construction.own("local cache", cache::close)
    val rpcClient = RpcClient(imClient)
    construction.own("raw RPC", rpcClient::stop)
    val outboundLease = SessionOutboundLease()
    construction.own("outbound admission", outboundLease::retire)
    val sessionTransportOwnerGeneration = imClient.currentTransportOwnerGeneration
    check(sessionTransportOwnerGeneration > 0L) { "Cannot create session without a transport owner" }
    val lifecycle = SessionLifecycleGate()
    val businessRpcClient = SessionBusinessRpcInvoker(rpcClient, lifecycle, outboundLease)
    val conversationRepo = ConversationRepository(businessRpcClient, cache)
    val ep = EventProcessor(
        imClient,
        cache,
        onConversationsDirty = { conversationRepo.listConversations().getOrThrow() },
        durableMessageSink = durableMessageSink,
        ownerUid = sessionOwnerUid,
    )
    construction.own("event processor", ep::stop)
    val messageSender = MessageSender { msg ->
        lifecycle.requireBusinessActive()
        val ack = imClient.sendAndWaitAckIfOwned(
            message = msg,
            expectedOwnerGeneration = sessionTransportOwnerGeneration,
            sessionLease = outboundLease,
        )
        lifecycle.requireBusinessActive()
        ack
    }

    // 发送队列：断线排队 → AUTHENTICATED 唤醒补发；状态机回写本地缓存驱动 UI
    val sendQueue = SendQueue(
        connectionState = imClient.state,
        sender = messageSender,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        onQueued = { msg -> cache.updateMessageStatus(msg.chatId, msg.clientMsgId, Message.SEND_STATUS_QUEUED) },
        onSent = { msg, ack -> cache.updateMessage(msg.chatId, msg.clientMsgId, ack.serverSeq) },
        onFailed = { msg, _ -> cache.updateMessageStatus(msg.chatId, msg.clientMsgId, Message.SEND_STATUS_FAILED) },
    )
    construction.own("send queue", sendQueue::close)

    // 日志缓冲区（分级：trace + fault）
    val traceBuffer = LogBuffer(capacity = 2000)
    val faultBuffer = LogBuffer(capacity = 500)

    val draftRecoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    construction.own("draft recovery", draftRecoveryScope::cancel)

    // HTTP 日志上传器 + crash 持久化
    val serverUrl = defaultServerConfig().serverUrl
    val dataDir = platformDataDir()
    val logOwnerUid = sessionOwnerUid
    val crashDumper = if (logUploadEnabled) {
        check(logOwnerUid.isNotBlank()) { "Cannot create HTTP log uploader before authentication" }
        CrashDumper(dataDir, serverUrl, logOwnerUid)
    } else null
    val uploader: HttpLogUploader? = crashDumper?.let { ownedCrashDumper ->
        HttpLogUploader(
            traceBuffer = traceBuffer,
            faultBuffer = faultBuffer,
            serverUrl = serverUrl,
            ownerUid = logOwnerUid,
            credentialsProvider = userSession::httpCredentialsSnapshot,
            crashDumper = ownedCrashDumper,
        )
    }
    val faultHandler: (() -> Unit)? = uploader?.let { activeUploader ->
        { activeUploader.trigger() }
    }
    var previousLogOwner: AppLogOwner? = null
    val logOwner = installAppLogOwnershipIfEnabled(
        enabled = uploader != null,
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        faultHandler = faultHandler,
        crashDumper = crashDumper,
        previousOwnerSink = { previousLogOwner = it },
    )
    logOwner?.let { installedOwner ->
        construction.own("AppLog owner") {
            AppLog.restoreAfterFailedInstall(installedOwner, previousLogOwner)
        }
    }
    uploader?.let { construction.own("log HTTP", it::stop) }
    // Install the complete owner snapshot before workers can publish or trigger a fault upload.
    uploader?.start()

    // A headless/disabled session still gets a fixed platform logger, but never installs it into
    // the process-global AppLog slot and therefore cannot borrow another account's buffers.
    val sessionLogOwner = logOwner ?: AppLogOwner(
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        onFault = null,
        crashSink = null,
    )
    rpcClient.bindLogger(sessionLogOwner.logger("RpcClient"))
    ep.bindLogger(sessionLogOwner.logger("EventProcessor"))
    rpcClient.start()
    // EventProcessor 先订阅入站事件，再安装持久 cursor/批次投影 binding 并发起显式分页同步。
    ep.start()
    // outbox 在账号本地库中持久化；初始认证与每次重连认证完成后均重试。
    // StateFlow 会立即下发当前 AUTHENTICATED，因此同时覆盖进程冷启恢复。
    imClient.state
        .onEach { state ->
            if (state == ConnectionState.AUTHENTICATED) conversationRepo.retryPendingDrafts()
        }
        .launchIn(draftRecoveryScope)

    val groupBotManagementRepo = HttpGroupBotManagementRepository(
        serverUrl = serverUrl,
        ownerUid = sessionOwnerUid,
        credentialsProvider = userSession::httpCredentialsSnapshot,
    )
    construction.own("group-bot HTTP", groupBotManagementRepo::close)

    val result = ClientSession(
        deviceId = deviceId,
        ownerUid = sessionOwnerUid,
        imClient = imClient,
        ownedUserSession = userSession,
        ownedLocalCache = cache,
        ownedRpcClient = rpcClient,
        ownedEventProcessor = ep,
        ownedHttpLogUploader = uploader,
        ownedLogOwner = sessionLogOwner,
        outboundLease = outboundLease,
        transportOwnerGeneration = sessionTransportOwnerGeneration,
        draftRecoveryScope = draftRecoveryScope,
        lifecycle = lifecycle,
        ownedConversationRepo = conversationRepo,
        ownedContactRepo = ContactRepository(businessRpcClient, cache),
        ownedMessageRepo = MessageRepository(businessRpcClient, cache, messageSender),
        ownedChatRepo = ChatRepository(businessRpcClient, cache),
        ownedDeviceRepo = DeviceRepository(businessRpcClient),
        ownedUserRepo = UserRepository(businessRpcClient, cache),
        ownedOrganizationRepo = OrganizationRepository(businessRpcClient),
        ownedGroupFileRepo = GroupFileRepository(businessRpcClient),
        ownedDocumentRepo = DocumentRepository(businessRpcClient),
        ownedGroupBotManagementRepo = groupBotManagementRepo,
        ownedMessageSender = messageSender,
        ownedSendQueue = sendQueue,
    )
    construction.handOff()
    result
    } catch (failure: Throwable) {
        construction.rollback().forEach { (_, cleanupFailure) ->
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}
