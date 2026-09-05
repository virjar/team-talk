package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.server.e2e.RemoteAcceptanceSupport
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * Explicit real-client connection baseline for the deployed development server.
 *
 * Account creation happens before the measured ramp. The ramp, hold, and reconnect phases then
 * exercise ordinary SDK sessions only. Offline simulation closes the selected clients' transports;
 * it never changes host networking or any process-wide network setting.
 */
class RemoteConnectionCapacityBaselineTest {
    @Test
    fun `connections authenticate remain stable and recover independently`() = runBlocking {
        val config = ConnectionConfig.fromSystemProperties()
        val sampler = RemoteTeamTalkResourceSampler()
        val sessions = mutableListOf<RemoteAcceptanceSupport.Session>()
        val resourceSnapshots = mutableListOf<TeamTalkResourceSnapshot>()
        val runId = UUID.randomUUID().toString().replace("-", "").take(12)

        val credentials = prepareAccounts(config, runId)
        // Session destruction is asynchronous on each client's EventLoop. Give the fixture phase
        // a short, fixed drain before measuring the server-side connection baseline.
        delay(FIXTURE_SETTLE_MILLIS)
        resourceSnapshots += sampler.sample("baseline", Instant.now().toString())

        lateinit var ramp: ConnectionRampResult
        lateinit var hold: ConnectionHoldResult
        lateinit var reconnect: ConnectionReconnectResult
        try {
            val rampRun = runRamp(config, credentials, runId, sessions)
            ramp = buildConnectionRampResult(
                attempted = config.clientCount,
                authenticated = sessions.size,
                failureCategories = rampRun.failureCategories,
                elapsedNanos = rampRun.elapsedNanos,
                readyLatenciesNanos = rampRun.readyLatenciesNanos,
            )
            resourceSnapshots += sampler.sample("ramp", Instant.now().toString())

            hold = runHold(config, sessions, sampler, resourceSnapshots)
            reconnect = runReconnect(config, sessions)
            resourceSnapshots += sampler.sample("reconnect", Instant.now().toString())
        } finally {
            sessions.asReversed().forEach { session -> runCatching(session::close) }
        }

        delay(config.cleanupObservationMs)
        resourceSnapshots += sampler.sample("cleanup", Instant.now().toString())
        val resources = summarizeConnectionResources(config.clientCount, resourceSnapshots)
        val report = ConnectionCapacityReport(
            generatedAt = Instant.now().toString(),
            target = CapacityTarget(RemoteAcceptanceSupport.host, RemoteAcceptanceSupport.port),
            config = config.reportConfig,
            ramp = ramp,
            hold = hold,
            reconnect = reconnect,
            resources = resources,
            passed = ramp.passed && hold.passed && reconnect.passed && resources.passed,
        )
        CapacityReportWriter.writeAtomically(report, config.reportFile)
        println("[ConnectionCapacity] report=${config.reportFile.absolutePath}")
        println(
            "[ConnectionCapacity] clients=${ramp.authenticated}/${ramp.attempted} " +
                "rampP95Ms=${ramp.readyLatency.p95Ms} rampP99Ms=${ramp.readyLatency.p99Ms} " +
                "holdMin=${hold.minAuthenticated} reconnect=${reconnect.recovered}/" +
                "${reconnect.targeted} reconnectP95Ms=${reconnect.latency.p95Ms} " +
                "stableControls=${reconnect.stableControlClients}/${reconnect.controlClients} " +
                "threads=${resources.baselineThreadCount}->${resources.maxThreadCount}" +
                "->${resources.finalThreadCount} fds=${resources.baselineFdCount}" +
                "->${resources.maxFdCount}->${resources.finalFdCount}",
        )
        check(report.passed) {
            "Remote connection capacity baseline failed; inspect the machine-readable report"
        }
    }

    private suspend fun prepareAccounts(
        config: ConnectionConfig,
        runId: String,
    ): List<Credential> {
        val credentials = mutableListOf<Credential>()
        repeat(config.clientCount) { index ->
            // Registration is fixture preparation, not the measured connection ramp. Keep it
            // serial so a small deployment's BCrypt/IO concurrency ceiling cannot manufacture a
            // capacity failure before the baseline starts; the shared pacer still respects the
            // production registration source window.
            val session = RemoteAcceptanceSupport.registerUser(
                suffix = "conn-cap-$index",
                password = CAPACITY_FIXTURE_PASSWORD,
                deviceId = "conn-cap-setup-$runId-$index",
                deviceName = "Connection capacity setup $index",
            )
            try {
                credentials += Credential(
                    username = checkNotNull(session.registeredUsername) {
                        "Connection capacity registration omitted its fixture username"
                    },
                    password = CAPACITY_FIXTURE_PASSWORD,
                )
            } finally {
                session.close()
            }
        }
        return credentials
    }

    private suspend fun runRamp(
        config: ConnectionConfig,
        credentials: List<Credential>,
        runId: String,
        sessions: MutableList<RemoteAcceptanceSupport.Session>,
    ): RampRun {
        val started = System.nanoTime()
        val latencies = mutableListOf<Long>()
        val failures = mutableListOf<String>()
        credentials.chunked(config.rampGroupSize).forEachIndexed { groupIndex, group ->
            val attempts = coroutineScope {
                group.mapIndexed { offset, credential ->
                    val clientIndex = groupIndex * config.rampGroupSize + offset
                    async { loginOne(credential, runId, clientIndex) }
                }.awaitAll()
            }
            attempts.forEach { attempt ->
                val session = attempt.session
                if (session != null) {
                    sessions += session
                    latencies += checkNotNull(attempt.latencyNanos)
                } else {
                    failures += checkNotNull(attempt.failureCategory)
                }
            }
            if ((groupIndex + 1) * config.rampGroupSize < credentials.size) {
                // The server window starts when its first AUTH arrives, not when this test starts
                // creating sockets. A full post-group interval keeps actual AUTH groups separated
                // even when connection establishment inside a group is slow.
                delay(config.rampIntervalMs)
            }
        }
        return RampRun(
            readyLatenciesNanos = latencies,
            failureCategories = failures,
            elapsedNanos = System.nanoTime() - started,
        )
    }

    private suspend fun loginOne(
        credential: Credential,
        runId: String,
        clientIndex: Int,
    ): LoginAttempt {
        val started = System.nanoTime()
        return try {
            val session = RemoteAcceptanceSupport.loginUser(
                username = credential.username,
                password = credential.password,
                deviceId = "conn-cap-$runId-$clientIndex",
                deviceName = "Connection capacity client $clientIndex",
            )
            LoginAttempt(
                session = session,
                latencyNanos = System.nanoTime() - started,
                failureCategory = null,
            )
        } catch (failure: TimeoutCancellationException) {
            LoginAttempt(null, null, CapacityFailureCategory.TIMEOUT)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: TransportUnavailableException) {
            LoginAttempt(null, null, CapacityFailureCategory.TRANSPORT)
        } catch (failure: IOException) {
            LoginAttempt(null, null, CapacityFailureCategory.TRANSPORT)
        } catch (failure: Throwable) {
            LoginAttempt(null, null, CapacityFailureCategory.UNEXPECTED)
        }
    }

    private suspend fun runHold(
        config: ConnectionConfig,
        sessions: List<RemoteAcceptanceSupport.Session>,
        sampler: RemoteTeamTalkResourceSampler,
        resourceSnapshots: MutableList<TeamTalkResourceSnapshot>,
    ): ConnectionHoldResult {
        val authenticationCounts = sessions.map(RemoteAcceptanceSupport.Session::authenticationCount)
        val disconnected = mutableSetOf<Int>()
        val authenticationChanged = mutableSetOf<Int>()
        val authenticatedSamples = mutableListOf<Int>()
        val deadline = System.nanoTime() + config.holdDurationMs * NANOS_PER_MILLISECOND
        var sampleIndex = 0
        while (true) {
            authenticatedSamples += sessions.count { session ->
                session.imClient.state.value == ConnectionState.AUTHENTICATED
            }
            sessions.forEachIndexed { index, session ->
                if (session.imClient.state.value != ConnectionState.AUTHENTICATED) disconnected += index
                if (session.authenticationCount != authenticationCounts[index]) {
                    authenticationChanged += index
                }
            }
            resourceSnapshots += sampler.sample("hold-$sampleIndex", Instant.now().toString())
            sampleIndex += 1
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            delay(
                minOf(
                    config.sampleIntervalMs,
                    (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
                ),
            )
        }
        return buildConnectionHoldResult(
            expectedClients = config.clientCount,
            authenticatedSamples = authenticatedSamples,
            unexpectedDisconnects = disconnected.size,
            unexpectedAuthenticationChanges = authenticationChanged.size,
        )
    }

    private suspend fun runReconnect(
        config: ConnectionConfig,
        sessions: List<RemoteAcceptanceSupport.Session>,
    ): ConnectionReconnectResult = coroutineScope {
        val targets = sessions.take(config.reconnectCount)
        val controls = sessions.drop(config.reconnectCount)
        val targetAuthenticationCounts = targets.map(RemoteAcceptanceSupport.Session::authenticationCount)
        val controlAuthenticationCounts = controls.map(RemoteAcceptanceSupport.Session::authenticationCount)
        val stableControls = BooleanArray(controls.size) { true }
        val controlMonitor = launch {
            while (isActive) {
                controls.forEachIndexed { index, session ->
                    if (
                        session.imClient.state.value != ConnectionState.AUTHENTICATED ||
                        session.authenticationCount != controlAuthenticationCounts[index]
                    ) {
                        stableControls[index] = false
                    }
                }
                delay(CONTROL_SAMPLE_INTERVAL_MILLIS)
            }
        }

        val disconnected: List<Boolean>
        val reconnectStarted: Long
        val latencies: List<Long>
        try {
            targets.forEach { session -> session.imClient.simulateNetworkDropAndPauseReconnect() }
            disconnected = targets.map { session ->
                async {
                    withTimeoutOrNull(config.reconnectTimeoutMs) {
                        session.imClient.state.first { state -> state == ConnectionState.DISCONNECTED }
                        true
                    } == true
                }
            }.awaitAll()

            reconnectStarted = System.nanoTime()
            targets.forEach { session -> session.imClient.resumeReconnectAfterSimulatedDrop() }
            latencies = targets.mapIndexed { index, session ->
                async {
                    if (!disconnected[index]) return@async null
                    val recovered = try {
                        session.awaitAuthenticationAfter(
                            previousCount = targetAuthenticationCounts[index],
                            timeoutMs = config.reconnectTimeoutMs,
                        )
                        true
                    } catch (_: TimeoutCancellationException) {
                        // Session.awaitAuthenticationAfter owns the timeout. An outer
                        // withTimeoutOrNull cannot consume that nested timeout identity and would
                        // abort the whole test instead of recording an unrecovered target.
                        false
                    }
                    if (recovered) System.nanoTime() - reconnectStarted else null
                }
            }.awaitAll().filterNotNull()
        } finally {
            // Idempotent and scoped to these logical clients; also releases a paused target when a
            // disconnect observation or later assertion fails.
            targets.forEach { session -> session.imClient.resumeReconnectAfterSimulatedDrop() }
            controlMonitor.cancelAndJoin()
        }

        controls.forEachIndexed { index, session ->
            if (
                session.imClient.state.value != ConnectionState.AUTHENTICATED ||
                session.authenticationCount != controlAuthenticationCounts[index]
            ) {
                stableControls[index] = false
            }
        }
        val exactAuthenticationDelta = targets.indices.count { index ->
            targets[index].authenticationCount == targetAuthenticationCounts[index] + 1
        }
        buildConnectionReconnectResult(
            targeted = config.reconnectCount,
            recovered = latencies.size,
            exactAuthenticationDelta = exactAuthenticationDelta,
            controlClients = config.clientCount - config.reconnectCount,
            stableControlClients = stableControls.count { stable -> stable },
            elapsedNanos = System.nanoTime() - reconnectStarted,
            latenciesNanos = latencies,
        )
    }

    private data class Credential(val username: String, val password: String)

    private data class LoginAttempt(
        val session: RemoteAcceptanceSupport.Session?,
        val latencyNanos: Long?,
        val failureCategory: String?,
    )

    private data class RampRun(
        val readyLatenciesNanos: List<Long>,
        val failureCategories: List<String>,
        val elapsedNanos: Long,
    )

    private data class ConnectionConfig(
        val clientCount: Int,
        val rampGroupSize: Int,
        val rampIntervalMs: Long,
        val holdDurationMs: Long,
        val reconnectCount: Int,
        val reconnectTimeoutMs: Long,
        val sampleIntervalMs: Long,
        val cleanupObservationMs: Long,
        val reportFile: File,
    ) {
        val reportConfig = ConnectionCapacityConfig(
            clientCount = clientCount,
            rampGroupSize = rampGroupSize,
            rampIntervalMs = rampIntervalMs,
            holdDurationMs = holdDurationMs,
            reconnectCount = reconnectCount,
            reconnectTimeoutMs = reconnectTimeoutMs,
            sampleIntervalMs = sampleIntervalMs,
            cleanupObservationMs = cleanupObservationMs,
        )

        companion object {
            fun fromSystemProperties(): ConnectionConfig {
                fun int(name: String, default: Int, range: IntRange): Int =
                    (System.getProperty(name)?.toIntOrNull() ?: default).also { value ->
                        require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    }

                fun long(name: String, default: Long, range: LongRange): Long =
                    (System.getProperty(name)?.toLongOrNull() ?: default).also { value ->
                        require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    }

                val clientCount = int("tk.connectionCapacity.clients", 64, 1..128)
                val rampGroupSize = int("tk.connectionCapacity.ramp.groupSize", 2, 1..128)
                require(rampGroupSize <= clientCount) {
                    "tk.connectionCapacity.ramp.groupSize must not exceed client count"
                }
                val reconnectCount = int("tk.connectionCapacity.reconnect.clients", 16, 1..128)
                require(reconnectCount <= clientCount) {
                    "tk.connectionCapacity.reconnect.clients must not exceed client count"
                }
                val rampIntervalMs = long(
                    "tk.connectionCapacity.ramp.intervalMs",
                    1_000L,
                    0L..60_000L,
                )
                val groups = (clientCount + rampGroupSize - 1) / rampGroupSize
                val groupsInsideLoginWindow = if (rampIntervalMs == 0L) {
                    groups
                } else {
                    minOf(groups.toLong(), (LOGIN_WINDOW_MILLIS + rampIntervalMs - 1) / rampIntervalMs)
                        .toInt()
                }
                require(rampGroupSize <= MAX_CONCURRENT_LOGINS) {
                    "tk.connectionCapacity.ramp.groupSize must not exceed " +
                        "$MAX_CONCURRENT_LOGINS concurrent logins"
                }
                require(
                    minOf(clientCount, groupsInsideLoginWindow * rampGroupSize) <=
                        MAX_LOGIN_ATTEMPTS_PER_WINDOW,
                ) {
                    "connection ramp would exceed $MAX_LOGIN_ATTEMPTS_PER_WINDOW logins per " +
                        "${LOGIN_WINDOW_MILLIS}ms source window"
                }
                return ConnectionConfig(
                    clientCount = clientCount,
                    rampGroupSize = rampGroupSize,
                    rampIntervalMs = rampIntervalMs,
                    holdDurationMs = long(
                        "tk.connectionCapacity.hold.durationMs",
                        60_000L,
                        1_000L..600_000L,
                    ),
                    reconnectCount = reconnectCount,
                    reconnectTimeoutMs = long(
                        "tk.connectionCapacity.reconnect.timeoutMs",
                        30_000L,
                        5_000L..120_000L,
                    ),
                    sampleIntervalMs = long(
                        "tk.connectionCapacity.sample.intervalMs",
                        5_000L,
                        500L..60_000L,
                    ),
                    cleanupObservationMs = long(
                        "tk.connectionCapacity.cleanup.observationMs",
                        30_000L,
                        1_000L..120_000L,
                    ),
                    reportFile = File(
                        requireNotNull(System.getProperty("tk.connectionCapacity.report")) {
                            "connectionCapacityTest must provide tk.connectionCapacity.report"
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val CAPACITY_FIXTURE_PASSWORD = "password123"
        private const val CONTROL_SAMPLE_INTERVAL_MILLIS = 50L
        private const val FIXTURE_SETTLE_MILLIS = 2_000L
        private const val LOGIN_WINDOW_MILLIS = 10_000L
        private const val MAX_LOGIN_ATTEMPTS_PER_WINDOW = 24
        private const val MAX_CONCURRENT_LOGINS = 16
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        @JvmStatic
        @AfterAll
        fun shutdownRemoteSupport() {
            RemoteAcceptanceSupport.shutdown()
        }
    }
}
