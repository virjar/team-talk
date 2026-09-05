package com.virjar.tk.shared.client

import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientSessionLifecycleTest {
    @Test
    fun `quiesce rejects new business but preserves raw logout RPC until full close`() = runBlocking {
        val lifecycle = SessionLifecycleGate()
        val rawCalls = mutableListOf<String>()
        val raw = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                rawCalls += service
                return ResponsePayload(1, 0, null)
            }
        }
        val outbound = SessionOutboundLease()
        val business = SessionBusinessRpcInvoker(raw, lifecycle, outbound)

        business.invoke("business", 1)
        assertEquals(true, lifecycle.beginQuiesce(SessionEndReason.USER_LOGOUT, outbound::retire))
        assertEquals(false, lifecycle.beginQuiesce(SessionEndReason.AUTH_REVOKED))

        val rejected = try {
            business.invoke("late-business", 2)
            null
        } catch (failure: Throwable) {
            failure
        }
        assertIs<IllegalStateException>(rejected)

        // 生命周期门禁为已封存的退场 owner 保留原始 transport 能力。
        raw.invoke("logout", 3)
        assertEquals(listOf("business", "logout"), rawCalls)
        assertEquals(SessionLifecyclePhase.QUIESCED, lifecycle.phase)
        assertEquals(SessionEndReason.USER_LOGOUT, lifecycle.endReason)

        lifecycle.markClosed()
        assertEquals(SessionLifecyclePhase.CLOSED, lifecycle.phase)
    }

    @Test
    fun `response started before quiesce cannot publish afterwards`() = runBlocking {
        val lifecycle = SessionLifecycleGate()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ResponsePayload>()
        val raw = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                entered.complete(Unit)
                return response.await()
            }
        }
        val outbound = SessionOutboundLease()
        val business = SessionBusinessRpcInvoker(raw, lifecycle, outbound)
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                business.invoke("business", 1)
                null
            } catch (failure: Throwable) {
                failure
            }
        }
        entered.await()

        lifecycle.beginQuiesce(SessionEndReason.AUTH_REVOKED, outbound::retire)
        response.complete(ResponsePayload(1, 0, null))

        assertIs<IllegalStateException>(call.await())
        Unit
    }

    @Test
    fun `outbound lease retires before quiesced phase becomes observable`() {
        val lifecycle = SessionLifecycleGate()
        val outbound = SessionOutboundLease()
        val syncOutbound = SessionOutboundLease()
        var retireObservedActivePhase = false

        assertTrue(
            lifecycle.beginQuiesce(SessionEndReason.USER_LOGOUT) {
                retireObservedActivePhase = lifecycle.phase == SessionLifecyclePhase.ACTIVE
                outbound.retire()
                syncOutbound.retire()
            },
        )

        assertTrue(retireObservedActivePhase)
        assertFalse(outbound.isActive())
        assertFalse(syncOutbound.isActive())
        assertEquals(SessionLifecyclePhase.QUIESCED, lifecycle.phase)
    }

    @Test
    fun `fatal outbound retirement still seals the lifecycle before propagation`() {
        val lifecycle = SessionLifecycleGate()
        val fatal = AssertionError("retirement defect")

        val thrown = assertFailsWith<AssertionError> {
            lifecycle.beginQuiesce(SessionEndReason.AUTH_REVOKED) { throw fatal }
        }

        assertSame(fatal, thrown)
        assertEquals(SessionLifecyclePhase.QUIESCED, lifecycle.phase)
        assertEquals(SessionEndReason.AUTH_REVOKED, lifecycle.endReason)
        assertFalse(lifecycle.beginQuiesce(SessionEndReason.SHUTDOWN))
    }

    @Test
    fun `terminal lifecycle follower waits for leader and replays its exact failure`() = runBlocking {
        val lifecycle = ClientSessionTerminalLifecycle()
        val leaderEntered = CompletableDeferred<Unit>()
        val releaseLeader = CompletableDeferred<Unit>()
        val followerAttempted = CompletableDeferred<Unit>()
        val fatal = AssertionError("terminal drain defect")
        var complete = false

        val leader = async(Dispatchers.Default) {
            captureFailureWithoutCoroutineRecovery {
                lifecycle.runUntil(
                    isComplete = { complete },
                    drain = {
                        leaderEntered.complete(Unit)
                        runBlocking { releaseLeader.await() }
                        complete = true
                        fatal
                    },
                )
            }
        }
        leaderEntered.await()
        val follower = async(Dispatchers.Default) {
            captureFailureWithoutCoroutineRecovery {
                followerAttempted.complete(Unit)
                lifecycle.runUntil(
                    isComplete = { complete },
                    drain = { error("follower must not repeat the leader drain") },
                )
            }
        }
        followerAttempted.await()
        yield()
        assertFalse(follower.isCompleted)

        releaseLeader.complete(Unit)
        assertSame(fatal, leader.await())
        assertSame(fatal, follower.await())
        val replay = assertFailsWith<AssertionError> {
            lifecycle.runUntil(
                isComplete = { complete },
                drain = { error("completed lifecycle must not drain again") },
            )
        }
        assertSame(fatal, replay)
    }

    @Test
    fun `terminal lifecycle reentry fails immediately and becomes the stable outcome`() {
        val lifecycle = ClientSessionTerminalLifecycle()
        var complete = false
        var reentrantFailure: SessionBoundaryReentrantCloseException? = null

        val leaderFailure = assertFailsWith<SessionBoundaryReentrantCloseException> {
            lifecycle.runUntil(
                isComplete = { complete },
                drain = {
                    reentrantFailure = assertFailsWith<SessionBoundaryReentrantCloseException> {
                        lifecycle.runUntil(
                            isComplete = { complete },
                            drain = { error("reentrant caller must not become a drain leader") },
                        )
                    }
                    complete = true
                    // 即使清理钩子捕获了立即标记，也无法抹除它。
                    null
                },
            )
        }

        assertSame(reentrantFailure, leaderFailure)
        val replay = assertFailsWith<SessionBoundaryReentrantCloseException> {
            lifecycle.runUntil(
                isComplete = { complete },
                drain = { error("terminal lifecycle must not drain again") },
            )
        }
        assertSame(leaderFailure, replay)
    }

    @Test
    fun `published quiesce failure keeps identity when close discovers another defect`() {
        val lifecycle = ClientSessionTerminalLifecycle()
        val quiesceFailure = IllegalStateException("quiesce defect")
        val closeFailure = AssertionError("close defect")
        var quiesced = false
        var closed = false

        val first = assertFailsWith<IllegalStateException> {
            lifecycle.runUntil(
                isComplete = { quiesced },
                drain = {
                    quiesced = true
                    quiesceFailure
                },
            )
        }
        val second = assertFailsWith<IllegalStateException> {
            lifecycle.runUntil(
                isComplete = { closed },
                drain = {
                    closed = true
                    closeFailure
                },
            )
        }

        assertSame(quiesceFailure, first)
        assertSame(first, second)
        assertTrue(first.suppressedExceptions.any { it === closeFailure })
    }

    @Test
    fun `logout retirement is one-shot under concurrent completion and always closes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var rawCalls = 0
        var closeCalls = 0
        val retirement = UserLogoutRetirementCapability(
            logoutRpc = {
                rawCalls += 1
                entered.complete(Unit)
                release.await()
                Outcome.Success(Unit)
            },
            closeSession = { closeCalls += 1 },
        )

        val first = async(start = CoroutineStart.UNDISPATCHED) { retirement.complete { true } }
        entered.await()
        val duplicate = try {
            retirement.complete { true }
            null
        } catch (failure: Throwable) {
            failure
        }
        assertIs<IllegalStateException>(duplicate)
        release.complete(Unit)
        assertEquals(Outcome.Success(Unit), first.await())
        assertEquals(1, rawCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun `fatal logout defect still disconnects and closes before propagating unchanged`() = runBlocking {
        val calls = mutableListOf<String>()
        val fatal = AssertionError("logout defect")
        val disconnectFailure = IllegalStateException("disconnect decision failed")
        val retirement = UserLogoutRetirementCapability(
            logoutRpc = {
                calls += "logout"
                throw fatal
            },
            closeSession = { disconnect -> calls += "close-$disconnect" },
        )

        val thrown = assertFailsWith<AssertionError> {
            retirement.complete {
                calls += "disconnect"
                throw disconnectFailure
            }
        }

        assertSame(fatal, thrown)
        assertEquals(listOf("logout", "disconnect", "close-true"), calls)
        assertTrue(thrown.suppressedExceptions.any { it === disconnectFailure })
    }

    @Test
    fun `every resource is released when any individual close hook throws`() {
        repeat(5) { failingIndex ->
            val released = mutableListOf<Int>()
            val actions = Array(5) { index ->
                "resource-$index" to {
                    released += index
                    if (index == failingIndex) error("close-$index")
                }
            }

            val failures = releaseAllSessionResources(*actions)

            assertEquals(listOf(0, 1, 2, 3, 4), released)
            assertEquals(listOf("resource-$failingIndex"), failures.map { it.first })
        }
    }

    @Test
    fun `fatal resource failure propagates unchanged after every owner is released`() {
        val released = mutableListOf<String>()
        val ordinary = IllegalStateException("ordinary cleanup")
        val fatal = AssertionError("fatal cleanup")

        val thrown = assertFailsWith<AssertionError> {
            releaseAllSessionResources(
                "ordinary" to {
                    released += "ordinary"
                    throw ordinary
                },
                "fatal" to {
                    released += "fatal"
                    throw fatal
                },
                "later" to { released += "later" },
            )
        }

        assertSame(fatal, thrown)
        assertEquals(listOf("ordinary", "fatal", "later"), released)
        assertTrue(thrown.suppressedExceptions.any { it === ordinary })
    }

    @Test
    fun `session work gate reentrant close is terminal and unwinds before later publication`() {
        val gate = SessionWorkGate("test worker")
        val lease = gate.lease()
        var publishedAfterClose = false

        assertFailsWith<SessionWorkGateReentrantCloseException> {
            gate.use(lease) {
                gate.close()
                publishedAfterClose = true
            }
        }

        assertFalse(publishedAfterClose)
        assertFalse(gate.runIfActive(lease) { publishedAfterClose = true })
        assertFalse(publishedAfterClose)
    }

    @Test
    fun `construction rollback releases every acquired owner in reverse order`() {
        repeat(5) { failingStage ->
            val rollback = SessionConstructionRollback()
            val released = mutableListOf<Int>()
            assertFailsWith<ConstructionFailure> {
                repeat(5) { stage ->
                    rollback.own("stage-$stage") { released += stage }
                    if (stage == failingStage) throw ConstructionFailure()
                }
            }

            assertTrue(rollback.rollback().isEmpty())
            assertEquals((failingStage downTo 0).toList(), released)
        }
    }

    @Test
    fun `construction handoff prevents rollback from closing live session owners`() {
        val rollback = SessionConstructionRollback()
        var releases = 0
        rollback.own("live") { releases += 1 }

        rollback.handOff()

        assertTrue(rollback.rollback().isEmpty())
        assertEquals(0, releases)
    }

    @Test
    fun `construction rollback drains reverse owners before propagating fatal and stays terminal`() {
        val rollback = SessionConstructionRollback()
        val released = mutableListOf<String>()
        val fatal = AssertionError("rollback defect")
        rollback.own("first") { released += "first" }
        rollback.own("fatal") {
            released += "fatal"
            throw fatal
        }
        rollback.own("last") { released += "last" }

        val thrown = assertFailsWith<AssertionError> { rollback.rollback() }

        assertSame(fatal, thrown)
        assertEquals(listOf("last", "fatal", "first"), released)
        assertTrue(rollback.rollback().isEmpty())
    }

    private class ConstructionFailure : RuntimeException()
}

private inline fun captureFailureWithoutCoroutineRecovery(block: () -> Unit): Throwable {
    try {
        block()
    } catch (throwable: Throwable) {
        return throwable
    }
    return AssertionError("Expected failure")
}
