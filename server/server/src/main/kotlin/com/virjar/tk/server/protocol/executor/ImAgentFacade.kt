package com.virjar.tk.server.protocol.executor

import com.virjar.tk.protocol.IProto
import com.virjar.tk.server.protocol.connection.ImAgent
import com.virjar.tk.server.protocol.trace.Recorder
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
    internal val taskLease: AgentTaskLease = agent.taskLease
    val sessionId: String = agent.sessionId

    val uid: String = agent.uid
    val deviceId: String = agent.deviceId
    val deviceCredentialEpoch: Long = agent.deviceCredentialEpoch
    val negotiatedProtocolVersion = agent.negotiatedProtocolVersion
    val recorder: Recorder = agent.recorder

    val isActive: Boolean
        get() = taskLease.isActive && ref.get()?.isActive == true

    /** 准入/出队守卫，绝不把弱 agent 引用提升为任务所有权。 */
    internal fun ensureTaskActive() {
        taskLease.ensureActive()
        if (ref.get()?.isActive != true) throw AgentDisposedException("Agent unavailable: $sessionId")
    }

    val isCredentialTerminal: Boolean
        get() = ref.get()?.isCredentialTerminal != false

    fun send(proto: IProto) {
        val agent = ref.get() ?: throw AgentDisposedException("Agent GC'd: $sessionId")
        if (!agent.isActive) throw AgentDisposedException("Agent disconnected: $sessionId")
        agent.write(proto)
    }

    /** 在关闭凭据已失效的连接之前，刷出一个终结响应。 */
    fun sendAndClose(proto: IProto) {
        val agent = ref.get() ?: throw AgentDisposedException("Agent GC'd: $sessionId")
        if (!agent.isActive) throw AgentDisposedException("Agent disconnected: $sessionId")
        agent.writeAndClose(proto)
    }

    /** 关闭无效协议会话，而不在挂起期间保留 Netty 处理器。 */
    fun closeConnection() {
        ref.get()?.closeConnection()
    }

    /** 通过弱连接边界完成认证状态迁移。 */
    fun completeAuthentication(
        uid: String,
        deviceId: String,
        userCredentialEpoch: Long,
        deviceCredentialEpoch: Long,
    ): Boolean = ref.get()?.completeAuthentication(
        uid,
        deviceId,
        userCredentialEpoch,
        deviceCredentialEpoch,
    ) == true

    /** 在服务器证明提交的游标不属于本连接后，重置分页准入。 */
    fun resetSyncAdmission() {
        ref.get()?.resetSyncAdmission()
    }

    /** 刷新有界同步窗口，而不在 IO 任务中捕获处理器。 */
    fun refreshSyncStallTimeout() {
        ref.get()?.refreshSyncStallTimeout()
    }

    /** 在服务器暴露成功的 AUTH 响应之前，登记一个绑定 epoch 的身份。 */
    internal suspend fun <T> admitAuthenticated(admit: suspend (ImAgent) -> T): T? {
        val agent = ref.get() ?: return null
        if (!agent.isActive) return null
        return admit(agent)
    }

    /** 由 EventLoop 定时器调用，定时器本身只保留此弱门面。 */
    fun closeIfSyncStalled(generation: Long, timeoutSeconds: Long) {
        ref.get()?.closeIfSyncStalled(generation, timeoutSeconds)
    }

    /** 认证定时器的对应物；被调度的任务不保留任何通道上下文。 */
    fun closeIfAuthenticationStalled(timeoutSeconds: Long) {
        ref.get()?.closeIfAuthenticationStalled(timeoutSeconds)
    }

    /**
     * 针对仍然当前的连接运行最终的活跃会话激活。
     *
     * 门面刻意让 agent 保持在弱引用之后；调用方提供
     * 基础设施操作，而不是在挂起期间保留 Netty 处理器。
     */
    suspend fun activateLive(activate: suspend (ImAgent) -> Boolean): Boolean {
        val agent = ref.get() ?: return false
        if (!agent.isActive) return false
        return activate(agent)
    }
}
