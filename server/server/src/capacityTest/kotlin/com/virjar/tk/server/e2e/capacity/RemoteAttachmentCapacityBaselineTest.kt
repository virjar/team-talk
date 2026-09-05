package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.server.e2e.RemoteAcceptanceSupport
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.shared.repository.FileRepository
import com.virjar.tk.shared.repository.GroupFileRepository
import com.virjar.tk.shared.repository.MAX_SMALL_DOWNLOAD_BYTES
import com.virjar.tk.shared.repository.asUploadSource
import com.virjar.tk.protocol.rpc.gen.ChatRpcProxy
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * Small-object attachment development baseline through the same SDK paths used by clients.
 *
 * Uploads use a repeatable file-backed [com.virjar.tk.shared.repository.UploadSource], then the same
 * authenticated uploader publishes the returned descriptor into a real group-file space. Every
 * object is subsequently downloaded by the other group member through [FileRepository]. There is
 * deliberately no raw HTTP shortcut and no retry after an ambiguous upload result. This test also
 * never changes host networking.
 */
class RemoteAttachmentCapacityBaselineTest {
    @Test
    fun `small attachments upload publish download and clean up under bounded load`() = runBlocking {
        val runId = UUID.randomUUID().toString().replace("-", "").take(12)
        val reportFile = File(
            System.getProperty("tk.attachmentCapacity.report") ?: DEFAULT_REPORT_PATH,
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
                AttachmentCapacityRunState(
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
            val config = AttachmentRuntimeConfig.fromSystemProperties(runId)
            val sampler = RemoteTeamTalkResourceSampler()
            runBaseline(config, sampler, runId, target) { current -> phase = current }
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
        println("[AttachmentCapacity] report=${reportFile.absolutePath}")
        println(
            "[AttachmentCapacity] uploads=" +
                "${report.warmupUpload.succeeded + report.steadyUpload.succeeded + report.burstUpload.succeeded}/" +
                "${report.config.expectedUploads} downloads=${report.authenticatedDownload.succeeded}/" +
                "${report.authenticatedDownload.attempted} uniquePaths=${report.correctness.uniquePaths}/" +
                "${report.correctness.expectedObjects} retainedForGc=" +
                "${report.cleanup.physicalObjectsRemainingForRetentionGc} " +
                "rss=${report.resources.baselineRssBytes}->${report.resources.maxRssBytes}" +
                "->${report.resources.finalRssBytes}",
        )
        check(report.passed) {
            "Remote attachment capacity baseline failed; inspect the machine-readable report"
        }
    }

    private suspend fun runBaseline(
        config: AttachmentRuntimeConfig,
        sampler: RemoteTeamTalkResourceSampler,
        runId: String,
        target: CapacityTarget,
        enterPhase: (String) -> Unit,
    ): AttachmentCapacityReport {
        val sessions = mutableListOf<RemoteAcceptanceSupport.Session>()
        val fileRepositories = mutableListOf<FileRepository>()
        val resourceSnapshots = mutableListOf<TeamTalkResourceSnapshot>()
        val publishedReferences = PublishedReferenceTracker()
        val tempDirectory = java.nio.file.Files.createTempDirectory(
            "teamtalk-attachment-capacity-$runId-",
        ).toFile()
        var fixture: AttachmentFixture? = null
        var completedObjects: List<UploadedObject>? = null
        var primaryFailure: Throwable? = null
        lateinit var warmup: AttachmentTransferResult
        lateinit var steady: AttachmentTransferResult
        lateinit var burst: AttachmentTransferResult
        lateinit var downloads: AttachmentTransferResult
        lateinit var correctness: AttachmentCorrectnessResult
        lateinit var sessionStability: AttachmentSessionStabilityResult

        try {
            enterPhase("fixture")
            fixture = createFixture(config, runId, sessions, fileRepositories, tempDirectory)
            val activeFixture = requireNotNull(fixture)
            val plans = buildPlans(config, runId)
            val authenticationBefore = activeFixture.sessions.map { session ->
                session.imClient.state.value to session.authenticationCount
            }

            enterPhase("warmup-upload")
            val warmupRun = runSerialUploaderLanes(
                plans = plans.warmup,
                fixture = activeFixture,
                config = config,
                intervalMs = 0L,
                publishedReferences = publishedReferences,
            )
            warmup = buildAttachmentTransferResult(
                AttachmentCapacityScenarioName.WARMUP_UPLOAD,
                warmupRun.objects.map(UploadedObject::attempt),
                warmupRun.elapsedNanos,
            )
            sample(resourceSnapshots, sampler, "baseline")

            enterPhase("steady-upload")
            val steadyRun = withResourceSampling(
                phase = "steady-upload",
                intervalMs = config.resourceSampleIntervalMs,
                sampler = sampler,
                snapshots = resourceSnapshots,
            ) {
                runSerialUploaderLanes(
                    plans = plans.steady,
                    fixture = activeFixture,
                    config = config,
                    intervalMs = config.steadyIntervalMs,
                    publishedReferences = publishedReferences,
                )
            }
            steady = buildAttachmentTransferResult(
                AttachmentCapacityScenarioName.STEADY_UPLOAD,
                steadyRun.objects.map(UploadedObject::attempt),
                steadyRun.elapsedNanos,
            )
            sample(resourceSnapshots, sampler, "steady-upload-complete")

            enterPhase("burst-upload")
            val burstRun = withResourceSampling(
                phase = "burst-upload",
                intervalMs = config.resourceSampleIntervalMs,
                sampler = sampler,
                snapshots = resourceSnapshots,
            ) {
                runConcurrentUploads(
                    plans.burst,
                    activeFixture,
                    config,
                    publishedReferences,
                )
            }
            burst = buildAttachmentTransferResult(
                AttachmentCapacityScenarioName.BURST_UPLOAD,
                burstRun.objects.map(UploadedObject::attempt),
                burstRun.elapsedNanos,
            )
            sample(resourceSnapshots, sampler, "burst-upload-complete")

            val objects = warmupRun.objects + steadyRun.objects + burstRun.objects
            completedObjects = objects
            enterPhase("peer-business-reference-verification")
            attachPeerVisibleBusinessReferences(activeFixture, objects)

            enterPhase("authenticated-peer-download")
            val downloadRun = withResourceSampling(
                phase = "authenticated-download",
                intervalMs = config.resourceSampleIntervalMs,
                sampler = sampler,
                snapshots = resourceSnapshots,
            ) {
                runAuthenticatedDownloads(objects, activeFixture, config)
            }
            downloads = buildAttachmentTransferResult(
                AttachmentCapacityScenarioName.AUTHENTICATED_DOWNLOAD,
                downloadRun.results.map(DownloadResult::attempt),
                downloadRun.elapsedNanos,
            )
            applyDownloadEvidence(objects, downloadRun.results)
            sample(resourceSnapshots, sampler, "authenticated-download-complete")
            correctness = buildAttachmentCorrectnessResult(
                expectedObjects = config.reportConfig.expectedUploads,
                observations = objects.map { uploaded -> uploaded.toObservation() },
            )

            enterPhase("business-reference-cleanup")
            publishedReferences.addCleanupElapsedNanos(
                cleanupPublishedReferences(
                    activeFixture,
                    publishedReferences,
                    config,
                ),
            )
            sample(resourceSnapshots, sampler, "business-reference-cleanup-complete")
            sessionStability = buildAttachmentSessionStabilityResult(
                expectedSessions = config.uploaderCount,
                observations = activeFixture.sessions.mapIndexed { index, session ->
                    AttachmentSessionObservation(
                        laneId = index,
                        authenticatedBefore = authenticationBefore[index].first ==
                            ConnectionState.AUTHENTICATED,
                        authenticatedAfter = session.imClient.state.value ==
                            ConnectionState.AUTHENTICATED,
                        authenticationCountBefore = authenticationBefore[index].second,
                        authenticationCountAfter = session.authenticationCount,
                    )
                },
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var lifecycleFailure: Throwable? = null
            withContext(NonCancellable) {
                val activeFixture = fixture
                if (activeFixture != null && publishedReferences.hasUnverifiedReferences()) {
                    try {
                        publishedReferences.addCleanupElapsedNanos(
                            cleanupPublishedReferences(
                                activeFixture,
                                publishedReferences,
                                config,
                            ),
                        )
                    } catch (failure: Throwable) {
                        lifecycleFailure = lifecycleFailure.appendFailure(failure)
                    }
                    publishedReferences.unresolvedLifecycleFailure()?.let { failure ->
                        lifecycleFailure = lifecycleFailure.appendFailure(failure)
                    }
                }
                fileRepositories.asReversed().forEach { repository ->
                    try {
                        repository.close()
                    } catch (failure: Throwable) {
                        lifecycleFailure = lifecycleFailure.appendFailure(failure)
                    }
                }
                sessions.asReversed().forEach { session ->
                    try {
                        session.close()
                    } catch (failure: Throwable) {
                        lifecycleFailure = lifecycleFailure.appendFailure(failure)
                    }
                }
                try {
                    val deleted = tempDirectory.deleteRecursively()
                    check(deleted || !tempDirectory.exists()) {
                        "Failed to delete attachment capacity fixture ${tempDirectory.absolutePath}"
                    }
                } catch (failure: Throwable) {
                    lifecycleFailure = lifecycleFailure.appendFailure(failure)
                }
            }
            lifecycleFailure?.let { failure ->
                val primary = primaryFailure
                if (primary == null) {
                    throw failure
                } else if (primary !== failure) {
                    primary.addSuppressed(failure)
                }
            }
        }

        enterPhase("cleanup-observation")
        delay(config.cleanupObservationMs)
        sample(resourceSnapshots, sampler, "cleanup")
        val resources = summarizeAttachmentResources(resourceSnapshots)
        val objects = requireNotNull(completedObjects) {
            "Attachment scenarios completed without retaining object evidence"
        }
        val cleanup = buildAttachmentCleanupResult(
            expectedBusinessReferences = config.reportConfig.expectedUploads,
            observations = publishedReferences.toCleanupObservations(objects),
            physicalObjectsRemainingForRetentionGc = objects.count { it.descriptor != null },
            elapsedNanos = publishedReferences.cleanupElapsedNanos(),
        )
        enterPhase("final-report")
        return buildAttachmentCapacityReport(
            generatedAt = Instant.now().toString(),
            target = target,
            config = config.reportConfig,
            warmupUpload = warmup,
            steadyUpload = steady,
            burstUpload = burst,
            authenticatedDownload = downloads,
            correctness = correctness,
            sessions = sessionStability,
            cleanup = cleanup,
            resources = resources,
        )
    }

    private suspend fun createFixture(
        config: AttachmentRuntimeConfig,
        runId: String,
        sessions: MutableList<RemoteAcceptanceSupport.Session>,
        fileRepositories: MutableList<FileRepository>,
        tempDirectory: File,
    ): AttachmentFixture {
        repeat(config.uploaderCount) { index ->
            sessions += RemoteAcceptanceSupport.registerUser(
                suffix = "att-cap-$index",
                deviceId = "att-cap-$runId-$index",
                deviceName = "Attachment capacity $index",
            )
        }
        val chat = ChatRpcProxy(sessions.first().rpc).createGroup(
            UUID.randomUUID().toString(),
            "Attachment capacity $runId",
            null,
            sessions.drop(1).map(RemoteAcceptanceSupport.Session::uid),
        )
        val baseUrl = System.getProperty("tk.e2e.server")
            ?: "https://${RemoteAcceptanceSupport.host}"
        sessions.forEach { session ->
            fileRepositories += FileRepository(
                baseUrl,
                session.uid,
                session.userSession::httpCredentialsSnapshot,
            )
        }
        return AttachmentFixture(
            sessions = sessions.toList(),
            fileRepositories = fileRepositories.toList(),
            groupFiles = sessions.map { session -> GroupFileRepository(session.rpc) },
            chat = chat,
            tempDirectory = tempDirectory,
        )
    }

    private fun buildPlans(config: AttachmentRuntimeConfig, runId: String): AttachmentPlans {
        var ordinal = 0
        fun plan(scenario: String, ownerIndex: Int): UploadPlan {
            val current = ordinal++
            return UploadPlan(
                objectId = "$scenario-$runId-$current",
                ownerIndex = ownerIndex,
                ordinal = current,
                entryId = UUID.randomUUID().toString(),
                commandId = UUID.randomUUID().toString(),
                expectedName = "attachment-$runId-$current.bin",
            )
        }
        fun perUser(scenario: String, count: Int): List<UploadPlan> = buildList {
            repeat(config.uploaderCount) { ownerIndex ->
                repeat(count) { add(plan(scenario, ownerIndex)) }
            }
        }
        return AttachmentPlans(
            warmup = perUser("warmup", config.warmupUploadsPerUser),
            steady = perUser("steady", config.steadyUploadsPerUser),
            burst = List(config.burstUploadsTotal) { index ->
                plan("burst", index % config.uploaderCount)
            },
        )
    }

    private suspend fun runSerialUploaderLanes(
        plans: List<UploadPlan>,
        fixture: AttachmentFixture,
        config: AttachmentRuntimeConfig,
        intervalMs: Long,
        publishedReferences: PublishedReferenceTracker,
    ): UploadScenarioRun = coroutineScope {
        val started = System.nanoTime()
        val lanes = plans.groupBy(UploadPlan::ownerIndex).toSortedMap().map { (_, lanePlans) ->
            async {
                buildList {
                    lanePlans.forEachIndexed { index, plan ->
                        add(
                            runPreparedUpload(
                                prepareUpload(plan, fixture, config),
                                fixture,
                                config,
                                publishedReferences,
                            ),
                        )
                        if (index + 1 < lanePlans.size && intervalMs > 0L) delay(intervalMs)
                    }
                }
            }
        }.awaitAll()
        UploadScenarioRun(lanes.flatten(), System.nanoTime() - started)
    }

    private suspend fun runConcurrentUploads(
        plans: List<UploadPlan>,
        fixture: AttachmentFixture,
        config: AttachmentRuntimeConfig,
        publishedReferences: PublishedReferenceTracker,
    ): UploadScenarioRun = coroutineScope {
        // Payload generation is fixture setup, not upload admission. Pre-generating it keeps the
        // configured permits around the actual HTTP upload only. The value remains a client-side
        // concurrency ceiling; this test does not claim a server-observed peak of the same size.
        val preparedUploads = plans.map { plan -> prepareUpload(plan, fixture, config) }
        val started = System.nanoTime()
        val global = Semaphore(config.burstConcurrency)
        val perUploader = List(config.uploaderCount) { Semaphore(MAX_UPLOADS_PER_USER) }
        val objects = preparedUploads.map { prepared ->
            async {
                runPreparedUpload(
                    prepared,
                    fixture,
                    config,
                    publishedReferences,
                ) { upload ->
                    perUploader[prepared.plan.ownerIndex].withPermit {
                        global.withPermit {
                            upload()
                        }
                    }
                }
            }
        }.awaitAll()
        UploadScenarioRun(objects, System.nanoTime() - started)
    }

    private fun prepareUpload(
        plan: UploadPlan,
        fixture: AttachmentFixture,
        config: AttachmentRuntimeConfig,
    ): PreparedUpload {
        val localFile = File(fixture.tempDirectory, plan.expectedName)
        val expectedSha256 = writeDeterministicPayload(
            localFile,
            config.payloadBytes,
            plan.ordinal + 1,
        )
        return PreparedUpload(plan, localFile, expectedSha256)
    }

    private suspend fun runPreparedUpload(
        prepared: PreparedUpload,
        fixture: AttachmentFixture,
        config: AttachmentRuntimeConfig,
        publishedReferences: PublishedReferenceTracker,
        uploadGate: suspend (suspend () -> Attachment) -> Attachment = { upload -> upload() },
    ): UploadedObject {
        val plan = prepared.plan
        var descriptor: Attachment? = null
        var entry: GroupFileEntry? = null
        var failureCategory: String? = null
        val started = System.nanoTime()
        try {
            withTimeout(config.requestTimeoutMs) {
                descriptor = uploadGate {
                    fixture.fileRepositories[plan.ownerIndex]
                        .upload(
                            prepared.localFile.asUploadSource(),
                            plan.expectedName,
                            CONTENT_TYPE,
                        )
                        .getOrThrow()
                }
                // The command carries a client-generated entry id. Register it before the RPC so
                // an ACK lost after a server commit can be recovered from the authoritative list;
                // create itself is deliberately never retried by this capacity baseline.
                publishedReferences.candidate(plan)
                val published = fixture.groupFiles[plan.ownerIndex].createFile(
                    entryId = plan.entryId,
                    commandId = plan.commandId,
                    chatId = fixture.chat.chatId,
                    parentId = null,
                    name = plan.expectedName,
                    attachment = requireNotNull(descriptor),
                ).getOrThrow()
                entry = published
                publishedReferences.published(plan, published)
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                failureCategory = AttachmentCapacityFailureCategory.TIMEOUT
            } else {
                throw cancelled
            }
        } catch (failure: Exception) {
            failureCategory = classifyFailure(failure)
        }
        val latency = System.nanoTime() - started
        return UploadedObject(
            plan = plan,
            expectedSha256 = prepared.expectedSha256,
            descriptor = descriptor,
            publishedEntry = entry,
            attempt = AttachmentTransferAttempt(
                objectId = plan.objectId,
                requestedBytes = config.payloadBytes,
                transferredBytes = if (descriptor == null) 0L else config.payloadBytes,
                latencyNanos = latency,
                failureCategory = failureCategory,
            ),
        )
    }

    private suspend fun attachPeerVisibleBusinessReferences(
        fixture: AttachmentFixture,
        objects: List<UploadedObject>,
    ) {
        val peerListings = fixture.groupFiles.map { repository ->
            repository.list(fixture.chat.chatId, null).getOrNull()
                ?.associateBy(GroupFileEntry::entryId)
                .orEmpty()
        }
        objects.forEach { uploaded ->
            val peerIndex = (uploaded.plan.ownerIndex + 1) % fixture.sessions.size
            uploaded.peerVisibleAttachment = peerListings[peerIndex]
                .get(uploaded.plan.entryId)
                ?.attachment
        }
    }

    private suspend fun runAuthenticatedDownloads(
        objects: List<UploadedObject>,
        fixture: AttachmentFixture,
        config: AttachmentRuntimeConfig,
    ): DownloadScenarioRun = coroutineScope {
        val plans = objects.flatMap { uploaded ->
            List(config.downloadsPerAttachment) { DownloadPlan(uploaded) }
        }
        val semaphore = Semaphore(config.downloadConcurrency)
        val started = System.nanoTime()
        val results = plans.map { plan ->
            async {
                semaphore.withPermit { runDownload(plan.uploaded, fixture, config) }
            }
        }.awaitAll()
        DownloadScenarioRun(results, System.nanoTime() - started)
    }

    private suspend fun runDownload(
        uploaded: UploadedObject,
        fixture: AttachmentFixture,
        config: AttachmentRuntimeConfig,
    ): DownloadResult {
        val descriptor = uploaded.descriptor
        if (descriptor == null) {
            return DownloadResult(
                objectId = uploaded.plan.objectId,
                attempt = AttachmentTransferAttempt(
                    objectId = uploaded.plan.objectId,
                    requestedBytes = config.payloadBytes,
                    transferredBytes = 0L,
                    latencyNanos = 0L,
                    failureCategory = AttachmentCapacityFailureCategory.DEPENDENCY_UPLOAD_FAILED,
                ),
            )
        }
        val peerIndex = (uploaded.plan.ownerIndex + 1) % fixture.sessions.size
        val started = System.nanoTime()
        var bytes: ByteArray? = null
        var failureCategory: String? = null
        try {
            bytes = withTimeout(config.requestTimeoutMs) {
                fixture.fileRepositories[peerIndex].downloadSmall(descriptor).getOrThrow()
            }
            val actual = requireNotNull(bytes)
            if (
                actual.size.toLong() != config.payloadBytes ||
                sha256(actual) != uploaded.expectedSha256
            ) {
                failureCategory = CONTENT_MISMATCH_FAILURE
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                failureCategory = AttachmentCapacityFailureCategory.TIMEOUT
            } else {
                throw cancelled
            }
        } catch (failure: Exception) {
            failureCategory = classifyFailure(failure)
        }
        val actual = bytes
        return DownloadResult(
            objectId = uploaded.plan.objectId,
            attempt = AttachmentTransferAttempt(
                objectId = uploaded.plan.objectId,
                requestedBytes = config.payloadBytes,
                transferredBytes = minOf(actual?.size?.toLong() ?: 0L, config.payloadBytes),
                latencyNanos = System.nanoTime() - started,
                failureCategory = failureCategory,
            ),
            downloadedLength = actual?.size?.toLong(),
            downloadedSha256 = actual?.let(::sha256),
        )
    }

    private fun applyDownloadEvidence(
        objects: List<UploadedObject>,
        results: List<DownloadResult>,
    ) {
        val byObject = results.groupBy(DownloadResult::objectId)
        objects.forEach { uploaded ->
            val evidence = byObject[uploaded.plan.objectId]
                .orEmpty()
                .firstOrNull { result -> result.downloadedLength != null }
            uploaded.downloadedLength = evidence?.downloadedLength
            uploaded.downloadedSha256 = evidence?.downloadedSha256
        }
    }

    private suspend fun cleanupPublishedReferences(
        fixture: AttachmentFixture,
        publishedReferences: PublishedReferenceTracker,
        config: AttachmentRuntimeConfig,
    ): Long {
        val started = System.nanoTime()
        verifyPublishedReferences(fixture, publishedReferences, config)
        publishedReferences.unverifiedReferences().asReversed().forEach { reference ->
            val revision = reference.revision
            if (revision == null) {
                publishedReferences.revisionUnavailable(reference)
                return@forEach
            }
            try {
                withTimeout(config.requestTimeoutMs) {
                    fixture.groupFiles[reference.ownerIndex]
                        .delete(
                            UUID.randomUUID().toString(),
                            fixture.chat.chatId,
                            reference.entryId,
                            revision,
                        )
                        .getOrThrow()
                }
                publishedReferences.deleteAcknowledged(reference)
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) {
                    publishedReferences.deleteFailed(
                        reference,
                        AttachmentCapacityFailureCategory.TIMEOUT,
                        cancelled,
                    )
                } else {
                    throw cancelled
                }
            } catch (failure: Exception) {
                publishedReferences.deleteFailed(
                    reference,
                    classifyFailure(failure),
                    failure,
                )
            }
        }
        verifyPublishedReferences(fixture, publishedReferences, config)
        return System.nanoTime() - started
    }

    private suspend fun verifyPublishedReferences(
        fixture: AttachmentFixture,
        publishedReferences: PublishedReferenceTracker,
        config: AttachmentRuntimeConfig,
    ) {
        val candidates = publishedReferences.unverifiedReferences()
        if (candidates.isEmpty()) return
        try {
            val remainingEntries = withTimeout(config.requestTimeoutMs) {
                fixture.groupFiles.first().list(fixture.chat.chatId, null).getOrThrow()
            }.associateBy(GroupFileEntry::entryId)
            publishedReferences.observedEntries(candidates, remainingEntries)
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                publishedReferences.verificationFailed(
                    candidates,
                    AttachmentCapacityFailureCategory.TIMEOUT,
                    cancelled,
                )
            } else {
                throw cancelled
            }
        } catch (failure: Exception) {
            publishedReferences.verificationFailed(
                candidates,
                CLEANUP_VERIFICATION_FAILURE,
                failure,
            )
        }
    }

    private suspend fun <T> withResourceSampling(
        phase: String,
        intervalMs: Long,
        sampler: RemoteTeamTalkResourceSampler,
        snapshots: MutableList<TeamTalkResourceSnapshot>,
        block: suspend () -> T,
    ): T = coroutineScope {
        val sampling = launch(Dispatchers.IO) {
            var index = 0
            while (isActive) {
                delay(intervalMs)
                val snapshot = sampler.sample("$phase-$index", Instant.now().toString())
                synchronized(snapshots) { snapshots += snapshot }
                index += 1
            }
        }
        try {
            block()
        } finally {
            sampling.cancelAndJoin()
        }
    }

    private fun sample(
        snapshots: MutableList<TeamTalkResourceSnapshot>,
        sampler: RemoteTeamTalkResourceSampler,
        phase: String,
    ) {
        val snapshot = sampler.sample(phase, Instant.now().toString())
        synchronized(snapshots) { snapshots += snapshot }
    }

    private fun writeDeterministicPayload(file: File, length: Long, seed: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.outputStream().buffered(PAYLOAD_BUFFER_BYTES).use { output ->
            val buffer = ByteArray(PAYLOAD_BUFFER_BYTES)
            var written = 0L
            while (written < length) {
                val count = minOf(buffer.size.toLong(), length - written).toInt()
                repeat(count) { index ->
                    val position = written + index
                    buffer[index] = ((seed * 37L + position * 17L + position / 251L) and 0xff)
                        .toByte()
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                written += count
            }
        }
        return digest.digest().toHexString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        this@toHexString.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    private fun Attachment.toEvidence(): AttachmentDescriptorEvidence =
        AttachmentDescriptorEvidence(path, name, contentType, size)

    private fun UploadedObject.toObservation(): AttachmentObjectObservation =
        AttachmentObjectObservation(
            objectId = plan.objectId,
            expectedName = plan.expectedName,
            expectedContentType = CONTENT_TYPE,
            expectedLength = attempt.requestedBytes,
            expectedSha256 = expectedSha256,
            descriptor = descriptor?.toEvidence(),
            groupFileCurrentAttachment = peerVisibleAttachment?.toEvidence(),
            downloadedLength = downloadedLength,
            downloadedSha256 = downloadedSha256,
        )

    private fun classifyFailure(failure: Throwable): String = when (failure) {
        is TimeoutCancellationException, AppError.Timeout -> AttachmentCapacityFailureCategory.TIMEOUT
        is TransportUnavailableException, is IOException, AppError.Network ->
            AttachmentCapacityFailureCategory.TRANSPORT
        is AppError.FatalCodec -> AttachmentCapacityFailureCategory.DECODE
        AppError.AuthExpired -> AttachmentCapacityFailureCategory.httpStatus(401)
        is AppError.Business -> if (failure.code in 100..599 && failure.code !in 200..299) {
            AttachmentCapacityFailureCategory.httpStatus(failure.code)
        } else {
            "business_${failure.code}"
        }
        is AppError.Unknown -> when (failure.cause) {
            is IOException, is TransportUnavailableException -> AttachmentCapacityFailureCategory.TRANSPORT
            else -> AttachmentCapacityFailureCategory.UNEXPECTED
        }
        else -> AttachmentCapacityFailureCategory.UNEXPECTED
    }

    private fun Throwable?.appendFailure(failure: Throwable): Throwable {
        val current = this
        if (current == null) return failure
        if (current !== failure) current.addSuppressed(failure)
        return current
    }

    private data class AttachmentFixture(
        val sessions: List<RemoteAcceptanceSupport.Session>,
        val fileRepositories: List<FileRepository>,
        val groupFiles: List<GroupFileRepository>,
        val chat: Chat,
        val tempDirectory: File,
    )

    private data class UploadPlan(
        val objectId: String,
        val ownerIndex: Int,
        val ordinal: Int,
        val entryId: String,
        val commandId: String,
        val expectedName: String,
    )

    private data class AttachmentPlans(
        val warmup: List<UploadPlan>,
        val steady: List<UploadPlan>,
        val burst: List<UploadPlan>,
    )

    private data class PreparedUpload(
        val plan: UploadPlan,
        val localFile: File,
        val expectedSha256: String,
    )

    private data class UploadScenarioRun(
        val objects: List<UploadedObject>,
        val elapsedNanos: Long,
    )

    private data class UploadedObject(
        val plan: UploadPlan,
        val expectedSha256: String,
        val descriptor: Attachment?,
        val publishedEntry: GroupFileEntry?,
        val attempt: AttachmentTransferAttempt,
        var peerVisibleAttachment: Attachment? = null,
        var downloadedLength: Long? = null,
        var downloadedSha256: String? = null,
    )

    private data class DownloadPlan(val uploaded: UploadedObject)

    private data class DownloadResult(
        val objectId: String,
        val attempt: AttachmentTransferAttempt,
        val downloadedLength: Long? = null,
        val downloadedSha256: String? = null,
    )

    private data class DownloadScenarioRun(
        val results: List<DownloadResult>,
        val elapsedNanos: Long,
    )

    private data class PublishedReference(
        val objectId: String,
        val entryId: String,
        val ownerIndex: Int,
        val revision: Long?,
    )

    private class PublishedReferenceTracker {
        private val lock = Any()
        private val references = linkedMapOf<String, PublishedReferenceState>()
        private var elapsedNanos = 0L

        fun candidate(plan: UploadPlan) {
            synchronized(lock) {
                references.putIfAbsent(
                    plan.objectId,
                    PublishedReferenceState(
                        reference = PublishedReference(
                            objectId = plan.objectId,
                            entryId = plan.entryId,
                            ownerIndex = plan.ownerIndex,
                            revision = null,
                        ),
                    ),
                )
            }
        }

        fun published(plan: UploadPlan, entry: GroupFileEntry) = synchronized(lock) {
            val state = references.getOrPut(plan.objectId) {
                PublishedReferenceState(
                    reference = PublishedReference(
                        objectId = plan.objectId,
                        entryId = plan.entryId,
                        ownerIndex = plan.ownerIndex,
                        revision = null,
                    ),
                )
            }
            state.reference = PublishedReference(
                objectId = plan.objectId,
                entryId = entry.entryId,
                ownerIndex = plan.ownerIndex,
                revision = entry.revision,
            )
        }

        fun hasUnverifiedReferences(): Boolean = synchronized(lock) {
            references.values.any { state -> !state.absentAfterCleanup }
        }

        fun unverifiedReferences(): List<PublishedReference> = synchronized(lock) {
            references.values
                .filterNot(PublishedReferenceState::absentAfterCleanup)
                .map(PublishedReferenceState::reference)
        }

        fun deleteAcknowledged(reference: PublishedReference) = synchronized(lock) {
            val state = state(reference)
            state.deleteAcknowledged = true
            state.failureCategory = null
            state.failureCauses.clear()
        }

        fun deleteFailed(
            reference: PublishedReference,
            category: String,
            failure: Throwable,
        ) = synchronized(lock) {
            val state = state(reference)
            if (!state.absentAfterCleanup) {
                state.failureCategory = category
                state.failureCauses += failure
            }
        }

        fun revisionUnavailable(reference: PublishedReference) = synchronized(lock) {
            val state = state(reference)
            if (!state.absentAfterCleanup && state.reference.revision == null) {
                state.failureCategory = CLEANUP_VERIFICATION_FAILURE
                state.failureCauses += IllegalStateException(
                    "GroupFile entry ${reference.entryId} revision was unavailable for cleanup",
                )
            }
        }

        fun observedEntries(
            candidates: List<PublishedReference>,
            remainingEntries: Map<String, GroupFileEntry>,
        ) = synchronized(lock) {
            candidates.forEach { reference ->
                val state = state(reference)
                val current = remainingEntries[reference.entryId]
                state.absentAfterCleanup = current == null
                if (current != null) {
                    state.reference = reference.copy(revision = current.revision)
                }
                if (state.absentAfterCleanup && state.deleteAcknowledged) {
                    state.failureCategory = null
                    state.failureCauses.clear()
                } else if (!state.absentAfterCleanup && state.failureCategory == null) {
                    state.failureCategory = BUSINESS_REFERENCE_REMAINING_FAILURE
                }
            }
        }

        fun verificationFailed(
            candidates: List<PublishedReference>,
            category: String,
            failure: Throwable,
        ) = synchronized(lock) {
            candidates.forEach { reference ->
                val state = state(reference)
                if (!state.absentAfterCleanup) {
                    if (
                        state.failureCategory == null ||
                        state.failureCategory == BUSINESS_REFERENCE_REMAINING_FAILURE
                    ) {
                        state.failureCategory = category
                    }
                    state.failureCauses += failure
                }
            }
        }

        fun addCleanupElapsedNanos(elapsed: Long) = synchronized(lock) {
            require(elapsed >= 0L) { "Attachment cleanup elapsed time must not be negative" }
            elapsedNanos = Math.addExact(elapsedNanos, elapsed)
        }

        fun cleanupElapsedNanos(): Long = synchronized(lock) { elapsedNanos }

        fun unresolvedLifecycleFailure(): Throwable? = synchronized(lock) {
            val remaining = references.values.filterNot(PublishedReferenceState::absentAfterCleanup)
            if (remaining.isEmpty()) return@synchronized null
            IllegalStateException(
                "Attachment cleanup left ${remaining.size} published GroupFile reference(s): " +
                    remaining.joinToString { state -> state.reference.entryId },
            ).also { aggregate ->
                remaining.flatMap(PublishedReferenceState::failureCauses)
                    .distinct()
                    .forEach(aggregate::addSuppressed)
            }
        }

        fun toCleanupObservations(
            objects: List<UploadedObject>,
        ): List<AttachmentCleanupObservation> = synchronized(lock) {
            objects.map { uploaded ->
                val state = references[uploaded.plan.objectId]
                check(uploaded.publishedEntry == null || state != null) {
                    "Published attachment ${uploaded.plan.objectId} was not tracked for cleanup"
                }
                if (state == null) {
                    AttachmentCleanupObservation(
                        objectId = uploaded.plan.objectId,
                        entryId = uploaded.plan.entryId,
                        deleteAcknowledged = false,
                        absentAfterCleanup = false,
                        failureCategory = AttachmentCapacityFailureCategory.DEPENDENCY_UPLOAD_FAILED,
                    )
                } else {
                    AttachmentCleanupObservation(
                        objectId = state.reference.objectId,
                        entryId = state.reference.entryId,
                        deleteAcknowledged = state.deleteAcknowledged,
                        absentAfterCleanup = state.absentAfterCleanup,
                        failureCategory = if (
                            state.deleteAcknowledged && state.absentAfterCleanup
                        ) {
                            null
                        } else {
                            state.failureCategory
                        },
                    )
                }
            }
        }

        private fun state(reference: PublishedReference): PublishedReferenceState =
            checkNotNull(references[reference.objectId]) {
                "Published attachment ${reference.objectId} disappeared from cleanup tracking"
            }.also { state ->
                check(state.reference == reference) {
                    "Published attachment ${reference.objectId} cleanup identity changed"
                }
            }

        private data class PublishedReferenceState(
            var reference: PublishedReference,
            var deleteAcknowledged: Boolean = false,
            var absentAfterCleanup: Boolean = false,
            var failureCategory: String? = null,
            val failureCauses: MutableList<Throwable> = mutableListOf(),
        )
    }

    private data class AttachmentRuntimeConfig(
        val uploaderCount: Int,
        val payloadBytes: Long,
        val warmupUploadsPerUser: Int,
        val steadyUploadsPerUser: Int,
        val steadyIntervalMs: Long,
        val burstUploadsTotal: Int,
        val burstConcurrency: Int,
        val downloadsPerAttachment: Int,
        val downloadConcurrency: Int,
        val requestTimeoutMs: Long,
        val resourceSampleIntervalMs: Long,
        val cleanupObservationMs: Long,
        val reportConfig: AttachmentCapacityConfig,
    ) {
        companion object {
            fun fromSystemProperties(runId: String): AttachmentRuntimeConfig {
                fun int(name: String, default: Int, range: IntRange): Int {
                    val configured = System.getProperty(name)
                    val value = when {
                        configured == null -> default
                        else -> configured.toIntOrNull()
                            ?: error("$name must be an integer, was: $configured")
                    }
                    require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    return value
                }

                fun long(name: String, default: Long, range: LongRange): Long {
                    val configured = System.getProperty(name)
                    val value = when {
                        configured == null -> default
                        else -> configured.toLongOrNull()
                            ?: error("$name must be an integer, was: $configured")
                    }
                    require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    return value
                }

                val users = int("tk.attachmentCapacity.users", 2, 2..2)
                val payloadBytes = long(
                    "tk.attachmentCapacity.payloadBytes",
                    DEFAULT_PAYLOAD_BYTES,
                    1L..MAX_SMALL_DOWNLOAD_BYTES,
                )
                val warmupPerUser = int(
                    "tk.attachmentCapacity.warmup.uploadsPerUser",
                    2,
                    1..8,
                )
                val steadyPerUser = int(
                    "tk.attachmentCapacity.steady.uploadsPerUser",
                    8,
                    1..32,
                )
                val burstTotal = int(
                    "tk.attachmentCapacity.burst.uploadsTotal",
                    16,
                    1..64,
                )
                val burstConcurrency = int(
                    "tk.attachmentCapacity.burst.concurrency",
                    4,
                    1..MAX_GLOBAL_UPLOADS,
                )
                require(burstConcurrency <= burstTotal) {
                    "Attachment burst concurrency must not exceed its upload count"
                }
                val downloadsPerAttachment = int(
                    "tk.attachmentCapacity.downloadsPerAttachment",
                    1,
                    1..4,
                )
                val downloadConcurrency = int(
                    "tk.attachmentCapacity.download.concurrency",
                    4,
                    1..MAX_DOWNLOAD_CONCURRENCY,
                )
                val report = AttachmentCapacityConfig(
                    runId = runId,
                    uploaderCount = users,
                    payloadBytes = payloadBytes,
                    warmupUploadsPerUser = warmupPerUser,
                    steadyUploadsPerUser = steadyPerUser,
                    steadyIntervalMs = long(
                        "tk.attachmentCapacity.steady.intervalMs",
                        280L,
                        0L..2_000L,
                    ),
                    burstUploadsTotal = burstTotal,
                    burstConcurrency = burstConcurrency,
                    downloadsPerAttachment = downloadsPerAttachment,
                    downloadConcurrency = downloadConcurrency,
                    requestTimeoutMs = long(
                        "tk.attachmentCapacity.request.timeoutMs",
                        120_000L,
                        5_000L..180_000L,
                    ),
                    resourceSampleIntervalMs = long(
                        "tk.attachmentCapacity.sample.intervalMs",
                        2_000L,
                        500L..30_000L,
                    ),
                    cleanupObservationMs = long(
                        "tk.attachmentCapacity.cleanup.observationMs",
                        30_000L,
                        0L..120_000L,
                    ),
                )
                require(report.expectedUploads <= MAX_OBJECTS) {
                    "Attachment capacity fixture exceeds $MAX_OBJECTS objects"
                }
                val attempts = Math.addExact(
                    report.expectedUploads.toLong(),
                    report.expectedAuthenticatedDownloads.toLong(),
                )
                val totalTransferBytes = Math.multiplyExact(attempts, payloadBytes)
                require(totalTransferBytes <= MAX_TOTAL_TRANSFER_BYTES) {
                    "Attachment capacity transfer exceeds $MAX_TOTAL_TRANSFER_BYTES bytes"
                }
                return AttachmentRuntimeConfig(
                    uploaderCount = users,
                    payloadBytes = payloadBytes,
                    warmupUploadsPerUser = warmupPerUser,
                    steadyUploadsPerUser = steadyPerUser,
                    steadyIntervalMs = report.steadyIntervalMs,
                    burstUploadsTotal = burstTotal,
                    burstConcurrency = burstConcurrency,
                    downloadsPerAttachment = downloadsPerAttachment,
                    downloadConcurrency = downloadConcurrency,
                    requestTimeoutMs = report.requestTimeoutMs,
                    resourceSampleIntervalMs = report.resourceSampleIntervalMs,
                    cleanupObservationMs = report.cleanupObservationMs,
                    reportConfig = report,
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
        private const val CONTENT_MISMATCH_FAILURE = "content_mismatch"
        private const val CLEANUP_VERIFICATION_FAILURE = "cleanup_verification_failed"
        private const val BUSINESS_REFERENCE_REMAINING_FAILURE = "business_reference_remaining"
        private const val MAX_UPLOADS_PER_USER = 2
        private const val MAX_GLOBAL_UPLOADS = 4
        private const val MAX_DOWNLOAD_CONCURRENCY = 8
        private const val MAX_OBJECTS = 64
        private const val PAYLOAD_BUFFER_BYTES = 64 * 1024
        private const val DEFAULT_PAYLOAD_BYTES = 512L * 1024L
        private const val MAX_TOTAL_TRANSFER_BYTES = 128L * 1024L * 1024L
        private const val MAX_FAILURE_MESSAGE_LENGTH = 2_000
        private const val DEFAULT_REPORT_PATH =
            "server/build/reports/capacity/attachment-capacity.json"
        private const val DEFAULT_TCP_HOST = "im.virjar.com"
        private const val DEFAULT_TCP_PORT = 5_100
        private const val INVALID_TCP_PORT = -1
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
