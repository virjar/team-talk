package com.virjar.tk.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImClientStateOwnersTest {

    @Test
    fun `retired transport generation can never become current again`() {
        val generations = ConnectionGeneration()
        val first = generations.next()
        val replacement = generations.next()

        assertFalse(generations.matches(first))
        assertTrue(generations.matches(replacement))

        generations.invalidate()
        assertFalse(generations.matches(replacement))
    }

    @Test
    fun `authentication failure is terminal until explicit credentials replace it`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.prepareAuthentication(authRequest())
        harness.coordinator.handleAuthResponse(
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED,
                reason = "upgrade",
            ),
        )

        assertTrue(harness.coordinator.isAuthenticationTerminal())
        assertEquals(ConnectionState.AUTH_FAILED, harness.state)
        assertNotNull(harness.coordinator.authenticationFailure.value)

        harness.coordinator.prepareAuthentication(authRequest(username = "replacement"))
        assertFalse(harness.coordinator.isAuthenticationTerminal())
        assertEquals("replacement", harness.coordinator.authenticationPayload()?.username)
    }

    @Test
    fun `sync page advances cursor only after its sequential projection completes`() = runTest {
        val harness = AuthSyncHarness(this)
        val projectionGate = CompletableDeferred<Unit>()
        var projectedPages = 0
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            cursor = { 41L },
            processBatch = { events, reportProgress ->
                projectedPages += 1
                projectionGate.await()
                events.forEach { reportProgress(it.eventId) }
                events.last().eventId
            },
            reset = { 0L },
        )
        harness.authenticateSuccessfully()

        assertEquals(listOf(41L), harness.syncRequests())
        val events = listOf(notify(42L), notify(43L))
        harness.coordinator.handleSyncBatch(SyncBatchPayload(events))
        runCurrent()

        assertEquals(1, projectedPages)
        assertEquals(41L, harness.coordinator.eventSyncCursor.value)
        assertEquals(listOf(41L), harness.syncRequests())

        projectionGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(43L, harness.coordinator.eventSyncCursor.value)
        assertEquals(listOf(41L, 43L), harness.syncRequests())
        harness.coordinator.handleSyncReady()
        assertEquals(ConnectionState.AUTHENTICATED, harness.state)
        assertTrue(harness.closes.isEmpty())
    }

    @Test
    fun `sync page cannot skip a durable per-user sequence`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            cursor = { 41L },
            processBatch = { events, _ -> events.last().eventId },
            reset = { 0L },
        )
        harness.authenticateSuccessfully()

        harness.coordinator.handleSyncBatch(SyncBatchPayload(listOf(notify(43L))))

        assertEquals(
            listOf("Sync events are not contiguous after requested cursor=41"),
            harness.closes,
        )
        assertEquals(41L, harness.coordinator.eventSyncCursor.value)
        assertEquals(listOf(41L), harness.syncRequests())
    }

    @Test
    fun `removing the projection owner during sync closes before ready`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            cursor = { 0L },
            processBatch = { events, _ -> events.last().eventId },
            reset = { 0L },
        )
        harness.authenticateSuccessfully()
        harness.coordinator.removeEventSync(this)

        assertEquals(ConnectionState.SYNCHRONIZING, harness.state)
        assertEquals(
            listOf("Event sync projection owner was removed during synchronization"),
            harness.closes,
        )
    }

    @Test
    fun `retired session sync admission rejects auth race before cursor or wire publication`() = runTest {
        val harness = AuthSyncHarness(this)
        val owner = Any()
        val wireAdmission = SessionOutboundLease()
        var cursorReads = 0
        harness.coordinator.installEventSync(
            owner = owner,
            expectedUid = "u1",
            wireAdmission = wireAdmission,
            cursor = { cursorReads += 1; 73L },
            processBatch = { _, _ -> error("retired projection must not run") },
            reset = { error("retired projection must not reset") },
        )

        wireAdmission.retire()
        harness.authenticateSuccessfully()
        harness.coordinator.handleSyncReady()
        harness.coordinator.removeEventSync(owner)
        harness.coordinator.handleSyncReady()

        assertEquals(ConnectionState.SYNCHRONIZING, harness.state)
        assertEquals(0, cursorReads)
        assertTrue(harness.syncRequests().isEmpty())
        assertTrue(harness.closes.isEmpty())
    }

    @Test
    fun `retired sync coroutine cannot advance a replacement transport`() = runTest {
        val harness = AuthSyncHarness(this)
        val projectionGate = CompletableDeferred<Unit>()
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            cursor = { 7L },
            processBatch = { events, _ ->
                projectionGate.await()
                events.last().eventId
            },
            reset = { 0L },
        )
        harness.authenticateSuccessfully()
        harness.coordinator.handleSyncBatch(SyncBatchPayload(listOf(notify(8L))))
        runCurrent()

        harness.coordinator.onTransportDisconnected()
        projectionGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(-1L, harness.coordinator.eventSyncCursor.value)
        assertEquals(listOf(7L), harness.syncRequests())
        assertTrue(harness.closes.isEmpty())
    }

    @Test
    fun `sync reset clears projection once and restarts from zero`() = runTest {
        val harness = AuthSyncHarness(this)
        var resets = 0
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            cursor = { 99L },
            processBatch = { events, _ -> events.last().eventId },
            reset = {
                resets += 1
                0L
            },
        )
        harness.authenticateSuccessfully()

        harness.coordinator.handleSyncReset()
        advanceUntilIdle()

        assertEquals(1, resets)
        assertEquals(listOf(99L, 0L), harness.syncRequests())
        assertEquals(0L, harness.coordinator.eventSyncCursor.value)

        harness.coordinator.handleSyncReset()
        assertEquals(1, resets)
        assertEquals("Unexpected, overlapping, or repeated SYNC_RESET", harness.closes.single())
    }

    @Test
    fun `ACK registry completes matching waiter and timeout remains typed`() = runTest {
        val router = packetRouter(scope = this)
        val accepted = router.sendAndAwaitAck("accepted", 1_000L) {
            router.route(1L, MessageAckPayload("accepted", 7L, 0))
        }
        assertEquals(7L, accepted.serverSeq)
        assertEquals(0, accepted.code)

        val timedOut = router.sendAndAwaitAck("timeout", 25L) {}
        assertEquals(-1, timedOut.code)
        assertEquals("ACK timeout", timedOut.reason)
    }

    @Test
    fun `transport close cancels every pending ACK waiter`() = runTest {
        supervisorScope {
            val router = packetRouter(scope = this)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                router.sendAndAwaitAck("cancelled", 10_000L) {}
            }

            router.onTransportDisconnected()

            assertFailsWith<AckTransportDisconnectedException> { waiter.await() }
        }
    }

    @Test
    fun `retired account ACK namespace rejects a replacement account ACK`() = runTest {
        supervisorScope {
            val router = packetRouter(scope = this)
            val lease = SessionOutboundLease()
            val registered = CompletableDeferred<Unit>()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                router.sendAndAwaitAck(
                    clientMsgId = "account-a-message",
                    timeoutMs = 10_000L,
                    sessionOwner = lease.ackOwner,
                    sessionLease = lease,
                ) {
                    registered.complete(Unit)
                }
            }
            registered.await()

            lease.retire()
            router.retirePendingAcks(lease.ackOwner)
            router.route(2L, MessageAckPayload("account-a-message", 99L, 0))

            assertFailsWith<CancellationException> { waiter.await() }
        }
    }

    @Test
    fun `failed inbound tryEmit closes transport instead of dropping packet`() = runTest {
        val closes = mutableListOf<String>()
        val router = packetRouter(
            scope = this,
            inboundBufferCapacity = 0,
            closeTransport = { reason -> closes += reason },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            router.packets.collect { /* Deliberately no buffered hand-off. */ }
        }

        router.route(1L, ResponsePayload(requestId = 1, status = 0, payload = null))

        assertEquals(1, closes.size)
        assertTrue(closes.single().contains("ResponsePayload"))
        collector.cancelAndJoin()
    }

    private class AuthSyncHarness(
        scope: CoroutineScope,
    ) {
        var state = ConnectionState.CONNECTED
        val writes = mutableListOf<IProto>()
        val closes = mutableListOf<String>()
        val coordinator = AuthSyncCoordinator(
            connectionState = { state },
            transitionTo = { next -> state = next },
            connectionScope = { scope },
            writeProtocol = { proto -> writes.add(proto) },
            closeTransport = { reason, _ -> closes += reason },
            onAuthenticationAccepted = {},
            publishAuthResponse = {},
            onAuthResult = null,
        )

        fun authenticateSuccessfully() {
            coordinator.prepareAuthentication(authRequest())
            coordinator.handleAuthResponse(
                AuthResponsePayload(
                    code = AuthResponsePayload.CODE_OK,
                    uid = "u1",
                    username = "user",
                    name = "User",
                    refreshToken = "refresh-2",
                    accessToken = "access",
                ),
            )
            assertEquals(2, coordinator.authenticationPayload()?.authType)
            assertEquals("refresh-2", coordinator.authenticationPayload()?.refreshToken)
        }

        fun syncRequests(): List<Long> = writes.mapNotNull { proto ->
            (proto as? SyncRequestPayload)?.lastEventId
        }
    }

    private fun packetRouter(
        scope: CoroutineScope,
        inboundBufferCapacity: Int = 64,
        closeTransport: (String) -> Unit = {},
    ) = PacketRouter(
        connectionState = { ConnectionState.AUTHENTICATED },
        connectionScope = { scope },
        handleAuthResponse = {},
        handleSyncBatch = {},
        handleSyncEvent = {},
        handleSyncReady = {},
        handleSyncReset = {},
        writeControl = { true },
        closeTransport = closeTransport,
        inboundBufferCapacity = inboundBufferCapacity,
    )

    private companion object {
        fun authRequest(username: String = "user") = AuthRequestPayload(
            authType = 0,
            username = username,
            password = "password",
            deviceId = "device",
            deviceName = "Device",
        )

        fun notify(eventId: Long) = NotifyPayload(
            eventId = eventId,
            notifyType = 1,
            payload = null,
        )
    }
}
