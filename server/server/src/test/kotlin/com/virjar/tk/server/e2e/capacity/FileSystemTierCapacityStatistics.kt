package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.protocol.body.AttachmentPolicy
import kotlinx.serialization.Serializable

const val FILESYSTEM_TIER_BOUNDARY_BYTES = 32L * 1024L * 1024L

@Serializable
data class FileSystemTierCapacityConfig(
    val runId: String,
    val payloadBytes: Long,
    val requestTimeoutMs: Long,
) {
    init {
        require(runId.isNotBlank()) { "filesystem-tier capacity run id must not be blank" }
        require(payloadBytes in (FILESYSTEM_TIER_BOUNDARY_BYTES + 1)..AttachmentPolicy.MAX_UPLOAD_BYTES) {
            "filesystem-tier capacity payload must be strictly larger than 32 MiB"
        }
        require(requestTimeoutMs > 0L) { "filesystem-tier request timeout must be positive" }
    }
}

/** 已部署 FileStore 文件系统层的只读快照。 */
@Serializable
data class FileSystemTierSnapshot(
    val phase: String,
    val capturedAt: String,
    val invocationId: String,
    val mainPid: Long,
    val fileCount: Long,
    val storedBytes: Long,
    val payloadSizedFileCount: Long,
    val availableBytes: Long,
) {
    init {
        require(phase.isNotBlank() && capturedAt.isNotBlank()) {
            "filesystem-tier snapshot identity must not be blank"
        }
        require(invocationId.matches(Regex("[0-9a-fA-F]{32}")) && mainPid > 0L) {
            "filesystem-tier snapshot process identity is invalid"
        }
        require(fileCount >= 0L && storedBytes >= 0L && payloadSizedFileCount >= 0L) {
            "filesystem-tier snapshot counters must not be negative"
        }
        require(payloadSizedFileCount <= fileCount) {
            "filesystem-tier payload-sized file count exceeds total files"
        }
        require(availableBytes > 0L) { "filesystem-tier available bytes must be positive" }
    }
}

@Serializable
data class FileSystemTierDescriptorEvidence(
    val path: String,
    val name: String,
    val contentType: String,
    val size: Long,
)

@Serializable
data class FileSystemTierTransferEvidence(
    val streamedBytes: Long,
    val sha256: String,
    val progressCallbacks: Int,
    val finalProgress: Float?,
    val passed: Boolean,
) {
    init {
        require(streamedBytes >= 0L) { "streamed byte count must not be negative" }
        require(sha256.matches(SHA256_HEX)) { "streamed SHA-256 must be canonical hexadecimal" }
        require(progressCallbacks >= 0) { "download progress callbacks must not be negative" }
        require(finalProgress == null || finalProgress in 0f..1f) {
            "download final progress is invalid"
        }
        require(passed == (progressCallbacks > 0 && finalProgress == 1f)) {
            "filesystem-tier transfer pass flag is inconsistent"
        }
    }
}

@Serializable
data class FileSystemTierRestartEvidence(
    val beforeInvocationId: String,
    val beforeMainPid: Long,
    val afterInvocationId: String,
    val afterMainPid: Long,
) {
    init {
        require(beforeInvocationId.matches(INVOCATION_ID_HEX))
        require(afterInvocationId.matches(INVOCATION_ID_HEX))
        require(beforeInvocationId != afterInvocationId)
        require(beforeMainPid > 0L && afterMainPid > 0L && beforeMainPid != afterMainPid)
    }
}

/** 在远端运行前写入、并被其最终报告原子替换的持久标记。 */
@Serializable
data class FileSystemTierCapacityRunState(
    val schemaVersion: Int = 1,
    val reportType: String = "filesystem-tier-capacity-run-state",
    val generatedAt: String,
    val runId: String,
    val target: CapacityTarget,
    val state: String,
    val phase: String,
    val failureType: String? = null,
    val failureMessage: String? = null,
) {
    init {
        require(schemaVersion == 1 && reportType == "filesystem-tier-capacity-run-state")
        require(generatedAt.isNotBlank() && runId.isNotBlank() && phase.isNotBlank())
        require(state == "started" || state == "failed")
        require((state == "failed") == (failureType != null))
        require(failureType == null || failureType.isNotBlank())
        require(failureMessage == null || failureMessage.isNotBlank())
    }
}

@Serializable
data class FileSystemTierCapacityReport(
    val schemaVersion: Int = 1,
    val reportType: String = "filesystem-tier-capacity",
    val generatedAt: String,
    val target: CapacityTarget,
    val config: FileSystemTierCapacityConfig,
    val expectedSha256: String,
    val firstDescriptor: FileSystemTierDescriptorEvidence,
    val peerDescriptor: FileSystemTierDescriptorEvidence,
    val replayedDescriptor: FileSystemTierDescriptorEvidence,
    val downloadBeforeRestart: FileSystemTierTransferEvidence,
    val downloadAfterRestartAndReplay: FileSystemTierTransferEvidence,
    val baseline: FileSystemTierSnapshot,
    val afterUpload: FileSystemTierSnapshot,
    val afterRestart: FileSystemTierSnapshot,
    val afterReplay: FileSystemTierSnapshot,
    val restart: FileSystemTierRestartEvidence,
    val ownerAuthenticationCountBefore: Int,
    val ownerAuthenticationCountAfter: Int,
    val peerAuthenticationCountBefore: Int,
    val peerAuthenticationCountAfter: Int,
    val cleanupAcknowledged: Boolean,
    val cleanupObservedAbsent: Boolean,
    val passed: Boolean,
    val note: String = "Single large-object filesystem-tier development gate; global file-count, " +
        "byte, and free-space values are observations, while target-sized deltas are correctness evidence.",
) {
    init {
        require(schemaVersion == 1 && reportType == "filesystem-tier-capacity")
        require(generatedAt.isNotBlank())
        require(expectedSha256.matches(SHA256_HEX))
        require(ownerAuthenticationCountBefore >= 0 && peerAuthenticationCountBefore >= 0)
        require(ownerAuthenticationCountAfter >= ownerAuthenticationCountBefore)
        require(peerAuthenticationCountAfter >= peerAuthenticationCountBefore)
        require(passed == evidencePasses()) {
            "filesystem-tier capacity pass flag is inconsistent with its evidence"
        }
    }

    private fun evidencePasses(): Boolean {
        val descriptorExact = firstDescriptor.path.isNotBlank() &&
            firstDescriptor.name.isNotBlank() &&
            firstDescriptor.contentType.isNotBlank() &&
            firstDescriptor.size == config.payloadBytes &&
            peerDescriptor == firstDescriptor &&
            replayedDescriptor == firstDescriptor
        val downloadsExact = listOf(
            downloadBeforeRestart,
            downloadAfterRestartAndReplay,
        ).all { transfer ->
            transfer.passed &&
                transfer.streamedBytes == config.payloadBytes &&
                transfer.sha256.equals(expectedSha256, ignoreCase = true)
        }
        val landedOnFilesystem = afterUpload.invocationId == baseline.invocationId &&
            afterUpload.mainPid == baseline.mainPid &&
            afterUpload.payloadSizedFileCount == baseline.payloadSizedFileCount + 1L
        val restartExact = restart.beforeInvocationId == afterUpload.invocationId &&
            restart.beforeMainPid == afterUpload.mainPid &&
            restart.afterInvocationId == afterRestart.invocationId &&
            restart.afterMainPid == afterRestart.mainPid &&
            afterRestart.payloadSizedFileCount == afterUpload.payloadSizedFileCount
        val replayStable = afterReplay.invocationId == afterRestart.invocationId &&
            afterReplay.mainPid == afterRestart.mainPid &&
            afterReplay.payloadSizedFileCount == afterRestart.payloadSizedFileCount
        val sessionsRecoveredOnce = ownerAuthenticationCountAfter == ownerAuthenticationCountBefore + 1 &&
            peerAuthenticationCountAfter == peerAuthenticationCountBefore + 1
        return descriptorExact && downloadsExact && landedOnFilesystem && restartExact && replayStable &&
            sessionsRecoveredOnce && cleanupAcknowledged && cleanupObservedAbsent
    }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val INVOCATION_ID_HEX = Regex("[0-9a-fA-F]{32}")
