package com.virjar.tk.shared.client

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.RemoteFailureClassification
import com.virjar.tk.shared.RemoteFailureClassifier
import com.virjar.tk.protocol.ReliableCommandContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 面向不明确本地镜像失败的确定性有界指数延迟。 */
internal class PendingMirrorRetryPolicy(
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
) {
    init {
        require(baseDelayMillis > 0L) { "Pending mirror retry base delay must be positive" }
        require(maxDelayMillis >= baseDelayMillis) {
            "Pending mirror retry max delay must not be smaller than its base delay"
        }
    }

    fun delayMillis(consecutiveTransientFailures: Int): Long {
        require(consecutiveTransientFailures > 0) {
            "Pending mirror retry attempt must be positive"
        }
        var bounded = baseDelayMillis
        repeat((consecutiveTransientFailures - 1).coerceAtMost(MAX_DOUBLINGS)) {
            if (bounded >= maxDelayMillis) return maxDelayMillis
            bounded = if (bounded > maxDelayMillis / 2L) maxDelayMillis else bounded * 2L
        }
        return bounded.coerceAtMost(maxDelayMillis)
    }

    private companion object {
        const val DEFAULT_BASE_DELAY_MILLIS = 500L
        const val DEFAULT_MAX_DELAY_MILLIS = 30_000L
        const val MAX_DOUBLINGS = 62
    }
}

/** 饱和计数器：会话可以无限重试，而不会回绕成负延迟。 */
internal fun nextPendingMirrorTransientFailureCount(current: Int): Int {
    require(current >= 0) { "Pending mirror transient failure count must not be negative" }
    return if (current == Int.MAX_VALUE) current else current + 1
}

/**
 * 一次合并协程唤醒周围的无损元数据。
 *
 * 普通合并 `Unit` channel 可能用一次后续提交擦除一条认证边。epoch 让两个事实保持单调，同时仍
 * 允许任意突发本地写入占用一个 channel 槽。
 */
internal class SessionPendingMirrorWake : AutoCloseable {
    internal data class Snapshot(
        val commitEpoch: Long,
        val authenticatedEpoch: Long,
        val retryGeneration: Long,
        val reliableExpiryGeneration: Long,
    )

    private val lock = Any()
    private val signal = Channel<Snapshot>(Channel.CONFLATED)
    private var closed = false
    private var commitEpoch = 0L
    private var authenticatedEpoch = 0L
    private var retryGeneration = 0L
    private var reliableExpiryGeneration = 0L

    fun pendingCommitted() = publish {
        commitEpoch = nextEpoch(commitEpoch, "commit")
    }

    fun authenticated() = publish {
        authenticatedEpoch = nextEpoch(authenticatedEpoch, "authentication")
    }

    fun retryDue(generation: Long) = publish {
        require(generation > 0L) { "Pending mirror retry generation must be positive" }
        // 已取消的旧定时器可能在其后继者之后发布。重试代际是单调的，因此过时的唤醒绝不能
        // 在这个合并槽中擦除更新的到期信号。
        retryGeneration = maxOf(retryGeneration, generation)
    }

    fun reliableCommandExpiryDue(generation: Long) = publish {
        require(generation > 0L) { "Reliable command expiry generation must be positive" }
        reliableExpiryGeneration = maxOf(reliableExpiryGeneration, generation)
    }

    /**
     * 接收一次唤醒，并把已缓冲的尾部折叠进同一次恢复趟次。
     *
     * 等待中的接收者可以被第一个生产者直接恢复，而同步提交突发的其余部分落入合并槽。只读一次
     * 就会把一次 UI 突发变成两次完整可靠发件箱扫描。max 合并也使发布顺序在 [lock] 下获取快照后
     * 竞争的不同生产者线程之间无关紧要。
     */
    suspend fun await(): Snapshot {
        var latest = signal.receive()
        while (true) {
            val buffered = signal.tryReceive().getOrNull() ?: return latest
            latest = Snapshot(
                commitEpoch = maxOf(latest.commitEpoch, buffered.commitEpoch),
                authenticatedEpoch = maxOf(
                    latest.authenticatedEpoch,
                    buffered.authenticatedEpoch,
                ),
                retryGeneration = maxOf(latest.retryGeneration, buffered.retryGeneration),
                reliableExpiryGeneration = maxOf(
                    latest.reliableExpiryGeneration,
                    buffered.reliableExpiryGeneration,
                ),
            )
        }
    }

    private fun publish(update: () -> Unit) {
        val snapshot = synchronized(lock) {
            if (closed) return
            update()
            Snapshot(
                commitEpoch,
                authenticatedEpoch,
                retryGeneration,
                reliableExpiryGeneration,
            )
        }
        signal.trySend(snapshot)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        signal.close()
    }

    private fun nextEpoch(current: Long, owner: String): Long {
        check(current < Long.MAX_VALUE) { "Pending mirror $owner epoch exhausted" }
        return current + 1L
    }
}

private enum class MirrorRecoveryDisposition {
    COMPLETE,
    RETRYABLE,
    EXTERNAL_WAKE_REQUIRED,
    AUTH_EXPIRED,
}

/**
 * 会话拥有的恢复 worker，面向持久草稿/已读镜像与结果未知的社交命令。
 *
 * 提交与已认证边是合并唤醒。网络/超时失败调度一个有界指数定时器；后续提交保持持久，但不能绕过
 * 该退避。重新认证是一条新连通性边并立即重试。业务/协议/未知失败等待另一次外部唤醒而不是空转，
 * 而权威 401 被投递一次给现有会话退役边界，并永久结束该 worker。
 */
internal class SessionPendingMirrorRecovery(
    private val connectionState: StateFlow<ConnectionState>,
    private val wake: SessionPendingMirrorWake,
    private val retryPendingDrafts: suspend () -> Outcome<Unit>,
    private val retryPendingReads: suspend () -> Outcome<Unit>,
    private val retryPendingReliableCommands: suspend () -> Outcome<Unit> = { Outcome.Success(Unit) },
    private val nextReliableCommandExpiryAt: () -> Long? = { null },
    parentScope: CoroutineScope,
    private val retryPolicy: PendingMirrorRetryPolicy = PendingMirrorRetryPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onAuthExpired: () -> Unit = {},
) : AutoCloseable {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)

    init {
        ownerJob.invokeOnCompletion { wake.close() }
        connectionState
            .onEach { state ->
                if (state == ConnectionState.AUTHENTICATED) wake.authenticated()
            }
            .launchIn(scope)
        scope.launch {
            try {
                workerLoop()
            } finally {
                // 意外的仓库缺陷绝不能留下一个僵尸状态收集器，在其唯一 worker 死亡后仍接受唤醒。
                scope.cancel()
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun workerLoop() {
        var observedCommitEpoch = 0L
        var observedAuthenticatedEpoch = 0L
        var consecutiveTransientFailures = 0
        var nextRetryGeneration = 0L
        var scheduledRetryGeneration: Long? = null
        var retryTimer: Job? = null
        var nextReliableExpiryGeneration = 0L
        var scheduledReliableExpiryGeneration: Long? = null
        var scheduledReliableExpiryAt: Long? = null
        var reliableExpiryTimer: Job? = null

        fun synchronizeReliableExpiryTimer() {
            val firstExpiryAt = nextReliableCommandExpiryAt()
            val now = nowMillis().coerceAtLeast(0L)
            val target = firstExpiryAt?.let { first ->
                when {
                    first > now -> first
                    else -> saturatingAdd(
                        first,
                        ReliableCommandContract.MAX_FUTURE_CLOCK_SKEW_MILLIS,
                    ).takeIf { it > now }
                }
            }
            if (target == scheduledReliableExpiryAt && reliableExpiryTimer?.isActive == true) return

            reliableExpiryTimer?.cancel()
            reliableExpiryTimer = null
            scheduledReliableExpiryGeneration = null
            scheduledReliableExpiryAt = null
            if (target == null) return

            check(nextReliableExpiryGeneration < Long.MAX_VALUE) {
                "Reliable command expiry generation exhausted"
            }
            nextReliableExpiryGeneration += 1L
            val generation = nextReliableExpiryGeneration
            scheduledReliableExpiryGeneration = generation
            scheduledReliableExpiryAt = target
            reliableExpiryTimer = scope.launch {
                delay(target - now)
                wake.reliableCommandExpiryDue(generation)
            }
        }

        while (true) {
            val snapshot = wake.await()
            val hasCommit = snapshot.commitEpoch > observedCommitEpoch
            val hasAuthenticationEdge = snapshot.authenticatedEpoch > observedAuthenticatedEpoch
            val retryIsDue = scheduledRetryGeneration != null &&
                snapshot.retryGeneration == scheduledRetryGeneration
            val reliableExpiryIsDue = scheduledReliableExpiryGeneration != null &&
                snapshot.reliableExpiryGeneration == scheduledReliableExpiryGeneration
            observedCommitEpoch = maxOf(observedCommitEpoch, snapshot.commitEpoch)
            observedAuthenticatedEpoch = maxOf(
                observedAuthenticatedEpoch,
                snapshot.authenticatedEpoch,
            )

            if (hasAuthenticationEdge) {
                retryTimer?.cancel()
                retryTimer = null
                scheduledRetryGeneration = null
                consecutiveTransientFailures = 0
            } else if (retryIsDue) {
                retryTimer = null
                scheduledRetryGeneration = null
            }
            if (reliableExpiryIsDue) {
                reliableExpiryTimer = null
                scheduledReliableExpiryGeneration = null
                scheduledReliableExpiryAt = null
                // 有限重放边界比无关的临时退避更权威：它到期时必须获得一次在线趟次，
                // 而不留下重复定时器。
                retryTimer?.cancel()
                retryTimer = null
                scheduledRetryGeneration = null
            }

            // 新提交的更早命令或重连必须替换单一截止时间。
            // 如果主边界因为服务器时钟落后于客户端而过早，允许在协议最大偏差处再唤醒一次；
            // 没有周期性轮询。
            synchronizeReliableExpiryTimer()

            val mayRunNow = connectionState.value == ConnectionState.AUTHENTICATED &&
                (
                    hasAuthenticationEdge || retryIsDue || reliableExpiryIsDue ||
                        (hasCommit && scheduledRetryGeneration == null)
                )
            if (!mayRunNow) continue

            val disposition = recoverPendingMirrors()
            if (disposition == MirrorRecoveryDisposition.AUTH_EXPIRED) {
                reliableExpiryTimer?.cancel()
                // worker 的终态 finally 在现有退役边界观察到该权威拒绝之后关闭其整个精确会话 owner。
                onAuthExpired()
                return
            }
            synchronizeReliableExpiryTimer()

            when (disposition) {
                MirrorRecoveryDisposition.COMPLETE,
                MirrorRecoveryDisposition.EXTERNAL_WAKE_REQUIRED -> {
                    consecutiveTransientFailures = 0
                }

                MirrorRecoveryDisposition.AUTH_EXPIRED -> error("handled above")

                MirrorRecoveryDisposition.RETRYABLE -> {
                    // RPC 可能在该趟次开始之后与一次断开竞争。下一条精确 AUTHENTICATED 边
                    // 于是成为唯一有用的重试信号。
                    if (connectionState.value != ConnectionState.AUTHENTICATED) continue
                    consecutiveTransientFailures = nextPendingMirrorTransientFailureCount(
                        consecutiveTransientFailures,
                    )
                    check(nextRetryGeneration < Long.MAX_VALUE) {
                        "Pending mirror retry generation exhausted"
                    }
                    nextRetryGeneration += 1L
                    val generation = nextRetryGeneration
                    scheduledRetryGeneration = generation
                    val delayMillis = retryPolicy.delayMillis(consecutiveTransientFailures)
                    retryTimer = scope.launch {
                        delay(delayMillis)
                        wake.retryDue(generation)
                    }
                }
            }
        }
    }

    private suspend fun recoverPendingMirrors(): MirrorRecoveryDisposition {
        val draftResult = retryPendingDrafts()
        val draftFailure = draftResult.failureClassification()
        if (draftFailure == RemoteFailureClassification.AUTH_EXPIRED) {
            return MirrorRecoveryDisposition.AUTH_EXPIRED
        }

        // 一个有界可靠发件箱的失败绝不能饿死另一个持久本地事实族。
        val readResult = retryPendingReads()
        val readFailure = readResult.failureClassification()
        if (readFailure == RemoteFailureClassification.AUTH_EXPIRED) {
            return MirrorRecoveryDisposition.AUTH_EXPIRED
        }

        val commandResult = retryPendingReliableCommands()
        val commandFailure = commandResult.failureClassification()
        if (commandFailure == RemoteFailureClassification.AUTH_EXPIRED) {
            return MirrorRecoveryDisposition.AUTH_EXPIRED
        }

        return when {
            draftFailure == RemoteFailureClassification.RETRYABLE ||
                readFailure == RemoteFailureClassification.RETRYABLE ||
                commandFailure == RemoteFailureClassification.RETRYABLE ->
                MirrorRecoveryDisposition.RETRYABLE
            draftFailure != null || readFailure != null || commandFailure != null ->
                MirrorRecoveryDisposition.EXTERNAL_WAKE_REQUIRED
            else -> MirrorRecoveryDisposition.COMPLETE
        }
    }
}

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

private fun Outcome<Unit>.failureClassification(): RemoteFailureClassification? =
    (this as? Outcome.Failure)?.let { RemoteFailureClassifier.classify(it.error) }
