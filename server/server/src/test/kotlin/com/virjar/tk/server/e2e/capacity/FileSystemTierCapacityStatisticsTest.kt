package com.virjar.tk.server.e2e.capacity

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemTierCapacityStatisticsTest {
    @Test
    fun `payload admission is strictly above the 32 MiB tier boundary`() {
        assertFailsWith<IllegalArgumentException> {
            config(payloadBytes = FILESYSTEM_TIER_BOUNDARY_BYTES)
        }
        assertEquals(
            FILESYSTEM_TIER_BOUNDARY_BYTES + 1L,
            config(payloadBytes = FILESYSTEM_TIER_BOUNDARY_BYTES + 1L).payloadBytes,
        )
    }

    @Test
    fun `passing report requires exact landing restart replay hashes and one reauthentication`() {
        val report = validReport()

        assertTrue(report.passed)
        assertEquals(report.firstDescriptor, report.peerDescriptor)
        assertEquals(report.firstDescriptor, report.replayedDescriptor)
        assertEquals(
            1L,
            report.afterUpload.payloadSizedFileCount - report.baseline.payloadSizedFileCount,
        )
        assertEquals(
            report.afterUpload.payloadSizedFileCount,
            report.afterReplay.payloadSizedFileCount,
        )
        assertEquals(
            report.ownerAuthenticationCountBefore + 1,
            report.ownerAuthenticationCountAfter,
        )
    }

    @Test
    fun `report rejects a duplicate replay file wrong hash or extra authentication`() {
        val report = validReport()
        assertFailsWith<IllegalArgumentException> {
            report.copy(
                afterReplay = report.afterReplay.copy(
                    fileCount = report.afterReplay.fileCount + 1L,
                    payloadSizedFileCount = report.afterReplay.payloadSizedFileCount + 1L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            report.copy(
                downloadAfterRestartAndReplay = report.downloadAfterRestartAndReplay.copy(
                    sha256 = "b".repeat(64),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            report.copy(ownerAuthenticationCountAfter = report.ownerAuthenticationCountAfter + 1)
        }
    }

    @Test
    fun `writer atomically replaces run state with final filesystem report`() {
        val directory = Files.createTempDirectory("filesystem-tier-capacity-report-").toFile()
        val target = directory.resolve("nested/filesystem-tier-capacity.json")
        try {
            CapacityReportWriter.writeAtomically(
                FileSystemTierCapacityRunState(
                    generatedAt = "2026-09-01T00:00:00Z",
                    runId = "run",
                    target = CapacityTarget("im.example", 5100),
                    state = "started",
                    phase = "configuration",
                ),
                target,
            )
            assertTrue(target.readText().contains("filesystem-tier-capacity-run-state"))

            CapacityReportWriter.writeAtomically(validReport(), target)
            val text = target.readText()
            assertTrue(text.contains("\"reportType\": \"filesystem-tier-capacity\""))
            assertTrue(text.contains("\"passed\": true"))
            assertFalse(text.contains("filesystem-tier-capacity-run-state"))
            Files.list(target.parentFile.toPath()).use { entries ->
                assertFalse(entries.anyMatch { path -> path.fileName.toString().startsWith(".capacity-") })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun validReport(): FileSystemTierCapacityReport {
        val config = config()
        val firstInvocation = "1".repeat(32)
        val secondInvocation = "2".repeat(32)
        val baseline = snapshot(
            phase = "baseline",
            invocation = firstInvocation,
            pid = 101L,
            files = 10L,
            bytes = 1_000_000L,
            matching = 0L,
        )
        val afterUpload = snapshot(
            phase = "after-upload",
            invocation = firstInvocation,
            pid = 101L,
            files = 11L,
            bytes = baseline.storedBytes + config.payloadBytes,
            matching = 1L,
        )
        val afterRestart = snapshot(
            phase = "after-restart",
            invocation = secondInvocation,
            pid = 202L,
            files = afterUpload.fileCount,
            bytes = afterUpload.storedBytes,
            matching = afterUpload.payloadSizedFileCount,
        )
        val descriptor = FileSystemTierDescriptorEvidence(
            path = "owner/object.bin",
            name = "object.bin",
            contentType = "application/octet-stream",
            size = config.payloadBytes,
        )
        val transfer = FileSystemTierTransferEvidence(
            streamedBytes = config.payloadBytes,
            sha256 = EXPECTED_SHA256,
            progressCallbacks = 513,
            finalProgress = 1f,
            passed = true,
        )
        return FileSystemTierCapacityReport(
            generatedAt = "2026-09-01T00:00:00Z",
            target = CapacityTarget("im.example", 5100),
            config = config,
            expectedSha256 = EXPECTED_SHA256,
            firstDescriptor = descriptor,
            peerDescriptor = descriptor,
            replayedDescriptor = descriptor,
            downloadBeforeRestart = transfer,
            downloadAfterRestartAndReplay = transfer,
            baseline = baseline,
            afterUpload = afterUpload,
            afterRestart = afterRestart,
            afterReplay = afterRestart.copy(phase = "after-replay"),
            restart = FileSystemTierRestartEvidence(
                beforeInvocationId = firstInvocation,
                beforeMainPid = 101L,
                afterInvocationId = secondInvocation,
                afterMainPid = 202L,
            ),
            ownerAuthenticationCountBefore = 1,
            ownerAuthenticationCountAfter = 2,
            peerAuthenticationCountBefore = 1,
            peerAuthenticationCountAfter = 2,
            cleanupAcknowledged = true,
            cleanupObservedAbsent = true,
            passed = true,
        )
    }

    private fun config(
        payloadBytes: Long = FILESYSTEM_TIER_BOUNDARY_BYTES + 64L * 1024L,
    ) = FileSystemTierCapacityConfig(
        runId = "run",
        payloadBytes = payloadBytes,
        requestTimeoutMs = 240_000L,
    )

    private fun snapshot(
        phase: String,
        invocation: String,
        pid: Long,
        files: Long,
        bytes: Long,
        matching: Long,
    ) = FileSystemTierSnapshot(
        phase = phase,
        capturedAt = "2026-09-01T00:00:00Z",
        invocationId = invocation,
        mainPid = pid,
        fileCount = files,
        storedBytes = bytes,
        payloadSizedFileCount = matching,
        availableBytes = 10L * 1024L * 1024L * 1024L,
    )

    private companion object {
        const val EXPECTED_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
