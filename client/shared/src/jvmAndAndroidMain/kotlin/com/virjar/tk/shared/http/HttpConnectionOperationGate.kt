package com.virjar.tk.shared.http

import com.virjar.tk.shared.client.SessionBoundaryReentrantCloseException
import com.virjar.tk.shared.client.collapseSessionLifecycleFailures
import com.virjar.tk.shared.client.isFatalSessionLifecycleFailure
import com.virjar.tk.shared.client.mergeSessionLifecycleFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 面向会话持有的 [HttpURLConnection] 操作的 JVM/Android 共享硬关闭返回边界。
 *
 * 连接在首次网络 I/O 之前注册。[close] 封存准入，断开
 * 每个已注册的连接，并且直到每个已注册操作都已离开才返回。
 * 操作的首次 I/O 准入在注册之后会再次检查，因此输掉
 * 该竞争的操作会被断开，且不会触网。
 */
class HttpConnectionOperationGate(
    private val ownerName: String,
) {
    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()
    private val operations = linkedSetOf<Operation>()
    private val currentOperations = ThreadLocal<MutableMap<Operation, Int>>()

    private var phase = Phase.OPEN
    private var closingThread: Thread? = null
    private var disconnectSweepComplete = false
    private val closeFailures = mutableListOf<Throwable>()
    private var completedCloseFailure: Throwable? = null

    /**
     * 注册一个惰性的、已配置的连接。当注册或首次 I/O 准入
     * 输掉与 [close] 的竞争时，[closedFailure] 保留每个适配器的公开错误契约。
     */
    fun register(
        connection: HttpURLConnection,
        onRejectedDisconnectFailure: (Throwable) -> Unit = {},
        closedFailure: () -> Throwable,
    ): Operation {
        lock.withLock {
            if (phase == Phase.OPEN) {
                return Operation(connection, closedFailure).also(operations::add)
            }
        }

        var failure = try {
            closedFailure()
        } catch (creationFailure: Throwable) {
            creationFailure
        }
        disconnectRejected(connection)?.let { cleanupFailure ->
            failure = mergeSessionLifecycleFailures(failure, cleanupFailure)
            try {
                onRejectedDisconnectFailure(cleanupFailure)
            } catch (reportFailure: Throwable) {
                failure = mergeSessionLifecycleFailures(failure, reportFailure)
            }
        }
        throw failure
    }

    /**
     * 封存准入，中止所有已准入的连接，然后等待它们的操作退出。
     * 并发与重复的调用方会观察到同一个已完成的边界。
     */
    fun close() {
        val role = lock.withLock {
            when (phase) {
                Phase.OPEN -> {
                    phase = Phase.CLOSING
                    closingThread = Thread.currentThread()
                    CloseRole.Leader(operations.toList(), currentOperationSnapshot())
                }

                Phase.CLOSING -> {
                    if (closingThread === Thread.currentThread() || isInsideOperation()) {
                        CloseRole.Reentrant
                    } else {
                        CloseRole.Follower
                    }
                }

                Phase.CLOSED -> CloseRole.Complete
            }
        }

        when (role) {
            is CloseRole.Leader -> closeAsLeader(role)
            CloseRole.Follower -> awaitCompletedClose()?.let { throw it }
            CloseRole.Complete -> completedFailure()?.let { throw it }
            CloseRole.Reentrant -> throw reentrantCloseFailure()
        }
    }

    /** 供已经等待操作退出的外层生命周期 owner 使用的非阻塞终局检查。 */
    fun completedCloseFailureOrNull(): Throwable? = lock.withLock {
        if (phase == Phase.CLOSED) completedCloseFailure else null
    }

    private fun closeAsLeader(role: CloseRole.Leader) {
        role.operations.forEach { operation ->
            // 从回调中断开当前操作可能会获取一把原生锁，而
            // 那个回调恰好已经持有它。现在先封存，并让它保证在 finally 中执行断开，
            // 同时重入的调用方收到一个硬边界异常。
            if (operation in role.reentrantOperations) return@forEach
            operation.ensureDisconnected()?.let(::recordCloseFailure)
        }

        val reentrantFailure = lock.withLock {
            disconnectSweepComplete = true
            closingThread = null
            completeCloseIfDrainedLocked()
            stateChanged.signalAll()
            if (role.reentrantOperations.isNotEmpty()) {
                reentrantCloseFailureLocked()
            } else {
                null
            }
        }
        if (reentrantFailure != null) {
            completedFailure()?.let { completedFailure ->
                if (reentrantFailure.suppressed.none { it === completedFailure }) {
                    reentrantFailure.addSuppressed(completedFailure)
                }
            }
            throw reentrantFailure
        }
        awaitCompletedClose()?.let { throw it }
    }

    private fun reentrantCloseFailure(): HttpConnectionReentrantCloseException =
        lock.withLock { reentrantCloseFailureLocked() }

    private fun reentrantCloseFailureLocked(): HttpConnectionReentrantCloseException =
        HttpConnectionReentrantCloseException(
            ownerName = ownerName,
            knownFatalFailure = closeFailures.firstOrNull(::isFatalSessionLifecycleFailure),
        )

    private fun awaitCompletedClose(): Throwable? = lock.withLock {
        while (phase != Phase.CLOSED) stateChanged.awaitUninterruptibly()
        completedCloseFailure
    }

    private fun completedFailure(): Throwable? = lock.withLock {
        completedCloseFailure
    }

    private fun recordCloseFailure(failure: Throwable) = lock.withLock {
        if (closeFailures.none { it === failure }) closeFailures += failure
    }

    private fun completeCloseIfDrainedLocked() {
        if (phase != Phase.CLOSING || !disconnectSweepComplete || operations.isNotEmpty()) return
        completedCloseFailure = when {
            closeFailures.isEmpty() -> null
            closeFailures.any(::isFatalSessionLifecycleFailure) -> {
                collapseSessionLifecycleFailures(closeFailures)
            }
            else -> HttpConnectionCloseException(ownerName, closeFailures.toList())
        }
        phase = Phase.CLOSED
        stateChanged.signalAll()
    }

    private fun isInsideOperation(): Boolean = !currentOperations.get().isNullOrEmpty()

    private fun currentOperationSnapshot(): Set<Operation> =
        currentOperations.get()?.keys?.toSet().orEmpty()

    private fun enterOperationThread(operation: Operation) {
        val depths = currentOperations.get() ?: mutableMapOf<Operation, Int>().also(currentOperations::set)
        depths[operation] = (depths[operation] ?: 0) + 1
    }

    private fun leaveOperationThread(operation: Operation) {
        val depths = currentOperations.get() ?: return
        val remaining = (depths[operation] ?: 1) - 1
        if (remaining == 0) depths -= operation else depths[operation] = remaining
        if (depths.isEmpty()) currentOperations.remove()
    }

    inner class Operation internal constructor(
        private val connection: HttpURLConnection,
        private val closedFailure: () -> Throwable,
    ) {
        private var disconnectPhase = DisconnectPhase.PENDING
        private var disconnectThread: Thread? = null
        private var disconnectFailure: Throwable? = null

        fun <T> execute(
            beforeFirstIoAdmission: () -> Unit = {},
            block: () -> T,
        ): T {
            enterOperationThread(this)
            return try {
                executeBody(beforeFirstIoAdmission, block)
            } finally {
                leaveOperationThread(this)
            }
        }

        suspend fun <T> executeSuspending(
            additionalContext: CoroutineContext = EmptyCoroutineContext,
            beforeFirstIoAdmission: () -> Unit = {},
            block: suspend () -> T,
        ): T = coroutineScope {
            // 公共 Job.invokeOnCompletion 只在 Job 完全结束之后才运行。一个
            // 阻塞在 URLConnection I/O 中的 Job 无法到达那个状态，所以它不是取消
            // 钩子。这个子协程在 body 开始之前就进入 awaitCancellation；父协程
            // 取消会立即取消 watcher 并断开已准入的 socket。
            val bodyExited = AtomicBoolean(false)
            val cancellationFailure = AtomicReference<CancellationException?>()
            val operationContext = currentCoroutineContext()
            val cancellationWatcher = launch(
                Dispatchers.Unconfined,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    if (!bodyExited.get()) {
                        val ownerCancellation = try {
                            operationContext.ensureActive()
                            cancellation
                        } catch (ownerCancellation: CancellationException) {
                            ownerCancellation
                        }
                        // 在断开之前发布：中止一次阻塞读取通常会把
                        // transport 终局转成 IOException，而 body 可能在其
                        // 协程上下文在该 worker 线程上暴露取消之前就展开。
                        cancellationFailure.compareAndSet(null, ownerCancellation)
                        abort()
                    }
                    throw cancellation
                }
            }
            var executionEntered = false
            var dispatchFailure: Throwable? = null
            try {
                withContext(OperationThreadContext() + additionalContext) {
                    executionEntered = true
                    executeBodySuspending(
                        beforeFirstIoAdmission = beforeFirstIoAdmission,
                        cancellationFailure = cancellationFailure::get,
                        block = block,
                    )
                }
            } catch (failure: Throwable) {
                dispatchFailure = failure
                throw failure
            } finally {
                bodyExited.set(true)
                cancellationWatcher.cancel()
                // 一个已取消的上下文可能在其 body 开始之前就拒绝 withContext。那个请求
                // 仍然已注册，因此必须在 close 能够返回之前离开。
                if (!executionEntered) finish(dispatchFailure)
            }
        }

        /** 供取消回调使用的尽力而为提前中止；操作退出仍然会被等待。 */
        fun abort() {
            ensureDisconnected()
        }

        private fun <T> executeBody(
            beforeFirstIoAdmission: () -> Unit,
            block: () -> T,
        ): T {
            var primaryFailure: Throwable? = null
            try {
                beforeFirstIoAdmission()
                requireFirstIoAdmission()
                return block()
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                finish(primaryFailure)
            }
        }

        private suspend fun <T> executeBodySuspending(
            beforeFirstIoAdmission: () -> Unit,
            cancellationFailure: () -> CancellationException?,
            block: suspend () -> T,
        ): T {
            var primaryFailure: Throwable? = null
            try {
                beforeFirstIoAdmission()
                currentCoroutineContext().ensureActive()
                requireFirstIoAdmission()
                return block()
            } catch (failure: Throwable) {
                val terminal = cancellationFailure()?.let { cancellation ->
                    mergeSessionLifecycleFailures(failure, cancellation)
                } ?: try {
                    currentCoroutineContext().ensureActive()
                    failure
                } catch (cancellation: CancellationException) {
                    mergeSessionLifecycleFailures(failure, cancellation)
                }
                primaryFailure = terminal
                throw terminal
            } finally {
                finish(primaryFailure)
            }
        }

        /** 适配器执行其首次网络 I/O 之前的那个串行化点。 */
        private fun requireFirstIoAdmission() {
            lock.withLock {
                if (phase != Phase.OPEN) throw closedFailure()
            }
        }

        private fun finish(primaryFailure: Throwable?) {
            val cleanupFailure = ensureDisconnected()
            cleanupFailure?.let(::recordCloseFailure)
            lock.withLock {
                operations -= this
                completeCloseIfDrainedLocked()
                stateChanged.signalAll()
            }
            var terminalFailure = if (primaryFailure is HttpConnectionReentrantCloseException) {
                // 重入标记必须保持位于顶层，直到这个操作离开：外层 owner
                // 用它来避免把一个不完整的边界声明为 CLOSED。一旦 finally 排空，
                // 已经保留的致命错误就成为稳定的终局对象。
                primaryFailure.knownFatalFailure ?: primaryFailure
            } else {
                primaryFailure
            }
            if (cleanupFailure != null) {
                terminalFailure = mergeSessionLifecycleFailures(terminalFailure, cleanupFailure)
            }
            if (terminalFailure !== primaryFailure) terminalFailure?.let { throw it }
        }

        /** 恰好一个线程执行断开；close 与操作清理等待同一次尝试。 */
        internal fun ensureDisconnected(): Throwable? {
            val shouldDisconnect = lock.withLock {
                when (disconnectPhase) {
                    DisconnectPhase.PENDING -> {
                        disconnectPhase = DisconnectPhase.RUNNING
                        disconnectThread = Thread.currentThread()
                        true
                    }

                    DisconnectPhase.RUNNING -> {
                        if (disconnectThread === Thread.currentThread()) {
                            return HttpConnectionReentrantDisconnectException(ownerName)
                        }
                        while (disconnectPhase == DisconnectPhase.RUNNING) {
                            stateChanged.awaitUninterruptibly()
                        }
                        return disconnectFailure
                    }

                    DisconnectPhase.DONE -> return disconnectFailure
                }
            }
            check(shouldDisconnect)

            val failure = runCatching(connection::disconnect).exceptionOrNull()
            lock.withLock {
                disconnectFailure = failure
                disconnectThread = null
                disconnectPhase = DisconnectPhase.DONE
                stateChanged.signalAll()
            }
            return failure
        }

        private inner class OperationThreadContext :
            ThreadContextElement<Unit>,
            AbstractCoroutineContextElement(OperationContextKey()) {
            override fun updateThreadContext(context: CoroutineContext) {
                enterOperationThread(this@Operation)
            }

            override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
                leaveOperationThread(this@Operation)
            }
        }
    }

    private fun disconnectRejected(connection: HttpURLConnection): Throwable? =
        runCatching(connection::disconnect).exceptionOrNull()

    private enum class Phase { OPEN, CLOSING, CLOSED }
    private enum class DisconnectPhase { PENDING, RUNNING, DONE }

    private sealed interface CloseRole {
        data class Leader(
            val operations: List<Operation>,
            val reentrantOperations: Set<Operation>,
        ) : CloseRole

        data object Follower : CloseRole
        data object Complete : CloseRole
        data object Reentrant : CloseRole
    }

    private class OperationContextKey : CoroutineContext.Key<CoroutineContext.Element>
}

interface HttpConnectionReentrantCloseFailure

internal class HttpConnectionReentrantCloseException(
    ownerName: String,
    internal val knownFatalFailure: Throwable? = null,
) :
    SessionBoundaryReentrantCloseException(
        "$ownerName cannot close reentrantly from an admitted HTTP operation",
    ), HttpConnectionReentrantCloseFailure {
    init {
        knownFatalFailure?.let(::addSuppressed)
    }
}

private class HttpConnectionReentrantDisconnectException(ownerName: String) : IllegalStateException(
    "$ownerName attempted to join its own in-progress HTTP disconnect",
)

internal class HttpConnectionCloseException(
    ownerName: String,
    failures: List<Throwable>,
) : IllegalStateException("$ownerName failed to disconnect ${failures.size} HTTP connection(s)") {
    init {
        failures.forEach(::addSuppressed)
    }
}
