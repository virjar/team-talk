package com.virjar.tk.tcp

import com.virjar.tk.looper.Looper


/**
 * 我先用单例对象直接管理，后面引入DI框架再考虑转换为对象，
 * 否则现在到处传参数太乱了
 */
object ClientRegistry {
    private val workThread = Looper("client-registry").apply {
        start()
    }

    /**
     * uid -> user -> Device
     */
    private val userStore = mutableMapOf<String, UserAgentGroup>()

    var onLastDeviceOffline: ((uid: String) -> Unit)? = null

    fun register(imAgent: ImAgent) {
        workThread.post {
            val userGroup = userStore.computeIfAbsent(imAgent.uid) { UserAgentGroup(workThread) }
            userGroup.register(imAgent)
            imAgent.channel.closeFuture().addListener {
                imAgent.recorder.record { "connection close" }
                unregister(imAgent)
            }
        }
    }

    fun unregister(imAgent: ImAgent) {
        workThread.post {
            val user = userStore[imAgent.uid] ?: return@post
            user.unregister(imAgent)
            if (!user.online()) {
                userStore.remove(imAgent.uid)
                onLastDeviceOffline?.invoke(imAgent.uid)
            }
        }
    }

    suspend fun isUserOnline(userId: String): Boolean {
        return workThread.suspendAwait {
            val userAgentGroup = userStore[userId] ?: return@suspendAwait false
            return@suspendAwait userAgentGroup.online()
        }
    }

    suspend fun kickDevice(uid: String, deviceId: String) {
        return workThread.suspendAwait {
            val userAgentGroup = userStore[uid] ?: return@suspendAwait
            userAgentGroup.kick(deviceId)
        }
    }

    suspend fun getAgentsByUid(uid: String): List<ImAgent> {
        return workThread.suspendAwait {
            val userAgentGroup = userStore[uid] ?: return@suspendAwait emptyList()
            return@suspendAwait userAgentGroup.allAgent.values.toList()
        }
    }

    fun doWithUserAgentGroup(uid: String, func: (UserAgentGroup) -> Unit) {
        workThread.post {
            val userAgentGroup = userStore[uid] ?: return@post
            func(userAgentGroup)
        }
    }

    suspend fun getOnlineUserCount(): Int {
        return workThread.suspendAwait {
            userStore.size
        }
    }

    suspend fun getOnlineConnectionCount(): Int {
        return workThread.suspendAwait {
            userStore.values.sumOf { it.allAgent.size }
        }
    }

    suspend fun getOnlineDeviceIds(uid: String): Set<String> {
        return workThread.suspendAwait {
            val userAgentGroup = userStore[uid] ?: return@suspendAwait emptySet()
            userAgentGroup.allAgent.keys.toSet()
        }
    }
}


class UserAgentGroup(val workThread: Looper) {
    val allAgent = mutableMapOf<String, ImAgent>()

    fun register(imAgent: ImAgent) {
        workThread.checkLooper()
        val old = allAgent.put(imAgent.deviceId, imAgent) ?: return

        old.recorder.record { "duplicate client registered , this client will be kick after 30s" }
        workThread.postDelay({
            old.kick()
        }, 30_000)
    }

    fun unregister(imAgent: ImAgent) {
        workThread.checkLooper()
        allAgent.remove(imAgent.deviceId)
    }

    fun online(): Boolean {
        return allAgent.isNotEmpty()
    }

    fun kick(deviceId: String) {
        workThread.checkLooper()
        allAgent.remove(deviceId)?.kick()
    }
}
