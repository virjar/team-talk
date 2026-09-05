package com.virjar.tk.protocol.telemetry

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientTelemetryContractTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `typed batch round trips without client asserted account identity`() {
        val batch = TelemetryBatch(
            batchId = "batch-1",
            createdAtEpochMs = 1_000,
            runtimeInfo = runtimeInfo(),
            events = listOf(
                event(0, "fault.one", TelemetryEventKind.FAULT, TelemetryFaultPayload(
                    logger = "Cache",
                    summary = "write failed",
                    faultCode = "cache.write_failed",
                    page = "chat",
                    action = "mark_read",
                    origin = "sqlite",
                    reasonCode = "disk_full",
                )),
                event(1, "notice.saved", TelemetryEventKind.USER_NOTICE, TelemetryUserNoticePayload(
                    feedbackCode = "notice.saved",
                    page = "settings",
                    action = "save",
                    origin = TelemetryNoticeOrigin.TOAST,
                    message = "保存失败",
                    level = TelemetryNoticeLevel.ERROR,
                )),
                event(2, "media.decode_failed", TelemetryEventKind.MEDIA, TelemetryMediaPayload(
                    mediaKind = TelemetryMediaKind.VIDEO,
                    operation = TelemetryMediaOperation.PREVIEW,
                    outcome = TelemetryActionOutcome.FAILED,
                    reasonCode = "media.decode_failed",
                )),
                event(3, "group_file.queued", TelemetryEventKind.ACTION, TelemetryActionPayload(
                    page = "group_files",
                    action = "publish_group_file",
                    outcome = TelemetryActionOutcome.QUEUED,
                )),
            ),
        )
        ClientTelemetryValidation.requireValid(batch)

        val encoded = json.encodeToString(batch)
        assertEquals(batch, json.decodeFromString<TelemetryBatch>(encoded))
        assertFalse(Regex("\"(uid|deviceId|phone)\"").containsMatchIn(encoded))
        assertTrue("\"type\":\"fault\"" in encoded)
    }

    @Test
    fun `wire validation rejects mismatched payload unsafe id and credential marker`() {
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                event(0, "wrong", TelemetryEventKind.ACTION, TelemetryLogPayload(
                    TelemetryLogLevel.INFO,
                    "Logger",
                    "safe",
                )),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                TelemetryBatch(
                    batchId = "windows:illegal",
                    createdAtEpochMs = 1,
                    runtimeInfo = runtimeInfo(),
                    events = listOf(event(0, "safe", TelemetryEventKind.LOG, TelemetryLogPayload(
                        TelemetryLogLevel.INFO,
                        "Logger",
                        "safe",
                    ))),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                event(0, "unsafe", TelemetryEventKind.LOG, TelemetryLogPayload(
                    TelemetryLogLevel.ERROR,
                    "Logger",
                    "authorization: secret",
                )),
            )
        }
    }

    @Test
    fun `baseline is sparse while diagnostic expires closed`() {
        val baseline = TelemetryPolicy.baseline()
        val fault = event(0, "fault", TelemetryEventKind.FAULT, TelemetryFaultPayload("Log", "failed"))
        val trace = event(1, "trace", TelemetryEventKind.LOG, TelemetryLogPayload(
            TelemetryLogLevel.TRACE,
            "Log",
            "trace",
        ))
        val criticalSystem = event(2, "network.offline", TelemetryEventKind.SYSTEM, TelemetrySystemPayload(
            name = "network.offline",
            critical = true,
        ))
        val failedMedia = event(3, "media.failed", TelemetryEventKind.MEDIA, TelemetryMediaPayload(
            TelemetryMediaKind.FILE,
            TelemetryMediaOperation.DOWNLOAD,
            TelemetryActionOutcome.FAILED,
            reasonCode = "media.failed",
        ))
        val outgoingQueue = event(
            4,
            TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
            TelemetryEventKind.OUTGOING_QUEUE,
            TelemetryOutgoingQueuePayload(3, 2, 1, 45_000, 7),
        )
        assertTrue(ClientTelemetryValidation.allows(baseline, fault, 10))
        assertFalse(ClientTelemetryValidation.allows(baseline, trace, 10))
        assertTrue(ClientTelemetryValidation.allows(baseline, criticalSystem, 10))
        assertTrue(ClientTelemetryValidation.allows(baseline, failedMedia, 10))
        assertFalse(ClientTelemetryValidation.allows(baseline, outgoingQueue, 10))

        val diagnostic = baseline.copy(
            revision = "diag-1",
            mode = TelemetryPolicyMode.DIAGNOSTIC,
            issuedAtEpochMs = 1,
            expiresAtEpochMs = 100,
        )
        assertTrue(ClientTelemetryValidation.allows(diagnostic, trace, 99))
        assertTrue(ClientTelemetryValidation.allows(diagnostic, outgoingQueue, 99))
        assertFalse(ClientTelemetryValidation.allows(diagnostic, trace, 100))
    }

    @Test
    fun `outgoing queue payload is numeric only canonical and bounded`() {
        val event = event(
            0,
            TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
            TelemetryEventKind.OUTGOING_QUEUE,
            TelemetryOutgoingQueuePayload(9, 4, 2, 123_456, 11),
        )
        ClientTelemetryValidation.requireValid(event)
        val encoded = json.encodeToString(event)
        assertEquals(event, json.decodeFromString<TelemetryEvent>(encoded))
        assertTrue("\"type\":\"outgoing_queue\"" in encoded)
        assertEquals(
            setOf(
                "type",
                "pendingCount",
                "retryWaitCount",
                "terminalFailedCount",
                "oldestActiveAgeMillis",
                "maxAttemptCount",
            ),
            json.parseToJsonElement(encoded).jsonObject.getValue("payload").jsonObject.keys,
        )
        listOf("chatId", "clientMsgId", "path", "body", "message", "metadata").forEach { forbidden ->
            assertFalse("\"$forbidden\"" in encoded)
        }

        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(event.copy(eventName = "outgoing.queue.custom"))
        }
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                event.copy(
                    payload = TelemetryOutgoingQueuePayload(
                        ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT,
                        1,
                        0,
                        1,
                        1,
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                event.copy(
                    payload = TelemetryOutgoingQueuePayload(
                        ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT + 1,
                        0,
                        0,
                        0,
                        0,
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                event.copy(payload = TelemetryOutgoingQueuePayload(0, 0, 0, 1, 0)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                event.copy(payload = TelemetryOutgoingQueuePayload(0, 0, 0, 0, 1)),
            )
        }
        ClientTelemetryValidation.requireValid(
            event.copy(payload = TelemetryOutgoingQueuePayload(0, 1, 0, 1, 2)),
        )
        ClientTelemetryValidation.requireValid(
            event.copy(payload = TelemetryOutgoingQueuePayload(0, 0, 1, 0, 2)),
        )
    }

    @Test
    fun `reviewed feedback vocabulary has unique stable codes and exact public text lookup`() {
        assertEquals(
            TelemetryFeedbackCode.entries.size,
            TelemetryFeedbackCode.entries.map(TelemetryFeedbackCode::code).toSet().size,
        )
        TelemetryFeedbackCode.entries.forEach { feedback ->
            assertEquals(feedback, TelemetryFeedbackCode.fromCode(feedback.code))
            assertEquals(feedback, TelemetryFeedbackCode.forDisplayedMessage(feedback.publicMessage))
            assertTrue(feedback.publicMessage.none { it == '\r' || it == '\n' || it.isISOControl() })
        }
        assertEquals(
            TelemetryFeedbackCode.OPERATION_FAILED,
            TelemetryFeedbackCode.forDisplayedMessage("unreviewed dynamic detail"),
        )
    }

    @Test
    fun `ack must identify exact batch and final sequence`() {
        val request = TelemetryBatch(
            batchId = "batch-ack",
            createdAtEpochMs = 1,
            runtimeInfo = runtimeInfo(),
            events = listOf(event(7, "fault", TelemetryEventKind.FAULT, TelemetryFaultPayload("Log", "failed"))),
        )
        ClientTelemetryValidation.requireValid(
            TelemetryUploadResponse(
                ack = TelemetryAck(batchId = request.batchId, acceptedThroughSequence = 7),
            ),
            request,
        )
        assertFailsWith<IllegalArgumentException> {
            ClientTelemetryValidation.requireValid(
                TelemetryUploadResponse(
                    ack = TelemetryAck(batchId = "another", acceptedThroughSequence = 7),
                ),
                request,
            )
        }
    }

    private fun runtimeInfo() = ClientRuntimeInfo(
        platform = ClientPlatform.DESKTOP,
        osName = "macOS",
        osVersion = "15.0",
        architecture = "arm64",
        deviceModel = "Mac",
        appVersion = "1.0.0",
        buildNumber = "1",
        gitCommit = "0123456789ab",
        buildIdentity = "1.0.0+0123456789abcdef",
        buildTime = "2026-08-27 16:00",
        protocolVersion = 9,
        distribution = "desktop",
    )

    private fun event(
        sequence: Long,
        name: String,
        kind: TelemetryEventKind,
        payload: TelemetryEventPayload,
    ) = TelemetryEvent(
        eventId = "event-$sequence",
        runId = "run-1",
        sequence = sequence,
        occurredAtEpochMs = sequence + 1,
        eventName = name,
        kind = kind,
        payload = payload,
    )
}
