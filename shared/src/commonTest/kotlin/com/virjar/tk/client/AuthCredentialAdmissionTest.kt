package com.virjar.tk.client

import com.virjar.tk.bot.admitImBotAuthentication
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            onAuthResult = { success, _, _, _, _, _, _ ->
                assertTrue(success)
                assertEquals(ConnectionState.CONNECTED, state)
                committed = true
            },
        )
        coordinator.prepareAuthentication(authRequest())

        coordinator.handleAuthResponse(successResponse("refresh-rotated"))

        assertTrue(committed)
        assertEquals(ConnectionState.SYNCHRONIZING, state)
        assertEquals("refresh-rotated", coordinator.authenticationPayload()?.refreshToken)
    }

    @Test
    fun `credential commit failure closes terminal auth without entering synchronization`() = runTest {
        var state = ConnectionState.CONNECTED
        var hookCalls = 0
        val closes = mutableListOf<Pair<String, Throwable?>>()
        val session = UserSession().apply {
            onAuthSuccess("uid-1", "agent-user", "Old name", "refresh-old", "access-old")
        }
        val coordinator = coordinator(
            scope = this,
            state = { state },
            transition = { state = it },
            close = { reason, failure -> closes += reason to failure },
            onAuthResult = { success, uid, username, name, refresh, access, _ ->
                assertTrue(success)
                admitImBotAuthentication(
                    userSession = session,
                    uid = requireNotNull(uid),
                    username = requireNotNull(username),
                    displayName = name,
                    refreshToken = requireNotNull(refresh),
                    accessToken = access,
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

        coordinator.handleAuthResponse(successResponse("refresh-not-durable"))

        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertTrue(coordinator.isAuthenticationTerminal())
        assertEquals(null, coordinator.authenticationPayload())
        assertEquals(1, closes.size)
        assertTrue(closes.single().first.contains("credential admission"))
        assertTrue(closes.single().second is CredentialCommitFailure)
        assertFalse(state == ConnectionState.SYNCHRONIZING)
        assertEquals(1, hookCalls)
        assertEquals("uid-1", session.uid)
        assertEquals("refresh-old", session.refreshToken)
        assertEquals("access-old", session.accessToken)
        assertEquals(null, session.authFailureReason)
    }

    @Test
    fun `mismatched reconnect uid never reaches durable hook or synchronization`() = runTest {
        var state = ConnectionState.CONNECTED
        var hookCalls = 0
        val session = UserSession().apply {
            onAuthSuccess("uid-owner", "owner", "Owner", "refresh-owner", "access-owner")
        }
        val coordinator = coordinator(
            scope = this,
            state = { state },
            transition = { state = it },
            close = { _, _ -> },
            onAuthResult = { success, uid, username, name, refresh, access, _ ->
                assertTrue(success)
                admitImBotAuthentication(
                    userSession = session,
                    uid = requireNotNull(uid),
                    username = requireNotNull(username),
                    displayName = name,
                    refreshToken = requireNotNull(refresh),
                    accessToken = access,
                    onRefreshCredentials = { _, _, _ -> hookCalls++ },
                )
            },
        )
        coordinator.prepareAuthentication(authRequest())

        coordinator.handleAuthResponse(
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
            onAuthSuccess("uid-a", "a", "Account A", "refresh-a", "access-a")
        }
        val writes = mutableListOf<IProto>()
        val closes = mutableListOf<Pair<String, Throwable?>>()
        var oldCursorReads = 0
        val coordinator = AuthSyncCoordinator(
            connectionState = { state },
            transitionTo = { state = it },
            connectionScope = { this },
            writeProtocol = { proto -> writes += proto; true },
            closeTransport = { reason, failure -> closes += reason to failure },
            onAuthenticationAccepted = {},
            publishAuthResponse = {},
            onAuthResult = { success, uid, username, name, refresh, access, _ ->
                if (success) userSession.onAuthSuccess(checkNotNull(uid), username, name, refresh, access)
            },
        )
        coordinator.installEventSync(
            owner = Any(),
            expectedUid = "uid-a",
            wireAdmission = SessionOutboundLease(),
            cursor = { oldCursorReads += 1; 99L },
            processBatch = { _, _ -> error("old projection must not run") },
            reset = { error("old projection must not reset") },
        )
        coordinator.prepareAuthentication(authRequest())

        coordinator.handleAuthResponse(successResponse("refresh-b").copy(uid = "uid-b"))

        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertTrue(coordinator.isAuthenticationTerminal())
        assertEquals(0, oldCursorReads)
        assertTrue(writes.isEmpty(), "old account cursor emitted a SYNC_REQUEST for uid-b")
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
            onAuthResult = { success, _, _, _, _, _, _ -> if (success) successHooks += 1 },
            writeProtocol = { proto -> writes += proto; true },
        )
        coordinator.prepareAuthentication(authRequest().copy(authType = 2), expectedUid = "uid-b")

        coordinator.handleAuthResponse(successResponse("rotated-for-wrong-owner"))

        assertEquals(ConnectionState.AUTH_FAILED, state)
        assertEquals(0, successHooks)
        assertTrue(writes.isEmpty())
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
        ) -> Unit,
        writeProtocol: (IProto) -> Boolean = { true },
    ): AuthSyncCoordinator = AuthSyncCoordinator(
        connectionState = state,
        transitionTo = transition,
        connectionScope = { scope },
        writeProtocol = writeProtocol,
        closeTransport = close,
        onAuthenticationAccepted = {},
        publishAuthResponse = {},
        onAuthResult = onAuthResult,
    )

    private fun authRequest() = AuthRequestPayload(
        authType = 0,
        username = "agent-user",
        password = "bootstrap-secret",
        deviceId = "agent-device",
    )

    private fun successResponse(refreshToken: String) = AuthResponsePayload(
        code = AuthResponsePayload.CODE_OK,
        uid = "uid-1",
        username = "agent-user",
        name = "Agent User",
        refreshToken = refreshToken,
        accessToken = "access-token",
    )

    private class CredentialCommitFailure : RuntimeException()
}
