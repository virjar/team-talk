package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.protocol.PingSignal
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConnectionTraceE2eTest {
    @Test
    fun `live diagnostic enable records and disable stops the sync ready connection`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                var authenticatedUid: String? = null
                val client = ImClient(
                    onAuthResult = { success, uid, _, _, _, _, _, _ ->
                        if (success) authenticatedUid = uid
                    },
                )
                val projection = client.installE2eEventProjection(env.syncDatasetId)
                try {
                    val deviceId = "trace-e2e-device"
                    client.register(
                        username = "trace-e2e-${UUID.randomUUID()}",
                        password = "password123",
                        name = "Trace E2E",
                        deviceId = deviceId,
                        deviceName = "Trace Device",
                        host = "127.0.0.1",
                        port = env.tcpPort,
                    )
                    withTimeout(10_000) {
                        client.state.first { it == ConnectionState.AUTHENTICATED }
                    }
                    val uid = assertNotNull(authenticatedUid)
                    assertEquals(0, env.connectionTraceSnapshot().documentCount)

                    val enabled = env.enableConnectionTrace(uid)
                    client.send(PingSignal)
                    assertTrue(
                        eventually {
                            env.connectionTraceSnapshot().documentCount > 0L &&
                                env.connectionTraceSnapshot().queuedEvents == 0
                        },
                        "live DIAGNOSTIC policy did not persist the ready connection's frame",
                    )
                    val afterEnable = env.connectionTraceSnapshot().documentCount

                    env.disableConnectionTrace(checkNotNull(enabled.policyId))
                    repeat(3) { client.send(PingSignal) }
                    delay(500)
                    val afterDisable = env.connectionTraceSnapshot()
                    assertEquals(0, afterDisable.queuedEvents)
                    assertEquals(
                        afterEnable,
                        afterDisable.documentCount,
                        "BASELINE terminal must stop the already-ready connection immediately",
                    )
                } finally {
                    projection.close()
                    client.destroy()
                }
            }
        }
    }

    @Test
    fun `client event joins exact real tcp trace and fresh client reconnect stays isolated`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val deviceId = "trace-correlation-e2e-device"
                val username = "trace-corr-${UUID.randomUUID().toString().take(12)}"
                var authenticatedUid: String? = null
                var durableRefreshToken: String? = null
                val firstClient = ImClient(
                    onAuthResult = { success, uid, _, _, refreshToken, _, _, _ ->
                        if (success) {
                            authenticatedUid = uid
                            durableRefreshToken = refreshToken
                        }
                    },
                )
                val firstProjection = firstClient.installE2eEventProjection(env.syncDatasetId)
                val firstContext: com.virjar.tk.protocol.telemetry.ConnectionTraceContext
                val firstEventRecordId: Long
                try {
                    firstClient.register(
                        username = username,
                        password = "password123",
                        name = "Trace Correlation E2E",
                        deviceId = deviceId,
                        deviceName = "Trace Device",
                        host = "127.0.0.1",
                        port = env.tcpPort,
                    )
                    withTimeout(10_000) {
                        firstClient.state.first { it == ConnectionState.AUTHENTICATED }
                    }
                    val uid = assertNotNull(authenticatedUid)
                    val traceCountBeforeEnable = env.connectionTraceSnapshot().documentCount
                    env.enableConnectionTrace(uid)
                    firstContext = assertNotNull(withTimeout(10_000) {
                        firstClient.connectionTraceContext.first { it != null }
                    })
                    firstClient.send(PingSignal)
                    assertTrue(
                        eventually {
                            val snapshot = env.connectionTraceSnapshot()
                            snapshot.documentCount > traceCountBeforeEnable && snapshot.queuedEvents == 0
                        },
                        "first real TCP connection trace was not committed",
                    )
                    firstEventRecordId = env.ingestClientTelemetryEvent(
                        uid = uid,
                        deviceId = deviceId,
                        context = firstContext,
                        eventName = "connection.trace.first",
                    )
                } finally {
                    firstProjection.close()
                    firstClient.disconnect()
                    withTimeoutOrNull(5_000) {
                        firstClient.state.first { it == ConnectionState.DISCONNECTED }
                    }
                    firstClient.destroy()
                }

                val uid = assertNotNull(authenticatedUid)
                val refreshToken = assertNotNull(durableRefreshToken)
                var reauthenticatedUid: String? = null
                val secondClient = ImClient(
                    onAuthResult = { success, responseUid, _, _, _, _, _, _ ->
                        if (success) reauthenticatedUid = responseUid
                    },
                )
                val secondProjection = secondClient.installE2eEventProjection(env.syncDatasetId)
                try {
                    val traceCountBeforeReconnect = env.connectionTraceSnapshot().documentCount
                    secondClient.authenticate(
                        uid = uid,
                        token = refreshToken,
                        deviceId = deviceId,
                        deviceName = "Trace Device",
                        host = "127.0.0.1",
                        port = env.tcpPort,
                    )
                    withTimeout(10_000) {
                        secondClient.state.first { it == ConnectionState.AUTHENTICATED }
                    }
                    assertEquals(uid, reauthenticatedUid)
                    val secondContext = assertNotNull(withTimeout(10_000) {
                        secondClient.connectionTraceContext.first { it != null }
                    })

                    // 两个全新的 ImClient 实例都从 generation 1 开始。因此隔离必须
                    // 依赖完整的不透明身份，而不是仅仅依赖 generation。
                    assertEquals(firstContext.connectionGeneration, secondContext.connectionGeneration)
                    assertEquals(firstContext.policyRevision, secondContext.policyRevision)
                    assertNotEquals(firstContext.correlationId, secondContext.correlationId)
                    assertNotEquals(firstContext.traceId, secondContext.traceId)
                    assertNotEquals(firstContext.sessionId, secondContext.sessionId)

                    secondClient.send(PingSignal)
                    assertTrue(
                        eventually {
                            val snapshot = env.connectionTraceSnapshot()
                            snapshot.documentCount > traceCountBeforeReconnect && snapshot.queuedEvents == 0
                        },
                        "reconnected real TCP trace was not committed",
                    )
                    val secondEventRecordId = env.ingestClientTelemetryEvent(
                        uid = uid,
                        deviceId = deviceId,
                        context = secondContext,
                        eventName = "connection.trace.second",
                    )

                    val firstLookup = assertNotNull(
                        env.clientTelemetryAdminService.connectionTraces(
                            firstEventRecordId,
                            actor = "connection-trace-e2e-admin",
                        ),
                    )
                    val secondLookup = assertNotNull(
                        env.clientTelemetryAdminService.connectionTraces(
                            secondEventRecordId,
                            actor = "connection-trace-e2e-admin",
                        ),
                    )
                    assertExactContext(firstContext, assertNotNull(firstLookup.context))
                    assertExactContext(secondContext, assertNotNull(secondLookup.context))
                    assertTrue(firstLookup.traces.isNotEmpty())
                    assertTrue(secondLookup.traces.isNotEmpty())
                    firstLookup.traces.forEach { trace -> assertExactContext(firstContext, trace) }
                    secondLookup.traces.forEach { trace -> assertExactContext(secondContext, trace) }
                    val secondTraceIds = secondLookup.traces.mapTo(mutableSetOf()) { it.id }
                    assertFalse(
                        firstLookup.traces.any { it.id in secondTraceIds },
                        "old and reconnected server trace result sets must not overlap",
                    )
                } finally {
                    secondProjection.close()
                    secondClient.disconnect()
                    withTimeoutOrNull(5_000) {
                        secondClient.state.first { it == ConnectionState.DISCONNECTED }
                    }
                    secondClient.destroy()
                }
            }
        }
    }

    private fun assertExactContext(
        expected: com.virjar.tk.protocol.telemetry.ConnectionTraceContext,
        actual: com.virjar.tk.server.application.admin.ClientTelemetryAdminService.ConnectionTraceContextItem,
    ) {
        assertEquals(expected.correlationId, actual.correlationId)
        assertEquals(expected.traceId, actual.traceId)
        assertEquals(expected.sessionId, actual.sessionId)
        assertEquals(expected.connectionGeneration, actual.connectionGeneration)
        assertEquals(expected.policyRevision, actual.policyRevision)
    }

    private fun assertExactContext(
        expected: com.virjar.tk.protocol.telemetry.ConnectionTraceContext,
        actual: com.virjar.tk.server.application.admin.ClientTelemetryAdminService.ConnectionTraceItem,
    ) {
        assertEquals(expected.correlationId, actual.correlationId)
        assertEquals(expected.traceId, actual.traceId)
        assertEquals(expected.sessionId, actual.sessionId)
        assertEquals(expected.connectionGeneration, actual.connectionGeneration)
        assertEquals(expected.policyRevision, actual.policyRevision)
    }

    private suspend fun eventually(
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }
}
