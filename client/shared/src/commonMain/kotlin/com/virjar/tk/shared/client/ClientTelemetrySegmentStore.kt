package com.virjar.tk.shared.client

import java.io.File

internal data class StoredTelemetrySegmentFile(
    val fileName: String,
    val byteCount: Long,
    val lastModifiedEpochMs: Long,
)

internal data class StoredTelemetryNamespaceEntry(
    val fileName: String,
    val byteCount: Long,
    val lastModifiedEpochMs: Long,
    /** 不透明文件系统身份，仅用于拒绝已变化的删除目标。 */
    val storageIdentity: Any?,
)

/**
 * [CLIENT_TELEMETRY_ROOT_DIRECTORY] 之下经过校验的、固定深度的遥测命名空间快照。
 *
 * 实现只会发布叶子中仅包含遥测段、可选版本标记或空内容的快照。未知文件、链接与嵌套目录绝不会
 * 出现在这里，因此也不可能成为删除目标。
 */
internal data class StoredTelemetryNamespace(
    val identityDirectories: List<String>,
    val retentionReferenceEpochMs: Long,
    val directoryStorageIdentity: Any?,
    val entries: List<StoredTelemetryNamespaceEntry>,
)

internal data class StoredTelemetryNamespaceScan(
    val namespaces: List<StoredTelemetryNamespace>,
    val visitedNodes: Int,
    val truncated: Boolean,
)

internal data class TelemetryNamespaceCleanup(
    val snapshot: StoredTelemetryNamespace,
    val expiredSegmentFileNames: List<String>,
    val deleteWholeNamespace: Boolean,
)

internal data class TelemetryNamespaceMaintenanceResult(
    val visitedNodes: Int,
    val truncated: Boolean,
    val nextMaintenanceEpochMs: Long,
)

/** 持久遥测假脱机使用的私有、不可变文件存储。 */
internal interface ClientTelemetrySegmentStore {
    /** 本存储拥有的精确 deployment/dataset/uid 命名空间。 */
    val identityDirectories: List<String>

    /** 仅对完全相同的不可变内容幂等；不同内容绝不覆盖。 */
    fun writeNew(fileName: String, content: String)

    fun read(fileName: String): String?

    fun list(): List<StoredTelemetrySegmentFile>

    fun delete(fileName: String): Boolean

    /** 一次 root 锁持有的盘点、清理与持久化维护循环。 */
    fun maintainNamespaces(
        nowEpochMs: Long,
        cutoffEpochMs: Long,
        retentionMillis: Long,
        maxVisitedNodes: Int,
        maxDeletes: Int,
    ): TelemetryNamespaceMaintenanceResult
}

internal expect fun createClientTelemetrySegmentStore(
    dataDir: File,
    privateDirectories: List<String>,
): ClientTelemetrySegmentStore

internal const val CLIENT_TELEMETRY_ROOT_DIRECTORY = "client-telemetry"
internal const val CLIENT_TELEMETRY_MARKER_FILE = "telemetry-spool.version"
internal const val CLIENT_TELEMETRY_MARKER_CONTENT = "1"
internal const val MAX_TELEMETRY_SEGMENT_BYTES: Long = 1024L * 1024L
internal const val MAX_TELEMETRY_NAMESPACE_SEGMENTS: Int = 256

internal val CLIENT_TELEMETRY_SEGMENT_FILE_REGEX = Regex(
    "telemetry-[0-9]{13,19}-[A-Za-z0-9._-]{1,64}\\.json",
)

/** 每个精确目录最多有一个待处理的、由 root 锁串行化的原子替换。 */
internal const val CLIENT_TELEMETRY_ATOMIC_PENDING_FILE = ".telemetry-atomic.pending"
internal val CLIENT_TELEMETRY_LEAF_ORPHAN_TEMP_FILE_REGEX =
    Regex(Regex.escape(CLIENT_TELEMETRY_ATOMIC_PENDING_FILE))

private val CLIENT_TELEMETRY_IDENTITY_DIRECTORY_REGEX = Regex(
    "[1-9][0-9]{0,9}--?[0-9a-z]{1,13}--?[0-9a-z]{1,13}",
)

internal fun isTelemetryIdentityDirectories(components: List<String>): Boolean =
    components.size == 3 && components.all(CLIENT_TELEMETRY_IDENTITY_DIRECTORY_REGEX::matches)

internal fun requireTelemetryPrivateDirectories(privateDirectories: List<String>): List<String> =
    privateDirectories.also { directories ->
        require(
            directories.size == 4 &&
                directories.firstOrNull() == CLIENT_TELEMETRY_ROOT_DIRECTORY &&
                isTelemetryIdentityDirectories(directories.drop(1)),
        ) { "Telemetry namespace must be the exact deployment/dataset/uid hierarchy" }
    }

/**
 * 纯清理策略：绝不选中当前 owner，独立地使旧段过期，并且只有在没有保留段剩余时才移除叶子。
 */
internal fun selectExpiredTelemetryNamespaceCleanups(
    currentIdentityDirectories: List<String>,
    scan: StoredTelemetryNamespaceScan,
    cutoffEpochMs: Long,
    maxDeletes: Int,
): List<TelemetryNamespaceCleanup> {
    require(isTelemetryIdentityDirectories(currentIdentityDirectories)) {
        "Current telemetry identity namespace is invalid"
    }
    require(cutoffEpochMs >= 0L) { "Telemetry namespace cutoff must be non-negative" }
    require(maxDeletes > 0) { "Telemetry namespace deletion budget must be positive" }
    require(scan.visitedNodes >= 0) { "Telemetry namespace scan count must be non-negative" }

    return scan.namespaces
        .asSequence()
        .mapNotNull { candidate ->
            telemetryNamespaceCleanup(candidate, currentIdentityDirectories, cutoffEpochMs)
        }
        .distinctBy { cleanup -> cleanup.snapshot.identityDirectories }
        .sortedWith(
            compareBy<TelemetryNamespaceCleanup> { cleanup ->
                cleanup.expiredSegmentFileNames
                    .mapNotNull(::telemetrySegmentCreatedAtEpochMs)
                    .minOrNull()
                    ?: cleanup.snapshot.retentionReferenceEpochMs
            }.thenBy { it.snapshot.identityDirectories.joinToString("/") },
        )
        .take(maxDeletes)
        .toList()
}

internal fun nextTelemetryNamespaceMaintenanceEpochMs(
    currentIdentityDirectories: List<String>,
    scan: StoredTelemetryNamespaceScan,
    cutoffEpochMs: Long,
    retentionMillis: Long,
): Long? {
    require(isTelemetryIdentityDirectories(currentIdentityDirectories))
    return scan.namespaces.asSequence()
        .filter { candidate ->
            isTelemetryIdentityDirectories(candidate.identityDirectories) &&
                candidate.identityDirectories != currentIdentityDirectories
        }
        .flatMap { candidate ->
            val segments = candidate.entries.asSequence()
                .filter { CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(it.fileName) }
                .mapNotNull { entry -> entry.retentionReferenceEpochMs() }
                .toList()
            sequenceOf(candidate.retentionReferenceEpochMs)
                .filter { it >= cutoffEpochMs } +
                segments.asSequence().filter { it >= cutoffEpochMs }
        }
        .map { reference -> saturatingEpochAdd(reference, retentionMillis, 1L) }
        .minOrNull()
}

internal fun telemetrySegmentCreatedAtEpochMs(fileName: String): Long? {
    if (!CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(fileName)) return null
    return fileName.substringAfter("telemetry-").substringBefore('-').toLongOrNull()
}

private fun telemetryNamespaceCleanup(
    candidate: StoredTelemetryNamespace,
    currentIdentityDirectories: List<String>,
    cutoffEpochMs: Long,
): TelemetryNamespaceCleanup? {
    if (!isTelemetryIdentityDirectories(candidate.identityDirectories) ||
        candidate.identityDirectories == currentIdentityDirectories ||
        candidate.retentionReferenceEpochMs < 0L ||
        candidate.entries.any { entry ->
            entry.fileName != CLIENT_TELEMETRY_MARKER_FILE &&
                !CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(entry.fileName)
        }
    ) {
        return null
    }
    val segments = candidate.entries.filter { entry ->
        CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(entry.fileName)
    }
    if (segments.any { it.retentionReferenceEpochMs() == null }) return null
    val expired = segments.filter { entry ->
        checkNotNull(entry.retentionReferenceEpochMs()) < cutoffEpochMs
    }
    return when {
        segments.isEmpty() && candidate.retentionReferenceEpochMs < cutoffEpochMs ->
            TelemetryNamespaceCleanup(candidate, emptyList(), deleteWholeNamespace = true)
        expired.isEmpty() -> null
        expired.size == segments.size && candidate.retentionReferenceEpochMs < cutoffEpochMs -> TelemetryNamespaceCleanup(
            candidate,
            expired.map(StoredTelemetryNamespaceEntry::fileName).sorted(),
            deleteWholeNamespace = true,
        )
        else -> TelemetryNamespaceCleanup(
            candidate,
            expired.map(StoredTelemetryNamespaceEntry::fileName).sorted(),
            deleteWholeNamespace = false,
        )
    }
}

private fun StoredTelemetryNamespaceEntry.retentionReferenceEpochMs(): Long? {
    if (lastModifiedEpochMs < 0L) return null
    val createdAt = telemetrySegmentCreatedAtEpochMs(fileName) ?: return null
    return minOf(createdAt, lastModifiedEpochMs)
}

internal fun saturatingEpochAdd(first: Long, second: Long, third: Long): Long {
    require(first >= 0L && second >= 0L && third >= 0L)
    if (first > Long.MAX_VALUE - second) return Long.MAX_VALUE
    val firstTwo = first + second
    return if (firstTwo > Long.MAX_VALUE - third) Long.MAX_VALUE else firstTwo + third
}
