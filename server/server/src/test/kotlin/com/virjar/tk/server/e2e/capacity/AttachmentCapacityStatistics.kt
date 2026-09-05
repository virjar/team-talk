package com.virjar.tk.server.e2e.capacity

import kotlinx.serialization.Serializable

/**
 * 复制进机器可读报告的有界附件负载形态。
 * 延迟与资源量级是观测值；本配置不定义 SLO。
 */
@Serializable
data class AttachmentCapacityConfig(
    val runId: String,
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
) {
    init {
        require(runId.isNotBlank()) { "attachment capacity run id must not be blank" }
        require(uploaderCount > 1) {
            "attachment capacity requires at least two users for cross-account downloads"
        }
        require(payloadBytes > 0L) { "attachment capacity payload must be positive" }
        require(warmupUploadsPerUser > 0) {
            "attachment capacity warmup uploads per user must be positive"
        }
        require(steadyUploadsPerUser > 0) {
            "attachment capacity steady uploads per user must be positive"
        }
        require(steadyIntervalMs >= 0L) {
            "attachment capacity steady interval must not be negative"
        }
        require(burstUploadsTotal > 0) {
            "attachment capacity burst upload count must be positive"
        }
        require(burstConcurrency in 1..burstUploadsTotal) {
            "attachment capacity burst concurrency must fit the burst"
        }
        require(downloadsPerAttachment > 0) {
            "attachment capacity downloads per attachment must be positive"
        }
        require(downloadConcurrency > 0) {
            "attachment capacity download concurrency must be positive"
        }
        require(requestTimeoutMs > 0L) {
            "attachment capacity request timeout must be positive"
        }
        require(resourceSampleIntervalMs > 0L) {
            "attachment capacity resource sample interval must be positive"
        }
        require(cleanupObservationMs >= 0L) {
            "attachment capacity cleanup observation must not be negative"
        }
        require(downloadConcurrency <= expectedAuthenticatedDownloads) {
            "attachment capacity download concurrency exceeds its attempt count"
        }
    }

    val expectedWarmupUploads: Int
        get() = Math.multiplyExact(uploaderCount, warmupUploadsPerUser)

    val expectedSteadyUploads: Int
        get() = Math.multiplyExact(uploaderCount, steadyUploadsPerUser)

    val expectedUploads: Int
        get() = Math.addExact(
            Math.addExact(expectedWarmupUploads, expectedSteadyUploads),
            burstUploadsTotal,
        )

    val expectedAuthenticatedDownloads: Int
        get() = Math.multiplyExact(expectedUploads, downloadsPerAttachment)
}

object AttachmentCapacityScenarioName {
    const val WARMUP_UPLOAD = "warmup-upload"
    const val STEADY_UPLOAD = "steady-upload"
    const val BURST_UPLOAD = "burst-upload"
    const val AUTHENTICATED_DOWNLOAD = "authenticated-download"
}

object AttachmentCapacityFailureCategory {
    const val TIMEOUT = "timeout"
    const val TRANSPORT = "transport"
    const val DECODE = "decode"
    const val UNEXPECTED = "unexpected"
    const val DEPENDENCY_UPLOAD_FAILED = "dependency_upload_failed"
    const val DELETE_NOT_ACKNOWLEDGED = "delete_not_acknowledged"
    const val BUSINESS_REFERENCE_PRESENT = "business_reference_present"
    const val DELETE_NOT_ACKNOWLEDGED_AND_REFERENCE_PRESENT =
        "delete_not_acknowledged_and_reference_present"

    fun httpStatus(status: Int): String {
        require(status !in 200..299) { "successful HTTP status is not a failure category" }
        return "http_status_$status"
    }
}

/** 一次已完成的 HTTP 传输尝试，包括有界失败。 */
data class AttachmentTransferAttempt(
    val objectId: String,
    val requestedBytes: Long,
    val transferredBytes: Long,
    val latencyNanos: Long,
    val failureCategory: String? = null,
) {
    init {
        require(objectId.isNotBlank()) { "attachment transfer object id must not be blank" }
        require(requestedBytes > 0L) { "attachment transfer request bytes must be positive" }
        require(transferredBytes in 0L..requestedBytes) {
            "attachment transfer bytes must be within its requested size"
        }
        require(latencyNanos >= 0L) { "attachment transfer latency must not be negative" }
        require(failureCategory == null || failureCategory.isNotBlank()) {
            "attachment transfer failure category must not be blank"
        }
        require(failureCategory != null || transferredBytes == requestedBytes) {
            "successful attachment transfer must complete every requested byte"
        }
    }
}

@Serializable
data class AttachmentTransferResult(
    val name: String,
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val requestedBytes: Long,
    val transferredBytes: Long,
    val successfulBytes: Long,
    val attemptsByObject: Map<String, Int>,
    val failuresByCategory: Map<String, Int>,
    val elapsedMs: Double,
    val attemptsPerSecond: Double,
    val successfulBytesPerSecond: Double,
    /** 包括所有已完成的尝试，包括有界错误响应。 */
    val latency: CapacityLatencySummary,
    val passed: Boolean,
) {
    init {
        require(name.isNotBlank()) { "attachment transfer result name must not be blank" }
        require(attempted >= 0 && succeeded >= 0 && failed >= 0) {
            "attachment transfer counts must not be negative"
        }
        require(succeeded + failed == attempted) {
            "attachment transfer successes and failures must partition attempts"
        }
        require(requestedBytes >= 0L && transferredBytes >= 0L && successfulBytes >= 0L) {
            "attachment transfer byte counts must not be negative"
        }
        require(successfulBytes <= transferredBytes && transferredBytes <= requestedBytes) {
            "attachment transfer byte counts are inconsistent"
        }
        require(
            attemptsByObject.all { (objectId, count) -> objectId.isNotBlank() && count > 0 } &&
                attemptsByObject.values.sum() == attempted,
        ) { "attachment transfer object distribution is inconsistent" }
        require(
            failuresByCategory.all { (category, count) -> category.isNotBlank() && count > 0 } &&
                failuresByCategory.values.sum() == failed,
        ) { "attachment transfer failure histogram is inconsistent" }
        require(elapsedMs >= 0.0 && elapsedMs.isFinite()) {
            "attachment transfer elapsed time must be finite and non-negative"
        }
        require(
            attemptsPerSecond >= 0.0 && attemptsPerSecond.isFinite() &&
                successfulBytesPerSecond >= 0.0 && successfulBytesPerSecond.isFinite(),
        ) { "attachment transfer rates must be finite and non-negative" }
        require(latency.sampleCount == attempted) {
            "attachment transfer requires one observed latency per attempt"
        }
        require(
            passed == (
                attempted > 0 &&
                    succeeded == attempted &&
                    failed == 0 &&
                    requestedBytes == transferredBytes &&
                    transferredBytes == successfulBytes
                ),
        ) { "attachment transfer pass flag is inconsistent with its evidence" }
    }
}

fun buildAttachmentTransferResult(
    name: String,
    attempts: List<AttachmentTransferAttempt>,
    elapsedNanos: Long,
): AttachmentTransferResult {
    require(name.isNotBlank()) { "attachment transfer result name must not be blank" }
    require(elapsedNanos >= 0L) { "attachment transfer elapsed time must not be negative" }
    val failures = attempts.mapNotNull(AttachmentTransferAttempt::failureCategory)
    val attemptsByObject = attempts.groupingBy(AttachmentTransferAttempt::objectId)
        .eachCount()
        .toSortedMap()
    val requestedBytes = attempts.sumBytes(AttachmentTransferAttempt::requestedBytes)
    val transferredBytes = attempts.sumBytes(AttachmentTransferAttempt::transferredBytes)
    val successfulBytes = attempts.asSequence()
        .filter { attempt -> attempt.failureCategory == null }
        .toList()
        .sumBytes(AttachmentTransferAttempt::transferredBytes)
    val succeeded = attempts.size - failures.size
    val passed = attempts.isNotEmpty() &&
        failures.isEmpty() &&
        requestedBytes == transferredBytes &&
        transferredBytes == successfulBytes
    return AttachmentTransferResult(
        name = name,
        attempted = attempts.size,
        succeeded = succeeded,
        failed = failures.size,
        requestedBytes = requestedBytes,
        transferredBytes = transferredBytes,
        successfulBytes = successfulBytes,
        attemptsByObject = attemptsByObject,
        failuresByCategory = failureCounts(failures),
        elapsedMs = elapsedMillis(elapsedNanos),
        attemptsPerSecond = capacityThroughputPerSecond(attempts.size, elapsedNanos),
        successfulBytesPerSecond = capacityEventRatePerSecond(successfulBytes, elapsedNanos),
        latency = summarizeAckLatencies(attempts.map(AttachmentTransferAttempt::latencyNanos)),
        passed = passed,
    )
}

private inline fun <T> List<T>.sumBytes(selector: (T) -> Long): Long =
    fold(0L) { total, item -> Math.addExact(total, selector(item)) }

/** 被持久化到报告中并按值比较的描述符字段。 */
@Serializable
data class AttachmentDescriptorEvidence(
    val path: String,
    val name: String,
    val contentType: String,
    val size: Long,
) {
    init {
        require(size >= 0L) { "attachment descriptor size must not be negative" }
    }
}

/**
 * 一次预期上传的证据。为 null 的实际值表示操作从未到达该验证步骤；
 * 它们会成为正确性失败，而不是让报告失效。
 */
data class AttachmentObjectObservation(
    val objectId: String,
    val expectedName: String,
    val expectedContentType: String,
    val expectedLength: Long,
    val expectedSha256: String,
    val descriptor: AttachmentDescriptorEvidence?,
    val groupFileCurrentAttachment: AttachmentDescriptorEvidence?,
    val downloadedLength: Long?,
    val downloadedSha256: String?,
) {
    init {
        require(objectId.isNotBlank()) { "attachment correctness object id must not be blank" }
        require(expectedName.isNotBlank()) { "attachment expected name must not be blank" }
        require(expectedContentType.isNotBlank()) {
            "attachment expected content type must not be blank"
        }
        require(expectedLength > 0L) { "attachment expected length must be positive" }
        require(SHA256_HEX.matches(expectedSha256)) {
            "attachment expected SHA-256 must contain 64 hexadecimal characters"
        }
        require(downloadedLength == null || downloadedLength >= 0L) {
            "attachment downloaded length must not be negative"
        }
    }
}

@Serializable
data class AttachmentObjectCorrectnessResult(
    val objectId: String,
    val expectedName: String,
    val expectedContentType: String,
    val expectedLength: Long,
    val expectedSha256: String,
    val descriptor: AttachmentDescriptorEvidence?,
    val groupFileCurrentAttachment: AttachmentDescriptorEvidence?,
    val downloadedLength: Long?,
    val downloadedSha256: String?,
    val descriptorExact: Boolean,
    val lengthExact: Boolean,
    val sha256Exact: Boolean,
    val businessReferenceExact: Boolean,
    val passed: Boolean,
) {
    init {
        require(objectId.isNotBlank()) { "attachment correctness object id must not be blank" }
        require(SHA256_HEX.matches(expectedSha256)) {
            "attachment correctness expected SHA-256 must be canonical hexadecimal evidence"
        }
        require(
            descriptorExact == descriptorMatchesExpectation(
                descriptor = descriptor,
                expectedName = expectedName,
                expectedContentType = expectedContentType,
                expectedLength = expectedLength,
            ),
        ) { "attachment descriptor exact flag is inconsistent with its evidence" }
        require(lengthExact == (downloadedLength == expectedLength)) {
            "attachment length exact flag is inconsistent with its evidence"
        }
        val expectedSha256Exact = downloadedSha256?.equals(
            expectedSha256,
            ignoreCase = true,
        ) == true
        require(sha256Exact == expectedSha256Exact) {
            "attachment SHA-256 exact flag is inconsistent with its evidence"
        }
        val expectedBusinessReferenceExact = descriptor != null &&
            groupFileCurrentAttachment == descriptor
        require(businessReferenceExact == expectedBusinessReferenceExact) {
            "attachment business-reference flag is inconsistent with its evidence"
        }
        require(
            passed == (
                descriptorExact && lengthExact && sha256Exact && businessReferenceExact
                ),
        ) { "attachment object pass flag is inconsistent with its evidence" }
    }
}

@Serializable
data class AttachmentCorrectnessResult(
    val expectedObjects: Int,
    val observedObjects: Int,
    val uniqueObjectIds: Int,
    val uniquePaths: Int,
    val descriptorExactObjects: Int,
    val lengthExactObjects: Int,
    val sha256ExactObjects: Int,
    val businessReferenceExactObjects: Int,
    val objects: List<AttachmentObjectCorrectnessResult>,
    val passed: Boolean,
) {
    init {
        require(expectedObjects > 0) { "attachment correctness requires expected objects" }
        require(observedObjects == objects.size) {
            "attachment correctness observation count is inconsistent"
        }
        require(uniqueObjectIds == objects.mapTo(hashSetOf()) { result -> result.objectId }.size) {
            "attachment correctness unique object count is inconsistent"
        }
        require(
            uniquePaths == objects.mapNotNull(AttachmentObjectCorrectnessResult::descriptor)
                .map(AttachmentDescriptorEvidence::path)
                .filter(String::isNotBlank)
                .toSet()
                .size,
        ) { "attachment correctness unique path count is inconsistent" }
        require(descriptorExactObjects == objects.count { result -> result.descriptorExact }) {
            "attachment descriptor aggregate is inconsistent"
        }
        require(lengthExactObjects == objects.count { result -> result.lengthExact }) {
            "attachment length aggregate is inconsistent"
        }
        require(sha256ExactObjects == objects.count { result -> result.sha256Exact }) {
            "attachment SHA-256 aggregate is inconsistent"
        }
        require(
            businessReferenceExactObjects == objects.count { result ->
                result.businessReferenceExact
            },
        ) { "attachment business-reference aggregate is inconsistent" }
        require(
            listOf(
                uniqueObjectIds,
                uniquePaths,
                descriptorExactObjects,
                lengthExactObjects,
                sha256ExactObjects,
                businessReferenceExactObjects,
            ).all { count -> count in 0..observedObjects },
        ) { "attachment correctness aggregate count is invalid" }
        require(
            passed == (
                observedObjects == expectedObjects &&
                    uniqueObjectIds == expectedObjects &&
                    uniquePaths == expectedObjects &&
                    descriptorExactObjects == expectedObjects &&
                    lengthExactObjects == expectedObjects &&
                    sha256ExactObjects == expectedObjects &&
                    businessReferenceExactObjects == expectedObjects &&
                    objects.all(AttachmentObjectCorrectnessResult::passed)
                ),
        ) { "attachment correctness pass flag is inconsistent with its evidence" }
    }
}

fun buildAttachmentCorrectnessResult(
    expectedObjects: Int,
    observations: List<AttachmentObjectObservation>,
): AttachmentCorrectnessResult {
    require(expectedObjects > 0) { "attachment correctness requires expected objects" }
    val objects = observations.map { observation ->
        val descriptor = observation.descriptor
        val descriptorExact = descriptorMatchesExpectation(
            descriptor = descriptor,
            expectedName = observation.expectedName,
            expectedContentType = observation.expectedContentType,
            expectedLength = observation.expectedLength,
        )
        val lengthExact = observation.downloadedLength == observation.expectedLength
        val sha256Exact = observation.downloadedSha256?.equals(
            observation.expectedSha256,
            ignoreCase = true,
        ) == true
        val businessReferenceExact = descriptor != null &&
            observation.groupFileCurrentAttachment == descriptor
        AttachmentObjectCorrectnessResult(
            objectId = observation.objectId,
            expectedName = observation.expectedName,
            expectedContentType = observation.expectedContentType,
            expectedLength = observation.expectedLength,
            expectedSha256 = observation.expectedSha256.lowercase(),
            descriptor = descriptor,
            groupFileCurrentAttachment = observation.groupFileCurrentAttachment,
            downloadedLength = observation.downloadedLength,
            downloadedSha256 = observation.downloadedSha256?.lowercase(),
            descriptorExact = descriptorExact,
            lengthExact = lengthExact,
            sha256Exact = sha256Exact,
            businessReferenceExact = businessReferenceExact,
            passed = descriptorExact && lengthExact && sha256Exact && businessReferenceExact,
        )
    }
    val uniqueObjectIds = objects.mapTo(hashSetOf(), AttachmentObjectCorrectnessResult::objectId).size
    val uniquePaths = objects.mapNotNull(AttachmentObjectCorrectnessResult::descriptor)
        .map(AttachmentDescriptorEvidence::path)
        .filter(String::isNotBlank)
        .toSet()
        .size
    val descriptorExactObjects = objects.count(AttachmentObjectCorrectnessResult::descriptorExact)
    val lengthExactObjects = objects.count(AttachmentObjectCorrectnessResult::lengthExact)
    val sha256ExactObjects = objects.count(AttachmentObjectCorrectnessResult::sha256Exact)
    val businessReferenceExactObjects = objects.count(
        AttachmentObjectCorrectnessResult::businessReferenceExact,
    )
    val passed = objects.size == expectedObjects &&
        uniqueObjectIds == expectedObjects &&
        uniquePaths == expectedObjects &&
        descriptorExactObjects == expectedObjects &&
        lengthExactObjects == expectedObjects &&
        sha256ExactObjects == expectedObjects &&
        businessReferenceExactObjects == expectedObjects &&
        objects.all(AttachmentObjectCorrectnessResult::passed)
    return AttachmentCorrectnessResult(
        expectedObjects = expectedObjects,
        observedObjects = objects.size,
        uniqueObjectIds = uniqueObjectIds,
        uniquePaths = uniquePaths,
        descriptorExactObjects = descriptorExactObjects,
        lengthExactObjects = lengthExactObjects,
        sha256ExactObjects = sha256ExactObjects,
        businessReferenceExactObjects = businessReferenceExactObjects,
        objects = objects,
        passed = passed,
    )
}

private fun descriptorMatchesExpectation(
    descriptor: AttachmentDescriptorEvidence?,
    expectedName: String,
    expectedContentType: String,
    expectedLength: Long,
): Boolean = descriptor != null &&
    descriptor.path.isNotBlank() &&
    descriptor.name == expectedName &&
    descriptor.contentType == expectedContentType &&
    descriptor.size == expectedLength

data class AttachmentSessionObservation(
    val laneId: Int,
    val authenticatedBefore: Boolean,
    val authenticatedAfter: Boolean,
    val authenticationCountBefore: Int,
    val authenticationCountAfter: Int,
) {
    init {
        require(laneId >= 0) { "attachment session lane id must not be negative" }
        require(authenticationCountBefore >= 0 && authenticationCountAfter >= 0) {
            "attachment session authentication counts must not be negative"
        }
        require(authenticationCountAfter >= authenticationCountBefore) {
            "attachment session authentication count must be monotonic"
        }
    }
}

@Serializable
data class AttachmentSessionResult(
    val laneId: Int,
    val authenticatedBefore: Boolean,
    val authenticatedAfter: Boolean,
    val authenticationCountBefore: Int,
    val authenticationCountAfter: Int,
    val authenticationDelta: Int,
    val passed: Boolean,
)

@Serializable
data class AttachmentSessionStabilityResult(
    val expectedSessions: Int,
    val observedSessions: Int,
    val stableSessions: Int,
    val unexpectedDisconnects: Int,
    val unexpectedAuthenticationChanges: Int,
    val sessions: List<AttachmentSessionResult>,
    val passed: Boolean,
) {
    init {
        require(expectedSessions > 0) { "attachment session stability requires sessions" }
        require(observedSessions == sessions.size) {
            "attachment session detail count is inconsistent"
        }
        require(stableSessions in 0..observedSessions) {
            "attachment stable session count is invalid"
        }
        require(unexpectedDisconnects in 0..observedSessions) {
            "attachment unexpected disconnect count is invalid"
        }
        require(unexpectedAuthenticationChanges in 0..observedSessions) {
            "attachment unexpected authentication change count is invalid"
        }
        require(
            passed == (
                observedSessions == expectedSessions &&
                    stableSessions == expectedSessions &&
                    unexpectedDisconnects == 0 &&
                    unexpectedAuthenticationChanges == 0
                ),
        ) { "attachment session pass flag is inconsistent with its evidence" }
    }
}

fun buildAttachmentSessionStabilityResult(
    expectedSessions: Int,
    observations: List<AttachmentSessionObservation>,
): AttachmentSessionStabilityResult {
    require(observations.mapTo(hashSetOf(), AttachmentSessionObservation::laneId).size ==
        observations.size) { "attachment session observations must use distinct lane ids" }
    val sessions = observations.sortedBy(AttachmentSessionObservation::laneId).map { observation ->
        val authenticationDelta = observation.authenticationCountAfter -
            observation.authenticationCountBefore
        AttachmentSessionResult(
            laneId = observation.laneId,
            authenticatedBefore = observation.authenticatedBefore,
            authenticatedAfter = observation.authenticatedAfter,
            authenticationCountBefore = observation.authenticationCountBefore,
            authenticationCountAfter = observation.authenticationCountAfter,
            authenticationDelta = authenticationDelta,
            passed = observation.authenticatedBefore &&
                observation.authenticatedAfter &&
                authenticationDelta == 0,
        )
    }
    val unexpectedDisconnects = sessions.count { session ->
        session.authenticatedBefore && !session.authenticatedAfter
    }
    val unexpectedAuthenticationChanges = sessions.count { session ->
        session.authenticationDelta != 0
    }
    val stableSessions = sessions.count(AttachmentSessionResult::passed)
    val passed = sessions.size == expectedSessions &&
        stableSessions == expectedSessions &&
        unexpectedDisconnects == 0 &&
        unexpectedAuthenticationChanges == 0
    return AttachmentSessionStabilityResult(
        expectedSessions = expectedSessions,
        observedSessions = sessions.size,
        stableSessions = stableSessions,
        unexpectedDisconnects = unexpectedDisconnects,
        unexpectedAuthenticationChanges = unexpectedAuthenticationChanges,
        sessions = sessions,
        passed = passed,
    )
}

/** 为一个 GroupFile 业务引用捕获的原始清理事实。 */
data class AttachmentCleanupObservation(
    val objectId: String,
    val entryId: String,
    val deleteAcknowledged: Boolean,
    val absentAfterCleanup: Boolean,
    val failureCategory: String? = null,
) {
    init {
        require(objectId.isNotBlank()) { "attachment cleanup object id must not be blank" }
        require(entryId.isNotBlank()) { "attachment cleanup entry id must not be blank" }
        require(failureCategory == null || failureCategory.isNotBlank()) {
            "attachment cleanup failure category must not be blank"
        }
    }
}

/**
 * 逐对象的清理证据。普通删除只有在删除 ACK 与随后的缺席观察都完成后才通过。
 * 物理对象保留在这里有意不被表示。
 */
@Serializable
data class AttachmentObjectCleanupResult(
    val objectId: String,
    val entryId: String,
    val deleteAcknowledged: Boolean,
    val absentAfterCleanup: Boolean,
    val failureCategory: String?,
    val passed: Boolean,
) {
    init {
        require(objectId.isNotBlank()) { "attachment cleanup object id must not be blank" }
        require(entryId.isNotBlank()) { "attachment cleanup entry id must not be blank" }
        require(failureCategory == null || failureCategory.isNotBlank()) {
            "attachment cleanup failure category must not be blank"
        }
        val evidencePassed = deleteAcknowledged && absentAfterCleanup && failureCategory == null
        require(passed == evidencePassed) {
            "attachment cleanup object pass flag is inconsistent with its evidence"
        }
        require(passed == (failureCategory == null)) {
            "attachment cleanup object requires exactly one success or failure outcome"
        }
    }
}

@Serializable
data class AttachmentCleanupResult(
    val expectedBusinessReferences: Int,
    val attempted: Int,
    val deleteAcknowledgedObjects: Int,
    val absentAfterCleanupObjects: Int,
    /** 同时具有删除 ACK 与已验证缺席业务引用的对象。 */
    val deleted: Int,
    val failed: Int,
    val failuresByCategory: Map<String, Int>,
    val objects: List<AttachmentObjectCleanupResult>,
    val physicalObjectsRemainingForRetentionGc: Int,
    val elapsedMs: Double,
    val passed: Boolean,
) {
    init {
        require(expectedBusinessReferences > 0) {
            "attachment cleanup requires expected business references"
        }
        require(attempted == objects.size) {
            "attachment cleanup attempt count is inconsistent with object evidence"
        }
        require(objects.mapTo(hashSetOf(), AttachmentObjectCleanupResult::objectId).size ==
            objects.size) { "attachment cleanup object ids must be unique" }
        require(objects.mapTo(hashSetOf(), AttachmentObjectCleanupResult::entryId).size ==
            objects.size) { "attachment cleanup entry ids must be unique" }
        require(deleteAcknowledgedObjects == objects.count { result ->
            result.deleteAcknowledged
        }) { "attachment cleanup ACK aggregate is inconsistent" }
        require(absentAfterCleanupObjects == objects.count { result ->
            result.absentAfterCleanup
        }) { "attachment cleanup absence aggregate is inconsistent" }
        require(deleted == objects.count(AttachmentObjectCleanupResult::passed)) {
            "attachment cleanup verified-deletion aggregate is inconsistent"
        }
        require(failed == objects.count { result -> !result.passed }) {
            "attachment cleanup failure aggregate is inconsistent"
        }
        require(deleted + failed == attempted) {
            "attachment cleanup results must partition attempts"
        }
        val expectedFailures = failureCounts(
            objects.mapNotNull(AttachmentObjectCleanupResult::failureCategory),
        )
        require(failuresByCategory == expectedFailures) {
            "attachment cleanup failure histogram is inconsistent"
        }
        require(physicalObjectsRemainingForRetentionGc >= 0) {
            "attachment retained physical object count must not be negative"
        }
        require(elapsedMs >= 0.0 && elapsedMs.isFinite()) {
            "attachment cleanup elapsed time must be finite and non-negative"
        }
        require(
            passed == (
                attempted == expectedBusinessReferences &&
                    deleteAcknowledgedObjects == expectedBusinessReferences &&
                    absentAfterCleanupObjects == expectedBusinessReferences &&
                    deleted == expectedBusinessReferences &&
                    failed == 0
                ),
        ) {
            "attachment cleanup pass flag must depend on verified business-reference deletion"
        }
    }
}

fun buildAttachmentCleanupResult(
    expectedBusinessReferences: Int,
    observations: List<AttachmentCleanupObservation>,
    physicalObjectsRemainingForRetentionGc: Int,
    elapsedNanos: Long,
): AttachmentCleanupResult {
    require(expectedBusinessReferences > 0) {
        "attachment cleanup requires expected business references"
    }
    require(elapsedNanos >= 0L) { "attachment cleanup elapsed time must not be negative" }
    require(observations.mapTo(hashSetOf(), AttachmentCleanupObservation::objectId).size ==
        observations.size) { "attachment cleanup observations must use unique object ids" }
    require(observations.mapTo(hashSetOf(), AttachmentCleanupObservation::entryId).size ==
        observations.size) { "attachment cleanup observations must use unique entry ids" }
    val objects = observations.sortedBy(AttachmentCleanupObservation::objectId).map { observation ->
        val failureCategory = observation.failureCategory ?: cleanupEvidenceFailureCategory(
            deleteAcknowledged = observation.deleteAcknowledged,
            absentAfterCleanup = observation.absentAfterCleanup,
        )
        val passed = observation.deleteAcknowledged &&
            observation.absentAfterCleanup &&
            failureCategory == null
        AttachmentObjectCleanupResult(
            objectId = observation.objectId,
            entryId = observation.entryId,
            deleteAcknowledged = observation.deleteAcknowledged,
            absentAfterCleanup = observation.absentAfterCleanup,
            failureCategory = failureCategory,
            passed = passed,
        )
    }
    val deleteAcknowledgedObjects = objects.count { result -> result.deleteAcknowledged }
    val absentAfterCleanupObjects = objects.count { result -> result.absentAfterCleanup }
    val deleted = objects.count(AttachmentObjectCleanupResult::passed)
    val failed = objects.size - deleted
    val passed = objects.size == expectedBusinessReferences &&
        deleteAcknowledgedObjects == expectedBusinessReferences &&
        absentAfterCleanupObjects == expectedBusinessReferences &&
        deleted == expectedBusinessReferences &&
        failed == 0
    return AttachmentCleanupResult(
        expectedBusinessReferences = expectedBusinessReferences,
        attempted = objects.size,
        deleteAcknowledgedObjects = deleteAcknowledgedObjects,
        absentAfterCleanupObjects = absentAfterCleanupObjects,
        deleted = deleted,
        failed = failed,
        failuresByCategory = failureCounts(
            objects.mapNotNull(AttachmentObjectCleanupResult::failureCategory),
        ),
        objects = objects,
        physicalObjectsRemainingForRetentionGc = physicalObjectsRemainingForRetentionGc,
        elapsedMs = elapsedMillis(elapsedNanos),
        passed = passed,
    )
}

private fun cleanupEvidenceFailureCategory(
    deleteAcknowledged: Boolean,
    absentAfterCleanup: Boolean,
): String? = when {
    deleteAcknowledged && absentAfterCleanup -> null
    !deleteAcknowledged && !absentAfterCleanup ->
        AttachmentCapacityFailureCategory.DELETE_NOT_ACKNOWLEDGED_AND_REFERENCE_PRESENT
    !deleteAcknowledged -> AttachmentCapacityFailureCategory.DELETE_NOT_ACKNOWLEDGED
    else -> AttachmentCapacityFailureCategory.BUSINESS_REFERENCE_PRESENT
}

@Serializable
data class AttachmentResourceResult(
    val requiredHealthyComponents: Int = REQUIRED_HEALTHY_COMPONENTS,
    val sampleCount: Int,
    val snapshots: List<TeamTalkResourceSnapshot>,
    val stableInvocation: Boolean,
    val stableBuildIdentity: Boolean,
    val allHealthy: Boolean,
    val cpuTicksMonotonic: Boolean,
    val cpuTicksDelta: Long,
    val maxRssBytes: Long,
    val maxThreadCount: Int,
    val maxFdCount: Int,
    val maxHostLoad1: Double,
    val minMemAvailableBytes: Long,
    val baselineRssBytes: Long,
    val finalRssBytes: Long,
    val baselineThreadCount: Int,
    val finalThreadCount: Int,
    val baselineFdCount: Int,
    val finalFdCount: Int,
    val passed: Boolean,
) {
    init {
        require(requiredHealthyComponents == REQUIRED_HEALTHY_COMPONENTS) {
            "attachment resources require the complete nine-component health set"
        }
        require(sampleCount == snapshots.size && sampleCount >= 2) {
            "attachment resources require matching baseline and final samples"
        }
        require(cpuTicksDelta >= 0L) { "attachment resource CPU delta must not be negative" }
        require(maxHostLoad1 >= 0.0 && maxHostLoad1.isFinite()) {
            "attachment resource maximum host load must be finite and non-negative"
        }
        val facts = deriveAttachmentResourceFacts(snapshots)
        require(stableInvocation == facts.stableInvocation) {
            "attachment stable invocation flag is inconsistent with its snapshots"
        }
        require(stableBuildIdentity == facts.stableBuildIdentity) {
            "attachment stable build flag is inconsistent with its snapshots"
        }
        require(allHealthy == facts.allHealthy) {
            "attachment nine-component health flag is inconsistent with its snapshots"
        }
        require(cpuTicksMonotonic == facts.cpuTicksMonotonic) {
            "attachment CPU monotonic flag is inconsistent with its snapshots"
        }
        require(cpuTicksDelta == facts.cpuTicksDelta) {
            "attachment CPU delta is inconsistent with its snapshots"
        }
        require(
            maxRssBytes == facts.maxRssBytes &&
                maxThreadCount == facts.maxThreadCount &&
                maxFdCount == facts.maxFdCount &&
                maxHostLoad1 == facts.maxHostLoad1 &&
                minMemAvailableBytes == facts.minMemAvailableBytes,
        ) { "attachment resource extrema are inconsistent with its snapshots" }
        require(
            baselineRssBytes == facts.baseline.rssBytes &&
                finalRssBytes == facts.final.rssBytes &&
                baselineThreadCount == facts.baseline.threadCount &&
                finalThreadCount == facts.final.threadCount &&
                baselineFdCount == facts.baseline.fdCount &&
                finalFdCount == facts.final.fdCount,
        ) { "attachment resource boundary counters are inconsistent with its snapshots" }
        require(
            passed == facts.passed,
        ) { "attachment resource pass flag may only depend on stable process health" }
    }
}

/** 记录资源量级，而不是凭空捏造首轮 RSS、线程或 FD SLO。 */
fun summarizeAttachmentResources(
    snapshots: List<TeamTalkResourceSnapshot>,
): AttachmentResourceResult {
    val facts = deriveAttachmentResourceFacts(snapshots)
    return AttachmentResourceResult(
        sampleCount = snapshots.size,
        snapshots = snapshots.toList(),
        stableInvocation = facts.stableInvocation,
        stableBuildIdentity = facts.stableBuildIdentity,
        allHealthy = facts.allHealthy,
        cpuTicksMonotonic = facts.cpuTicksMonotonic,
        cpuTicksDelta = facts.cpuTicksDelta,
        maxRssBytes = facts.maxRssBytes,
        maxThreadCount = facts.maxThreadCount,
        maxFdCount = facts.maxFdCount,
        maxHostLoad1 = facts.maxHostLoad1,
        minMemAvailableBytes = facts.minMemAvailableBytes,
        baselineRssBytes = facts.baseline.rssBytes,
        finalRssBytes = facts.final.rssBytes,
        baselineThreadCount = facts.baseline.threadCount,
        finalThreadCount = facts.final.threadCount,
        baselineFdCount = facts.baseline.fdCount,
        finalFdCount = facts.final.fdCount,
        passed = facts.passed,
    )
}

private data class AttachmentResourceFacts(
    val baseline: TeamTalkResourceSnapshot,
    val final: TeamTalkResourceSnapshot,
    val stableInvocation: Boolean,
    val stableBuildIdentity: Boolean,
    val allHealthy: Boolean,
    val cpuTicksMonotonic: Boolean,
    val cpuTicksDelta: Long,
    val maxRssBytes: Long,
    val maxThreadCount: Int,
    val maxFdCount: Int,
    val maxHostLoad1: Double,
    val minMemAvailableBytes: Long,
) {
    val passed: Boolean
        get() = stableInvocation && stableBuildIdentity && allHealthy && cpuTicksMonotonic
}

private fun deriveAttachmentResourceFacts(
    snapshots: List<TeamTalkResourceSnapshot>,
): AttachmentResourceFacts {
    require(snapshots.size >= 2) {
        "attachment resources require baseline and final samples"
    }
    require(
        snapshots.all { snapshot ->
            snapshot.mainPid > 0L &&
                snapshot.rssBytes >= 0L &&
                snapshot.threadCount >= 0 &&
                snapshot.fdCount >= 0 &&
                snapshot.cpuTicks >= 0L &&
                snapshot.hostLoad1 >= 0.0 && snapshot.hostLoad1.isFinite() &&
                snapshot.memAvailableBytes >= 0L &&
                snapshot.totalComponents >= 0 &&
                snapshot.healthyComponents in 0..snapshot.totalComponents
        },
    ) { "attachment resource sample contains an invalid counter" }

    val baseline = snapshots.first()
    val final = snapshots.last()
    val stableInvocation = baseline.invocationId.isNotBlank() && snapshots.all { snapshot ->
        snapshot.invocationId == baseline.invocationId && snapshot.mainPid == baseline.mainPid
    }
    val stableBuildIdentity = baseline.buildIdentity.isNotBlank() && snapshots.all { snapshot ->
        snapshot.buildIdentity == baseline.buildIdentity
    }
    val allHealthy = snapshots.all { snapshot ->
        snapshot.healthStatus == "UP" &&
            snapshot.totalComponents == REQUIRED_HEALTHY_COMPONENTS &&
            snapshot.healthyComponents == REQUIRED_HEALTHY_COMPONENTS
    }
    val cpuTicksMonotonic = snapshots.zipWithNext().all { (before, after) ->
        after.cpuTicks >= before.cpuTicks
    }
    val cpuTicksDelta = if (cpuTicksMonotonic) final.cpuTicks - baseline.cpuTicks else 0L
    return AttachmentResourceFacts(
        baseline = baseline,
        final = final,
        stableInvocation = stableInvocation,
        stableBuildIdentity = stableBuildIdentity,
        allHealthy = allHealthy,
        cpuTicksMonotonic = cpuTicksMonotonic,
        cpuTicksDelta = cpuTicksDelta,
        maxRssBytes = snapshots.maxOf(TeamTalkResourceSnapshot::rssBytes),
        maxThreadCount = snapshots.maxOf(TeamTalkResourceSnapshot::threadCount),
        maxFdCount = snapshots.maxOf(TeamTalkResourceSnapshot::fdCount),
        maxHostLoad1 = snapshots.maxOf(TeamTalkResourceSnapshot::hostLoad1),
        minMemAvailableBytes = snapshots.minOf(TeamTalkResourceSnapshot::memAvailableBytes),
    )
}

/** 在远端附件压测运行前写入的持久标记，最终被其最终报告替换。 */
@Serializable
data class AttachmentCapacityRunState(
    val schemaVersion: Int = 1,
    val reportType: String = "attachment-capacity-run-state",
    val generatedAt: String,
    val runId: String,
    val target: CapacityTarget,
    val state: String,
    val phase: String,
    val failureType: String? = null,
    val failureMessage: String? = null,
) {
    init {
        require(schemaVersion == 1) { "unsupported attachment run-state schema" }
        require(reportType == "attachment-capacity-run-state") {
            "unsupported attachment run-state report type"
        }
        require(generatedAt.isNotBlank()) { "attachment run-state timestamp must not be blank" }
        require(runId.isNotBlank()) { "attachment run-state id must not be blank" }
        require(state == "started" || state == "failed") { "invalid attachment run state" }
        require(phase.isNotBlank()) { "attachment run-state phase must not be blank" }
        require((state == "failed") == (failureType != null)) {
            "failed attachment run state requires a failure type"
        }
        require(failureType == null || failureType.isNotBlank()) {
            "attachment run-state failure type must not be blank"
        }
        require(failureMessage == null || failureMessage.isNotBlank()) {
            "attachment run-state failure message must not be blank"
        }
    }
}

@Serializable
data class AttachmentCapacityReport(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val target: CapacityTarget,
    val config: AttachmentCapacityConfig,
    val warmupUpload: AttachmentTransferResult,
    val steadyUpload: AttachmentTransferResult,
    val burstUpload: AttachmentTransferResult,
    val authenticatedDownload: AttachmentTransferResult,
    val correctness: AttachmentCorrectnessResult,
    val sessions: AttachmentSessionStabilityResult,
    val cleanup: AttachmentCleanupResult,
    val resources: AttachmentResourceResult,
    val passed: Boolean,
    val note: String = "Single-run attachment development baseline; latency and resource " +
        "magnitudes are observations, not product SLOs. Physical objects remain subject to " +
        "the retention GC window after business references are deleted.",
) {
    init {
        require(schemaVersion == 1) { "unsupported attachment capacity report schema" }
        require(generatedAt.isNotBlank()) {
            "attachment capacity report timestamp must not be blank"
        }
        requireAttachmentCapacityEvidence(
            config = config,
            warmupUpload = warmupUpload,
            steadyUpload = steadyUpload,
            burstUpload = burstUpload,
            authenticatedDownload = authenticatedDownload,
            correctness = correctness,
            sessions = sessions,
            cleanup = cleanup,
        )
        require(
            passed == (
                warmupUpload.passed &&
                    steadyUpload.passed &&
                    burstUpload.passed &&
                    authenticatedDownload.passed &&
                    correctness.passed &&
                    sessions.passed &&
                    cleanup.passed &&
                    resources.passed
                ),
        ) { "attachment capacity report pass flag is inconsistent with its evidence" }
    }
}

fun buildAttachmentCapacityReport(
    generatedAt: String,
    target: CapacityTarget,
    config: AttachmentCapacityConfig,
    warmupUpload: AttachmentTransferResult,
    steadyUpload: AttachmentTransferResult,
    burstUpload: AttachmentTransferResult,
    authenticatedDownload: AttachmentTransferResult,
    correctness: AttachmentCorrectnessResult,
    sessions: AttachmentSessionStabilityResult,
    cleanup: AttachmentCleanupResult,
    resources: AttachmentResourceResult,
): AttachmentCapacityReport {
    val passed = warmupUpload.passed &&
        steadyUpload.passed &&
        burstUpload.passed &&
        authenticatedDownload.passed &&
        correctness.passed &&
        sessions.passed &&
        cleanup.passed &&
        resources.passed
    return AttachmentCapacityReport(
        generatedAt = generatedAt,
        target = target,
        config = config,
        warmupUpload = warmupUpload,
        steadyUpload = steadyUpload,
        burstUpload = burstUpload,
        authenticatedDownload = authenticatedDownload,
        correctness = correctness,
        sessions = sessions,
        cleanup = cleanup,
        resources = resources,
        passed = passed,
    )
}

private fun requireAttachmentCapacityEvidence(
    config: AttachmentCapacityConfig,
    warmupUpload: AttachmentTransferResult,
    steadyUpload: AttachmentTransferResult,
    burstUpload: AttachmentTransferResult,
    authenticatedDownload: AttachmentTransferResult,
    correctness: AttachmentCorrectnessResult,
    sessions: AttachmentSessionStabilityResult,
    cleanup: AttachmentCleanupResult,
) {
    requireTransferShape(
        result = warmupUpload,
        expectedName = AttachmentCapacityScenarioName.WARMUP_UPLOAD,
        expectedAttempts = config.expectedWarmupUploads,
        payloadBytes = config.payloadBytes,
    )
    requireTransferShape(
        result = steadyUpload,
        expectedName = AttachmentCapacityScenarioName.STEADY_UPLOAD,
        expectedAttempts = config.expectedSteadyUploads,
        payloadBytes = config.payloadBytes,
    )
    requireTransferShape(
        result = burstUpload,
        expectedName = AttachmentCapacityScenarioName.BURST_UPLOAD,
        expectedAttempts = config.burstUploadsTotal,
        payloadBytes = config.payloadBytes,
    )
    requireTransferShape(
        result = authenticatedDownload,
        expectedName = AttachmentCapacityScenarioName.AUTHENTICATED_DOWNLOAD,
        expectedAttempts = config.expectedAuthenticatedDownloads,
        payloadBytes = config.payloadBytes,
    )
    require(correctness.expectedObjects == config.expectedUploads) {
        "attachment correctness target differs from report configuration"
    }
    require(correctness.objects.all { objectResult ->
        objectResult.expectedLength == config.payloadBytes
    }) { "attachment correctness payload size differs from report configuration" }
    require(sessions.expectedSessions == config.uploaderCount) {
        "attachment session target differs from report configuration"
    }
    require(cleanup.expectedBusinessReferences == config.expectedUploads) {
        "attachment cleanup target differs from report configuration"
    }
    val expectedObjectIds = correctness.objects.mapTo(linkedSetOf()) { result -> result.objectId }
    require(expectedObjectIds.size == config.expectedUploads) {
        "attachment correctness must identify every configured object exactly once"
    }
    val uploadAttemptsByObject = linkedMapOf<String, Int>()
    listOf(warmupUpload, steadyUpload, burstUpload).forEach { result ->
        result.attemptsByObject.forEach { (objectId, attempts) ->
            uploadAttemptsByObject[objectId] = Math.addExact(
                uploadAttemptsByObject[objectId] ?: 0,
                attempts,
            )
        }
    }
    require(
        uploadAttemptsByObject.keys == expectedObjectIds &&
            uploadAttemptsByObject.values.all { attempts -> attempts == 1 },
    ) { "attachment upload phases must cover every configured object exactly once" }
    require(
        authenticatedDownload.attemptsByObject.keys == expectedObjectIds &&
            authenticatedDownload.attemptsByObject.values.all { attempts ->
                attempts == config.downloadsPerAttachment
            },
    ) {
        "authenticated downloads must cover every attachment the configured number of times"
    }
    require(
        cleanup.objects.mapTo(linkedSetOf(), AttachmentObjectCleanupResult::objectId) ==
            expectedObjectIds,
    ) { "attachment cleanup must cover the exact correctness object set" }
}

private fun requireTransferShape(
    result: AttachmentTransferResult,
    expectedName: String,
    expectedAttempts: Int,
    payloadBytes: Long,
) {
    require(result.name == expectedName) {
        "attachment transfer phase differs from report field"
    }
    require(result.attempted == expectedAttempts) {
        "attachment transfer attempt count differs from report configuration"
    }
    require(result.requestedBytes == Math.multiplyExact(expectedAttempts.toLong(), payloadBytes)) {
        "attachment transfer requested bytes differ from report configuration"
    }
}

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
private const val REQUIRED_HEALTHY_COMPONENTS = 9
