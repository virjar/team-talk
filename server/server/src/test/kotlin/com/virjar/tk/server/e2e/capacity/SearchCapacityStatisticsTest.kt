package com.virjar.tk.server.e2e.capacity

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchCapacityStatisticsTest {
    @Test
    fun `configuration validates bounded scenario dimensions without defining an SLO`() {
        val config = searchConfig()

        assertEquals(2, config.queryUsers)
        assertEquals(6, config.sharedChats * config.messagesPerChat)
        assertEquals(2, config.globalMarkerCount)
        assertEquals(0L, config.cleanupObservationMs)

        assertFailsWith<IllegalArgumentException> {
            searchConfig(uiBurstConcurrency = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            searchConfig(cleanupObservationMs = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            searchConfig(resultLimit = 11)
        }
        assertFailsWith<IllegalArgumentException> {
            searchConfig(sharedChats = 21, globalMarkerCount = 2, resultLimit = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            searchConfig(uiMessageChats = 3, resultLimit = 2)
        }
        assertEquals(
            7,
            searchConfig(sharedChats = 64, globalMarkerCount = 7).globalMarkerCount,
        )
        assertFailsWith<ArithmeticException> {
            searchConfig(
                sharedChats = Int.MAX_VALUE,
                messagesPerChat = 2,
                globalMarkerCount = Int.MAX_VALUE,
            )
        }
    }

    @Test
    fun `fixture passes only when every requested durable count is exact`() {
        val exact = fixtureResult()
        val missing = fixtureResult(acknowledgedSearchableMessages = 5)

        assertTrue(exact.passed)
        assertFalse(missing.passed)
        assertEquals(6, exact.expectedSearchableMessages)
        assertEquals(1.0, exact.elapsedMs)
    }

    @Test
    fun `message-only scenario records all latency and does not invent a latency gate`() {
        val scenario = buildSearchScenarioResult(
            name = "steady",
            expectsMessageSearch = true,
            expectsUserSearch = false,
            attempts = listOf(
                SearchQueryAttempt(
                    messageLatencyNanos = 60_000_000_000L,
                    cycleLatencyNanos = 60_000_000_000L,
                ),
                SearchQueryAttempt(
                    messageLatencyNanos = 90_000_000_000L,
                    cycleLatencyNanos = 90_000_000_000L,
                ),
            ),
            elapsedNanos = 150_000_000_000L,
        )

        assertTrue(scenario.passed)
        assertEquals(2, scenario.succeededCycles)
        assertEquals(0, scenario.failedCycles)
        assertEquals(2, scenario.message.attempted)
        assertEquals(0, scenario.user.attempted)
        assertEquals(2, scenario.message.latency.sampleCount)
        assertEquals(90_000.0, scenario.message.latency.p99Ms)
        assertEquals(90_000.0, scenario.cycleLatency.p95Ms)
        assertEquals(0, scenario.user.latency.sampleCount)
        assertNull(scenario.user.latency.p99Ms)
    }

    @Test
    fun `UI scenario attributes component failures and result defects without hiding latency`() {
        val scenario = buildSearchScenarioResult(
            name = "ui-burst",
            expectsMessageSearch = true,
            expectsUserSearch = true,
            attempts = listOf(
                SearchQueryAttempt(
                    messageLatencyNanos = 1_000_000L,
                    userLatencyNanos = 2_000_000L,
                    cycleLatencyNanos = 2_000_000L,
                ),
                SearchQueryAttempt(
                    messageLatencyNanos = 5_000_000L,
                    userLatencyNanos = 4_000_000L,
                    cycleLatencyNanos = 6_000_000L,
                    messageFailureCategory = SearchCapacityFailureCategory.rpcStatus(429),
                    userFailureCategory = SearchCapacityFailureCategory.TIMEOUT,
                    exactResultPassed = false,
                    orderingPassed = false,
                ),
            ),
            elapsedNanos = 10_000_000L,
        )

        assertFalse(scenario.passed)
        assertEquals(2, scenario.attemptedCycles)
        assertEquals(1, scenario.succeededCycles)
        assertEquals(1, scenario.failedCycles)
        assertEquals(1, scenario.incorrectCycles)
        assertEquals(1, scenario.exactResultFailures)
        assertEquals(1, scenario.orderingFailures)
        assertEquals(mapOf("status_429" to 1), scenario.message.failuresByCategory)
        assertEquals(mapOf("timeout" to 1), scenario.user.failuresByCategory)
        assertEquals(
            mapOf("message.status_429" to 1, "user.timeout" to 1),
            scenario.failuresByCategory,
        )
        assertEquals(2, scenario.message.latency.sampleCount)
        assertEquals(5.0, scenario.message.latency.p99Ms)
        assertEquals(6.0, scenario.cycleLatency.p99Ms)
        assertEquals(200.0, scenario.attemptedCyclesPerSecond)
        assertEquals(100.0, scenario.succeededCyclesPerSecond)
    }

    @Test
    fun `report binds per-user warmup and steady cycles to total attempts`() {
        val healthy = healthyReport()

        assertEquals(
            healthy.config.queryUsers * healthy.config.warmupCycles,
            healthy.warmup.attemptedCycles,
        )
        assertEquals(
            healthy.config.queryUsers * healthy.config.steadyCycles,
            healthy.steady.attemptedCycles,
        )
        assertFailsWith<IllegalArgumentException> {
            buildSearchCapacityReport(
                generatedAt = healthy.generatedAt,
                target = healthy.target,
                config = healthy.config.copy(warmupCycles = 2),
                fixture = healthy.fixture,
                warmup = healthy.warmup,
                steady = healthy.steady,
                uiBurst = healthy.uiBurst,
                correctness = healthy.correctness,
                freshProbe = healthy.freshProbe,
                sessions = healthy.sessions,
                resources = healthy.resources,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildSearchCapacityReport(
                generatedAt = healthy.generatedAt,
                target = healthy.target,
                config = healthy.config,
                fixture = healthy.fixture,
                warmup = healthy.warmup,
                steady = healthy.steady,
                uiBurst = healthy.uiBurst,
                correctness = healthy.correctness.copy(
                    scopedExact = buildSearchCorrectnessCheck(3, 0),
                ),
                freshProbe = healthy.freshProbe,
                sessions = healthy.sessions,
                resources = healthy.resources,
            )
        }
    }

    @Test
    fun `scenario shape and monotonic cycle duration are enforced`() {
        assertFailsWith<IllegalArgumentException> {
            buildSearchScenarioResult(
                name = "wrong-shape",
                expectsMessageSearch = true,
                expectsUserSearch = true,
                attempts = listOf(
                    SearchQueryAttempt(
                        messageLatencyNanos = 1,
                        cycleLatencyNanos = 1,
                    ),
                ),
                elapsedNanos = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchQueryAttempt(
                messageLatencyNanos = 2,
                cycleLatencyNanos = 1,
            )
        }

        val empty = buildSearchScenarioResult(
            name = "empty",
            expectsMessageSearch = true,
            expectsUserSearch = false,
            attempts = emptyList(),
            elapsedNanos = 0,
        )
        assertFalse(empty.passed)
        assertEquals(0, empty.cycleLatency.sampleCount)
    }

    @Test
    fun `failure categories reject successful RPC status and retain stable names`() {
        assertEquals("timeout", SearchCapacityFailureCategory.TIMEOUT)
        assertEquals("transport", SearchCapacityFailureCategory.TRANSPORT)
        assertEquals("decode", SearchCapacityFailureCategory.DECODE)
        assertEquals("unexpected", SearchCapacityFailureCategory.UNEXPECTED)
        assertEquals("status_503", SearchCapacityFailureCategory.rpcStatus(503))
        assertFailsWith<IllegalArgumentException> {
            SearchCapacityFailureCategory.rpcStatus(0)
        }
    }

    @Test
    fun `correctness result preserves exact ordering foreign and miss attribution`() {
        val healthy = correctnessResult()
        val leaking = buildSearchCorrectnessResult(
            scopedExact = buildSearchCorrectnessCheck(2, 0),
            globalExact = buildSearchCorrectnessCheck(2, 0),
            userExact = buildSearchCorrectnessCheck(1, 0),
            ordering = buildSearchCorrectnessCheck(2, 0),
            foreignIsolation = buildSearchCorrectnessCheck(2, 1),
            miss = buildSearchCorrectnessCheck(2, 0),
        )

        assertTrue(healthy.passed)
        assertFalse(leaking.passed)
        assertEquals(1, leaking.foreignIsolation.failures)
        assertTrue(leaking.scopedExact.passed)
        assertFailsWith<IllegalArgumentException> {
            buildSearchCorrectnessCheck(checks = 1, failures = 2)
        }
    }

    @Test
    fun `fresh post-load message must acknowledge and appear exactly once in both searches`() {
        val exact = freshProbe()
        val duplicate = freshProbe(globalExactMatches = 2)
        val failed = freshProbe(
            attempts = 2,
            failureCategories = listOf(SearchCapacityFailureCategory.rpcStatus(429)),
        )
        val acknowledgementFailed = freshProbe(
            attempts = 0,
            messageAcknowledged = false,
            failureCategories = listOf(SearchCapacityFailureCategory.TRANSPORT),
        )

        assertTrue(exact.passed)
        assertFalse(duplicate.passed)
        assertFalse(failed.passed)
        assertFalse(acknowledgementFailed.passed)
        assertEquals(mapOf("status_429" to 1), failed.failuresByCategory)
        assertEquals(5.0, exact.elapsedMs)
    }

    @Test
    fun `session stability requires the same authenticated generation on every lane`() {
        val stable = buildSearchSessionStabilityResult(
            expectedSessions = 2,
            observations = listOf(
                sessionObservation(laneId = 2),
                sessionObservation(laneId = 1),
            ),
        )
        val changed = buildSearchSessionStabilityResult(
            expectedSessions = 2,
            observations = listOf(
                sessionObservation(laneId = 1),
                sessionObservation(
                    laneId = 2,
                    authenticatedAfter = false,
                    authenticationCountAfter = 2,
                ),
            ),
        )

        assertTrue(stable.passed)
        assertEquals(listOf(1, 2), stable.sessions.map(SearchSessionResult::laneId))
        assertEquals(2, stable.stableSessions)
        assertFalse(changed.passed)
        assertEquals(1, changed.unexpectedDisconnects)
        assertEquals(1, changed.unexpectedAuthenticationChanges)
        assertFailsWith<IllegalArgumentException> {
            buildSearchSessionStabilityResult(
                expectedSessions = 2,
                observations = listOf(sessionObservation(1), sessionObservation(1)),
            )
        }
    }

    @Test
    fun `search resources gate stable process health but only record magnitude`() {
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

        val result = summarizeSearchResources(listOf(baseline, peak, final))

        assertTrue(result.passed)
        assertTrue(result.cpuTicksMonotonic)
        assertEquals(30, result.cpuTicksDelta)
        assertEquals(Long.MAX_VALUE, result.maxRssBytes)
        assertEquals(Int.MAX_VALUE, result.maxThreadCount)
        assertEquals(Int.MAX_VALUE, result.maxFdCount)
        assertEquals(999.0, result.maxHostLoad1)
        assertEquals(0, result.minMemAvailableBytes)

        listOf(
            listOf(baseline, final.copy(invocationId = "another")),
            listOf(baseline, final.copy(mainPid = 43)),
            listOf(baseline, final.copy(buildIdentity = "another")),
            listOf(baseline, final.copy(healthStatus = "DOWN")),
            listOf(baseline, final.copy(healthyComponents = 2)),
            listOf(baseline.copy(cpuTicks = 20), final.copy(cpuTicks = 10)),
        ).forEach { snapshots ->
            assertFalse(summarizeSearchResources(snapshots).passed)
        }
    }

    @Test
    fun `report derives pass without a latency SLO and writer replaces stale JSON atomically`() {
        val report = healthyReport()
        val directory = Files.createTempDirectory("search-capacity-report-test").toFile()
        val target = directory.resolve("nested/search-capacity.json")
        try {
            target.parentFile.mkdirs()
            target.writeText("stale report")

            CapacityReportWriter.writeAtomically(
                SearchCapacityRunState(
                    generatedAt = "2026-01-01T00:00:00Z",
                    runId = report.config.runId,
                    target = report.target,
                    state = "started",
                    phase = "fixture",
                ),
                target,
            )
            assertEquals(
                "started",
                Json.parseToJsonElement(target.readText()).jsonObject
                    .getValue("state").jsonPrimitive.content,
            )

            CapacityReportWriter.writeAtomically(report, target)

            val text = target.readText()
            val parsed = Json.parseToJsonElement(text).jsonObject
            assertTrue(report.passed)
            assertEquals(1, parsed.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertTrue(parsed.getValue("passed").jsonPrimitive.content.toBoolean())
            assertEquals(
                90_000.0,
                parsed.getValue("steady").jsonObject
                    .getValue("cycleLatency").jsonObject
                    .getValue("p99Ms").jsonPrimitive.content.toDouble(),
            )
            assertEquals(
                2,
                parsed.getValue("resources").jsonObject
                    .getValue("snapshots").jsonArray.size,
            )
            assertTrue(parsed.getValue("note").jsonPrimitive.content.contains("not product SLOs"))
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

    @Test
    fun `one scenario failure makes the report fail without rewriting evidence flags`() {
        val healthy = healthyReport()
        val failedSteady = buildSearchScenarioResult(
            name = "steady",
            expectsMessageSearch = true,
            expectsUserSearch = false,
            attempts = listOf(
                SearchQueryAttempt(
                    messageLatencyNanos = 1_000_000,
                    cycleLatencyNanos = 1_000_000,
                    messageFailureCategory = SearchCapacityFailureCategory.TIMEOUT,
                ),
                SearchQueryAttempt(
                    messageLatencyNanos = 1_000_000,
                    cycleLatencyNanos = 1_000_000,
                ),
                SearchQueryAttempt(
                    messageLatencyNanos = 1_000_000,
                    cycleLatencyNanos = 1_000_000,
                ),
                SearchQueryAttempt(
                    messageLatencyNanos = 1_000_000,
                    cycleLatencyNanos = 1_000_000,
                ),
            ),
            elapsedNanos = 4_000_000,
        )

        val failed = buildSearchCapacityReport(
            generatedAt = healthy.generatedAt,
            target = healthy.target,
            config = healthy.config,
            fixture = healthy.fixture,
            warmup = healthy.warmup,
            steady = failedSteady,
            uiBurst = healthy.uiBurst,
            correctness = healthy.correctness,
            freshProbe = healthy.freshProbe,
            sessions = healthy.sessions,
            resources = healthy.resources,
        )

        assertFalse(failed.passed)
        assertFalse(failed.steady.passed)
        assertTrue(failed.correctness.passed)
        assertTrue(failed.resources.passed)
    }

    private fun healthyReport(): SearchCapacityReport {
        val config = searchConfig()
        val warmup = buildSearchScenarioResult(
            name = "warmup",
            expectsMessageSearch = true,
            expectsUserSearch = true,
            attempts = List(2) { queryAttempt(messageMillis = 1.0, userMillis = 2.0) },
            elapsedNanos = 4_000_000,
        )
        val steady = buildSearchScenarioResult(
            name = "steady",
            expectsMessageSearch = true,
            expectsUserSearch = false,
            attempts = listOf(
                queryAttempt(messageMillis = 60_000.0),
                queryAttempt(messageMillis = 90_000.0),
                queryAttempt(messageMillis = 60_000.0),
                queryAttempt(messageMillis = 90_000.0),
            ),
            elapsedNanos = 300_000_000_000,
        )
        val burst = buildSearchScenarioResult(
            name = "ui-burst",
            expectsMessageSearch = true,
            expectsUserSearch = true,
            attempts = listOf(
                queryAttempt(messageMillis = 1.0, userMillis = 2.0),
                queryAttempt(messageMillis = 3.0, userMillis = 4.0),
            ),
            elapsedNanos = 5_000_000,
        )
        return buildSearchCapacityReport(
            generatedAt = "2026-01-01T00:00:00Z",
            target = CapacityTarget("example.test", 5100),
            config = config,
            fixture = fixtureResult(),
            warmup = warmup,
            steady = steady,
            uiBurst = burst,
            correctness = correctnessResult(),
            freshProbe = freshProbe(),
            sessions = buildSearchSessionStabilityResult(
                expectedSessions = 2,
                observations = listOf(sessionObservation(1), sessionObservation(2)),
            ),
            resources = summarizeSearchResources(
                listOf(
                    resourceSnapshot("baseline", cpuTicks = 10),
                    resourceSnapshot("cleanup", cpuTicks = 20),
                ),
            ),
        )
    }

    private fun searchConfig(
        sharedChats: Int = 2,
        messagesPerChat: Int = 3,
        resultLimit: Int = 10,
        globalMarkerCount: Int = minOf(2, sharedChats),
        uiMessageChats: Int = minOf(2, sharedChats, resultLimit),
        uiBurstConcurrency: Int = 2,
        cleanupObservationMs: Long = 0,
    ): SearchCapacityConfig = SearchCapacityConfig(
        runId = "test-run",
        queryUsers = 2,
        sharedChats = sharedChats,
        messagesPerChat = messagesPerChat,
        resultLimit = resultLimit,
        globalMarkerCount = globalMarkerCount,
        uiMessageChats = uiMessageChats,
        warmupCycles = 1,
        steadyCycles = 2,
        steadyIntervalMs = 280,
        steadyMissEvery = 2,
        uiBurstCycles = 2,
        uiBurstConcurrency = uiBurstConcurrency,
        recoveryTimeoutMs = 30_000,
        resourceSampleIntervalMs = 1_000,
        cleanupObservationMs = cleanupObservationMs,
        rpcTimeoutMs = 10_000,
    )

    private fun fixtureResult(
        acknowledgedSearchableMessages: Int = 6,
    ): SearchFixtureResult = buildSearchFixtureResult(
        expectedQuerySessions = 2,
        authenticatedQuerySessions = 2,
        expectedSharedChats = 2,
        createdSharedChats = 2,
        expectedSearchableMessages = 6,
        acknowledgedSearchableMessages = acknowledgedSearchableMessages,
        expectedForeignMessages = 1,
        acknowledgedForeignMessages = 1,
        directoryFixtures = 1,
        elapsedNanos = 1_000_000,
    )

    private fun correctnessResult(): SearchCorrectnessResult = buildSearchCorrectnessResult(
        scopedExact = buildSearchCorrectnessCheck(4, 0),
        globalExact = buildSearchCorrectnessCheck(4, 0),
        userExact = buildSearchCorrectnessCheck(2, 0),
        ordering = buildSearchCorrectnessCheck(9, 0),
        foreignIsolation = buildSearchCorrectnessCheck(3, 0),
        miss = buildSearchCorrectnessCheck(2, 0),
    )

    private fun freshProbe(
        attempts: Int = 1,
        messageAcknowledged: Boolean = true,
        globalExactMatches: Int = 1,
        failureCategories: List<String> = emptyList(),
    ): SearchFreshProbeResult = buildSearchFreshProbeResult(
        attempts = attempts,
        messageAcknowledged = messageAcknowledged,
        scopedExactMatches = 1,
        globalExactMatches = globalExactMatches,
        failureCategories = failureCategories,
        elapsedNanos = 5_000_000,
    )

    private fun queryAttempt(
        messageMillis: Double,
        userMillis: Double? = null,
    ): SearchQueryAttempt {
        val messageNanos = millisToNanos(messageMillis)
        val userNanos = userMillis?.let(::millisToNanos)
        return SearchQueryAttempt(
            messageLatencyNanos = messageNanos,
            userLatencyNanos = userNanos,
            cycleLatencyNanos = maxOf(messageNanos, userNanos ?: 0L),
        )
    }

    private fun sessionObservation(
        laneId: Int,
        authenticatedAfter: Boolean = true,
        authenticationCountAfter: Int = 1,
    ): SearchSessionObservation = SearchSessionObservation(
        laneId = laneId,
        authenticatedBefore = true,
        authenticatedAfter = authenticatedAfter,
        authenticationCountBefore = 1,
        authenticationCountAfter = authenticationCountAfter,
    )

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
