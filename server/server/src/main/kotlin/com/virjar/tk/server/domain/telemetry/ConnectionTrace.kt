package com.virjar.tk.server.domain.telemetry

/**
 * 服务端连接诊断的稳定、刻意粗粒度的阶段。追踪事件绝不能仅仅为了解释哪个阶段失败
 * 就携带解码后的请求体。
 */
enum class ConnectionTracePhase {
    CONNECTION,
    AUTHENTICATION,
    POLICY,
    RPC,
    SYNC,
    EVENT,
    MESSAGE,
    HEARTBEAT,
    SHUTDOWN,
}

enum class ConnectionTraceOutcome {
    STARTED,
    SUCCEEDED,
    REJECTED,
    FAILED,
    DROPPED,
    CLOSED,
}

/** 绑定到一个物理连接并复制进遥测事件的精确客户端 AUTH 身份。 */
data class ConnectionTraceContext(
    val correlationId: String,
    val traceId: String,
    val sessionId: String,
    val connectionGeneration: Long,
    val policyRevision: Long,
) {
    init {
        requireConnectionTraceIdentifier("correlationId", correlationId)
        requireConnectionTraceIdentifier("traceId", traceId)
        requireConnectionTraceIdentifier("sessionId", sessionId)
        require(connectionGeneration > 0L) { "connection generation must be positive" }
        require(policyRevision > 0L) { "trace policy revision must be positive" }
    }
}

/**
 * 一个有界的服务器事件。[uid] 与 [deviceId] 仅在认证成功之前可空。[detail] 是展示文本，
 * 不是可扩展的载荷袋；调用方必须在构造草稿之前使用 [sanitizeConnectionTraceDetail]。
 */
data class ConnectionTraceEventDraft(
    val uid: String?,
    val deviceId: String?,
    val correlationId: String,
    val traceId: String,
    val sessionId: String,
    val connectionGeneration: Long,
    val policyRevision: Long,
    val occurredAt: Long,
    val phase: ConnectionTracePhase,
    val outcome: ConnectionTraceOutcome,
    val detail: String? = null,
) {
    init {
        uid?.let { requireConnectionTraceOwner("uid", it, MAX_CONNECTION_TRACE_UID_CHARS) }
        deviceId?.let { requireConnectionTraceOwner("deviceId", it, MAX_CONNECTION_TRACE_DEVICE_ID_CHARS) }
        ConnectionTraceContext(
            correlationId = correlationId,
            traceId = traceId,
            sessionId = sessionId,
            connectionGeneration = connectionGeneration,
            policyRevision = policyRevision,
        )
        require(occurredAt > 0L) { "connection trace time must be positive" }
        require(detail == sanitizeConnectionTraceDetail(detail)) {
            "connection trace detail must already be sanitized"
        }
    }

    val context: ConnectionTraceContext
        get() = ConnectionTraceContext(
            correlationId,
            traceId,
            sessionId,
            connectionGeneration,
            policyRevision,
        )
}

data class StoredConnectionTraceEvent(
    val id: Long,
    val event: ConnectionTraceEventDraft,
)

/** 所有身份字段都是必需且精确的，使一次重连代号绝不会渗入另一次。 */
data class ConnectionTraceQuery(
    /** 从客户端遥测事件复制的已认证所有者；绝不从查询输入接受。 */
    val uid: String,
    /** 从客户端遥测事件复制的已认证设备；绝不从查询输入接受。 */
    val deviceId: String,
    val correlationId: String,
    val traceId: String,
    val sessionId: String,
    val connectionGeneration: Long,
    val policyRevision: Long,
    val occurredAtFrom: Long,
    val occurredAtUntil: Long,
    val limit: Int = ConnectionTraceStoragePolicy.MAX_QUERY_EVENTS,
) {
    init {
        requireConnectionTraceOwner("uid", uid, MAX_CONNECTION_TRACE_UID_CHARS)
        requireConnectionTraceOwner("deviceId", deviceId, MAX_CONNECTION_TRACE_DEVICE_ID_CHARS)
        requireConnectionTraceIdentifier("correlationId", correlationId)
        requireConnectionTraceIdentifier("traceId", traceId)
        requireConnectionTraceIdentifier("sessionId", sessionId)
        require(connectionGeneration > 0L) { "connection generation must be positive" }
        require(policyRevision > 0L) { "trace policy revision must be positive" }
        require(occurredAtFrom <= occurredAtUntil) { "connection trace time range is inverted" }
        require(limit in 1..ConnectionTraceStoragePolicy.MAX_QUERY_EVENTS) {
            "connection trace query exceeds its event limit"
        }
    }
}

data class ConnectionTracePage(
    val events: List<StoredConnectionTraceEvent>,
    val truncated: Boolean,
)

data class ConnectionTraceStoreSnapshot(
    val available: Boolean,
    val queuedEvents: Int,
    val queuedBytes: Long,
    val documentCount: Long,
    val accountedBytes: Long,
    val physicalBytes: Long,
    val droppedEvents: Long,
    val lastRetentionSuccessAt: Long?,
)

fun interface ConnectionTraceEventSink {
    /** 快速、非阻塞准入。False 表示该诊断事件被刻意绕过。 */
    fun tryAppend(event: ConnectionTraceEventDraft): Boolean
}

interface ConnectionTraceEventStore : ConnectionTraceEventSink, AutoCloseable {
    /** 打开一个有效索引，或在损坏/schema 变更后把可丢弃索引重置为空。 */
    fun start(): Boolean

    fun query(query: ConnectionTraceQuery): ConnectionTracePage

    /** 保留是维护工作，绝不能运行在 IM 事件循环线程上。 */
    fun deleteBefore(occurredBefore: Long): Boolean

    fun snapshot(): ConnectionTraceStoreSnapshot

    fun isAvailable(): Boolean
}

object ConnectionTraceStoragePolicy {
    const val RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_QUERY_EVENTS: Int = 200
    const val MAX_DETAIL_CHARS: Int = 512
}

/**
 * 把一小句诊断文本转换为追踪存储所接受的唯一自由文本表示。秘密赋值、URI/路径形态值、
 * JSON/正文形态块与控制字符被拒绝，而不是被部分持久化。
 */
fun sanitizeConnectionTraceDetail(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    require(normalized.length <= ConnectionTraceStoragePolicy.MAX_DETAIL_CHARS) {
        "connection trace detail is too long"
    }
    require(normalized.none(Char::isISOControl)) { "connection trace detail contains controls" }
    require(!SECRET_ASSIGNMENT.containsMatchIn(normalized)) { "connection trace detail contains a secret" }
    require(!URI_OR_PATH.containsMatchIn(normalized)) { "connection trace detail contains a path or URI" }
    require(!STRUCTURED_BODY.containsMatchIn(normalized)) { "connection trace detail contains structured body data" }
    val fields = normalized.split(' ')
    require(fields.size in 1..MAX_CONNECTION_TRACE_DETAIL_FIELDS && fields.all(TRACE_DETAIL_FIELD::matches)) {
        "connection trace detail is not a bounded diagnostic field list"
    }
    return normalized
}

private fun requireConnectionTraceIdentifier(name: String, value: String) {
    require(value.length in MIN_CONNECTION_TRACE_ID_CHARS..MAX_CONNECTION_TRACE_ID_CHARS &&
        CONNECTION_TRACE_ID.matches(value)
    ) {
        "invalid connection trace $name"
    }
}

private fun requireConnectionTraceOwner(name: String, value: String, maxChars: Int) {
    require(value.length in 1..maxChars && value.none(Char::isISOControl)) {
        "invalid connection trace $name"
    }
}

private const val MIN_CONNECTION_TRACE_ID_CHARS = 16
private const val MAX_CONNECTION_TRACE_ID_CHARS = 128
private const val MAX_CONNECTION_TRACE_UID_CHARS = 64
private const val MAX_CONNECTION_TRACE_DEVICE_ID_CHARS = 128
private const val MAX_CONNECTION_TRACE_DETAIL_FIELDS = 8
private val CONNECTION_TRACE_ID = Regex("[A-Za-z0-9_-]+")
private val TRACE_DETAIL_FIELD = Regex("[A-Za-z][A-Za-z0-9]*=[A-Za-z0-9_.:#-]+")
private val SECRET_ASSIGNMENT = Regex(
    "(?i)(authorization|password|passwd|token|secret|cookie|set-cookie)\\s*[:=]",
)
private val URI_OR_PATH = Regex("(?i)([a-z][a-z0-9+.-]*://|file:|(?:^|\\s)/(?:Users|home|var|tmp|etc)/|[A-Z]:\\\\)")
private val STRUCTURED_BODY = Regex("[{}\\[\\]]|(?i)(?:payload|body)\\s*[:=]")

enum class TelemetryAdminAuditAction {
    EVENT_SEARCH,
    CONNECTION_TRACE_CORRELATE,
    POLICY_ENABLE,
    POLICY_DISABLE,
}

enum class TelemetryAdminAuditResult {
    SUCCESS,
    EMPTY,
    NOT_FOUND,
    REJECTED,
    FAILED,
}

data class TelemetryAdminAuditEntry(
    val actor: String,
    val action: TelemetryAdminAuditAction,
    val target: String,
    val result: TelemetryAdminAuditResult,
    val occurredAt: Long,
)

fun interface ClientTelemetryAdminAuditRepository {
    fun append(entry: TelemetryAdminAuditEntry)
}
