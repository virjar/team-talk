package com.virjar.tk.infra.sync

import com.virjar.tk.domain.session.OnlineSessions
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.executor.Looper
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncReadyPayload
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 认证完成后连接先进入 [authenticatedAgents]，同步完成后才进入 [userAgents]。
 *
 * 激活和实时推送都在同一个 Looper 上串行；[activate] 先写 SYNC_READY 再公开连接。
 * SyncEventDispatcher 还会在 per-user delivery gate 内调用它，因此 gate 释放后的第一个
 * 实时 NOTIFY 必然排在 SYNC_READY 之后。
 */
class ClientRegistry : OnlineSessions, LiveEventSink {
    private val logger = LoggerFactory.getLogger("ClientRegistry")
    private val workThread = Looper("client-registry").apply { start() }
    private val acceptingWork = AtomicBoolean(true)

    /** uid → (deviceId → ImAgent)；只包含已经完成同步的连接。 */
    private val userAgents = mutableMapOf<String, MutableMap<String, ImAgent>>()
    /** 已绑定权威凭据 epoch 的全部连接，包括仍在 SYNCHRONIZING 的连接。 */
    private val authenticatedAgents = mutableSetOf<ImAgent>()
    private val userCredentialFences = mutableMapOf<String, Long>()
    private val deviceCredentialFences = mutableMapOf<Pair<String, String>, Long>()

    var onFirstDeviceOnline: ((uid: String) -> Unit)? = null
    var onLastDeviceOffline: ((uid: String) -> Unit)? = null

    /**
     * 身份认证提交后、发送成功 AUTH_RESP 前的准入屏障。
     *
     * 连接在同步历史事件期间也必须可被 ban/reset/device-revoke 找到；否则封禁动作只能
     * 等到 SYNC_READY 才生效。准入和凭据 fence 在同一 Looper 上串行，因此不存在
     * “先封禁、后登记旧 epoch”窗口。
     */
    suspend fun admitAuthenticated(agent: ImAgent): Boolean = workThread.suspendAwait {
        if (!agent.isActive) return@suspendAwait false
        val userFence = userCredentialFences[agent.uid] ?: 0L
        val deviceFence = deviceCredentialFences[agent.uid to agent.deviceId] ?: 0L
        if (!hasCurrentCredentials(agent, userFence, deviceFence)) {
            logger.info(
                "Rejecting stale authenticated session uid={} deviceId={} userEpoch={} deviceEpoch={}",
                agent.uid,
                agent.deviceId,
                agent.userCredentialEpoch,
                agent.deviceCredentialEpoch,
            )
            retireAgent(agent)
            return@suspendAwait false
        }
        // Issuing a new pair makes every previous credential for this device invalid. A live TCP
        // connection must not outlive that replacement until the newcomer eventually reaches
        // SYNC_READY, so supersede it at credential admission rather than activation.
        val sameDeviceSessions = authenticatedAgents.filter { old ->
            old !== agent && old.uid == agent.uid && old.deviceId == agent.deviceId
        }
        if (sameDeviceSessions.any { old ->
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
            return@suspendAwait false
        }
        sameDeviceSessions.forEach { old ->
            retireAgent(old)
        }
        authenticatedAgents += agent
        true
    }

    /**
     * 发送同步完成标记，并仅在写入已排队后公开连接用于 live delivery。
     * 返回 false 表示连接已关闭，调用方必须终止本轮同步。
     */
    suspend fun activate(agent: ImAgent): Boolean = workThread.suspendAwait {
        if (!agent.isActive) return@suspendAwait false

        val userFence = userCredentialFences[agent.uid] ?: 0L
        val deviceFence = deviceCredentialFences[agent.uid to agent.deviceId] ?: 0L
        if (agent !in authenticatedAgents || !hasCurrentCredentials(agent, userFence, deviceFence)) {
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

        // State transition and publication happen on this same serial looper. If the connection
        // closes concurrently, channelInactive queues unregister behind this activation.
        if (!agent.markReadyForLiveActivation()) return@suspendAwait false

        // 先写 ready，再注册。SyncEventService 的用户门闩保证这两步之间没有同用户 push。
        agent.write(SyncReadyPayload)
        val devices = userAgents.getOrPut(agent.uid) { mutableMapOf() }
        val wasOffline = devices.isEmpty()
        val old = devices.put(agent.deviceId, agent)
        if (old != null && old !== agent) {
            // The replacement is already fully synchronized. Keeping the superseded connection
            // writable would let a credential fence miss it because it is no longer map-reachable.
            logger.debug("Duplicate device uid=${agent.uid} deviceId=${agent.deviceId}, kicking old immediately")
            retireAgent(old)
        }
        if (wasOffline) onFirstDeviceOnline?.invoke(agent.uid)
        logger.debug("Activated agent for uid=${agent.uid} deviceId=${agent.deviceId}, devices=${devices.size}")
        true
    }

    fun unregister(agent: ImAgent) {
        if (!acceptingWork.get()) return
        workThread.post {
            authenticatedAgents.remove(agent)
            removeLiveAgent(agent)
            logger.debug("Unregistered agent for uid=${agent.uid} deviceId=${agent.deviceId}")
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

    override suspend fun isOnline(uid: String): Boolean {
        return workThread.suspendAwait {
            val devices = userAgents[uid]
            devices != null && devices.isNotEmpty()
        }
    }

    /** 踢掉某用户全部在线设备（封禁/重置密码联动）。 */
    override suspend fun kickUser(uid: String) {
        workThread.suspendAwait {
            authenticatedAgents.filter { it.uid == uid }.forEach { agent ->
                retireAgent(agent)
            }
        }
    }

    override suspend fun onlineUids(): Set<String> {
        return workThread.suspendAwait {
            userAgents.keys.toSet()
        }
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
            val committedFence = maxOf(userCredentialFences[uid] ?: 0L, minimumEpoch)
            userCredentialFences[uid] = committedFence
            if (preservedSessionId != null) {
                authenticatedAgents
                    .firstOrNull { agent -> agent.uid == uid && agent.sessionId == preservedSessionId }
                    ?.let { preserved ->
                        // The channel survives only long enough for RpcDispatcher to flush the
                        // successful password-change response. It is no longer an authenticated
                        // or live-delivery session once the credential transaction commits.
                        retireAgent(preserved, closeChannel = false)
                    }
            }
            authenticatedAgents
                .filter { agent ->
                    agent.uid == uid && agent.userCredentialEpoch < committedFence &&
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
            val key = uid to deviceId
            val committedFence = maxOf(deviceCredentialFences[key] ?: 0L, minimumEpoch)
            deviceCredentialFences[key] = committedFence
            if (preservedSessionId != null) {
                authenticatedAgents
                    .firstOrNull { agent ->
                        agent.uid == uid && agent.deviceId == deviceId && agent.sessionId == preservedSessionId
                    }
                    ?.let { preserved -> retireAgent(preserved, closeChannel = false) }
            }
            authenticatedAgents
                .filter { agent ->
                    agent.uid == uid && agent.deviceId == deviceId &&
                        agent.deviceCredentialEpoch < committedFence && agent.sessionId != preservedSessionId
                }
                .forEach { agent ->
                    retireAgent(agent)
                }
        }
    }

    private fun hasCurrentCredentials(agent: ImAgent, userFence: Long, deviceFence: Long): Boolean =
        credentialEpochsMeetFences(
            userEpoch = agent.userCredentialEpoch,
            deviceEpoch = agent.deviceCredentialEpoch,
            userFence = userFence,
            deviceFence = deviceFence,
        )

    /** Retire command authority before closing so already queued work observes the terminal bit. */
    private fun retireAgent(agent: ImAgent, closeChannel: Boolean = true) {
        agent.markCredentialTerminal()
        authenticatedAgents.remove(agent)
        removeLiveAgent(agent)
        if (closeChannel) agent.kick()
    }

    /** Remove only when this exact connection is the published live device. */
    private fun removeLiveAgent(agent: ImAgent) {
        val devices = userAgents[agent.uid] ?: return
        if (devices[agent.deviceId] !== agent) return
        devices.remove(agent.deviceId)
        if (devices.isEmpty()) {
            userAgents.remove(agent.uid)
            onLastDeviceOffline?.invoke(agent.uid)
        }
    }

    fun stop() {
        if (!acceptingWork.compareAndSet(true, false)) return
        try {
            // TCP shutdown waits for channelInactive before this barrier is queued.
            // Drain all unregister callbacks before terminating the looper.
            runBlocking {
                workThread.suspendAwait {
                    userAgents.clear()
                    authenticatedAgents.clear()
                    userCredentialFences.clear()
                    deviceCredentialFences.clear()
                    onFirstDeviceOnline = null
                    onLastDeviceOffline = null
                }
            }
        } finally {
            workThread.stop()
        }
    }
}

/** A replacement must be from the same/newer user epoch and a strictly newer device pair. */
internal fun credentialEpochsDoNotRegress(
    candidateUserEpoch: Long,
    candidateDeviceEpoch: Long,
    existingUserEpoch: Long,
    existingDeviceEpoch: Long,
): Boolean = candidateUserEpoch >= existingUserEpoch && candidateDeviceEpoch > existingDeviceEpoch

internal fun credentialEpochsMeetFences(
    userEpoch: Long,
    deviceEpoch: Long,
    userFence: Long,
    deviceFence: Long,
): Boolean = userEpoch > 0L && deviceEpoch > 0L && userEpoch >= userFence && deviceEpoch >= deviceFence
