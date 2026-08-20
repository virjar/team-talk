package com.virjar.tk.protocol.executor

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.trace.Recorder
import java.lang.ref.WeakReference

/**
 * ImAgent 的安全门面：通过弱引用持有 ImAgent，只暴露功能方法，不暴露对象本身。
 *
 * GC 安全原理：
 * - ImAgentFacade 内部通过 WeakReference 持有 agent
 * - 协程挂起期间，如果 TCP 连接断开，GC 可以正常回收 ImAgent 和 Netty Channel（包括 DirectByteBuffer 堆外内存）
 * - 协程 resume 时，send/write 方法检查 agent 存活状态，已销毁则抛 AgentDisposedException 取消协程
 *
 * [uid]、[deviceId] 在创建时从 agent 提取，后续使用不依赖 agent 存活。
 */
class ImAgentFacade(agent: ImAgent) {
    private val ref = WeakReference(agent)
    private val channelId = agent.channelId

    val uid: String = agent.uid
    val deviceId: String = agent.deviceId
    val recorder: Recorder = agent.recorder

    val isActive: Boolean
        get() = ref.get()?.isActive == true

    fun send(proto: IProto) {
        val agent = ref.get() ?: throw AgentDisposedException("Agent GC'd: $channelId")
        if (!agent.isActive) throw AgentDisposedException("Agent disconnected: $channelId")
        agent.write(proto)
    }

    /** Close an invalid protocol session without retaining the Netty handler across suspension. */
    fun closeConnection() {
        ref.get()?.closeConnection()
    }

    /** Complete auth state transition through the weak connection boundary. */
    fun completeAuthentication(uid: String, deviceId: String): Boolean =
        ref.get()?.completeAuthentication(uid, deviceId) == true

    /** Reset page admission after the server proves that the submitted cursor is foreign. */
    fun resetSyncAdmission() {
        ref.get()?.resetSyncAdmission()
    }

    /** Refresh the bounded synchronization window without capturing the handler in an IO task. */
    fun refreshSyncStallTimeout() {
        ref.get()?.refreshSyncStallTimeout()
    }

    /** Called by the EventLoop timer, which itself retains only this weak facade. */
    fun closeIfSyncStalled(generation: Long, timeoutSeconds: Long) {
        ref.get()?.closeIfSyncStalled(generation, timeoutSeconds)
    }

    /** Authentication timer counterpart; the scheduled task retains no channel context. */
    fun closeIfAuthenticationStalled(timeoutSeconds: Long) {
        ref.get()?.closeIfAuthenticationStalled(timeoutSeconds)
    }

    /**
     * Runs the final live-session activation against the still-current connection.
     *
     * The facade intentionally keeps the agent behind a weak reference; callers provide the
     * infrastructure operation instead of retaining the Netty handler across suspension.
     */
    suspend fun activateLive(activate: suspend (ImAgent) -> Boolean): Boolean {
        val agent = ref.get() ?: return false
        if (!agent.isActive) return false
        return activate(agent)
    }
}
