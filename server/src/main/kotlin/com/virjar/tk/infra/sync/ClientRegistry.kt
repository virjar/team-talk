package com.virjar.tk.infra.sync

import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.executor.Looper
import org.slf4j.LoggerFactory

/**
 * uid → ImAgent 列表映射。支持多设备同时在线。
 *
 * 所有状态操作通过 Looper 线程序列化执行，无需 ConcurrentHashMap。
 * 对外提供 suspend 方法供协程安全调用。
 */
class ClientRegistry {
    private val logger = LoggerFactory.getLogger("ClientRegistry")
    private val workThread = Looper("client-registry").apply { start() }

    /** uid → (deviceId → ImAgent) */
    private val userAgents = mutableMapOf<String, MutableMap<String, ImAgent>>()

    var onLastDeviceOffline: ((uid: String) -> Unit)? = null

    fun register(agent: ImAgent) {
        workThread.post {
            val devices = userAgents.computeIfAbsent(agent.uid) { mutableMapOf() }
            val old = devices.put(agent.deviceId, agent)
            if (old != null) {
                logger.debug("Duplicate device uid=${agent.uid} deviceId=${agent.deviceId}, kicking old after 30s")
                workThread.postDelay({
                    if (devices[agent.deviceId] === old) {
                        old.kick()
                    }
                }, 30_000)
            }
            logger.debug("Registered agent for uid=${agent.uid} deviceId=${agent.deviceId}, devices=${devices.size}")
        }
    }

    fun unregister(agent: ImAgent) {
        workThread.post {
            val devices = userAgents[agent.uid] ?: return@post
            devices.remove(agent.deviceId)
            if (devices.isEmpty()) {
                userAgents.remove(agent.uid)
                onLastDeviceOffline?.invoke(agent.uid)
            }
            logger.debug("Unregistered agent for uid=${agent.uid} deviceId=${agent.deviceId}")
        }
    }

    suspend fun getAgents(uid: String): List<ImAgent> {
        return workThread.suspendAwait {
            userAgents[uid]?.values?.toList() ?: emptyList()
        }
    }

    suspend fun isOnline(uid: String): Boolean {
        return workThread.suspendAwait {
            val devices = userAgents[uid]
            devices != null && devices.isNotEmpty()
        }
    }

    suspend fun onlineUids(): Set<String> {
        return workThread.suspendAwait {
            userAgents.keys.toSet()
        }
    }

    suspend fun kickDevice(uid: String, deviceId: String) {
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
        workThread.stop()
    }
}
