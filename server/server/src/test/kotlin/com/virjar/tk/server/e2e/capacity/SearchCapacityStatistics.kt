package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.protocol.model.Message
import kotlinx.serialization.Serializable

/**
 * 复制进一份有界首屏搜索容量报告的配置。
 * 预热与稳定周期的次数按查询用户计；UI 突发次数是全局的。
 */
@Serializable
data class SearchCapacityConfig(
    val runId: String,
    val queryUsers: Int,
    val sharedChats: Int,
    val messagesPerChat: Int,
    val resultLimit: Int,
    val globalMarkerCount: Int,
    val uiMessageChats: Int,
    val warmupCycles: Int,
    val steadyCycles: Int,
    val steadyIntervalMs: Long,
    val steadyMissEvery: Int,
    val uiBurstCycles: Int,
    val uiBurstConcurrency: Int,
    val recoveryTimeoutMs: Long,
    val resourceSampleIntervalMs: Long,
    val cleanupObservationMs: Long,
    val rpcTimeoutMs: Long,
) {
    init {
        require(runId.isNotBlank()) { "search capacity run id must not be blank" }
        require(queryUsers > 0) { "search capacity requires at least one query user" }
        require(sharedChats > 0) { "search capacity requires at least one shared chat" }
        require(messagesPerChat > 0) { "search capacity messages per chat must be positive" }
        require(resultLimit in 1..Message.MAX_QUERY_PAGE_SIZE) {
            "search capacity result limit must be within the client first-page limit"
        }
        require(globalMarkerCount in 1..sharedChats) {
            "search capacity global marker count must be within the shared chat count"
        }
        require(
            (sharedChats.toLong() + globalMarkerCount - 1L) /
                globalMarkerCount.toLong() <= resultLimit,
        ) {
            "search capacity global marker buckets must fit in one result page"
        }
        require(uiMessageChats in 1..minOf(sharedChats, resultLimit)) {
            "search capacity UI message matches must fit in one result page"
        }
        require(warmupCycles > 0) { "search capacity warmup cycles must be positive" }
        require(steadyCycles > 0) { "search capacity steady cycles must be positive" }
        require(steadyIntervalMs >= 0L) { "search capacity steady interval must not be negative" }
        require(steadyMissEvery in 1..steadyCycles) {
            "search capacity steady miss interval must be within its cycle count"
        }
        require(uiBurstCycles > 0) { "search capacity UI burst cycles must be positive" }
        require(uiBurstConcurrency in 1..uiBurstCycles) {
            "search capacity UI burst concurrency must be within the configured cycle count"
        }
        require(recoveryTimeoutMs > 0L) { "search capacity recovery timeout must be positive" }
        require(resourceSampleIntervalMs > 0L) {
            "search capacity resource sample interval must be positive"
        }
        require(cleanupObservationMs >= 0L) {
            "search capacity cleanup observation must not be negative"
        }
        require(rpcTimeoutMs > 0L) { "search capacity RPC timeout must be positive" }
        Math.multiplyExact(sharedChats, messagesPerChat)
    }
}

/** 实际夹具计数，与请求的配置分开保存。 */
@Serializable
data class SearchFixtureResult(
    val expectedQuerySessions: Int,
    val authenticatedQuerySessions: Int,
    val expectedSharedChats: Int,
    val createdSharedChats: Int,
    val expectedSearchableMessages: Int,
    val acknowledgedSearchableMessages: Int,
    val expectedForeignMessages: Int,
    val acknowledgedForeignMessages: Int,
    val directoryFixtures: Int,
    val elapsedMs: Double,
    val passed: Boolean,
) {
    init {
        require(expectedQuerySessions > 0) { "search fixture requires query sessions" }
        require(authenticatedQuerySessions >= 0) {
            "search fixture authenticated session count must not be negative"
        }
        require(expectedSharedChats > 0) { "search fixture requires shared chats" }
        require(createdSharedChats >= 0) { "search fixture created chat count must not be negative" }
        require(expectedSearchableMessages > 0) {
            "search fixture requires searchable messages"
        }
        require(acknowledgedSearchableMessages >= 0) {
            "search fixture acknowledged message count must not be negative"
        }
        require(expectedForeignMessages > 0) { "search fixture requires foreign messages" }
        require(acknowledgedForeignMessages >= 0) {
            "search fixture acknowledged foreign message count must not be negative"
        }
        require(directoryFixtures > 0) { "search fixture requires a directory fixture" }
        require(elapsedMs >= 0.0 && elapsedMs.isFinite()) {
            "search fixture elapsed time must be finite and non-negative"
        }
        require(
            passed == (
                authenticatedQuerySessions == expectedQuerySessions &&
                    createdSharedChats == expectedSharedChats &&
                    acknowledgedSearchableMessages == expectedSearchableMessages &&
                    acknowledgedForeignMessages == expectedForeignMessages &&
                    directoryFixtures == 1
                ),
        ) { "search fixture pass flag is inconsistent with its counts" }
    }
}

fun buildSearchFixtureResult(
    expectedQuerySessions: Int,
    authenticatedQuerySessions: Int,
    expectedSharedChats: Int,
    createdSharedChats: Int,
    expectedSearchableMessages: Int,
    acknowledgedSearchableMessages: Int,
    expectedForeignMessages: Int,
    acknowledgedForeignMessages: Int,
    directoryFixtures: Int,
    elapsedNanos: Long,
): SearchFixtureResult {
    require(elapsedNanos >= 0L) { "search fixture elapsed time must not be negative" }
    val passed = authenticatedQuerySessions == expectedQuerySessions &&
        createdSharedChats == expectedSharedChats &&
        acknowledgedSearchableMessages == expectedSearchableMessages &&
        acknowledgedForeignMessages == expectedForeignMessages &&
        directoryFixtures == 1
    return SearchFixtureResult(
        expectedQuerySessions = expectedQuerySessions,
        authenticatedQuerySessions = authenticatedQuerySessions,
        expectedSharedChats = expectedSharedChats,
        createdSharedChats = createdSharedChats,
        expectedSearchableMessages = expectedSearchableMessages,
        acknowledgedSearchableMessages = acknowledgedSearchableMessages,
        expectedForeignMessages = expectedForeignMessages,
        acknowledgedForeignMessages = acknowledgedForeignMessages,
        directoryFixtures = directoryFixtures,
        elapsedMs = elapsedMillis(elapsedNanos),
        passed = passed,
    )
}

/** 一个已完成的产品形态查询周期。失败延迟仍然是可用的观测延迟。 */
data class SearchQueryAttempt(
    val messageLatencyNanos: Long?,
    val userLatencyNanos: Long? = null,
    val cycleLatencyNanos: Long,
    val messageFailureCategory: String? = null,
    val userFailureCategory: String? = null,
    val exactResultPassed: Boolean = true,
    val orderingPassed: Boolean = true,
) {
    init {
        require(messageLatencyNanos == null || messageLatencyNanos >= 0L) {
            "message search latency must not be negative"
        }
        require(userLatencyNanos == null || userLatencyNanos >= 0L) {
            "user search latency must not be negative"
        }
        require(cycleLatencyNanos >= 0L) { "search cycle latency must not be negative" }
        require(messageFailureCategory == null || messageFailureCategory.isNotBlank()) {
            "message search failure category must not be blank"
        }
        require(userFailureCategory == null || userFailureCategory.isNotBlank()) {
            "user search failure category must not be blank"
        }
        require(messageLatencyNanos != null || messageFailureCategory == null) {
            "message search failure requires an attempted operation latency"
        }
        require(userLatencyNanos != null || userFailureCategory == null) {
            "user search failure requires an attempted operation latency"
        }
        val longestOperation = maxOf(messageLatencyNanos ?: 0L, userLatencyNanos ?: 0L)
        require(cycleLatencyNanos >= longestOperation) {
            "search cycle latency cannot be shorter than one of its operations"
        }
    }
}

@Serializable
data class SearchOperationResult(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val failuresByCategory: Map<String, Int>,
    /** 包括每个已完成的操作，包括有界错误响应。 */
    val latency: CapacityLatencySummary,
) {
    init {
        require(attempted >= 0 && succeeded >= 0 && failed >= 0) {
            "search operation counts must not be negative"
        }
        require(succeeded + failed == attempted) {
            "search operation successes and failures must partition its attempts"
        }
        require(
            failuresByCategory.all { (category, count) -> category.isNotBlank() && count > 0 } &&
                failuresByCategory.values.sum() == failed,
        ) { "search operation failure histogram is inconsistent with its failures" }
        require(latency.sampleCount == attempted) {
            "search operation requires one observed latency per attempt"
        }
    }
}

@Serializable
data class SearchScenarioResult(
    val name: String,
    val expectsMessageSearch: Boolean,
    val expectsUserSearch: Boolean,
    val attemptedCycles: Int,
    val succeededCycles: Int,
    val failedCycles: Int,
    val incorrectCycles: Int,
    val exactResultFailures: Int,
    val orderingFailures: Int,
    val failuresByCategory: Map<String, Int>,
    val elapsedMs: Double,
    val attemptedCyclesPerSecond: Double,
    val succeededCyclesPerSecond: Double,
    val message: SearchOperationResult,
    val user: SearchOperationResult,
    /** 包括每个已完成的周期；错误仍然通过失败计数可见。 */
    val cycleLatency: CapacityLatencySummary,
    val passed: Boolean,
) {
    /** 源码兼容的展示别名；JSON 使用上方显式的周期字段名。 */
    val attempted: Int get() = attemptedCycles
    val succeeded: Int get() = succeededCycles
    val throughputPerSecond: Double get() = attemptedCyclesPerSecond

    init {
        require(name.isNotBlank()) { "search scenario name must not be blank" }
        require(expectsMessageSearch || expectsUserSearch) {
            "search scenario must execute at least one search operation"
        }
        require(attemptedCycles >= 0) { "search scenario attempts must not be negative" }
        require(succeededCycles in 0..attemptedCycles) {
            "search scenario succeeded cycle count is invalid"
        }
        require(failedCycles in 0..attemptedCycles) {
            "search scenario failed cycle count is invalid"
        }
        require(incorrectCycles in 0..attemptedCycles) {
            "search scenario incorrect cycle count is invalid"
        }
        require(exactResultFailures in 0..attemptedCycles) {
            "search scenario exact-result failure count is invalid"
        }
        require(orderingFailures in 0..attemptedCycles) {
            "search scenario ordering failure count is invalid"
        }
        require(elapsedMs >= 0.0 && elapsedMs.isFinite()) {
            "search scenario elapsed time must be finite and non-negative"
        }
        require(
            attemptedCyclesPerSecond >= 0.0 && attemptedCyclesPerSecond.isFinite() &&
                succeededCyclesPerSecond >= 0.0 && succeededCyclesPerSecond.isFinite(),
        ) {
            "search scenario cycle rates must be finite and non-negative"
        }
        require(cycleLatency.sampleCount == attemptedCycles) {
            "search scenario requires one cycle latency per attempt"
        }
        require(
            failuresByCategory.all { (category, count) -> category.isNotBlank() && count > 0 },
        ) { "search scenario failure categories must be non-blank with positive counts" }
        require(message.attempted == if (expectsMessageSearch) attemptedCycles else 0) {
            "search scenario message attempt count is inconsistent with its shape"
        }
        require(user.attempted == if (expectsUserSearch) attemptedCycles else 0) {
            "search scenario user attempt count is inconsistent with its shape"
        }
        val componentFailures = message.failed + user.failed
        require(failuresByCategory.values.sum() == componentFailures) {
            "search scenario failure histogram is inconsistent with its component failures"
        }
        val enabledComponents = listOf(expectsMessageSearch, expectsUserSearch).count { it }
        require(
            componentFailures >= failedCycles &&
                componentFailures <= failedCycles * enabledComponents,
        ) { "search scenario component failures are inconsistent with its failed cycles" }
        require(exactResultFailures <= incorrectCycles && orderingFailures <= incorrectCycles) {
            "search scenario correctness attribution exceeds its incorrect cycles"
        }
        require(incorrectCycles <= exactResultFailures + orderingFailures) {
            "search scenario incorrect cycles lack exact or ordering attribution"
        }
        val nonSucceededCycles = attemptedCycles - succeededCycles
        require(
            nonSucceededCycles >= maxOf(failedCycles, incorrectCycles) &&
                nonSucceededCycles <= minOf(attemptedCycles, failedCycles + incorrectCycles),
        ) { "search scenario succeeded cycles are inconsistent with its failures" }
        require(
            passed == (
                attemptedCycles > 0 &&
                    succeededCycles == attemptedCycles &&
                    failedCycles == 0 &&
                    incorrectCycles == 0 &&
                    exactResultFailures == 0 &&
                    orderingFailures == 0 &&
                    message.failed == 0 &&
                    user.failed == 0
                ),
        ) { "search scenario pass flag is inconsistent with its results" }
    }
}

fun buildSearchScenarioResult(
    name: String,
    expectsMessageSearch: Boolean,
    expectsUserSearch: Boolean,
    attempts: List<SearchQueryAttempt>,
    elapsedNanos: Long,
): SearchScenarioResult {
    require(name.isNotBlank()) { "search scenario name must not be blank" }
    require(expectsMessageSearch || expectsUserSearch) {
        "search scenario must execute at least one search operation"
    }
    require(elapsedNanos >= 0L) { "search scenario elapsed time must not be negative" }
    attempts.forEach { attempt ->
        require((attempt.messageLatencyNanos != null) == expectsMessageSearch) {
            "search scenario message attempt does not match its declared shape"
        }
        require((attempt.userLatencyNanos != null) == expectsUserSearch) {
            "search scenario user attempt does not match its declared shape"
        }
    }

    val message = buildSearchOperationResult(
        attempts.mapNotNull { attempt ->
            attempt.messageLatencyNanos?.let { latency ->
                latency to attempt.messageFailureCategory
            }
        },
    )
    val user = buildSearchOperationResult(
        attempts.mapNotNull { attempt ->
            attempt.userLatencyNanos?.let { latency -> latency to attempt.userFailureCategory }
        },
    )
    val failedCycles = attempts.count { attempt ->
        attempt.messageFailureCategory != null || attempt.userFailureCategory != null
    }
    val exactResultFailures = attempts.count { attempt -> !attempt.exactResultPassed }
    val orderingFailures = attempts.count { attempt -> !attempt.orderingPassed }
    val incorrectCycles = attempts.count { attempt ->
        !attempt.exactResultPassed || !attempt.orderingPassed
    }
    val succeededCycles = attempts.count { attempt ->
        attempt.messageFailureCategory == null &&
            attempt.userFailureCategory == null &&
            attempt.exactResultPassed &&
            attempt.orderingPassed
    }
    val combinedFailures = buildList {
        attempts.forEach { attempt ->
            attempt.messageFailureCategory?.let { add("message.$it") }
            attempt.userFailureCategory?.let { add("user.$it") }
        }
    }
    return SearchScenarioResult(
        name = name,
        expectsMessageSearch = expectsMessageSearch,
        expectsUserSearch = expectsUserSearch,
        attemptedCycles = attempts.size,
        succeededCycles = succeededCycles,
        failedCycles = failedCycles,
        incorrectCycles = incorrectCycles,
        exactResultFailures = exactResultFailures,
        orderingFailures = orderingFailures,
        failuresByCategory = failureCounts(combinedFailures),
        elapsedMs = elapsedMillis(elapsedNanos),
        attemptedCyclesPerSecond = capacityThroughputPerSecond(attempts.size, elapsedNanos),
        succeededCyclesPerSecond = capacityThroughputPerSecond(succeededCycles, elapsedNanos),
        message = message,
        user = user,
        cycleLatency = summarizeAckLatencies(attempts.map(SearchQueryAttempt::cycleLatencyNanos)),
        passed = attempts.isNotEmpty() && failedCycles == 0 && incorrectCycles == 0,
    )
}

private fun buildSearchOperationResult(
    observations: List<Pair<Long, String?>>,
): SearchOperationResult {
    val failures = observations.mapNotNull { observation -> observation.second }
    return SearchOperationResult(
        attempted = observations.size,
        succeeded = observations.size - failures.size,
        failed = failures.size,
        failuresByCategory = failureCounts(failures),
        latency = summarizeAckLatencies(observations.map { observation -> observation.first }),
    )
}

object SearchCapacityFailureCategory {
    const val TIMEOUT = "timeout"
    const val TRANSPORT = "transport"
    const val DECODE = "decode"
    const val UNEXPECTED = "unexpected"

    fun rpcStatus(status: Int): String {
        require(status != 0) { "successful RPC status is not a failure category" }
        return "status_$status"
    }
}

/**
 * 在远端运行开始前写入、并由最终报告替换的持久标记。如果夹具或基础设施
 * 搭建失败，调用方仍然能拿到机器可读的 run id 与 phase。
 */
@Serializable
data class SearchCapacityRunState(
    val schemaVersion: Int = 1,
    val reportType: String = "search-capacity-run-state",
    val generatedAt: String,
    val runId: String,
    val target: CapacityTarget,
    val state: String,
    val phase: String,
    val failureType: String? = null,
    val failureMessage: String? = null,
) {
    init {
        require(schemaVersion == 1) { "unsupported search run-state schema" }
        require(reportType == "search-capacity-run-state") {
            "unsupported search run-state report type"
        }
        require(generatedAt.isNotBlank()) { "search run-state timestamp must not be blank" }
        require(runId.isNotBlank()) { "search run-state id must not be blank" }
        require(state == "started" || state == "failed") { "invalid search run state" }
        require(phase.isNotBlank()) { "search run-state phase must not be blank" }
        require((state == "failed") == (failureType != null)) {
            "failed search run state requires a failure type"
        }
        require(failureType == null || failureType.isNotBlank()) {
            "search run-state failure type must not be blank"
        }
        require(failureMessage == null || failureMessage.isNotBlank()) {
            "search run-state failure message must not be blank"
        }
    }
}

@Serializable
data class SearchCorrectnessCheck(
    val checks: Int,
    val failures: Int,
    val passed: Boolean,
) {
    init {
        require(checks > 0) { "search correctness check requires at least one observation" }
        require(failures in 0..checks) { "search correctness failure count is invalid" }
        require(passed == (failures == 0)) {
            "search correctness pass flag is inconsistent with its failures"
        }
    }
}

fun buildSearchCorrectnessCheck(checks: Int, failures: Int): SearchCorrectnessCheck =
    SearchCorrectnessCheck(checks, failures, failures == 0)

@Serializable
data class SearchCorrectnessResult(
    val scopedExact: SearchCorrectnessCheck,
    val globalExact: SearchCorrectnessCheck,
    val userExact: SearchCorrectnessCheck,
    val ordering: SearchCorrectnessCheck,
    val foreignIsolation: SearchCorrectnessCheck,
    val miss: SearchCorrectnessCheck,
    val passed: Boolean,
) {
    init {
        require(
            passed == listOf(
                scopedExact,
                globalExact,
                userExact,
                ordering,
                foreignIsolation,
                miss,
            ).all(SearchCorrectnessCheck::passed),
        ) { "search correctness pass flag is inconsistent with its checks" }
    }
}

fun buildSearchCorrectnessResult(
    scopedExact: SearchCorrectnessCheck,
    globalExact: SearchCorrectnessCheck,
    userExact: SearchCorrectnessCheck,
    ordering: SearchCorrectnessCheck,
    foreignIsolation: SearchCorrectnessCheck,
    miss: SearchCorrectnessCheck,
): SearchCorrectnessResult = SearchCorrectnessResult(
    scopedExact = scopedExact,
    globalExact = globalExact,
    userExact = userExact,
    ordering = ordering,
    foreignIsolation = foreignIsolation,
    miss = miss,
    passed = listOf(
        scopedExact,
        globalExact,
        userExact,
        ordering,
        foreignIsolation,
        miss,
    ).all(SearchCorrectnessCheck::passed),
)

@Serializable
data class SearchFreshProbeResult(
    val attempts: Int,
    val failedOperations: Int,
    val failuresByCategory: Map<String, Int>,
    val messageAcknowledged: Boolean,
    val scopedExactMatches: Int,
    val globalExactMatches: Int,
    val elapsedMs: Double,
    val passed: Boolean,
) {
    init {
        require(attempts >= 0) { "fresh search probe attempt count must not be negative" }
        require(failedOperations >= 0) { "fresh search probe failure count is invalid" }
        require(
            failuresByCategory.all { (category, count) -> category.isNotBlank() && count > 0 },
        ) { "fresh search probe failure categories must be non-blank with positive counts" }
        require(failuresByCategory.values.sum() == failedOperations) {
            "fresh search probe operations must have exactly one failure category each"
        }
        require(scopedExactMatches >= 0 && globalExactMatches >= 0) {
            "fresh search probe match counts must not be negative"
        }
        require(elapsedMs >= 0.0 && elapsedMs.isFinite()) {
            "fresh search probe elapsed time must be finite and non-negative"
        }
        require(
            passed == (
                attempts > 0 &&
                    failedOperations == 0 &&
                    messageAcknowledged &&
                    scopedExactMatches == 1 &&
                    globalExactMatches == 1
                ),
        ) { "fresh search probe pass flag is inconsistent with its result" }
    }
}

fun buildSearchFreshProbeResult(
    attempts: Int,
    messageAcknowledged: Boolean,
    scopedExactMatches: Int,
    globalExactMatches: Int,
    failureCategories: List<String>,
    elapsedNanos: Long,
): SearchFreshProbeResult {
    require(failureCategories.all { category -> category.isNotBlank() }) {
        "fresh search probe failure category must not be blank"
    }
    require(elapsedNanos >= 0L) { "fresh search probe elapsed time must not be negative" }
    val passed = attempts > 0 &&
        failureCategories.isEmpty() &&
        messageAcknowledged &&
        scopedExactMatches == 1 &&
        globalExactMatches == 1
    return SearchFreshProbeResult(
        attempts = attempts,
        failedOperations = failureCategories.size,
        failuresByCategory = failureCounts(failureCategories),
        messageAcknowledged = messageAcknowledged,
        scopedExactMatches = scopedExactMatches,
        globalExactMatches = globalExactMatches,
        elapsedMs = elapsedMillis(elapsedNanos),
        passed = passed,
    )
}

data class SearchSessionObservation(
    val laneId: Int,
    val authenticatedBefore: Boolean,
    val authenticatedAfter: Boolean,
    val authenticationCountBefore: Int,
    val authenticationCountAfter: Int,
) {
    init {
        require(laneId >= 0) { "search session lane id must not be negative" }
        require(authenticationCountBefore >= 0 && authenticationCountAfter >= 0) {
            "search session authentication counts must not be negative"
        }
        require(authenticationCountAfter >= authenticationCountBefore) {
            "search session authentication count must be monotonic"
        }
    }
}

@Serializable
data class SearchSessionResult(
    val laneId: Int,
    val authenticatedBefore: Boolean,
    val authenticatedAfter: Boolean,
    val authenticationCountBefore: Int,
    val authenticationCountAfter: Int,
    val authenticationDelta: Int,
    val passed: Boolean,
)

@Serializable
data class SearchSessionStabilityResult(
    val expectedSessions: Int,
    val observedSessions: Int,
    val stableSessions: Int,
    val unexpectedDisconnects: Int,
    val unexpectedAuthenticationChanges: Int,
    val sessions: List<SearchSessionResult>,
    val passed: Boolean,
) {
    init {
        require(expectedSessions > 0) { "search session stability requires sessions" }
        require(observedSessions >= 0 && stableSessions in 0..observedSessions) {
            "search session stability counts are invalid"
        }
        require(unexpectedDisconnects in 0..observedSessions) {
            "search session disconnect count is invalid"
        }
        require(unexpectedAuthenticationChanges in 0..observedSessions) {
            "search session authentication change count is invalid"
        }
        require(sessions.size == observedSessions) {
            "search session detail count does not match the observed count"
        }
        require(
            passed == (
                observedSessions == expectedSessions &&
                    stableSessions == expectedSessions &&
                    unexpectedDisconnects == 0 &&
                    unexpectedAuthenticationChanges == 0
                ),
        ) { "search session stability pass flag is inconsistent with its observations" }
    }
}

fun buildSearchSessionStabilityResult(
    expectedSessions: Int,
    observations: List<SearchSessionObservation>,
): SearchSessionStabilityResult {
    require(observations.mapTo(hashSetOf(), SearchSessionObservation::laneId).size ==
        observations.size) { "search session observations must use distinct lane ids" }
    val sessions = observations.sortedBy(SearchSessionObservation::laneId).map { observation ->
        val authenticationDelta = observation.authenticationCountAfter -
            observation.authenticationCountBefore
        SearchSessionResult(
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
    val unexpectedAuthenticationChanges = sessions.count { it.authenticationDelta != 0 }
    val stableSessions = sessions.count(SearchSessionResult::passed)
    val passed = sessions.size == expectedSessions &&
        stableSessions == expectedSessions &&
        unexpectedDisconnects == 0 &&
        unexpectedAuthenticationChanges == 0
    return SearchSessionStabilityResult(
        expectedSessions = expectedSessions,
        observedSessions = sessions.size,
        stableSessions = stableSessions,
        unexpectedDisconnects = unexpectedDisconnects,
        unexpectedAuthenticationChanges = unexpectedAuthenticationChanges,
        sessions = sessions,
        passed = passed,
    )
}

@Serializable
data class SearchResourceResult(
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
    val baselineThreadCount: Int,
    val finalThreadCount: Int,
    val baselineFdCount: Int,
    val finalFdCount: Int,
    val passed: Boolean,
) {
    init {
        require(sampleCount == snapshots.size && sampleCount >= 2) {
            "search resources require matching baseline and final samples"
        }
        require(cpuTicksDelta >= 0L) { "search resource CPU delta must not be negative" }
        require(maxHostLoad1 >= 0.0 && maxHostLoad1.isFinite()) {
            "search resource maximum host load must be finite and non-negative"
        }
        require(
            passed == (stableInvocation && stableBuildIdentity && allHealthy && cpuTicksMonotonic),
        ) { "search resource pass flag may only depend on stable process health" }
    }
}

/** 记录资源高水位，而不是凭空捏造首轮 RSS、FD 或延迟 SLO。 */
fun summarizeSearchResources(
    snapshots: List<TeamTalkResourceSnapshot>,
): SearchResourceResult {
    require(snapshots.size >= 2) {
        "search resources require baseline and final samples"
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
                snapshot.healthyComponents in 0..snapshot.totalComponents
        },
    ) { "search resource sample contains an invalid counter" }

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
    val cpuTicksMonotonic = snapshots.zipWithNext().all { (before, after) ->
        after.cpuTicks >= before.cpuTicks
    }
    val cpuTicksDelta = if (cpuTicksMonotonic) final.cpuTicks - baseline.cpuTicks else 0L
    return SearchResourceResult(
        sampleCount = snapshots.size,
        snapshots = snapshots.toList(),
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
        baselineThreadCount = baseline.threadCount,
        finalThreadCount = final.threadCount,
        baselineFdCount = baseline.fdCount,
        finalFdCount = final.fdCount,
        passed = stableInvocation && stableBuildIdentity && allHealthy && cpuTicksMonotonic,
    )
}

@Serializable
data class SearchCapacityReport(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val target: CapacityTarget,
    val config: SearchCapacityConfig,
    val fixture: SearchFixtureResult,
    val warmup: SearchScenarioResult,
    val steady: SearchScenarioResult,
    val uiBurst: SearchScenarioResult,
    val correctness: SearchCorrectnessResult,
    val freshProbe: SearchFreshProbeResult,
    val sessions: SearchSessionStabilityResult,
    val resources: SearchResourceResult,
    val passed: Boolean,
    val note: String = "Single-run first-screen search development baseline; " +
        "latency values are observations, not product SLOs.",
) {
    init {
        require(schemaVersion == 1) { "unsupported search capacity report schema" }
        require(generatedAt.isNotBlank()) { "search capacity report timestamp must not be blank" }
        require(
            passed == (
                fixture.passed &&
                    warmup.passed &&
                    steady.passed &&
                    uiBurst.passed &&
                    correctness.passed &&
                    freshProbe.passed &&
                    sessions.passed &&
                    resources.passed
                ),
        ) { "search capacity report pass flag is inconsistent with its evidence" }
    }
}

fun buildSearchCapacityReport(
    generatedAt: String,
    target: CapacityTarget,
    config: SearchCapacityConfig,
    fixture: SearchFixtureResult,
    warmup: SearchScenarioResult,
    steady: SearchScenarioResult,
    uiBurst: SearchScenarioResult,
    correctness: SearchCorrectnessResult,
    freshProbe: SearchFreshProbeResult,
    sessions: SearchSessionStabilityResult,
    resources: SearchResourceResult,
): SearchCapacityReport {
    require(fixture.expectedQuerySessions == config.queryUsers) {
        "search fixture query-session target differs from report configuration"
    }
    require(fixture.expectedSharedChats == config.sharedChats) {
        "search fixture chat target differs from report configuration"
    }
    require(
        fixture.expectedSearchableMessages == Math.multiplyExact(
            config.sharedChats,
            config.messagesPerChat,
        ),
    ) { "search fixture message target differs from report configuration" }
    require(
        warmup.attemptedCycles == Math.multiplyExact(config.queryUsers, config.warmupCycles),
    ) {
        "search warmup result differs from its per-user report configuration"
    }
    require(
        steady.attemptedCycles == Math.multiplyExact(config.queryUsers, config.steadyCycles),
    ) {
        "search steady result differs from report configuration"
    }
    require(uiBurst.attemptedCycles == config.uiBurstCycles) {
        "search UI burst result differs from report configuration"
    }
    require(uiBurst.expectsMessageSearch && uiBurst.expectsUserSearch) {
        "search UI burst must execute the product message and user requests"
    }
    require(warmup.expectsMessageSearch && steady.expectsMessageSearch) {
        "search warmup and steady scenarios must execute message search"
    }
    require(sessions.expectedSessions == config.queryUsers) {
        "search session stability target differs from report configuration"
    }
    require(
        correctness.scopedExact.checks == Math.multiplyExact(
            config.queryUsers,
            config.sharedChats,
        ),
    ) { "search scoped correctness matrix is incomplete" }
    require(
        correctness.globalExact.checks == Math.multiplyExact(
            config.queryUsers,
            config.globalMarkerCount,
        ),
    ) { "search global correctness matrix is incomplete" }
    require(correctness.userExact.checks == config.queryUsers) {
        "search directory correctness matrix is incomplete"
    }
    require(
        correctness.ordering.checks == Math.addExact(
            Math.multiplyExact(
                config.queryUsers,
                Math.addExact(config.sharedChats, config.globalMarkerCount),
            ),
            1,
        ),
    ) { "search ordering correctness matrix is incomplete" }
    require(correctness.foreignIsolation.checks == config.queryUsers + 1) {
        "search foreign-isolation correctness matrix is incomplete"
    }
    require(correctness.miss.checks == config.queryUsers) {
        "search miss correctness matrix is incomplete"
    }
    val passed = fixture.passed &&
        warmup.passed &&
        steady.passed &&
        uiBurst.passed &&
        correctness.passed &&
        freshProbe.passed &&
        sessions.passed &&
        resources.passed
    return SearchCapacityReport(
        generatedAt = generatedAt,
        target = target,
        config = config,
        fixture = fixture,
        warmup = warmup,
        steady = steady,
        uiBurst = uiBurst,
        correctness = correctness,
        freshProbe = freshProbe,
        sessions = sessions,
        resources = resources,
        passed = passed,
    )
}
