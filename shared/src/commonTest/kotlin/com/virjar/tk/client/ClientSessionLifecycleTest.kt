package com.virjar.tk.client

import com.virjar.tk.Outcome
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.rpc.RpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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

        // The lifecycle gate preserves raw transport capability for the sealed retirement owner.
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

    private class ConstructionFailure : RuntimeException()
}

private suspend fun <T> kotlinx.coroutines.Deferred<T>.awaitFailure(): Throwable {
    var failure: Throwable? = null
    try {
        await()
    } catch (throwable: Throwable) {
        failure = throwable
    }
    return failure ?: AssertionError("Expected failure")
}
