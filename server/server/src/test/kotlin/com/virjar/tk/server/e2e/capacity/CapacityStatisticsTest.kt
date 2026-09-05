package com.virjar.tk.server.e2e.capacity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapacityStatisticsTest {
    @Test
    fun `nearest-rank percentiles are deterministic for small samples`() {
        val summary = summarizeAckLatencies(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).map { it * 1_000_000L },
        )

        assertEquals(10, summary.sampleCount)
        assertEquals(1.0, summary.minMs)
        assertEquals(5.0, summary.p50Ms)
        assertEquals(10.0, summary.p95Ms)
        assertEquals(10.0, summary.p99Ms)
        assertEquals(10.0, summary.maxMs)
    }

    @Test
    fun `empty samples do not fabricate latency numbers`() {
        val summary = summarizeAckLatencies(emptyList())

        assertEquals(0, summary.sampleCount)
        assertNull(summary.minMs)
        assertNull(summary.p50Ms)
        assertNull(summary.p95Ms)
        assertNull(summary.p99Ms)
        assertNull(summary.maxMs)
    }

    @Test
    fun `connection latency gates include their percentile boundaries`() {
        val rampAtBoundary = buildConnectionRampResult(
            attempted = 20,
            authenticated = 20,
            failureCategories = emptyList(),
            elapsedNanos = 10_000_000_000L,
            readyLatenciesNanos = List(18) { millisToNanos(1.0) } +
                listOf(millisToNanos(5_000.0), millisToNanos(10_000.0)),
        )
        val rampOverP95 = buildConnectionRampResult(
            attempted = 20,
            authenticated = 20,
            failureCategories = emptyList(),
            elapsedNanos = 10_000_000_000L,
            readyLatenciesNanos = listOf(millisToNanos(1.0)) +
                List(18) { millisToNanos(5_000.0) + 1L } +
                listOf(millisToNanos(10_000.0)),
        )
        val rampOverP99 = buildConnectionRampResult(
            attempted = 20,
            authenticated = 20,
            failureCategories = emptyList(),
            elapsedNanos = 10_000_000_000L,
            readyLatenciesNanos = List(19) { millisToNanos(5_000.0) } +
                (millisToNanos(10_000.0) + 1L),
        )
        val reconnectAtBoundary = buildConnectionReconnectResult(
            targeted = 20,
            recovered = 20,
            exactAuthenticationDelta = 20,
            controlClients = 44,
            stableControlClients = 44,
            elapsedNanos = 30_000_000_000L,
            latenciesNanos = List(19) { millisToNanos(15_000.0) } +
                millisToNanos(30_000.0),
        )
        val reconnectOverP95 = buildConnectionReconnectResult(
            targeted = 20,
            recovered = 20,
            exactAuthenticationDelta = 20,
            controlClients = 44,
            stableControlClients = 44,
            elapsedNanos = 30_000_000_000L,
            latenciesNanos = listOf(millisToNanos(1.0)) +
                List(19) { millisToNanos(15_000.0) + 1L },
        )
        val reconnectOverMax = buildConnectionReconnectResult(
            targeted = 20,
            recovered = 20,
            exactAuthenticationDelta = 20,
            controlClients = 44,
            stableControlClients = 44,
            elapsedNanos = 30_000_000_000L,
            latenciesNanos = List(19) { millisToNanos(15_000.0) } +
                (millisToNanos(30_000.0) + 1L),
        )

        assertTrue(rampAtBoundary.passed)
        assertEquals(5_000.0, rampAtBoundary.readyLatency.p95Ms)
        assertEquals(10_000.0, rampAtBoundary.readyLatency.p99Ms)
        assertFalse(rampOverP95.passed)
        assertEquals(5_000.0, rampOverP95.readyLatency.p95Ms)
        assertFalse(rampOverP99.passed)
        assertEquals(10_000.0, rampOverP99.readyLatency.p99Ms)
        assertTrue(reconnectAtBoundary.passed)
        assertEquals(15_000.0, reconnectAtBoundary.latency.p95Ms)
        assertFalse(reconnectOverP95.passed)
        assertEquals(15_000.0, reconnectOverP95.latency.p95Ms)
        assertEquals(15_000.0, reconnectOverMax.latency.p95Ms)
        assertEquals(30_000.0, reconnectOverMax.latency.maxMs)
        assertFalse(reconnectOverMax.passed)
    }

    @Test
    fun `connection hold requires every client without disconnect or authentication churn`() {
        assertTrue(
            buildConnectionHoldResult(
                expectedClients = 64,
                authenticatedSamples = listOf(64, 64, 64),
                unexpectedDisconnects = 0,
                unexpectedAuthenticationChanges = 0,
            ).passed,
        )
        assertFalse(
            buildConnectionHoldResult(
                expectedClients = 64,
                authenticatedSamples = listOf(64, 63, 64),
                unexpectedDisconnects = 1,
                unexpectedAuthenticationChanges = 1,
            ).passed,
        )
    }

    @Test
    fun `throughput and overload failure categories are stable and rounded`() {
        assertEquals(4.0, capacityThroughputPerSecond(attempted = 10, elapsedNanos = 2_500_000_000L))
        assertEquals(0.0, capacityThroughputPerSecond(attempted = 0, elapsedNanos = 0L))
        assertEquals(
            mapOf("busy_503" to 2, "timeout" to 1, "transport" to 1),
            failureCounts(listOf("timeout", "busy_503", "transport", "busy_503")),
        )
        assertTrue(isRecoverableCapacityFailure(CapacityFailureCategory.BUSY_503))
        assertTrue(isRecoverableCapacityFailure(CapacityFailureCategory.TIMEOUT))
        assertTrue(isRecoverableCapacityFailure(CapacityFailureCategory.TRANSPORT))
        assertFalse(isRecoverableCapacityFailure(CapacityFailureCategory.ackCode(500)))
        assertFalse(isRecoverableCapacityFailure(CapacityFailureCategory.UNEXPECTED))
    }

    @Test
    fun `synthetic ack timeout is classified as a recoverable timeout`() {
        val expected = CapacityMessageIdentity("chat", "message")

        assertEquals(
            CapacityFailureCategory.TIMEOUT,
            classifyCapacityAcknowledgement(
                expectedIdentity = expected,
                actualIdentity = expected,
                serverSeq = 0,
                code = CapacityFailureCategory.CLIENT_ACK_TIMEOUT_CODE,
            ),
        )
        assertTrue(isRecoverableCapacityFailure(CapacityFailureCategory.TIMEOUT))
        assertEquals(
            CapacityFailureCategory.BUSY_503,
            classifyCapacityAcknowledgement(expected, expected, serverSeq = 0, code = 503),
        )
        assertNull(
            classifyCapacityAcknowledgement(expected, expected, serverSeq = 1, code = 0),
        )
    }

    @Test
    fun `server sequences are unique and contiguous per lane rather than globally`() {
        val laneOneFirst = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "shared-1"),
        )
        val laneOneSecond = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "shared-2"),
        )
        val laneTwoFirst = CapacityTrackedMessage(
            2,
            CapacityMessageIdentity("chat-two", "shared-1"),
        )
        val laneTwoSecond = CapacityTrackedMessage(
            2,
            CapacityMessageIdentity("chat-two", "shared-2"),
        )
        val acknowledgements = listOf(
            CapacityAcceptedMessage(laneOneFirst, 1),
            CapacityAcceptedMessage(laneOneSecond, 2),
            CapacityAcceptedMessage(laneTwoFirst, 1),
            CapacityAcceptedMessage(laneTwoSecond, 2),
        )
        val observed = acknowledgements.associate { it.message.identity to listOf(it.serverSeq) }

        val integrity = buildCapacityIntegrity(
            laneIds = setOf(1, 2),
            expectedMessages = acknowledgements.mapTo(
                linkedSetOf(),
                CapacityAcceptedMessage::message,
            ),
            acknowledgements = acknowledgements,
            notificationSeqs = observed,
            historySeqs = observed,
        )

        assertTrue(integrity.passed)
        assertEquals(4, integrity.expectedMessages)
        assertEquals(2, integrity.laneCount)
        assertEquals(listOf(2, 2), integrity.lanes.map { it.ackUniqueServerSeqs })
        assertTrue(integrity.lanes.all { it.serverSeqContiguous })
    }

    @Test
    fun `a sequence hole fails only its lane and the aggregate`() {
        val first = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "one-1"),
        )
        val second = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "one-2"),
        )
        val acknowledgements = listOf(
            CapacityAcceptedMessage(first, 4),
            CapacityAcceptedMessage(second, 6),
        )
        val observed = acknowledgements.associate { it.message.identity to listOf(it.serverSeq) }

        val integrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = observed,
            historySeqs = observed,
        )

        assertFalse(integrity.passed)
        assertFalse(integrity.lanes.single().serverSeqContiguous)
    }

    @Test
    fun `a contiguous sequence with a missing prefix fails a new chat lane`() {
        val first = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "one-1"),
        )
        val second = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "one-2"),
        )
        val acknowledgements = listOf(
            CapacityAcceptedMessage(first, 4),
            CapacityAcceptedMessage(second, 5),
        )
        val observed = acknowledgements.associate { it.message.identity to listOf(it.serverSeq) }

        val integrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = observed,
            historySeqs = observed,
        )

        assertFalse(integrity.passed)
        assertFalse(integrity.lanes.single().serverSeqContiguous)
    }

    @Test
    fun `notification loss and duplicate history are attributed to the owning lane`() {
        val healthy = CapacityTrackedMessage(
            1,
            CapacityMessageIdentity("chat-one", "shared"),
        )
        val broken = CapacityTrackedMessage(
            2,
            CapacityMessageIdentity("chat-two", "shared"),
        )
        val acknowledgements = listOf(
            CapacityAcceptedMessage(healthy, 1),
            CapacityAcceptedMessage(broken, 1),
        )

        val integrity = buildCapacityIntegrity(
            laneIds = setOf(1, 2),
            expectedMessages = setOf(healthy, broken),
            acknowledgements = acknowledgements,
            notificationSeqs = mapOf(healthy.identity to listOf(1L)),
            historySeqs = mapOf(
                healthy.identity to listOf(1L),
                broken.identity to listOf(1L, 1L),
            ),
        )

        assertFalse(integrity.passed)
        assertTrue(integrity.lanes.single { it.laneId == 1 }.passed)
        assertFalse(integrity.lanes.single { it.laneId == 2 }.passed)
        assertEquals(1, integrity.notificationMissing)
        assertEquals(1, integrity.historyDuplicateMessages)
    }

    @Test
    fun `overload recovery cannot pass without observed busy rejection`() {
        assertFailsWith<IllegalArgumentException> {
            CapacityRecoveryResult(
                initialRecoverableMessages = 0,
                initialBusyRejected = 0,
                initialTimeouts = 0,
                initialTransportFailures = 0,
                initialTerminalFailures = 0,
                recoveryAttempts = 1,
                attemptFailuresByCategory = emptyMap(),
                recoveredMessages = 0,
                unrecoveredMessages = 0,
                recoveryTerminalFailures = 0,
                freshProbeExpectedLanes = 1,
                freshProbeSucceededLanes = 1,
                elapsedMs = 1.0,
                overloadObserved = false,
                passed = true,
            )
        }
    }

    @Test
    fun `multi page event catchup passes after one authentication and exact replay`() {
        val first = CapacityTrackedMessage(1, CapacityMessageIdentity("chat", "one"))
        val second = CapacityTrackedMessage(1, CapacityMessageIdentity("chat", "two"))
        val acknowledgements = listOf(
            CapacityAcceptedMessage(first, 1),
            CapacityAcceptedMessage(second, 2),
        )
        val observed = acknowledgements.associate { accepted ->
            accepted.message.identity to listOf(accepted.serverSeq)
        }
        val integrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = observed,
            historySeqs = observed,
        )

        val catchup = buildCapacityEventCatchupResult(
            baseCursor = 10,
            targetCursor = 138,
            finalCursor = 138,
            minimumBacklogEvents = 128,
            syncPageSize = 64,
            authenticationCountBefore = 1,
            authenticationCountAfter = 2,
            replayIntegrity = integrity,
            localProjectionIntegrity = integrity,
            expectedConversations = 1,
            convergedConversations = 1,
            elapsedNanos = 2_000_000_000,
        )

        assertTrue(catchup.passed)
        assertEquals(128L, catchup.backlogEvents)
        assertEquals(2L, catchup.minimumReplayPages)
        assertEquals(1, catchup.authenticationDelta)
        assertEquals(2, catchup.replayUniqueMessages)
        assertEquals(2, catchup.localProjectionUniqueMessages)
        assertTrue(catchup.localProjectionConverged)
        assertEquals(64.0, catchup.eventsPerSecond)
    }

    @Test
    fun `single page duplicate replay and repeated authentication fail event catchup`() {
        val first = CapacityTrackedMessage(1, CapacityMessageIdentity("chat", "one"))
        val second = CapacityTrackedMessage(1, CapacityMessageIdentity("chat", "two"))
        val acknowledgements = listOf(
            CapacityAcceptedMessage(first, 1),
            CapacityAcceptedMessage(second, 2),
        )
        val replay = mapOf(
            first.identity to listOf(1L, 1L),
            second.identity to listOf(2L),
        )
        val history = acknowledgements.associate { accepted ->
            accepted.message.identity to listOf(accepted.serverSeq)
        }
        val replayIntegrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = replay,
            historySeqs = history,
        )
        val localProjectionIntegrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = history,
            historySeqs = history,
        )

        val catchup = buildCapacityEventCatchupResult(
            baseCursor = 10,
            targetCursor = 74,
            finalCursor = 74,
            minimumBacklogEvents = 128,
            syncPageSize = 64,
            authenticationCountBefore = 1,
            authenticationCountAfter = 3,
            replayIntegrity = replayIntegrity,
            localProjectionIntegrity = localProjectionIntegrity,
            expectedConversations = 1,
            convergedConversations = 0,
            elapsedNanos = 1_000_000_000,
        )

        assertFalse(catchup.passed)
        assertEquals(1L, catchup.minimumReplayPages)
        assertEquals(2, catchup.authenticationDelta)
        assertEquals(1, catchup.replayDuplicateMessages)
        assertFalse(catchup.ackReplaySeqConverged)
        assertTrue(catchup.localProjectionConverged)
    }

    @Test
    fun `exact replay cannot hide a missing final local message projection`() {
        val first = CapacityTrackedMessage(1, CapacityMessageIdentity("chat", "one"))
        val second = CapacityTrackedMessage(1, CapacityMessageIdentity("chat", "two"))
        val acknowledgements = listOf(
            CapacityAcceptedMessage(first, 1),
            CapacityAcceptedMessage(second, 2),
        )
        val exact = acknowledgements.associate { accepted ->
            accepted.message.identity to listOf(accepted.serverSeq)
        }
        val replayIntegrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = exact,
            historySeqs = exact,
        )
        val localProjectionIntegrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(first, second),
            acknowledgements = acknowledgements,
            notificationSeqs = mapOf(first.identity to listOf(1L)),
            historySeqs = exact,
        )

        val catchup = buildCapacityEventCatchupResult(
            baseCursor = 10,
            targetCursor = 138,
            finalCursor = 138,
            minimumBacklogEvents = 128,
            syncPageSize = 64,
            authenticationCountBefore = 1,
            authenticationCountAfter = 2,
            replayIntegrity = replayIntegrity,
            localProjectionIntegrity = localProjectionIntegrity,
            expectedConversations = 1,
            convergedConversations = 1,
            elapsedNanos = 1_000_000_000,
        )

        assertFalse(catchup.passed)
        assertEquals(0, catchup.replayMissingMessages)
        assertEquals(1, catchup.localProjectionMissingMessages)
        assertFalse(catchup.localProjectionConverged)
    }

    @Test
    fun `connection resource gates are inclusive and ignore cpu and rss magnitude`() {
        val baseline = resourceSnapshot(
            phase = "baseline",
            rssBytes = 1_000,
            threadCount = 100,
            fdCount = 200,
            cpuTicks = 10,
        )
        val peakAtBoundary = resourceSnapshot(
            phase = "hold",
            rssBytes = Long.MAX_VALUE,
            threadCount = 132,
            fdCount = 392,
            cpuTicks = Long.MAX_VALUE,
            hostLoad1 = 99.0,
            memAvailableBytes = 0,
        )
        val cleanupAtBoundary = resourceSnapshot(
            phase = "cleanup",
            rssBytes = 2_000,
            threadCount = 116,
            fdCount = 216,
            cpuTicks = 20,
        )

        val result = summarizeConnectionResources(
            clientCount = 64,
            snapshots = listOf(baseline, peakAtBoundary, cleanupAtBoundary),
        )

        assertTrue(result.passed)
        assertEquals(3, result.sampleCount)
        assertEquals(listOf("baseline", "hold", "cleanup"), result.snapshots.map { it.phase })
        assertEquals(Long.MAX_VALUE, result.maxRssBytes)
        assertEquals(132, result.maxThreadCount)
        assertEquals(392, result.maxFdCount)
        assertEquals(200, result.baselineFdCount)
        assertEquals(216, result.finalFdCount)
        assertEquals(100, result.baselineThreadCount)
        assertEquals(116, result.finalThreadCount)

        listOf(
            listOf(baseline, peakAtBoundary.copy(threadCount = 133), cleanupAtBoundary),
            listOf(baseline, peakAtBoundary.copy(fdCount = 393), cleanupAtBoundary),
            listOf(baseline, peakAtBoundary, cleanupAtBoundary.copy(threadCount = 117)),
            listOf(baseline, peakAtBoundary, cleanupAtBoundary.copy(fdCount = 217)),
            listOf(baseline, peakAtBoundary.copy(invocationId = "another"), cleanupAtBoundary),
            listOf(baseline, peakAtBoundary.copy(mainPid = 43), cleanupAtBoundary),
            listOf(baseline, peakAtBoundary.copy(buildIdentity = "another"), cleanupAtBoundary),
            listOf(baseline, peakAtBoundary.copy(healthStatus = "DOWN"), cleanupAtBoundary),
            listOf(baseline, peakAtBoundary.copy(healthyComponents = 2), cleanupAtBoundary),
        ).forEach { snapshots ->
            assertFalse(summarizeConnectionResources(64, snapshots).passed)
        }
    }

    @Test
    fun `writer publishes machine-readable report with non-commitment note`() {
        val directory = Files.createTempDirectory("capacity-report-test").toFile()
        val target = directory.resolve("nested/report.json")
        val identity = CapacityMessageIdentity("chat", "message")
        val trackedMessage = CapacityTrackedMessage(1, identity)
        val integrity = buildCapacityIntegrity(
            laneIds = setOf(1),
            expectedMessages = setOf(trackedMessage),
            acknowledgements = listOf(CapacityAcceptedMessage(trackedMessage, 1)),
            notificationSeqs = mapOf(identity to listOf(1L)),
            historySeqs = mapOf(identity to listOf(1L)),
        )
        val report = MessageCapacityReport(
            generatedAt = "2026-01-01T00:00:00Z",
            target = CapacityTarget("example.test", 5100),
            config = CapacityRunConfig(
                senderLanes = 1,
                warmupMessagesPerLane = 0,
                steadyMessagesPerLane = 1,
                steadyIntervalMs = 0,
                burstMessagesTotal = 1,
                burstConcurrency = 1,
                ackTimeoutMs = 10_000,
                deliveryTimeoutMs = 10_000,
                recoveryTimeoutMs = 10_000,
                recoveryRetryIntervalMs = 100,
                eventCatchupTimeoutMs = 20_000,
                eventCatchupMinimumEvents = 128,
            ),
            scenarios = listOf(
                CapacityScenarioMetrics(
                    name = "steady",
                    attempted = 1,
                    succeeded = 1,
                    failed = 0,
                    busyRejected = 0,
                    timedOut = 0,
                    transportFailed = 0,
                    failuresByCategory = emptyMap(),
                    elapsedMs = 2.0,
                    throughputPerSecond = 500.0,
                    ackLatency = summarizeAckLatencies(listOf(2_000_000L)),
                ),
            ),
            recovery = CapacityRecoveryResult(
                initialRecoverableMessages = 1,
                initialBusyRejected = 1,
                initialTimeouts = 0,
                initialTransportFailures = 0,
                initialTerminalFailures = 0,
                recoveryAttempts = 1,
                attemptFailuresByCategory = emptyMap(),
                recoveredMessages = 1,
                unrecoveredMessages = 0,
                recoveryTerminalFailures = 0,
                freshProbeExpectedLanes = 1,
                freshProbeSucceededLanes = 1,
                elapsedMs = 1.0,
                overloadObserved = true,
                passed = true,
            ),
            eventCatchup = buildCapacityEventCatchupResult(
                baseCursor = 1,
                targetCursor = 129,
                finalCursor = 129,
                minimumBacklogEvents = 128,
                syncPageSize = 64,
                authenticationCountBefore = 1,
                authenticationCountAfter = 2,
                replayIntegrity = integrity,
                localProjectionIntegrity = integrity,
                expectedConversations = 1,
                convergedConversations = 1,
                elapsedNanos = 1_000_000_000,
            ),
            integrity = integrity,
            passed = true,
        )
        try {
            CapacityReportWriter.writeAtomically(report, target)
            val parsed = Json.parseToJsonElement(target.readText()).jsonObject
            assertEquals(3, parsed.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertTrue(parsed.getValue("passed").jsonPrimitive.content.toBoolean())
            assertTrue(
                parsed.getValue("recovery").jsonObject
                    .getValue("overloadObserved").jsonPrimitive.content.toBoolean(),
            )
            assertEquals(
                2L,
                parsed.getValue("eventCatchup").jsonObject
                    .getValue("minimumReplayPages").jsonPrimitive.content.toLong(),
            )
            assertTrue(parsed.getValue("note").jsonPrimitive.content.contains("not a product"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `writer atomically replaces a connection report with schema and resource samples`() {
        val directory = Files.createTempDirectory("connection-capacity-report-test").toFile()
        val target = directory.resolve("nested/report.json")
        val ramp = buildConnectionRampResult(
            attempted = 2,
            authenticated = 2,
            failureCategories = emptyList(),
            elapsedNanos = 2_000_000_000L,
            readyLatenciesNanos = listOf(1_000_000L, 2_000_000L),
        )
        val hold = buildConnectionHoldResult(
            expectedClients = 2,
            authenticatedSamples = listOf(2, 2),
            unexpectedDisconnects = 0,
            unexpectedAuthenticationChanges = 0,
        )
        val reconnect = buildConnectionReconnectResult(
            targeted = 1,
            recovered = 1,
            exactAuthenticationDelta = 1,
            controlClients = 1,
            stableControlClients = 1,
            elapsedNanos = 1_000_000_000L,
            latenciesNanos = listOf(1_000_000L),
        )
        val resources = summarizeConnectionResources(
            clientCount = 2,
            snapshots = listOf(
                resourceSnapshot(phase = "baseline", cpuTicks = 10),
                resourceSnapshot(phase = "cleanup", cpuTicks = 20),
            ),
        )
        val report = ConnectionCapacityReport(
            generatedAt = "2026-01-01T00:00:00Z",
            target = CapacityTarget("example.test", 5100),
            config = ConnectionCapacityConfig(
                clientCount = 2,
                rampGroupSize = 1,
                rampIntervalMs = 1_000,
                holdDurationMs = 60_000,
                reconnectCount = 1,
                reconnectTimeoutMs = 30_000,
                sampleIntervalMs = 1_000,
                cleanupObservationMs = 5_000,
            ),
            ramp = ramp,
            hold = hold,
            reconnect = reconnect,
            resources = resources,
            passed = ramp.passed && hold.passed && reconnect.passed && resources.passed,
        )
        try {
            target.parentFile.mkdirs()
            target.writeText("stale report")

            CapacityReportWriter.writeAtomically(report, target)

            val text = target.readText()
            val parsed = Json.parseToJsonElement(text).jsonObject
            val resourceJson = parsed.getValue("resources").jsonObject
            val snapshots = resourceJson.getValue("snapshots").jsonArray
            assertEquals(1, parsed.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertEquals(2, parsed.getValue("config").jsonObject
                .getValue("clientCount").jsonPrimitive.content.toInt())
            assertTrue(parsed.getValue("passed").jsonPrimitive.content.toBoolean())
            assertEquals(2, snapshots.size)
            assertEquals(
                10L,
                snapshots.first().jsonObject.getValue("cpuTicks").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                "cleanup",
                snapshots.last().jsonObject.getValue("phase").jsonPrimitive.content,
            )
            assertTrue(parsed.getValue("note").jsonPrimitive.content.contains("not a product"))
            assertTrue(text.endsWith("\n"))
            Files.list(target.parentFile.toPath()).use { entries ->
                assertFalse(
                    entries.anyMatch { path -> path.fileName.toString().startsWith(".capacity-") },
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun resourceSnapshot(
        phase: String,
        capturedAt: String = "2026-01-01T00:00:00Z",
        invocationId: String = "00000000000000000000000000000042",
        mainPid: Long = 42,
        rssBytes: Long = 1_000,
        threadCount: Int = 10,
        fdCount: Int = 20,
        cpuTicks: Long = 1,
        hostLoad1: Double = 0.5,
        memAvailableBytes: Long = 1_000_000,
        healthStatus: String = "UP",
        buildIdentity: String = "build-identity",
        healthyComponents: Int = 3,
        totalComponents: Int = 3,
    ): TeamTalkResourceSnapshot = TeamTalkResourceSnapshot(
        phase = phase,
        capturedAt = capturedAt,
        invocationId = invocationId,
        mainPid = mainPid,
        rssBytes = rssBytes,
        threadCount = threadCount,
        fdCount = fdCount,
        cpuTicks = cpuTicks,
        hostLoad1 = hostLoad1,
        memAvailableBytes = memAvailableBytes,
        healthStatus = healthStatus,
        buildIdentity = buildIdentity,
        healthyComponents = healthyComponents,
        totalComponents = totalComponents,
    )

    private fun millisToNanos(millis: Double): Long = (millis * 1_000_000.0).toLong()
}
