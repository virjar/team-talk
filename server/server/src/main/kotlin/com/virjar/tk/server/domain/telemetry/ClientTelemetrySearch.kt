package com.virjar.tk.server.domain.telemetry

/** 结构化过滤器除 keyword（作为产品文本分析）外都是精确匹配。 */
data class TelemetrySearchQuery(
    val keyword: String? = null,
    val uid: String? = null,
    val deviceId: String? = null,
    val platform: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val gitCommit: String? = null,
    val category: String? = null,
    val eventName: String? = null,
    val receivedAtFrom: Long,
    val receivedAtUntil: Long,
    val outgoingQueue: TelemetryOutgoingQueueQuery? = null,
)

data class TelemetryNumericRange(
    val minInclusive: Long? = null,
    val maxInclusive: Long? = null,
)

/** 可选的数值过滤器；缺失字段绝不回退为解析索引的展示文本。 */
data class TelemetryOutgoingQueueQuery(
    val pendingCount: TelemetryNumericRange? = null,
    val retryWaitCount: TelemetryNumericRange? = null,
    val terminalFailedCount: TelemetryNumericRange? = null,
    val oldestActiveAgeMillis: TelemetryNumericRange? = null,
    val maxAttemptCount: TelemetryNumericRange? = null,
)

data class TelemetrySearchHit(
    val event: StoredTelemetryEvent,
    val highlight: TelemetryTextHighlight?,
)

/** UTF-16 偏移；[end] 是排他的，因此 JVM 与浏览器字符串切片使用相同单位。 */
data class TelemetryHighlightSpan(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0 && end > start) { "telemetry highlight span is invalid" }
    }
}

/** 纯文本加上有序、互不重叠的区间。它绝不携带标记（markup）。 */
data class TelemetryTextHighlight(
    val text: String,
    val spans: List<TelemetryHighlightSpan>,
) {
    init {
        var previousEnd = 0
        spans.forEach { span ->
            require(span.start >= previousEnd && span.end <= text.length) {
                "telemetry highlight spans are not ordered within text"
            }
            previousEnd = span.end
        }
    }
}

data class TelemetrySearchPage(
    val total: Long,
    val hits: List<TelemetrySearchHit>,
)

/** 公开安全的保留就绪状态；它刻意不包含路径或失败文本。 */
data class TelemetryRetentionStatus(
    val lastSuccessAt: Long?,
    val backlog: Boolean,
    val overdue: Boolean,
)

class TelemetrySearchUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("client telemetry event store is unavailable", cause)

/**
 * 七天事件权威。实现必须在完成 [ingest] 之前原子提交一个非空批次回执及其全部事件。
 * 手机号与任意原始载荷 JSON 被禁止。
 */
interface ClientTelemetryEventStore {
    /** 重新打开一个结构有效的单写入者提交；无效的可丢弃遥测重置为空。 */
    fun start(): Boolean

    fun findBatchReceipt(uid: String, deviceId: String, batchId: String): TelemetryBatchReceipt?

    /** 仅由已认证管理端使用的精确内部 Lucene 记录身份。 */
    fun findEventById(recordId: Long): StoredTelemetryEvent?

    suspend fun ingest(
        uid: String,
        deviceId: String,
        batch: TelemetryBatchDraft,
        receivedAt: Long,
        sourceBytes: Int,
    ): TelemetryIngestResult

    fun search(query: TelemetrySearchQuery, offset: Int, limit: Int): TelemetrySearchPage

    /** 删除 receivedAt 严格早于 [receivedBefore] 的事件与回执。 */
    suspend fun deleteBefore(receivedBefore: Long): Boolean

    fun retentionStatus(nowEpochMs: Long = System.currentTimeMillis()): TelemetryRetentionStatus

    fun isAvailable(): Boolean

    fun close()
}
