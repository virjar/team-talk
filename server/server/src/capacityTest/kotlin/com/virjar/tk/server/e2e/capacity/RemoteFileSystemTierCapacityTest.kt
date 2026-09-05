package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.server.e2e.RemoteAcceptanceSupport
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.shared.repository.DownloadSink
import com.virjar.tk.shared.repository.FileRepository
import com.virjar.tk.shared.repository.GroupFileRepository
import com.virjar.tk.shared.repository.asUploadSource
import com.virjar.tk.protocol.rpc.gen.ChatRpcProxy
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * One deliberately small >32 MiB release gate. It exercises the product SDK, GroupFile ACL,
 * deployed filesystem tier, and one exact TeamTalk service restart without changing host network
 * state or stopping any service directly.
 */
class RemoteFileSystemTierCapacityTest {
    @Test
    fun `large attachment survives filesystem tier restart and exact identity replay`() = runBlocking {
        val runId = UUID.randomUUID().toString().replace("-", "").take(12)
        val reportFile = File(
            System.getProperty("tk.filesystemTierCapacity.report") ?: DEFAULT_REPORT_PATH,
        )
        val configuredPort = System.getProperty("tk.e2e.port")
        val target = CapacityTarget(
            host = System.getProperty("tk.e2e.host") ?: DEFAULT_TCP_HOST,
            port = configuredPort?.toIntOrNull()
                ?: if (configuredPort == null) DEFAULT_TCP_PORT else INVALID_TCP_PORT,
        )
        var phase = "configuration"
        fun writeRunState(state: String, failure: Throwable? = null) {
            val failureType = failure?.let {
                it::class.qualifiedName ?: it::class.simpleName ?: "Exception"
            }
            CapacityReportWriter.writeAtomically(
                FileSystemTierCapacityRunState(
                    generatedAt = Instant.now().toString(),
                    runId = runId,
                    target = target,
                    state = state,
                    phase = phase,
                    failureType = failureType,
                    failureMessage = failure?.let {
                        (it.message ?: requireNotNull(failureType))
                            .take(MAX_FAILURE_MESSAGE_LENGTH)
                            .ifBlank { requireNotNull(failureType) }
                    },
                ),
                reportFile,
            )
        }

        writeRunState("started")
        val report = try {
            val config = RuntimeConfig.fromSystemProperties(runId)
            runScenario(config, target) { current -> phase = current }
        } catch (failure: Throwable) {
            try {
                writeRunState("failed", failure)
            } catch (reportFailure: Throwable) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        }
        phase = "final-report-write"
        try {
            CapacityReportWriter.writeAtomically(report, reportFile)
        } catch (failure: Throwable) {
            try {
                writeRunState("failed", failure)
            } catch (reportFailure: Throwable) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        }
        println("[FileSystemTierCapacity] report=${reportFile.absolutePath}")
        println(
            "[FileSystemTierCapacity] bytes=${report.config.payloadBytes} " +
                "path=${report.firstDescriptor.path} " +
                "files=${report.baseline.fileCount}->${report.afterUpload.fileCount}" +
                "->${report.afterReplay.fileCount} restart=" +
                "${report.restart.beforeInvocationId}->${report.restart.afterInvocationId}",
        )
        check(report.passed) {
            "Remote filesystem-tier capacity gate failed; inspect the machine-readable report"
        }
    }

    private suspend fun runScenario(
        config: RuntimeConfig,
        target: CapacityTarget,
        enterPhase: (String) -> Unit,
    ): FileSystemTierCapacityReport {
        val tempDirectory = java.nio.file.Files.createTempDirectory(
            "teamtalk-filesystem-tier-${config.report.runId}-",
        ).toFile()
        val payload = File(tempDirectory, "filesystem-tier-${config.report.runId}.bin")
        val sessions = mutableListOf<RemoteAcceptanceSupport.Session>()
        val fileRepositories = mutableListOf<FileRepository>()
        var ownerGroupFiles: GroupFileRepository? = null
        var chatId: String? = null
        var publishedEntry: GroupFileEntry? = null
        var cleanupObservedAbsent = false
        var primaryFailure: Throwable? = null
        try {
            enterPhase("payload")
            val expectedSha256 = withContext(Dispatchers.IO) {
                writeDeterministicPayload(payload, config.report.payloadBytes)
            }

            enterPhase("fixture")
            val owner = RemoteAcceptanceSupport.registerUser(
                suffix = "fs-tier-owner",
                deviceId = "fs-tier-${config.report.runId}-owner",
                deviceName = "Filesystem tier owner",
            ).also(sessions::add)
            val peer = RemoteAcceptanceSupport.registerUser(
                suffix = "fs-tier-peer",
                deviceId = "fs-tier-${config.report.runId}-peer",
                deviceName = "Filesystem tier peer",
            ).also(sessions::add)
            val chat = ChatRpcProxy(owner.rpc).createGroup(
                UUID.randomUUID().toString(),
                "Filesystem tier ${config.report.runId}",
                null,
                listOf(peer.uid),
            )
            chatId = chat.chatId
            val baseUrl = System.getProperty("tk.e2e.server")
                ?: "https://${RemoteAcceptanceSupport.host}"
            val ownerFiles = FileRepository(
                baseUrl,
                owner.uid,
                owner.userSession::httpCredentialsSnapshot,
            ).also(fileRepositories::add)
            val peerFiles = FileRepository(
                baseUrl,
                peer.uid,
                peer.userSession::httpCredentialsSnapshot,
            ).also(fileRepositories::add)
            val ownerGroup = GroupFileRepository(owner.rpc)
            ownerGroupFiles = ownerGroup
            val peerGroupFiles = GroupFileRepository(peer.rpc)
            val sampler = RemoteFileSystemTierSampler()

            enterPhase("filesystem-baseline")
            val baseline = sample(sampler, "baseline", config.report.payloadBytes)
            val identity = AttachmentUploadIdentity(
                uploadId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            )

            enterPhase("large-upload")
            val firstUpload = withTimeout(config.report.requestTimeoutMs) {
                ownerFiles.uploadWithMeta(
                    source = payload.asUploadSource(),
                    fileName = payload.name,
                    contentType = CONTENT_TYPE,
                    identity = identity,
                ).getOrThrow()
            }
            check(firstUpload.file.size == config.report.payloadBytes) {
                "Large upload descriptor length is not exact"
            }

            enterPhase("group-file-publication")
            publishedEntry = withTimeout(config.report.requestTimeoutMs) {
                ownerGroup.createFile(
                    entryId = UUID.randomUUID().toString(),
                    commandId = UUID.randomUUID().toString(),
                    chatId = chat.chatId,
                    parentId = null,
                    name = payload.name,
                    attachment = firstUpload.file,
                ).getOrThrow()
            }
            val peerDescriptor = requireNotNull(withTimeout(config.report.requestTimeoutMs) {
                peerGroupFiles.list(chat.chatId, null).getOrThrow()
                    .single { entry -> entry.entryId == requireNotNull(publishedEntry).entryId }
                    .attachment
            }) { "Peer GroupFile entry is missing its attachment" }
            check(peerDescriptor == firstUpload.file) {
                "Peer GroupFile descriptor differs from the upload result"
            }

            enterPhase("filesystem-after-upload")
            val afterUpload = sample(sampler, "after-upload", config.report.payloadBytes)

            enterPhase("peer-download-before-restart")
            val downloadBeforeRestart = downloadAndHash(peerFiles, peerDescriptor, config)

            val ownerAuthenticationBefore = owner.authenticationCount
            val peerAuthenticationBefore = peer.authenticationCount
            check(owner.imClient.state.value == ConnectionState.AUTHENTICATED)
            check(peer.imClient.state.value == ConnectionState.AUTHENTICATED)

            enterPhase("exact-teamtalk-restart")
            val restart = withContext(Dispatchers.IO) {
                restartTeamTalkExactlyOnceForFileSystemTier()
            }
            withTimeout(config.report.requestTimeoutMs) {
                owner.awaitAuthenticationAfter(ownerAuthenticationBefore, config.report.requestTimeoutMs)
                peer.awaitAuthenticationAfter(peerAuthenticationBefore, config.report.requestTimeoutMs)
            }

            enterPhase("filesystem-after-restart")
            val afterRestart = sample(sampler, "after-restart", config.report.payloadBytes)

            enterPhase("stable-identity-replay")
            val replayed = withTimeout(config.report.requestTimeoutMs) {
                ownerFiles.uploadWithMeta(
                    source = payload.asUploadSource(),
                    fileName = payload.name,
                    contentType = CONTENT_TYPE,
                    identity = identity,
                ).getOrThrow()
            }
            check(replayed == firstUpload) {
                "Restart replay did not return the exact stable upload receipt"
            }

            enterPhase("filesystem-after-replay")
            val afterReplay = sample(sampler, "after-replay", config.report.payloadBytes)

            enterPhase("peer-download-after-restart-and-replay")
            val downloadAfterRestartAndReplay = downloadAndHash(peerFiles, peerDescriptor, config)

            enterPhase("business-reference-cleanup")
            val entry = requireNotNull(publishedEntry)
            withTimeout(config.report.requestTimeoutMs) {
                ownerGroup.delete(
                    UUID.randomUUID().toString(),
                    chat.chatId,
                    entry.entryId,
                    entry.revision,
                ).getOrThrow()
            }
            cleanupObservedAbsent = withTimeout(config.report.requestTimeoutMs) {
                ownerGroup.list(chat.chatId, null).getOrThrow()
                    .none { current -> current.entryId == entry.entryId }
            }
            check(cleanupObservedAbsent) { "Large attachment GroupFile reference remains after cleanup" }

            enterPhase("final-report")
            return FileSystemTierCapacityReport(
                generatedAt = Instant.now().toString(),
                target = target,
                config = config.report,
                expectedSha256 = expectedSha256,
                firstDescriptor = firstUpload.file.toEvidence(),
                peerDescriptor = peerDescriptor.toEvidence(),
                replayedDescriptor = replayed.file.toEvidence(),
                downloadBeforeRestart = downloadBeforeRestart,
                downloadAfterRestartAndReplay = downloadAfterRestartAndReplay,
                baseline = baseline,
                afterUpload = afterUpload,
                afterRestart = afterRestart,
                afterReplay = afterReplay,
                restart = restart,
                ownerAuthenticationCountBefore = ownerAuthenticationBefore,
                ownerAuthenticationCountAfter = owner.authenticationCount,
                peerAuthenticationCountBefore = peerAuthenticationBefore,
                peerAuthenticationCountAfter = peer.authenticationCount,
                cleanupAcknowledged = true,
                cleanupObservedAbsent = cleanupObservedAbsent,
                passed = true,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var lifecycleFailure: Throwable? = null
            withContext(NonCancellable) {
                val groupFiles = ownerGroupFiles
                val currentChatId = chatId
                val entry = publishedEntry
                if (!cleanupObservedAbsent && groupFiles != null && currentChatId != null && entry != null) {
                    try {
                        groupFiles.delete(
                            UUID.randomUUID().toString(),
                            currentChatId,
                            entry.entryId,
                            entry.revision,
                        ).getOrThrow()
                    } catch (failure: Throwable) {
                        lifecycleFailure = lifecycleFailure.append(failure)
                    }
                }
                fileRepositories.asReversed().forEach { repository ->
                    try {
                        repository.close()
                    } catch (failure: Throwable) {
                        lifecycleFailure = lifecycleFailure.append(failure)
                    }
                }
                sessions.asReversed().forEach { session ->
                    try {
                        session.close()
                    } catch (failure: Throwable) {
                        lifecycleFailure = lifecycleFailure.append(failure)
                    }
                }
                try {
                    check(tempDirectory.deleteRecursively() || !tempDirectory.exists()) {
                        "Failed to delete filesystem-tier capacity fixture"
                    }
                } catch (failure: Throwable) {
                    lifecycleFailure = lifecycleFailure.append(failure)
                }
            }
            lifecycleFailure?.let { failure ->
                val primary = primaryFailure
                if (primary == null) throw failure
                if (primary !== failure) primary.addSuppressed(failure)
            }
        }
    }

    private suspend fun sample(
        sampler: RemoteFileSystemTierSampler,
        phase: String,
        payloadBytes: Long,
    ): FileSystemTierSnapshot = withContext(Dispatchers.IO) {
        sampler.sample(phase, Instant.now().toString(), payloadBytes)
    }

    private suspend fun downloadAndHash(
        repository: FileRepository,
        attachment: Attachment,
        config: RuntimeConfig,
    ): FileSystemTierTransferEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        var streamedBytes = 0L
        var progressCallbacks = 0
        var finalProgress: Float? = null
        withTimeout(config.report.requestTimeoutMs) {
            repository.downloadTo(
                attachment,
                DownloadSink { bytes, offset, length ->
                    digest.update(bytes, offset, length)
                    streamedBytes = Math.addExact(streamedBytes, length.toLong())
                },
            ) { progress ->
                progressCallbacks += 1
                finalProgress = progress
            }.getOrThrow()
        }
        return FileSystemTierTransferEvidence(
            streamedBytes = streamedBytes,
            sha256 = digest.digest().toHexString(),
            progressCallbacks = progressCallbacks,
            finalProgress = finalProgress,
            passed = progressCallbacks > 0 && finalProgress == 1f,
        )
    }

    private fun writeDeterministicPayload(file: File, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.outputStream().buffered(PAYLOAD_BUFFER_BYTES).use { output ->
            val buffer = ByteArray(PAYLOAD_BUFFER_BYTES)
            var written = 0L
            while (written < length) {
                val count = minOf(buffer.size.toLong(), length - written).toInt()
                repeat(count) { index ->
                    val position = written + index
                    buffer[index] = ((position * 29L + position / 257L + 0x5aL) and 0xff).toByte()
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                written += count
            }
        }
        check(file.length() == length) { "Generated filesystem-tier payload length is not exact" }
        return digest.digest().toHexString()
    }

    private fun Attachment.toEvidence() = FileSystemTierDescriptorEvidence(
        path = path,
        name = name,
        contentType = contentType,
        size = size,
    )

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        this@toHexString.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    private fun Throwable?.append(failure: Throwable): Throwable {
        val current = this
        if (current == null) return failure
        if (current !== failure) current.addSuppressed(failure)
        return current
    }

    private data class RuntimeConfig(val report: FileSystemTierCapacityConfig) {
        companion object {
            fun fromSystemProperties(runId: String): RuntimeConfig {
                fun long(name: String, default: Long, range: LongRange): Long {
                    val configured = System.getProperty(name)
                    val value = configured?.toLongOrNull()
                        ?: if (configured == null) default else error("$name must be an integer")
                    require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    return value
                }
                return RuntimeConfig(
                    FileSystemTierCapacityConfig(
                        runId = runId,
                        payloadBytes = long(
                            "tk.filesystemTierCapacity.payloadBytes",
                            DEFAULT_PAYLOAD_BYTES,
                            (FILESYSTEM_TIER_BOUNDARY_BYTES + 1)..MAX_PAYLOAD_BYTES,
                        ),
                        requestTimeoutMs = long(
                            "tk.filesystemTierCapacity.request.timeoutMs",
                            DEFAULT_REQUEST_TIMEOUT_MS,
                            30_000L..MAX_REQUEST_TIMEOUT_MS,
                        ),
                    ),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun shutdownSupportScope() {
            RemoteAcceptanceSupport.shutdown()
        }

        private const val CONTENT_TYPE = "application/octet-stream"
        private const val PAYLOAD_BUFFER_BYTES = 64 * 1024
        private const val DEFAULT_PAYLOAD_BYTES = FILESYSTEM_TIER_BOUNDARY_BYTES + 64L * 1024L
        private const val MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 240_000L
        private const val MAX_REQUEST_TIMEOUT_MS = 300_000L
        private const val MAX_FAILURE_MESSAGE_LENGTH = 2_000
        private const val DEFAULT_REPORT_PATH =
            "server/build/reports/capacity/filesystem-tier-capacity.json"
        private const val DEFAULT_TCP_HOST = "im.virjar.com"
        private const val DEFAULT_TCP_PORT = 5_100
        private const val INVALID_TCP_PORT = -1
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
