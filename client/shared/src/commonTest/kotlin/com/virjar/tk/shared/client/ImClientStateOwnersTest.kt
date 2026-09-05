package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
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
    fun `authentication failure is terminal until explicit credentials replace it`() = runTest {
        val observed = mutableListOf<AuthenticationFailureKind>()
        val harness = AuthSyncHarness(
            scope = this,
            onFailureObserved = { failure -> observed += failure.kind },
        )
        harness.coordinator.prepareAuthentication(authRequest())
        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            harness.connectionGeneration,
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED,
                reason = "upgrade",
            ),
        )

        assertTrue(harness.coordinator.isAuthenticationTerminal())
        assertEquals(ConnectionState.AUTH_FAILED, harness.state)
        assertNotNull(harness.coordinator.authenticationFailure.value)
        assertEquals(listOf(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED), observed)

        harness.coordinator.prepareAuthentication(authRequest(username = "replacement"))
        assertFalse(harness.coordinator.isAuthenticationTerminal())
        assertEquals("replacement", harness.authenticationPayloadForTest()?.username)
    }

    @Test
    fun `retryable server auth failure retains refresh owner and requests reconnect`() = runTest {
        val harness = AuthSyncHarness(this)
        val refresh = authRequest().copy(
            authType = 2,
            username = null,
            password = null,
            refreshToken = "refresh-owner",
        )
        harness.coordinator.prepareAuthentication(refresh, expectedUid = "u1")

        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            harness.connectionGeneration,
            AuthResponsePayload(code = AuthResponsePayload.CODE_SERVER_MAINTENANCE),
        )

        assertFalse(harness.coordinator.isAuthenticationTerminal())
        val retryAuth = checkNotNull(harness.authenticationPayloadForTest())
        assertEquals(
            refresh.copy(
                correlationId = retryAuth.correlationId,
                connectionGeneration = retryAuth.connectionGeneration,
            ),
            retryAuth,
        )
        assertEquals(ConnectionState.AUTH_FAILED, harness.state)
        assertEquals(listOf("Retryable authentication failure: SERVER_MAINTENANCE"), harness.retries)
    }

    @Test
    fun `password auth server back-pressure is terminal and never retried`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.prepareAuthentication(authRequest())

        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            harness.connectionGeneration,
            AuthResponsePayload(code = AuthResponsePayload.CODE_SERVER_MAINTENANCE),
        )

        assertEquals(ConnectionState.AUTH_FAILED, harness.state)
        assertTrue(harness.coordinator.isAuthenticationTerminal())
        assertEquals(null, harness.authenticationPayloadForTest())
        assertTrue(harness.retries.isEmpty())
    }

    @Test
    fun `password transport end releases secret and publishes retryable login state`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.prepareAuthentication(authRequest())
        val attempt = harness.coordinator.currentAuthenticationAttempt()

        assertFalse(harness.coordinator.onAuthenticationTransportAttemptEnded(attempt))

        assertTrue(harness.coordinator.isAuthenticationTerminal())
        assertEquals(null, harness.authenticationPayloadForTest())
        assertEquals(
            AuthenticationAttemptFailureKind.TRANSPORT_UNAVAILABLE,
            harness.coordinator.authenticationAttemptFailure.value?.kind,
        )
        assertEquals(null, harness.coordinator.authenticationFailure.value)
    }

    @Test
    fun `refresh transport end preserves credential and reconnect owner`() = runTest {
        val harness = AuthSyncHarness(this)
        val refresh = authRequest().copy(
            authType = 2,
            username = null,
            password = null,
            refreshToken = "refresh-owner",
        )
        harness.coordinator.prepareAuthentication(refresh, expectedUid = "u1")
        val attempt = harness.coordinator.currentAuthenticationAttempt()

        assertTrue(harness.coordinator.onAuthenticationTransportAttemptEnded(attempt))

        val reconnectAuth = checkNotNull(harness.authenticationPayloadForTest())
        assertEquals(
            refresh.copy(
                correlationId = reconnectAuth.correlationId,
                connectionGeneration = reconnectAuth.connectionGeneration,
            ),
            reconnectAuth,
        )
        assertFalse(harness.coordinator.isAuthenticationTerminal())
        assertEquals(null, harness.coordinator.authenticationAttemptFailure.value)
    }

    @Test
    fun `late A transport cleanup cannot retire or erase B`() = runTest {
        val attempts = AuthenticationAttemptAdmission()
        val harness = AuthSyncHarness(this, authenticationAttempts = attempts)
        val attemptA = attempts.reserve()
        assertTrue(
            harness.coordinator.prepareAuthentication(
                authRequest(username = "a"),
                expectedUid = null,
                attempt = attemptA,
            ),
        )
        val logicalA = harness.coordinator.currentAuthenticationAttempt()
        val attemptB = attempts.reserve()

        assertFalse(harness.coordinator.onAuthenticationTransportAttemptEnded(logicalA))
        assertEquals(null, harness.coordinator.authenticationAttemptFailure.value)
        assertTrue(
            harness.coordinator.prepareAuthentication(
                authRequest(username = "b"),
                expectedUid = null,
                attempt = attemptB,
            ),
        )

        assertEquals("b", harness.authenticationPayloadForTest()?.username)
        assertFalse(harness.coordinator.isAuthenticationTerminal())
    }

    @Test
    fun `terminal transport retirement releases secret without reporting network failure`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.prepareAuthentication(authRequest())
        val attempt = harness.coordinator.currentAuthenticationAttempt()

        harness.coordinator.onAuthenticationTransportRetired(attempt)

        assertEquals(null, harness.authenticationPayloadForTest())
        assertEquals(null, harness.coordinator.authenticationAttemptFailure.value)
        assertFalse(checkNotNull(attempt).isActive())
    }

    @Test
    fun `queued A responses are wholly inert after B reserves authentication ownership`() = runTest {
        val staleResponses = listOf(
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_OK,
                uid = "uid-a",
                username = "a",
                name = "Account A",
                refreshToken = "refresh-a",
                accessToken = "access-a",
                datasetId = TEST_SYNC_DATASET_ID,
            ) to emptyList(),
            AuthResponsePayload(code = AuthResponsePayload.CODE_AUTH_FAILED) to emptyList(),
            AuthResponsePayload(code = AuthResponsePayload.CODE_SERVER_MAINTENANCE) to emptyList(),
            AuthResponsePayload(code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED) to
                listOf(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED),
        )

        staleResponses.forEach { (staleResponse, expectedDeploymentFacts) ->
            val attempts = AuthenticationAttemptAdmission()
            val observed = mutableListOf<AuthenticationFailureKind>()
            val harness = AuthSyncHarness(
                scope = this,
                onFailureObserved = { failure -> observed += failure.kind },
                authenticationAttempts = attempts,
            )
            val attemptA = attempts.reserve()
            assertTrue(
                harness.coordinator.prepareAuthentication(
                    authRequest(username = "a"),
                    expectedUid = null,
                    attempt = attemptA,
                ),
            )

            val attemptB = attempts.reserve()
            harness.coordinator.handleAuthResponseAfterTestNegotiation(harness.connectionGeneration, staleResponse)

            assertEquals(ConnectionState.CONNECTED, harness.state)
            assertEquals(null, harness.coordinator.authenticationFailure.value)
            assertEquals(null, harness.authenticationPayloadForTest())
            assertEquals(expectedDeploymentFacts, observed)
            assertTrue(harness.authResults.isEmpty())
            assertEquals(
                listOf("Discarding response from a retired authentication attempt"),
                harness.retries,
            )

            assertTrue(
                harness.coordinator.prepareAuthentication(
                    authRequest(username = "b"),
                    expectedUid = null,
                    attempt = attemptB,
                ),
            )
            harness.coordinator.handleAuthResponseAfterTestNegotiation(
                harness.connectionGeneration,
                AuthResponsePayload(
                    code = AuthResponsePayload.CODE_OK,
                    uid = "uid-b",
                    username = "b",
                    name = "Account B",
                    refreshToken = "refresh-b",
                    accessToken = "access-b",
                    datasetId = TEST_SYNC_DATASET_ID,
                ),
            )
            assertEquals(listOf(true), harness.authResults)
            assertEquals(ConnectionState.SYNCHRONIZING, harness.state)
        }
    }

    @Test
    fun `deployment observer replacing A cannot apply A response or cleanup to B`() = runTest {
        val attempts = AuthenticationAttemptAdmission()
        val observed = mutableListOf<AuthenticationFailureKind>()
        lateinit var harness: AuthSyncHarness
        harness = AuthSyncHarness(
            scope = this,
            onFailureObserved = { failure ->
                observed += failure.kind
                val attemptB = attempts.reserve()
                assertTrue(
                    harness.coordinator.prepareAuthentication(
                        authRequest(username = "b"),
                        expectedUid = null,
                        attempt = attemptB,
                        startTransport = { harness.connectionGeneration += 1L },
                    ),
                )
            },
            authenticationAttempts = attempts,
        )
        val attemptA = attempts.reserve()
        assertTrue(
            harness.coordinator.prepareAuthentication(
                authRequest(username = "a"),
                expectedUid = null,
                attempt = attemptA,
            ),
        )
        val generationA = harness.connectionGeneration

        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            generationA,
            AuthResponsePayload(code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED),
        )

        assertEquals(listOf(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED), observed)
        assertEquals(ConnectionState.CONNECTED, harness.state)
        assertEquals("b", harness.authenticationPayloadForTest()?.username)
        assertEquals(null, harness.coordinator.authenticationFailure.value)
        assertFalse(harness.coordinator.isAuthenticationTerminal())
        assertTrue(harness.retries.isEmpty(), "cleanup(A) must not close B's connection generation")
        assertTrue(harness.authResults.isEmpty())

        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            harness.connectionGeneration,
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_OK,
                uid = "uid-b",
                username = "b",
                name = "Account B",
                refreshToken = "refresh-b",
                accessToken = "access-b",
                datasetId = TEST_SYNC_DATASET_ID,
            ),
        )
        assertEquals(listOf(true), harness.authResults)
        assertEquals(ConnectionState.SYNCHRONIZING, harness.state)
    }

    @Test
    fun `deployment observer generation advance makes old response inert with same attempt`() = runTest {
        val observed = mutableListOf<AuthenticationFailureKind>()
        lateinit var harness: AuthSyncHarness
        harness = AuthSyncHarness(
            scope = this,
            onFailureObserved = { failure ->
                observed += failure.kind
                harness.connectionGeneration += 1L
            },
        )
        harness.coordinator.prepareAuthentication(authRequest(username = "a"))
        val generationA = harness.connectionGeneration

        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            generationA,
            AuthResponsePayload(code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED),
        )

        assertEquals(listOf(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED), observed)
        assertEquals(ConnectionState.CONNECTED, harness.state)
        assertEquals("a", harness.authenticationPayloadForTest()?.username)
        assertEquals(null, harness.coordinator.authenticationFailure.value)
        assertFalse(harness.coordinator.isAuthenticationTerminal())
        assertTrue(harness.retries.isEmpty())
        assertTrue(harness.authResults.isEmpty())
    }

    @Test
    fun `unsolicited protocol response outside connected auth phase cannot persist a fence`() = runTest {
        val observed = mutableListOf<AuthenticationFailureKind>()
        val harness = AuthSyncHarness(
            scope = this,
            onFailureObserved = { failure -> observed += failure.kind },
        )
        harness.authenticateSuccessfully()
        assertEquals(ConnectionState.SYNCHRONIZING, harness.state)

        harness.coordinator.handleAuthResponseAfterTestNegotiation(
            harness.connectionGeneration,
            AuthResponsePayload(code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED),
        )

        assertTrue(observed.isEmpty())
        assertTrue(harness.coordinator.authenticationFailure.value == null)
        assertTrue(harness.closes.single().contains("Unexpected AUTH response"))
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
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 41L },
            processBatch = { events, reportProgress ->
                projectedPages += 1
                projectionGate.await()
                events.forEach { reportProgress(it.eventId) }
                events.last().eventId
            },
            applyCheckpoint = { _, _ -> 0L },
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
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 41L },
            processBatch = { events, _ -> events.last().eventId },
            applyCheckpoint = { _, _ -> 0L },
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
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 0L },
            processBatch = { events, _ -> events.last().eventId },
            applyCheckpoint = { _, _ -> 0L },
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
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { cursorReads += 1; 73L },
            processBatch = { _, _ -> error("retired projection must not run") },
            applyCheckpoint = { _, _ -> error("retired projection must not checkpoint") },
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
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 7L },
            processBatch = { events, _ ->
                projectionGate.await()
                events.last().eventId
            },
            applyCheckpoint = { _, _ -> 0L },
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
    fun `sync reset applies checkpoint once and restarts from its base`() = runTest {
        val harness = AuthSyncHarness(this)
        var resets = 0
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 99L },
            processBatch = { events, _ -> events.last().eventId },
            applyCheckpoint = { _, reportProgress ->
                resets += 1
                reportProgress()
                50L
            },
        )
        harness.authenticateSuccessfully()

        harness.coordinator.handleSyncReset(SyncResetPayload(TEST_SYNC_DATASET_ID))
        advanceUntilIdle()

        assertEquals(1, resets)
        assertEquals(listOf(99L, 50L), harness.syncRequests())
        assertEquals(50L, harness.coordinator.eventSyncCursor.value)
        // 一次脉冲上报检查点页的工作；第二次发布已安装的基础游标。
        assertEquals(2L, harness.coordinator.eventSyncProgress.value)

        harness.coordinator.handleSyncReset(SyncResetPayload(TEST_SYNC_DATASET_ID))
        assertEquals(1, resets)
        assertEquals("Unexpected, overlapping, or repeated SYNC_RESET", harness.closes.single())
    }

    @Test
    fun `checkpoint request admits an immediate loopback tail response`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 99L },
            processBatch = { events, _ -> events.last().eventId },
            applyCheckpoint = { _, _ -> 50L },
        )
        harness.authenticateSuccessfully()
        harness.onWrite = { packet ->
            if ((packet as? SyncRequestPayload)?.lastEventId == 50L) {
                harness.coordinator.handleSyncBatch(SyncBatchPayload(listOf(notify(51L))))
            }
        }

        harness.coordinator.handleSyncReset(SyncResetPayload(TEST_SYNC_DATASET_ID))
        advanceUntilIdle()

        assertEquals(listOf(99L, 50L, 51L), harness.syncRequests())
        assertTrue(harness.closes.isEmpty())
    }

    @Test
    fun `persisted page request admits an immediate loopback next page`() = runTest {
        val harness = AuthSyncHarness(this)
        harness.coordinator.installEventSync(
            owner = this,
            expectedUid = null,
            wireAdmission = SessionOutboundLease(),
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { 0L },
            processBatch = { events, _ -> events.last().eventId },
            applyCheckpoint = { _, _ -> error("not expected") },
        )
        harness.authenticateSuccessfully()
        harness.onWrite = { packet ->
            if ((packet as? SyncRequestPayload)?.lastEventId == 1L) {
                harness.coordinator.handleSyncBatch(SyncBatchPayload(listOf(notify(2L))))
            }
        }

        harness.coordinator.handleSyncBatch(SyncBatchPayload(listOf(notify(1L))))
        advanceUntilIdle()

        assertEquals(listOf(0L, 1L, 2L), harness.syncRequests())
        assertTrue(harness.closes.isEmpty())
    }

    @Test
    fun `ACK registry completes matching waiter and timeout remains typed`() = runTest {
        val router = packetRouter()
        val accepted = router.sendAndAwaitAck("chat-1", "accepted", 1_000L, sessionOwner = Any()) {
            router.route(1L, MessageAckPayload("chat-1", "accepted", 7L, 0))
        }
        assertEquals(7L, accepted.serverSeq)
        assertEquals(0, accepted.code)

        val timedOut = router.sendAndAwaitAck("chat-1", "timeout", 25L, sessionOwner = Any()) {}
        assertEquals(-1, timedOut.code)
        assertEquals("ACK timeout", timedOut.reason)
    }

    @Test
    fun `transport close cancels every pending ACK waiter`() = runTest {
        supervisorScope {
            val router = packetRouter()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                router.sendAndAwaitAck("chat-1", "cancelled", 10_000L, sessionOwner = Any()) {}
            }

            router.onTransportDisconnected()

            assertFailsWith<TransportUnavailableException> { waiter.await() }
        }
    }

    @Test
    fun `retired account ACK namespace rejects a replacement account ACK`() = runTest {
        supervisorScope {
            val router = packetRouter()
            val lease = SessionOutboundLease()
            val registered = CompletableDeferred<Unit>()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                router.sendAndAwaitAck(
                    chatId = "chat-1",
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
            router.route(2L, MessageAckPayload("chat-1", "account-a-message", 99L, 0))

            assertFailsWith<CancellationException> { waiter.await() }
        }
    }

    @Test
    fun `failed inbound tryEmit closes transport instead of dropping packet`() = runTest {
        val closes = mutableListOf<String>()
        val retries = mutableListOf<String>()
        val router = packetRouter(
            inboundBufferCapacity = 0,
            closeTransport = { reason -> closes += reason },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            router.packets.collect { /* 刻意不做带缓冲的交接。 */ }
        }

        router.route(1L, ResponsePayload(requestId = 1, status = 0, payload = null))

        assertEquals(1, closes.size)
        assertTrue(closes.single().contains("ResponsePayload"))
        collector.cancelAndJoin()
    }

    @Test
    fun `AUTH response is consumed by auth owner and never enters generic packet broadcast`() = runTest {
        val handled = mutableListOf<Pair<Long, AuthResponsePayload>>()
        val broadcast = mutableListOf<IProto>()
        val router = packetRouter(
            handleAuthResponse = { generation, response -> handled += generation to response },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            router.packets.collect { packet -> broadcast += packet }
        }
        val response = AuthResponsePayload(code = AuthResponsePayload.CODE_AUTH_FAILED)

        router.route(1L, response)
        runCurrent()

        assertEquals(listOf(1L to response), handled)
        assertTrue(broadcast.isEmpty())
        collector.cancelAndJoin()
    }

    private class AuthSyncHarness(
        scope: CoroutineScope,
        onFailureObserved: ((AuthenticationFailure) -> Unit)? = null,
        authenticationAttempts: AuthenticationAttemptAdmission =
            AuthenticationAttemptAdmission(),
    ) {
        var state = ConnectionState.CONNECTED
        var connectionGeneration = 1L
        val writes = mutableListOf<IProto>()
        val closes = mutableListOf<String>()
        val retries = mutableListOf<String>()
        val authResults = mutableListOf<Boolean>()
        var onWrite: (IProto) -> Unit = {}
        val coordinator = AuthSyncCoordinator(
            connectionState = { state },
            isConnectionGenerationCurrent = { generation ->
                generation == connectionGeneration
            },
            transitionTo = { next -> state = next },
            connectionScope = { scope },
            writeProtocol = { proto ->
                writes.add(proto)
                onWrite(proto)
                true
            },
            closeTransport = { reason, _ -> closes += reason },
            retryTransport = { generation, reason ->
                if (generation == connectionGeneration) retries += reason
            },
            onAuthenticationFailureObserved = onFailureObserved,
            onAuthenticationAccepted = {},
            onAuthResult = { success, _, _, _, _, _, _, _ -> authResults += success },
            authenticationAttempts = authenticationAttempts,
        )

        fun authenticateSuccessfully() {
            coordinator.prepareAuthentication(authRequest())
            coordinator.handleAuthResponseAfterTestNegotiation(
                connectionGeneration,
                AuthResponsePayload(
                    code = AuthResponsePayload.CODE_OK,
                    uid = "u1",
                    username = "user",
                    name = "User",
                    refreshToken = "refresh-2",
                    accessToken = "access",
                    datasetId = TEST_SYNC_DATASET_ID,
                ),
            )
            assertEquals(2, authenticationPayloadForTest()?.authType)
            assertEquals("refresh-2", authenticationPayloadForTest()?.refreshToken)
        }

        fun authenticationPayloadForTest(): AuthRequestPayload? {
            var payload: AuthRequestPayload? = null
            val admitted = coordinator.sendAuthenticationIfActive(connectionGeneration) { auth ->
                payload = auth
                true
            }
            return payload.takeIf { admitted }
        }

        fun syncRequests(): List<Long> = writes.mapNotNull { proto ->
            (proto as? SyncRequestPayload)?.lastEventId
        }
    }

    private fun packetRouter(
        inboundBufferCapacity: Int = 64,
        closeTransport: (String) -> Unit = {},
        handleAuthResponse: (Long, AuthResponsePayload) -> Unit = { _, _ -> },
    ) = PacketRouter(
        connectionState = { ConnectionState.AUTHENTICATED },
        handleAuthResponse = handleAuthResponse,
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
            correlationId = "state-owner-test-token",
            connectionGeneration = 1L,
        )

        fun notify(eventId: Long) = NotifyPayload(
            eventId = eventId,
            notifyType = 1,
            payload = null,
        )
    }
}
