package com.virjar.tk.server.api

import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.TelemetryBatchConflictException
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceAuthority
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueMetrics
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_MESSAGE
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import com.virjar.tk.server.domain.telemetry.TelemetryStoreBusyException
import com.virjar.tk.server.domain.telemetry.TelemetryStoreCapacityException
import com.virjar.tk.server.domain.telemetry.sanitizeTelemetryDiagnosticText
import com.virjar.tk.server.domain.telemetry.sanitizeTelemetryRuntimeText
import com.virjar.tk.server.domain.telemetry.sanitizeTelemetryStableText
import com.virjar.tk.server.domain.telemetry.sanitizeTelemetryStackFileName
import com.virjar.tk.protocol.telemetry.CLIENT_TELEMETRY_ENDPOINT
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.ClientTelemetryValidation
import com.virjar.tk.protocol.telemetry.TelemetryAck
import com.virjar.tk.protocol.telemetry.TelemetryActionOutcome
import com.virjar.tk.protocol.telemetry.TelemetryActionPayload
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import com.virjar.tk.protocol.telemetry.TelemetryEvent
import com.virjar.tk.protocol.telemetry.TelemetryFaultPayload
import com.virjar.tk.protocol.telemetry.TelemetryFeedbackCode
import com.virjar.tk.protocol.telemetry.TelemetryLogPayload
import com.virjar.tk.protocol.telemetry.TelemetryMediaPayload
import com.virjar.tk.protocol.telemetry.TelemetryOutgoingQueuePayload
import com.virjar.tk.protocol.telemetry.TelemetryPageDwellPayload
import com.virjar.tk.protocol.telemetry.TelemetryPolicy
import com.virjar.tk.protocol.telemetry.TelemetryPolicyMode
import com.virjar.tk.protocol.telemetry.TelemetrySystemPayload
import com.virjar.tk.protocol.telemetry.TelemetryUploadResponse
import com.virjar.tk.protocol.telemetry.TelemetryUserNoticePayload
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

private val telemetryJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    explicitNulls = false
}

/** 已鉴权、保护身份的客户端结构化遥测上传边界。 */
fun Route.clientTelemetryRoutes(
    control: ClientTelemetryControlRepository,
    events: ClientTelemetryEventStore,
    accessTokens: AccessTokenValidator,
    ingressAdmission: ClientTelemetryIngressAdmission = ClientTelemetryIngressAdmission(),
    admission: ClientTelemetryAdmission = ClientTelemetryAdmission(),
    clock: () -> Long = System::currentTimeMillis,
    bodyTimeoutMillis: Long = CLIENT_TELEMETRY_BODY_TIMEOUT_MILLIS,
) {
    require(bodyTimeoutMillis > 0L) { "telemetry body timeout must be positive" }
    post(CLIENT_TELEMETRY_ENDPOINT) {
        val bearer = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf(String::isNotBlank)
        val principal = bearer?.let { accessTokens.validateAccessToken(it) }
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid token"))
        val requestStartedAt = clock()
        if (!ingressAdmission.tryAdmitRequest(principal.uid, principal.deviceId, requestStartedAt)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "telemetry ingress exhausted"))
        }
        if (!call.request.contentType().withoutParameters().match(ContentType.Application.Json) ||
            !call.request.header(HttpHeaders.ContentEncoding).equals("gzip", ignoreCase = true)
        ) {
            return@post call.respond(
                HttpStatusCode.UnsupportedMediaType,
                mapOf("error" to "telemetry requires application/json with gzip content encoding"),
            )
        }

        val compressed = try {
            withTimeout(bodyTimeoutMillis) {
                call.receiveChannel().readTelemetryBody(CLIENT_TELEMETRY_MAX_COMPRESSED_BYTES)
            }
        } catch (_: TimeoutCancellationException) {
            return@post call.respond(HttpStatusCode.RequestTimeout, mapOf("error" to "telemetry upload timed out"))
        } catch (_: TelemetryPayloadTooLargeException) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "telemetry payload too large"))
        }
        val payloadBytes = try {
            decompressTelemetryBody(compressed)
        } catch (_: TelemetryPayloadTooLargeException) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "telemetry payload too large"))
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid gzip telemetry payload"))
        }
        if (payloadBytes.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty telemetry payload"))
        }
        val batch = try {
            telemetryJson.decodeFromString<TelemetryBatch>(payloadBytes.decodeUtf8Strict())
                .also(ClientTelemetryValidation::requireValid)
        } catch (_: SerializationException) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid telemetry JSON"))
        } catch (_: CharacterCodingException) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid telemetry JSON"))
        } catch (_: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid telemetry batch"))
        }
        // 策略、时间戳与持久化决策使用完整有界请求体到达并解码完成的时刻。
        // 一个慢速的已鉴权上传不能保留在其字节仍在传输途中就已过期的诊断授权。
        val receivedAt = clock()
        if (!ingressAdmission.tryAdmitBytes(principal.uid, principal.deviceId, payloadBytes.size, receivedAt)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "telemetry ingress exhausted"))
        }
        val payloadSha256 = sha256Hex(payloadBytes)
        val safeRuntime = batch.runtimeInfo.toSafeSnapshot()
        val deviceAuthority = TelemetryDeviceAuthority(
            uid = principal.uid,
            deviceId = principal.deviceId,
            userCredentialEpoch = principal.userCredentialEpoch,
            deviceCredentialEpoch = principal.deviceCredentialEpoch,
        )
        val hasEvents = batch.events.isNotEmpty()

        // 每个请求都已经消耗了独立的入口预算。在每次拒绝新批次之前都要重新检查，
        // 因为在我们第一次读取之后，一个并发的首次尝试可能已经提交了。
        suspend fun respondCommittedRetry(): Boolean {
            val receipt = try {
                events.findBatchReceipt(principal.uid, principal.deviceId, batch.batchId)
            } catch (_: TelemetrySearchUnavailableException) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "telemetry store unavailable"))
                return true
            }
            if (receipt == null) return false
            if (receipt.payloadSha256 != payloadSha256) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "batch identity conflict"))
                return true
            }
            control.refreshDevice(
                deviceAuthority,
                safeRuntime,
                receivedAt,
                receipt.receivedAt,
                runtimeObservedAt = receipt.receivedAt,
            )
            val currentPolicy = control.effectivePolicy(principal.uid, principal.deviceId, receivedAt).toWirePolicy()
            call.respondTelemetrySuccess(batch.response(receipt.acceptedThroughSequence, currentPolicy))
            return true
        }
        if (hasEvents && respondCommittedRetry()) return@post

        if (!batch.hasAcceptableClientTimes(receivedAt)) {
            if (hasEvents && respondCommittedRetry()) return@post
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "telemetry timestamp is out of range"))
        }

        val policy = control.effectivePolicy(principal.uid, principal.deviceId, receivedAt).toWirePolicy()
        val diagnostic = policy.mode == TelemetryPolicyMode.DIAGNOSTIC && receivedAt < policy.expiresAtEpochMs
        if (batch.events.size > policy.maxBatchEvents ||
            batch.events.any {
                !ClientTelemetryValidation.allows(policy, it, receivedAt) ||
                    (!diagnostic && !it.isApprovedBaselineEvent())
            }
        ) {
            if (hasEvents && respondCommittedRetry()) return@post
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "telemetry is outside current policy"))
        }
        if (!admission.tryAdmit(
                uid = principal.uid,
                deviceId = principal.deviceId,
                eventCount = batch.events.size,
                uncompressedBytes = payloadBytes.size,
                policy = policy,
                now = receivedAt,
            )
        ) {
            if (hasEvents && respondCommittedRetry()) return@post
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "telemetry budget exhausted"))
        }

        if (!hasEvents) {
            control.refreshDevice(
                deviceAuthority,
                safeRuntime,
                receivedAt,
                acceptedEventAt = null,
            )
            val currentPolicy = control.effectivePolicy(principal.uid, principal.deviceId, receivedAt).toWirePolicy()
            return@post call.respondTelemetrySuccess(batch.response(null, currentPolicy))
        }

        val result = try {
            events.ingest(
                uid = principal.uid,
                deviceId = principal.deviceId,
                batch = batch.toDraft(payloadSha256, safeRuntime, diagnostic),
                receivedAt = receivedAt,
                sourceBytes = payloadBytes.size,
            )
        } catch (_: TelemetryBatchConflictException) {
            return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "batch identity conflict"))
        } catch (_: TelemetryStoreBusyException) {
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "telemetry writer busy"))
        } catch (_: TelemetryStoreCapacityException) {
            return@post call.respond(HttpStatusCode.InsufficientStorage, mapOf("error" to "telemetry storage capacity exhausted"))
        } catch (_: TelemetrySearchUnavailableException) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "telemetry store unavailable"))
        } catch (_: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid telemetry authority"))
        }
        control.refreshDevice(
            deviceAuthority,
            safeRuntime,
            receivedAt,
            acceptedEventAt = result.receivedAt,
        )
        val currentPolicy = control.effectivePolicy(principal.uid, principal.deviceId, receivedAt).toWirePolicy()
        val response = batch.response(result.acceptedThroughSequence, currentPolicy)
        call.respondTelemetrySuccess(response)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondTelemetrySuccess(
    response: TelemetryUploadResponse,
) {
    respondText(
        text = telemetryJson.encodeToString(response),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
    )
}

private fun TelemetryBatch.response(sequence: Long?, policy: TelemetryPolicy) =
    TelemetryUploadResponse(
        ack = TelemetryAck(batchId = batchId, acceptedThroughSequence = sequence),
        policy = policy,
    ).also { ClientTelemetryValidation.requireValid(it, this) }

private fun TelemetryBatch.hasAcceptableClientTimes(receivedAt: Long): Boolean {
    val oldest = receivedAt - TelemetryStoragePolicy.RETENTION_MILLIS
    val newest = receivedAt + MAX_CLIENT_CLOCK_SKEW_MILLIS
    return createdAtEpochMs in oldest..newest &&
        events.all { it.occurredAtEpochMs in oldest..newest }
}

private fun ClientTelemetryPolicy.toWirePolicy(): TelemetryPolicy {
    val activeDiagnostic = mode == TelemetryCollectionMode.DIAGNOSTIC && expiresAt != null
    if (!activeDiagnostic && revision == 0L) return TelemetryPolicy.baseline()
    return TelemetryPolicy(
        revision = "server-${revision.coerceAtLeast(1L)}",
        mode = if (activeDiagnostic) TelemetryPolicyMode.DIAGNOSTIC else TelemetryPolicyMode.BASELINE,
        issuedAtEpochMs = updatedAt.coerceAtLeast(0L),
        expiresAtEpochMs = if (activeDiagnostic) checkNotNull(expiresAt) else Long.MAX_VALUE,
        maxEventsPerMinute = if (activeDiagnostic) DIAGNOSTIC_EVENTS_PER_MINUTE else BASELINE_EVENTS_PER_MINUTE,
        maxBytesPerDay = if (activeDiagnostic) DIAGNOSTIC_BYTES_PER_DAY else BASELINE_BYTES_PER_DAY,
        maxBatchEvents = if (activeDiagnostic) DIAGNOSTIC_BATCH_EVENTS else BASELINE_BATCH_EVENTS,
        uploadIntervalSeconds = if (activeDiagnostic) DIAGNOSTIC_UPLOAD_INTERVAL_SECONDS else BASELINE_UPLOAD_INTERVAL_SECONDS,
    ).also(ClientTelemetryValidation::requireValid)
}

private fun TelemetryEvent.isApprovedBaselineEvent(): Boolean = when (val body = payload) {
    is TelemetryFaultPayload ->
        body.faultCode in BASELINE_FAULT_CODES &&
            body.page.isNullOrIn(BASELINE_PAGE_CODES) &&
            body.action.isNullOrIn(BASELINE_ACTION_CODES) &&
            body.origin.isNullOrIn(BASELINE_FAULT_ORIGINS) &&
            body.reasonCode.isNullOrIn(BASELINE_FAULT_REASON_CODES) &&
            eventName == body.baselineEventName()
    is TelemetryUserNoticePayload ->
        TelemetryFeedbackCode.fromCode(body.feedbackCode) != null &&
            body.page.isNullOrIn(BASELINE_PAGE_CODES) &&
            body.action.isNullOrIn(BASELINE_ACTION_CODES) &&
            eventName == body.feedbackCode
    is TelemetrySystemPayload ->
        body.critical &&
            body.name == BASELINE_SYSTEM_EVENT &&
            body.state in BASELINE_SYSTEM_STATES &&
            eventName == body.name
    is TelemetryMediaPayload ->
        body.outcome == TelemetryActionOutcome.FAILED &&
            body.reasonCode.isNullOrIn(BASELINE_MEDIA_REASON_CODES) &&
            eventName == body.baselineEventName()
    is TelemetryOutgoingQueuePayload -> false
    is TelemetryLogPayload,
    is TelemetryPageDwellPayload,
    is TelemetryActionPayload,
    -> false
}

private fun TelemetryBatch.toDraft(
    payloadSha256: String,
    safeRuntime: TelemetryRuntimeSnapshot,
    diagnostic: Boolean,
) = TelemetryBatchDraft(
    batchId = batchId,
    payloadSha256 = payloadSha256,
    createdAt = createdAtEpochMs,
    runtime = safeRuntime,
    events = events.map { event ->
        var outgoingQueue: TelemetryOutgoingQueueMetrics? = null
        val (message, searchText) = when (val payload = event.payload) {
            is TelemetryLogPayload -> {
                val safeLogger = sanitizeTelemetryStableText(payload.logger)
                val safeMessage = sanitizeTelemetryDiagnosticText(payload.message)
                "[${payload.level.name}] $safeLogger: $safeMessage" to
                    "${payload.level.name} $safeLogger $safeMessage"
            }
            is TelemetryFaultPayload -> if (!diagnostic) {
                val context = listOfNotNull(
                    payload.faultCode,
                    payload.page,
                    payload.action,
                    payload.origin,
                    payload.reasonCode,
                    "fatal=${payload.fatal}",
                )
                context.joinToString(" · ") to context.joinToString(" ")
            } else {
                val safeLogger = sanitizeTelemetryStableText(payload.logger)
                val safeFaultCode = sanitizeTelemetryStableText(payload.faultCode)
                val safeSummary = sanitizeTelemetryDiagnosticText(payload.summary)
                "$safeFaultCode: $safeSummary" to buildString {
                    append(safeLogger).append(' ').append(safeFaultCode).append(' ').append(safeSummary)
                    payload.page?.let { append(' ').append(sanitizeTelemetryStableText(it)) }
                    payload.action?.let { append(' ').append(sanitizeTelemetryStableText(it)) }
                    payload.origin?.let { append(' ').append(sanitizeTelemetryStableText(it)) }
                    payload.reasonCode?.let { append(' ').append(sanitizeTelemetryStableText(it)) }
                    payload.exceptionClass?.let {
                        append(' ').append(
                            sanitizeTelemetryStableText(
                                it,
                                ClientTelemetryLimits.MAX_STACK_FIELD_CHARS,
                                "Throwable",
                            ),
                        )
                    }
                    payload.stackFrames.forEach { frame ->
                        append(' ').append(
                            sanitizeTelemetryStableText(
                                frame.className,
                                ClientTelemetryLimits.MAX_STACK_FIELD_CHARS,
                                "Class",
                            ),
                        ).append('.').append(
                            sanitizeTelemetryStableText(
                                frame.methodName,
                                ClientTelemetryLimits.MAX_STACK_FIELD_CHARS,
                                "method",
                            ),
                        )
                        frame.fileName?.let { append(' ').append(sanitizeTelemetryStackFileName(it)) }
                        frame.lineNumber?.let { append(':').append(it) }
                    }
                    append(" fatal=").append(payload.fatal)
                }
            }
            is TelemetryPageDwellPayload -> {
                val safePage = sanitizeTelemetryStableText(payload.page)
                "$safePage · ${payload.durationMillis} ms · ${payload.exitReason.name}" to
                    "$safePage ${payload.exitReason.name} ${payload.durationMillis}"
            }
            is TelemetryActionPayload -> {
                val safePage = sanitizeTelemetryStableText(payload.page)
                val safeAction = sanitizeTelemetryStableText(payload.action)
                "$safePage · $safeAction · ${payload.outcome.name}" to
                    "$safePage $safeAction ${payload.outcome.name}"
            }
            is TelemetrySystemPayload -> {
                val safeName = sanitizeTelemetryStableText(payload.name)
                val safeState = payload.state?.let {
                    if (diagnostic) sanitizeTelemetryDiagnosticText(it)
                    else sanitizeTelemetryStableText(it, ClientTelemetryLimits.MAX_STATE_CHARS)
                }
                val context = listOfNotNull(
                    safeName,
                    safeState,
                    "critical=${payload.critical}",
                )
                context.joinToString(" · ") to context.joinToString(" ")
            }
            is TelemetryUserNoticePayload -> {
                val safeFeedbackCode = sanitizeTelemetryStableText(payload.feedbackCode)
                val reviewed = TelemetryFeedbackCode.fromCode(payload.feedbackCode)
                val safeMessage = reviewed?.publicMessage
                    ?: payload.message.takeIf { diagnostic }?.let(::sanitizeTelemetryDiagnosticText)
                    ?: UNKNOWN_BASELINE_NOTICE
                val messageMismatch = reviewed != null && payload.message != reviewed.publicMessage
                listOfNotNull(
                    safeFeedbackCode,
                    payload.page?.let(::sanitizeTelemetryStableText),
                    payload.action?.let(::sanitizeTelemetryStableText),
                    safeMessage,
                ).joinToString(" · ") to listOfNotNull(
                    safeFeedbackCode,
                    payload.page?.let(::sanitizeTelemetryStableText),
                    payload.action?.let(::sanitizeTelemetryStableText),
                    payload.origin.name,
                    payload.level.name,
                    safeMessage,
                    "messageMismatch=$messageMismatch",
                ).joinToString(" ")
            }
            is TelemetryMediaPayload -> {
                val safeReason = payload.reasonCode?.let(::sanitizeTelemetryStableText)
                listOfNotNull(
                    "${payload.mediaKind.name} ${payload.operation.name}",
                    payload.outcome.name,
                    safeReason,
                    payload.byteCount?.let { "$it bytes" },
                    payload.durationMillis?.let { "$it ms" },
                ).joinToString(" · ") to listOfNotNull(
                    payload.mediaKind.name,
                    payload.operation.name,
                    payload.outcome.name,
                    safeReason,
                    payload.byteCount?.toString(),
                    payload.durationMillis?.toString(),
                ).joinToString(" ")
            }
            is TelemetryOutgoingQueuePayload -> {
                outgoingQueue = TelemetryOutgoingQueueMetrics(
                    pendingCount = payload.pendingCount,
                    retryWaitCount = payload.retryWaitCount,
                    terminalFailedCount = payload.terminalFailedCount,
                    oldestActiveAgeMillis = payload.oldestActiveAgeMillis,
                    maxAttemptCount = payload.maxAttemptCount,
                )
                OUTGOING_QUEUE_STORED_MESSAGE to event.eventName
            }
        }
        val safeEventName = if (diagnostic) {
            sanitizeTelemetryStableText(event.eventName)
        } else {
            checkNotNull(event.baselineEventName()) { "baseline event must have a canonical name" }
        }
        TelemetryEventDraft(
            eventId = event.eventId,
            runId = event.runId,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMs,
            category = event.kind.name,
            eventName = safeEventName,
            message = message,
            searchText = "$safeEventName $searchText",
            outgoingQueue = outgoingQueue,
            connectionTraceContext = event.connectionTraceContext?.let { context ->
                ConnectionTraceContext(
                    correlationId = context.correlationId,
                    traceId = context.traceId,
                    sessionId = context.sessionId,
                    connectionGeneration = context.connectionGeneration,
                    policyRevision = context.policyRevision,
                )
            },
        )
    },
)

private fun TelemetryEvent.baselineEventName(): String? = when (val body = payload) {
    is TelemetryFaultPayload -> body.baselineEventName()
    is TelemetryUserNoticePayload -> body.feedbackCode
    is TelemetrySystemPayload -> body.name
    is TelemetryMediaPayload -> body.baselineEventName()
    is TelemetryOutgoingQueuePayload -> null
    is TelemetryLogPayload,
    is TelemetryPageDwellPayload,
    is TelemetryActionPayload,
    -> null
}

private fun TelemetryFaultPayload.baselineEventName(): String? = when (faultCode) {
    "legacy.app_log" -> "fault.reported"
    "process.uncaught_exception" -> "fault.uncaught"
    in BASELINE_FAULT_CODES -> faultCode
    else -> null
}

private fun TelemetryMediaPayload.baselineEventName(): String =
    reasonCode ?: "media.${operation.name.lowercase()}.${outcome.name.lowercase()}"

private fun String?.isNullOrIn(allowed: Set<String>): Boolean = this == null || this in allowed

private fun ClientRuntimeInfo.toSafeSnapshot(): TelemetryRuntimeSnapshot {
    val normalizedGitCommit = gitCommit.safeBuildFact(
        TELEMETRY_GIT_COMMIT_PATTERN,
        ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS,
    )
    val normalizedBuildIdentity = buildIdentity.safeBuildFact(
        TELEMETRY_BUILD_IDENTITY_PATTERN,
        ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS,
    )
    val identityCommit = normalizedBuildIdentity
        .takeUnless { it == "unknown" }
        ?.substringAfter('+')
        ?.substringBefore('.')
    val buildFactsAgree = normalizedGitCommit == "unknown" ||
        identityCommit == null ||
        identityCommit.startsWith(normalizedGitCommit, ignoreCase = true)
    return TelemetryRuntimeSnapshot(
        platform = platform.name,
        osName = sanitizeTelemetryRuntimeText(osName, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
        osVersion = sanitizeTelemetryRuntimeText(osVersion, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
        architecture = sanitizeTelemetryRuntimeText(architecture, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
        deviceModel = sanitizeTelemetryRuntimeText(deviceModel, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS),
        appVersion = appVersion.safeBuildFact(
            TELEMETRY_APP_VERSION_PATTERN,
            ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
        ),
        buildNumber = buildNumber.safeBuildFact(
            TELEMETRY_BUILD_NUMBER_PATTERN,
            ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
        ),
        gitCommit = normalizedGitCommit.takeIf { buildFactsAgree } ?: "unknown",
        buildIdentity = normalizedBuildIdentity.takeIf { buildFactsAgree } ?: "unknown",
        buildTime = buildTime.safeBuildFact(
            TELEMETRY_BUILD_TIME_PATTERN,
            ClientTelemetryLimits.MAX_BUILD_TIME_CHARS,
        ),
        protocolVersion = protocolVersion,
        distribution = distribution.safeBuildFact(
            TELEMETRY_DISTRIBUTION_PATTERN,
            ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
        ),
    )
}

private fun String.safeBuildFact(pattern: Regex, maxChars: Int): String =
    trim()
        .takeIf { it.length <= maxChars && it.none(Char::isISOControl) }
        ?.takeIf(pattern::matches)
        ?: "unknown"

internal const val CLIENT_TELEMETRY_MAX_COMPRESSED_BYTES = 1024 * 1024
internal const val CLIENT_TELEMETRY_MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024
internal const val CLIENT_TELEMETRY_BODY_TIMEOUT_MILLIS = 15_000L
private const val BEARER_PREFIX = "Bearer "
private const val BASELINE_EVENTS_PER_MINUTE = 120
private const val BASELINE_BYTES_PER_DAY = 8L * 1024L * 1024L
private const val BASELINE_BATCH_EVENTS = 64
private const val BASELINE_UPLOAD_INTERVAL_SECONDS = 300
private const val DIAGNOSTIC_EVENTS_PER_MINUTE = 1_200
private const val DIAGNOSTIC_BYTES_PER_DAY = 64L * 1024L * 1024L
private const val DIAGNOSTIC_BATCH_EVENTS = 256
private const val DIAGNOSTIC_UPLOAD_INTERVAL_SECONDS = 30
private const val MAX_CLIENT_CLOCK_SKEW_MILLIS = 10L * 60L * 1_000L
private const val BASELINE_SYSTEM_EVENT = "connection_state"
private const val UNKNOWN_BASELINE_NOTICE = "客户端展示了未识别提示"
private val BASELINE_SYSTEM_STATES = setOf("disconnected", "authentication_failed")
private val BASELINE_FAULT_CODES = setOf(
    "mark_read_local_failure",
    "media_failure",
    "platform_lifecycle_failure",
    "legacy.app_log",
    "process.uncaught_exception",
)
private val BASELINE_FAULT_ORIGINS = setOf(
    "toast",
    "snackbar",
    "dialog",
    "inline",
    "system",
    "app_log",
    "platform",
)
private val BASELINE_FAULT_REASON_CODES = setOf("sqlite", "local_data", "lifecycle", "unknown")
private val BASELINE_MEDIA_REASON_CODES = setOf(
    "http_denied",
    "http_missing",
    "http_status",
    "cache_quota",
    "size_validation",
    "network",
    "io",
    "session",
    "permission",
    "unsupported",
    "unknown",
)
private val BASELINE_PAGE_CODES = setOf(
    "login",
    "register",
    "conversations",
    "contacts",
    "documents",
    "settings",
    "chat",
    "search_messages",
    "search_users",
    "create_group",
    "friend_applies",
    "user_profile",
    "edit_profile",
    "change_password",
    "devices",
    "blacklist",
    "group_detail",
    "group_files",
    "group_bots",
    "invite_members",
    "invite_links",
    "forward",
    "text_attachment_preview",
    "document_window",
    "media_gallery",
)
private val BASELINE_ACTION_CODES = setOf(
    "show_feedback",
    "open_page",
    "send_message",
    "upload_media",
    "download_media",
    "open_media",
    "start_voice_recording",
    "send_voice_recording",
    "mark_read",
    "create_group",
    "create_invite_link",
    "publish_group_file",
    "save_document",
    "logout",
)
private val TELEMETRY_APP_VERSION_PATTERN = Regex("(?:unknown|(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*))")
private val TELEMETRY_BUILD_NUMBER_PATTERN = Regex("(?:unknown|0|[1-9]\\d*)")
private val TELEMETRY_GIT_COMMIT_PATTERN = Regex("(?i)(?:unknown|[0-9a-f]{12})")
private val TELEMETRY_BUILD_IDENTITY_PATTERN = Regex(
    "(?i)(?:unknown|(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\+" +
        "[0-9a-f]{40}(?:\\.dirty)?)",
)
private val TELEMETRY_BUILD_TIME_PATTERN = Regex(
    "(?:unknown|\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?Z?)?)",
)
private val TELEMETRY_DISTRIBUTION_PATTERN = Regex(
    "(?:unknown|compose-desktop|android-(?:debug|release)|headless)",
)

private class TelemetryPayloadTooLargeException : IllegalArgumentException()

private suspend fun ByteReadChannel.readTelemetryBody(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = readAvailable(buffer)
        if (count == -1) break
        total += count
        if (total > maxBytes) throw TelemetryPayloadTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decompressTelemetryBody(compressed: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(minOf(CLIENT_TELEMETRY_MAX_DECOMPRESSED_BYTES, 8192))
    GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = gzip.read(buffer)
            if (count == -1) break
            total += count
            if (total > CLIENT_TELEMETRY_MAX_DECOMPRESSED_BYTES) throw TelemetryPayloadTooLargeException()
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private fun ByteArray.decodeUtf8Strict(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
