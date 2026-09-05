package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.server.e2e.RemoteAcceptanceSupport
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * Explicit remote development baseline. This class lives in the capacityTest source set and is
 * therefore neither discovered nor compiled by the ordinary server test task.
 *
 * Each sender owns a real authenticated connection and a distinct chat. The shared receiver is
 * only the observation point, so a burst exercises independent server `user-command` lanes while
 * notifications and history still converge through one ordinary SDK session.
 */
class RemoteMessageCapacityBaselineTest {
    @Test
    fun `multi user steady burst and recovery converge per lane`() = runBlocking {
        val config = CapacityConfig.fromSystemProperties()
        val sessions = mutableListOf<RemoteAcceptanceSupport.Session>()
        try {
            val runId = UUID.randomUUID().toString().replace("-", "").take(12)
            val receiver = RemoteAcceptanceSupport.registerUser(
                suffix = "capacity-receiver",
                password = CAPACITY_FIXTURE_PASSWORD,
                deviceId = "capacity-primary-$runId",
                deviceName = "Capacity primary receiver",
            )
                .also(sessions::add)
            val receiverUsername = checkNotNull(receiver.registeredUsername) {
                "Capacity receiver registration did not retain its fixture username"
            }
            val laggingReceiver = RemoteAcceptanceSupport.loginUser(
                username = receiverUsername,
                password = CAPACITY_FIXTURE_PASSWORD,
                deviceId = "capacity-lagging-$runId",
                deviceName = "Capacity lagging receiver",
            ).also(sessions::add)
            val catchupBaseCursor = laggingReceiver.syncCursor()
            val catchupAuthenticationBefore = laggingReceiver.authenticationCount
            laggingReceiver.imClient.simulateNetworkDropAndPauseReconnect()
            withTimeout(config.eventCatchupTimeoutMs) {
                laggingReceiver.imClient.state.first { state -> state == ConnectionState.DISCONNECTED }
            }

            val lanes = (1..config.senderLanes).map { laneId ->
                val sender = RemoteAcceptanceSupport.registerUser("capacity-sender-$laneId")
                    .also(sessions::add)
                CapacityLane(
                    id = laneId,
                    sender = sender,
                    chatId = createPersonalChat(sender, receiver),
                )
            }
            val warmup = runPerLaneScenario(
                name = "warmup",
                messagesPerLane = config.warmupMessagesPerLane,
                intervalMs = 0L,
                lanes = lanes,
                runId = runId,
                ackTimeoutMs = config.ackTimeoutMs,
            )
            val steady = runPerLaneScenario(
                name = "steady",
                messagesPerLane = config.steadyMessagesPerLane,
                intervalMs = config.steadyIntervalMs,
                lanes = lanes,
                runId = runId,
                ackTimeoutMs = config.ackTimeoutMs,
            )
            val burst = runBurstScenario(config, lanes, runId)

            val recoveryStarted = System.nanoTime()
            val recoveryDeadline = recoveryStarted + config.recoveryTimeoutMs * NANOS_PER_MILLISECOND
            val recoverableBurstMessages = burst.results.asSequence()
                .filter { isRecoverableCapacityFailure(it.failureCategory) }
                .map(SubmitAttempt::logicalMessage)
                .distinctBy(LogicalMessage::identity)
                .toList()
            val originalRecovery = recoverMessages(
                messages = recoverableBurstMessages,
                config = config,
                deadlineNanos = recoveryDeadline,
                scenario = "recovery",
                firstAttemptNumber = 2,
            )
            val recoveryProbes = lanes.map { lane ->
                logicalMessage(
                    lane = lane,
                    clientMsgId = "cap-$runId-recovery-l${lane.id}",
                    text = "capacity recovery probe lane ${lane.id}",
                )
            }
            val probeRecovery = sendFreshRecoveryProbes(
                messages = recoveryProbes,
                config = config,
                deadlineNanos = recoveryDeadline,
            )
            val recoveryElapsed = System.nanoTime() - recoveryStarted
            val initialBusyRejected = burst.results.count {
                it.failureCategory == CapacityFailureCategory.BUSY_503
            }
            val initialTimeouts = burst.results.count {
                it.failureCategory == CapacityFailureCategory.TIMEOUT
            }
            val initialTransportFailures = burst.results.count {
                it.failureCategory == CapacityFailureCategory.TRANSPORT
            }
            val initialTerminalFailures = burst.results.count {
                it.failureCategory != null && !isRecoverableCapacityFailure(it.failureCategory)
            }
            val overloadObserved = initialBusyRejected > 0
            val freshProbeSucceededLanes = probeRecovery.successful.values
                .mapTo(mutableSetOf()) { it.logicalMessage.lane.id }
                .size
            val unrecoveredOriginalMessages = originalRecovery.pending.size +
                originalRecovery.terminal.size
            val recoveryAttempts = originalRecovery.attempts + probeRecovery.attempts
            val recovery = CapacityRecoveryResult(
                initialRecoverableMessages = recoverableBurstMessages.size,
                initialBusyRejected = initialBusyRejected,
                initialTimeouts = initialTimeouts,
                initialTransportFailures = initialTransportFailures,
                initialTerminalFailures = initialTerminalFailures,
                recoveryAttempts = recoveryAttempts.size,
                attemptFailuresByCategory = failureCounts(
                    recoveryAttempts.mapNotNull(SubmitAttempt::failureCategory),
                ),
                recoveredMessages = originalRecovery.successful.size,
                unrecoveredMessages = unrecoveredOriginalMessages,
                recoveryTerminalFailures = originalRecovery.terminal.size + probeRecovery.terminal.size,
                freshProbeExpectedLanes = lanes.size,
                freshProbeSucceededLanes = freshProbeSucceededLanes,
                elapsedMs = elapsedMillis(recoveryElapsed),
                overloadObserved = overloadObserved,
                // Unknown outcomes are retried to prove convergence, but only an explicit 503 is
                // evidence of bounded overload. A healthy post-burst probe without such evidence
                // must not claim that the overload-recovery gate passed.
                passed = overloadObserved &&
                    initialTimeouts == 0 &&
                    initialTransportFailures == 0 &&
                    initialTerminalFailures == 0 &&
                    unrecoveredOriginalMessages == 0 &&
                    probeRecovery.terminal.isEmpty() &&
                    probeRecovery.pending.isEmpty() &&
                    freshProbeSucceededLanes == lanes.size,
            )

            val expectedLogicalMessages = warmup.logicalMessages +
                steady.logicalMessages + burst.logicalMessages + recoveryProbes
            val expectedMessages = expectedLogicalMessages
                .mapTo(linkedSetOf(), LogicalMessage::trackedMessage)
            val expectedIdentities = expectedMessages
                .mapTo(linkedSetOf(), CapacityTrackedMessage::identity)
            check(
                expectedMessages.size == expectedLogicalMessages.size &&
                    expectedIdentities.size == expectedLogicalMessages.size,
            ) {
                "Capacity fixture generated a duplicate composite message identity"
            }
            val allAttempts = warmup.results + steady.results + burst.results +
                originalRecovery.attempts + probeRecovery.attempts
            val acknowledgements = allAttempts.mapNotNull(SubmitAttempt::acceptedMessageOrNull)
            val notificationSeqs = collectNotifications(
                receiver = receiver,
                lanes = lanes,
                expectedIdentities = expectedIdentities,
                timeoutMs = config.deliveryTimeoutMs,
            )
            val historySeqs = collectHistory(
                receiver = receiver,
                lanes = lanes,
                expectedIdentities = expectedIdentities,
            )
            val integrity = buildCapacityIntegrity(
                laneIds = lanes.mapTo(linkedSetOf(), CapacityLane::id),
                expectedMessages = expectedMessages,
                acknowledgements = acknowledgements,
                notificationSeqs = notificationSeqs,
                historySeqs = historySeqs,
            )
            val acceptedLastSeqByChat = acknowledgements.groupBy { accepted ->
                accepted.message.identity.chatId
            }.mapValues { (_, accepted) -> accepted.maxOf(CapacityAcceptedMessage::serverSeq) }
            val primaryConversations = lanes.associate { lane ->
                val expectedLastSeq = acceptedLastSeqByChat[lane.chatId] ?: 0L
                lane.chatId to receiver.awaitConversation(
                    chatId = lane.chatId,
                    timeoutMs = config.eventCatchupTimeoutMs,
                ) { conversation -> conversation.lastSeq == expectedLastSeq }
            }
            // Conversation rows become visible inside event projection just before that event's
            // cursor commit. Wait for a short quiet period so the target is the completed primary
            // workload high-water mark, not a transient pre-commit sample.
            val catchupTargetCursor = awaitStableSyncCursor(
                receiver,
                config.eventCatchupTimeoutMs,
            )
            val catchupStarted = System.nanoTime()
            laggingReceiver.imClient.resumeReconnectAfterSimulatedDrop()
            val catchupCompleted = withTimeoutOrNull(config.eventCatchupTimeoutMs) {
                laggingReceiver.awaitAuthenticationAfter(
                    previousCount = catchupAuthenticationBefore,
                    timeoutMs = config.eventCatchupTimeoutMs,
                )
                laggingReceiver.awaitSyncCursorAtLeast(
                    eventId = catchupTargetCursor,
                    timeoutMs = config.eventCatchupTimeoutMs,
                )
                while (true) {
                    val converged = primaryConversations.count { (chatId, expected) ->
                        laggingReceiver.conversation(chatId) == expected
                    }
                    if (converged == primaryConversations.size) break
                    delay(50)
                }
                true
            } == true
            val catchupElapsed = System.nanoTime() - catchupStarted
            val catchupAuthenticationAfter = laggingReceiver.authenticationCount
            val catchupFinalCursor = laggingReceiver.syncCursor()
            val convergedConversations = primaryConversations.count { (chatId, expected) ->
                laggingReceiver.conversation(chatId) == expected
            }
            val replayMessageSeqs = collectReplayedMessageSeqs(
                receiver = laggingReceiver,
                lanes = lanes,
                expectedIdentities = expectedIdentities,
            )
            val replayIntegrity = buildCapacityIntegrity(
                laneIds = lanes.mapTo(linkedSetOf(), CapacityLane::id),
                expectedMessages = expectedMessages,
                acknowledgements = acknowledgements,
                notificationSeqs = replayMessageSeqs,
                historySeqs = historySeqs,
            )
            val localProjectionMessageSeqs = collectLocalProjectionMessageSeqs(
                receiver = laggingReceiver,
                lanes = lanes,
                expectedIdentities = expectedIdentities,
            )
            val localProjectionIntegrity = buildCapacityIntegrity(
                laneIds = lanes.mapTo(linkedSetOf(), CapacityLane::id),
                expectedMessages = expectedMessages,
                acknowledgements = acknowledgements,
                notificationSeqs = localProjectionMessageSeqs,
                historySeqs = historySeqs,
            )
            val eventCatchup = buildCapacityEventCatchupResult(
                baseCursor = catchupBaseCursor,
                targetCursor = catchupTargetCursor,
                finalCursor = catchupFinalCursor,
                minimumBacklogEvents = config.eventCatchupMinimumEvents,
                syncPageSize = SyncBatchPayload.MAX_EVENTS,
                authenticationCountBefore = catchupAuthenticationBefore,
                authenticationCountAfter = catchupAuthenticationAfter,
                replayIntegrity = replayIntegrity,
                localProjectionIntegrity = localProjectionIntegrity,
                expectedConversations = primaryConversations.size,
                convergedConversations = convergedConversations,
                elapsedNanos = catchupElapsed,
            ).let { result ->
                if (catchupCompleted) result else result.copy(passed = false)
            }
            val scenarios = listOf(warmup.metrics(), steady.metrics(), burst.metrics())
            val report = MessageCapacityReport(
                generatedAt = Instant.now().toString(),
                target = CapacityTarget(RemoteAcceptanceSupport.host, RemoteAcceptanceSupport.port),
                config = config.reportConfig,
                scenarios = scenarios,
                recovery = recovery,
                eventCatchup = eventCatchup,
                integrity = integrity,
                passed = scenarios[0].failed == 0 && scenarios[1].failed == 0 &&
                    recovery.passed && eventCatchup.passed && integrity.passed,
            )
            CapacityReportWriter.writeAtomically(report, config.reportFile)
            println("[Capacity] report=${config.reportFile.absolutePath}")
            println(
                "[Capacity] lanes=${lanes.size} " +
                    "steady=${scenarios[1].throughputPerSecond} msg/s " +
                    "burst=${scenarios[2].throughputPerSecond} msg/s " +
                    "busy503=$initialBusyRejected recoveryMs=${recovery.elapsedMs} " +
                    "catchupEvents=${eventCatchup.backlogEvents} " +
                    "minimumCatchupPages=${eventCatchup.minimumReplayPages} " +
                    "catchupMs=${eventCatchup.elapsedMs}",
            )
            check(report.passed) {
                "Remote multi-user capacity baseline failed; inspect the machine-readable report"
            }
        } finally {
            sessions.asReversed().forEach { session -> runCatching(session::close) }
        }
    }

    private suspend fun runPerLaneScenario(
        name: String,
        messagesPerLane: Int,
        intervalMs: Long,
        lanes: List<CapacityLane>,
        runId: String,
        ackTimeoutMs: Long,
    ): ScenarioRun = coroutineScope {
        val started = System.nanoTime()
        val laneRuns = lanes.map { lane ->
            async {
                val logicalMessages = mutableListOf<LogicalMessage>()
                val results = mutableListOf<SubmitAttempt>()
                repeat(messagesPerLane) { index ->
                    val logical = logicalMessage(
                        lane = lane,
                        clientMsgId = "cap-$runId-$name-l${lane.id}-$index",
                        text = "capacity $name lane ${lane.id} message $index",
                    )
                    logicalMessages += logical
                    results += submitOne(logical, name, attemptNumber = 1, timeoutMs = ackTimeoutMs)
                    if (index + 1 < messagesPerLane && intervalMs > 0L) delay(intervalMs)
                }
                logicalMessages to results
            }
        }.awaitAll()
        ScenarioRun(
            name = name,
            logicalMessages = laneRuns.flatMap { it.first },
            results = laneRuns.flatMap { it.second },
            elapsedNanos = System.nanoTime() - started,
        )
    }

    private suspend fun runBurstScenario(
        config: CapacityConfig,
        lanes: List<CapacityLane>,
        runId: String,
    ): ScenarioRun = coroutineScope {
        val logicalMessages = List(config.burstMessagesTotal) { index ->
            val lane = lanes[index % lanes.size]
            logicalMessage(
                lane = lane,
                clientMsgId = "cap-$runId-burst-l${lane.id}-$index",
                text = "capacity burst lane ${lane.id} message $index",
            )
        }
        val nextIndex = AtomicInteger()
        val started = System.nanoTime()
        val workerResults = List(min(config.burstConcurrency, logicalMessages.size)) {
            async {
                buildList {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= logicalMessages.size) break
                        add(
                            submitOne(
                                logicalMessage = logicalMessages[index],
                                scenario = "burst",
                                attemptNumber = 1,
                                timeoutMs = config.ackTimeoutMs,
                            ),
                        )
                    }
                }
            }
        }.awaitAll()
        ScenarioRun(
            name = "burst",
            logicalMessages = logicalMessages,
            results = workerResults.flatten().sortedBy { it.logicalMessage.identity.clientMsgId },
            elapsedNanos = System.nanoTime() - started,
        )
    }

    private suspend fun recoverMessages(
        messages: List<LogicalMessage>,
        config: CapacityConfig,
        deadlineNanos: Long,
        scenario: String,
        firstAttemptNumber: Int,
    ): RecoveryRun {
        // Keep the original Message instance: chatId, clientMsgId, timestamp and body are the
        // immutable idempotency request. Recovery must never manufacture a new message generation.
        val pending = messages.associateByTo(linkedMapOf(), LogicalMessage::identity)
        val successful = linkedMapOf<CapacityMessageIdentity, SubmitAttempt>()
        val terminal = linkedMapOf<CapacityMessageIdentity, SubmitAttempt>()
        val attempts = mutableListOf<SubmitAttempt>()
        var round = 0
        while (pending.isNotEmpty() && System.nanoTime() < deadlineNanos) {
            val snapshot = pending.values.groupBy { it.lane.id }
            val roundResults = coroutineScope {
                snapshot.values.map { laneMessages ->
                    async {
                        buildList {
                            for (logical in laneMessages) {
                                val timeoutMs = recoveryAttemptTimeoutMs(
                                    deadlineNanos = deadlineNanos,
                                    configuredTimeoutMs = config.ackTimeoutMs,
                                ) ?: break
                                add(
                                    submitOne(
                                        logicalMessage = logical,
                                        scenario = scenario,
                                        attemptNumber = firstAttemptNumber + round,
                                        timeoutMs = timeoutMs,
                                    ),
                                )
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
            if (roundResults.isEmpty()) break
            attempts += roundResults
            roundResults.forEach { result ->
                when {
                    result.succeeded -> {
                        successful[result.logicalMessage.identity] = result
                        pending.remove(result.logicalMessage.identity)
                    }

                    !isRecoverableCapacityFailure(result.failureCategory) -> {
                        terminal[result.logicalMessage.identity] = result
                        pending.remove(result.logicalMessage.identity)
                    }
                }
            }
            round += 1
            if (pending.isNotEmpty()) {
                val remainingMillis = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND)
                    .coerceAtLeast(0L)
                if (remainingMillis > 0L) {
                    delay(min(config.recoveryRetryIntervalMs, remainingMillis))
                }
            }
        }
        return RecoveryRun(
            attempts = attempts,
            successful = successful,
            terminal = terminal,
            pending = pending.keys.toSet(),
        )
    }

    private suspend fun sendFreshRecoveryProbes(
        messages: List<LogicalMessage>,
        config: CapacityConfig,
        deadlineNanos: Long,
    ): RecoveryRun = recoverMessages(
        // These messages are fresh relative to the burst. If a probe itself meets residual
        // overload or an unknown transport outcome, retry this exact immutable Message identity
        // until the shared recovery deadline rather than creating another logical message.
        messages = messages,
        config = config,
        deadlineNanos = deadlineNanos,
        scenario = "recovery-probe",
        firstAttemptNumber = 1,
    )

    private fun recoveryAttemptTimeoutMs(
        deadlineNanos: Long,
        configuredTimeoutMs: Long,
    ): Long? {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return null
        val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1L) /
            NANOS_PER_MILLISECOND
        return min(configuredTimeoutMs, remainingMillis).coerceAtLeast(1L)
    }

    private suspend fun submitOne(
        logicalMessage: LogicalMessage,
        scenario: String,
        attemptNumber: Int,
        timeoutMs: Long,
    ): SubmitAttempt {
        val started = System.nanoTime()
        return try {
            val message = logicalMessage.message
            val ack = logicalMessage.lane.sender.imClient.sendAndWaitAck(message, timeoutMs)
            val latency = System.nanoTime() - started
            val failureCategory = classifyCapacityAcknowledgement(
                expectedIdentity = logicalMessage.identity,
                actualIdentity = CapacityMessageIdentity(ack.chatId, ack.clientMsgId),
                serverSeq = ack.serverSeq,
                code = ack.code,
            )
            SubmitAttempt(
                logicalMessage = logicalMessage,
                scenario = scenario,
                attemptNumber = attemptNumber,
                latencyNanos = latency,
                ackCode = ack.code,
                serverSeq = ack.serverSeq.takeIf { failureCategory == null },
                failureCategory = failureCategory,
            )
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                failedAttempt(
                    logicalMessage,
                    scenario,
                    attemptNumber,
                    CapacityFailureCategory.TIMEOUT,
                )
            } else {
                throw cancelled
            }
        } catch (_: TransportUnavailableException) {
            failedAttempt(
                logicalMessage,
                scenario,
                attemptNumber,
                CapacityFailureCategory.TRANSPORT,
            )
        } catch (_: IOException) {
            failedAttempt(
                logicalMessage,
                scenario,
                attemptNumber,
                CapacityFailureCategory.TRANSPORT,
            )
        } catch (_: Exception) {
            failedAttempt(
                logicalMessage,
                scenario,
                attemptNumber,
                CapacityFailureCategory.UNEXPECTED,
            )
        }
    }

    private fun failedAttempt(
        logicalMessage: LogicalMessage,
        scenario: String,
        attemptNumber: Int,
        failureCategory: String,
    ) = SubmitAttempt(
        logicalMessage = logicalMessage,
        scenario = scenario,
        attemptNumber = attemptNumber,
        latencyNanos = null,
        ackCode = null,
        serverSeq = null,
        failureCategory = failureCategory,
    )

    private suspend fun collectNotifications(
        receiver: RemoteAcceptanceSupport.Session,
        lanes: List<CapacityLane>,
        expectedIdentities: Set<CapacityMessageIdentity>,
        timeoutMs: Long,
    ): Map<CapacityMessageIdentity, List<Long>> {
        val laneByChatId = lanes.associateBy(CapacityLane::chatId)
        val observed = mutableMapOf<CapacityMessageIdentity, MutableList<Long>>()
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        var completeAt: Long? = null
        while (System.nanoTime() < deadline) {
            try {
                val notify = receiver.awaitNotify(NotifyType.MESSAGE_RECV.code, timeoutMs = 250)
                val payload = notify.payload ?: continue
                val message = ProtoCodec.decode(Message, payload)
                val lane = laneByChatId[message.chatId] ?: continue
                val identity = CapacityMessageIdentity(message.chatId, message.clientMsgId)
                if (identity in expectedIdentities) {
                    observed.getOrPut(identity, ::mutableListOf) += message.serverSeq
                    if (observed.keys.containsAll(expectedIdentities) && completeAt == null) {
                        completeAt = System.nanoTime()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                val completed = completeAt
                if (completed != null && System.nanoTime() - completed >= NOTIFICATION_QUIET_NANOS) break
            }
        }
        return observed
    }

    private fun collectReplayedMessageSeqs(
        receiver: RemoteAcceptanceSupport.Session,
        lanes: List<CapacityLane>,
        expectedIdentities: Set<CapacityMessageIdentity>,
    ): Map<CapacityMessageIdentity, List<Long>> {
        val expectedChatIds = lanes.mapTo(hashSetOf(), CapacityLane::chatId)
        return receiver.observedMessageEvents().asSequence()
            .map { (_, message) -> message }
            .filter { message -> message.chatId in expectedChatIds }
            .map { message ->
                CapacityMessageIdentity(message.chatId, message.clientMsgId) to message.serverSeq
            }
            .filter { (identity, _) -> identity in expectedIdentities }
            .groupBy(
                keySelector = { (identity, _) -> identity },
                valueTransform = { (_, serverSeq) -> serverSeq },
            )
    }

    private fun collectLocalProjectionMessageSeqs(
        receiver: RemoteAcceptanceSupport.Session,
        lanes: List<CapacityLane>,
        expectedIdentities: Set<CapacityMessageIdentity>,
    ): Map<CapacityMessageIdentity, List<Long>> = lanes.asSequence()
        .flatMap { lane -> receiver.messages(lane.chatId).asSequence() }
        .map { message ->
            CapacityMessageIdentity(message.chatId, message.clientMsgId) to message.serverSeq
        }
        .filter { (identity, _) -> identity in expectedIdentities }
        .groupBy(
            keySelector = { (identity, _) -> identity },
            valueTransform = { (_, serverSeq) -> serverSeq },
        )

    private suspend fun awaitStableSyncCursor(
        receiver: RemoteAcceptanceSupport.Session,
        timeoutMs: Long,
    ): Long = withTimeout(timeoutMs) {
        var cursor = receiver.syncCursor()
        var unchangedSince = System.nanoTime()
        while (true) {
            delay(CURSOR_STABILITY_POLL_MILLIS)
            val current = receiver.syncCursor()
            if (current != cursor) {
                cursor = current
                unchangedSince = System.nanoTime()
            } else if (System.nanoTime() - unchangedSince >= CURSOR_STABILITY_QUIET_NANOS) {
                return@withTimeout current
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun collectHistory(
        receiver: RemoteAcceptanceSupport.Session,
        lanes: List<CapacityLane>,
        expectedIdentities: Set<CapacityMessageIdentity>,
    ): Map<CapacityMessageIdentity, List<Long>> {
        val observed = mutableMapOf<CapacityMessageIdentity, MutableList<Long>>()
        lanes.forEach { lane ->
            val expectedForLane = expectedIdentities.filterTo(hashSetOf()) {
                it.chatId == lane.chatId
            }
            var fromSeq = 0L
            while (true) {
                val response = receiver.invoke(
                    MessageRpcContract.SERVICE,
                    MessageRpcContract.M_GET_HISTORY,
                    MessageRpcContract.encodeGetHistory(
                        lane.chatId,
                        fromSeq,
                        Message.MAX_QUERY_PAGE_SIZE,
                    ),
                )
                check(response.status == 0 && response.payload != null) {
                    "Unable to read capacity history for lane ${lane.id}"
                }
                val page = ProtoCodec.decodeList(Message, requireNotNull(response.payload))
                if (page.isEmpty()) break
                page.forEach { message ->
                    val identity = CapacityMessageIdentity(message.chatId, message.clientMsgId)
                    if (identity in expectedForLane) {
                        observed.getOrPut(identity, ::mutableListOf) += message.serverSeq
                    }
                }
                val oldest = page.last().serverSeq
                if (page.size < Message.MAX_QUERY_PAGE_SIZE || oldest <= 1L) break
                fromSeq = oldest - 1L
            }
        }
        return observed
    }

    private suspend fun createPersonalChat(
        sender: RemoteAcceptanceSupport.Session,
        receiver: RemoteAcceptanceSupport.Session,
    ): String {
        val apply = sender.invoke(
            ContactRpcContract.SERVICE,
            ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload {
                writeString(receiver.uid)
                writeString("capacity baseline")
            },
        )
        check(apply.status == 0) { "Unable to create capacity contact fixture" }
        val pendingToken = receiver.pendingApplyToken(sender.uid)
        val accept = receiver.invoke(
            ContactRpcContract.SERVICE,
            ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(pendingToken)
            },
        )
        check(accept.status == 0) { "Unable to accept capacity contact fixture" }
        val response = sender.invoke(
            ChatRpcContract.SERVICE,
            ChatRpcContract.M_CREATE_PERSONAL,
            ProtoCodec.encodePayload { writeString(receiver.uid) },
        )
        check(response.status == 0 && response.payload != null) {
            "Unable to create capacity chat fixture"
        }
        return ProtoCodec.decode(Chat, requireNotNull(response.payload)).chatId
    }

    private fun logicalMessage(
        lane: CapacityLane,
        clientMsgId: String,
        text: String,
    ): LogicalMessage = LogicalMessage(
        lane = lane,
        message = Message(
            chatId = lane.chatId,
            clientMsgId = clientMsgId,
            senderUid = lane.sender.uid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody(text),
        ),
    )

    private data class CapacityLane(
        val id: Int,
        val sender: RemoteAcceptanceSupport.Session,
        val chatId: String,
    )

    private data class LogicalMessage(
        val lane: CapacityLane,
        val message: Message,
    ) {
        val identity = CapacityMessageIdentity(message.chatId, message.clientMsgId)
        val trackedMessage = CapacityTrackedMessage(lane.id, identity)
    }

    private data class SubmitAttempt(
        val logicalMessage: LogicalMessage,
        val scenario: String,
        val attemptNumber: Int,
        val latencyNanos: Long?,
        val ackCode: Int?,
        val serverSeq: Long?,
        val failureCategory: String?,
    ) {
        val succeeded: Boolean
            get() = failureCategory == null && ackCode == 0 && serverSeq != null

        fun acceptedMessageOrNull(): CapacityAcceptedMessage? = serverSeq
            ?.takeIf { succeeded }
            ?.let { CapacityAcceptedMessage(logicalMessage.trackedMessage, it) }
    }

    private data class ScenarioRun(
        val name: String,
        val logicalMessages: List<LogicalMessage>,
        val results: List<SubmitAttempt>,
        val elapsedNanos: Long,
    ) {
        fun metrics(): CapacityScenarioMetrics {
            val failures = results.mapNotNull(SubmitAttempt::failureCategory)
            return CapacityScenarioMetrics(
                name = name,
                attempted = results.size,
                succeeded = results.count(SubmitAttempt::succeeded),
                failed = failures.size,
                busyRejected = failures.count { it == CapacityFailureCategory.BUSY_503 },
                timedOut = failures.count { it == CapacityFailureCategory.TIMEOUT },
                transportFailed = failures.count { it == CapacityFailureCategory.TRANSPORT },
                failuresByCategory = failureCounts(failures),
                elapsedMs = elapsedMillis(elapsedNanos),
                throughputPerSecond = capacityThroughputPerSecond(results.size, elapsedNanos),
                ackLatency = summarizeAckLatencies(
                    results.filter(SubmitAttempt::succeeded).mapNotNull(SubmitAttempt::latencyNanos),
                ),
            )
        }
    }

    private data class RecoveryRun(
        val attempts: List<SubmitAttempt>,
        val successful: Map<CapacityMessageIdentity, SubmitAttempt>,
        val terminal: Map<CapacityMessageIdentity, SubmitAttempt>,
        val pending: Set<CapacityMessageIdentity>,
    )

    private data class CapacityConfig(
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
        val reportFile: File,
    ) {
        val reportConfig = CapacityRunConfig(
            senderLanes = senderLanes,
            warmupMessagesPerLane = warmupMessagesPerLane,
            steadyMessagesPerLane = steadyMessagesPerLane,
            steadyIntervalMs = steadyIntervalMs,
            burstMessagesTotal = burstMessagesTotal,
            burstConcurrency = burstConcurrency,
            ackTimeoutMs = ackTimeoutMs,
            deliveryTimeoutMs = deliveryTimeoutMs,
            recoveryTimeoutMs = recoveryTimeoutMs,
            recoveryRetryIntervalMs = recoveryRetryIntervalMs,
            eventCatchupTimeoutMs = eventCatchupTimeoutMs,
            eventCatchupMinimumEvents = eventCatchupMinimumEvents,
        )

        companion object {
            fun fromSystemProperties(): CapacityConfig {
                fun int(name: String, default: Int, range: IntRange): Int =
                    (System.getProperty(name)?.toIntOrNull() ?: default).also {
                        require(it in range) { "$name must be in ${range.first}..${range.last}" }
                    }

                fun long(name: String, default: Long, range: LongRange): Long =
                    (System.getProperty(name)?.toLongOrNull() ?: default).also {
                        require(it in range) { "$name must be in ${range.first}..${range.last}" }
                    }

                val senderLanes = int("tk.capacity.sender.lanes", 4, 1..16)
                val warmupMessages = int(
                    "tk.capacity.warmup.messagesPerLane",
                    1,
                    0..20,
                )
                val steadyMessages = int(
                    "tk.capacity.steady.messagesPerLane",
                    30,
                    1..500,
                )
                val burstMessages = int("tk.capacity.burst.messagesTotal", 80, 1..1_000)
                val burstConcurrency = int("tk.capacity.burst.concurrency", 16, 1..128)
                require(burstConcurrency <= burstMessages) {
                    "tk.capacity.burst.concurrency must not exceed burst messages"
                }
                val maximumMessagesPerLane = warmupMessages + steadyMessages +
                    (burstMessages + senderLanes - 1) / senderLanes + 1
                require(maximumMessagesPerLane <= LocalCache.MAX_MESSAGE_READ_LIMIT) {
                    "capacity messages per lane must not exceed the bounded E2E projection read " +
                        "limit ${LocalCache.MAX_MESSAGE_READ_LIMIT}"
                }
                return CapacityConfig(
                    senderLanes = senderLanes,
                    warmupMessagesPerLane = warmupMessages,
                    steadyMessagesPerLane = steadyMessages,
                    steadyIntervalMs = long(
                        "tk.capacity.steady.intervalMs",
                        100L,
                        0L..10_000L,
                    ),
                    burstMessagesTotal = burstMessages,
                    burstConcurrency = burstConcurrency,
                    ackTimeoutMs = long(
                        "tk.capacity.ack.timeoutMs",
                        10_000L,
                        1_000L..60_000L,
                    ),
                    deliveryTimeoutMs = long(
                        "tk.capacity.delivery.timeoutMs",
                        30_000L,
                        5_000L..120_000L,
                    ),
                    recoveryTimeoutMs = long(
                        "tk.capacity.recovery.timeoutMs",
                        30_000L,
                        5_000L..120_000L,
                    ),
                    recoveryRetryIntervalMs = long(
                        "tk.capacity.recovery.retryIntervalMs",
                        250L,
                        50L..5_000L,
                    ),
                    eventCatchupTimeoutMs = long(
                        "tk.capacity.eventCatchup.timeoutMs",
                        60_000L,
                        5_000L..300_000L,
                    ),
                    eventCatchupMinimumEvents = int(
                        "tk.capacity.eventCatchup.minimumEvents",
                        128,
                        (SyncBatchPayload.MAX_EVENTS + 1)..100_000,
                    ),
                    reportFile = File(
                        requireNotNull(System.getProperty("tk.capacity.report")) {
                            "capacityTest must provide tk.capacity.report"
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val CAPACITY_FIXTURE_PASSWORD = "password123"
        private const val CURSOR_STABILITY_POLL_MILLIS = 50L
        private const val CURSOR_STABILITY_QUIET_NANOS = 500_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NOTIFICATION_QUIET_NANOS = 750_000_000L

        @JvmStatic
        @AfterAll
        fun shutdownRemoteSupport() {
            RemoteAcceptanceSupport.shutdown()
        }
    }
}
