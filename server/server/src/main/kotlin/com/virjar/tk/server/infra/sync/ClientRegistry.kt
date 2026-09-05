package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.auth.AuthenticatedDevicePolicy
import com.virjar.tk.server.domain.auth.CredentialSessionAuthority
import com.virjar.tk.server.domain.organization.OrganizationChangePublisher
import com.virjar.tk.server.domain.contact.ContactPolicy
import com.virjar.tk.server.domain.user.UserProfileChangePublisher
import com.virjar.tk.server.domain.presence.FriendPresenceSnapshotReader
import com.virjar.tk.server.domain.session.OnlineSessions
import com.virjar.tk.server.domain.presence.PresenceObserverLease
import com.virjar.tk.server.domain.presence.PresenceTransitionObserver
import com.virjar.tk.server.domain.presence.PresenceTransitionSource
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceIdentity
import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.OrganizationChangedPayload
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.server.protocol.connection.ImAgent
import com.virjar.tk.server.protocol.executor.Looper
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncReadyPayload
import com.virjar.tk.server.protocol.trace.RecorderPolicyUpdate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AuthenticatedAgentAdmission {
    ADMITTED,
    REJECTED,
    USER_LIMIT_REACHED,
}

internal data class CredentialAdmissionTicket(
    val uid: String,
    val deviceId: String,
    val sessionId: String,
    val userCredentialEpoch: Long,
    val deviceCredentialEpoch: Long,
    val correlationId: String,
    val connectionGeneration: Long,
)

internal sealed interface AuthenticatedAgentAdmissionPlan {
    data class Validate(val ticket: CredentialAdmissionTicket) : AuthenticatedAgentAdmissionPlan
    data class Finished(val result: AuthenticatedAgentAdmission) : AuthenticatedAgentAdmissionPlan
}

/**
 * 认证完成后连接先进入 [authenticatedAgents]，同步完成后才进入 [userAgents]。
 *
 * 激活和实时推送都在同一个 Looper 上串行；[activate] 先写 SYNC_READY 再公开连接。
 * SyncEventDispatcher 还会在 per-user delivery gate 内调用它，因此 gate 释放后的第一个
 * 实时 NOTIFY 必然排在 SYNC_READY 之后。
 */
class ClientRegistry(
    private val credentialAuthority: CredentialSessionAuthority,
    private val telemetryControl: ClientTelemetryControlRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    serverEpoch: String = UUID.randomUUID().toString(),
) : OnlineSessions, LiveEventSink, PresenceTransitionSource, FriendPresenceSnapshotReader,
    OrganizationChangePublisher, UserProfileChangePublisher {
    private val logger = LoggerFactory.getLogger("ClientRegistry")
    private val acceptingWork = AtomicBoolean(true)
    private val presenceState = RegistryPresenceState(serverEpoch)

    /** uid → (deviceId → ImAgent)；只包含已经完成同步的连接。 */
    private val userAgents = mutableMapOf<String, MutableMap<String, ImAgent>>()
    /** 已绑定权威凭据 epoch 的全部连接，包括仍在 SYNCHRONIZING 的连接。 */
    private val authenticatedAgents = AuthenticatedConnectionIndex(
        capacity = AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER,
        uidOf = { agent: ImAgent -> agent.uid },
        deviceIdOf = { agent -> agent.deviceId },
    )
    private val workThread = Looper(
        name = "client-registry",
        criticalSweep = ::reapUnregisteredAgents,
    ).apply { start() }

    private val presenceObserverLock = Any()

    /** 由应用生命周期线程安装，由 [workThread] 读取。 */
    @Volatile
    private var presenceObserver: PresenceTransitionObserver? = null

    override fun installPresenceObserver(observer: PresenceTransitionObserver): PresenceObserverLease {
        synchronized(presenceObserverLock) {
            check(acceptingWork.get()) { "ClientRegistry no longer accepts presence observers" }
            check(presenceObserver == null) { "ClientRegistry already has a presence observer" }
            presenceObserver = observer
        }
        val uninstalled = AtomicBoolean(false)
        return PresenceObserverLease {
            if (uninstalled.compareAndSet(false, true)) {
                synchronized(presenceObserverLock) {
                    if (presenceObserver === observer) presenceObserver = null
                }
            }
        }
    }

    /**
     * 身份认证提交后、发送成功 AUTH_RESP 前的准入屏障。
     *
     * 连接在同步历史事件期间也必须可被 ban/reset/device-revoke 找到；否则封禁动作只能
     * 等到 SYNC_READY 才生效。候选身份先在同一 Looper 上登记，再进行权威 epoch 重验，
     * 因而 credential mutation 不会落入“已提交但仍找不到旧连接”的窗口。
     */
    internal suspend fun beginAuthenticatedAdmission(agent: ImAgent): AuthenticatedAgentAdmissionPlan =
        workThread.suspendAwait {
            if (!agent.isActive) {
                return@suspendAwait AuthenticatedAgentAdmissionPlan.Finished(
                    AuthenticatedAgentAdmission.REJECTED,
                )
            }
            // 签发新凭据对会使此设备所有先前的凭据失效。一条存活的 TCP
            // 连接不能在新来者最终到达
            // SYNC_READY 之前比该替代存续更久，因此在凭据准入而非激活时就取代它。
            val sameDeviceSessions = authenticatedAgents.forDevice(agent.uid, agent.deviceId)
                .filter { old -> old !== agent }
            val candidateTraceIdentity = agent.connectionTraceIdentity
            if (candidateTraceIdentity == null || sameDeviceSessions.any { old ->
                    val existingTraceIdentity = old.connectionTraceIdentity
                    existingTraceIdentity == null ||
                        !connectionIdentityIsFresh(
                            candidateCorrelationId = candidateTraceIdentity.correlationId,
                            existingCorrelationId = existingTraceIdentity.correlationId,
                        ) ||
                    !credentialEpochsDoNotRegress(
                        candidateUserEpoch = agent.userCredentialEpoch,
                        candidateDeviceEpoch = agent.deviceCredentialEpoch,
                        existingUserEpoch = old.userCredentialEpoch,
                        existingDeviceEpoch = old.deviceCredentialEpoch,
                    )
                }
            ) {
                logger.info(
                    "Rejecting credential-regressing replacement uid={} deviceId={} userEpoch={} deviceEpoch={}",
                    agent.uid,
                    agent.deviceId,
                    agent.userCredentialEpoch,
                    agent.deviceCredentialEpoch,
                )
                retireAgent(agent)
                return@suspendAwait AuthenticatedAgentAdmissionPlan.Finished(
                    AuthenticatedAgentAdmission.REJECTED,
                )
            }
            when (val admission = authenticatedAgents.admit(agent)) {
                IndexedConnectionAdmission.LimitReached -> {
                    logger.info("Rejecting authenticated session because the per-user connection limit was reached")
                    retireAgent(agent)
                    return@suspendAwait AuthenticatedAgentAdmissionPlan.Finished(
                        AuthenticatedAgentAdmission.USER_LIMIT_REACHED,
                    )
                }
                is IndexedConnectionAdmission.Admitted -> {
                    check(admission.replaced.toSet() == sameDeviceSessions.toSet()) {
                        "Authenticated connection replacement changed during serialized admission"
                    }
                    admission.replaced.forEach { old -> retireAgent(old) }
                }
            }
            AuthenticatedAgentAdmissionPlan.Validate(agent.toCredentialAdmissionTicket())
        }

    /**
     * 针对一个权威 PostgreSQL 快照完成临时准入。
     *
     * 候选者在读取开始之前已经被索引。因此并发的凭据变更
     * 要么先于快照并使 [CredentialSessionAuthority] 返回 false，
     * 要么后于快照并通过序列化的失效路径回收已索引的候选者。
     * 在所有相关连接离开后，无需让任何历史 uid/device fence 保持驻留。
     */
    internal suspend fun completeAuthenticatedAdmission(
        ticket: CredentialAdmissionTicket,
    ): AuthenticatedAgentAdmission {
        return try {
            completeProvisionalCredentialAdmission(
                authoritativeSnapshot = {
                    credentialAuthority.isCurrent(
                        uid = ticket.uid,
                        deviceId = ticket.deviceId,
                        userCredentialEpoch = ticket.userCredentialEpoch,
                        deviceCredentialEpoch = ticket.deviceCredentialEpoch,
                    )
                },
                serializedCompletion = { isCurrent ->
                    workThread.suspendAwait {
                        val agent = findProvisionalAgent(ticket)
                            ?: return@suspendAwait AuthenticatedAgentAdmission.REJECTED
                        if (!credentialAdmissionCanComplete(isCurrent, agent)) {
                            logger.info(
                                "Rejecting non-authoritative credential session uid={} deviceId={} " +
                                    "userEpoch={} deviceEpoch={}",
                                ticket.uid,
                                ticket.deviceId,
                                ticket.userCredentialEpoch,
                                ticket.deviceCredentialEpoch,
                            )
                            retireAgent(agent)
                            AuthenticatedAgentAdmission.REJECTED
                        } else {
                            AuthenticatedAgentAdmission.ADMITTED
                        }
                    }
                }
            )
        } catch (failure: Throwable) {
            rejectProvisionalAdmission(ticket, failure)
            throw failure
        }
    }

    /**
     * 发送同步完成标记，并仅在写入已排队后公开连接用于 live delivery。
     * 返回 false 表示连接已关闭，调用方必须终止本轮同步。
     */
    suspend fun activate(agent: ImAgent): Boolean {
        val activated = workThread.suspendAwait {
            if (!agent.isActive) return@suspendAwait false

            if (!authenticatedAgents.contains(agent) || agent.isCredentialTerminal) {
                logger.info(
                    "Rejecting stale credential session uid={} deviceId={} userEpoch={} deviceEpoch={}",
                    agent.uid,
                    agent.deviceId,
                    agent.userCredentialEpoch,
                    agent.deviceCredentialEpoch,
                )
                retireAgent(agent)
                return@suspendAwait false
            }

            // 状态迁移与发布发生在同一个串行 looper 上。若连接
            // 并发关闭，channelInactive 会把注销排在此激活之后。
            if (!agent.markReadyForLiveActivation()) return@suspendAwait false

            // 先写 ready，再注册。SyncEventService 的用户门闩保证这两步之间没有同用户 push。
            agent.write(SyncReadyPayload)
            val devices = userAgents.getOrPut(agent.uid) { mutableMapOf() }
            val previousDeviceCount = devices.size
            val old = devices.put(agent.deviceId, agent)
            if (old != null && old !== agent) {
                // 替代者已经完全同步。让被取代的连接保持可写，
                // 会让凭据 fence 因其不再可从映射到达而错过它。
                logger.debug("Duplicate device uid=${agent.uid} deviceId=${agent.deviceId}, kicking old immediately")
                retireAgent(old)
            }
            presenceState.onDeviceCountChanged(
                uid = agent.uid,
                previousDeviceCount = previousDeviceCount,
                currentDeviceCount = devices.size,
                occurredAt = clock,
            )?.let { presenceObserver?.onTransition(it) }
            logger.debug("Activated agent for uid=${agent.uid} deviceId=${agent.deviceId}, devices=${devices.size}")
            true
        }
        if (!activated) return false

        // 在此连接同步期间，管理员变更可能与 AUTH 策略读取竞态。
        // 只在连接于 userAgents 中可见之后再次查询，然后通过
        // 精确 agent fence 应用。SYNC_READY 已先入队，因此客户端接受此瞬时更新。
        reconcileConnectionTracePolicy(agent)
        return true
    }

    /** AUTH_RESP 使用的 IO-worker 控制面读取；绝不要从 registry looper 调用。 */
    internal fun effectiveConnectionTracePolicy(uid: String, deviceId: String): ClientTelemetryPolicy? =
        telemetryControl?.effectivePolicy(uid, deviceId, clock())

    /**
     * 为每个凭据准入的连接刷新服务器记录器，但只向完全同步的
     * [userAgents] 发送瞬时帧。因此临时连接
     * 不可能收到越状态的数据包；其单调的 Recorder 版本改为
     * 由 AUTH_RESP 反映，并再由 [activate] 对账一次。
     */
    suspend fun refreshConnectionTracePolicy(uid: String, deviceId: String? = null) {
        val repository = telemetryControl ?: return
        val targets = awaitConnectionTraceControl {
            authenticatedAgents.forUser(uid)
                .asSequence()
                .filter { agent ->
                    (deviceId == null || agent.deviceId == deviceId) && agent.isActive
                }
                .map { agent -> TelemetryDeviceIdentity(uid, agent.deviceId) }
                .toSet()
        }
        if (targets.isEmpty()) return
        val policies: Map<TelemetryDeviceIdentity, ClientTelemetryPolicy>? = try {
            repository.effectivePolicies(targets, clock())
        } catch (failure: Exception) {
            logger.warn(
                "Connection trace policy refresh failed; errorType={}",
                failure::class.java.name,
            )
            null
        }
        if (policies != null) {
            val unresolvedTargets = targets.count { identity ->
                policies[identity]?.let { policy ->
                    policy.uid == identity.uid &&
                        (policy.deviceId == null || policy.deviceId == identity.deviceId)
                } != true
            }
            if (unresolvedTargets > 0) {
                logger.warn(
                    "Connection trace policy refresh returned an incomplete snapshot; missingTargets={}",
                    unresolvedTargets,
                )
            }
        }
        awaitConnectionTraceControl {
            applyConnectionTracePolicySnapshot(
                targets = targets,
                policies = policies,
                connectionsFor = { identity ->
                    authenticatedAgents.forDevice(identity.uid, identity.deviceId)
                        .filter { agent -> agent.isActive && !agent.isCredentialTerminal }
                },
                applyPolicy = ::applyConnectionTracePolicy,
                terminalDisable = ::terminalDisableConnectionTracePolicy,
            )
        }
    }

    private suspend fun reconcileConnectionTracePolicy(agent: ImAgent) {
        val policy = try {
            effectiveConnectionTracePolicy(agent.uid, agent.deviceId)
        } catch (failure: Exception) {
            logger.warn(
                "Connection trace activation reconciliation failed; errorType={}",
                failure::class.java.name,
            )
            awaitConnectionTraceControl {
                if (
                    userAgents[agent.uid]?.get(agent.deviceId) === agent &&
                    agent.isActive &&
                    !agent.isCredentialTerminal
                ) {
                    agent.terminalDisableConnectionTracePolicy()
                    publishCurrentConnectionTracePolicyDecision(agent)
                }
            }
            return
        } ?: return
        awaitConnectionTraceControl {
            if (
                userAgents[agent.uid]?.get(agent.deviceId) === agent &&
                agent.isActive &&
                !agent.isCredentialTerminal
            ) {
                // 在此连接仍处于 SYNCHRONIZING 期间，可能已经应用了一个更新的决策。
                // 重新应用该版本刻意是空操作，因此激活
                // 发布记录器当前的决策，而不依赖此调用的
                // 返回值。
                agent.applyConnectionTracePolicy(policy)
                publishCurrentConnectionTracePolicyDecision(agent)
            }
        }
    }

    private fun applyConnectionTracePolicy(agent: ImAgent, policy: ClientTelemetryPolicy) {
        agent.applyConnectionTracePolicy(policy)
            ?.let { update -> publishConnectionTracePolicyUpdate(agent, update) }
    }

    private fun terminalDisableConnectionTracePolicy(agent: ImAgent) {
        agent.terminalDisableConnectionTracePolicy()
            ?.let { update -> publishConnectionTracePolicyUpdate(agent, update) }
    }

    private fun publishCurrentConnectionTracePolicyDecision(agent: ImAgent) {
        agent.currentConnectionTracePolicyDecision()
            ?.let { update -> publishConnectionTracePolicyUpdate(agent, update) }
    }

    /** 瞬态策略帧只在此精确连接到达 SYNC_READY 之后才合法。 */
    private fun publishConnectionTracePolicyUpdate(agent: ImAgent, update: RecorderPolicyUpdate) {
        publishExactConnectionTracePolicyUpdate(
            expected = agent,
            current = userAgents[agent.uid]?.get(agent.deviceId),
            update = update,
            publish = ImAgent::sendConnectionTracePolicyUpdate,
        )
    }

    private suspend fun <T> awaitConnectionTraceControl(block: () -> T): T =
        awaitConnectionTraceControlAdmission(
            accepting = acceptingWork::get,
            trySubmit = workThread::post,
            block = block,
        )

    fun unregister(agent: ImAgent) {
        // 该回调运行在 Netty EventLoop 上，绝不能阻塞。标记已拥有的 agent，
        // 然后触发 looper 保留的、合并的清扫，而不把此参数保留在
        // 排队的闭包中。因此普通队列饱和不可能丢失断开连接，也不会泄漏
        // 每个断开连接的 Runnable/ImAgent 引用。
        agent.requestRegistryUnregister()
        if (!acceptingWork.get()) return
        if (!workThread.signalCriticalSweep() && acceptingWork.get()) {
            // ClientRegistry.stop 在停止 looper 之前先关闭 acceptingWork。仍在
            // 接受时被拒绝会违反保留槽拥有权不变量，绝不能
            // 保持静默。
            logger.error("ClientRegistry critical unregister sweep was rejected while accepting work")
        }
    }

    /** 实时事件的唯一投递入口；避免“先取连接快照、后写入”穿透激活屏障。 */
    override suspend fun push(uid: String, notify: NotifyPayload) {
        workThread.suspendAwait {
            userAgents[uid]?.values?.toList().orEmpty().forEach { agent ->
                if (agent.isActive) {
                    try {
                        agent.write(notify)
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed live delivery uid=$uid deviceId=${agent.deviceId}; continuing other devices",
                            e,
                        )
                    }
                }
            }
        }
    }

    /**
     * 进程本地组织失效提示。[userAgents] 只包含 SYNC_READY 已入队的
     * 连接，因此此广播不可能赶在初始同步之前。
     * 该通知刻意是瞬时的：重连执行权威的全量
     * 目录刷新，而不是为每个用户重放一个事件。
     */
    override suspend fun publish(revision: Long) {
        val notify = organizationChangedNotify(revision)
        workThread.suspendAwait {
            deliverTransientBroadcast(
                targets = userAgents.values.asSequence().flatMap { it.values.asSequence() },
                isActive = ImAgent::isActive,
                deliver = { agent -> agent.write(notify) },
                onFailure = { agent, failure ->
                    logger.warn(
                        "Failed organization change delivery uid={} deviceId={}; continuing other sessions",
                        agent.uid,
                        agent.deviceId,
                        failure,
                    )
                },
            )
        }
    }

    /**
     * 针对每个活跃非持久受众的瞬时档案收敛。自己与当前
     * 好友已通过其持久流收到相同的 User，因此被排除，
     * 以避免重复的 reducer/验收通知。重连仍基于 RPC/checkpoint。
     */
    override suspend fun publish(user: User, durableRecipientUids: Set<String>) {
        require(durableRecipientUids.size <= ContactPolicy.MAX_FRIENDS_PER_USER + 1) {
            "Profile durable audience exceeds the self plus friend boundary"
        }
        val excludedUids = durableRecipientUids.toSet()
        val notify = userProfileChangedNotify(user)
        workThread.suspendAwait {
            deliverUserProfileBroadcast(
                targets = userAgents.values.asSequence().flatMap { it.values.asSequence() },
                excludedUids = excludedUids,
                uidOf = ImAgent::uid,
                isActive = ImAgent::isActive,
                deliver = { agent -> agent.write(notify) },
                onFailure = { agent, failure ->
                    logger.warn(
                        "Failed transient profile delivery uid={} deviceId={}; continuing other sessions",
                        agent.uid,
                        agent.deviceId,
                        failure,
                    )
                },
            )
        }
    }

    override suspend fun isOnline(uid: String): Boolean {
        return workThread.suspendAwait {
            val devices = userAgents[uid]
            devices != null && devices.isNotEmpty()
        }
    }

    override suspend fun snapshot(friendUids: Set<String>): FriendPresenceSnapshot {
        require(friendUids.size <= ContactPolicy.MAX_FRIENDS_PER_USER) {
            "Presence snapshot exceeds ${ContactPolicy.MAX_FRIENDS_PER_USER} friends"
        }
        // 在挂起之前冻结调用方拥有的输入；拥有者收到一个完整的候选集合。
        val candidates = friendUids.sorted()
        return workThread.suspendAwait {
            presenceState.snapshot(candidates) { candidateUid ->
                userAgents[candidateUid]?.isNotEmpty() == true
            }
        }
    }

    /** 踢掉某用户全部在线设备（封禁/重置密码联动）。 */
    override suspend fun kickUser(uid: String) {
        workThread.suspendAwait {
            authenticatedAgents.forUser(uid).forEach { agent ->
                retireAgent(agent)
            }
        }
    }

    override suspend fun onlineCount(): Int {
        return workThread.suspendAwait {
            userAgents.size
        }
    }

    /** 测试/诊断缝隙：凭据状态恰好是有界的活跃/临时索引。 */
    internal suspend fun retainedCredentialSessionCount(): Int = workThread.suspendAwait {
        authenticatedAgents.totalSize()
    }

    override suspend fun invalidateUserCredentials(uid: String, minimumEpoch: Long) {
        invalidateUserCredentials(uid, minimumEpoch, preservedSessionId = null)
    }

    override suspend fun invalidateUserCredentialsExceptSession(
        uid: String,
        minimumEpoch: Long,
        sessionId: String,
    ) {
        require(sessionId.isNotBlank()) { "Preserved session id must not be blank" }
        invalidateUserCredentials(uid, minimumEpoch, preservedSessionId = sessionId)
    }

    private suspend fun invalidateUserCredentials(
        uid: String,
        minimumEpoch: Long,
        preservedSessionId: String?,
    ) {
        require(minimumEpoch > 0L) { "Credential fence epoch must be positive" }
        workThread.suspendAwait {
            if (preservedSessionId != null) {
                authenticatedAgents.forUser(uid)
                    .firstOrNull { agent -> agent.uid == uid && agent.sessionId == preservedSessionId }
                    ?.let { preserved ->
                        // 该通道只存续足够长的时间，供 RpcDispatcher 刷出
                        // 成功的密码修改响应。凭据事务提交之后，
                        // 它就不再是已鉴权或实时投递的会话。
                        retireAgent(preserved, closeChannel = false)
                    }
            }
            authenticatedAgents.forUser(uid)
                .filter { agent ->
                    agent.userCredentialEpoch < minimumEpoch &&
                        agent.sessionId != preservedSessionId
                }
                .forEach { agent ->
                    retireAgent(agent)
                }
        }
    }

    override suspend fun invalidateDeviceCredentials(uid: String, deviceId: String, minimumEpoch: Long) {
        invalidateDeviceCredentials(uid, deviceId, minimumEpoch, preservedSessionId = null)
    }

    override suspend fun invalidateDeviceCredentialsExceptSession(
        uid: String,
        deviceId: String,
        minimumEpoch: Long,
        sessionId: String,
    ) {
        require(sessionId.isNotBlank()) { "Preserved session id must not be blank" }
        invalidateDeviceCredentials(uid, deviceId, minimumEpoch, preservedSessionId = sessionId)
    }

    private suspend fun invalidateDeviceCredentials(
        uid: String,
        deviceId: String,
        minimumEpoch: Long,
        preservedSessionId: String?,
    ) {
        require(minimumEpoch > 0L) { "Credential fence epoch must be positive" }
        workThread.suspendAwait {
            if (preservedSessionId != null) {
                authenticatedAgents.forDevice(uid, deviceId)
                    .firstOrNull { agent ->
                        agent.sessionId == preservedSessionId
                    }
                    ?.let { preserved -> retireAgent(preserved, closeChannel = false) }
            }
            authenticatedAgents.forDevice(uid, deviceId)
                .filter { agent ->
                    agent.deviceCredentialEpoch < minimumEpoch && agent.sessionId != preservedSessionId
                }
                .forEach { agent ->
                    retireAgent(agent)
                }
        }
    }

    private fun ImAgent.toCredentialAdmissionTicket() = CredentialAdmissionTicket(
        uid = uid,
        deviceId = deviceId,
        sessionId = sessionId,
        userCredentialEpoch = userCredentialEpoch,
        deviceCredentialEpoch = deviceCredentialEpoch,
        correlationId = checkNotNull(connectionTraceIdentity).correlationId,
        connectionGeneration = checkNotNull(connectionTraceIdentity).connectionGeneration,
    )

    private fun findProvisionalAgent(ticket: CredentialAdmissionTicket): ImAgent? =
        authenticatedAgents.forDevice(ticket.uid, ticket.deviceId).firstOrNull { agent ->
            agent.sessionId == ticket.sessionId &&
                agent.userCredentialEpoch == ticket.userCredentialEpoch &&
                agent.deviceCredentialEpoch == ticket.deviceCredentialEpoch
                && agent.connectionTraceIdentity?.correlationId == ticket.correlationId
                && agent.connectionTraceIdentity?.connectionGeneration == ticket.connectionGeneration
        }

    private suspend fun rejectProvisionalAdmission(
        ticket: CredentialAdmissionTicket,
        originalFailure: Throwable,
    ) {
        try {
            withContext(NonCancellable) {
                workThread.suspendAwait {
                    findProvisionalAgent(ticket)?.let(::retireAgent)
                }
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== originalFailure) originalFailure.addSuppressed(cleanupFailure)
        }
    }

    /** 在关闭之前先回收命令权威，使已排队的工作观察到终结位。 */
    private fun retireAgent(agent: ImAgent, closeChannel: Boolean = true) {
        agent.markCredentialTerminal()
        authenticatedAgents.remove(agent)
        removeLiveAgent(agent)
        if (closeChannel) agent.kick()
    }

    /** 只在此精确连接是已发布的活跃设备时才移除。 */
    private fun removeLiveAgent(agent: ImAgent) {
        val devices = userAgents[agent.uid] ?: return
        if (devices[agent.deviceId] !== agent) return
        val previousDeviceCount = devices.size
        devices.remove(agent.deviceId)
        if (devices.isEmpty()) {
            userAgents.remove(agent.uid)
        }
        presenceState.onDeviceCountChanged(
            uid = agent.uid,
            previousDeviceCount = previousDeviceCount,
            currentDeviceCount = devices.size,
            occurredAt = clock,
        )?.let { presenceObserver?.onTransition(it) }
    }

    /**
     * 合并的断开连接清理。标记是单调的，因此合并信号是安全的。与
     * 此扫描竞态的信号会改变 Looper 的关键版本，并再调度一次 FIFO 清扫；
     * 任何侧集合都不需要保留已断开的 agent。
     */
    private fun reapUnregisteredAgents() {
        workThread.checkLooper()
        val requested = linkedSetOf<ImAgent>()
        authenticatedAgents.all().filterTo(requested) { it.isRegistryUnregisterRequested }
        userAgents.values.forEach { devices ->
            devices.values.filterTo(requested) { it.isRegistryUnregisterRequested }
        }
        requested.forEach { agent ->
            try {
                authenticatedAgents.remove(agent)
                removeLiveAgent(agent)
            } catch (error: Exception) {
                // presence 观察者必须非阻塞，但一个故障观察者绝不能
                // 阻止同一次终结清扫释放其他每个已断开的 agent。
                logger.error(
                    "Failed unregister cleanup uid={} deviceId={}; continuing sweep",
                    agent.uid,
                    agent.deviceId,
                    error,
                )
            }
        }
        if (requested.isNotEmpty()) {
            logger.debug("Unregistered {} disconnected client session(s)", requested.size)
        }
    }

    fun stop() {
        acceptingWork.set(false)
        // TCP 关闭在此拥有者停止之前等待 channelInactive。Looper.stop 排空
        // 每个已接受的命令（包括保留的注销清扫），然后在此
        // 同一串行线程上运行清理并 join 它。终结器本身不可能被已满的
        // 普通队列拒绝。每个调用方都进入 stop，因此并发/重复的生命周期拥有者等待
        // 同一终止边界，而不是在第一次关闭仍活跃时就返回。
        workThread.stop(::clearOwnedState)
    }

    private fun clearOwnedState() {
        workThread.checkLooper()
        userAgents.clear()
        authenticatedAgents.clear()
        synchronized(presenceObserverLock) { presenceObserver = null }
    }
}

/** 替代者必须来自相同/更新的用户 epoch，以及严格更新的设备对。 */
internal fun credentialEpochsDoNotRegress(
    candidateUserEpoch: Long,
    candidateDeviceEpoch: Long,
    existingUserEpoch: Long,
    existingDeviceEpoch: Long,
): Boolean = candidateUserEpoch >= existingUserEpoch && candidateDeviceEpoch > existingDeviceEpoch

/**
 * 世代只在一个客户端进程内单调，应用重启后可能重置。
 * 因此跨连接重放保护使用新的随机 correlation id；世代
 * 在该物理连接内仍是精确的每包/每票据 fence。
 */
internal fun connectionIdentityIsFresh(
    candidateCorrelationId: String,
    existingCorrelationId: String,
): Boolean = candidateCorrelationId != existingCorrelationId

internal fun credentialAdmissionCanComplete(
    authorityCurrent: Boolean,
    provisionalIndexed: Boolean,
    connectionActive: Boolean,
    credentialTerminal: Boolean,
): Boolean = authorityCurrent && provisionalIndexed && connectionActive && !credentialTerminal

internal suspend fun <T> completeProvisionalCredentialAdmission(
    authoritativeSnapshot: suspend () -> Boolean,
    serializedCompletion: suspend (Boolean) -> T,
): T = serializedCompletion(authoritativeSnapshot())

private fun credentialAdmissionCanComplete(authorityCurrent: Boolean, agent: ImAgent): Boolean =
    credentialAdmissionCanComplete(
        authorityCurrent = authorityCurrent,
        provisionalIndexed = true,
        connectionActive = agent.isActive,
        credentialTerminal = agent.isCredentialTerminal,
    )

internal fun organizationChangedNotify(revision: Long): NotifyPayload = NotifyPayload(
    eventId = 0L,
    notifyType = NotifyType.ORGANIZATION_CHANGED.code,
    payload = ProtoCodec.encode(OrganizationChangedPayload(revision)),
)

internal fun userProfileChangedNotify(user: User): NotifyPayload = NotifyPayload(
    eventId = 0L,
    notifyType = NotifyType.USER_UPDATED.code,
    payload = ProtoCodec.encode(user),
)

/** 在使用同一条故障隔离的瞬时投递缝隙之前，先排除持久接收者。 */
internal fun <T> deliverUserProfileBroadcast(
    targets: Sequence<T>,
    excludedUids: Set<String>,
    uidOf: (T) -> String,
    isActive: (T) -> Boolean,
    deliver: (T) -> Unit,
    onFailure: (T, Exception) -> Unit,
) {
    deliverTransientBroadcast(
        targets = targets.filter { target -> uidOf(target) !in excludedUids },
        isActive = isActive,
        deliver = deliver,
        onFailure = onFailure,
    )
}

/** 纯投递缝隙：跳过不活跃目标，且一次失败的写入不能中止清扫。 */
internal fun <T> deliverTransientBroadcast(
    targets: Sequence<T>,
    isActive: (T) -> Boolean,
    deliver: (T) -> Unit,
    onFailure: (T, Exception) -> Unit,
) {
    targets.forEach { target ->
        if (!isActive(target)) return@forEach
        try {
            deliver(target)
        } catch (failure: Exception) {
            onFailure(target, failure)
        }
    }
}
