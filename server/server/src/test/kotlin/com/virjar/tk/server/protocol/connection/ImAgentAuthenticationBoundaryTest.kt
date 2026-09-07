package com.virjar.tk.server.protocol.connection

import com.virjar.tk.server.domain.auth.AuthenticationResult
import com.virjar.tk.server.domain.auth.AuthenticationAttemptKeys
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.AccountBannedPayload
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import java.net.InetAddress
import java.net.InetSocketAddress

class ImAgentAuthenticationBoundaryTest {
    @Test
    fun `scoped account ban uses new frame while negotiated legacy clients retain the frozen response`() {
        val denied = AuthResponsePayload(code = AuthResponsePayload.CODE_ACCOUNT_BANNED, uid = "user-1", reason = "Banned")
        val datasetId = "11111111-1111-4111-8111-111111111111"
        for (minor in 0..1) {
            val legacy = authenticationResponseForProtocol(denied, ProtocolVersion(0, minor), datasetId)
            assertEquals(denied, legacy)
            assertEquals(denied, ProtoCodec.decode(AuthResponsePayload, ProtoCodec.encode(legacy)))
        }
        val scoped = authenticationResponseForProtocol(denied, ProtocolVersion(0, 2), datasetId)
        assertEquals(AccountBannedPayload("user-1", datasetId, "Banned"), scoped)
        assertEquals(scoped, ProtoCodec.decode(AccountBannedPayload, ProtoCodec.encode(scoped)))
        val ordinary = AuthResponsePayload(code = AuthResponsePayload.CODE_AUTH_FAILED)
        assertSame(ordinary, authenticationResponseForProtocol(ordinary, ProtocolVersion(0, 2), datasetId))
    }

    @Test
    fun `tcp admission uses direct socket peer and uniform retryable denial`() {
        val peer = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 51_000)
        val attempt = authenticationAttempt(
            remoteAddress = peer,
            payload = AuthRequestPayload(
                authType = 0,
                username = " Alice ",
                password = "password123",
                deviceId = "device",
                correlationId = "auth-boundary-test-0001",
                connectionGeneration = 1L,
            ),
        )

        assertEquals(AuthenticationOperation.LOGIN, attempt.operation)
        assertEquals(AuthenticationAttemptKeys.directSource("127.0.0.1"), attempt.sourceKey)
        assertEquals(AuthenticationAttemptKeys.username("human", "alice"), attempt.accountKey)
        assertEquals(AuthResponsePayload.CODE_SERVER_MAINTENANCE, authenticationGuardDenialResponse().code)
        assertEquals("Authentication temporarily unavailable", authenticationGuardDenialResponse().reason)
    }

    @Test
    fun `authentication boundary rethrows exact owner cancellation`() = runTest {
        val cancellation = CancellationException("connection owner retired")
        var reported: Exception? = null

        val observed = try {
            executeAuthenticationBoundary(
                authenticate = { throw cancellation },
                reportInternalFailure = { reported = it },
            )
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, observed)
        assertEquals(null, reported)
    }

    @Test
    fun `ordinary authentication exception maps to redacted internal failure`() = runTest {
        val failure = IllegalStateException("database unavailable")
        var reported: Exception? = null

        val result: AuthenticationResult = executeAuthenticationBoundary(
            authenticate = { throw failure },
            reportInternalFailure = { reported = it },
        )

        assertSame(failure, reported)
        assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, result.response.code)
        assertEquals("Internal error", result.response.reason)
        assertEquals(null, result.principal)
    }
}
