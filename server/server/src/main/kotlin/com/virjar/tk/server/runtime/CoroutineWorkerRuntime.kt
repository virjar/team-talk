package com.virjar.tk.server.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch

/**
 * Application 拥有的后台 worker 运行时骨架：SupervisorJob + IO scope + 有界关闭。
 *
 * 关闭纪律（与 ServerResourceOwner 的 dependency barrier 配合）：先取消后续派发，再在
 * 截止时间内等待全部 worker 终止；超时报告失败并保留 [workersTerminated] = false，
 * 让屏障阻止更早获取的依赖（存储/连接）先行释放。子类经 [scope] 派发工作并保留各自的
 * 异常终结语义；取消后的提交是不执行的无害空操作。
 */
internal abstract class CoroutineWorkerRuntime(
    runtimeName: String,
    shutdownTimeoutMillis: Long,
) : AutoCloseable {
    private val lifecycle = SupervisorJob()
    protected val scope = CoroutineScope(lifecycle + Dispatchers.IO)
    private val finished = CountDownLatch(1)
    private val closeGate = BoundedCloseGate(runtimeName, shutdownTimeoutMillis, onTerminal = {})

    /** 全部 worker 已真实退出；关闭屏障据此放行依赖释放。 */
    val workersTerminated: Boolean get() = finished.count == 0L

    init {
        lifecycle.invokeOnCompletion { finished.countDown() }
    }

    override fun close() {
        val failure = when (val attempt = closeGate.begin()) {
            is BoundedCloseGate.Attempt.Owner -> {
                lifecycle.cancel(CancellationException("${closeRuntimeName()} is closing"))
                val completed = attempt.deadline.awaitBlocking(finished) { closeGate.recordFailure(it) }
                if (completed) closeGate.complete(attempt) else closeGate.expire(attempt.deadline)
            }
            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollowerBlocking(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }
        failure?.let { throw it }
    }

    /** 关闭取消消息里的运行时名称；默认取类简单名。 */
    protected open fun closeRuntimeName(): String = this::class.simpleName ?: "worker runtime"
}
