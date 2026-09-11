package com.virjar.tk.server.domain.telemetry

import com.virjar.tk.protocol.telemetry.ClientTelemetryValidation
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
import com.virjar.tk.protocol.telemetry.TelemetryUserNoticePayload

/**
 * 遥测基线采集的领域策略：客户端时钟可接受窗口、服务端策略到 wire 策略的映射，
 * 以及基线模式下哪些客户端事件被接纳。路由层只做 HTTP 状态映射。
 */

internal const val BASELINE_EVENTS_PER_MINUTE = 120
internal const val BASELINE_BYTES_PER_DAY = 8L * 1024L * 1024L
internal const val BASELINE_BATCH_EVENTS = 64
internal const val BASELINE_UPLOAD_INTERVAL_SECONDS = 300
internal const val DIAGNOSTIC_EVENTS_PER_MINUTE = 1_200
internal const val DIAGNOSTIC_BYTES_PER_DAY = 64L * 1024L * 1024L
internal const val DIAGNOSTIC_BATCH_EVENTS = 256
internal const val DIAGNOSTIC_UPLOAD_INTERVAL_SECONDS = 30
internal const val MAX_CLIENT_CLOCK_SKEW_MILLIS = 10L * 60L * 1_000L
private const val BASELINE_SYSTEM_EVENT = "connection_state"

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
    "decode",
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

internal fun TelemetryBatch.hasAcceptableClientTimes(receivedAt: Long): Boolean {
    val oldest = receivedAt - TelemetryStoragePolicy.RETENTION_MILLIS
    val newest = receivedAt + MAX_CLIENT_CLOCK_SKEW_MILLIS
    return createdAtEpochMs in oldest..newest &&
        events.all { it.occurredAtEpochMs in oldest..newest }
}

internal fun ClientTelemetryPolicy.toWirePolicy(): TelemetryPolicy {
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

internal fun TelemetryEvent.isApprovedBaselineEvent(): Boolean = when (val body = payload) {
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

internal fun TelemetryEvent.baselineEventName(): String? = when (val body = payload) {
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
