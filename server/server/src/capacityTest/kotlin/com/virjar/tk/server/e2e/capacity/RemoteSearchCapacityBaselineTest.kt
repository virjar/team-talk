package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.server.e2e.RemoteAcceptanceSupport
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * First-page search development baseline through the same binary RPCs used by Desktop/Android.
 *
 * Distinct authenticated users are required because each uid owns one serial server command lane.
 * The fixture uses ordinary group creation and MESSAGE/ACK writes; neither Lucene nor PostgreSQL is
 * called directly. This test never changes host networking.
 */
class RemoteSearchCapacityBaselineTest {
    @Test
    fun `multi user search stays exact through steady burst and fresh projection`() = runBlocking {
        val runId = UUID.randomUUID().toString().replace("-", "").take(12)
        val reportFile = File(
            System.getProperty("tk.searchCapacity.report") ?: DEFAULT_REPORT_PATH,
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
                SearchCapacityRunState(
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
            val config = SearchRuntimeConfig.fromSystemProperties()
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
        println("[SearchCapacity] report=${reportFile.absolutePath}")
        println(
            "[SearchCapacity] users=${report.fixture.authenticatedQuerySessions}/" +
                "${report.fixture.expectedQuerySessions} chats=${report.fixture.createdSharedChats} " +
                "messages=${report.fixture.acknowledgedSearchableMessages} " +
                "steadyQps=${report.steady.throughputPerSecond} steadyP95Ms=" +
                "${report.steady.message.latency.p95Ms} uiCycles=${report.uiBurst.succeeded}/" +
                "${report.uiBurst.attempted} uiP95Ms=${report.uiBurst.cycleLatency.p95Ms} " +
                "freshMs=${report.freshProbe.elapsedMs}",
        )
        check(report.passed) {
            "Remote search capacity baseline failed; inspect the machine-readable report"
        }
    }

    private suspend fun runBaseline(
        config: SearchRuntimeConfig,
        sampler: RemoteTeamTalkResourceSampler,
        runId: String,
        target: CapacityTarget,
        enterPhase: (String) -> Unit,
    ): SearchCapacityReport {
        val sessions = mutableListOf<RemoteAcceptanceSupport.Session>()
        val resourceSnapshots = mutableListOf<TeamTalkResourceSnapshot>()
        lateinit var fixture: SearchFixture
        lateinit var fixtureResult: SearchFixtureResult
        lateinit var warmup: SearchScenarioResult
        lateinit var steady: SearchScenarioResult
        lateinit var uiBurst: SearchScenarioResult
        lateinit var correctness: SearchCorrectnessResult
        lateinit var freshProbe: SearchFreshProbeResult
        lateinit var sessionStability: SearchSessionStabilityResult

        try {
            enterPhase("fixture")
            val fixtureStarted = System.nanoTime()
            fixture = createFixture(config, runId, sessions)
            fixtureResult = buildSearchFixtureResult(
                expectedQuerySessions = config.queryUsers,
                authenticatedQuerySessions = fixture.querySessions.count {
                    it.imClient.state.value == ConnectionState.AUTHENTICATED
                },
                expectedSharedChats = config.sharedChats,
                createdSharedChats = fixture.sharedChats.size,
                expectedSearchableMessages = config.sharedChats * config.messagesPerChat,
                acknowledgedSearchableMessages = fixture.acknowledgedMessages,
                expectedForeignMessages = 1,
                acknowledgedForeignMessages = if (fixture.foreignMessage.serverSeq > 0L) 1 else 0,
                directoryFixtures = 1,
                elapsedNanos = System.nanoTime() - fixtureStarted,
            )

            enterPhase("fixture-projection")
            check(awaitFixtureSearchProjection(fixture, config.recoveryTimeoutMs)) {
                "Search capacity fixture did not become visible before the recovery deadline"
            }
            enterPhase("correctness-preflight")
            correctness = runPreflight(fixture, config.resultLimit)

            enterPhase("warmup")
            val warmupAttempts = runUiCycles(
                fixture = fixture,
                cyclesPerUser = config.warmupCycles,
                concurrency = config.queryUsers,
            )
            warmup = buildSearchScenarioResult(
                name = "warmup",
                expectsMessageSearch = true,
                expectsUserSearch = true,
                attempts = warmupAttempts.attempts,
                elapsedNanos = warmupAttempts.elapsedNanos,
            )
            enterPhase("baseline-resource-sample")
            sample(resourceSnapshots, sampler, "baseline")

            val authenticationBefore = fixture.querySessions.map { session ->
                session.imClient.state.value to session.authenticationCount
            }
            enterPhase("steady")
            val steadyRun = withResourceSampling(
                phase = "steady",
                intervalMs = config.resourceSampleIntervalMs,
                sampler = sampler,
                snapshots = resourceSnapshots,
            ) {
                runSteady(fixture, config)
            }
            steady = buildSearchScenarioResult(
                name = "global-message-steady",
                expectsMessageSearch = true,
                expectsUserSearch = false,
                attempts = steadyRun.attempts,
                elapsedNanos = steadyRun.elapsedNanos,
            )
            sample(resourceSnapshots, sampler, "steady-complete")

            enterPhase("ui-burst")
            val burstRun = withResourceSampling(
                phase = "ui-burst",
                intervalMs = config.resourceSampleIntervalMs,
                sampler = sampler,
                snapshots = resourceSnapshots,
            ) {
                runUiBurst(fixture, config)
            }
            uiBurst = buildSearchScenarioResult(
                name = "global-ui-burst",
                expectsMessageSearch = true,
                expectsUserSearch = true,
                attempts = burstRun.attempts,
                elapsedNanos = burstRun.elapsedNanos,
            )
            sample(resourceSnapshots, sampler, "ui-burst-complete")

            enterPhase("fresh-projection")
            freshProbe = runFreshProjectionProbe(fixture, config, runId)
            sample(resourceSnapshots, sampler, "fresh-probe")
            sessionStability = buildSearchSessionStabilityResult(
                expectedSessions = config.queryUsers,
                observations = fixture.querySessions.mapIndexed { index, session ->
                    SearchSessionObservation(
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
        } finally {
            sessions.asReversed().forEach { session -> runCatching(session::close) }
        }

        enterPhase("cleanup-observation")
        delay(config.cleanupObservationMs)
        sample(resourceSnapshots, sampler, "cleanup")
        val resources = summarizeSearchResources(resourceSnapshots)
        enterPhase("final-report")
        return buildSearchCapacityReport(
            generatedAt = Instant.now().toString(),
            target = target,
            config = config.reportConfig(runId),
            fixture = fixtureResult,
            warmup = warmup,
            steady = steady,
            uiBurst = uiBurst,
            correctness = correctness,
            freshProbe = freshProbe,
            sessions = sessionStability,
            resources = resources,
        )
    }

    private suspend fun createFixture(
        config: SearchRuntimeConfig,
        runId: String,
        sessions: MutableList<RemoteAcceptanceSupport.Session>,
    ): SearchFixture {
        val uiToken = "uisearchneedle$runId"
        val querySessions = (0 until config.queryUsers).map { index ->
            RemoteAcceptanceSupport.registerUser(
                suffix = "search-cap-$index",
                displayName = if (index == 0) {
                    "Search Directory $uiToken"
                } else {
                    "Search Capacity User $index"
                },
                deviceId = "search-cap-$runId-$index",
                deviceName = "Search capacity user $index",
            ).also(sessions::add)
        }
        val outsider = RemoteAcceptanceSupport.registerUser(
            suffix = "search-foreign",
            displayName = "Search Capacity Outsider",
            deviceId = "search-cap-$runId-foreign",
            deviceName = "Search capacity outsider",
        ).also(sessions::add)

        val owner = querySessions.first()
        val memberUids = querySessions.drop(1).map(RemoteAcceptanceSupport.Session::uid)
        val sharedChats = (0 until config.sharedChats).map { chatIndex ->
            createGroup(owner, "Search capacity $runId $chatIndex", memberUids)
        }
        val foreignChat = createGroup(
            outsider,
            "Search capacity foreign $runId",
            emptyList(),
        )
        val markerCount = config.globalMarkerCount
        val globalTokens = (0 until markerCount).map { index ->
            "globalsearchneedle${runId}m$index"
        }
        val scopedTokens = sharedChats.indices.map { index ->
            "scopedsearchneedle${runId}c$index"
        }
        val foreignToken = "foreignsearchneedle$runId"
        val messagePlans = buildList {
            sharedChats.forEachIndexed { chatIndex, chat ->
                repeat(config.messagesPerChat) { messageIndex ->
                    val senderIndex = (chatIndex + messageIndex) % querySessions.size
                    val clientMsgId = "search-$runId-c$chatIndex-m$messageIndex"
                    val body = buildString {
                        append("search capacity chat ")
                        append(chatIndex)
                        append(" message ")
                        append(messageIndex)
                        if (messageIndex == 0) {
                            append(' ')
                            append(globalTokens[chatIndex % markerCount])
                            append(' ')
                            append(scopedTokens[chatIndex])
                            if (chatIndex < config.uiMessageChats) {
                                append(' ')
                                append(uiToken)
                            }
                        }
                    }
                    add(
                        FixtureMessagePlan(
                            senderIndex = senderIndex,
                            message = capacityMessage(
                                sender = querySessions[senderIndex],
                                chatId = chat.chatId,
                                clientMsgId = clientMsgId,
                                text = body,
                            ),
                        ),
                    )
                }
            }
        }
        val acknowledged = coroutineScope {
            messagePlans.groupBy(FixtureMessagePlan::senderIndex).map { (senderIndex, plans) ->
                async {
                    plans.map { plan ->
                        sendFixtureMessage(querySessions[senderIndex], plan.message)
                    }
                }
            }.awaitAll().flatten()
        }
        val foreignText = (globalTokens + uiToken + foreignToken).joinToString(" ")
        val foreignMessage = sendFixtureMessage(
            outsider,
            capacityMessage(
                sender = outsider,
                chatId = foreignChat.chatId,
                clientMsgId = "search-$runId-foreign",
                text = foreignText,
            ),
        )
        val acknowledgedByClientIdentity = acknowledged.associateBy { message ->
            message.chatId to message.clientMsgId
        }
        check(acknowledgedByClientIdentity.size == messagePlans.size) {
            "Search capacity fixture acknowledgement identities are not unique"
        }
        val markerExpected = globalTokens.mapIndexed { markerIndex, _ ->
            acknowledged.filterTo(linkedSetOf()) { message ->
                val chatIndex = sharedChats.indexOfFirst { it.chatId == message.chatId }
                chatIndex >= 0 && chatIndex % markerCount == markerIndex &&
                    message.clientMsgId.endsWith("-m0")
            }.mapTo(linkedSetOf(), SearchMessageIdentity::fromMessage)
        }
        val scopedExpected = sharedChats.mapIndexed { chatIndex, chat ->
            val message = checkNotNull(
                acknowledgedByClientIdentity[chat.chatId to "search-$runId-c$chatIndex-m0"],
            ) { "Scoped search fixture acknowledgement is missing" }
            SearchMessageIdentity.fromMessage(message)
        }
        val uiExpected = sharedChats.take(config.uiMessageChats)
            .mapIndexedTo(linkedSetOf()) { chatIndex, chat ->
                SearchMessageIdentity.fromMessage(
                    checkNotNull(
                        acknowledgedByClientIdentity[
                            chat.chatId to "search-$runId-c$chatIndex-m0"
                        ],
                    ) { "UI search fixture acknowledgement is missing" },
                )
            }
        return SearchFixture(
            querySessions = querySessions,
            outsider = outsider,
            sharedChats = sharedChats,
            foreignChat = foreignChat,
            globalTokens = globalTokens,
            globalExpected = markerExpected,
            scopedTokens = scopedTokens,
            scopedExpected = scopedExpected,
            uiToken = uiToken,
            uiMessageExpected = uiExpected,
            directoryExpectedUids = setOf(querySessions.first().uid),
            foreignToken = foreignToken,
            foreignMessage = foreignMessage,
            acknowledgedMessages = acknowledged.size,
        )
    }

    private suspend fun createGroup(
        owner: RemoteAcceptanceSupport.Session,
        name: String,
        memberUids: List<String>,
    ): Chat {
        val response = owner.invoke(
            ChatRpcContract.SERVICE,
            ChatRpcContract.M_CREATE_GROUP,
            ChatRpcContract.encodeCreateGroup(
                operationId = UUID.randomUUID().toString(),
                name = name,
                avatar = null,
                memberUids = memberUids,
            ),
        )
        check(response.status == 0 && response.payload != null) {
            "Unable to create search capacity group (status=${response.status})"
        }
        return ProtoCodec.decode(Chat, requireNotNull(response.payload))
    }

    private suspend fun sendFixtureMessage(
        sender: RemoteAcceptanceSupport.Session,
        message: Message,
    ): Message {
        val acknowledgement = sender.imClient.sendAndWaitAck(message)
        check(
            acknowledgement.code == 0 &&
                acknowledgement.chatId == message.chatId &&
                acknowledgement.clientMsgId == message.clientMsgId &&
                acknowledgement.serverSeq > 0L,
        ) { "Search capacity fixture message was not acknowledged exactly" }
        return message.copy(serverSeq = acknowledgement.serverSeq)
    }

    private suspend fun awaitFixtureSearchProjection(
        fixture: SearchFixture,
        timeoutMs: Long,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val result = searchMessages(
                session = fixture.querySessions.first(),
                chatId = "",
                keyword = fixture.globalTokens.first(),
                resultLimit = Message.MAX_QUERY_PAGE_SIZE,
                expected = fixture.globalExpected.first(),
            )
            if (result.failureCategory == null && result.exactResultPassed) return@withTimeoutOrNull true
            delay(PROJECTION_POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } == true

    private suspend fun runPreflight(
        fixture: SearchFixture,
        resultLimit: Int,
    ): SearchCorrectnessResult {
        val counters = PreflightCounters()
        fixture.querySessions.forEach { session ->
            fixture.sharedChats.indices.forEach { chatIndex ->
                val result = searchMessages(
                    session = session,
                    chatId = fixture.sharedChats[chatIndex].chatId,
                    keyword = fixture.scopedTokens[chatIndex],
                    resultLimit = resultLimit,
                    expected = setOf(fixture.scopedExpected[chatIndex]),
                )
                counters.scoped.record(result.exactResultPassed && result.failureCategory == null)
                counters.ordering.record(result.orderingPassed)
            }
            fixture.globalTokens.indices.forEach { markerIndex ->
                val result = searchMessages(
                    session = session,
                    chatId = "",
                    keyword = fixture.globalTokens[markerIndex],
                    resultLimit = resultLimit,
                    expected = fixture.globalExpected[markerIndex],
                )
                counters.global.record(result.exactResultPassed && result.failureCategory == null)
                counters.ordering.record(result.orderingPassed)
            }
            val foreign = searchMessages(
                session = session,
                chatId = "",
                keyword = fixture.foreignToken,
                resultLimit = resultLimit,
                expected = emptySet(),
            )
            counters.foreign.record(foreign.exactResultPassed && foreign.failureCategory == null)
            val miss = searchMessages(
                session = session,
                chatId = "",
                keyword = "missingsearchneedle${UUID.randomUUID().toString().replace("-", "")}",
                resultLimit = resultLimit,
                expected = emptySet(),
            )
            counters.miss.record(miss.exactResultPassed && miss.failureCategory == null)
            val users = searchUsers(session, fixture.uiToken, fixture.directoryExpectedUids)
            counters.user.record(users.exactResultPassed && users.failureCategory == null)
        }
        val outsiderForeign = searchMessages(
            session = fixture.outsider,
            chatId = "",
            keyword = fixture.foreignToken,
            resultLimit = resultLimit,
            expected = setOf(SearchMessageIdentity.fromMessage(fixture.foreignMessage)),
        )
        counters.foreign.record(
            outsiderForeign.exactResultPassed && outsiderForeign.failureCategory == null,
        )
        counters.ordering.record(outsiderForeign.orderingPassed)
        return buildSearchCorrectnessResult(
            scopedExact = counters.scoped.result(),
            globalExact = counters.global.result(),
            userExact = counters.user.result(),
            ordering = counters.ordering.result(),
            foreignIsolation = counters.foreign.result(),
            miss = counters.miss.result(),
        )
    }

    private suspend fun runSteady(
        fixture: SearchFixture,
        config: SearchRuntimeConfig,
    ): ScenarioRun = coroutineScope {
        val started = System.nanoTime()
        val laneRuns = fixture.querySessions.mapIndexed { laneIndex, session ->
            async {
                buildList {
                    repeat(config.steadyCycles) { cycle ->
                        val markerIndex = (laneIndex + cycle) % fixture.globalTokens.size
                        val query = if (cycle % STEADY_MISS_EVERY == 0) {
                            MessageQuery(
                                keyword = "steadymissing${laneIndex}x${cycle}x${fixture.uiToken}",
                                expected = emptySet(),
                            )
                        } else {
                            MessageQuery(
                                keyword = fixture.globalTokens[markerIndex],
                                expected = fixture.globalExpected[markerIndex],
                            )
                        }
                        val cycleStarted = System.nanoTime()
                        val message = searchMessages(
                            session = session,
                            chatId = "",
                            keyword = query.keyword,
                            resultLimit = config.resultLimit,
                            expected = query.expected,
                        )
                        add(message.toAttempt(System.nanoTime() - cycleStarted))
                        if (cycle + 1 < config.steadyCycles && config.steadyIntervalMs > 0L) {
                            delay(config.steadyIntervalMs)
                        }
                    }
                }
            }
        }.awaitAll()
        ScenarioRun(laneRuns.flatten(), System.nanoTime() - started)
    }

    private suspend fun runUiCycles(
        fixture: SearchFixture,
        cyclesPerUser: Int,
        concurrency: Int,
    ): ScenarioRun {
        val total = fixture.querySessions.size * cyclesPerUser
        return runConcurrentCycles(fixture, total, minOf(concurrency, total))
    }

    private suspend fun runUiBurst(
        fixture: SearchFixture,
        config: SearchRuntimeConfig,
    ): ScenarioRun = runConcurrentCycles(
        fixture = fixture,
        totalCycles = config.uiBurstCycles,
        concurrency = config.uiBurstConcurrency,
    )

    private suspend fun runConcurrentCycles(
        fixture: SearchFixture,
        totalCycles: Int,
        concurrency: Int,
    ): ScenarioRun = coroutineScope {
        val next = AtomicInteger()
        val started = System.nanoTime()
        val workerRuns = List(concurrency) {
            async {
                buildList {
                    while (true) {
                        val cycle = next.getAndIncrement()
                        if (cycle >= totalCycles) break
                        val session = fixture.querySessions[cycle % fixture.querySessions.size]
                        add(runUiCycle(session, fixture))
                    }
                }
            }
        }.awaitAll()
        ScenarioRun(workerRuns.flatten(), System.nanoTime() - started)
    }

    private suspend fun runUiCycle(
        session: RemoteAcceptanceSupport.Session,
        fixture: SearchFixture,
    ): SearchQueryAttempt = coroutineScope {
        val started = System.nanoTime()
        val message = async {
            searchMessages(
                session = session,
                chatId = "",
                keyword = fixture.uiToken,
                resultLimit = Message.MAX_QUERY_PAGE_SIZE,
                expected = fixture.uiMessageExpected,
            )
        }
        val users = async {
            searchUsers(session, fixture.uiToken, fixture.directoryExpectedUids)
        }
        val messageResult = message.await()
        val userResult = users.await()
        SearchQueryAttempt(
            messageLatencyNanos = messageResult.latencyNanos,
            userLatencyNanos = userResult.latencyNanos,
            cycleLatencyNanos = System.nanoTime() - started,
            messageFailureCategory = messageResult.failureCategory,
            userFailureCategory = userResult.failureCategory,
            exactResultPassed = messageResult.exactResultPassed && userResult.exactResultPassed,
            orderingPassed = messageResult.orderingPassed,
        )
    }

    private suspend fun runFreshProjectionProbe(
        fixture: SearchFixture,
        config: SearchRuntimeConfig,
        runId: String,
    ): SearchFreshProbeResult {
        val started = System.nanoTime()
        val token = "freshsearchneedle${runId}${System.nanoTime()}"
        val message = capacityMessage(
            sender = fixture.querySessions.first(),
            chatId = fixture.sharedChats.first().chatId,
            clientMsgId = "search-$runId-fresh",
            text = token,
        )
        var expected: SearchMessageIdentity? = null
        var acknowledged = false
        var attempts = 0
        var scopedMatches = 0
        var globalMatches = 0
        val failures = mutableListOf<String>()
        try {
            expected = SearchMessageIdentity.fromMessage(
                sendFixtureMessage(fixture.querySessions.first(), message),
            )
            acknowledged = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TransportUnavailableException) {
            failures += SearchCapacityFailureCategory.TRANSPORT
        } catch (_: IOException) {
            failures += SearchCapacityFailureCategory.TRANSPORT
        } catch (_: Exception) {
            failures += SearchCapacityFailureCategory.UNEXPECTED
        }
        if (acknowledged) {
            withTimeoutOrNull(config.recoveryTimeoutMs) {
                while (scopedMatches == 0 || globalMatches == 0) {
                    attempts += 1
                    val scoped = searchMessages(
                        fixture.querySessions.first(),
                        message.chatId,
                        token,
                        config.resultLimit,
                        setOf(requireNotNull(expected)),
                    )
                    val global = searchMessages(
                        fixture.querySessions.first(),
                        "",
                        token,
                        config.resultLimit,
                        setOf(requireNotNull(expected)),
                    )
                    scoped.failureCategory?.let(failures::add)
                    global.failureCategory?.let(failures::add)
                    if (scoped.failureCategory == null && scoped.exactResultPassed) scopedMatches = 1
                    if (global.failureCategory == null && global.exactResultPassed) globalMatches = 1
                    if (scopedMatches == 0 || globalMatches == 0) delay(PROJECTION_POLL_INTERVAL_MS)
                }
            }
            if (scopedMatches == 0 || globalMatches == 0) {
                failures += SearchCapacityFailureCategory.TIMEOUT
            }
        }
        return buildSearchFreshProbeResult(
            attempts = attempts,
            messageAcknowledged = acknowledged,
            scopedExactMatches = scopedMatches,
            globalExactMatches = globalMatches,
            failureCategories = failures,
            elapsedNanos = System.nanoTime() - started,
        )
    }

    private suspend fun searchMessages(
        session: RemoteAcceptanceSupport.Session,
        chatId: String,
        keyword: String,
        resultLimit: Int,
        expected: Set<SearchMessageIdentity>,
    ): MessageSearchResult {
        val started = System.nanoTime()
        return try {
            val response = session.invoke(
                MessageRpcContract.SERVICE,
                MessageRpcContract.M_SEARCH,
                MessageRpcContract.encodeSearch(chatId, keyword, resultLimit),
            )
            val latency = System.nanoTime() - started
            if (response.status != 0) {
                return MessageSearchResult(
                    latency,
                    classifyRpcStatus(response.status),
                    exactResultPassed = false,
                    orderingPassed = false,
                )
            }
            val messages = try {
                ProtoCodec.decodeList(Message, requireNotNull(response.payload))
            } catch (_: Exception) {
                return MessageSearchResult(
                    latency,
                    SearchCapacityFailureCategory.DECODE,
                    exactResultPassed = false,
                    orderingPassed = false,
                )
            }
            val identities = messages.mapTo(linkedSetOf(), SearchMessageIdentity::fromMessage)
            MessageSearchResult(
                latencyNanos = latency,
                failureCategory = null,
                exactResultPassed = messages.size == identities.size && identities == expected,
                orderingPassed = messages.zipWithNext().all { (left, right) ->
                    left.timestamp >= right.timestamp
                },
            )
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                MessageSearchResult(
                    System.nanoTime() - started,
                    SearchCapacityFailureCategory.TIMEOUT,
                    exactResultPassed = false,
                    orderingPassed = false,
                )
            } else {
                throw cancelled
            }
        } catch (_: TransportUnavailableException) {
            MessageSearchResult(
                System.nanoTime() - started,
                SearchCapacityFailureCategory.TRANSPORT,
                exactResultPassed = false,
                orderingPassed = false,
            )
        } catch (_: IOException) {
            MessageSearchResult(
                System.nanoTime() - started,
                SearchCapacityFailureCategory.TRANSPORT,
                exactResultPassed = false,
                orderingPassed = false,
            )
        } catch (_: Exception) {
            MessageSearchResult(
                System.nanoTime() - started,
                SearchCapacityFailureCategory.UNEXPECTED,
                exactResultPassed = false,
                orderingPassed = false,
            )
        }
    }

    private suspend fun searchUsers(
        session: RemoteAcceptanceSupport.Session,
        keyword: String,
        expectedUids: Set<String>,
    ): UserSearchResult {
        val started = System.nanoTime()
        return try {
            val response = session.invoke(
                UserRpcContract.SERVICE,
                UserRpcContract.M_SEARCH,
                UserRpcContract.encodeSearch(keyword),
            )
            val latency = System.nanoTime() - started
            if (response.status != 0) {
                return UserSearchResult(latency, classifyRpcStatus(response.status), false)
            }
            val users = try {
                ProtoCodec.decodeList(User, requireNotNull(response.payload))
            } catch (_: Exception) {
                return UserSearchResult(latency, SearchCapacityFailureCategory.DECODE, false)
            }
            val uids = users.mapTo(linkedSetOf(), User::uid)
            UserSearchResult(
                latencyNanos = latency,
                failureCategory = null,
                exactResultPassed = users.size == uids.size && uids == expectedUids,
            )
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                UserSearchResult(
                    System.nanoTime() - started,
                    SearchCapacityFailureCategory.TIMEOUT,
                    false,
                )
            } else {
                throw cancelled
            }
        } catch (_: TransportUnavailableException) {
            UserSearchResult(
                System.nanoTime() - started,
                SearchCapacityFailureCategory.TRANSPORT,
                false,
            )
        } catch (_: IOException) {
            UserSearchResult(
                System.nanoTime() - started,
                SearchCapacityFailureCategory.TRANSPORT,
                false,
            )
        } catch (_: Exception) {
            UserSearchResult(
                System.nanoTime() - started,
                SearchCapacityFailureCategory.UNEXPECTED,
                false,
            )
        }
    }

    private fun classifyRpcStatus(status: Int): String =
        if (status == RPC_TIMEOUT_STATUS) {
            SearchCapacityFailureCategory.TIMEOUT
        } else {
            SearchCapacityFailureCategory.rpcStatus(status)
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

    private fun capacityMessage(
        sender: RemoteAcceptanceSupport.Session,
        chatId: String,
        clientMsgId: String,
        text: String,
    ): Message = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        senderUid = sender.uid,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = System.currentTimeMillis(),
        body = buildRichTextBody(text),
    )

    private data class SearchFixture(
        val querySessions: List<RemoteAcceptanceSupport.Session>,
        val outsider: RemoteAcceptanceSupport.Session,
        val sharedChats: List<Chat>,
        val foreignChat: Chat,
        val globalTokens: List<String>,
        val globalExpected: List<Set<SearchMessageIdentity>>,
        val scopedTokens: List<String>,
        val scopedExpected: List<SearchMessageIdentity>,
        val uiToken: String,
        val uiMessageExpected: Set<SearchMessageIdentity>,
        val directoryExpectedUids: Set<String>,
        val foreignToken: String,
        val foreignMessage: Message,
        val acknowledgedMessages: Int,
    )

    private data class FixtureMessagePlan(
        val senderIndex: Int,
        val message: Message,
    )

    private data class SearchMessageIdentity(
        val chatId: String,
        val clientMsgId: String,
        val serverSeq: Long,
    ) {
        companion object {
            fun fromMessage(message: Message): SearchMessageIdentity =
                SearchMessageIdentity(message.chatId, message.clientMsgId, message.serverSeq)
        }
    }

    private data class MessageQuery(
        val keyword: String,
        val expected: Set<SearchMessageIdentity>,
    )

    private data class MessageSearchResult(
        val latencyNanos: Long,
        val failureCategory: String?,
        val exactResultPassed: Boolean,
        val orderingPassed: Boolean,
    ) {
        fun toAttempt(cycleLatencyNanos: Long): SearchQueryAttempt = SearchQueryAttempt(
            messageLatencyNanos = latencyNanos,
            cycleLatencyNanos = cycleLatencyNanos,
            messageFailureCategory = failureCategory,
            exactResultPassed = exactResultPassed,
            orderingPassed = orderingPassed,
        )
    }

    private data class UserSearchResult(
        val latencyNanos: Long,
        val failureCategory: String?,
        val exactResultPassed: Boolean,
    )

    private data class ScenarioRun(
        val attempts: List<SearchQueryAttempt>,
        val elapsedNanos: Long,
    )

    private class CorrectnessCounter {
        var checks = 0
            private set
        var failures = 0
            private set

        fun record(passed: Boolean) {
            checks += 1
            if (!passed) failures += 1
        }

        fun result(): SearchCorrectnessCheck = buildSearchCorrectnessCheck(checks, failures)
    }

    private class PreflightCounters {
        val scoped = CorrectnessCounter()
        val global = CorrectnessCounter()
        val user = CorrectnessCounter()
        val ordering = CorrectnessCounter()
        val foreign = CorrectnessCounter()
        val miss = CorrectnessCounter()
    }

    private data class SearchRuntimeConfig(
        val queryUsers: Int,
        val sharedChats: Int,
        val messagesPerChat: Int,
        val resultLimit: Int,
        val globalMarkerCount: Int,
        val uiMessageChats: Int,
        val warmupCycles: Int,
        val steadyCycles: Int,
        val steadyIntervalMs: Long,
        val uiBurstCycles: Int,
        val uiBurstConcurrency: Int,
        val recoveryTimeoutMs: Long,
        val resourceSampleIntervalMs: Long,
        val cleanupObservationMs: Long,
        val reportFile: File,
    ) {
        fun reportConfig(runId: String) = SearchCapacityConfig(
            runId = runId,
            queryUsers = queryUsers,
            sharedChats = sharedChats,
            messagesPerChat = messagesPerChat,
            resultLimit = resultLimit,
            globalMarkerCount = globalMarkerCount,
            uiMessageChats = uiMessageChats,
            warmupCycles = warmupCycles,
            steadyCycles = steadyCycles,
            steadyIntervalMs = steadyIntervalMs,
            steadyMissEvery = STEADY_MISS_EVERY,
            uiBurstCycles = uiBurstCycles,
            uiBurstConcurrency = uiBurstConcurrency,
            recoveryTimeoutMs = recoveryTimeoutMs,
            resourceSampleIntervalMs = resourceSampleIntervalMs,
            cleanupObservationMs = cleanupObservationMs,
            rpcTimeoutMs = RPC_TIMEOUT_MS,
        )

        companion object {
            fun fromSystemProperties(): SearchRuntimeConfig {
                fun int(name: String, default: Int, range: IntRange): Int =
                    (System.getProperty(name)?.toIntOrNull() ?: default).also { value ->
                        require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    }

                fun long(name: String, default: Long, range: LongRange): Long =
                    (System.getProperty(name)?.toLongOrNull() ?: default).also { value ->
                        require(value in range) { "$name must be in ${range.first}..${range.last}" }
                    }

                val users = int("tk.searchCapacity.users", 4, 2..6)
                val chats = int("tk.searchCapacity.chats", 16, 4..64)
                val messagesPerChat = int("tk.searchCapacity.messagesPerChat", 16, 2..64)
                require(chats.toLong() * messagesPerChat <= MAX_FIXTURE_MESSAGES) {
                    "Search capacity fixture exceeds $MAX_FIXTURE_MESSAGES messages"
                }
                val burstCycles = int("tk.searchCapacity.burst.cycles", 100, 20..500)
                val burstConcurrency = int(
                    "tk.searchCapacity.burst.concurrency",
                    16,
                    1..64,
                )
                require(burstConcurrency <= burstCycles) {
                    "Search burst concurrency must not exceed its cycle count"
                }
                val resultLimit = Message.MAX_QUERY_PAGE_SIZE
                val markerCount = maxOf(
                    DEFAULT_GLOBAL_MARKERS,
                    (chats + resultLimit - 1) / resultLimit,
                )
                return SearchRuntimeConfig(
                    queryUsers = users,
                    sharedChats = chats,
                    messagesPerChat = messagesPerChat,
                    resultLimit = resultLimit,
                    globalMarkerCount = markerCount,
                    uiMessageChats = minOf(UI_SEARCH_MESSAGE_CHAT_COUNT, chats, resultLimit),
                    warmupCycles = int("tk.searchCapacity.warmup.cycles", 2, 1..10),
                    steadyCycles = int("tk.searchCapacity.steady.queriesPerUser", 50, 25..250),
                    steadyIntervalMs = long(
                        "tk.searchCapacity.steady.intervalMs",
                        280L,
                        0L..2_000L,
                    ),
                    uiBurstCycles = burstCycles,
                    uiBurstConcurrency = burstConcurrency,
                    recoveryTimeoutMs = long(
                        "tk.searchCapacity.projection.timeoutMs",
                        30_000L,
                        5_000L..120_000L,
                    ),
                    resourceSampleIntervalMs = long(
                        "tk.searchCapacity.sample.intervalMs",
                        2_000L,
                        500L..30_000L,
                    ),
                    cleanupObservationMs = long(
                        "tk.searchCapacity.cleanup.observationMs",
                        30_000L,
                        0L..120_000L,
                    ),
                    reportFile = File(
                        System.getProperty("tk.searchCapacity.report")
                            ?: DEFAULT_REPORT_PATH,
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

        private const val DEFAULT_GLOBAL_MARKERS = 4
        private const val UI_SEARCH_MESSAGE_CHAT_COUNT = 4
        private const val STEADY_MISS_EVERY = 5
        private const val PROJECTION_POLL_INTERVAL_MS = 100L
        private const val RPC_TIMEOUT_STATUS = 504
        private const val RPC_TIMEOUT_MS = 10_000L
        private const val MAX_FAILURE_MESSAGE_LENGTH = 2_000
        private const val MAX_FIXTURE_MESSAGES = 4_096L
        private const val DEFAULT_REPORT_PATH =
            "server/build/reports/capacity/search-capacity.json"
        private const val DEFAULT_TCP_HOST = "im.virjar.com"
        private const val DEFAULT_TCP_PORT = 5_100
        private const val INVALID_TCP_PORT = -1
    }
}
