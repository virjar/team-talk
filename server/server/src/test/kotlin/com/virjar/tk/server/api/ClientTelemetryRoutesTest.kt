package com.virjar.tk.server.api

import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.TelemetryBatchReceipt
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueMetrics
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_MESSAGE
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_SEARCH_TEXT
import com.virjar.tk.server.integration.IntegrationTestExtension
import com.virjar.tk.server.integration.uniqueUsername
import com.virjar.tk.protocol.telemetry.ClientPlatform
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import com.virjar.tk.protocol.telemetry.TelemetryEvent
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import com.virjar.tk.protocol.telemetry.TelemetryFeedbackCode
import com.virjar.tk.protocol.telemetry.TelemetryFaultPayload
import com.virjar.tk.protocol.telemetry.TelemetryLogLevel
import com.virjar.tk.protocol.telemetry.TelemetryLogPayload
import com.virjar.tk.protocol.telemetry.TelemetryNoticeLevel
import com.virjar.tk.protocol.telemetry.TelemetryNoticeOrigin
import com.virjar.tk.protocol.telemetry.TelemetryOutgoingQueuePayload
import com.virjar.tk.protocol.telemetry.TelemetryPolicy
import com.virjar.tk.protocol.telemetry.TelemetryUserNoticePayload
import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientTelemetryRoutesTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `empty heartbeat keeps PostgreSQL control plane available when event store is down`() = testApplication {
        val deviceId = "heartbeat-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-heartbeat"),
            "pass123",
            "Telemetry HTTP Heartbeat",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-heartbeat-token-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        val unavailableEvents = object : ClientTelemetryEventStore by ctx.clientTelemetryEvents {
            override fun findBatchReceipt(uid: String, deviceId: String, batchId: String): TelemetryBatchReceipt? =
                throw TelemetrySearchUnavailableException()
        }
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, unavailableEvents, tokens, clock = { now })
            }
        }

        val heartbeat = noticeBatch(now).copy(events = emptyList(), heartbeat = true)
        val heartbeatResponse = uploadResponse(token, heartbeat)
        assertEquals(HttpStatusCode.OK, heartbeatResponse.status)
        assertEquals(
            "BASELINE",
            Json.parseToJsonElement(heartbeatResponse.bodyAsText())
                .jsonObject.getValue("policy").jsonObject.getValue("mode").jsonPrimitive.content,
        )
        assertNotNull(ctx.clientTelemetryControl.findDevice(uid, deviceId))
        assertEquals(HttpStatusCode.ServiceUnavailable, upload(token, noticeBatch(now)))
    }

    @Test
    fun `bearer identity wins and committed retry bypasses later client timestamp expiry`() = testApplication {
        val deviceId = "authoritative-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-identity"),
            "pass123",
            "Telemetry HTTP Identity",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-http-token-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = AtomicLong(System.currentTimeMillis())
        val batch = noticeBatch(now.get()).copy(
            runtimeInfo = runtimeInfo().copy(
                appVersion = "0.0.0",
                buildNumber = "0",
                buildIdentity = "0.0.0+${"a".repeat(40)}",
                protocolVersion = 0,
            ),
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(
                    control = ctx.clientTelemetryControl,
                    events = ctx.clientTelemetryEvents,
                    accessTokens = tokens,
                    clock = now::get,
                )
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/client-telemetry") {
            setBody(gzip(Json.encodeToString(batch)))
        }.status)
        assertEquals(HttpStatusCode.OK, upload(token, batch))
        val storedRuntime = assertNotNull(ctx.clientTelemetryControl.findDevice(uid, deviceId)).runtime
        assertEquals("0.0.0", storedRuntime.appVersion)
        assertEquals("0", storedRuntime.buildNumber)
        assertEquals(0, storedRuntime.protocolVersion)
        val storedEvent = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(uid = uid, deviceId = deviceId, receivedAtFrom = now.get(), receivedAtUntil = now.get()),
            0,
            10,
        ).hits.single().event
        assertEquals("0", storedEvent.runtime.buildNumber)
        assertEquals(0, storedEvent.runtime.protocolVersion)

        now.addAndGet(TelemetryStoragePolicy.RETENTION_MILLIS + 1L)
        assertEquals(
            HttpStatusCode.OK,
            upload(token, batch),
            "an exact committed retry must be ACKed before applying new-upload time gates",
        )
        assertEquals(
            HttpStatusCode.Conflict,
            upload(
                token,
                batch.copy(events = batch.events.map { event ->
                    event.copy(
                        payload = (event.payload as TelemetryUserNoticePayload).copy(message = "different notice"),
                    )
                }),
            ),
        )
    }

    @Test
    fun `new telemetry enforces offline and future clock bounds before persistence`() = testApplication {
        val deviceId = "time-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-time"),
            "pass123",
            "Telemetry HTTP Time",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-time-token-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        assertEquals(
            HttpStatusCode.BadRequest,
            upload(token, noticeBatch(now - TelemetryStoragePolicy.RETENTION_MILLIS - 1L)),
        )
        val validCreatedAt = noticeBatch(now)
        assertEquals(
            HttpStatusCode.BadRequest,
            upload(
                token,
                validCreatedAt.copy(
                    batchId = "future-${UUID.randomUUID()}",
                    events = validCreatedAt.events.map {
                        it.copy(occurredAtEpochMs = now + 10L * 60L * 1_000L + 1L)
                    },
                ),
            ),
        )
    }

    @Test
    fun `baseline rejects diagnostic logs and gzip expansion remains bounded`() = testApplication {
        val deviceId = "boundary-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-boundary"),
            "pass123",
            "Telemetry HTTP Boundary",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-boundary-token-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }
        val logBatch = TelemetryBatch(
            batchId = "log-${UUID.randomUUID()}",
            createdAtEpochMs = now,
            runtimeInfo = runtimeInfo(),
            events = listOf(
                TelemetryEvent(
                    eventId = "event-${UUID.randomUUID()}",
                    runId = "run-${UUID.randomUUID()}",
                    sequence = 1L,
                    occurredAtEpochMs = now,
                    eventName = "debug.detail",
                    kind = TelemetryEventKind.LOG,
                    payload = TelemetryLogPayload(TelemetryLogLevel.DEBUG, "Desktop", "diagnostic detail"),
                ),
            ),
        )
        assertEquals(HttpStatusCode.Forbidden, upload(token, logBatch))
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/client-telemetry") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(gzip("x".repeat(CLIENT_TELEMETRY_MAX_DECOMPRESSED_BYTES + 1)))
        }.status)
    }

    @Test
    fun `outgoing queue upload is diagnostic only and ingestion preserves typed numbers`() = testApplication {
        val deviceId = "outgoing-queue-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-outgoing"),
            "pass123",
            "Telemetry HTTP Outgoing",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-outgoing-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        assertEquals(HttpStatusCode.Forbidden, upload(token, outgoingQueueBatch(now)))
        ctx.clientTelemetryControl.enableDiagnosticPolicy(
            uid = uid,
            deviceId = null,
            reason = "typed outgoing queue route test",
            expiresAt = now + 60_000L,
            actor = "test",
            now = now - 1L,
        )
        assertEquals(HttpStatusCode.OK, upload(token, outgoingQueueBatch(now)))

        val stored = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(
                uid = uid,
                deviceId = deviceId,
                eventName = TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            offset = 0,
            limit = 10,
        ).hits.single().event.event
        assertEquals("OUTGOING_QUEUE", stored.category)
        assertEquals(
            TelemetryOutgoingQueueMetrics(2, 1, 4, 45_000, 7),
            stored.outgoingQueue,
        )
        assertEquals(OUTGOING_QUEUE_STORED_MESSAGE, stored.message)
        assertEquals(OUTGOING_QUEUE_STORED_SEARCH_TEXT, stored.searchText)
    }

    @Test
    fun `diagnostic gzip upload preserves trace context under bearer identity authority`() = testApplication {
        val deviceId = "trace-context-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-trace-context"),
            "pass123",
            "Telemetry HTTP Trace Context",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-trace-context-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        val policy = ctx.clientTelemetryControl.enableDiagnosticPolicy(
            uid = uid,
            deviceId = null,
            reason = "connection trace HTTP upload test",
            expiresAt = now + 60_000L,
            actor = "test",
            now = now - 1L,
        )
        val traceContext = ConnectionTraceContext(
            correlationId = "correlation-${UUID.randomUUID()}",
            traceId = "trace-${UUID.randomUUID()}",
            sessionId = "session-${UUID.randomUUID()}",
            connectionGeneration = 7L,
            policyRevision = policy.revision,
            expiresAtEpochMs = checkNotNull(policy.expiresAt),
        )
        val batch = logBatch(now).let { source ->
            source.copy(events = source.events.map { event ->
                event.copy(connectionTraceContext = traceContext)
            })
        }
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        val encodedBatch = Json.encodeToString(batch)
        assertEquals(false, encodedBatch.contains("\"uid\""))
        assertEquals(false, encodedBatch.contains("\"deviceId\""))
        assertEquals(HttpStatusCode.OK, upload(token, batch))

        val stored = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(
                uid = uid,
                deviceId = deviceId,
                eventName = "debug.detail",
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            offset = 0,
            limit = 10,
        ).hits.single().event
        assertEquals(uid, stored.uid)
        assertEquals(deviceId, stored.deviceId)
        val storedContext = assertNotNull(stored.event.connectionTraceContext)
        assertEquals(traceContext.correlationId, storedContext.correlationId)
        assertEquals(traceContext.traceId, storedContext.traceId)
        assertEquals(traceContext.sessionId, storedContext.sessionId)
        assertEquals(traceContext.connectionGeneration, storedContext.connectionGeneration)
        assertEquals(traceContext.policyRevision, storedContext.policyRevision)

        val forgedUid = "forged-uid-${UUID.randomUUID()}"
        val forgedDeviceId = "forged-device-${UUID.randomUUID()}"
        val forgedOwnerPayload = encodedBatch.replaceFirst(
            "{",
            "{\"uid\":\"$forgedUid\",\"deviceId\":\"$forgedDeviceId\",",
        )
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/client-telemetry") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(gzip(forgedOwnerPayload))
        }.status)
        assertEquals(
            0L,
            ctx.clientTelemetryEvents.search(
                TelemetrySearchQuery(
                    uid = forgedUid,
                    deviceId = forgedDeviceId,
                    receivedAtFrom = now,
                    receivedAtUntil = now,
                ),
                offset = 0,
                limit = 10,
            ).total,
        )
    }

    @Test
    fun `baseline fault accepts only reviewed semantics and drops every free text field`() = testApplication {
        val deviceId = "fault-catalog-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-fault-catalog"),
            "pass123",
            "Telemetry HTTP Fault Catalog",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-fault-catalog-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        val privateSentinel = "synthetic private prose must never reach baseline"
        val approved = TelemetryBatch(
            batchId = "fault-${UUID.randomUUID()}",
            createdAtEpochMs = now,
            runtimeInfo = runtimeInfo(),
            events = listOf(
                TelemetryEvent(
                    eventId = "event-${UUID.randomUUID()}",
                    runId = "run-${UUID.randomUUID()}",
                    sequence = 1L,
                    occurredAtEpochMs = now,
                    eventName = "mark_read_local_failure",
                    kind = TelemetryEventKind.FAULT,
                    payload = TelemetryFaultPayload(
                        logger = "arbitrary.logger",
                        summary = privateSentinel,
                        faultCode = "mark_read_local_failure",
                        page = "chat",
                        action = "mark_read",
                        origin = "system",
                        reasonCode = "sqlite",
                        exceptionClass = "arbitrary.Exception",
                    ),
                ),
            ),
        )
        assertEquals(HttpStatusCode.OK, upload(token, approved))
        val stored = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(
                uid = uid,
                deviceId = deviceId,
                eventName = "mark_read_local_failure",
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            offset = 0,
            limit = 10,
        ).hits.single().event.event
        assertEquals(false, stored.message.contains(privateSentinel))
        assertEquals(false, stored.searchText.contains("arbitrary"))

        val unreviewed = approved.copy(
            batchId = "unreviewed-${UUID.randomUUID()}",
            events = approved.events.map { event ->
                event.copy(
                    eventId = "event-${UUID.randomUUID()}",
                    eventName = "invented_fault_code",
                    payload = (event.payload as TelemetryFaultPayload).copy(faultCode = "invented_fault_code"),
                )
            },
        )
        assertEquals(HttpStatusCode.Forbidden, upload(token, unreviewed))
    }

    @Test
    fun `diagnostic policy is rechecked after the complete request body arrives`() = testApplication {
        val deviceId = "policy-expiry-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-policy-expiry"),
            "pass123",
            "Telemetry HTTP Policy Expiry",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-policy-expiry-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val startedAt = System.currentTimeMillis()
        val completedAt = startedAt + 2L
        ctx.clientTelemetryControl.enableDiagnosticPolicy(
            uid = uid,
            deviceId = null,
            reason = "request completion policy test",
            expiresAt = startedAt + 1L,
            actor = "test",
            now = startedAt - 1L,
        )
        val clockCalls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(
                    ctx.clientTelemetryControl,
                    ctx.clientTelemetryEvents,
                    tokens,
                    clock = { if (clockCalls.getAndIncrement() == 0) startedAt else completedAt },
                )
            }
        }

        assertEquals(HttpStatusCode.Forbidden, upload(token, logBatch(startedAt)))
        assertEquals(2, clockCalls.get())
    }

    @Test
    fun `telemetry requires explicit wire media types and emits every schema version`() = testApplication {
        val deviceId = "wire-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-wire"),
            "pass123",
            "Telemetry HTTP Wire",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-wire-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        val batch = noticeBatch(now)
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/api/client-telemetry") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(gzip(Json.encodeToString(batch)))
        }.status)
        val response = uploadResponse(token, batch)
        assertEquals(HttpStatusCode.OK, response.status)
        val wire = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ClientTelemetryLimits.SCHEMA_VERSION, wire.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(
            ClientTelemetryLimits.SCHEMA_VERSION,
            wire.getValue("ack").jsonObject.getValue("schemaVersion").jsonPrimitive.int,
        )
        assertEquals(
            ClientTelemetryLimits.SCHEMA_VERSION,
            wire.getValue("policy").jsonObject.getValue("schemaVersion").jsonPrimitive.int,
        )
    }

    @Test
    fun `telemetry body has a total deadline independent of socket activity`() = testApplication {
        val deviceId = "deadline-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-deadline"),
            "pass123",
            "Telemetry HTTP Deadline",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-deadline-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(
                    ctx.clientTelemetryControl,
                    ctx.clientTelemetryEvents,
                    tokens,
                    bodyTimeoutMillis = 50L,
                )
            }
        }

        val body = gzip(Json.encodeToString(noticeBatch(System.currentTimeMillis())))
        coroutineScope {
            val requestBody = ByteChannel()
            val producer = launch {
                try {
                    requestBody.writeFully(byteArrayOf(body.first()))
                    requestBody.flush()
                    delay(500L)
                    requestBody.writeFully(body.copyOfRange(1, body.size))
                    requestBody.close()
                } catch (_: Exception) {
                    // 预期服务器端的截止时间会取消该请求生产者。
                }
            }
            try {
                val request = async {
                    client.post("/api/client-telemetry") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(fixedTelemetryContent(requestBody, body.size.toLong()))
                    }
                }
                assertEquals(HttpStatusCode.RequestTimeout, withTimeout(5_000L) { request.await() }.status)
            } finally {
                requestBody.cancel(null)
                producer.cancelAndJoin()
            }
        }
    }

    @Test
    fun `empty decompressed telemetry payload returns bad request`() = testApplication {
        val deviceId = "empty-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-empty"),
            "pass123",
            "Telemetry HTTP Empty",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-empty-token-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens)
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.post("/api/client-telemetry") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(gzip(""))
        }.status)
    }

    @Test
    fun `runtime facts are privacy normalized before an acknowledged commit`() = testApplication {
        val deviceId = "runtime-max-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-runtime-max"),
            "pass123",
            "Telemetry HTTP Runtime Max",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-runtime-max-token-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        val maxRuntime = runtimeInfo().copy(
            osName = "o7".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS / 2),
            osVersion = "v7".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS / 2),
            architecture = "a7".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS / 2),
            deviceModel = "m7".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS / 2),
            appVersion = "p".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
            buildNumber = "n".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
            gitCommit = "c".repeat(ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS),
            buildIdentity = "i".repeat(ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS),
            buildTime = "t".repeat(ClientTelemetryLimits.MAX_BUILD_TIME_CHARS),
            distribution = "d".repeat(ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        assertEquals(HttpStatusCode.OK, upload(token, noticeBatch(now).copy(runtimeInfo = maxRuntime)))
        val stored = ctx.clientTelemetryControl.findDevice(uid, deviceId)?.runtime
        assertEquals("unknown", stored?.osName)
        assertEquals("unknown", stored?.osVersion)
        assertEquals("unknown", stored?.architecture)
        assertEquals("unknown", stored?.deviceModel)
        assertEquals("unknown", stored?.appVersion)
        assertEquals("unknown", stored?.gitCommit)
        assertEquals("unknown", stored?.buildIdentity)
        assertEquals("unknown", stored?.distribution)
    }

    @Test
    fun `baseline notice uses reviewed server text and rejects an unknown code`() = testApplication {
        val deviceId = "reviewed-notice-device"
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-http-reviewed-notice"),
            "pass123",
            "Telemetry HTTP Reviewed Notice",
            deviceId = deviceId,
        ).uid
        val token = "telemetry-reviewed-notice-${UUID.randomUUID()}"
        val tokens = TestAccessTokenValidator.single(token, uid, deviceId)
        val now = System.currentTimeMillis()
        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(ctx.clientTelemetryControl, ctx.clientTelemetryEvents, tokens, clock = { now })
            }
        }

        val approved = noticeBatch(now).let { batch ->
            batch.copy(events = batch.events.map { event ->
                event.copy(
                    eventName = TelemetryFeedbackCode.CHAT_ASSET_UPLOAD_PENDING.code,
                    payload = (event.payload as TelemetryUserNoticePayload).copy(
                        feedbackCode = TelemetryFeedbackCode.CHAT_ASSET_UPLOAD_PENDING.code,
                        message = "arbitrary client supplied prose",
                    ),
                )
            })
        }
        assertEquals(HttpStatusCode.OK, upload(token, approved))
        val indexed = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(
                uid = uid,
                deviceId = deviceId,
                eventName = TelemetryFeedbackCode.CHAT_ASSET_UPLOAD_PENDING.code,
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            offset = 0,
            limit = 10,
        ).hits.single().event.event
        assertTrue(
            indexed.message.contains(TelemetryFeedbackCode.CHAT_ASSET_UPLOAD_PENDING.publicMessage),
        )
        assertEquals(false, indexed.message.contains("arbitrary client supplied prose"))

        val unknown = noticeBatch(now).let { batch ->
            batch.copy(
                batchId = "unknown-${UUID.randomUUID()}",
                events = batch.events.map { event ->
                    event.copy(
                        eventId = "event-${UUID.randomUUID()}",
                        eventName = "unknown.notice",
                        payload = (event.payload as TelemetryUserNoticePayload).copy(
                            feedbackCode = "unknown.notice",
                            message = "unreviewed notice",
                        ),
                    )
                },
            )
        }
        assertEquals(HttpStatusCode.Forbidden, upload(token, unknown))
    }

    @Test
    fun `admission enforces per device policy windows without unbounded identities`() {
        val admission = ClientTelemetryAdmission(maxTrackedDevices = 1)
        val policy = TelemetryPolicy.baseline()
        val now = 120_000L
        repeat(policy.maxEventsPerMinute) {
            assertTrue(admission.tryAdmit("uid", "device", 1, 1, policy, now))
        }
        assertEquals(false, admission.tryAdmit("uid", "device", 1, 1, policy, now))
        assertEquals(false, admission.tryAdmit("other", "device", 0, 1, policy, now))
        assertTrue(admission.tryAdmit("uid", "device", 1, 1, policy, now + 60_000L))
    }

    @Test
    fun `ingress admission applies to exact retries before receipt lookup`() {
        val ingress = ClientTelemetryIngressAdmission(
            maxTrackedDevices = 1,
            requestsPerDeviceMinute = 1,
        )
        val now = 120_000L
        assertTrue(ingress.tryAdmitRequest("uid", "device", now))
        assertTrue(ingress.tryAdmitBytes("uid", "device", 1_024, now))
        assertEquals(false, ingress.tryAdmitRequest("uid", "device", now))
        assertEquals(false, ingress.tryAdmitRequest("other", "device", now))
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.upload(
        token: String,
        batch: TelemetryBatch,
    ) = uploadResponse(token, batch).status

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.uploadResponse(
        token: String,
        batch: TelemetryBatch,
    ) = client.post("/api/client-telemetry") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.ContentEncoding, "gzip")
        setBody(gzip(Json.encodeToString(batch)))
    }

    private fun logBatch(timestamp: Long) = TelemetryBatch(
        batchId = "log-${UUID.randomUUID()}",
        createdAtEpochMs = timestamp,
        runtimeInfo = runtimeInfo(),
        events = listOf(
            TelemetryEvent(
                eventId = "event-${UUID.randomUUID()}",
                runId = "run-${UUID.randomUUID()}",
                sequence = 1L,
                occurredAtEpochMs = timestamp,
                eventName = "debug.detail",
                kind = TelemetryEventKind.LOG,
                payload = TelemetryLogPayload(TelemetryLogLevel.DEBUG, "Desktop", "diagnostic detail"),
            ),
        ),
    )

    private fun noticeBatch(timestamp: Long) = TelemetryBatch(
        batchId = "notice-${UUID.randomUUID()}",
        createdAtEpochMs = timestamp,
        runtimeInfo = runtimeInfo(),
        events = listOf(
            TelemetryEvent(
                eventId = "event-${UUID.randomUUID()}",
                runId = "run-${UUID.randomUUID()}",
                sequence = 1L,
                occurredAtEpochMs = timestamp,
                eventName = TelemetryFeedbackCode.MEDIA_OPEN_FAILED.code,
                kind = TelemetryEventKind.USER_NOTICE,
                payload = TelemetryUserNoticePayload(
                    feedbackCode = TelemetryFeedbackCode.MEDIA_OPEN_FAILED.code,
                    page = "chat",
                    action = "open_media",
                    origin = TelemetryNoticeOrigin.TOAST,
                    message = TelemetryFeedbackCode.MEDIA_OPEN_FAILED.publicMessage,
                    level = TelemetryNoticeLevel.ERROR,
                ),
            ),
        ),
    )

    private fun outgoingQueueBatch(timestamp: Long) = TelemetryBatch(
        batchId = "outgoing-${UUID.randomUUID()}",
        createdAtEpochMs = timestamp,
        runtimeInfo = runtimeInfo(),
        events = listOf(
            TelemetryEvent(
                eventId = "event-${UUID.randomUUID()}",
                runId = "run-${UUID.randomUUID()}",
                sequence = 1L,
                occurredAtEpochMs = timestamp,
                eventName = TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
                kind = TelemetryEventKind.OUTGOING_QUEUE,
                payload = TelemetryOutgoingQueuePayload(2, 1, 4, 45_000, 7),
            ),
        ),
    )

    private fun runtimeInfo() = ClientRuntimeInfo(
        platform = ClientPlatform.DESKTOP,
        osName = "macOS",
        osVersion = "15.6",
        architecture = "arm64",
        deviceModel = "Mac",
        appVersion = "0.1.0",
        buildNumber = "1",
        gitCommit = "abcdef012345",
        buildIdentity = "1.0.7+${"a".repeat(40)}",
        buildTime = "2026-08-27 14:04",
        protocolVersion = 1,
        distribution = "compose-desktop",
    )

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
    }.toByteArray()

    private fun fixedTelemetryContent(
        requestBody: ByteReadChannel,
        length: Long,
    ): OutgoingContent.ReadChannelContent = object : OutgoingContent.ReadChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json
        override val contentLength: Long = length
        override fun readFrom(): ByteReadChannel = requestBody
    }
}
