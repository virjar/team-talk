package com.virjar.tk.shared.client

import com.virjar.tk.shared.bot.admitImBotAuthentication
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthCredentialAdmissionTest {

    @Test
    fun `credential commit finishes before authentication enters synchronization`() = runTest {
        var state = ConnectionState.CONNECTED
        var committed = false
        val coordinator = coordinator(
            scope = this,
            state = { state },
            transition = { state = it },
            close = { _, _ -> error("must not close") },
            onAuthResult = { success, _, _, _, _, _, _, _ ->
                assertTrue(success)
                assertEquals(ConnectionState.CONNECTED, state)
                committed = true
            },
        )
        coordinator.prepareAuthentication(authRequest())
        val attempt = assertNotNull(coordinator.currentAuthenticationAttempt())

        coordinator.handleAuthResponseAfterTestNegotiation(1L, successResponse("refresh-rotated"))

        assertTrue(committed)
        assertEquals(ConnectionState.SYNCHRONIZING, state)
        val reconnectCredentials = assertNotNull(coordinator.authenticationPayloadForTest())
        assertEquals(2, reconnectCredentials.authType)
        assertEquals("refresh-rotated", reconnectCredentials.refreshToken)
        assertNull(reconnectCredentials.password)
        assertNull(reconnectCredentials.username)
        assertNull(reconnectCredentials.name)
        assertSame(attempt, coordinator.currentAuthenticationAttempt())
    }

    @Test
    fun `credential commit failure closes terminal auth without entering synchronization`() = runTest {
        var state = ConnectionState.CONNECTED
        var hookCalls = 0
        val closes = mutableListOf<Pair<String, Throwable?>>()
        val session = UserSession().apply {
            onAuthSuccess(
                "uid-1",
                "agent-user",
                "Old name",
                "refresh-old",
                "access-old",
                TEST_SYNC_DATASET_ID,
            )
        }
        val identityEpochBefore = session.httpCredentialsSnapshot().identityEpoch
        val coordinator = coordinator(
            scope = this,
            state = { state },
            transition = { state = it },
            close = { reason, failure -> closes += reason to failure },
            onAuthResult = { success, uid, username, name, refresh, access, datasetId, _ ->
                assertTrue(success)
                admitImBotAuthentication(
                    userSession = session,
                    uid = requireNotNull(uid),
                    username = requireNotNull(username),
                    displayName = name,
                    refreshToken = requireNotNull(refresh),
                    accessToken = access,
                    datasetId = checkNotNull(datasetId),
                    onRefreshCredentials = { _, _, _ ->
                        hookCalls += 1
                        assertEquals(ConnectionState.CONNECTED, state)
                        assertEquals("refresh-old", session.refreshToken)
                        assertEquals("access-old", session.accessToken)
                        throw CredentialCommitFailure()
                    },
                )
            },
        )
        coordinator.prepareAuthentication(authRequest())

        coordinator.handleAuthResponseAfterTestNegotiation(1L, successResponse("refresh-not-durable"))

        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertTrue(coordinator.isAuthenticationTerminal())
        assertEquals(null, coordinator.authenticationPayloadForTest())
        assertNull(coordinator.currentAuthenticationAttempt())
        assertEquals(1, closes.size)
        assertTrue(closes.single().first.contains("credential admission"))
        assertTrue(closes.single().second is CredentialCommitFailure)
        assertFalse(state == ConnectionState.SYNCHRONIZING)
        assertEquals(1, hookCalls)
        assertEquals("uid-1", session.uid)
        assertEquals("refresh-old", session.refreshToken)
        assertEquals(null, session.accessToken)
        assertEquals(LOCAL_CREDENTIAL_COMMIT_FAILURE_REASON, session.authFailureReason)
        assertEquals(identityEpochBefore, session.httpCredentialsSnapshot().identityEpoch)
    }

    @Test
    fun `mismatched reconnect uid never reaches durable hook or synchronization`() = runTest {
        var state = ConnectionState.CONNECTED
        var hookCalls = 0
        val session = UserSession().apply {
            onAuthSuccess(
                "uid-owner",
                "owner",
                "Owner",
                "refresh-owner",
                "access-owner",
                TEST_SYNC_DATASET_ID,
            )
        }
        val coordinator = coordinator(
            scope = this,
            state = { state },
            transition = { state = it },
            close = { _, _ -> },
            onAuthResult = { success, uid, username, name, refresh, access, datasetId, _ ->
                assertTrue(success)
                admitImBotAuthentication(
                    userSession = session,
                    uid = requireNotNull(uid),
                    username = requireNotNull(username),
                    displayName = name,
                    refreshToken = requireNotNull(refresh),
                    accessToken = access,
                    datasetId = checkNotNull(datasetId),
                    onRefreshCredentials = { _, _, _ -> hookCalls++ },
                )
            },
        )
        coordinator.prepareAuthentication(authRequest())

        coordinator.handleAuthResponseAfterTestNegotiation(
            1L,
            successResponse("refresh-other").copy(uid = "uid-other"),
        )

        assertEquals(0, hookCalls)
        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertFalse(state == ConnectionState.SYNCHRONIZING)
    }

    @Test
    fun `different uid is rejected before old event projection can request sync`() = runTest {
        var state = ConnectionState.CONNECTED
        val userSession = UserSession().apply {
            onAuthSuccess(
                "uid-a",
                "a",
                "Account A",
                "refresh-a",
                "access-a",
                TEST_SYNC_DATASET_ID,
            )
        }
        val writes = mutableListOf<IProto>()
        val closes = mutableListOf<Pair<String, Throwable?>>()
        var oldCursorReads = 0
        val coordinator = AuthSyncCoordinator(
            connectionState = { state },
            isConnectionGenerationCurrent = { generation -> generation == 1L },
            transitionTo = { state = it },
            connectionScope = { this },
            writeProtocol = { proto -> writes += proto; true },
            closeTransport = { reason, failure -> closes += reason to failure },
            retryTransport = { _, reason -> closes += reason to null },
            onAuthenticationFailureObserved = null,
            onAuthenticationAccepted = {},
            onAuthResult = { success, uid, username, name, refresh, access, datasetId, _ ->
                if (success) {
                    userSession.onAuthSuccess(
                        checkNotNull(uid), username, name, refresh, access, checkNotNull(datasetId),
                    )
                }
            },
        )
        coordinator.installEventSync(
            owner = Any(),
            expectedUid = "uid-a",
            wireAdmission = SessionOutboundLease(),
            datasetId = { TEST_SYNC_DATASET_ID },
            cursor = { oldCursorReads += 1; 99L },
            processBatch = { _, _ -> error("old projection must not run") },
            applyCheckpoint = { _, _ -> error("old projection must not checkpoint") },
        )
        coordinator.prepareAuthentication(authRequest())

        coordinator.handleAuthResponseAfterTestNegotiation(1L, successResponse("refresh-b").copy(uid = "uid-b"))

        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertTrue(coordinator.isAuthenticationTerminal())
        assertEquals(0, oldCursorReads)
        assertTrue(writes.none { it is com.virjar.tk.protocol.payload.SyncRequestPayload }, "old account cursor emitted a SYNC_REQUEST for uid-b")
        assertEquals("uid-a", userSession.uid)
        assertEquals(1, closes.size)
        assertTrue(closes.single().first.contains("event projection owner"))
    }

    @Test
    fun `cold refresh uid mismatch is rejected before success hook or synchronization`() = runTest {
        var state = ConnectionState.CONNECTED
        var successHooks = 0
        val writes = mutableListOf<IProto>()
        val closes = mutableListOf<String>()
        val coordinator = coordinator(
            scope = this,
            state = { state },
            transition = { state = it },
            close = { reason, _ -> closes += reason },
            onAuthResult = { success, _, _, _, _, _, _, _ -> if (success) successHooks += 1 },
            writeProtocol = { proto -> writes += proto; true },
        )
        coordinator.prepareAuthentication(authRequest().copy(authType = 2), expectedUid = "uid-b")

        coordinator.handleAuthResponseAfterTestNegotiation(1L, successResponse("rotated-for-wrong-owner"))

        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertEquals(0, successHooks)
        assertTrue(writes.none { it is com.virjar.tk.protocol.payload.SyncRequestPayload })
        assertTrue(closes.single().contains("credential owner"))
    }

    private fun coordinator(
        scope: CoroutineScope,
        state: () -> ConnectionState,
        transition: (ConnectionState) -> Unit,
        close: (String, Throwable?) -> Unit,
        onAuthResult: (
            Boolean,
            String?,
            String?,
            String?,
            String?,
            String?,
            String?,
            String?,
        ) -> Unit,
        writeProtocol: (IProto) -> Boolean = { true },
    ): AuthSyncCoordinator = AuthSyncCoordinator(
        connectionState = state,
        isConnectionGenerationCurrent = { generation -> generation == 1L },
        transitionTo = transition,
        connectionScope = { scope },
        writeProtocol = writeProtocol,
        closeTransport = close,
        retryTransport = { _, reason -> close(reason, null) },
        onAuthenticationFailureObserved = null,
        onAuthenticationAccepted = {},
        onAuthResult = onAuthResult,
    )

    private fun AuthSyncCoordinator.authenticationPayloadForTest(): AuthRequestPayload? {
        var payload: AuthRequestPayload? = null
        val admitted = sendAuthenticationIfActive(1L) { auth ->
            payload = auth
            true
        }
        return payload.takeIf { admitted }
    }

    private fun authRequest() = AuthRequestPayload(
        authType = 0,
        username = "agent-user",
        password = "bootstrap-secret",
        deviceId = "agent-device",
        correlationId = "credential-test-token",
        connectionGeneration = 1L,
    )

    private fun successResponse(refreshToken: String) = AuthResponsePayload(
        code = AuthResponsePayload.CODE_OK,
        uid = "uid-1",
        username = "agent-user",
        name = "Agent User",
        refreshToken = refreshToken,
        accessToken = "access-token",
        datasetId = TEST_SYNC_DATASET_ID,
    )

    private class CredentialCommitFailure : RuntimeException()
}
