package com.virjar.tk.app.telemetry

import com.virjar.tk.shared.client.ClientTelemetryRecorder
import com.virjar.tk.protocol.telemetry.TelemetryActionOutcome
import com.virjar.tk.protocol.telemetry.TelemetryMediaKind
import com.virjar.tk.protocol.telemetry.TelemetryMediaOperation
import com.virjar.tk.protocol.telemetry.TelemetryNoticeLevel
import com.virjar.tk.protocol.telemetry.TelemetryNoticeOrigin
import com.virjar.tk.protocol.telemetry.TelemetryPageExitReason

/** 类型化 UI 词汇表适配器。它刻意没有自由格式字符串入口点。 */
class SessionClientUiTelemetrySink(
    private val recorder: ClientTelemetryRecorder?,
) : ClientUiTelemetrySink {
    override fun recordUserNotice(notice: UserFeedbackNotice) = safely {
        recorder?.recordUserNotice(
            feedbackCode = notice.feedbackCode.code,
            page = notice.page.code,
            action = notice.action.code,
            origin = notice.origin.toProtocol(),
            message = notice.publicMessage,
            level = notice.feedbackCode.noticeLevel(),
        )
    }

    override fun recordPageDwell(
        page: ClientUiPage,
        durationMillis: Long,
        exitReason: ClientPageExitReason,
    ) = safely {
        recorder?.recordPageDwell(
            page = page.code,
            durationMillis = durationMillis,
            exitReason = exitReason.toProtocol(),
        )
    }

    override fun recordAction(
        page: ClientUiPage,
        action: ClientUiAction,
        outcome: ClientActionOutcome,
    ) = safely {
        recorder?.recordAction(page.code, action.code, outcome.toProtocol())
    }

    override fun recordSystem(event: ClientSystemEvent, state: ClientSystemState) = safely {
        recorder?.recordSystem(
            name = event.code,
            state = state.code,
            critical = event == ClientSystemEvent.CONNECTION_STATE &&
                (state == ClientSystemState.DISCONNECTED ||
                    state == ClientSystemState.AUTHENTICATION_FAILED),
        )
    }

    override fun recordMedia(
        page: ClientUiPage,
        mediaKind: ClientMediaKind,
        operation: MediaOperation,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason?,
    ) = safely {
        recorder?.recordAction(
            page = page.code,
            action = operation.action.code,
            outcome = outcome.toProtocol(),
        )
        recorder?.recordMedia(
            mediaKind = mediaKind.toProtocol(),
            operation = operation.toProtocol(),
            outcome = outcome.toProtocol(),
            reasonCode = reason?.code,
        )
    }

    override fun recordFault(
        code: ClientFaultCode,
        page: ClientUiPage?,
        action: ClientUiAction?,
        origin: FeedbackOrigin,
        reason: ClientFaultReason,
    ) = safely {
        val fields = clientFaultTelemetryFields(code, page, action, origin, reason)
        recorder?.recordFault(
            code = fields.code,
            page = fields.page,
            action = fields.action,
            origin = fields.origin,
            reasonCode = fields.reasonCode,
        )
    }

    /** 遥测绝不能使一个本来有效的用户动作失败。 */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // 不要通过 AppLog 上报：那会递归回到这个 recorder。
        }
    }
}

internal data class ClientFaultTelemetryFields(
    val code: String,
    val page: String?,
    val action: String?,
    val origin: String,
    val reasonCode: String,
)

/** 纯类型化到 wire 的转换；缺失的平台上下文必须在 wire 上保持缺失。 */
internal fun clientFaultTelemetryFields(
    code: ClientFaultCode,
    page: ClientUiPage?,
    action: ClientUiAction?,
    origin: FeedbackOrigin,
    reason: ClientFaultReason,
) = ClientFaultTelemetryFields(
    code = code.code,
    page = page?.code,
    action = action?.code,
    origin = origin.code,
    reasonCode = reason.code,
)

private val MediaOperation.action: ClientUiAction
    get() = when (this) {
        MediaOperation.RECORD -> ClientUiAction.START_VOICE_RECORDING
        MediaOperation.UPLOAD -> ClientUiAction.UPLOAD_MEDIA
        MediaOperation.DOWNLOAD -> ClientUiAction.DOWNLOAD_MEDIA
        MediaOperation.PREVIEW,
        MediaOperation.PLAY,
        MediaOperation.OPEN,
        -> ClientUiAction.OPEN_MEDIA
    }

private fun FeedbackOrigin.toProtocol(): TelemetryNoticeOrigin = when (this) {
    FeedbackOrigin.TOAST -> TelemetryNoticeOrigin.TOAST
    FeedbackOrigin.SNACKBAR -> TelemetryNoticeOrigin.SNACKBAR
    FeedbackOrigin.DIALOG -> TelemetryNoticeOrigin.DIALOG
    FeedbackOrigin.INLINE -> TelemetryNoticeOrigin.INLINE
    FeedbackOrigin.SYSTEM -> TelemetryNoticeOrigin.SYSTEM
}

private fun ClientPageExitReason.toProtocol(): TelemetryPageExitReason = when (this) {
    ClientPageExitReason.NAVIGATION -> TelemetryPageExitReason.NAVIGATION
    ClientPageExitReason.BACKGROUND -> TelemetryPageExitReason.BACKGROUND
    ClientPageExitReason.SESSION_END -> TelemetryPageExitReason.SESSION_END
}

private fun ClientActionOutcome.toProtocol(): TelemetryActionOutcome = when (this) {
    ClientActionOutcome.STARTED -> TelemetryActionOutcome.STARTED
    ClientActionOutcome.QUEUED -> TelemetryActionOutcome.QUEUED
    ClientActionOutcome.SUCCEEDED -> TelemetryActionOutcome.SUCCEEDED
    ClientActionOutcome.FAILED -> TelemetryActionOutcome.FAILED
    ClientActionOutcome.CANCELLED -> TelemetryActionOutcome.CANCELLED
}

private fun ClientMediaKind.toProtocol(): TelemetryMediaKind = when (this) {
    ClientMediaKind.IMAGE -> TelemetryMediaKind.IMAGE
    ClientMediaKind.VIDEO -> TelemetryMediaKind.VIDEO
    ClientMediaKind.AUDIO -> TelemetryMediaKind.AUDIO
    ClientMediaKind.FILE -> TelemetryMediaKind.FILE
    ClientMediaKind.UNKNOWN -> TelemetryMediaKind.UNKNOWN
}

private fun MediaOperation.toProtocol(): TelemetryMediaOperation = when (this) {
    MediaOperation.RECORD -> TelemetryMediaOperation.RECORD
    MediaOperation.UPLOAD -> TelemetryMediaOperation.UPLOAD
    MediaOperation.DOWNLOAD -> TelemetryMediaOperation.DOWNLOAD
    MediaOperation.PREVIEW -> TelemetryMediaOperation.PREVIEW
    MediaOperation.PLAY -> TelemetryMediaOperation.PLAYBACK
    MediaOperation.OPEN -> TelemetryMediaOperation.OPEN
}

private fun UserFeedbackCode.noticeLevel(): TelemetryNoticeLevel = when (this) {
    UserFeedbackCode.MICROPHONE_PERMISSION_REQUIRED,
    UserFeedbackCode.VOICE_TOO_SHORT,
    UserFeedbackCode.NETWORK_UNAVAILABLE,
    UserFeedbackCode.CHAT_ASSET_UPLOAD_PENDING,
    UserFeedbackCode.RELIABLE_COMMAND_PENDING,
    -> TelemetryNoticeLevel.WARNING
    else -> TelemetryNoticeLevel.ERROR
}
