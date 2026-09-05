package com.virjar.tk.shared.client

import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import com.virjar.tk.protocol.telemetry.ClientTelemetryValidation
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal data class QueuedTelemetryBatch(
    val batch: TelemetryBatch,
    internal val fileName: String,
    internal val encodedJson: String,
)

/**
 * 精确身份、有界、多批的遥测假脱机。一个段只发布一次、永不重写；因此传输失败无法替换一个更旧的
 * 未确认批。
 */
class ClientTelemetrySpool internal constructor(
    private val store: ClientTelemetrySegmentStore,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = TELEMETRY_JSON,
) {
    private var evictedEventCount = 0L
    private var rootMaintenanceNotBeforeEpochMs = 0L

    constructor(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        datasetId: String,
        ownerUid: String,
    ) : this(
        store = createClientTelemetrySegmentStore(
            dataDir = dataDir,
            privateDirectories = telemetryPrivateDirectories(
                deploymentIdentity = deploymentIdentity,
                datasetId = datasetId,
                ownerUid = ownerUid,
            ),
        ),
    )

    init {
        require(maxFiles > 0) { "Telemetry spool file budget must be positive" }
        require(maxBytes in 1L..MAX_ABSOLUTE_SPOOL_BYTES) { "Invalid telemetry spool byte budget" }
        require(retentionMillis in 1L..MAX_ABSOLUTE_RETENTION_MILLIS) {
            "Invalid telemetry retention"
        }
    }

    /**
     * 当活跃的保留假脱机已满时返回 false；任何保留段都不会被覆盖。这个阻塞边界在 uploader 的 IO
     * worker 上从 [ClientTelemetryRecorder.flush] 到达。record 与 UI 调用路径绝不进入段或根目录维护。
     */
    @Synchronized
    fun append(batch: TelemetryBatch, highPriority: Boolean = false): Boolean {
        ClientTelemetryValidation.requireValid(batch)
        require(!batch.heartbeat && batch.events.isNotEmpty()) { "Heartbeat batches are not spooled" }
        val encoded = json.encodeToString(batch)
        val encodedBytes = encoded.encodeToByteArray().size.toLong()
        require(encodedBytes <= MAX_SEGMENT_BYTES) { "Telemetry segment exceeds the wire hard limit" }
        val fileName = segmentFileName(batch)

        pruneExpiredLocked(clock())
        store.read(fileName)?.let { existing ->
            check(existing == encoded) { "Stable telemetry batchId resolved to different content" }
            return true
        }

        var retained = retainedFilesLocked()
        while (retained.size >= maxFiles || retained.sumOf(StoredTelemetrySegmentFile::byteCount) + encodedBytes > maxBytes) {
            if (!highPriority) return false
            val evictable = retained
                .map(::decodeStoredBatch)
                .filterNot(QueuedTelemetryBatch::containsHighPriorityEvent)
                .minWithOrNull(compareBy<QueuedTelemetryBatch>({ it.batch.createdAtEpochMs }, { it.fileName }))
                ?: return false
            check(store.delete(evictable.fileName)) { "Failed to evict a low-priority telemetry segment" }
            evictedEventCount += evictable.batch.events.size
            retained = retainedFilesLocked()
        }
        store.writeNew(fileName, encoded)
        check(store.read(fileName) == encoded) { "Telemetry segment was not durably published" }
        return true
    }

    @Synchronized
    internal fun oldest(): QueuedTelemetryBatch? {
        pruneExpiredLocked(clock())
        return retainedFilesLocked()
            .map(::decodeStoredBatch)
            .minWithOrNull(compareBy<QueuedTelemetryBatch>({ it.batch.createdAtEpochMs }, { it.fileName }))
    }

    /** 只删除通过其精确最终 sequence 确认的、完全相同的不可变批。 */
    @Synchronized
    fun acknowledge(batchId: String, acceptedThroughSequence: Long): Boolean {
        val queued = retainedFilesLocked()
            .asSequence()
            .map(::decodeStoredBatch)
            .firstOrNull { it.batch.batchId == batchId }
            ?: return false
        check(queued.batch.events.last().sequence == acceptedThroughSequence) {
            "Telemetry ACK does not cover the exact queued batch"
        }
        val unchanged = store.read(queued.fileName)
        check(unchanged == queued.encodedJson) { "Telemetry segment changed before ACK deletion" }
        return store.delete(queued.fileName)
    }

    /**
     * 只丢弃服务器权威判定为超出当前遥测策略的那个完全相同的不可变批。身份或内容漂移会按失败关闭
     * 处理，并且什么都不删。
     */
    @Synchronized
    internal fun discardRejectedExact(batchId: String, encodedJson: String): Boolean {
        val queued = retainedFilesLocked()
            .asSequence()
            .map(::decodeStoredBatch)
            .firstOrNull { it.batch.batchId == batchId }
            ?: return false
        if (queued.encodedJson != encodedJson || store.read(queued.fileName) != encodedJson) {
            return false
        }
        if (!store.delete(queued.fileName)) return false
        evictedEventCount += queued.batch.events.size
        return true
    }

    /** 阻塞的诊断/测试边界；生产调用方使用 uploader IO worker。 */
    @Synchronized
    fun retainedBatchIds(): List<String> {
        pruneExpiredLocked(clock())
        return retainedFilesLocked()
            .map(::decodeStoredBatch)
            .sortedWith(compareBy<QueuedTelemetryBatch>({ it.batch.createdAtEpochMs }, { it.fileName }))
            .map { it.batch.batchId }
    }

    @Synchronized
    internal fun evictedEvents(): Long = evictedEventCount

    private fun pruneExpiredLocked(nowEpochMs: Long): Int {
        require(nowEpochMs >= 0L) { "Telemetry clock must be a non-negative epoch" }
        val cutoff = (nowEpochMs - retentionMillis).coerceAtLeast(0L)
        pruneExpiredNamespacesLocked(nowEpochMs, cutoff)
        var deleted = 0
        retainedFilesLocked().forEach { file ->
            val queued = decodeStoredBatch(file)
            if (queued.batch.createdAtEpochMs < cutoff || file.lastModifiedEpochMs < cutoff) {
                if (store.delete(file.fileName)) deleted++
            }
        }
        return deleted
    }

    private fun pruneExpiredNamespacesLocked(nowEpochMs: Long, cutoffEpochMs: Long) {
        if (nowEpochMs < rootMaintenanceNotBeforeEpochMs) return
        val maintenance = store.maintainNamespaces(
            nowEpochMs = nowEpochMs,
            cutoffEpochMs = cutoffEpochMs,
            retentionMillis = retentionMillis,
            maxVisitedNodes = MAX_ROOT_SCAN_NODES,
            maxDeletes = MAX_ROOT_DELETIONS_PER_PASS,
        )
        require(maintenance.visitedNodes <= MAX_ROOT_SCAN_NODES) {
            "Telemetry root scan exceeded its node budget"
        }
        require(maintenance.nextMaintenanceEpochMs >= nowEpochMs) {
            "Telemetry root maintenance scheduled in the past"
        }
        rootMaintenanceNotBeforeEpochMs = maintenance.nextMaintenanceEpochMs
    }

    private fun retainedFilesLocked(): List<StoredTelemetrySegmentFile> {
        val files = store.list()
        require(files.size <= MAX_ABSOLUTE_FILES) { "Telemetry namespace exceeds its hard file limit" }
        return files.onEach { file ->
            require(CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(file.fileName)) {
                "Unexpected telemetry spool file"
            }
            require(file.byteCount in 1L..MAX_SEGMENT_BYTES) { "Invalid telemetry segment size" }
        }
    }

    private fun decodeStoredBatch(file: StoredTelemetrySegmentFile): QueuedTelemetryBatch {
        val encoded = checkNotNull(store.read(file.fileName)) { "Telemetry segment disappeared during scan" }
        require(encoded.encodeToByteArray().size.toLong() == file.byteCount) {
            "Telemetry segment changed during scan"
        }
        val batch = json.decodeFromString<TelemetryBatch>(encoded)
        ClientTelemetryValidation.requireValid(batch)
        require(!batch.heartbeat && batch.events.isNotEmpty()) { "Stored telemetry heartbeat is invalid" }
        require(segmentFileName(batch) == file.fileName) { "Telemetry segment name/content mismatch" }
        return QueuedTelemetryBatch(batch, file.fileName, encoded)
    }

    private fun segmentFileName(batch: TelemetryBatch): String =
        "telemetry-${batch.createdAtEpochMs.toString().padStart(13, '0')}-${batch.batchId}.json"

    companion object {
        const val DEFAULT_MAX_FILES: Int = 64
        const val DEFAULT_MAX_BYTES: Long = 16L * 1024L * 1024L
        const val DEFAULT_RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
        const val MAX_SEGMENT_BYTES: Long = 1024L * 1024L
        const val MAX_ABSOLUTE_FILES: Int = 256
        const val MAX_ABSOLUTE_SPOOL_BYTES: Long = 64L * 1024L * 1024L
        const val MAX_ABSOLUTE_RETENTION_MILLIS: Long = DEFAULT_RETENTION_MILLIS
        internal const val MAX_ROOT_SCAN_NODES: Int = 4096
        internal const val MAX_ROOT_DELETIONS_PER_PASS: Int = 32
        internal val TELEMETRY_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = false
            classDiscriminator = "type"
        }
    }
}

private fun QueuedTelemetryBatch.containsHighPriorityEvent(): Boolean {
    val baseline = com.virjar.tk.protocol.telemetry.TelemetryPolicy.baseline()
    return batch.events.any { event ->
        ClientTelemetryValidation.allows(baseline, event, event.occurredAtEpochMs)
    }
}

private fun telemetryPrivateDirectories(
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    ownerUid: String,
): List<String> {
    SyncDatasetIdPolicy.requireValid(datasetId)
    require(ownerUid.isNotBlank()) { "Telemetry spool owner uid must not be blank" }
    return listOf(
        CLIENT_TELEMETRY_ROOT_DIRECTORY,
        stableTelemetryNamespace(deploymentIdentity.fingerprint),
        stableTelemetryNamespace(datasetId),
        stableTelemetryNamespace(ownerUid),
    )
}

/** 原始部署坐标、dataset id 与 uid 绝不会成为本地路径组件。 */
internal fun stableTelemetryNamespace(value: String): String {
    require(value.isNotEmpty()) { "Telemetry namespace identity must not be empty" }
    var first = 1_125_899_906_842_597L
    var second = -7_046_029_254_386_353_131L
    value.forEach { char ->
        first = first * 31L + char.code
        second = (second xor char.code.toLong()) * 1_099_511_628_211L
    }
    return "${value.length}-${first.toString(36)}-${second.toString(36)}"
}
