package com.virjar.tk.shared.client

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.ReliableCommandContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPendingMirrorRecoveryTest {
    @Test
    fun `offline commits conflate and the next authenticated edge drains every outbox`() = runTest {
        val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val wake = SessionPendingMirrorWake()
        var draftPasses = 0
        var readPasses = 0
        var commandPasses = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = state,
            wake = wake,
            retryPendingDrafts = {
                draftPasses += 1
                Outcome.Success(Unit)
            },
            retryPendingReads = {
                readPasses += 1
                Outcome.Success(Unit)
            },
            retryPendingReliableCommands = {
                commandPasses += 1
                Outcome.Success(Unit)
            },
            parentScope = backgroundScope,
        )

        repeat(100) { wake.pendingCommitted() }
        runCurrent()
        assertEquals(0, draftPasses)
        assertEquals(0, readPasses)
        assertEquals(0, commandPasses)

        state.value = ConnectionState.AUTHENTICATED
        runCurrent()
        assertEquals(1, draftPasses)
        assertEquals(1, readPasses)
        assertEquals(1, commandPasses)

        repeat(100) { wake.pendingCommitted() }
        runCurrent()
        assertEquals(2, draftPasses, "one commit burst must occupy one recovery pass")
        assertEquals(2, readPasses)
        assertEquals(2, commandPasses)
        recovery.close()
    }

    @Test
    fun `network and timeout failures use a bounded exponential retry without commit bypass`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val wake = SessionPendingMirrorWake()
        val policy = PendingMirrorRetryPolicy(baseDelayMillis = 100L, maxDelayMillis = 200L)
        var draftPasses = 0
        var readPasses = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = state,
            wake = wake,
            retryPendingDrafts = {
                draftPasses += 1
                when (draftPasses) {
                    1 -> Outcome.Failure(AppError.Network)
                    2 -> Outcome.Failure(AppError.Timeout)
                    3 -> Outcome.Failure(AppError.Network)
                    else -> Outcome.Success(Unit)
                }
            },
            retryPendingReads = {
                readPasses += 1
                Outcome.Success(Unit)
            },
            parentScope = backgroundScope,
            retryPolicy = policy,
        )

        runCurrent()
        assertEquals(1, draftPasses)
        wake.pendingCommitted()
        runCurrent()
        assertEquals(1, draftPasses, "commits must not turn a transport outage into a tight loop")

        advanceTimeBy(99L)
        runCurrent()
        assertEquals(1, draftPasses)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, draftPasses)

        advanceTimeBy(199L)
        runCurrent()
        assertEquals(2, draftPasses)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, draftPasses)

        advanceTimeBy(200L)
        runCurrent()
        assertEquals(4, draftPasses)
        assertEquals(4, readPasses, "one failing outbox must not starve the other")
        assertEquals(listOf(100L, 200L, 200L, 200L), (1..4).map(policy::delayMillis))
        assertEquals(200L, policy.delayMillis(Int.MAX_VALUE))
        assertEquals(
            Int.MAX_VALUE,
            nextPendingMirrorTransientFailureCount(Int.MAX_VALUE),
            "a multi-month outage must never wrap retry state negative",
        )
        recovery.close()
    }

    @Test
    fun `rate limits and server failures automatically use the same bounded backoff`() = runTest {
        val wake = SessionPendingMirrorWake()
        var draftPasses = 0
        var readPasses = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            wake = wake,
            retryPendingDrafts = {
                draftPasses += 1
                when (draftPasses) {
                    1 -> Outcome.Failure(AppError.Business(429, "rate limited"))
                    2 -> Outcome.Failure(AppError.Business(500, "server unavailable"))
                    else -> Outcome.Success(Unit)
                }
            },
            retryPendingReads = {
                readPasses += 1
                Outcome.Success(Unit)
            },
            parentScope = backgroundScope,
            retryPolicy = PendingMirrorRetryPolicy(baseDelayMillis = 100L, maxDelayMillis = 200L),
        )

        runCurrent()
        assertEquals(1, draftPasses)
        advanceTimeBy(99L)
        runCurrent()
        assertEquals(1, draftPasses)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, draftPasses, "429 must retry automatically after the base delay")

        advanceTimeBy(199L)
        runCurrent()
        assertEquals(2, draftPasses)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, draftPasses, "5xx must retry automatically with bounded backoff")
        assertEquals(3, readPasses)
        recovery.close()
    }

    @Test
    fun `scheduled retry never runs while disconnected and reauthentication retries immediately`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val wake = SessionPendingMirrorWake()
        var attempts = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = state,
            wake = wake,
            retryPendingDrafts = {
                attempts += 1
                if (attempts == 1) Outcome.Failure(AppError.Network) else Outcome.Success(Unit)
            },
            retryPendingReads = { Outcome.Success(Unit) },
            parentScope = backgroundScope,
            retryPolicy = PendingMirrorRetryPolicy(baseDelayMillis = 100L, maxDelayMillis = 100L),
        )

        runCurrent()
        assertEquals(1, attempts)
        state.value = ConnectionState.DISCONNECTED
        runCurrent()
        wake.pendingCommitted()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, attempts)

        state.value = ConnectionState.AUTHENTICATED
        runCurrent()
        assertEquals(2, attempts, "a new authenticated edge must not inherit the stale timer")
        recovery.close()
    }

    @Test
    fun `deterministic 4xx fatal and unknown failures never schedule their own retry`() = runTest {
        val wake = SessionPendingMirrorWake()
        var draftPasses = 0
        var readPasses = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            wake = wake,
            retryPendingDrafts = {
                draftPasses += 1
                if (draftPasses == 1) {
                    Outcome.Failure(AppError.Business(400, "invalid"))
                } else {
                    Outcome.Failure(AppError.FatalCodec("projection defect"))
                }
            },
            retryPendingReads = {
                readPasses += 1
                if (readPasses == 1) {
                    Outcome.Failure(AppError.Business(409, "conflict"))
                } else {
                    Outcome.Failure(AppError.Unknown(IllegalStateException("projection defect")))
                }
            },
            parentScope = backgroundScope,
            retryPolicy = PendingMirrorRetryPolicy(baseDelayMillis = 10L, maxDelayMillis = 10L),
        )

        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, draftPasses)
        assertEquals(1, readPasses)

        wake.pendingCommitted()
        runCurrent()
        assertEquals(2, draftPasses)
        assertEquals(2, readPasses)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, draftPasses, "fatal failures must not enter an automatic retry loop")
        assertEquals(2, readPasses, "unknown failures must not enter an automatic retry loop")
        recovery.close()
    }

    @Test
    fun `retained 403 gets one horizon wake while continuously authenticated`() = runTest {
        val wake = SessionPendingMirrorWake()
        val expiryAt = ReliableCommandContract.firstExpiredAt(0L)
        var retainedExpiryAt: Long? = expiryAt
        var commandPasses = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            wake = wake,
            retryPendingDrafts = { Outcome.Success(Unit) },
            retryPendingReads = { Outcome.Success(Unit) },
            retryPendingReliableCommands = {
                commandPasses += 1
                if (commandPasses == 1) {
                    Outcome.Failure(AppError.Business(403, "permission changed"))
                } else {
                    // 模拟一个确定性的服务端 410：repository 有条件地清除该槽位。
                    retainedExpiryAt = null
                    Outcome.Failure(AppError.Business(410, "command expired"))
                }
            },
            nextReliableCommandExpiryAt = { retainedExpiryAt },
            parentScope = backgroundScope,
            nowMillis = { testScheduler.currentTime },
        )

        runCurrent()
        assertEquals(1, commandPasses)
        advanceTimeBy(expiryAt - 1L)
        runCurrent()
        assertEquals(1, commandPasses)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, commandPasses, "the finite replay boundary must wake a resident session")

        advanceTimeBy(ReliableCommandContract.MAX_FUTURE_CLOCK_SKEW_MILLIS * 2L)
        runCurrent()
        assertEquals(2, commandPasses, "a cleared slot must leave no periodic expiry poll")
        recovery.close()
    }

    @Test
    fun `closing the session cancels its sole reliable expiry wake`() = runTest {
        val wake = SessionPendingMirrorWake()
        var commandPasses = 0
        val recovery = SessionPendingMirrorRecovery(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            wake = wake,
            retryPendingDrafts = { Outcome.Success(Unit) },
            retryPendingReads = { Outcome.Success(Unit) },
            retryPendingReliableCommands = {
                commandPasses += 1
                Outcome.Failure(AppError.Business(403, "permission changed"))
            },
            nextReliableCommandExpiryAt = { 100L },
            parentScope = backgroundScope,
            nowMillis = { testScheduler.currentTime },
        )

        runCurrent()
        assertEquals(1, commandPasses)
        recovery.close()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, commandPasses)
    }

    @Test
    fun `auth expiry is handed off once and permanently retires this worker`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val wake = SessionPendingMirrorWake()
        var draftPasses = 0
        var readPasses = 0
        var authExpiredReports = 0
        SessionPendingMirrorRecovery(
            connectionState = state,
            wake = wake,
            retryPendingDrafts = {
                draftPasses += 1
                Outcome.Failure(AppError.AuthExpired)
            },
            retryPendingReads = {
                readPasses += 1
                Outcome.Success(Unit)
            },
            parentScope = backgroundScope,
            onAuthExpired = { authExpiredReports += 1 },
        )

        runCurrent()
        assertEquals(1, draftPasses)
        assertEquals(0, readPasses, "deployment-wide auth loss must stop the pass")
        assertEquals(1, authExpiredReports)

        wake.pendingCommitted()
        state.value = ConnectionState.DISCONNECTED
        runCurrent()
        state.value = ConnectionState.AUTHENTICATED
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, draftPasses)
        assertEquals(1, authExpiredReports)
    }

    @Test
    fun `session owner cancellation fences retry and old wake cannot drive replacement session`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val oldWake = SessionPendingMirrorWake()
        val oldOwnerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val oldOwnerScope = CoroutineScope(backgroundScope.coroutineContext + oldOwnerJob)
        var oldPasses = 0
        SessionPendingMirrorRecovery(
            connectionState = state,
            wake = oldWake,
            retryPendingDrafts = {
                oldPasses += 1
                Outcome.Failure(AppError.Network)
            },
            retryPendingReads = { Outcome.Success(Unit) },
            parentScope = oldOwnerScope,
            retryPolicy = PendingMirrorRetryPolicy(baseDelayMillis = 100L, maxDelayMillis = 100L),
        )

        runCurrent()
        assertEquals(1, oldPasses)
        // ClientSession quiesce 取消了这个精确的 owner。在这里 join 使替代
        // 交接变得确定，并证明每个子协程（包括重试定时器）都已退场。
        oldOwnerJob.cancelAndJoin()
        oldWake.pendingCommitted()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, oldPasses)

        val replacementWake = SessionPendingMirrorWake()
        var replacementPasses = 0
        val replacement = SessionPendingMirrorRecovery(
            connectionState = state,
            wake = replacementWake,
            retryPendingDrafts = {
                replacementPasses += 1
                Outcome.Success(Unit)
            },
            retryPendingReads = { Outcome.Success(Unit) },
            parentScope = backgroundScope,
        )
        runCurrent()
        assertEquals(1, replacementPasses)

        oldWake.pendingCommitted()
        runCurrent()
        assertEquals(1, replacementPasses, "old-session commits must not wake a new owner")
        replacement.close()
    }
}
