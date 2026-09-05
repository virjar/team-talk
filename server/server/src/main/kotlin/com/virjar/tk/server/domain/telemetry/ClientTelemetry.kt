package com.virjar.tk.server.domain.telemetry

import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME

internal const val OUTGOING_QUEUE_STORED_MESSAGE = "Outgoing queue snapshot"
internal const val OUTGOING_QUEUE_STORED_SEARCH_TEXT =
    "$TELEMETRY_OUTGOING_QUEUE_EVENT_NAME $TELEMETRY_OUTGOING_QUEUE_EVENT_NAME"

/** 一次客户端安装的最新已认证运行时事实。 */
data class TelemetryRuntimeSnapshot(
    val platform: String,
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
)

/** 一条经过校验、对 UI 安全的事件，准备进入七天的 Lucene 权威边界。 */
data class TelemetryEventDraft(
    val eventId: String,
    val runId: String,
    val sequence: Long,
    val occurredAt: Long,
    val category: String,
    val eventName: String,
    val message: String,
    val searchText: String,
    val outgoingQueue: TelemetryOutgoingQueueMetrics? = null,
    /** 精确的客户端 AUTH 身份，仅在该连接具有诊断上下文时存在。 */
    val connectionTraceContext: ConnectionTraceContext? = null,
)

/** 仅数值的队列事实，保留为类型化 Lucene 字段，绝不从文本重建。 */
data class TelemetryOutgoingQueueMetrics(
    val pendingCount: Int,
    val retryWaitCount: Int,
    val terminalFailedCount: Int,
    val oldestActiveAgeMillis: Long,
    val maxAttemptCount: Long,
)

data class TelemetryBatchDraft(
    val batchId: String,
    val payloadSha256: String,
    val createdAt: Long,
    val runtime: TelemetryRuntimeSnapshot,
    val events: List<TelemetryEventDraft>,
)

data class StoredTelemetryEvent(
    val id: Long,
    val batchId: String,
    val uid: String,
    val deviceId: String,
    val receivedAt: Long,
    val runtime: TelemetryRuntimeSnapshot,
    val event: TelemetryEventDraft,
)

enum class TelemetryIngestStatus {
    ACCEPTED,
    DUPLICATE,
}

data class TelemetryIngestResult(
    val status: TelemetryIngestStatus,
    val acceptedThroughSequence: Long,
    val receivedAt: Long,
)

data class TelemetryBatchReceipt(
    val payloadSha256: String,
    val acceptedThroughSequence: Long,
    val receivedAt: Long,
)

class TelemetryBatchConflictException : IllegalArgumentException(
    "Telemetry batch identity was reused with different content",
)

class TelemetryStoreBusyException : IllegalStateException("client telemetry writer is busy")

class TelemetryStoreCapacityException : IllegalStateException("client telemetry storage capacity is exhausted")

enum class TelemetryCollectionMode {
    BASELINE,
    DIAGNOSTIC,
}

data class ClientTelemetryPolicy(
    val policyId: String?,
    val uid: String,
    val deviceId: String?,
    val mode: TelemetryCollectionMode,
    /** 对目标客户端可见的每个策略状态之间单调递增。 */
    val revision: Long,
    val reason: String?,
    val expiresAt: Long?,
    val updatedAt: Long,
    val updatedBy: String,
)

data class ClientTelemetryDeviceProfile(
    val uid: String,
    val deviceId: String,
    val runtime: TelemetryRuntimeSnapshot,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastEventAt: Long?,
)

data class TelemetryDeviceIdentity(
    val uid: String,
    val deviceId: String,
)

/** 已认证 HTTP 请求捕获的凭证代号（generation）。 */
data class TelemetryDeviceAuthority(
    val uid: String,
    val deviceId: String,
    val userCredentialEpoch: Long,
    val deviceCredentialEpoch: Long,
)

/** 设备管理过滤器。身份选择器保持精确；[text] 是唯一的模糊字段。 */
data class TelemetryDeviceFilter(
    val text: String? = null,
    val exactUid: String? = null,
)

data class TelemetryPage<T>(
    val total: Long,
    val items: List<T>,
)

/** PostgreSQL 控制面。事件/回执字节被刻意设计为不跨越此边界。 */
interface ClientTelemetryControlRepository {
    /** 当已认证安装在本次 PG 事务之前已被退役时返回 false。 */
    fun refreshDevice(
        authority: TelemetryDeviceAuthority,
        runtime: TelemetryRuntimeSnapshot,
        receivedAt: Long,
        acceptedEventAt: Long?,
        /** [runtime] 的观察时间；精确批次重试可能晚得多才被看到。 */
        runtimeObservedAt: Long = receivedAt,
    ): Boolean

    fun effectivePolicy(uid: String, deviceId: String, now: Long): ClientTelemetryPolicy

    fun effectivePolicies(
        devices: Set<TelemetryDeviceIdentity>,
        now: Long,
    ): Map<TelemetryDeviceIdentity, ClientTelemetryPolicy>

    fun pageDevices(
        filter: TelemetryDeviceFilter,
        offset: Long,
        limit: Int,
    ): TelemetryPage<ClientTelemetryDeviceProfile>

    fun findDevice(uid: String, deviceId: String): ClientTelemetryDeviceProfile?

    fun pagePolicies(offset: Long, limit: Int): TelemetryPage<ClientTelemetryPolicy>

    fun enableDiagnosticPolicy(
        uid: String,
        deviceId: String?,
        reason: String,
        expiresAt: Long,
        actor: String,
        now: Long,
        /** 存在时，成功的管理回执必须与策略行一起提交。 */
        successAudit: TelemetryAdminAuditEntry? = null,
    ): ClientTelemetryPolicy

    fun disablePolicy(
        policyId: String,
        actor: String,
        now: Long,
        /** 仅当找到并停用了一条策略时，在同一事务中追加。 */
        successAudit: TelemetryAdminAuditEntry? = null,
    ): ClientTelemetryPolicy?

    /** 为每条已过期的活跃规则持久化显式的 BASELINE 终态与审计行。 */
    fun expirePolicies(now: Long, limit: Int): Int
}

object TelemetryStoragePolicy {
    const val RETENTION_MILLIS: Long = 168L * 60L * 60L * 1_000L
    const val MAX_RETENTION_DELETE_BATCHES: Int = 1_000
    const val MAX_ADMIN_PAGE: Int = 100
    const val MAX_POLICY_DURATION_MILLIS: Long = 24L * 60L * 60L * 1_000L
    const val MAX_POLICY_REASON_CHARS: Int = 500
}
