package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryIngestResult
import kotlinx.coroutines.CompletableDeferred
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.SerialMergeScheduler
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.SimpleCollector
import org.apache.lucene.search.CollectorManager
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.store.FilterDirectory
import org.apache.lucene.store.FilterIndexOutput
import org.apache.lucene.store.IOContext
import org.apache.lucene.store.IndexOutput
import java.io.File
import java.io.IOException

internal const val CLIENT_TELEMETRY_RETENTION_OVERDUE_MILLIS = 75L * 60L * 1_000L

internal class TelemetryQueueBudget(private val maxBytes: Long) {
    private val lock = Any()
    private var reservedBytes = 0L

    fun reserve(bytes: Long): Boolean = synchronized(lock) {
        if (bytes > maxBytes || reservedBytes > maxBytes - bytes) return@synchronized false
        reservedBytes += bytes
        true
    }

    fun release(bytes: Long) = synchronized(lock) {
        reservedBytes = (reservedBytes - bytes).coerceAtLeast(0L)
    }
}

internal sealed interface WriteCommand {
    fun fail(failure: Throwable)
    fun releaseReservation()
}

internal class IngestCommand(
    val uid: String,
    val deviceId: String,
    val batch: TelemetryBatchDraft,
    val receivedAt: Long,
    sourceBytes: Int,
    val completion: CompletableDeferred<TelemetryIngestResult>,
    private val releaseQueueBytes: (Long) -> Unit,
) : WriteCommand {
    private val reservedBytes = sourceBytes.toLong()

    override fun fail(failure: Throwable) {
        completion.completeExceptionally(failure)
    }

    override fun releaseReservation() {
        releaseQueueBytes(reservedBytes)
    }
}

internal class RetentionCommand(
    val receivedBefore: Long,
    val completion: CompletableDeferred<Boolean>,
) : WriteCommand {
    override fun fail(failure: Throwable) {
        completion.completeExceptionally(failure)
    }

    override fun releaseReservation() = Unit
}

internal data class PendingBatch(
    val primary: IngestCommand,
    val followers: MutableList<IngestCommand> = mutableListOf(),
)

internal data class TelemetryAccountedDocument(
    val document: Document,
    val accountedBytes: Long,
)

internal data class TelemetryStoredStats(
    val documents: Long,
    val accountedBytes: Long,
)

internal class TelemetryAccountedBytesCollector : SimpleCollector() {
    private var values: org.apache.lucene.index.NumericDocValues? = null
    var documents: Long = 0L
        private set
    var accountedBytes: Long = 0L
        private set

    override fun doSetNextReader(context: LeafReaderContext) {
        values = DocValues.getNumeric(context.reader(), FIELD_ACCOUNTED_BYTES)
    }

    override fun collect(doc: Int) {
        val openedValues = checkNotNull(values)
        if (openedValues.advanceExact(doc)) {
            accountedBytes = Math.addExact(accountedBytes, openedValues.longValue())
        }
        documents++
    }

    override fun scoreMode(): ScoreMode = ScoreMode.COMPLETE_NO_SCORES
}

internal fun collectTelemetryStats(searcher: IndexSearcher, query: Query): TelemetryStoredStats =
    searcher.search(
        query,
        object : CollectorManager<TelemetryAccountedBytesCollector, TelemetryStoredStats> {
            override fun newCollector() = TelemetryAccountedBytesCollector()

            override fun reduce(
                collectors: Collection<TelemetryAccountedBytesCollector>,
            ) = TelemetryStoredStats(
                documents = collectors.fold(0L) { total, collector ->
                    Math.addExact(total, collector.documents)
                },
                accountedBytes = collectors.fold(0L) { total, collector ->
                    Math.addExact(total, collector.accountedBytes)
                },
            )
        },
    )

internal fun telemetryPhysicalBytes(directory: Directory): Long =
    directory.listAll().fold(0L) { total, name ->
        Math.addExact(total, directory.fileLength(name))
    }

internal fun telemetryWriterConfig(analyzer: Analyzer, mode: IndexWriterConfig.OpenMode) =
    IndexWriterConfig(analyzer).apply {
        openMode = mode
        setMergeScheduler(SerialMergeScheduler())
    }

internal fun telemetryHasPhysicalCapacity(
    currentPhysicalBytes: Long,
    candidateAccountedBytes: Long,
    maxPhysicalBytes: Long,
): Boolean {
    val estimatedCandidateWrite = saturatedMultiply(candidateAccountedBytes, TELEMETRY_DISK_WRITE_FACTOR)
    val worstCasePhysicalBytes = saturatedAdd(
        saturatedMultiply(currentPhysicalBytes, TELEMETRY_MERGE_TRANSIENT_FACTOR),
        estimatedCandidateWrite,
    )
    return worstCasePhysicalBytes <= maxPhysicalBytes
}

internal fun telemetryCandidateFitsPhysicalEmpty(
    candidateAccountedBytes: Long,
    maxPhysicalBytes: Long,
): Boolean = saturatedMultiply(candidateAccountedBytes, TELEMETRY_DISK_WRITE_FACTOR) <= maxPhysicalBytes

internal fun telemetryHasDiskCapacity(
    indexDir: File,
    currentPhysicalBytes: Long,
    candidateAccountedBytes: Long,
): Boolean {
    val estimatedCandidateWrite = saturatedMultiply(candidateAccountedBytes, TELEMETRY_DISK_WRITE_FACTOR)
    val transientFreeSpace = saturatedAdd(currentPhysicalBytes, estimatedCandidateWrite)
    return indexDir.usableSpace >= saturatedAdd(TELEMETRY_MIN_FREE_DISK_BYTES, transientFreeSpace)
}

private fun saturatedAdd(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L)
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

private fun saturatedMultiply(value: Long, factor: Long): Long {
    require(value >= 0L && factor > 0L)
    return if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
}

/**
 * Lucene 写入器之下的最终物理安全 fence。准入预留合并余量，而
 * 此目录在字节到达磁盘之前，拒绝任何意外的编解码器/合并放大。
 */
internal class PhysicalQuotaDirectory(
    private val delegateDirectory: FSDirectory,
    private val indexRoot: File,
    private val maxPhysicalBytes: Long,
) : FilterDirectory(delegateDirectory) {
    private val quotaLock = Any()
    private var trackedBytes: Long = measurePhysicalBytes()

    override fun createOutput(name: String, context: IOContext): IndexOutput =
        quotaOutput(delegateDirectory.createOutput(name, context))

    override fun createTempOutput(prefix: String, suffix: String, context: IOContext): IndexOutput =
        quotaOutput(delegateDirectory.createTempOutput(prefix, suffix, context))

    override fun deleteFile(name: String) {
        delegateDirectory.deleteFile(name)
        refreshTrackedBytes()
    }

    override fun rename(source: String, dest: String) {
        delegateDirectory.rename(source, dest)
        refreshTrackedBytes()
    }

    private fun quotaOutput(output: IndexOutput): IndexOutput {
        refreshTrackedBytes()
        return object : FilterIndexOutput("client telemetry quota output", output.name, output) {
            override fun writeByte(value: Byte) {
                reserve(1L)
                super.writeByte(value)
            }

            override fun writeBytes(bytes: ByteArray, offset: Int, length: Int) {
                reserve(length.toLong())
                super.writeBytes(bytes, offset, length)
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    refreshTrackedBytes()
                }
            }
        }
    }

    private fun reserve(bytes: Long) = synchronized(quotaLock) {
        if (bytes < 0L || trackedBytes > maxPhysicalBytes - bytes) {
            throw IOException("client telemetry physical quota exhausted")
        }
        trackedBytes += bytes
    }

    private fun refreshTrackedBytes() = synchronized(quotaLock) {
        trackedBytes = measurePhysicalBytes()
    }

    private fun measurePhysicalBytes(): Long = indexRoot.walkTopDown()
        .filter(File::isFile)
        .fold(0L) { total, file -> Math.addExact(total, file.length()) }
}

private const val TELEMETRY_MIN_FREE_DISK_BYTES = 512L * 1024L * 1024L
private const val TELEMETRY_DISK_WRITE_FACTOR = 4L
private const val TELEMETRY_MERGE_TRANSIENT_FACTOR = 2L
