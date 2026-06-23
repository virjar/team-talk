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
}
