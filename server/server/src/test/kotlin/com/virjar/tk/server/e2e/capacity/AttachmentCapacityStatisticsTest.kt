package com.virjar.tk.server.e2e.capacity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentCapacityStatisticsTest {
    @Test
    fun `configuration records the bounded default load shape`() {
        val config = attachmentConfig()

        assertEquals(4, config.expectedWarmupUploads)
        assertEquals(16, config.expectedSteadyUploads)
        assertEquals(36, config.expectedUploads)
        assertEquals(36, config.expectedAuthenticatedDownloads)

        assertFailsWith<IllegalArgumentException> {
            attachmentConfig(uploaderCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            attachmentConfig(burstConcurrency = 17)
        }
        assertFailsWith<IllegalArgumentException> {
            attachmentConfig(downloadConcurrency = 37)
        }
        assertFailsWith<IllegalArgumentException> {
            attachmentConfig(cleanupObservationMs = -1)
        }
        assertFailsWith<ArithmeticException> {
            attachmentConfig(
                uploaderCount = Int.MAX_VALUE,
                warmupUploadsPerUser = 2,
            )
        }
    }

    @Test
    fun `upload and authenticated download layers record bytes throughput failures and latency`() {
        val successful = buildAttachmentTransferResult(
            name = AttachmentCapacityScenarioName.STEADY_UPLOAD,
            attempts = listOf(
                transferAttempt("object-1", bytes = 1_024, latencyNanos = 60_000_000_000L),
                transferAttempt("object-2", bytes = 1_024, latencyNanos = 90_000_000_000L),
            ),
            elapsedNanos = 1_000_000_000L,
        )

        assertTrue(successful.passed)
        assertEquals(2, successful.attempted)
        assertEquals(2_048, successful.requestedBytes)
        assertEquals(2_048, successful.transferredBytes)
        assertEquals(2_048.0, successful.successfulBytesPerSecond)
        assertEquals(2.0, successful.attemptsPerSecond)
        assertEquals(90_000.0, successful.latency.p95Ms)
        assertEquals(90_000.0, successful.latency.p99Ms)

        val failed = buildAttachmentTransferResult(
            name = AttachmentCapacityScenarioName.AUTHENTICATED_DOWNLOAD,
            attempts = listOf(
                transferAttempt("object-1", bytes = 1_024, latencyNanos = 1_000_000L),
                AttachmentTransferAttempt(
                    objectId = "object-2",
                    requestedBytes = 1_024,
                    transferredBytes = 512,
                    latencyNanos = 2_000_000L,
                    failureCategory = AttachmentCapacityFailureCategory.TIMEOUT,
                ),
            ),
            elapsedNanos = 4_000_000L,
        )

        assertFalse(failed.passed)
        assertEquals(1, failed.succeeded)
        assertEquals(1, failed.failed)
        assertEquals(2_048, failed.requestedBytes)
        assertEquals(1_536, failed.transferredBytes)
        assertEquals(1_024, failed.successfulBytes)
        assertEquals(mapOf("timeout" to 1), failed.failuresByCategory)
        assertEquals(2, failed.latency.sampleCount)
    }

    @Test
    fun `failure category names are stable and reject successful HTTP status`() {
        assertEquals("timeout", AttachmentCapacityFailureCategory.TIMEOUT)
        assertEquals("transport", AttachmentCapacityFailureCategory.TRANSPORT)
        assertEquals("decode", AttachmentCapacityFailureCategory.DECODE)
        assertEquals("unexpected", AttachmentCapacityFailureCategory.UNEXPECTED)
        assertEquals(
            "dependency_upload_failed",
            AttachmentCapacityFailureCategory.DEPENDENCY_UPLOAD_FAILED,
        )
        assertEquals(
            "delete_not_acknowledged",
            AttachmentCapacityFailureCategory.DELETE_NOT_ACKNOWLEDGED,
        )
        assertEquals(
            "business_reference_present",
            AttachmentCapacityFailureCategory.BUSINESS_REFERENCE_PRESENT,
        )
        assertEquals("http_status_503", AttachmentCapacityFailureCategory.httpStatus(503))
        assertFailsWith<IllegalArgumentException> {
            AttachmentCapacityFailureCategory.httpStatus(200)
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentTransferAttempt(
                objectId = "object",
                requestedBytes = 10,
                transferredBytes = 9,
                latencyNanos = 1,
            )
        }
    }

    @Test
    fun `correctness binds exact descriptor group file length sha and globally unique paths`() {
        val first = objectObservation(id = "first", path = "u/first")
        val second = objectObservation(id = "second", path = "u/second", sha = "b".repeat(64))

        val exact = buildAttachmentCorrectnessResult(2, listOf(first, second))

        assertTrue(exact.passed)
        assertEquals(2, exact.observedObjects)
        assertEquals(2, exact.uniqueObjectIds)
        assertEquals(2, exact.uniquePaths)
        assertEquals(2, exact.descriptorExactObjects)
        assertEquals(2, exact.businessReferenceExactObjects)
        assertEquals("a".repeat(64), exact.objects.first().downloadedSha256)
        assertFailsWith<IllegalArgumentException> {
            exact.copy(uniquePaths = 1, passed = false)
        }
        assertFailsWith<IllegalArgumentException> {
            exact.objects.first().copy(descriptor = null, passed = false)
        }

        val duplicatePath = buildAttachmentCorrectnessResult(
            expectedObjects = 2,
            observations = listOf(first, second.copy(descriptor = first.descriptor)),
        )
        assertFalse(duplicatePath.passed)
        assertEquals(1, duplicatePath.uniquePaths)

        val staleBusinessReference = buildAttachmentCorrectnessResult(
            expectedObjects = 1,
            observations = listOf(
                first.copy(
                    groupFileCurrentAttachment = first.descriptor?.copy(name = "stale.bin"),
                ),
            ),
        )
        assertFalse(staleBusinessReference.passed)
        assertEquals(0, staleBusinessReference.businessReferenceExactObjects)

        val missingObservation = buildAttachmentCorrectnessResult(2, listOf(first))
        assertFalse(missingObservation.passed)
        assertEquals(1, missingObservation.observedObjects)
    }

    @Test
    fun `malformed descriptor and downloaded content remain reportable failures`() {
        val observation = objectObservation(id = "object", path = "").copy(
            descriptor = descriptor(path = "", name = "wrong.bin", size = 12),
            groupFileCurrentAttachment = descriptor(path = "", name = "wrong.bin", size = 12),
            downloadedLength = 12,
            downloadedSha256 = "not-a-sha",
        )

        val result = buildAttachmentCorrectnessResult(1, listOf(observation))

        assertFalse(result.passed)
        assertEquals(0, result.uniquePaths)
        assertEquals(0, result.descriptorExactObjects)
        assertEquals(0, result.lengthExactObjects)
        assertEquals(0, result.sha256ExactObjects)
        assertEquals(1, result.businessReferenceExactObjects)
    }

    @Test
    fun `session stability requires every uploader generation to remain unchanged`() {
        val stable = buildAttachmentSessionStabilityResult(
            expectedSessions = 2,
            observations = listOf(sessionObservation(2), sessionObservation(1)),
        )
        val changed = buildAttachmentSessionStabilityResult(
            expectedSessions = 2,
            observations = listOf(
                sessionObservation(1),
                sessionObservation(2).copy(
                    authenticatedAfter = false,
                    authenticationCountAfter = 2,
                ),
            ),
        )

        assertTrue(stable.passed)
        assertEquals(listOf(1, 2), stable.sessions.map(AttachmentSessionResult::laneId))
        assertFalse(changed.passed)
        assertEquals(1, changed.unexpectedDisconnects)
        assertEquals(1, changed.unexpectedAuthenticationChanges)
        assertFailsWith<IllegalArgumentException> {
            buildAttachmentSessionStabilityResult(
                expectedSessions = 2,
                observations = listOf(sessionObservation(1), sessionObservation(1)),
            )
        }
    }

    @Test
    fun `resources gate stable process nine-part health and monotonic CPU only`() {
        val baseline = resourceSnapshot(phase = "baseline", cpuTicks = 10)
        val peak = resourceSnapshot(
            phase = "burst",
            rssBytes = Long.MAX_VALUE,
            threadCount = Int.MAX_VALUE,
            fdCount = Int.MAX_VALUE,
            cpuTicks = 30,
            hostLoad1 = 999.0,
            memAvailableBytes = 0,
        )
        val final = resourceSnapshot(phase = "cleanup", cpuTicks = 40)

        val healthy = summarizeAttachmentResources(listOf(baseline, peak, final))

        assertTrue(healthy.passed)
        assertEquals(9, healthy.requiredHealthyComponents)
        assertEquals(30, healthy.cpuTicksDelta)
        assertEquals(Long.MAX_VALUE, healthy.maxRssBytes)
        assertEquals(Int.MAX_VALUE, healthy.maxThreadCount)
        assertEquals(Int.MAX_VALUE, healthy.maxFdCount)
        assertEquals(999.0, healthy.maxHostLoad1)
        assertEquals(0, healthy.minMemAvailableBytes)
        assertFailsWith<IllegalArgumentException> {
            healthy.copy(
                snapshots = listOf(baseline, peak, final.copy(healthyComponents = 8)),
                passed = false,
            )
        }

        listOf(
            listOf(baseline, final.copy(invocationId = "another")),
            listOf(baseline, final.copy(mainPid = 43)),
            listOf(baseline, final.copy(buildIdentity = "another")),
            listOf(baseline, final.copy(healthStatus = "DOWN")),
            listOf(baseline, final.copy(healthyComponents = 8)),
            listOf(baseline, final.copy(totalComponents = 10, healthyComponents = 10)),
            listOf(baseline.copy(cpuTicks = 20), final.copy(cpuTicks = 10)),
        ).forEach { snapshots ->
            assertFalse(summarizeAttachmentResources(snapshots).passed)
        }
    }

    @Test
    fun `cleanup gates business reference deletion but not seven-day physical retention`() {
        val retained = buildAttachmentCleanupResult(
            expectedBusinessReferences = 36,
            observations = cleanupObservations(36),
            physicalObjectsRemainingForRetentionGc = 36,
            elapsedNanos = 1_000_000,
        )
        val failed = buildAttachmentCleanupResult(
            expectedBusinessReferences = 36,
            observations = cleanupObservations(36).mapIndexed { index, observation ->
                if (index == 0) {
                    observation.copy(
                        deleteAcknowledged = true,
                        absentAfterCleanup = false,
                    )
                } else {
                    observation
                }
            },
            physicalObjectsRemainingForRetentionGc = 36,
            elapsedNanos = 2_000_000,
        )

        assertTrue(retained.passed)
        assertEquals(36, retained.physicalObjectsRemainingForRetentionGc)
        assertEquals(36, retained.deleteAcknowledgedObjects)
        assertEquals(36, retained.absentAfterCleanupObjects)
        assertFalse(failed.passed)
        assertEquals(1, failed.failed)
        assertEquals(
            mapOf(AttachmentCapacityFailureCategory.BUSINESS_REFERENCE_PRESENT to 1),
            failed.failuresByCategory,
        )
    }

    @Test
    fun `cleanup aggregates cannot be forged and require unique object and entry ids`() {
        val result = buildAttachmentCleanupResult(
            expectedBusinessReferences = 2,
            observations = cleanupObservations(2),
            physicalObjectsRemainingForRetentionGc = 2,
            elapsedNanos = 1_000_000,
        )

        assertFailsWith<IllegalArgumentException> {
            result.copy(attempted = 1, passed = false)
        }
        assertFailsWith<IllegalArgumentException> {
            result.copy(deleteAcknowledgedObjects = 1, passed = false)
        }
        assertFailsWith<IllegalArgumentException> {
            result.copy(absentAfterCleanupObjects = 1, passed = false)
        }
        assertFailsWith<IllegalArgumentException> {
            result.copy(deleted = 1, failed = 1, passed = false)
        }
        assertFailsWith<IllegalArgumentException> {
            result.copy(
                failuresByCategory = mapOf(AttachmentCapacityFailureCategory.TIMEOUT to 1),
                passed = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            result.objects.first().copy(deleteAcknowledged = false)
        }
        assertFailsWith<IllegalArgumentException> {
            result.objects.first().copy(passed = false)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAttachmentCleanupResult(
                expectedBusinessReferences = 2,
                observations = cleanupObservations(2).mapIndexed { index, observation ->
                    if (index == 1) observation.copy(objectId = "object-0") else observation
                },
                physicalObjectsRemainingForRetentionGc = 2,
                elapsedNanos = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildAttachmentCleanupResult(
                expectedBusinessReferences = 2,
                observations = cleanupObservations(2).mapIndexed { index, observation ->
                    if (index == 1) observation.copy(entryId = "entry-0") else observation
                },
                physicalObjectsRemainingForRetentionGc = 2,
                elapsedNanos = 1,
            )
        }
    }

    @Test
    fun `cleanup refuses an ACK without absence and absence without an ACK`() {
        val result = buildAttachmentCleanupResult(
            expectedBusinessReferences = 2,
            observations = listOf(
                cleanupObservation(0).copy(absentAfterCleanup = false),
                cleanupObservation(1).copy(deleteAcknowledged = false),
            ),
            physicalObjectsRemainingForRetentionGc = 2,
            elapsedNanos = 1,
        )

        assertFalse(result.passed)
        assertEquals(1, result.deleteAcknowledgedObjects)
        assertEquals(1, result.absentAfterCleanupObjects)
        assertEquals(0, result.deleted)
        assertEquals(2, result.failed)
        assertEquals(
            mapOf(
                AttachmentCapacityFailureCategory.BUSINESS_REFERENCE_PRESENT to 1,
                AttachmentCapacityFailureCategory.DELETE_NOT_ACKNOWLEDGED to 1,
            ),
            result.failuresByCategory,
        )
    }

    @Test
    fun `report binds every load layer and evidence result to its configuration`() {
        val report = healthyReport()

        assertTrue(report.passed)
        assertEquals(36, report.correctness.expectedObjects)
        assertEquals(36, report.authenticatedDownload.attempted)

        assertFailsWith<IllegalArgumentException> {
            buildAttachmentCapacityReport(
                generatedAt = report.generatedAt,
                target = report.target,
                config = report.config.copy(warmupUploadsPerUser = 3),
                warmupUpload = report.warmupUpload,
                steadyUpload = report.steadyUpload,
                burstUpload = report.burstUpload,
                authenticatedDownload = report.authenticatedDownload,
                correctness = report.correctness,
                sessions = report.sessions,
                cleanup = report.cleanup,
                resources = report.resources,
            )
        }
        val wrongCleanupObjectSet = buildAttachmentCleanupResult(
            expectedBusinessReferences = report.config.expectedUploads,
            observations = cleanupObservations(report.config.expectedUploads).mapIndexed {
                    index, observation ->
                if (index == 0) observation.copy(objectId = "not-in-correctness") else observation
            },
            physicalObjectsRemainingForRetentionGc = report.config.expectedUploads,
            elapsedNanos = 1,
        )
        assertFailsWith<IllegalArgumentException> {
            buildAttachmentCapacityReport(
                generatedAt = report.generatedAt,
                target = report.target,
                config = report.config,
                warmupUpload = report.warmupUpload,
                steadyUpload = report.steadyUpload,
                burstUpload = report.burstUpload,
                authenticatedDownload = report.authenticatedDownload,
                correctness = report.correctness,
                sessions = report.sessions,
                cleanup = wrongCleanupObjectSet,
                resources = report.resources,
            )
        }

        val repeatedDownload = successfulTransfer(
            name = AttachmentCapacityScenarioName.AUTHENTICATED_DOWNLOAD,
            objectIds = List(report.config.expectedAuthenticatedDownloads) { "object-0" },
            bytes = report.config.payloadBytes,
        )
        assertFailsWith<IllegalArgumentException> {
            buildAttachmentCapacityReport(
                generatedAt = report.generatedAt,
                target = report.target,
                config = report.config,
                warmupUpload = report.warmupUpload,
                steadyUpload = report.steadyUpload,
                burstUpload = report.burstUpload,
                authenticatedDownload = repeatedDownload,
                correctness = report.correctness,
                sessions = report.sessions,
                cleanup = report.cleanup,
                resources = report.resources,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildAttachmentCapacityReport(
                generatedAt = report.generatedAt,
                target = report.target,
                config = report.config,
                warmupUpload = report.steadyUpload.copy(
                    name = AttachmentCapacityScenarioName.WARMUP_UPLOAD,
                ),
                steadyUpload = report.steadyUpload,
                burstUpload = report.burstUpload,
                authenticatedDownload = report.authenticatedDownload,
                correctness = report.correctness,
                sessions = report.sessions,
                cleanup = report.cleanup,
                resources = report.resources,
            )
        }
    }

    @Test
    fun `one failed transfer makes the top-level report fail without hiding other evidence`() {
        val healthy = healthyReport()
        val attempts = List(healthy.config.expectedSteadyUploads) { index ->
            val objectId = "object-${healthy.config.expectedWarmupUploads + index}"
            if (index == 0) {
                AttachmentTransferAttempt(
                    objectId = objectId,
                    requestedBytes = healthy.config.payloadBytes,
                    transferredBytes = 0,
                    latencyNanos = 1_000_000,
                    failureCategory = AttachmentCapacityFailureCategory.TRANSPORT,
                )
            } else {
                transferAttempt(objectId, healthy.config.payloadBytes, 1_000_000)
            }
        }
        val failedSteady = buildAttachmentTransferResult(
            name = AttachmentCapacityScenarioName.STEADY_UPLOAD,
            attempts = attempts,
            elapsedNanos = 20_000_000,
        )

        val report = buildAttachmentCapacityReport(
            generatedAt = healthy.generatedAt,
            target = healthy.target,
            config = healthy.config,
            warmupUpload = healthy.warmupUpload,
            steadyUpload = failedSteady,
            burstUpload = healthy.burstUpload,
            authenticatedDownload = healthy.authenticatedDownload,
            correctness = healthy.correctness,
            sessions = healthy.sessions,
            cleanup = healthy.cleanup,
            resources = healthy.resources,
        )

        assertFalse(report.passed)
        assertFalse(report.steadyUpload.passed)
        assertTrue(report.correctness.passed)
        assertTrue(report.resources.passed)
    }

    @Test
    fun `run state and final report atomically replace stale JSON`() {
        val report = healthyReport()
        val directory = Files.createTempDirectory("attachment-capacity-report-test").toFile()
        val target = directory.resolve("nested/attachment-capacity.json")
        try {
            target.parentFile.mkdirs()
            target.writeText("stale report")

            CapacityReportWriter.writeAtomically(
                AttachmentCapacityRunState(
                    generatedAt = "2026-01-01T00:00:00Z",
                    runId = report.config.runId,
                    target = report.target,
                    state = "started",
                    phase = "configuration",
                ),
                target,
            )
            assertEquals(
                "attachment-capacity-run-state",
                Json.parseToJsonElement(target.readText()).jsonObject
                    .getValue("reportType").jsonPrimitive.content,
            )

            CapacityReportWriter.writeAtomically(report, target)

            val parsed = Json.parseToJsonElement(target.readText()).jsonObject
            assertEquals(1, parsed.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertTrue(parsed.getValue("passed").jsonPrimitive.content.toBoolean())
            assertEquals(
                36,
                parsed.getValue("correctness").jsonObject
                    .getValue("uniquePaths").jsonPrimitive.content.toInt(),
            )
            assertTrue(parsed.getValue("note").jsonPrimitive.content.contains("retention GC"))
            assertTrue(target.readText().endsWith("\n"))
            Files.list(target.parentFile.toPath()).use { entries ->
                assertFalse(
                    entries.anyMatch { path -> path.fileName.toString().startsWith(".capacity-") },
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty evidence remains a failed observation instead of defining a latency gate`() {
        val empty = buildAttachmentTransferResult(
            name = AttachmentCapacityScenarioName.WARMUP_UPLOAD,
            attempts = emptyList(),
            elapsedNanos = 0,
        )

        assertFalse(empty.passed)
        assertEquals(0, empty.latency.sampleCount)
        assertNull(empty.latency.p99Ms)
    }

    private fun healthyReport(): AttachmentCapacityReport {
        val config = attachmentConfig()
        val objectIds = List(config.expectedUploads) { index -> "object-$index" }
        val observations = objectIds.mapIndexed { index, objectId ->
            objectObservation(
                id = objectId,
                path = "u/path-$index",
                sha = index.toString(16).padStart(64, '0'),
            )
        }
        return buildAttachmentCapacityReport(
            generatedAt = "2026-01-01T00:00:00Z",
            target = CapacityTarget("example.test", 5100),
            config = config,
            warmupUpload = successfulTransfer(
                name = AttachmentCapacityScenarioName.WARMUP_UPLOAD,
                objectIds = objectIds.take(config.expectedWarmupUploads),
                bytes = config.payloadBytes,
            ),
            steadyUpload = successfulTransfer(
                name = AttachmentCapacityScenarioName.STEADY_UPLOAD,
                objectIds = objectIds.drop(config.expectedWarmupUploads)
                    .take(config.expectedSteadyUploads),
                bytes = config.payloadBytes,
            ),
            burstUpload = successfulTransfer(
                name = AttachmentCapacityScenarioName.BURST_UPLOAD,
                objectIds = objectIds.takeLast(config.burstUploadsTotal),
                bytes = config.payloadBytes,
            ),
            authenticatedDownload = successfulTransfer(
                name = AttachmentCapacityScenarioName.AUTHENTICATED_DOWNLOAD,
                objectIds = objectIds.flatMap { objectId ->
                    List(config.downloadsPerAttachment) { objectId }
                },
                bytes = config.payloadBytes,
            ),
            correctness = buildAttachmentCorrectnessResult(
                expectedObjects = config.expectedUploads,
                observations = observations,
            ),
            sessions = buildAttachmentSessionStabilityResult(
                expectedSessions = config.uploaderCount,
                observations = List(config.uploaderCount) { index -> sessionObservation(index) },
            ),
            cleanup = buildAttachmentCleanupResult(
                expectedBusinessReferences = config.expectedUploads,
                observations = cleanupObservations(config.expectedUploads),
                physicalObjectsRemainingForRetentionGc = config.expectedUploads,
                elapsedNanos = 10_000_000,
            ),
            resources = summarizeAttachmentResources(
                listOf(
                    resourceSnapshot("baseline", cpuTicks = 10),
                    resourceSnapshot("cleanup", cpuTicks = 20),
                ),
            ),
        )
    }

    private fun successfulTransfer(
        name: String,
        objectIds: List<String>,
        bytes: Long,
    ): AttachmentTransferResult = buildAttachmentTransferResult(
        name = name,
        attempts = objectIds.map { objectId -> transferAttempt(objectId, bytes, 1_000_000) },
        elapsedNanos = objectIds.size * 1_000_000L,
    )

    private fun attachmentConfig(
        uploaderCount: Int = 2,
        payloadBytes: Long = 512L * 1_024L,
        warmupUploadsPerUser: Int = 2,
        steadyUploadsPerUser: Int = 8,
        burstUploadsTotal: Int = 16,
        burstConcurrency: Int = 4,
        downloadsPerAttachment: Int = 1,
        downloadConcurrency: Int = 4,
        cleanupObservationMs: Long = 0,
    ): AttachmentCapacityConfig = AttachmentCapacityConfig(
        runId = "attachment-test-run",
        uploaderCount = uploaderCount,
        payloadBytes = payloadBytes,
        warmupUploadsPerUser = warmupUploadsPerUser,
        steadyUploadsPerUser = steadyUploadsPerUser,
        steadyIntervalMs = 100,
        burstUploadsTotal = burstUploadsTotal,
        burstConcurrency = burstConcurrency,
        downloadsPerAttachment = downloadsPerAttachment,
        downloadConcurrency = downloadConcurrency,
        requestTimeoutMs = 10_000,
        resourceSampleIntervalMs = 2_000,
        cleanupObservationMs = cleanupObservationMs,
    )

    private fun transferAttempt(
        objectId: String,
        bytes: Long,
        latencyNanos: Long,
    ) = AttachmentTransferAttempt(
        objectId = objectId,
        requestedBytes = bytes,
        transferredBytes = bytes,
        latencyNanos = latencyNanos,
    )

    private fun cleanupObservations(count: Int): List<AttachmentCleanupObservation> =
        List(count, ::cleanupObservation)

    private fun cleanupObservation(index: Int) = AttachmentCleanupObservation(
        objectId = "object-$index",
        entryId = "entry-$index",
        deleteAcknowledged = true,
        absentAfterCleanup = true,
    )

    private fun objectObservation(
        id: String,
        path: String,
        sha: String = "a".repeat(64),
    ): AttachmentObjectObservation {
        val descriptor = descriptor(path = path)
        return AttachmentObjectObservation(
            objectId = id,
            expectedName = "payload.bin",
            expectedContentType = "application/octet-stream",
            expectedLength = 512L * 1_024L,
            expectedSha256 = sha,
            descriptor = descriptor,
            groupFileCurrentAttachment = descriptor,
            downloadedLength = 512L * 1_024L,
            downloadedSha256 = sha.uppercase(),
        )
    }

    private fun descriptor(
        path: String,
        name: String = "payload.bin",
        size: Long = 512L * 1_024L,
    ) = AttachmentDescriptorEvidence(
        path = path,
        name = name,
        contentType = "application/octet-stream",
        size = size,
    )

    private fun sessionObservation(laneId: Int) = AttachmentSessionObservation(
        laneId = laneId,
        authenticatedBefore = true,
        authenticatedAfter = true,
        authenticationCountBefore = 1,
        authenticationCountAfter = 1,
    )

    private fun resourceSnapshot(
        phase: String,
        invocationId: String = "invocation",
        mainPid: Long = 42,
        rssBytes: Long = 1_024,
        threadCount: Int = 10,
        fdCount: Int = 20,
        cpuTicks: Long,
        hostLoad1: Double = 0.5,
        memAvailableBytes: Long = 4_096,
        healthStatus: String = "UP",
        buildIdentity: String = "build-1",
        healthyComponents: Int = 9,
        totalComponents: Int = 9,
    ) = TeamTalkResourceSnapshot(
        phase = phase,
        capturedAt = "2026-01-01T00:00:00Z",
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
}
