package com.virjar.tk.server.application

import com.virjar.tk.server.domain.presence.PresenceService
import com.virjar.tk.server.domain.presence.PresenceObserverLease
import com.virjar.tk.server.domain.presence.PresenceTransition
import com.virjar.tk.server.domain.presence.PresenceTransitionObserver
import com.virjar.tk.server.domain.presence.PresenceTransitionSource
import com.virjar.tk.server.runtime.BoundedCloseGate
import com.virjar.tk.server.runtime.await
import com.virjar.tk.server.runtime.awaitBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 将串行的连接状态迁移源桥接到较慢的 presence 扇出。
 *
 * 迁移回调绝不允许阻塞：扇出最终会重新进入连接拥有者去推送
 * 每个瞬态事件，因此把有界通道的背压传播给那个串行 actor 会死锁。
 * 所以邮箱对每个 uid 只保留最新等待状态，对等待中和处理中的工作
 * 设有唯一 uid 数的硬上限，并且唤醒一个独立的串行 worker，
 * 而不在唤醒通道中携带工作。
 */
class PresenceCoordinator internal constructor(
    private val transitions: PresenceTransitionSource,
    workerDispatcher: CoroutineDispatcher,
    mailboxCapacity: Int,
    private val broadcastPresence: suspend (PresenceTransition) -> Unit,
    shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
) : AutoCloseable {
    constructor(
        transitions: PresenceTransitionSource,
        presenceService: PresenceService,
    ) : this(
        transitions = transitions,
        workerDispatcher = Dispatchers.IO,
        mailboxCapacity = DEFAULT_MAILBOX_CAPACITY,
        broadcastPresence = presenceService::broadcast,
    )

    private val logger = LoggerFactory.getLogger(PresenceCoordinator::class.java)
    private val lifecycle = SupervisorJob()
    private val shutdownCancellation = CancellationException("PresenceCoordinator lifecycle is closing")
    private val exceptionHandler = CoroutineExceptionHandler { _, failure ->
        logger.error("Presence coordinator worker terminated", failure)
    }
    private val scope = CoroutineScope(lifecycle + workerDispatcher + exceptionHandler)
    private val mailbox = PresenceChangeMailbox(mailboxCapacity)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lifecycleLock = Any()
    private val lifecycleFinished = CountDownLatch(1)
    private val lifecycleFinishedAsync = CompletableDeferred<Unit>()
    private val stopped = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<Throwable?>(null)
    private val closeGate = BoundedCloseGate(
        ownerName = "PresenceCoordinator",
        timeoutMillis = shutdownTimeoutMillis,
        onTerminal = { failure ->
            terminalFailure.set(failure)
            stopped.set(true)
        },
    )
    private var started = false
    private var closed = false
    private var observerLease: PresenceObserverLease? = null
    private var nextDropReportAt = 1L
    private var lastReportedDropCount = 0L

    internal val pendingChangeCount: Int get() = mailbox.pendingSize
    internal val droppedChangeCount: Long get() = mailbox.droppedNewUidCount
    internal val isStopped: Boolean get() = stopped.get()
    internal val closeTerminalFailure: Throwable? get() = terminalFailure.get()

    init {
        lifecycle.invokeOnCompletion {
            lifecycleFinished.countDown()
            lifecycleFinishedAsync.complete(Unit)
        }
    }

    fun start() {
        var startupAttempted = false
        try {
            synchronized(lifecycleLock) {
                check(!closed) { "PresenceCoordinator is already closed" }
                if (started) return
                startupAttempted = true
                observerLease = transitions.installPresenceObserver(
                    PresenceTransitionObserver(::enqueue),
                )
                started = true
                val worker = scope.launch { runWorker() }
                worker.invokeOnCompletion(::retainCompletionFailure)
                if (worker.isCompleted) {
                    terminalFailure.get()?.let { throw it }
                    error("Presence coordinator worker stopped during start")
                }
            }
        } catch (failure: Throwable) {
            if (!startupAttempted) throw failure
            closeGate.recordFailure(failure)
            val terminal = runCatching { close() }.exceptionOrNull()
            throw terminal ?: failure
        }
    }

    private fun enqueue(change: PresenceTransition) {
        when (mailbox.offer(change)) {
            PresenceOfferResult.ACCEPTED,
            PresenceOfferResult.DROPPED_CAPACITY,
            -> wake.trySend(Unit)

            PresenceOfferResult.CLOSED -> Unit
        }
    }

    private suspend fun runWorker() {
        try {
            for (ignored in wake) {
                reportDropsIfNeeded()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val change = mailbox.poll() ?: break
                    try {
                        try {
                            broadcastPresence(change)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            logger.warn(
                                "Failed to broadcast {} presence for uid={}",
                                if (change.online) "online" else "offline",
                                change.uid,
                                error,
                            )
                        }
                    } finally {
                        mailbox.complete(change.uid)
                    }
                }
                reportDropsIfNeeded()
            }
        } catch (failure: Throwable) {
            if (!isOwnedShutdownCancellation(failure)) {
                closeGate.recordFailure(failure)
                // CoroutineExceptionHandler 刻意忽略 CancellationException，但一个
                // 非自有的 worker 取消仍是终结性的生命周期失败。
                if (failure is CancellationException) {
                    logger.error("Presence coordinator worker terminated", failure)
                }
            }
            throw failure
        }
    }

    /** 覆盖 [runWorker] 进入其保留边界之前启动即失败的情况。 */
    private fun retainCompletionFailure(cause: Throwable?) {
        if (cause == null || isOwnedShutdownCancellation(cause)) return
        closeGate.recordFailure(cause)
        // 完成处理器只在该 worker 终结后运行，因此吊销生产者并
        // 发布其保留的失败，不可能因 worker 从自身 join 自己而死锁。
        when (val attempt = initiateClose()) {
            is BoundedCloseGate.Attempt.Owner -> closeGate.complete(attempt)
            is BoundedCloseGate.Attempt.Follower,
            is BoundedCloseGate.Attempt.Terminal,
            -> Unit
        }
    }

    /** 丢弃上报在 worker 上运行，绝不在迁移源那个对延迟敏感的 actor 上运行。 */
    private fun reportDropsIfNeeded() {
        val totalDropped = mailbox.droppedNewUidCount
        if (totalDropped < nextDropReportAt || totalDropped == lastReportedDropCount) return
        logger.warn(
            "Presence mailbox saturated at {} occupied uids; total dropped new-uid transitions={}",
            mailbox.capacity,
            totalDropped,
        )
        lastReportedDropCount = totalDropped
        nextDropReportAt = nextPresenceDropReportThreshold(totalDropped)
    }

    override fun close() {
        val attempt = initiateClose()
        val failure = when (attempt) {
            is BoundedCloseGate.Attempt.Owner -> {
                val completed = attempt.deadline.awaitBlocking(lifecycleFinished) { interrupted ->
                    closeGate.recordFailure(interrupted)
                }
                if (completed) closeGate.complete(attempt) else closeGate.expire(attempt.deadline)
            }

            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollowerBlocking(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }
        failure?.let { throw it }
    }

    /** 可挂起的关闭缝隙，让确定性测试不会阻塞其测试分发器。 */
    internal suspend fun closeAndJoin() {
        val attempt = initiateClose()
        val failure = when (attempt) {
            is BoundedCloseGate.Attempt.Owner -> {
                try {
                    val completed = attempt.deadline.await(lifecycleFinishedAsync)
                    if (completed) closeGate.complete(attempt) else closeGate.expire(attempt.deadline)
                } catch (cancelled: CancellationException) {
                    // 准入与观察者所有权已经被吊销。发布调用方的
                    // 取消，以便跟随者即使在此拥有者无法等待的情况下也能被释放。
                    closeGate.recordFailure(cancelled)
                    closeGate.complete(attempt)
                }
            }

            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollower(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }
        failure?.let { throw it }
    }

    private fun initiateClose(): BoundedCloseGate.Attempt {
        var lease: PresenceObserverLease? = null
        val attempt = synchronized(lifecycleLock) {
            closeGate.begin().also { selected ->
                if (selected is BoundedCloseGate.Attempt.Owner) {
                    closed = true
                    lease = observerLease
                    observerLease = null
                    // 在取消和 join worker 之前，先吊销所有生产者路径。
                    mailbox.close()
                    wake.close()
                }
            }
        }
        if (attempt is BoundedCloseGate.Attempt.Owner) {
            try {
                lease?.uninstall()
            } catch (failure: Throwable) {
                closeGate.recordFailure(failure)
            } finally {
                lifecycle.cancel(shutdownCancellation)
            }
        }
        return attempt
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

    private companion object {
        const val DEFAULT_MAILBOX_CAPACITY = 1_024
        const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
        const val MAX_CAUSE_DEPTH = 16
    }
}

internal enum class PresenceOfferResult { ACCEPTED, DROPPED_CAPACITY, CLOSED }

/**
 * PresenceCoordinator 的单个 worker 消费的有界 latest-per-uid 状态。
 *
 * 移除并重新插入一个已更新的 uid，会把它最新的迁移放到队尾。因此保留的序列
 * A-online、B-online、A-offline 会按 B-online、A-offline 排空：被取代的状态
 * 消失，而存活的 actor 迁移保持其原本的相对顺序。处理中的
 * uid 保留其容量槽，并始终可以入队一个最新的后继。
 */
internal class PresenceChangeMailbox(internal val capacity: Int) {
    private val lock = Any()
    private val pending = linkedMapOf<String, PresenceTransition>()
    private var inFlightUid: String? = null
    private var closed = false
    private var dropped = 0L

    init {
        require(capacity > 0) { "Presence mailbox capacity must be positive" }
    }

    val pendingSize: Int get() = synchronized(lock) { pending.size }
    val occupiedUidCount: Int get() = synchronized(lock) { occupiedUidCountLocked() }
    val droppedNewUidCount: Long get() = synchronized(lock) { dropped }

    fun offer(change: PresenceTransition): PresenceOfferResult = synchronized(lock) {
        if (closed) return@synchronized PresenceOfferResult.CLOSED
        if (pending.containsKey(change.uid)) {
            pending.remove(change.uid)
            pending[change.uid] = change
            return@synchronized PresenceOfferResult.ACCEPTED
        }
        if (change.uid == inFlightUid) {
            pending[change.uid] = change
            return@synchronized PresenceOfferResult.ACCEPTED
        }
        if (occupiedUidCountLocked() >= capacity) {
            if (dropped < Long.MAX_VALUE) dropped += 1L
            return@synchronized PresenceOfferResult.DROPPED_CAPACITY
        }
        pending[change.uid] = change
        PresenceOfferResult.ACCEPTED
    }

    fun poll(): PresenceTransition? = synchronized(lock) {
        check(inFlightUid == null) { "Presence worker must complete its in-flight uid before polling" }
        val iterator = pending.entries.iterator()
        if (!iterator.hasNext()) return@synchronized null
        iterator.next().also {
            iterator.remove()
            inFlightUid = it.key
        }.value
    }

    fun complete(uid: String) = synchronized(lock) {
        check(inFlightUid == uid) {
            "Presence worker completed uid=$uid while in-flight uid=$inFlightUid"
        }
        inFlightUid = null
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        pending.clear()
    }

    private fun occupiedUidCountLocked(): Int =
        pending.size + if (inFlightUid != null && !pending.containsKey(inFlightUid)) 1 else 0
}

/** 返回下一个 2 的幂聚合阈值，且 Long 不溢出。 */
internal fun nextPresenceDropReportThreshold(observed: Long): Long {
    require(observed > 0L) { "Observed presence drop count must be positive" }
    val highest = java.lang.Long.highestOneBit(observed)
    return if (highest > Long.MAX_VALUE / 2L) Long.MAX_VALUE else highest shl 1
}
