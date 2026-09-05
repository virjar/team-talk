package com.virjar.tk.app.telemetry

import com.virjar.tk.protocol.telemetry.TelemetryFeedbackCode

/** 图形客户端共享的、稳定的、无参数的页面身份。 */
enum class ClientUiPage(val code: String) {
    LOGIN("login"),
    REGISTER("register"),
    CONVERSATIONS("conversations"),
    CONTACTS("contacts"),
    DOCUMENTS("documents"),
    SETTINGS("settings"),
    CHAT("chat"),
    SEARCH_MESSAGES("search_messages"),
    SEARCH_USERS("search_users"),
    CREATE_GROUP("create_group"),
    FRIEND_APPLIES("friend_applies"),
    USER_PROFILE("user_profile"),
    EDIT_PROFILE("edit_profile"),
    CHANGE_PASSWORD("change_password"),
    DEVICES("devices"),
    BLACKLIST("blacklist"),
    GROUP_DETAIL("group_detail"),
    GROUP_FILES("group_files"),
    GROUP_BOTS("group_bots"),
    INVITE_MEMBERS("invite_members"),
    INVITE_LINKS("invite_links"),
    FORWARD("forward"),
    TEXT_ATTACHMENT_PREVIEW("text_attachment_preview"),
    DOCUMENT_WINDOW("document_window"),
    MEDIA_GALLERY("media_gallery"),
}

/** 只记录高价值动作。动态 route 值、搜索词和 test tag 绝不是动作。 */
enum class ClientUiAction(val code: String) {
    SHOW_FEEDBACK("show_feedback"),
    OPEN_PAGE("open_page"),
    SEND_MESSAGE("send_message"),
    UPLOAD_MEDIA("upload_media"),
    DOWNLOAD_MEDIA("download_media"),
    OPEN_MEDIA("open_media"),
    START_VOICE_RECORDING("start_voice_recording"),
    SEND_VOICE_RECORDING("send_voice_recording"),
    MARK_READ("mark_read"),
    CREATE_GROUP("create_group"),
    CREATE_INVITE_LINK("create_invite_link"),
    PUBLISH_GROUP_FILE("publish_group_file"),
    SAVE_DOCUMENT("save_document"),
    LOGOUT("logout"),
}

enum class ClientActionOutcome(val code: String) {
    STARTED("started"),
    QUEUED("queued"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

enum class ClientPageExitReason(val code: String) {
    NAVIGATION("navigation"),
    BACKGROUND("background"),
    SESSION_END("session_end"),
}

enum class FeedbackOrigin(val code: String) {
    TOAST("toast"),
    SNACKBAR("snackbar"),
    DIALOG("dialog"),
    INLINE("inline"),
    SYSTEM("system"),
}

/**
 * 公开文本是一个有限的 wire 级词汇表。调用方不能通过这个边界传递 Throwable.message、
 * 路径、文件名、URL、标识符、搜索文本或消息内容。
 */
typealias UserFeedbackCode = TelemetryFeedbackCode

enum class MediaOperation(val code: String) {
    DOWNLOAD("download"),
    OPEN("open"),
    PREVIEW("preview"),
    UPLOAD("upload"),
    PLAY("play"),
    RECORD("record"),
}

enum class ClientMediaKind(val code: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    FILE("file"),
    UNKNOWN("unknown"),
}

enum class MediaFailureReason(val code: String) {
    HTTP_DENIED("http_denied"),
    HTTP_MISSING("http_missing"),
    HTTP_STATUS("http_status"),
    CACHE_QUOTA("cache_quota"),
    SIZE_VALIDATION("size_validation"),
    NETWORK("network"),
    IO("io"),
    SESSION("session"),
    PERMISSION("permission"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
}

val MediaFailureReason.downloadFeedbackCode: UserFeedbackCode
    get() = when (this) {
        MediaFailureReason.HTTP_DENIED -> UserFeedbackCode.MEDIA_HTTP_DENIED
        MediaFailureReason.HTTP_MISSING -> UserFeedbackCode.MEDIA_HTTP_MISSING
        MediaFailureReason.CACHE_QUOTA -> UserFeedbackCode.MEDIA_CACHE_FULL
        MediaFailureReason.SIZE_VALIDATION -> UserFeedbackCode.MEDIA_SIZE_INVALID
        MediaFailureReason.NETWORK -> UserFeedbackCode.MEDIA_NETWORK_FAILED
        MediaFailureReason.IO -> UserFeedbackCode.MEDIA_IO_FAILED
        MediaFailureReason.SESSION -> UserFeedbackCode.MEDIA_SESSION_CHANGED
        MediaFailureReason.PERMISSION,
        MediaFailureReason.HTTP_STATUS,
        MediaFailureReason.UNSUPPORTED,
        MediaFailureReason.UNKNOWN,
        -> UserFeedbackCode.MEDIA_DOWNLOAD_FAILED
    }

val MediaFailureReason.uploadFeedbackCode: UserFeedbackCode
    get() = when (this) {
        MediaFailureReason.HTTP_DENIED -> UserFeedbackCode.MEDIA_HTTP_DENIED
        MediaFailureReason.HTTP_MISSING -> UserFeedbackCode.MEDIA_HTTP_MISSING
        MediaFailureReason.CACHE_QUOTA -> UserFeedbackCode.MEDIA_CACHE_FULL
        MediaFailureReason.SIZE_VALIDATION -> UserFeedbackCode.MEDIA_SIZE_INVALID
        MediaFailureReason.NETWORK -> UserFeedbackCode.MEDIA_NETWORK_FAILED
        MediaFailureReason.IO -> UserFeedbackCode.MEDIA_IO_FAILED
        MediaFailureReason.SESSION -> UserFeedbackCode.MEDIA_SESSION_CHANGED
        MediaFailureReason.PERMISSION,
        MediaFailureReason.HTTP_STATUS,
        MediaFailureReason.UNSUPPORTED,
        MediaFailureReason.UNKNOWN,
        -> UserFeedbackCode.MEDIA_UPLOAD_FAILED
    }

val MediaFailureReason.recordingFeedbackCode: UserFeedbackCode
    get() = when (this) {
        MediaFailureReason.PERMISSION -> UserFeedbackCode.MICROPHONE_PERMISSION_REQUIRED
        else -> UserFeedbackCode.VOICE_RECORDING_FAILED
    }

enum class ClientSystemEvent(val code: String) {
    CONNECTION_STATE("connection_state"),
    APP_FOREGROUND("app_foreground"),
    APP_BACKGROUND("app_background"),
    WINDOW_OPENED("window_opened"),
    WINDOW_CLOSED("window_closed"),
    WINDOW_FOCUSED("window_focused"),
    WINDOW_UNFOCUSED("window_unfocused"),
}

/** 系统状态的封闭词汇表。绝不传递主机名或框架生成的值。 */
enum class ClientSystemState(val code: String) {
    CONNECTING("connecting"),
    CONNECTED("connected"),
    AUTHENTICATING("authenticating"),
    AUTHENTICATED("authenticated"),
    AUTHENTICATION_FAILED("authentication_failed"),
    SYNCHRONIZING("synchronizing"),
    DISCONNECTED("disconnected"),
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    OPEN("open"),
    CLOSED("closed"),
    FOCUSED("focused"),
    UNFOCUSED("unfocused"),
}

enum class ClientFaultReason(val code: String) {
    SQLITE("sqlite"),
    LOCAL_DATA("local_data"),
    LIFECYCLE("lifecycle"),
    UNKNOWN("unknown"),
}

enum class ClientFaultCode(val code: String) {
    MARK_READ_LOCAL_FAILURE("mark_read_local_failure"),
    MEDIA_FAILURE("media_failure"),
    PLATFORM_LIFECYCLE_FAILURE("platform_lifecycle_failure"),
}

data class UserFeedbackNotice(
    val feedbackCode: UserFeedbackCode,
    val page: ClientUiPage,
    val action: ClientUiAction,
    val origin: FeedbackOrigin,
) {
    val publicMessage: String get() = feedbackCode.publicMessage
}

/** 客户端适配器；SDK 支撑的实现由 [AppDataState] 安装。 */
interface ClientUiTelemetrySink {
    fun recordUserNotice(notice: UserFeedbackNotice)
    fun recordPageDwell(
        page: ClientUiPage,
        durationMillis: Long,
        exitReason: ClientPageExitReason,
    )
    fun recordAction(
        page: ClientUiPage,
        action: ClientUiAction,
        outcome: ClientActionOutcome,
    )
    fun recordSystem(event: ClientSystemEvent, state: ClientSystemState)
    fun recordMedia(
        page: ClientUiPage,
        mediaKind: ClientMediaKind,
        operation: MediaOperation,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason? = null,
    )
    fun recordFault(
        code: ClientFaultCode,
        page: ClientUiPage?,
        action: ClientUiAction?,
        origin: FeedbackOrigin,
        reason: ClientFaultReason,
    )
}

object NoopClientUiTelemetrySink : ClientUiTelemetrySink {
    override fun recordUserNotice(notice: UserFeedbackNotice) = Unit
    override fun recordPageDwell(
        page: ClientUiPage,
        durationMillis: Long,
        exitReason: ClientPageExitReason,
    ) = Unit
    override fun recordAction(
        page: ClientUiPage,
        action: ClientUiAction,
        outcome: ClientActionOutcome,
    ) = Unit
    override fun recordSystem(event: ClientSystemEvent, state: ClientSystemState) = Unit
    override fun recordMedia(
        page: ClientUiPage,
        mediaKind: ClientMediaKind,
        operation: MediaOperation,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason?,
    ) = Unit
    override fun recordFault(
        code: ClientFaultCode,
        page: ClientUiPage?,
        action: ClientUiAction?,
        origin: FeedbackOrigin,
        reason: ClientFaultReason,
    ) = Unit
}

/**
 * 一个被准入的业务动作，带有一个恰好一次的终止遥测结果。
 *
 * 尝试不携带动态业务字段。调用方只在自己的状态机中保留资源身份，
 * 并仅使用这个对象来线性化封闭的结果词汇表。
 */
internal class ClientActionAttempt private constructor(
    private val sink: ClientUiTelemetrySink,
    private val page: ClientUiPage,
    private val action: ClientUiAction,
) {
    private val lock = Any()
    private var active = true

    fun succeed() = finish(ClientActionOutcome.SUCCEEDED)

    fun queue() = finish(ClientActionOutcome.QUEUED)

    fun fail() = finish(ClientActionOutcome.FAILED)

    fun cancel() = finish(ClientActionOutcome.CANCELLED)

    private fun finish(outcome: ClientActionOutcome) {
        val shouldReport = synchronized(lock) {
            if (!active) false else {
                active = false
                true
            }
        }
        if (shouldReport) sink.recordAction(page, action, outcome)
    }

    companion object {
        fun start(
            sink: ClientUiTelemetrySink,
            page: ClientUiPage,
            action: ClientUiAction,
        ): ClientActionAttempt {
            sink.recordAction(page, action, ClientActionOutcome.STARTED)
            return ClientActionAttempt(sink, page, action)
        }
    }
}

internal fun ClientUiTelemetrySink.startActionAttempt(
    page: ClientUiPage,
    action: ClientUiAction,
): ClientActionAttempt = ClientActionAttempt.start(this, page, action)

class UserFeedbackReporter(
    private val sink: ClientUiTelemetrySink,
) {
    /** 记录并返回一个已经上下文化的、经过审查的通知。 */
    fun displayed(notice: UserFeedbackNotice): String {
        sink.recordUserNotice(notice)
        return notice.publicMessage
    }

    /** 返回调用方应当渲染的同一条经过审查的消息。 */
    fun displayed(
        feedbackCode: UserFeedbackCode,
        page: ClientUiPage,
        action: ClientUiAction = ClientUiAction.SHOW_FEEDBACK,
        origin: FeedbackOrigin,
    ): String {
        val notice = UserFeedbackNotice(feedbackCode, page, action, origin)
        sink.recordUserNotice(notice)
        return notice.publicMessage
    }
}

/**
 * 同一时间一个活动页面段。转换、后台边界或显式 finish 恰好发出一次
 * 前一个段；重复的 finish/pause 调用是幂等的。
 */
class PageDwellTracker(
    private val nowMillis: () -> Long,
    private val report: (ClientUiPage, Long, ClientPageExitReason) -> Unit,
) {
    private var activePage: ClientUiPage? = null
    private var enteredAtMillis: Long = 0L

    fun enter(page: ClientUiPage) {
        if (activePage == page) return
        finishActive(ClientPageExitReason.NAVIGATION)
        activePage = page
        enteredAtMillis = nowMillis()
    }

    fun pause() = finishActive(ClientPageExitReason.BACKGROUND)

    /** 用平台调用点拥有的生命周期原因结束当前段。 */
    fun finish(exitReason: ClientPageExitReason) = finishActive(exitReason)

    private fun finishActive(exitReason: ClientPageExitReason) {
        val page = activePage ?: return
        activePage = null
        val elapsed = (nowMillis() - enteredAtMillis).coerceAtLeast(0L)
        report(page, elapsed, exitReason)
    }
}

/**
 * 线性化一次异步媒体尝试。启动一个替换会把未完成的尝试关闭为已取消；
 * 因此每一次启动都恰好有一个终止结果。
 */
class MediaOperationAttemptTracker(
    private val report: (ClientActionOutcome, MediaFailureReason?) -> Unit,
) {
    var hasActiveAttempt: Boolean = false
        private set

    fun start() {
        if (hasActiveAttempt) finish(ClientActionOutcome.CANCELLED)
        hasActiveAttempt = true
        report(ClientActionOutcome.STARTED, null)
    }

    fun succeed() = finish(ClientActionOutcome.SUCCEEDED)

    fun fail(reason: MediaFailureReason) = finish(ClientActionOutcome.FAILED, reason)

    fun cancel() = finish(ClientActionOutcome.CANCELLED)

    private fun finish(
        outcome: ClientActionOutcome,
        reason: MediaFailureReason? = null,
    ) {
        if (!hasActiveAttempt) return
        hasActiveAttempt = false
        report(outcome, reason)
    }
}
