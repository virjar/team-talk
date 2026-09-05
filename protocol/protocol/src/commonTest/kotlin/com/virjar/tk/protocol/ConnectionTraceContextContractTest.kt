package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.ConnectionTraceContextPayload
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConnectionTraceContextContractTest {
    @Test
    fun `auth and live connection trace contexts round trip on current protocol`() {
        val context = context(revision = 3L)
        val request = AuthRequestPayload(
            authType = 2,
            refreshToken = "refresh-token",
            deviceId = "device-1",
            correlationId = context.correlationId,
            connectionGeneration = context.connectionGeneration,
        )
        assertEquals(request, ProtoCodec.decode(AuthRequestPayload, ProtoCodec.encode(request)))

        val response = AuthResponsePayload(
            code = AuthResponsePayload.CODE_OK,
            uid = "uid-1",
            username = "user-1",
            name = "User 1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            datasetId = "00000000-0000-4000-8000-000000000001",
            connectionTraceContext = context,
        )
        assertEquals(response, ProtoCodec.decode(AuthResponsePayload, ProtoCodec.encode(response)))

        val enabled = ConnectionTraceContextPayload(
            correlationId = context.correlationId,
            connectionGeneration = context.connectionGeneration,
            policyRevision = context.policyRevision,
            context = context,
        )
        assertEquals(enabled, ProtoCodec.decode(ConnectionTraceContextPayload, ProtoCodec.encode(enabled)))
        val disabled = enabled.copy(policyRevision = 4L, context = null)
        val decodedDisabled = ProtoCodec.decode(
            ConnectionTraceContextPayload,
            ProtoCodec.encode(disabled),
        )
        assertEquals(disabled, decodedDisabled)
        assertNull(decodedDisabled.context)
    }

    @Test
    fun `trace identifiers and numeric fields fail closed`() {
        listOf(
            "short",
            "x".repeat(ConnectionTraceContextPolicy.MAX_TOKEN_LENGTH + 1),
            "contains.dot.not-safe",
            "包含非ASCII字符的token值",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                context().copy(correlationId = invalid)
            }
        }
        assertFailsWith<IllegalArgumentException> { context().copy(connectionGeneration = 0L) }
        assertFailsWith<IllegalArgumentException> { context().copy(policyRevision = 0L) }
        assertFailsWith<IllegalArgumentException> { context().copy(expiresAtEpochMs = 0L) }
        assertFailsWith<IllegalArgumentException> {
            ConnectionTraceContextPayload(
                correlationId = context().correlationId,
                connectionGeneration = 10L,
                policyRevision = 2L,
                context = context(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_AUTH_FAILED,
                connectionTraceContext = context(),
            )
        }
    }

    @Test
    fun `generic diagnostics redact every opaque trace token`() {
        val context = context()
        val update = ConnectionTraceContextPayload(
            correlationId = context.correlationId,
            connectionGeneration = context.connectionGeneration,
            policyRevision = context.policyRevision,
            context = context,
        )
        val rendered = listOf(context.toString(), update.toString()).joinToString()
        assertFalse(rendered.contains(context.correlationId))
        assertFalse(rendered.contains(context.traceId))
        assertFalse(rendered.contains(context.sessionId))
    }

    private fun context(revision: Long = 2L) = ConnectionTraceContext(
        correlationId = "correlation-token-0001",
        traceId = "trace-token-000000001",
        sessionId = "session-token-0000001",
        connectionGeneration = 9L,
        policyRevision = revision,
        expiresAtEpochMs = 9_999_999L,
    )
}
