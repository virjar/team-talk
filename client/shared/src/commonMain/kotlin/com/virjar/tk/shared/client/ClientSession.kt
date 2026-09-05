package com.virjar.tk.shared.client

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.log.TkLogger
import com.virjar.tk.shared.outcome
import com.virjar.tk.shared.repository.*
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import com.virjar.tk.shared.log.AppLog
import com.virjar.tk.shared.log.AppLogOwner
import com.virjar.tk.shared.log.LogBuffer
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.rpc.gen.AuthRpcProxy
import com.virjar.tk.protocol.rpc.gen.ContactRpcProxy
import com.virjar.tk.protocol.rpc.gen.SyncRpcProxy
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.TelemetryLogLevel
import java.io.File

/**
 * 已认证会话的共享依赖容器。
 * 封装认证后创建的所有组件，统一生命周期管理。
 *
 * [userSession] 是用户层状态（uid/refreshToken），独立于 [imClient] 的 TCP 连接。
 */
class ClientSession internal constructor(
    val deviceId: String,
    val deploymentIdentity: DeploymentIdentity,
    /** 下方每个缓存、仓库与出站租约的不可变已认证身份。 */
    val ownerUid: String,
    /** 这份完整资源图的不可变权威 dataset。 */
    val datasetId: String,
    internal val imClient: ImClient,
    private val ownedUserSession: UserSession,
    private val ownedLocalCache: LocalCache,
    private val ownedRpcClient: RpcClient,
    private val ownedEventProcessor: EventProcessor,
    private val ownedFriendPresenceRepository: FriendPresenceRepository,
    /** 对于显式的无头/禁上传会话，结构化遥测被禁用。 */
    private val ownedTelemetryUploader: ClientTelemetryUploader?,
    private val ownedTelemetryRecorder: ClientTelemetryRecorder?,
    private val ownedLogOwner: AppLogOwner?,
    private val outboundLease: SessionOutboundLease,
    private val transportOwnerGeneration: Long,
    private val localMirrorRecoveryScope: CoroutineScope,
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
    private val ownedHttpAuthExpiredRouter: SessionHttpAuthExpiredRouter,
    private val ownedGroupBotManagementRepo: GroupBotManagementRepository,
    /** 发送队列（断线排队重连补发，状态机回写 localCache） */
    private val ownedSendQueue: SendQueue,
    /** UI 发起的 SQLite 变更：精确会话准入、单一写者与终态排空。 */
    private val ownedLocalMutations: SessionLocalMutationQueue,
    /** 面向 UI 投影的 ACK 后提示，其恢复出的秘密结果不会被其他方式缓存。 */
    private val ownedInviteLinkRecoveryCompletions: SharedFlow<String>,
    /** 在 UI ownership 之前或期间恢复的联系人决策的、脱敏的 ACK 后提示。 */
    private val ownedContactDecisionRecoveryCompletions: SharedFlow<RecoveredContactDecision>,
    /** 群文件命令在其页面可能已经打开时被重放的终态提示。 */
    private val ownedGroupFileRecoveryCompletions: SharedFlow<GroupFileCommandCompletion>,
    /** 由会话恢复完成的文档移动/重命名命令的终态提示。 */
    private val ownedDocumentMoveRecoveryCompletions: SharedFlow<DocumentMoveCommandCompletion>,
) {
    private val resourceLifecycle = ClientSessionTerminalLifecycle()
    private val retirementLock = Any()
    private var retirementIssued = false
    private val rawLogoutRpc = AuthRpcProxy(ownedRpcClient)

    val lifecyclePhase: SessionLifecyclePhase get() = lifecycle.phase
    val endReason: SessionEndReason? get() = lifecycle.endReason
    val isBusinessActive: Boolean get() = lifecycle.phase == SessionLifecyclePhase.ACTIVE
    val connectionState: StateFlow<ConnectionState> get() = imClient.state
    val protocolCompatibility: StateFlow<ProtocolCompatibility?> get() = imClient.protocolCompatibility
    val userSession: UserSessionView get() = ownedUserSession

    /** 供 shell 创建的平台资源使用的、会话门禁的动态 HTTP 凭据。 */
    fun httpCredentialsSnapshot(): SessionHttpCredentials = lifecycle.whileBusinessActive {
        ownedUserSession.httpCredentialsSnapshot()
    }

    /** 仅为当前会话把当前应用 owner 绑定到精确 bearer 的 HTTP 401 终态。 */
    fun bindHttpAuthExpiredHandler(
        handler: (rejectedAccessToken: String) -> Unit,
    ): SessionHttpAuthExpiredBinding =
        lifecycle.whileBusinessActive { ownedHttpAuthExpiredRouter.bind(handler) }

    /**
     * 平台资源可以保留此 logger 而无需访问进程级 owner 槽。退役会关闭其账号缓冲区；平台资源仍必须
     * 自己门禁其异步生命周期。
     */
    fun diagnosticLogger(name: String): TkLogger = lifecycle.whileBusinessActive {
        ownedLogOwner?.logger(name) ?: PlatformOnlyTkLogger(name)
    }

    val localCache: LocalCache get() = businessResource(ownedLocalCache)
    val eventProcessor: EventProcessor get() = businessResource(ownedEventProcessor)
    /** 会话本地权威的好友在线状态；断开连接会发布空投影。 */
    val friendPresenceByUid: StateFlow<Map<String, FriendPresence>>
        get() = businessResource(ownedFriendPresenceRepository.presenceByUid)
    val telemetryRecorder: ClientTelemetryRecorder? get() = businessResource(ownedTelemetryRecorder)
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
    val sendQueue: SendQueue get() = businessResource(ownedSendQueue)
    val outgoingQueueSnapshots: kotlinx.coroutines.flow.StateFlow<OutgoingQueueSnapshot>
        get() = businessResource(ownedSendQueue.queueSnapshots)
    val localMutations: SessionLocalMutationWriter get() = businessResource(ownedLocalMutations)
    val inviteLinkRecoveryCompletions: SharedFlow<String>
        get() = businessResource(ownedInviteLinkRecoveryCompletions)
    val contactDecisionRecoveryCompletions: SharedFlow<RecoveredContactDecision>
        get() = businessResource(ownedContactDecisionRecoveryCompletions)
    val groupFileRecoveryCompletions: SharedFlow<GroupFileCommandCompletion>
        get() = businessResource(ownedGroupFileRecoveryCompletions)
    val documentMoveRecoveryCompletions: SharedFlow<DocumentMoveCommandCompletion>
        get() = businessResource(ownedDocumentMoveRecoveryCompletions)

    /** 面向无头与 SDK 集成的、稳定 id 的持久准入。 */
    fun enqueueOutgoing(message: com.virjar.tk.protocol.model.Message, requestFingerprint: ByteArray? = null): OutgoingMessage =
        lifecycle.whileBusinessActive { ownedSendQueue.enqueue(message, requestFingerprint) }

    /** 固定已认证账号的持久 active/failed/success 回执。 */
    fun outgoingReceipt(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage? = lifecycle.whileBusinessActive {
        ownedSendQueue.receipt(chatId, clientMsgId, requestFingerprint)
    }

    /** 该已认证账号当前的、对载荷不透明的发送诊断。 */
    fun outgoingQueueSnapshot(): OutgoingQueueSnapshot = lifecycle.whileBusinessActive {
        ownedSendQueue.snapshot()
    }

    fun discardTerminalFailure(chatId: String, clientMsgId: String): Boolean =
        lifecycle.whileBusinessActive {
            ownedSendQueue.discardTerminalFailure(chatId, clientMsgId)
        }

    fun replaceTerminalFailure(
        chatId: String,
        clientMsgId: String,
        replacement: com.virjar.tk.protocol.model.Message,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage? = lifecycle.whileBusinessActive {
        ownedSendQueue.replaceTerminalFailure(
            chatId,
            clientMsgId,
            replacement,
            requestFingerprint,
        )
    }

    private fun <T> businessResource(resource: T): T {
        lifecycle.requireBusinessActive()
        return resource
    }

    /**
     * 针对该精确已认证会话的 best-effort 正在输入准入。
     *
     * TYPING 刻意既不持久化也不排队。断开、被取代、已退役或过载的 transport 会直接拒绝该信号，
     * 这样 UI 输入绝不会把临时提示变成用户可见的失败。
     */
    fun trySendTyping(chatId: String): Boolean {
        return trySendSessionTyping(
            chatId = chatId,
            ownerUid = ownerUid,
            lifecycle = lifecycle,
            imClient = imClient,
            transportOwnerGeneration = transportOwnerGeneration,
            outboundLease = outboundLease,
        )
    }

    /**
     * 第一个生命周期阶段：拒绝新业务，终态退役 outbound/event/cache/HTTP owner，只保留密封在
     * 用户登出退役内部的裸 RPC owner。
     */
    fun quiesce(reason: SessionEndReason) {
        resourceLifecycle.runUntil(
            isComplete = { lifecycle.phase != SessionLifecyclePhase.ACTIVE },
            drain = { drainQuiesce(reason) },
        )
    }

    private fun drainQuiesce(reason: SessionEndReason): Throwable? {
        var terminalFailure: Throwable? = null
        val beganQuiesce = try {
            lifecycle.beginQuiesce(reason) {
                outboundLease.retire()
                ownedEventProcessor.retireSyncWireAdmission()
                ownedLocalMutations.retireAdmission()
            }
        } catch (failure: Throwable) {
            // beginQuiesce 会在 finally 中发布不可逆的终态阶段。在同一调用中继续释放剩余的
            // owner，之后再重放该缺陷。
            terminalFailure = failure
            true
        }
        if (!beganQuiesce) return null
        val failures = try {
            releaseBestEffort(
                stage = "quiesce",
                "pending ACKs" to { imClient.retireSessionOutbound(outboundLease.ackOwner) },
                // AppDataState 在进入此边界前捕获其最终编辑帧。在 SendQueue 与 SQLite driver
                // 都还存活时排空每一个已接受的本地命令。
                "local UI mutations" to ownedLocalMutations::closeAndDrain,
                "local mirror recovery" to { localMirrorRecoveryScope.cancel() },
                // 该仓库拥有在途 HTTP 工作与一个 LocalCache 支撑的凭据槽，因此要先于其 auth
                // router 或 cache 依赖关闭之前退役它。
                "group-bot HTTP" to ownedGroupBotManagementRepo::close,
                "send queue" to { ownedSendQueue.close(reason.outgoingDisposition()) },
                "friend presence" to ownedFriendPresenceRepository::close,
                "event processor" to ownedEventProcessor::stop,
                "HTTP auth expiry router" to ownedHttpAuthExpiredRouter::close,
                // 退役 RPC 不使用 LocalCache。在此处关闭会隔断正在退役的 UI 捕获的仓库与分页器，
                // 同时只保留密封的裸 RPC owner。
                "local cache" to ownedLocalCache::close,
                "AppLog owner" to { ownedLogOwner?.let(AppLog::release) },
                // AppLog 准入最先退役；随后 uploader 可以持久化精确的最终内存 recorder 后缀，
                // 而不会有日志在其优雅刷写背后竞争。
                "telemetry HTTP" to { ownedTelemetryUploader?.stop() },
            )
        } catch (failure: Throwable) {
            terminalFailure = mergeSessionLifecycleFailures(terminalFailure, failure)
            emptyList()
        }
        failures.firstOrNull { it.second is SessionBoundaryReentrantCloseException }
            ?.second
            ?.let { failure ->
                terminalFailure = mergeSessionLifecycleFailures(terminalFailure, failure)
            }
        return terminalFailure
    }

    /** 同步 UI 边界；不暴露裸 RPC owner 或其能力。 */
    fun beginUserLogoutRetirement() {
        quiesce(SessionEndReason.USER_LOGOUT)
    }

    /** 应用唯一的登出完成入口；一次性能力保留在会话内部。 */
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
     * 释放会话拥有的 SDK 组件。在紧接着的 logout -> login 转换期间，退役中的会话与新会话会短暂
     * 共享 [imClient]；此时退役中的 owner 必须在不断开新 transport 的前提下释放其 job。
     */
    fun close(
        reason: SessionEndReason = SessionEndReason.SHUTDOWN,
        disconnectTransport: Boolean = true,
    ) {
        resourceLifecycle.runUntil(
            isComplete = { lifecycle.phase == SessionLifecyclePhase.CLOSED },
            drain = {
                var boundaryFailure: Throwable? = null
                try {
                    boundaryFailure = drainQuiesce(reason)
                } catch (failure: Throwable) {
                    // 在传播取消、VM 致命缺陷或重入 owner 违规之前完成裸 RPC/transport 退役。
                    boundaryFailure = failure
                }
                val shouldCloseResources = try {
                    lifecycle.markClosed()
                } catch (failure: Throwable) {
                    boundaryFailure = mergeSessionLifecycleFailures(boundaryFailure, failure)
                    false
                }
                if (shouldCloseResources) {
                    try {
                        val closeFailures = releaseBestEffort(
                            stage = "close",
                            "raw RPC" to ownedRpcClient::stop,
                            "transport" to {
                                if (disconnectTransport) {
                                    imClient.disconnectIfOwned(transportOwnerGeneration)
                                }
                            },
                        )
                        closeFailures.firstOrNull {
                            it.second is SessionBoundaryReentrantCloseException
                        }?.second?.let { failure ->
                            boundaryFailure = mergeSessionLifecycleFailures(boundaryFailure, failure)
                        }
                    } catch (failure: Throwable) {
                        boundaryFailure = mergeSessionLifecycleFailures(boundaryFailure, failure)
                    }
                }
                boundaryFailure
            },
        )
    }

    private fun releaseBestEffort(
        stage: String,
        vararg releases: Pair<String, () -> Unit>,
    ): List<Pair<String, Throwable>> {
        val failures = releaseAllSessionResources(*releases).toMutableList()
        if (failures.isNotEmpty()) {
            val summary = failures.joinToString { (owner, failure) ->
                "$owner=${failure::class.simpleName}:${failure.message}"
            }
            try {
                ownedLogOwner?.recordCleanupFault(
                    "ClientSession",
                    "$stage released with ${failures.size} failure(s): $summary",
                    failures.first().second,
                )
            } catch (diagnosticFailure: Throwable) {
                if (isFatalSessionLifecycleFailure(diagnosticFailure)) {
                    failures.forEach { (_, failure) -> addSuppressedDistinct(diagnosticFailure, failure) }
                    throw diagnosticFailure
                }
                failures += "cleanup diagnostics" to diagnosticFailure
            }
        }
        return failures
    }

    fun recordRetirementFailure(stage: String, failure: Throwable) {
        ownedLogOwner?.recordCleanupFault("ClientSession", stage, failure)
    }
}

/**
 * 创建完整会话。在持久账号身份恢复或在线认证成功后调用；网络认证可在后台继续。
 * @param deploymentIdentity cache 与 HTTP 资源共享的规范 TCP+HTTP 部署
 * @param createCache 平台提供的 LocalCache 工厂 (deploymentIdentity, uid) -> LocalCache
 * @param deviceId 设备 ID，用于日志上传标识
 */
fun createSession(
    imClient: ImClient,
    userSession: UserSession,
    deploymentIdentity: DeploymentIdentity,
    createCache: (DeploymentIdentity, String, String) -> LocalCache,
    deviceId: String,
    logUploadEnabled: Boolean = true,  // 无头场景（serverUrl 未知）传 false 免噪音
    durableMessageSink: ((Long, Message) -> Unit)? = null,
    durableChatTombstoneSink: ((String, () -> Unit) -> Unit)? = null,
    runtimeInfo: ClientRuntimeInfo = ClientRuntimeInfo.unknown(),
    telemetrySpoolRoot: File = platformDataDir(),
): ClientSession {
    val sessionOwnerUid = userSession.uid
    require(sessionOwnerUid.isNotBlank()) { "Cannot create a session without an account owner uid" }
    val sessionDatasetId = userSession.datasetId
    com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(sessionDatasetId)
    val construction = SessionConstructionRollback()
    return try {
    val cache = createCache(deploymentIdentity, sessionDatasetId, sessionOwnerUid)
    construction.own("local cache", cache::close)
    cache.bindSyncDataset(sessionDatasetId)
    val rpcClient = RpcClient(imClient)
    construction.own("raw RPC", rpcClient::stop)
    val outboundLease = SessionOutboundLease()
    construction.own("outbound admission", outboundLease::retire)
    val sessionTransportOwnerGeneration = imClient.currentTransportOwnerGeneration
    check(sessionTransportOwnerGeneration > 0L) { "Cannot create session without a transport owner" }
    val lifecycle = SessionLifecycleGate()
    val businessRpcClient = SessionBusinessRpcInvoker(rpcClient, lifecycle, outboundLease)
    val checkpointAdmission = SessionOutboundLease()
    construction.own("checkpoint admission", checkpointAdmission::retire)
    val checkpointLoader = SyncCheckpointLoader(
        SyncRpcProxy(SynchronizationRpcInvoker(rpcClient, checkpointAdmission)),
    )
    val httpAuthExpiredRouter = SessionHttpAuthExpiredRouter()
    construction.own("HTTP auth expiry router", httpAuthExpiredRouter::close)
    val pendingMirrorWake = SessionPendingMirrorWake()
    construction.own("pending mirror wake", pendingMirrorWake::close)
    lateinit var sendQueue: SendQueue
    val refreshOutgoingProjectionSnapshot: () -> Unit = {
        try {
            sendQueue.snapshot()
        } catch (failure: Exception) {
            // 此元数据刷新绝不能回滚已经持久化的消息事件/历史投影，包括 SendQueue 先退役的
            // 狭窄 quiesce 窗口。
            com.virjar.tk.shared.log.AppLog.trace(
                "ClientSession",
                "outgoing queue projection refresh skipped: ${failure::class.simpleName}",
            )
        }
    }
    val reliableCommandCompletions = SessionReliableCommandCompletionFlows.create()
    val conversationRepo = ConversationRepository(
        rpcClient = businessRpcClient,
        localCache = cache,
        onPendingMirrorCommitted = pendingMirrorWake::pendingCommitted,
    )
    val ep = EventProcessor(
        imClient,
        cache,
        onConversationsDirty = { conversationRepo.listConversations().getOrThrow() },
        durableMessageSink = durableMessageSink,
        durableChatTombstoneSink = durableChatTombstoneSink,
        ownerUid = sessionOwnerUid,
        onOutgoingProjectionMayHaveChanged = refreshOutgoingProjectionSnapshot,
        checkpointLoader = checkpointLoader,
    )
    ep.bindSyncWireAdmission(checkpointAdmission)
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
    val messageRepo = MessageRepository(
        rpcClient = businessRpcClient,
        localCache = cache,
        onPendingMirrorCommitted = pendingMirrorWake::pendingCommitted,
        onOutgoingProjectionMayHaveChanged = refreshOutgoingProjectionSnapshot,
    )
    val reliableCommandFamilies = SessionReliableCommandFamilies.create(
        rpcClient = businessRpcClient,
        localCache = cache,
        ownerUid = sessionOwnerUid,
        completions = reliableCommandCompletions,
        onPendingCommitted = pendingMirrorWake::pendingCommitted,
    )

    // 发送队列：断线排队 → AUTHENTICATED 唤醒补发；状态机回写本地缓存驱动 UI
    sendQueue = SendQueue(
        ownerUid = sessionOwnerUid,
        localCache = cache,
        connectionState = imClient.state,
        sender = messageSender,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    )
    construction.own("send queue", sendQueue::close)

    // 日志缓冲区（分级：trace + fault）
    val traceBuffer = LogBuffer(capacity = 2000)
    val faultBuffer = LogBuffer(capacity = 500)

    val localMirrorRecoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    construction.own("local mirror recovery", localMirrorRecoveryScope::cancel)

    val localMutations = SessionLocalMutationQueue(
        ownerUid = sessionOwnerUid,
        operations = SessionLocalMutationOperations(
            setDraft = conversationRepo::setDraftLocal,
            // setDraftLocal 已经将持久提交发布给会话恢复 worker。
            draftCommitted = { _, _ -> },
            markRead = messageRepo::markReadLocal,
            // markReadLocal 使用同一提交唤醒；一个 worker 拥有所有镜像重试时机。
            readCommitted = { _, _ -> },
            insertMessage = cache::insertMessage,
            updateUploadProgress = { chatId, clientMsgId, progress ->
                cache.updateMessageInMemory(chatId, clientMsgId) {
                    it.copy(uploadProgress = progress)
                }
            },
            enqueueOutgoing = { message -> sendQueue.enqueue(message) },
            discardTerminalFailure = sendQueue::discardTerminalFailure,
            replaceTerminalFailure = { chatId, clientMsgId, replacement ->
                sendQueue.replaceTerminalFailure(chatId, clientMsgId, replacement)
            },
            markMessageFailed = { chatId, clientMsgId ->
                cache.updateMessageStatus(chatId, clientMsgId, Message.SEND_STATUS_FAILED)
            },
            closePager = MessagePager::close,
            rollbackOptimisticEdit = { lease -> cache.rollbackOptimisticMessageEdit(lease) },
        ),
    )
    construction.own("local UI mutations", localMutations::closeAndDrain)

    // 结构化遥测：精确 owner 命名空间、不可变假脱机段与策略心跳。
    val serverUrl = deploymentIdentity.httpBaseUrl
    val logOwnerUid = sessionOwnerUid
    if (logUploadEnabled) {
        check(logOwnerUid.isNotBlank()) { "Cannot create structured telemetry uploader before authentication" }
    }
    val telemetryBootstrap = bestEffortSessionTelemetry(
        enabled = logUploadEnabled,
        localDiagnostics = traceBuffer,
    ) {
        val spool = ClientTelemetrySpool(
            telemetrySpoolRoot,
            deploymentIdentity,
            sessionDatasetId,
            logOwnerUid,
        )
        SessionTelemetryBootstrap(
            spool = spool,
            crashDumper = CrashDumper(
                telemetrySpoolRoot,
                deploymentIdentity,
                sessionDatasetId,
                logOwnerUid,
            ),
        )
    }
    val telemetrySpool = telemetryBootstrap?.spool
    val crashDumper = telemetryBootstrap?.crashDumper
    val telemetryRecorder = telemetrySpool?.let { spool ->
        ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo,
            spool = spool,
            connectionTraceContextProvider = imClient::connectionTraceContextSnapshot,
        )
    }
    val outgoingQueueTelemetry = telemetryRecorder?.let { recorder ->
        OutgoingQueueTelemetryBridge(
            recorder = recorder,
            currentSnapshot = { sendQueue.queueSnapshots.value },
        )
    }
    val uploader: ClientTelemetryUploader? = telemetryRecorder?.let { recorder ->
        ClientTelemetryUploader(
            recorder = recorder,
            spool = checkNotNull(telemetrySpool),
            serverUrl = serverUrl,
            ownerUid = logOwnerUid,
            credentialsProvider = userSession::httpCredentialsSnapshot,
            localDiagnostics = traceBuffer,
            emergencyCrashDumper = checkNotNull(crashDumper),
            onAuthExpired = httpAuthExpiredRouter::report,
            // 诊断策略可能在队列闲置时变得活跃。记录当前聚合值；bridge 会合并 snapshot()
            // 的 StateFlow 发布。
            onPolicyApplied = {
                outgoingQueueTelemetry?.recordIfCurrent(sendQueue.snapshot())
            },
        )
    }
    val faultHandler: (() -> Unit)? = uploader?.let { activeUploader ->
        { activeUploader.trigger() }
    }
    // 在 AppLog 之前注册：构造回滚是逆序的，因此日志准入会在 uploader 执行其精确的最终
    // recorder 刷写之前退役，与正常的会话关闭一致。
    uploader?.let { construction.own("telemetry HTTP", it::stop) }
    var previousLogOwner: AppLogOwner? = null
    val logOwner = installAppLogOwnershipIfEnabled(
        enabled = uploader != null,
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        faultHandler = faultHandler,
        telemetrySink = telemetryRecorder?.let { recorder ->
            { level, tag, message, throwable ->
                recorder.recordAppLog(
                    level = if (level == "fault") TelemetryLogLevel.ERROR else TelemetryLogLevel.TRACE,
                    logger = tag,
                    message = message,
                    throwable = throwable,
                )
            }
        },
        crashSink = crashDumper?.let { dumper ->
            { _, _ ->
                // 未捕获异常处理器是唯一的同步持久化例外。固定标记不包含原始 message/stack/body，
                // 并在下次启动时被转移。
                dumper.flushPending(CLIENT_TELEMETRY_FATAL_MARKER)
            }
        },
        previousOwnerSink = { previousLogOwner = it },
    )
    logOwner?.let { installedOwner ->
        construction.own("AppLog owner") {
            AppLog.restoreAfterFailedInstall(installedOwner, previousLogOwner)
        }
    }
    outgoingQueueTelemetry?.let { bridge ->
        sendQueue.queueSnapshots
            .onEach(bridge::recordIfCurrent)
            .launchIn(localMirrorRecoveryScope)
    }
    uploader?.bindConnectionTracePolicyRefresh(imClient, localMirrorRecoveryScope)
    // 在 worker 可以发布或触发 fault 上传之前，安装完整的 owner 快照。
    uploader?.start()

    // 无头/禁用的会话仍会获得一个固定的平台 logger，但绝不会把它安装进进程级 AppLog 槽，
    // 因此无法借用其他账号的缓冲区。
    val sessionLogOwner = logOwner ?: AppLogOwner(
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        onFault = null,
        crashSink = null,
    )
    val friendPresenceRepository = FriendPresenceRepository(
        connectionState = imClient.state,
        presenceEvents = ep.presenceEvents,
        contactEvents = ep.contactEvents,
        loadSnapshot = ContactRpcProxy(businessRpcClient)::getPresenceSnapshot,
    )
    construction.own("friend presence", friendPresenceRepository::close)
    rpcClient.bindLogger(sessionLogOwner.logger("RpcClient"))
    ep.bindLogger(sessionLogOwner.logger("EventProcessor"))
    friendPresenceRepository.bindLogger(sessionLogOwner.logger("FriendPresenceRepository"))
    rpcClient.start()
    friendPresenceRepository.start()
    // EventProcessor 先订阅入站事件，再安装持久 cursor/批次投影 binding 并发起显式分页同步。
    ep.start()
    // 只有在 RPC owner 存活之后才启动。初始 StateFlow 值可能已经是 AUTHENTICATED，因此过早
    // 启动该 worker 会把构造顺序失败变成不可重试的 Unknown outcome。
    startSessionPendingMirrorRecovery(
        connectionState = imClient.state,
        wake = pendingMirrorWake,
        retryPendingDrafts = conversationRepo::retryPendingDrafts,
        retryPendingReads = messageRepo::retryPendingReads,
        reliableCommands = reliableCommandFamilies,
        parentScope = localMirrorRecoveryScope,
        onAuthExpired = {
            val credentials = userSession.httpCredentialsSnapshot()
            if (credentials.uid == sessionOwnerUid) {
                credentials.accessToken?.let(httpAuthExpiredRouter::report)
            }
        },
    )
    // 待处理的草稿/已读镜像与可靠命令共享上面的会话 worker。
    // 崩溃上传恢复只需要已认证边界，并且独立保持 best-effort。
    imClient.state
        .onEach { state ->
            if (state == ConnectionState.AUTHENTICATED) {
                uploader?.retryPending()
            }
        }
        .launchIn(localMirrorRecoveryScope)

    val groupBotManagementRepo = GroupBotManagementRepository(
        serverUrl = serverUrl,
        ownerUid = sessionOwnerUid,
        credentialsProvider = userSession::httpCredentialsSnapshot,
        localCache = cache,
        onAuthExpired = httpAuthExpiredRouter::report,
    )
    construction.own("group-bot HTTP", groupBotManagementRepo::close)

    val result = ClientSession(
        deviceId = deviceId,
        deploymentIdentity = deploymentIdentity,
        ownerUid = sessionOwnerUid,
        datasetId = sessionDatasetId,
        imClient = imClient,
        ownedUserSession = userSession,
        ownedLocalCache = cache,
        ownedRpcClient = rpcClient,
        ownedEventProcessor = ep,
        ownedFriendPresenceRepository = friendPresenceRepository,
        ownedTelemetryUploader = uploader,
        ownedTelemetryRecorder = telemetryRecorder,
        ownedLogOwner = sessionLogOwner,
        outboundLease = outboundLease,
        transportOwnerGeneration = sessionTransportOwnerGeneration,
        localMirrorRecoveryScope = localMirrorRecoveryScope,
        lifecycle = lifecycle,
        ownedConversationRepo = conversationRepo,
        ownedContactRepo = reliableCommandFamilies.contacts,
        ownedMessageRepo = messageRepo,
        ownedChatRepo = reliableCommandFamilies.chats,
        ownedDeviceRepo = DeviceRepository(businessRpcClient),
        ownedUserRepo = UserRepository(businessRpcClient, cache),
        ownedOrganizationRepo = OrganizationRepository(businessRpcClient, cache),
        ownedGroupFileRepo = reliableCommandFamilies.groupFiles,
        ownedDocumentRepo = reliableCommandFamilies.documents,
        ownedHttpAuthExpiredRouter = httpAuthExpiredRouter,
        ownedGroupBotManagementRepo = groupBotManagementRepo,
        ownedSendQueue = sendQueue,
        ownedLocalMutations = localMutations,
        ownedInviteLinkRecoveryCompletions = reliableCommandCompletions.inviteLinks,
        ownedContactDecisionRecoveryCompletions = reliableCommandCompletions.contactDecisions,
        ownedGroupFileRecoveryCompletions = reliableCommandCompletions.groupFiles,
        ownedDocumentMoveRecoveryCompletions = reliableCommandCompletions.documentMoves,
    )
    construction.handOff()
    result
    } catch (failure: Throwable) {
        val rollbackFailures = try {
            construction.rollback()
        } catch (rollbackFailure: Throwable) {
            throw mergeSessionLifecycleFailures(failure, rollbackFailure)
        }
        rollbackFailures.forEach { (_, cleanupFailure) ->
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

private data class SessionTelemetryBootstrap(
    val spool: ClientTelemetrySpool,
    val crashDumper: CrashDumper,
)
