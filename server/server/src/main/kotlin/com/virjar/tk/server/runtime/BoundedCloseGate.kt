package com.virjar.tk.server.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 选举一个关闭拥有者，并给该拥有者与每个跟随者一个单调截止时间。
 *
 * 在终结发布之前观察到的失败用 [mergeRuntimeFailure] 合并。发布
 * 的对象作为终结身份是不可变的：并发与重复的关闭调用方
 * 要么全部成功，要么全部抛出那个精确对象。在超时关闭之后才到达的失败
 * 只能作为 suppressed 上下文保留，因为替换已被观察到的终结对象会
 * 破坏该身份保证。
 */
internal class BoundedCloseGate(
    ownerName: String,
    timeoutMillis: Long,
    private val onTerminal: (Throwable?) -> Unit,
    nanoTime: () -> Long = System::nanoTime,
) {
    internal sealed interface Attempt {
        class Owner internal constructor(val deadline: CloseDeadline) : Attempt
        class Follower internal constructor(val deadline: CloseDeadline) : Attempt
        class Terminal internal constructor(val failure: Throwable?) : Attempt
    }

    private sealed interface Phase {
        data class Open(val failure: Throwable?) : Phase
        data class Closing(val deadline: CloseDeadline, val failure: Throwable?) : Phase
        data class Closed(val failure: Throwable?) : Phase
    }

    private val validatedOwnerName = ownerName.also {
        require(it.isNotBlank()) { "close owner name must not be blank" }
    }
    private val validatedTimeoutMillis = timeoutMillis.also {
        require(it > 0L) { "close timeout must be positive" }
    }
    private val nanoTimeSource = nanoTime
    private val lock = ReentrantLock()
    private val terminalPublished = CountDownLatch(1)
    private val terminalPublishedAsync = CompletableDeferred<Unit>()
    private var phase: Phase = Phase.Open(failure = null)

    fun begin(): Attempt = lock.withLock {
        when (val current = phase) {
            is Phase.Open -> {
                val deadline = CloseDeadline.after(
                    timeoutMillis = validatedTimeoutMillis,
                    nanoTime = nanoTimeSource,
                )
                phase = Phase.Closing(deadline = deadline, failure = current.failure)
                Attempt.Owner(deadline)
            }

            is Phase.Closing -> Attempt.Follower(current.deadline)
            is Phase.Closed -> Attempt.Terminal(current.failure)
        }
    }

    /** 记录一次失败，并返回当前具有传播优先级的失败。 */
    fun recordFailure(failure: Throwable): Throwable = lock.withLock {
        when (val current = phase) {
            is Phase.Open -> mergeRuntimeFailure(current.failure, failure).also {
                phase = current.copy(failure = it)
            }

            is Phase.Closing -> mergeRuntimeFailure(current.failure, failure).also {
                phase = current.copy(failure = it)
            }

            is Phase.Closed -> current.failure?.also {
                // 终结身份在另一个关闭调用方可能已经观察到它之后不能改变。
                it.addSuppressedDistinct(failure)
            } ?: failure
        }
    }

    /** 发布被选拥有者在其截止时间之前累积的失败。 */
    fun complete(owner: Attempt.Owner): Throwable? = publish(owner.deadline, timedOut = false)

    /** 当拥有者或跟随者到达截止时间时，发布一个共享的超时终结。 */
    fun expire(deadline: CloseDeadline): Throwable? = publish(deadline, timedOut = true)

    fun awaitFollowerBlocking(follower: Attempt.Follower): Throwable? {
        var interrupted = false
        try {
            while (true) {
                closedFailure()?.let { return it.failure }
                val remainingNanos = follower.deadline.remainingNanos()
                if (remainingNanos <= 0L) return expire(follower.deadline)
                try {
                    if (terminalPublished.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                        return requireNotNull(closedFailure()).failure
                    }
                } catch (failure: InterruptedException) {
                    interrupted = true
                    recordFailure(failure)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    suspend fun awaitFollower(follower: Attempt.Follower): Throwable? {
        while (true) {
            closedFailure()?.let { return it.failure }
            if (follower.deadline.remainingNanos() <= 0L) return expire(follower.deadline)
            try {
                val completed = withTimeoutOrNull(follower.deadline.remainingMillisCeiling()) {
                    terminalPublishedAsync.await()
                    true
                } == true
                if (completed) return requireNotNull(closedFailure()).failure
                if (follower.deadline.remainingNanos() <= 0L) return expire(follower.deadline)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 调用方取消是可观察的，但不会取消被选的关闭拥有者。
                throw recordFailure(cancelled)
            }
        }
    }

    private fun publish(deadline: CloseDeadline, timedOut: Boolean): Throwable? {
        var published = false
        val terminal = lock.withLock {
            when (val current = phase) {
                is Phase.Open -> error("close terminal cannot be published before owner election")
                is Phase.Closed -> current.failure
                is Phase.Closing -> {
                    check(current.deadline === deadline) { "close attempt used a stale owner deadline" }
                    val failure = if (timedOut) {
                        mergeRuntimeFailure(
                            current.failure,
                            BoundedCloseTimeoutException(
                                ownerName = validatedOwnerName,
                                timeoutMillis = validatedTimeoutMillis,
                            ),
                        )
                    } else {
                        current.failure
                    }
                    // 在把终结暴露给调用方之前，先发布组件的 STOPPED 状态。
                    onTerminal(failure)
                    phase = Phase.Closed(failure)
                    published = true
                    failure
                }
            }
        }
        if (published) {
            terminalPublished.countDown()
            terminalPublishedAsync.complete(Unit)
        }
        return terminal
    }

    private fun closedFailure(): Phase.Closed? = lock.withLock { phase as? Phase.Closed }
}

/** 一个单调的拥有者截止时间；墙钟变化不能延长关闭。 */
internal class CloseDeadline private constructor(
    private val deadlineNanos: Long,
    private val nanoTime: () -> Long,
) {
    fun remainingNanos(): Long = (deadlineNanos - nanoTime()).coerceAtLeast(0L)

    fun remainingMillisCeiling(): Long {
        val nanos = remainingNanos()
        if (nanos <= 0L) return 0L
        return 1L + (nanos - 1L) / NANOS_PER_MILLISECOND
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun after(timeoutMillis: Long, nanoTime: () -> Long): CloseDeadline {
            val now = nanoTime()
            val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            // 刻意的补码回绕使 nanoTime 减法在其回绕期间仍然有效。
            val deadline = now + timeoutNanos
            return CloseDeadline(deadlineNanos = deadline, nanoTime = nanoTime)
        }
    }
}

internal class BoundedCloseTimeoutException(
    ownerName: String,
    timeoutMillis: Long,
) : IllegalStateException("$ownerName did not stop within $timeoutMillis ms")

/** 等待自有 worker 完成，而不允许重复中断延长截止时间。 */
internal fun CloseDeadline.awaitBlocking(
    completion: CountDownLatch,
    onInterrupted: (InterruptedException) -> Unit,
): Boolean {
    var interrupted = false
    try {
        while (true) {
            if (completion.count == 0L) return true
            val remainingNanos = remainingNanos()
            if (remainingNanos <= 0L) return completion.count == 0L
            try {
                if (completion.await(remainingNanos, TimeUnit.NANOSECONDS)) return true
            } catch (failure: InterruptedException) {
                interrupted = true
                onInterrupted(failure)
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal suspend fun CloseDeadline.await(completion: CompletableDeferred<Unit>): Boolean {
    if (completion.isCompleted) return true
    if (remainingNanos() <= 0L) return completion.isCompleted
    val completed = withTimeoutOrNull(remainingMillisCeiling()) {
        completion.await()
        true
    } == true
    return completed || completion.isCompleted
}
