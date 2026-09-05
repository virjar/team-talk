package com.virjar.tk.protocol.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 所有 TeamTalk 客户端共享的带版本号、已认证的 HTTP 端点。 */
const val CLIENT_TELEMETRY_ENDPOINT: String = "/api/client-telemetry"

/**
 * 客户端遥测的硬性线格式上限。这些上限有意独立于远端策略：
 * 服务端可以把采集收得更严，但永远不能授权一个无界的 payload。
 */
object ClientTelemetryLimits {
    const val SCHEMA_VERSION: Int = 2
    const val MAX_ID_CHARS: Int = 64
    const val MAX_NAME_CHARS: Int = 96
    const val MAX_RUNTIME_FIELD_CHARS: Int = 128
    const val MAX_GIT_COMMIT_CHARS: Int = 80
    const val MAX_BUILD_IDENTITY_CHARS: Int = 192
    const val MAX_BUILD_TIME_CHARS: Int = 64
    const val MAX_MESSAGE_CHARS: Int = 512
    const val MAX_STATE_CHARS: Int = 128
    const val MAX_STACK_FRAMES: Int = 48
    const val MAX_STACK_FIELD_CHARS: Int = 192
    const val MAX_EVENTS_PER_BATCH: Int = 256
    const val MAX_POLICY_EVENTS_PER_MINUTE: Int = 10_000
    const val MAX_POLICY_BYTES_PER_DAY: Long = 1L * 1024L * 1024L * 1024L
    const val MIN_UPLOAD_INTERVAL_SECONDS: Int = 10
    const val MAX_UPLOAD_INTERVAL_SECONDS: Int = 3_600
    /** 镜像本地可靠发件箱的硬容量，而不接受无界的客户端计数。 */
    const val MAX_OUTGOING_ACTIVE_COUNT: Int = 1_024
    const val MAX_OUTGOING_TERMINAL_FAILED_COUNT: Int = 512
    /** 更老的值由调用方截断；遥测永远不需要无界的墙钟历史。 */
    const val MAX_OUTGOING_ACTIVE_AGE_MILLIS: Long = 365L * 24L * 60L * 60L * 1_000L
    const val MAX_OUTGOING_ATTEMPT_COUNT: Long = 1_000_000L
}

/** 仅数值的发送队列诊断事件的固定语义名称。 */
const val TELEMETRY_OUTGOING_QUEUE_EVENT_NAME: String = "outgoing.queue.snapshot"

@Serializable
enum class ClientPlatform {
    ANDROID,
    DESKTOP,
    HEADLESS,
    UNKNOWN,
}

/**
 * 可安全在服务端索引的构建/运行时事实。认证决定 uid 与设备；
 * 在这个客户端控制的请求模型中，uid、phone 和 deviceId 都不被接受。
 */
@Serializable
data class ClientRuntimeInfo(
    val platform: ClientPlatform,
    val osName: String,
    val osVersion: String,
    val architecture: String,
    val deviceModel: String,
    val appVersion: String,
    val buildNumber: String,
    val gitCommit: String,
    val buildIdentity: String,
    val buildTime: String,
    val protocolVersion: Int,
    val distribution: String,
) {
    companion object {
        fun unknown(platform: ClientPlatform = ClientPlatform.UNKNOWN): ClientRuntimeInfo =
            ClientRuntimeInfo(
                platform = platform,
                osName = "unknown",
                osVersion = "unknown",
                architecture = "unknown",
                deviceModel = "unknown",
                appVersion = "unknown",
                buildNumber = "unknown",
                gitCommit = "unknown",
                buildIdentity = "unknown",
                buildTime = "unknown",
                protocolVersion = 0,
                distribution = "unknown",
            )
    }
}

@Serializable
enum class TelemetryEventKind {
    LOG,
    FAULT,
    PAGE_DWELL,
    ACTION,
    SYSTEM,
    USER_NOTICE,
    MEDIA,
    OUTGOING_QUEUE,
}

@Serializable
enum class TelemetryLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** 封闭的 payload 层级：任意 map 被有意排除在线格式契约之外。 */
@Serializable
sealed interface TelemetryEventPayload

@Serializable
@SerialName("log")
data class TelemetryLogPayload(
    val level: TelemetryLogLevel,
    val logger: String,
    val message: String,
) : TelemetryEventPayload

@Serializable
data class TelemetryStackFrame(
    val className: String,
    val methodName: String,
    val fileName: String? = null,
    val lineNumber: Int? = null,
)

@Serializable
@SerialName("fault")
data class TelemetryFaultPayload(
    val logger: String,
    val summary: String,
    val faultCode: String = "fault.reported",
    val page: String? = null,
    val action: String? = null,
    val origin: String? = null,
    val reasonCode: String? = null,
    val exceptionClass: String? = null,
    val stackFrames: List<TelemetryStackFrame> = emptyList(),
    val fatal: Boolean = false,
) : TelemetryEventPayload

@Serializable
enum class TelemetryPageExitReason {
    NAVIGATION,
    BACKGROUND,
    SESSION_END,
    UNKNOWN,
}

@Serializable
@SerialName("page_dwell")
data class TelemetryPageDwellPayload(
    val page: String,
    val durationMillis: Long,
    val exitReason: TelemetryPageExitReason = TelemetryPageExitReason.UNKNOWN,
) : TelemetryEventPayload

@Serializable
enum class TelemetryActionOutcome {
    STARTED,
    QUEUED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

@Serializable
@SerialName("action")
data class TelemetryActionPayload(
    val page: String,
    val action: String,
    val outcome: TelemetryActionOutcome = TelemetryActionOutcome.UNKNOWN,
) : TelemetryEventPayload

@Serializable
@SerialName("system")
data class TelemetrySystemPayload(
    val name: String,
    val state: String? = null,
    /** 只有显式标记为 critical 的生命周期/连接事件才符合 BASELINE 上传条件。 */
    val critical: Boolean = false,
) : TelemetryEventPayload

@Serializable
enum class TelemetryNoticeLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

/**
 * 经过审查、无参数的文本，可被客户端展示并在 BASELINE 模式下索引。
 *
 * 把这份词表放在 wire 模块里，让图形客户端与服务端就精确的公开文案达成一致，
 * 而不接受 Throwable 消息、路径、文件名、URL、标识符、搜索词或消息内容作为等价替代。
 */
enum class TelemetryFeedbackCode(
    val code: String,
    val publicMessage: String,
) {
    OPERATION_FAILED("operation_failed", "操作未完成，请稍后重试"),
    LOCAL_DATA_UNAVAILABLE("local_data_unavailable", "本地数据暂时不可用，请重试"),
    NETWORK_UNAVAILABLE("network_unavailable", "当前网络不可用，连接后可重试"),
    AUTHENTICATION_REQUIRED("authentication_required", "登录状态已失效，请重新登录"),
    MEDIA_DOWNLOAD_FAILED("media_download_failed", "附件下载失败，请稍后重试"),
    MEDIA_UPLOAD_FAILED("media_upload_failed", "附件发送失败，请稍后重试"),
    CHAT_ASSET_UPLOAD_PENDING("chat_asset_upload_pending", "附件仍在上传，请等待完成后再发送"),
    RELIABLE_COMMAND_PENDING("reliable_command_pending", "操作已保存，稍后将自动重试"),
    RELIABLE_COMMAND_REJECTED("reliable_command_rejected", "先前保存的群文件操作未能完成，请刷新后重试"),
    MEDIA_NETWORK_FAILED("media_network_failed", "网络不可用，连接后可重试附件"),
    MEDIA_HTTP_DENIED("media_http_denied", "无权访问此附件"),
    MEDIA_HTTP_MISSING("media_http_missing", "附件不存在或已失效"),
    MEDIA_CACHE_FULL("media_cache_full", "本地缓存空间不足，请清理后重试"),
    MEDIA_SIZE_INVALID("media_size_invalid", "附件校验失败，无法打开"),
    MEDIA_IO_FAILED("media_io_failed", "本地文件处理失败，请检查存储空间"),
    MEDIA_SESSION_CHANGED("media_session_changed", "登录状态已变化，请重新打开页面"),
    MEDIA_OPEN_FAILED("media_open_failed", "无法打开文件，请检查是否安装了可处理此格式的应用"),
    MICROPHONE_PERMISSION_REQUIRED("microphone_permission_required", "需要麦克风权限才能发送语音"),
    VOICE_RECORDING_FAILED("voice_recording_failed", "录音失败，请重试"),
    VOICE_TOO_SHORT("voice_too_short", "录音时间太短"),
    OFFICE_REF_UNAVAILABLE("office_ref_unavailable", "内容不可访问或已被删除"),
    ;

    companion object {
        private val byCode = entries.associateBy(TelemetryFeedbackCode::code)

        fun fromCode(code: String): TelemetryFeedbackCode? = byCode[code]

        /** 保留经过审查的 UI 文案；任意的遗留错误文本安全回退。 */
        fun forDisplayedMessage(message: String?): TelemetryFeedbackCode =
            entries.firstOrNull { it.publicMessage == message } ?: OPERATION_FAILED
    }
}

@Serializable
enum class TelemetryNoticeOrigin {
    TOAST,
    SNACKBAR,
    DIALOG,
    INLINE,
    SYSTEM,
    UNKNOWN,
}

@Serializable
@SerialName("user_notice")
data class TelemetryUserNoticePayload(
    /** 稳定的产品反馈码；绝不内插 uid、文件名或消息正文。 */
    val feedbackCode: String,
    val page: String? = null,
    val action: String? = null,
    val origin: TelemetryNoticeOrigin,
    val message: String,
    val level: TelemetryNoticeLevel,
) : TelemetryEventPayload

@Serializable
enum class TelemetryMediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    UNKNOWN,
}

@Serializable
enum class TelemetryMediaOperation {
    RECORD,
    UPLOAD,
    DOWNLOAD,
    PREVIEW,
    PLAYBACK,
    OPEN,
}

@Serializable
@SerialName("media")
data class TelemetryMediaPayload(
    val mediaKind: TelemetryMediaKind,
    val operation: TelemetryMediaOperation,
    val outcome: TelemetryActionOutcome,
    val byteCount: Long? = null,
    val durationMillis: Long? = null,
    /** 稳定的失败/取消码。不得包含文件名、URL 或服务端响应。 */
    val reasonCode: String? = null,
) : TelemetryEventPayload

/**
 * 仅数值的 SendQueue 健康快照。它有意不包含 uid、会话/消息身份、
 * 文件名、路径、正文、错误文本或可扩展的元数据字段。
 */
@Serializable
@SerialName("outgoing_queue")
data class TelemetryOutgoingQueuePayload(
    val pendingCount: Int,
    val retryWaitCount: Int,
    val terminalFailedCount: Int,
    val oldestActiveAgeMillis: Long,
    val maxAttemptCount: Long,
) : TelemetryEventPayload

@Serializable
data class TelemetryEvent(
    val eventId: String,
    val runId: String,
    val sequence: Long,
    val occurredAtEpochMs: Long,
    /** 稳定的低基数语义名称，例如 `navigation.open_chat`。 */
    val eventName: String,
    val kind: TelemetryEventKind,
    val payload: TelemetryEventPayload,
    /** 事件创建时冻结的服务端/客户端 join 身份；在活跃 trace 策略之外为 null。 */
    val connectionTraceContext: ConnectionTraceContext? = null,
)

/** 一个不可变的上传/暂存段。只有策略心跳允许空事件列表。 */
@Serializable
data class TelemetryBatch(
    val schemaVersion: Int = ClientTelemetryLimits.SCHEMA_VERSION,
    val batchId: String,
    val createdAtEpochMs: Long,
    val runtimeInfo: ClientRuntimeInfo,
    val events: List<TelemetryEvent>,
    val heartbeat: Boolean = false,
)

@Serializable
enum class TelemetryPolicyMode {
    BASELINE,
    DIAGNOSTIC,
}

/**
 * 服务端签发的采集策略。诊断采集仅在 [expiresAtEpochMs] 之前有效，
 * 并始终受事件与编码字节双重预算约束。
 */
@Serializable
data class TelemetryPolicy(
    val schemaVersion: Int = ClientTelemetryLimits.SCHEMA_VERSION,
    val revision: String,
    val mode: TelemetryPolicyMode,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val maxEventsPerMinute: Int,
    val maxBytesPerDay: Long,
    val maxBatchEvents: Int,
    val uploadIntervalSeconds: Int,
) {
    companion object {
        fun baseline(): TelemetryPolicy = TelemetryPolicy(
            revision = "baseline-v1",
            mode = TelemetryPolicyMode.BASELINE,
            issuedAtEpochMs = 0L,
            expiresAtEpochMs = Long.MAX_VALUE,
            maxEventsPerMinute = 120,
            maxBytesPerDay = 8L * 1024L * 1024L,
            maxBatchEvents = 64,
            uploadIntervalSeconds = 300,
        )
    }
}

@Serializable
data class TelemetryAck(
    val schemaVersion: Int = ClientTelemetryLimits.SCHEMA_VERSION,
    val batchId: String,
    /** 仅对空的心跳批次为 null。 */
    val acceptedThroughSequence: Long? = null,
)

@Serializable
data class TelemetryUploadResponse(
    val schemaVersion: Int = ClientTelemetryLimits.SCHEMA_VERSION,
    val ack: TelemetryAck,
    val policy: TelemetryPolicy? = null,
)

/** 纯函数式、确定性的校验，在 HTTP 边界两侧共用。 */
object ClientTelemetryValidation {
    fun requireValid(runtimeInfo: ClientRuntimeInfo) {
        requirePlainText(runtimeInfo.osName, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "osName")
        requirePlainText(runtimeInfo.osVersion, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "osVersion")
        requirePlainText(
            runtimeInfo.architecture,
            ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
            "architecture",
        )
        requirePlainText(runtimeInfo.deviceModel, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "deviceModel")
        requirePlainText(runtimeInfo.appVersion, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "appVersion")
        requirePlainText(runtimeInfo.buildNumber, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "buildNumber")
        requireToken(runtimeInfo.gitCommit, ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS, "gitCommit")
        requireToken(
            runtimeInfo.buildIdentity,
            ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS,
            "buildIdentity",
        )
        requirePlainText(runtimeInfo.buildTime, ClientTelemetryLimits.MAX_BUILD_TIME_CHARS, "buildTime")
        require(runtimeInfo.protocolVersion >= 0) { "protocolVersion must be a nonnegative packed major/minor ID" }
        requirePlainText(
            runtimeInfo.distribution,
            ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS,
            "distribution",
        )
    }

    fun requireValid(event: TelemetryEvent) {
        requireId(event.eventId, "eventId")
        requireId(event.runId, "runId")
        require(event.sequence >= 0L) { "sequence must not be negative" }
        require(event.occurredAtEpochMs > 0L) { "occurredAtEpochMs must be positive" }
        event.connectionTraceContext?.let { context ->
            ConnectionTraceContextPolicy.requireValid(context)
            require(event.occurredAtEpochMs < context.expiresAtEpochMs) {
                "connectionTraceContext expired before the event occurred"
            }
        }
        requireName(event.eventName, "eventName")
        require(event.kind == event.payload.expectedKind()) { "event kind does not match payload" }
        when (val payload = event.payload) {
            is TelemetryLogPayload -> {
                requireName(payload.logger, "logger")
                requireDiagnosticText(payload.message, "message")
            }
            is TelemetryFaultPayload -> {
                requireName(payload.logger, "logger")
                requireDiagnosticText(payload.summary, "summary")
                requireName(payload.faultCode, "faultCode")
                payload.page?.let { requireName(it, "fault page") }
                payload.action?.let { requireName(it, "fault action") }
                payload.origin?.let { requireName(it, "fault origin") }
                payload.reasonCode?.let { requireName(it, "fault reasonCode") }
                payload.exceptionClass?.let { requireName(it, "exceptionClass", allowDollar = true) }
                require(payload.stackFrames.size <= ClientTelemetryLimits.MAX_STACK_FRAMES) {
                    "too many stack frames"
                }
                payload.stackFrames.forEach(::requireValid)
            }
            is TelemetryPageDwellPayload -> {
                requireName(payload.page, "page")
                require(payload.durationMillis >= 0L) { "page duration must not be negative" }
            }
            is TelemetryActionPayload -> {
                requireName(payload.page, "page")
                requireName(payload.action, "action")
            }
            is TelemetrySystemPayload -> {
                requireName(payload.name, "system name")
                payload.state?.let { requirePlainText(it, ClientTelemetryLimits.MAX_STATE_CHARS, "system state") }
            }
            is TelemetryUserNoticePayload -> {
                requireName(payload.feedbackCode, "feedbackCode")
                payload.page?.let { requireName(it, "notice page") }
                payload.action?.let { requireName(it, "notice action") }
                requireDiagnosticText(payload.message, "notice message")
            }
            is TelemetryMediaPayload -> {
                payload.byteCount?.let { require(it >= 0L) { "media byteCount must not be negative" } }
                payload.durationMillis?.let { require(it >= 0L) { "media duration must not be negative" } }
                payload.reasonCode?.let { requireName(it, "media reasonCode") }
            }
            is TelemetryOutgoingQueuePayload -> {
                require(event.eventName == TELEMETRY_OUTGOING_QUEUE_EVENT_NAME) {
                    "outgoing queue event name must be canonical"
                }
                require(payload.pendingCount in 0..ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT) {
                    "outgoing pendingCount is out of range"
                }
                require(payload.retryWaitCount in 0..ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT) {
                    "outgoing retryWaitCount is out of range"
                }
                require(payload.pendingCount + payload.retryWaitCount <=
                    ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT
                ) { "outgoing active counts exceed the queue capacity" }
                require(
                    payload.terminalFailedCount in
                        0..ClientTelemetryLimits.MAX_OUTGOING_TERMINAL_FAILED_COUNT,
                ) { "outgoing terminalFailedCount is out of range" }
                require(
                    payload.oldestActiveAgeMillis in
                        0L..ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_AGE_MILLIS,
                ) { "outgoing oldestActiveAgeMillis is out of range" }
                require(payload.maxAttemptCount in 0L..ClientTelemetryLimits.MAX_OUTGOING_ATTEMPT_COUNT) {
                    "outgoing maxAttemptCount is out of range"
                }
                if (payload.pendingCount + payload.retryWaitCount == 0) {
                    require(payload.oldestActiveAgeMillis == 0L) {
                        "outgoing queue without active work cannot report active age"
                    }
                }
                if (payload.pendingCount + payload.retryWaitCount + payload.terminalFailedCount == 0) {
                    require(payload.maxAttemptCount == 0L) {
                        "empty outgoing queue cannot report attempts"
                    }
                }
            }
        }
    }

    fun requireValid(batch: TelemetryBatch) {
        require(batch.schemaVersion == ClientTelemetryLimits.SCHEMA_VERSION) {
            "unsupported telemetry schema version"
        }
        requireId(batch.batchId, "batchId")
        require(batch.createdAtEpochMs > 0L) { "createdAtEpochMs must be positive" }
        requireValid(batch.runtimeInfo)
        require(batch.events.size <= ClientTelemetryLimits.MAX_EVENTS_PER_BATCH) { "too many events" }
        require(batch.heartbeat || batch.events.isNotEmpty()) { "non-heartbeat batch must contain events" }
        if (batch.events.isEmpty()) require(batch.heartbeat) { "empty batch must be a heartbeat" }
        var lastSequence = -1L
        val ids = mutableSetOf<String>()
        var runId: String? = null
        batch.events.forEach { event ->
            requireValid(event)
            require(event.sequence > lastSequence) { "event sequence must be strictly increasing" }
            require(ids.add(event.eventId)) { "duplicate eventId" }
            require(runId == null || runId == event.runId) { "one batch cannot mix client runs" }
            runId = event.runId
            lastSequence = event.sequence
        }
    }

    fun requireValid(policy: TelemetryPolicy) {
        require(policy.schemaVersion == ClientTelemetryLimits.SCHEMA_VERSION) {
            "unsupported telemetry policy version"
        }
        requireToken(policy.revision, ClientTelemetryLimits.MAX_ID_CHARS, "policy revision")
        require(policy.issuedAtEpochMs >= 0L) { "policy issuedAtEpochMs must not be negative" }
        require(policy.expiresAtEpochMs > policy.issuedAtEpochMs) { "policy expiry must follow issuance" }
        require(policy.maxEventsPerMinute in 1..ClientTelemetryLimits.MAX_POLICY_EVENTS_PER_MINUTE) {
            "invalid event budget"
        }
        require(policy.maxBytesPerDay in 1L..ClientTelemetryLimits.MAX_POLICY_BYTES_PER_DAY) {
            "invalid byte budget"
        }
        require(policy.maxBatchEvents in 1..ClientTelemetryLimits.MAX_EVENTS_PER_BATCH) {
            "invalid batch event budget"
        }
        require(
            policy.uploadIntervalSeconds in
                ClientTelemetryLimits.MIN_UPLOAD_INTERVAL_SECONDS..ClientTelemetryLimits.MAX_UPLOAD_INTERVAL_SECONDS,
        ) { "invalid upload interval" }
    }

    fun requireValid(response: TelemetryUploadResponse, request: TelemetryBatch) {
        require(response.schemaVersion == ClientTelemetryLimits.SCHEMA_VERSION) {
            "unsupported telemetry response version"
        }
        require(response.ack.schemaVersion == ClientTelemetryLimits.SCHEMA_VERSION) {
            "unsupported telemetry ACK version"
        }
        require(response.ack.batchId == request.batchId) { "telemetry ACK batchId mismatch" }
        val lastSequence = request.events.lastOrNull()?.sequence
        require(response.ack.acceptedThroughSequence == lastSequence) {
            "telemetry ACK sequence mismatch"
        }
        response.policy?.let(::requireValid)
    }

    /** BASELINE 有意保持精简；DIAGNOSTIC 绝不会在其绝对过期时间后继续存活。 */
    fun allows(policy: TelemetryPolicy, event: TelemetryEvent, nowEpochMs: Long): Boolean {
        requireValid(policy)
        requireValid(event)
        val diagnostic = policy.mode == TelemetryPolicyMode.DIAGNOSTIC && nowEpochMs < policy.expiresAtEpochMs
        if (diagnostic) return true
        return when (event.kind) {
            TelemetryEventKind.FAULT,
            TelemetryEventKind.USER_NOTICE,
            -> true
            TelemetryEventKind.SYSTEM -> (event.payload as TelemetrySystemPayload).critical
            TelemetryEventKind.MEDIA ->
                (event.payload as TelemetryMediaPayload).outcome == TelemetryActionOutcome.FAILED
            TelemetryEventKind.LOG,
            TelemetryEventKind.PAGE_DWELL,
            TelemetryEventKind.ACTION,
            TelemetryEventKind.OUTGOING_QUEUE,
            -> false
        }
    }

    private fun requireValid(frame: TelemetryStackFrame) {
        requirePlainText(frame.className, ClientTelemetryLimits.MAX_STACK_FIELD_CHARS, "stack class")
        requirePlainText(frame.methodName, ClientTelemetryLimits.MAX_STACK_FIELD_CHARS, "stack method")
        frame.fileName?.let {
            requirePlainText(it, ClientTelemetryLimits.MAX_STACK_FIELD_CHARS, "stack file")
        }
        frame.lineNumber?.let { require(it >= -1) { "invalid stack line" } }
    }

    private fun TelemetryEventPayload.expectedKind(): TelemetryEventKind = when (this) {
        is TelemetryLogPayload -> TelemetryEventKind.LOG
        is TelemetryFaultPayload -> TelemetryEventKind.FAULT
        is TelemetryPageDwellPayload -> TelemetryEventKind.PAGE_DWELL
        is TelemetryActionPayload -> TelemetryEventKind.ACTION
        is TelemetrySystemPayload -> TelemetryEventKind.SYSTEM
        is TelemetryUserNoticePayload -> TelemetryEventKind.USER_NOTICE
        is TelemetryMediaPayload -> TelemetryEventKind.MEDIA
        is TelemetryOutgoingQueuePayload -> TelemetryEventKind.OUTGOING_QUEUE
    }

    private fun requireId(value: String, label: String) {
        require(value.length in 1..ClientTelemetryLimits.MAX_ID_CHARS) { "$label has invalid length" }
        require(value.first().isLetterOrDigit()) { "$label has an invalid prefix" }
        // ID 也用于不可变的跨平台暂存文件名；':' 在 Windows 上非法。
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "$label contains invalid characters"
        }
    }

    private fun requireToken(value: String, maxChars: Int, label: String) {
        require(value.length in 1..maxChars) { "$label has invalid length" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' || it == '+' }) {
            "$label contains invalid characters"
        }
    }

    private fun requireName(value: String, label: String, allowDollar: Boolean = false) {
        require(value.length in 1..ClientTelemetryLimits.MAX_NAME_CHARS) { "$label has invalid length" }
        require(value.first().isLetterOrDigit() || value.first() == '_') { "$label has an invalid prefix" }
        require(
            value.all {
                it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' ||
                    (allowDollar && it == '$')
            },
        ) { "$label contains invalid characters" }
    }

    private fun requireDiagnosticText(value: String, label: String) {
        requirePlainText(value, ClientTelemetryLimits.MAX_MESSAGE_CHARS, label)
        val canonical = value.lowercase()
        require(
            listOf("password=", "password:", "token=", "token:", "authorization:", "bearer ")
                .none(canonical::contains),
        ) { "$label contains a forbidden credential marker" }
    }

    private fun requirePlainText(value: String, maxChars: Int, label: String) {
        require(value.length in 1..maxChars) { "$label has invalid length" }
        require(value.none { it == '\r' || it == '\n' || it.isISOControl() }) {
            "$label contains control characters"
        }
    }
}
