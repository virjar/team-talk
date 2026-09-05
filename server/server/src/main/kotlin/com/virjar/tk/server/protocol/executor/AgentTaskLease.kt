package com.virjar.tk.server.protocol.executor

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * 连接拥有的取消信号，复制到排队的工作中，而不保留其 ImAgent。
 *
 * 租约没有指回 Netty 处理器或通道的引用。关闭连接完成
 * 一个稳定的终结信号；因此每个排队或运行中的请求都能观察到相同的
 * 取消，而长寿命 IO worker 仍只归 [IOExecutor] 拥有。
 */
internal class AgentTaskLease(private val sessionId: String) {
    private val terminal = AtomicReference<AgentDisposedException?>(null)
    private val terminalSignal = CompletableDeferred<AgentDisposedException>()

    val isActive: Boolean get() = terminal.get() == null

    fun ensureActive() {
        terminal.get()?.let { throw it }
    }

    suspend fun awaitCancellation(): AgentDisposedException = terminalSignal.await()

    fun cancel() {
        val cancellation = AgentDisposedException("Agent disconnected: $sessionId")
        if (terminal.compareAndSet(null, cancellation)) {
            terminalSignal.complete(cancellation)
        }
    }
}
