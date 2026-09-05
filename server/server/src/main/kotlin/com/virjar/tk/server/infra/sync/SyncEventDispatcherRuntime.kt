package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.runtime.BoundedCloseGate
import com.virjar.tk.server.runtime.awaitBlocking
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 进程本地持久 sync worker 的生命周期阶段。 */
enum class SyncEventDispatcherPhase {
    NOT_STARTED,
    STARTING,
    READY,
    FAILED,
    STOPPING,
    STOPPED,
}

/** 一个 worker 轮次是有界的；强制/溢出扫描会向运行时请求另一个轮次。 */
internal enum class SyncEventDispatchPassResult {
    COMPLETE,
    MORE_REQUIRED,
}

/**
 * 启动接线与健康检查使用的查询结果。
 *
 * [detail] 是固定的运营描述。worker 的异常对象、类型、消息、SQL、
 * 载荷与标识符保持私有，仅供日志与生命周期传播使用。
 */
class SyncEventDispatcherSnapshot internal constructor(
    val phase: SyncEventDispatcherPhase,
    val live: Boolean,
    val ready: Boolean,
    val detail: String?,
)

private data class SyncEventDispatcherRuntimeState(
    val phase: SyncEventDispatcherPhase,
    val terminalFailure: Throwable? = null,
    val publicDetail: String? = null,
)

/**
 * 独立于 PostgreSQL 拥有 worker 终结状态，使生命周期语义保持纯可测试。
 *
 * 意外的 worker 失败立即记录，为之后的 [close] 重放而保留，并
 * 反映在 [snapshot] 中。原始失败从 worker 中重新抛出；协程
 * 异常处理器只提供自有后台协程所需的显式日志边界。
 */
internal class SyncEventDispatcherRuntime(
    workerDispatcher: CoroutineDispatcher,
    private val scanIntervalMillis: Long,
    shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
    private val runPass: suspend (
        scanDatabase: Boolean,
        requireScanSuccess: Boolean,
    ) -> SyncEventDispatchPassResult,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SyncEventDispatcher::class.java)
    private val lifecycle = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, failure ->
        logger.error("Durable sync dispatcher worker terminated", failure)
    }
    private val scope = CoroutineScope(lifecycle + workerDispatcher + exceptionHandler)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val state = AtomicReference(
        SyncEventDispatcherRuntimeState(SyncEventDispatcherPhase.NOT_STARTED),
    )
    private val startupScanCompleted = CompletableDeferred<Unit>()
    private val shutdownCancellation = CancellationException("SyncEventDispatcher lifecycle is closing")
    private val startCloseLock = Any()
    private val started = AtomicBoolean(false)
    private val closeStarted = AtomicBoolean(false)
    private val lifecycleFinished = CountDownLatch(1)
    private val closeGate = BoundedCloseGate(
        ownerName = "SyncEventDispatcher",
        timeoutMillis = shutdownTimeoutMillis,
        onTerminal = { terminalFailure ->
            state.set(
                SyncEventDispatcherRuntimeState(
                    phase = SyncEventDispatcherPhase.STOPPED,
                    terminalFailure = terminalFailure,
                ),
            )
        },
    )

    init {
        require(scanIntervalMillis > 0L) { "scanIntervalMillis must be positive" }
        lifecycle.invokeOnCompletion { lifecycleFinished.countDown() }
    }

    fun start() {
        synchronized(startCloseLock) {
            check(!closeStarted.get()) { "SyncEventDispatcher is already closed" }
            val current = state.get()
            when (current.phase) {
                SyncEventDispatcherPhase.STARTING,
                SyncEventDispatcherPhase.READY,
                -> return

                SyncEventDispatcherPhase.FAILED ->
                    error("SyncEventDispatcher worker has already failed")

                SyncEventDispatcherPhase.NOT_STARTED -> Unit
                SyncEventDispatcherPhase.STOPPING,
                SyncEventDispatcherPhase.STOPPED,
                -> error("SyncEventDispatcher is already closed")
            }
            check(state.compareAndSet(
                current,
                SyncEventDispatcherRuntimeState(SyncEventDispatcherPhase.STARTING),
            )) { "SyncEventDispatcher lifecycle changed during start" }
            started.set(true)
            scope.launch { runWorker() }.invokeOnCompletion(::completePreStartCancellation)
        }
    }

    suspend fun awaitStartupScan() {
        check(started.get()) { "SyncEventDispatcher has not started" }
        startupScanCompleted.await()
    }

    fun requestPass() {
        if (acceptsSignals()) wake.trySend(Unit)
    }

    fun acceptsSignals(): Boolean = when (state.get().phase) {
        SyncEventDispatcherPhase.NOT_STARTED,
        SyncEventDispatcherPhase.STARTING,
        SyncEventDispatcherPhase.READY,
        -> true

        SyncEventDispatcherPhase.FAILED,
        SyncEventDispatcherPhase.STOPPING,
        SyncEventDispatcherPhase.STOPPED,
        -> false
    }

    fun snapshot(): SyncEventDispatcherSnapshot {
        val current = state.get()
        return when (current.phase) {
            SyncEventDispatcherPhase.NOT_STARTED -> SyncEventDispatcherSnapshot(
                phase = current.phase,
                live = false,
                ready = false,
                detail = "Durable sync dispatcher has not started",
            )
            SyncEventDispatcherPhase.STARTING -> SyncEventDispatcherSnapshot(
                phase = current.phase,
                live = true,
                ready = false,
                detail = "Durable sync startup scan has not completed",
            )
            SyncEventDispatcherPhase.READY -> SyncEventDispatcherSnapshot(
                phase = current.phase,
                live = true,
                ready = true,
                detail = null,
            )
            SyncEventDispatcherPhase.FAILED -> SyncEventDispatcherSnapshot(
                phase = current.phase,
                live = false,
                ready = false,
                detail = current.publicDetail ?: "Durable sync dispatcher is unavailable",
            )
            SyncEventDispatcherPhase.STOPPING -> SyncEventDispatcherSnapshot(
                phase = current.phase,
                live = false,
                ready = false,
                detail = "Durable sync dispatcher is stopping",
            )
            SyncEventDispatcherPhase.STOPPED -> SyncEventDispatcherSnapshot(
                phase = current.phase,
                live = false,
                ready = false,
                detail = "Durable sync dispatcher is stopped",
            )
        }
    }

    private suspend fun runWorker() {
        var startupSucceeded = false
        try {
            // 进程可能在 PostgreSQL 提交之后、内存唤醒之前退出。此扫描
            // 必须在就绪之前成功；与之后的维护扫描不同，失败是终结性的。
            var startupProgress: SyncEventDispatchPassResult
            do {
                startupProgress = runPass(true, true)
                currentCoroutineContext().ensureActive()
            } while (startupProgress == SyncEventDispatchPassResult.MORE_REQUIRED)
            if (!markReady()) {
                throw shutdownCancellation
            }
            startupSucceeded = true
            startupScanCompleted.complete(Unit)

            while (currentCoroutineContext().isActive) {
                val wakeResult = withTimeoutOrNull(scanIntervalMillis) { wake.receiveCatching() }
                // 关闭准入通道是自有的关闭信号，不是 worker 故障。
                // receiveCatching 还消除了 close-vs-cancel 竞态，
                // 否则一次普通关闭会变成 ClosedReceiveChannelException。
                if (wakeResult?.isClosed == true) break
                val signaled = wakeResult?.isSuccess == true
                val progress = runPass(!signaled, false)
                currentCoroutineContext().ensureActive()
                if (progress == SyncEventDispatchPassResult.MORE_REQUIRED) {
                    // 通过合并的准入通道重新进入，使每个数据库页都是
                    // 一个独立的、可取消的 worker 轮次，具有相同的固定工作预算。
                    wake.trySend(Unit)
                }
            }
        } catch (failure: Throwable) {
            if (!isOwnedShutdownCancellation(failure)) {
                recordWorkerFailure(failure, startup = !startupSucceeded)
            }
            if (!startupScanCompleted.isCompleted) startupScanCompleted.completeExceptionally(failure)
            throw failure
        }
    }

    private fun markReady(): Boolean {
        while (true) {
            val current = state.get()
            if (current.phase != SyncEventDispatcherPhase.STARTING) return false
            if (state.compareAndSet(
                current,
                SyncEventDispatcherRuntimeState(SyncEventDispatcherPhase.READY),
            )) return true
        }
    }

    private fun recordWorkerFailure(failure: Throwable, startup: Boolean) {
        closeGate.recordFailure(failure)
        while (true) {
            val current = state.get()
            if (current.phase == SyncEventDispatcherPhase.FAILED) return
            if (current.phase == SyncEventDispatcherPhase.STOPPED) return
            if (current.phase == SyncEventDispatcherPhase.STOPPING) {
                val terminal = mergeRuntimeFailure(current.terminalFailure, failure)
                if (state.compareAndSet(current, current.copy(terminalFailure = terminal))) return
                continue
            }
            val failed = SyncEventDispatcherRuntimeState(
                phase = SyncEventDispatcherPhase.FAILED,
                terminalFailure = failure,
                publicDetail = if (startup) {
                    "Durable sync startup scan failed"
                } else {
                    "Durable sync dispatcher worker terminated"
                },
            )
            if (state.compareAndSet(current, failed)) return
        }
    }

    /** 覆盖 launch 已创建、但 [runWorker] 尚未进入其 try 块时的取消。 */
    private fun completePreStartCancellation(cause: Throwable?) {
        if (startupScanCompleted.isCompleted) return
        val terminal = cause ?: IllegalStateException("SyncEventDispatcher worker stopped before startup scan")
        if (!isOwnedShutdownCancellation(terminal)) recordWorkerFailure(terminal, startup = true)
        startupScanCompleted.completeExceptionally(terminal)
    }

    override fun close() {
        val attempt = synchronized(startCloseLock) {
            closeGate.begin().also { selected ->
                if (selected is BoundedCloseGate.Attempt.Owner) {
                    closeStarted.set(true)
                    transitionToStopping()
                    // 在取消之前先吊销唤醒准入，使晚到的信号无法
                    // 在依赖存储开始其自身关闭时保留工作。
                    wake.close()
                }
            }
        }

        val terminalFailure = when (attempt) {
            is BoundedCloseGate.Attempt.Owner -> {
                lifecycle.cancel(shutdownCancellation)
                val completed = attempt.deadline.awaitBlocking(lifecycleFinished) { interrupted ->
                    closeGate.recordFailure(interrupted)
                }
                if (completed) closeGate.complete(attempt) else closeGate.expire(attempt.deadline)
            }

            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollowerBlocking(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }

        terminalFailure?.let { throw it }
    }

    private fun isOwnedShutdownCancellation(failure: Throwable): Boolean {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            if (current === shutdownCancellation) return true
            current = current?.cause
            if (current == null) return false
        }
        return false
    }

    private fun transitionToStopping() {
        while (true) {
            val current = state.get()
            if (
                current.phase == SyncEventDispatcherPhase.STOPPING ||
                current.phase == SyncEventDispatcherPhase.STOPPED
            ) return
            if (state.compareAndSet(
                current,
                current.copy(
                    phase = SyncEventDispatcherPhase.STOPPING,
                    publicDetail = null,
                ),
            )) return
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 16
        const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
    }
}
