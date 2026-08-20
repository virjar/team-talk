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
 * uid → 已完成持久事件同步的连接。认证成功本身不会把连接放进本表。
 *
 * 激活和实时推送都在同一个 Looper 上串行；[activate] 先写 SYNC_READY 再公开连接。
 * SyncEventService 还会在 per-user delivery gate 内调用它，因此 gate 释放后的第一个
 * 实时 NOTIFY 必然排在 SYNC_READY 之后。
 */
class ClientRegistry : OnlineSessions {
    private val logger = LoggerFactory.getLogger("ClientRegistry")
    private val workThread = Looper("client-registry").apply { start() }
    private val acceptingWork = AtomicBoolean(true)

    /** uid → (deviceId → ImAgent)；只包含已经完成同步的连接。 */
    private val userAgents = mutableMapOf<String, MutableMap<String, ImAgent>>()

    var onFirstDeviceOnline: ((uid: String) -> Unit)? = null
    var onLastDeviceOffline: ((uid: String) -> Unit)? = null

    /**
     * 发送同步完成标记，并仅在写入已排队后公开连接用于 live delivery。
     * 返回 false 表示连接已关闭，调用方必须终止本轮同步。
     */
    suspend fun activate(agent: ImAgent): Boolean = workThread.suspendAwait {
        if (!agent.isActive) return@suspendAwait false

        // State transition and publication happen on this same serial looper. If the connection
        // closes concurrently, channelInactive queues unregister behind this activation.
        if (!agent.markReadyForLiveActivation()) return@suspendAwait false

        // 先写 ready，再注册。SyncEventService 的用户门闩保证这两步之间没有同用户 push。
        agent.write(SyncReadyPayload)
        val devices = userAgents.getOrPut(agent.uid) { mutableMapOf() }
        val wasOffline = devices.isEmpty()
        val old = devices.put(agent.deviceId, agent)
        if (old != null && old !== agent) {
            logger.debug("Duplicate device uid=${agent.uid} deviceId=${agent.deviceId}, kicking old after 30s")
            workThread.postDelay({
                // 只有替代连接仍是当前设备连接时才踢旧连接。
                if (userAgents[agent.uid]?.get(agent.deviceId) === agent) old.kick()
            }, 30_000)
        }
        if (wasOffline) onFirstDeviceOnline?.invoke(agent.uid)
        logger.debug("Activated agent for uid=${agent.uid} deviceId=${agent.deviceId}, devices=${devices.size}")
        true
    }

    fun unregister(agent: ImAgent) {
        if (!acceptingWork.get()) return
        workThread.post {
            val devices = userAgents[agent.uid] ?: return@post
            // 被替代的旧连接晚到 channelInactive 时不能把当前新连接删掉。
            if (devices[agent.deviceId] !== agent) return@post
            devices.remove(agent.deviceId)
            if (devices.isEmpty()) {
                userAgents.remove(agent.uid)
                onLastDeviceOffline?.invoke(agent.uid)
            }
            logger.debug("Unregistered agent for uid=${agent.uid} deviceId=${agent.deviceId}")
        }
    }

    /** 实时事件的唯一投递入口；避免“先取连接快照、后写入”穿透激活屏障。 */
    suspend fun push(uid: String, notify: NotifyPayload) {
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
            val devices = userAgents[uid] ?: return@suspendAwait
            devices.values.toList().forEach { it.kick() }
        }
    }

    override suspend fun onlineUids(): Set<String> {
        return workThread.suspendAwait {
            userAgents.keys.toSet()
        }
    }

    override suspend fun kickDevice(uid: String, deviceId: String) {
        workThread.suspendAwait {
            val devices = userAgents[uid] ?: return@suspendAwait
            val agent = devices.remove(deviceId)
            agent?.kick()
            if (devices.isEmpty()) {
                userAgents.remove(uid)
                onLastDeviceOffline?.invoke(uid)
            }
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
                    onFirstDeviceOnline = null
                    onLastDeviceOffline = null
                }
            }
        } finally {
            workThread.stop()
        }
    }
}
