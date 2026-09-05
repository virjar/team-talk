package com.virjar.tk.server.e2e.capacity

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ceil
import kotlin.math.round

@Serializable
data class CapacityLatencySummary(
    val sampleCount: Int,
    val minMs: Double? = null,
    val p50Ms: Double? = null,
    val p95Ms: Double? = null,
    val p99Ms: Double? = null,
    val maxMs: Double? = null,
)

@Serializable
data class CapacityScenarioMetrics(
    val name: String,
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val busyRejected: Int,
    val timedOut: Int,
    val transportFailed: Int,
    val failuresByCategory: Map<String, Int>,
    val elapsedMs: Double,
    val throughputPerSecond: Double,
    val ackLatency: CapacityLatencySummary,
)

@Serializable
data class CapacityLaneIntegrity(
    val laneId: Int,
    val expectedMessages: Int,
    val ackUniqueMessages: Int,
    val ackDuplicateMessages: Int,
    val ackUniqueServerSeqs: Int,
    val serverSeqContiguous: Boolean,
    val notificationUniqueMessages: Int,
    val notificationMissing: Int,
    val notificationDuplicateMessages: Int,
    val historyUniqueMessages: Int,
    val historyMissing: Int,
    val historyDuplicateMessages: Int,
    val ackNotificationSeqConverged: Boolean,
    val ackHistorySeqConverged: Boolean,
    val passed: Boolean,
)

@Serializable
data class CapacityIntegrityResult(
    val expectedMessages: Int,
    val laneCount: Int,
    val ackDuplicateMessages: Int,
    val notificationMissing: Int,
    val notificationDuplicateMessages: Int,
    val historyMissing: Int,
    val historyDuplicateMessages: Int,
    val lanes: List<CapacityLaneIntegrity>,
    val passed: Boolean,
)

@Serializable
data class CapacityRecoveryResult(
    val initialRecoverableMessages: Int,
    val initialBusyRejected: Int,
    val initialTimeouts: Int,
    val initialTransportFailures: Int,
    val initialTerminalFailures: Int,
    val recoveryAttempts: Int,
    val attemptFailuresByCategory: Map<String, Int>,
    val recoveredMessages: Int,
    val unrecoveredMessages: Int,
    val recoveryTerminalFailures: Int,
    val freshProbeExpectedLanes: Int,
    val freshProbeSucceededLanes: Int,
    val elapsedMs: Double,
    val overloadObserved: Boolean,
    val passed: Boolean,
) {
    init {
        require(!passed || overloadObserved) {
            "capacity overload recovery cannot pass without an observed busy rejection"
        }
    }
}

@Serializable
data class CapacityEventCatchupResult(
    val baseCursor: Long,
    val targetCursor: Long,
    val finalCursor: Long,
    val backlogEvents: Long,
    val minimumBacklogEvents: Int,
    val syncPageSize: Int,
    val minimumReplayPages: Long,
    val authenticationCountBefore: Int,
    val authenticationCountAfter: Int,
    val authenticationDelta: Int,
    val expectedMessages: Int,
    val replayUniqueMessages: Int,
    val replayMissingMessages: Int,
    val replayDuplicateMessages: Int,
    val ackReplaySeqConverged: Boolean,
    val localProjectionUniqueMessages: Int,
    val localProjectionMissingMessages: Int,
    val localProjectionDuplicateMessages: Int,
    val ackLocalProjectionSeqConverged: Boolean,
    val localProjectionConverged: Boolean,
    val expectedConversations: Int,
    val convergedConversations: Int,
    val elapsedMs: Double,
    val eventsPerSecond: Double,
    val passed: Boolean,
)

@Serializable
data class CapacityRunConfig(
    val senderLanes: Int,
    val warmupMessagesPerLane: Int,
    val steadyMessagesPerLane: Int,
    val steadyIntervalMs: Long,
    val burstMessagesTotal: Int,
    val burstConcurrency: Int,
    val ackTimeoutMs: Long,
    val deliveryTimeoutMs: Long,
    val recoveryTimeoutMs: Long,
    val recoveryRetryIntervalMs: Long,
    val eventCatchupTimeoutMs: Long,
    val eventCatchupMinimumEvents: Int,
)

@Serializable
data class CapacityTarget(
    val host: String,
    val port: Int,
)

@Serializable
data class MessageCapacityReport(
    val schemaVersion: Int = 3,
    val generatedAt: String,
    val target: CapacityTarget,
    val config: CapacityRunConfig,
    val scenarios: List<CapacityScenarioMetrics>,
    val recovery: CapacityRecoveryResult,
    val eventCatchup: CapacityEventCatchupResult,
    val integrity: CapacityIntegrityResult,
    val passed: Boolean,
    val note: String = "Single-run development baseline; not a product capacity commitment.",
)

@Serializable
data class ConnectionCapacityConfig(
    val clientCount: Int,
    val rampGroupSize: Int,
    val rampIntervalMs: Long,
    val holdDurationMs: Long,
    val reconnectCount: Int,
    val reconnectTimeoutMs: Long,
    val sampleIntervalMs: Long,
    val cleanupObservationMs: Long,
)

@Serializable
data class ConnectionRampResult(
    val attempted: Int,
    val authenticated: Int,
    val failed: Int,
    val failuresByCategory: Map<String, Int>,
    val elapsedMs: Double,
    val readyLatency: CapacityLatencySummary,
    val passed: Boolean,
)

@Serializable
data class ConnectionHoldResult(
    val expectedClients: Int,
    val sampleCount: Int,
    val minAuthenticated: Int,
    val unexpectedDisconnects: Int,
    val unexpectedAuthenticationChanges: Int,
    val passed: Boolean,
)

@Serializable
data class ConnectionReconnectResult(
    val targeted: Int,
    val recovered: Int,
    val exactAuthenticationDelta: Int,
    val controlClients: Int,
    val stableControlClients: Int,
    val elapsedMs: Double,
    val latency: CapacityLatencySummary,
    val passed: Boolean,
)

@Serializable
data class TeamTalkResourceSnapshot(
    val phase: String,
    val capturedAt: String,
    val invocationId: String,
    val mainPid: Long,
    val rssBytes: Long,
    val threadCount: Int,
    val fdCount: Int,
    val cpuTicks: Long,
    val hostLoad1: Double,
    val memAvailableBytes: Long,
    val healthStatus: String,
    val buildIdentity: String,
    val healthyComponents: Int,
    val totalComponents: Int,
)

@Serializable
data class ConnectionResourceResult(
    val sampleCount: Int,
    val snapshots: List<TeamTalkResourceSnapshot>,
    val stableInvocation: Boolean,
    val stableBuildIdentity: Boolean,
    val allHealthy: Boolean,
    val maxRssBytes: Long,
    val maxThreadCount: Int,
    val maxFdCount: Int,
    val baselineFdCount: Int,
    val finalFdCount: Int,
    val baselineThreadCount: Int,
    val finalThreadCount: Int,
    val passed: Boolean,
)

@Serializable
data class ConnectionCapacityReport(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val target: CapacityTarget,
    val config: ConnectionCapacityConfig,
    val ramp: ConnectionRampResult,
    val hold: ConnectionHoldResult,
    val reconnect: ConnectionReconnectResult,
    val resources: ConnectionResourceResult,
    val passed: Boolean,
    val note: String = "Single-run development baseline; not a product capacity commitment.",
)

data class CapacityMessageIdentity(
    val chatId: String,
    val clientMsgId: String,
)

data class CapacityTrackedMessage(
    val laneId: Int,
    val identity: CapacityMessageIdentity,
)

data class CapacityAcceptedMessage(
    val message: CapacityTrackedMessage,
    val serverSeq: Long,
)

object CapacityFailureCategory {
    const val CLIENT_ACK_TIMEOUT_CODE = -1
    const val BUSY_503 = "busy_503"
    const val TIMEOUT = "timeout"
    const val TRANSPORT = "transport"
    const val UNEXPECTED = "unexpected"
    const val INVALID_ACK_IDENTITY = "invalid_ack_identity"
    const val INVALID_ACK_SEQ = "invalid_ack_seq"

    fun ackCode(code: Int): String = "ack_code_$code"
}

fun classifyCapacityAcknowledgement(
    expectedIdentity: CapacityMessageIdentity,
    actualIdentity: CapacityMessageIdentity,
    serverSeq: Long,
    code: Int,
): String? = when {
    code == CapacityFailureCategory.CLIENT_ACK_TIMEOUT_CODE -> CapacityFailureCategory.TIMEOUT
    code == 503 -> CapacityFailureCategory.BUSY_503
    code != 0 -> CapacityFailureCategory.ackCode(code)
    actualIdentity != expectedIdentity -> CapacityFailureCategory.INVALID_ACK_IDENTITY
    serverSeq <= 0L -> CapacityFailureCategory.INVALID_ACK_SEQ
    else -> null
}

fun isRecoverableCapacityFailure(category: String?): Boolean =
    category == CapacityFailureCategory.BUSY_503 ||
        category == CapacityFailureCategory.TIMEOUT ||
        category == CapacityFailureCategory.TRANSPORT

fun buildCapacityIntegrity(
    laneIds: Set<Int>,
    expectedMessages: Set<CapacityTrackedMessage>,
    acknowledgements: List<CapacityAcceptedMessage>,
    notificationSeqs: Map<CapacityMessageIdentity, List<Long>>,
    historySeqs: Map<CapacityMessageIdentity, List<Long>>,
): CapacityIntegrityResult {
    require(laneIds.isNotEmpty()) { "capacity integrity requires at least one lane" }
    require(expectedMessages.all { it.laneId in laneIds }) {
        "expected capacity message references an unknown lane"
    }
    require(
        expectedMessages.mapTo(hashSetOf(), CapacityTrackedMessage::identity).size ==
            expectedMessages.size,
    ) {
        "capacity messages must have unique chatId + clientMsgId identities"
    }
    require(acknowledgements.all { it.message in expectedMessages && it.serverSeq > 0L }) {
        "capacity acknowledgement references an unknown message or invalid sequence"
    }

    val lanes = laneIds.sorted().map { laneId ->
        val expected = expectedMessages.filterTo(linkedSetOf()) { it.laneId == laneId }
        val ackByIdentity = acknowledgements.asSequence()
            .filter { it.message.laneId == laneId }
            .groupBy(CapacityAcceptedMessage::message)
        val acceptedServerSeqs = ackByIdentity.values.flatten()
            .map(CapacityAcceptedMessage::serverSeq)
        val singleAckSeqByIdentity = ackByIdentity.mapNotNull { (identity, accepted) ->
            accepted.singleOrNull()?.serverSeq?.let { identity to it }
        }.toMap()
        val sortedSeqs = singleAckSeqByIdentity.values.sorted()
        // 每条容量泳道拥有一个新创建、且无其他用途的会话。因此其完整的已接受
        // 序号必须恰好是 1..N；只检查相邻值会让 [4, 5] 这种永久前缀空洞通过。
        val sequenceContiguous = sortedSeqs.size == expected.size &&
            sortedSeqs.withIndex().all { (index, serverSeq) -> serverSeq == index + 1L }
        val notificationMissing = expected.count { notificationSeqs[it.identity].isNullOrEmpty() }
        val notificationDuplicates = expected.count {
            notificationSeqs[it.identity].orEmpty().size > 1
        }
        val historyMissing = expected.count { historySeqs[it.identity].isNullOrEmpty() }
        val historyDuplicates = expected.count { historySeqs[it.identity].orEmpty().size > 1 }
        val ackNotificationConverged = expected.all { message ->
            val serverSeq = singleAckSeqByIdentity[message]
            serverSeq != null && notificationSeqs[message.identity]?.singleOrNull() == serverSeq
        }
        val ackHistoryConverged = expected.all { message ->
            val serverSeq = singleAckSeqByIdentity[message]
            serverSeq != null && historySeqs[message.identity]?.singleOrNull() == serverSeq
        }
        val ackDuplicates = ackByIdentity.values.count { it.size > 1 }
        val passed = singleAckSeqByIdentity.size == expected.size &&
            ackDuplicates == 0 && sequenceContiguous &&
            notificationMissing == 0 && notificationDuplicates == 0 &&
            historyMissing == 0 && historyDuplicates == 0 &&
            ackNotificationConverged && ackHistoryConverged
        CapacityLaneIntegrity(
            laneId = laneId,
            expectedMessages = expected.size,
            ackUniqueMessages = ackByIdentity.size,
            ackDuplicateMessages = ackDuplicates,
            ackUniqueServerSeqs = acceptedServerSeqs.toSet().size,
            serverSeqContiguous = sequenceContiguous,
            notificationUniqueMessages = expected.count {
                notificationSeqs[it.identity]?.size == 1
            },
            notificationMissing = notificationMissing,
            notificationDuplicateMessages = notificationDuplicates,
            historyUniqueMessages = expected.count { historySeqs[it.identity]?.size == 1 },
            historyMissing = historyMissing,
            historyDuplicateMessages = historyDuplicates,
            ackNotificationSeqConverged = ackNotificationConverged,
            ackHistorySeqConverged = ackHistoryConverged,
            passed = passed,
        )
    }
    return CapacityIntegrityResult(
        expectedMessages = expectedMessages.size,
        laneCount = lanes.size,
        ackDuplicateMessages = lanes.sumOf(CapacityLaneIntegrity::ackDuplicateMessages),
        notificationMissing = lanes.sumOf(CapacityLaneIntegrity::notificationMissing),
        notificationDuplicateMessages = lanes.sumOf(
            CapacityLaneIntegrity::notificationDuplicateMessages,
        ),
        historyMissing = lanes.sumOf(CapacityLaneIntegrity::historyMissing),
        historyDuplicateMessages = lanes.sumOf(CapacityLaneIntegrity::historyDuplicateMessages),
        lanes = lanes,
        passed = lanes.all(CapacityLaneIntegrity::passed),
    )
}

fun buildCapacityEventCatchupResult(
    baseCursor: Long,
    targetCursor: Long,
    finalCursor: Long,
    minimumBacklogEvents: Int,
    syncPageSize: Int,
    authenticationCountBefore: Int,
    authenticationCountAfter: Int,
    replayIntegrity: CapacityIntegrityResult,
    localProjectionIntegrity: CapacityIntegrityResult,
    expectedConversations: Int,
    convergedConversations: Int,
    elapsedNanos: Long,
): CapacityEventCatchupResult {
    require(baseCursor >= 0L) { "event catchup base cursor must not be negative" }
    require(targetCursor >= baseCursor) { "event catchup target cursor precedes its base" }
    require(finalCursor >= 0L) { "event catchup final cursor must not be negative" }
    require(syncPageSize > 0) { "event catchup sync page size must be positive" }
    require(minimumBacklogEvents > syncPageSize) {
        "event catchup minimum must prove more than one sync page"
    }
    require(authenticationCountBefore >= 0 && authenticationCountAfter >= 0) {
        "event catchup authentication counts must not be negative"
    }
    require(localProjectionIntegrity.expectedMessages == replayIntegrity.expectedMessages) {
        "event catchup replay and local projection must cover the same messages"
    }
    require(
        localProjectionIntegrity.lanes.map(CapacityLaneIntegrity::laneId) ==
            replayIntegrity.lanes.map(CapacityLaneIntegrity::laneId),
    ) {
        "event catchup replay and local projection must cover the same lanes"
    }
    require(expectedConversations > 0) { "event catchup requires at least one conversation" }
    require(convergedConversations in 0..expectedConversations) {
        "event catchup converged conversation count is invalid"
    }
    require(elapsedNanos >= 0L) { "event catchup elapsed time must not be negative" }

    val backlogEvents = targetCursor - baseCursor
    val minimumReplayPages = if (backlogEvents == 0L) {
        0L
    } else {
        (backlogEvents - 1L) / syncPageSize + 1L
    }
    val authenticationDelta = authenticationCountAfter - authenticationCountBefore
    val replayUniqueMessages = replayIntegrity.lanes.sumOf(
        CapacityLaneIntegrity::notificationUniqueMessages,
    )
    val ackReplaySeqConverged = replayIntegrity.lanes.all(
        CapacityLaneIntegrity::ackNotificationSeqConverged,
    )
    val localProjectionUniqueMessages = localProjectionIntegrity.lanes.sumOf(
        CapacityLaneIntegrity::notificationUniqueMessages,
    )
    val ackLocalProjectionSeqConverged = localProjectionIntegrity.lanes.all(
        CapacityLaneIntegrity::ackNotificationSeqConverged,
    )
    val passed = backlogEvents >= minimumBacklogEvents &&
        minimumReplayPages >= 2L &&
        authenticationDelta == 1 &&
        finalCursor >= targetCursor &&
        replayIntegrity.passed &&
        localProjectionIntegrity.passed &&
        convergedConversations == expectedConversations
    return CapacityEventCatchupResult(
        baseCursor = baseCursor,
        targetCursor = targetCursor,
        finalCursor = finalCursor,
        backlogEvents = backlogEvents,
        minimumBacklogEvents = minimumBacklogEvents,
        syncPageSize = syncPageSize,
        minimumReplayPages = minimumReplayPages,
        authenticationCountBefore = authenticationCountBefore,
        authenticationCountAfter = authenticationCountAfter,
        authenticationDelta = authenticationDelta,
        expectedMessages = replayIntegrity.expectedMessages,
        replayUniqueMessages = replayUniqueMessages,
        replayMissingMessages = replayIntegrity.notificationMissing,
        replayDuplicateMessages = replayIntegrity.notificationDuplicateMessages,
        ackReplaySeqConverged = ackReplaySeqConverged,
        localProjectionUniqueMessages = localProjectionUniqueMessages,
        localProjectionMissingMessages = localProjectionIntegrity.notificationMissing,
        localProjectionDuplicateMessages = localProjectionIntegrity.notificationDuplicateMessages,
        ackLocalProjectionSeqConverged = ackLocalProjectionSeqConverged,
        localProjectionConverged = localProjectionIntegrity.passed,
        expectedConversations = expectedConversations,
        convergedConversations = convergedConversations,
        elapsedMs = elapsedMillis(elapsedNanos),
        eventsPerSecond = capacityEventRatePerSecond(backlogEvents, elapsedNanos),
        passed = passed,
    )
}

fun buildConnectionRampResult(
    attempted: Int,
    authenticated: Int,
    failureCategories: List<String>,
    elapsedNanos: Long,
    readyLatenciesNanos: List<Long>,
): ConnectionRampResult {
    require(attempted >= 0) { "connection ramp attempts must not be negative" }
    require(authenticated in 0..attempted) { "connection ramp authenticated count is invalid" }
    val failed = attempted - authenticated
    require(failureCategories.size == failed) {
        "connection ramp failures must have exactly one category each"
    }
    require(readyLatenciesNanos.size == authenticated) {
        "connection ramp requires one ready latency per authenticated client"
    }
    val readyLatency = summarizeAckLatencies(readyLatenciesNanos)
    val sortedReadyLatencies = readyLatenciesNanos.sorted()
    val latencyPassed = sortedReadyLatencies.isNotEmpty() &&
        nearestRankNanos(sortedReadyLatencies, 95) <= CONNECTION_RAMP_P95_LIMIT_NANOS &&
        nearestRankNanos(sortedReadyLatencies, 99) <= CONNECTION_RAMP_P99_LIMIT_NANOS
    val passed = attempted > 0 &&
        authenticated == attempted &&
        failed == 0 &&
        latencyPassed
    return ConnectionRampResult(
        attempted = attempted,
        authenticated = authenticated,
        failed = failed,
        failuresByCategory = failureCounts(failureCategories),
        elapsedMs = elapsedMillis(elapsedNanos),
        readyLatency = readyLatency,
        passed = passed,
    )
}

fun buildConnectionHoldResult(
    expectedClients: Int,
    authenticatedSamples: List<Int>,
    unexpectedDisconnects: Int,
    unexpectedAuthenticationChanges: Int,
): ConnectionHoldResult {
    require(expectedClients > 0) { "connection hold requires at least one client" }
    require(authenticatedSamples.all { it in 0..expectedClients }) {
        "connection hold authenticated sample is invalid"
    }
    require(unexpectedDisconnects >= 0) {
        "connection hold unexpected disconnects must not be negative"
    }
    require(unexpectedAuthenticationChanges >= 0) {
        "connection hold authentication changes must not be negative"
    }
    val minAuthenticated = authenticatedSamples.minOrNull() ?: 0
    val passed = authenticatedSamples.isNotEmpty() &&
        minAuthenticated == expectedClients &&
        unexpectedDisconnects == 0 &&
        unexpectedAuthenticationChanges == 0
    return ConnectionHoldResult(
        expectedClients = expectedClients,
        sampleCount = authenticatedSamples.size,
        minAuthenticated = minAuthenticated,
        unexpectedDisconnects = unexpectedDisconnects,
        unexpectedAuthenticationChanges = unexpectedAuthenticationChanges,
        passed = passed,
    )
}

fun buildConnectionReconnectResult(
    targeted: Int,
    recovered: Int,
    exactAuthenticationDelta: Int,
    controlClients: Int,
    stableControlClients: Int,
    elapsedNanos: Long,
    latenciesNanos: List<Long>,
): ConnectionReconnectResult {
    require(targeted > 0) { "connection reconnect requires at least one target" }
    require(recovered in 0..targeted) { "connection reconnect recovered count is invalid" }
    require(exactAuthenticationDelta in 0..targeted) {
        "connection reconnect authentication delta count is invalid"
    }
    require(controlClients >= 0) { "connection reconnect control count must not be negative" }
    require(stableControlClients in 0..controlClients) {
        "connection reconnect stable control count is invalid"
    }
    require(latenciesNanos.size == recovered) {
        "connection reconnect requires one latency per recovered client"
    }
    val latency = summarizeAckLatencies(latenciesNanos)
    val sortedLatencies = latenciesNanos.sorted()
    val latencyPassed = sortedLatencies.isNotEmpty() &&
        nearestRankNanos(sortedLatencies, 95) <= CONNECTION_RECONNECT_P95_LIMIT_NANOS &&
        sortedLatencies.last() <= CONNECTION_RECONNECT_MAX_LIMIT_NANOS
    val passed = recovered == targeted &&
        exactAuthenticationDelta == targeted &&
        stableControlClients == controlClients &&
        latencyPassed
    return ConnectionReconnectResult(
        targeted = targeted,
        recovered = recovered,
        exactAuthenticationDelta = exactAuthenticationDelta,
        controlClients = controlClients,
        stableControlClients = stableControlClients,
        elapsedMs = elapsedMillis(elapsedNanos),
        latency = latency,
        passed = passed,
    )
}

/** 第一个样本是基线，最后一个样本是清理后观测。 */
fun summarizeConnectionResources(
    clientCount: Int,
    snapshots: List<TeamTalkResourceSnapshot>,
): ConnectionResourceResult {
    require(clientCount > 0) { "connection resources require at least one client" }
    require(snapshots.size >= 2) {
        "connection resources require baseline and post-cleanup samples"
    }
    require(
        snapshots.all { snapshot ->
            snapshot.mainPid > 0L &&
                snapshot.rssBytes >= 0L &&
                snapshot.threadCount >= 0 &&
                snapshot.fdCount >= 0 &&
                snapshot.cpuTicks >= 0L &&
                snapshot.memAvailableBytes >= 0L &&
                snapshot.healthyComponents in 0..snapshot.totalComponents
        },
    ) { "connection resource sample contains an invalid counter" }

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
            snapshot.totalComponents > 0 &&
            snapshot.healthyComponents == snapshot.totalComponents
    }
    val maxRssBytes = snapshots.maxOf(TeamTalkResourceSnapshot::rssBytes)
    val maxThreadCount = snapshots.maxOf(TeamTalkResourceSnapshot::threadCount)
    val maxFdCount = snapshots.maxOf(TeamTalkResourceSnapshot::fdCount)
    val maxThreadLimit = baseline.threadCount.toLong() + CONNECTION_THREAD_HEADROOM
    val maxFdLimit = baseline.fdCount.toLong() +
        CONNECTION_FDS_PER_CLIENT * clientCount + CONNECTION_FD_FIXED_HEADROOM
    val finalThreadLimit = baseline.threadCount.toLong() + CONNECTION_CLEANUP_HEADROOM
    val finalFdLimit = baseline.fdCount.toLong() + CONNECTION_CLEANUP_HEADROOM
    val passed = stableInvocation &&
        stableBuildIdentity &&
        allHealthy &&
        maxThreadCount <= maxThreadLimit &&
        maxFdCount <= maxFdLimit &&
        final.threadCount <= finalThreadLimit &&
        final.fdCount <= finalFdLimit
    return ConnectionResourceResult(
        sampleCount = snapshots.size,
        snapshots = snapshots.toList(),
        stableInvocation = stableInvocation,
        stableBuildIdentity = stableBuildIdentity,
        allHealthy = allHealthy,
        maxRssBytes = maxRssBytes,
        maxThreadCount = maxThreadCount,
        maxFdCount = maxFdCount,
        baselineFdCount = baseline.fdCount,
        finalFdCount = final.fdCount,
        baselineThreadCount = baseline.threadCount,
        finalThreadCount = final.threadCount,
        passed = passed,
    )
}

fun summarizeAckLatencies(latenciesNanos: List<Long>): CapacityLatencySummary {
    if (latenciesNanos.isEmpty()) return CapacityLatencySummary(sampleCount = 0)
    require(latenciesNanos.all { it >= 0L }) { "latency must not be negative" }
    val sorted = latenciesNanos.sorted()
    fun millis(nanos: Long): Double = round(nanos / 1_000.0) / 1_000.0
    return CapacityLatencySummary(
        sampleCount = sorted.size,
        minMs = millis(sorted.first()),
        p50Ms = millis(nearestRankNanos(sorted, 50)),
        p95Ms = millis(nearestRankNanos(sorted, 95)),
        p99Ms = millis(nearestRankNanos(sorted, 99)),
        maxMs = millis(sorted.last()),
    )
}

private fun nearestRankNanos(sortedLatenciesNanos: List<Long>, percent: Int): Long {
    require(sortedLatenciesNanos.isNotEmpty()) { "latency percentile requires a sample" }
    require(percent in 1..100) { "latency percentile must be between 1 and 100" }
    val index = (
        ceil(percent / 100.0 * sortedLatenciesNanos.size).toInt() - 1
    ).coerceIn(sortedLatenciesNanos.indices)
    return sortedLatenciesNanos[index]
}

fun capacityThroughputPerSecond(attempted: Int, elapsedNanos: Long): Double {
    require(attempted >= 0) { "attempted must not be negative" }
    require(elapsedNanos >= 0L) { "elapsedNanos must not be negative" }
    if (attempted == 0 || elapsedNanos == 0L) return 0.0
    return round((attempted * 1_000_000_000.0 / elapsedNanos) * 1_000.0) / 1_000.0
}

fun capacityEventRatePerSecond(events: Long, elapsedNanos: Long): Double {
    require(events >= 0L) { "events must not be negative" }
    require(elapsedNanos >= 0L) { "elapsedNanos must not be negative" }
    if (events == 0L || elapsedNanos == 0L) return 0.0
    return round((events * 1_000_000_000.0 / elapsedNanos) * 1_000.0) / 1_000.0
}

fun elapsedMillis(elapsedNanos: Long): Double {
    require(elapsedNanos >= 0L) { "elapsedNanos must not be negative" }
    return round(elapsedNanos / 1_000.0) / 1_000.0
}

fun failureCounts(categories: List<String>): Map<String, Int> =
    categories.groupingBy { it }.eachCount().toSortedMap()

private const val CONNECTION_RAMP_P95_LIMIT_NANOS = 5_000_000_000L
private const val CONNECTION_RAMP_P99_LIMIT_NANOS = 10_000_000_000L
private const val CONNECTION_RECONNECT_P95_LIMIT_NANOS = 15_000_000_000L
private const val CONNECTION_RECONNECT_MAX_LIMIT_NANOS = 30_000_000_000L
private const val CONNECTION_THREAD_HEADROOM = 32L
private const val CONNECTION_FDS_PER_CLIENT = 2L
private const val CONNECTION_FD_FIXED_HEADROOM = 64L
private const val CONNECTION_CLEANUP_HEADROOM = 16L

object CapacityReportWriter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun writeAtomically(report: MessageCapacityReport, target: File) {
        writeAtomically(report, MessageCapacityReport.serializer(), target)
    }

    fun writeAtomically(report: ConnectionCapacityReport, target: File) {
        writeAtomically(report, ConnectionCapacityReport.serializer(), target)
    }

    fun writeAtomically(report: SearchCapacityReport, target: File) {
        writeAtomically(report, SearchCapacityReport.serializer(), target)
    }

    fun writeAtomically(report: SearchCapacityRunState, target: File) {
        writeAtomically(report, SearchCapacityRunState.serializer(), target)
    }

    fun writeAtomically(report: AttachmentCapacityReport, target: File) {
        writeAtomically(report, AttachmentCapacityReport.serializer(), target)
    }

    fun writeAtomically(report: AttachmentCapacityRunState, target: File) {
        writeAtomically(report, AttachmentCapacityRunState.serializer(), target)
    }

    fun writeAtomically(report: FileSystemTierCapacityReport, target: File) {
        writeAtomically(report, FileSystemTierCapacityReport.serializer(), target)
    }

    fun writeAtomically(report: FileSystemTierCapacityRunState, target: File) {
        writeAtomically(report, FileSystemTierCapacityRunState.serializer(), target)
    }

    private fun <T> writeAtomically(
        report: T,
        serializer: SerializationStrategy<T>,
        target: File,
    ) {
        val absolute = target.absoluteFile
        val parent = absolute.parentFile ?: error("容量报告路径缺少父目录")
        require(parent.mkdirs() || parent.isDirectory) { "无法创建容量报告目录" }
        val partial = Files.createTempFile(parent.toPath(), ".capacity-", ".json")
        try {
            Files.writeString(partial, json.encodeToString(serializer, report) + "\n")
            try {
                Files.move(
                    partial,
                    absolute.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial, absolute.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
