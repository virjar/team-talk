package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.TelemetryBatchConflictException
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryBatchReceipt
import com.virjar.tk.server.domain.telemetry.TelemetryIngestResult
import com.virjar.tk.server.domain.telemetry.TelemetryIngestStatus
import com.virjar.tk.server.domain.telemetry.TelemetryRetentionStatus
import com.virjar.tk.server.domain.telemetry.TelemetrySearchPage
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.domain.telemetry.TelemetryStoreBusyException
import com.virjar.tk.server.domain.telemetry.TelemetryStoreCapacityException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.LongPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.Query
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.slf4j.LoggerFactory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 单实例的七天遥测权威源。
 *
 * 上传进入一个有界写入器，与其回执分组提交，并且只在
 * Lucene 持久提交之后才完成。PostgreSQL 只拥有低频设备/策略控制事实。
 */
class ClientTelemetrySearchIndex(
    private val indexDir: File,
    private val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
    private val maxDocuments: Long = DEFAULT_MAX_DOCUMENTS,
    private val maxAccountedBytes: Long = DEFAULT_MAX_ACCOUNTED_BYTES,
    private val maxPhysicalBytes: Long = DEFAULT_MAX_PHYSICAL_BYTES,
    private val groupCommitDelayMillis: Long = DEFAULT_GROUP_COMMIT_DELAY_MILLIS,
    private val maxGroupCommands: Int = DEFAULT_MAX_GROUP_COMMANDS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClientTelemetryEventStore {
    private val logger = LoggerFactory.getLogger(ClientTelemetrySearchIndex::class.java)
    private val lifecycleLock = Any()
    private val queueBudget = TelemetryQueueBudget(maxQueuedBytes)
    /** 防止 Unix 上已打开即解除链接的段逃逸物理配额核算。 */
    private val searchMutationGate = ReentrantReadWriteLock(true)
    private val accepting = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val commands = Channel<WriteCommand>(DEFAULT_QUEUE_COMMANDS)
    /** 保留的维护通道；偏置选择把保留延迟限定在一个摄入组内。 */
    private val retentionCommands = Channel<RetentionCommand>(1)
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "client-telemetry-writer").apply { isDaemon = true }
        },
    ).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var analyzer: Analyzer? = null
    @Volatile
    private var directory: Directory? = null
    @Volatile
    private var writer: IndexWriter? = null
    @Volatile
    private var searchers: SearcherManager? = null
    @Volatile
    private var nextRecordId: Long = 1L
    @Volatile
    private var accountedBytes: Long = 0L
    @Volatile
    private var documentCount: Long = 0L
    @Volatile
    internal var durableCommitCount: Long = 0L
        private set
    private var writerJob: Job? = null
    private val terminalFailure = AtomicReference<TelemetrySearchUnavailableException?>(null)
    @Volatile
    private var retentionStartedAt: Long = 0L
    @Volatile
    private var retentionLastSuccessAt: Long? = null
    private val retentionBacklog = AtomicBoolean(true)
    @Volatile
    internal var searchLeaseHookForTest: (() -> Unit)? = null

    init {
        require(maxQueuedBytes > 0L)
        require(maxDocuments > 0L)
        require(maxAccountedBytes > 0L)
        require(maxPhysicalBytes > 0L)
        require(groupCommitDelayMillis in 0L..1_000L)
        require(maxGroupCommands > 0)
    }

    override fun start(): Boolean = synchronized(lifecycleLock) {
        check(!closed.get()) { "client telemetry event store is closed" }
        if (terminalFailure.get() != null) return@synchronized false
        if (writer != null && searchers != null) return@synchronized true
        val reopened = try {
            openRuntime(reset = false)
            logger.info(
                "Client telemetry event store reopened (documents={}, accountedBytes={})",
                documentCount,
                accountedBytes,
            )
            true
        } catch (failure: Exception) {
            closeRuntime(rollback = true)?.let(failure::addSuppressed)
            logger.warn(
                "Client telemetry index is missing, invalid, or corrupt; resetting disposable seven-day events",
                failure,
            )
            runCatching { openRuntime(reset = true) }
                .onFailure { resetFailure ->
                    closeRuntime(rollback = true)?.let(resetFailure::addSuppressed)
                    logger.warn("Client telemetry event store reset failed", resetFailure)
                }
                .isSuccess
        }
        if (reopened) {
            retentionStartedAt = clock()
            retentionBacklog.set(true)
            accepting.set(true)
            writerJob = scope.launch { writerLoop() }
        }
        reopened
    }

    override fun findBatchReceipt(uid: String, deviceId: String, batchId: String): TelemetryBatchReceipt? =
        withSearcher { searcher ->
            findTelemetryReceipt(searcher, telemetryReceiptKey(uid, deviceId, batchId))
        }

    override fun findEventById(recordId: Long) = withSearcher { searcher ->
        findTelemetryEventById(searcher, recordId)
    }

    override suspend fun ingest(
        uid: String,
        deviceId: String,
        batch: TelemetryBatchDraft,
        receivedAt: Long,
        sourceBytes: Int,
    ): TelemetryIngestResult {
        require(uid.isNotBlank() && deviceId.isNotBlank()) { "telemetry owner is required" }
        require(batch.events.isNotEmpty()) { "only non-empty telemetry batches are persisted" }
        require(receivedAt > 0L && sourceBytes > 0) { "invalid telemetry write accounting" }
        requireValidTelemetryDraft(uid, deviceId, batch)
        if (!isAvailable()) throw TelemetrySearchUnavailableException()
        if (!queueBudget.reserve(sourceBytes.toLong())) throw TelemetryStoreBusyException()
        val completion = CompletableDeferred<TelemetryIngestResult>()
        val command = IngestCommand(
            uid,
            deviceId,
            batch,
            receivedAt,
            sourceBytes,
            completion,
            queueBudget::release,
        )
        if (commands.trySend(command).isFailure) {
            queueBudget.release(sourceBytes.toLong())
            if (!isAvailable()) throw TelemetrySearchUnavailableException()
            throw TelemetryStoreBusyException()
        }
        return completion.await()
    }

    override suspend fun deleteBefore(receivedBefore: Long): Boolean {
        retentionBacklog.set(true)
        if (!isAvailable()) return false
        val completion = CompletableDeferred<Boolean>()
        return try {
            retentionCommands.send(RetentionCommand(receivedBefore, completion))
            completion.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun retentionStatus(nowEpochMs: Long): TelemetryRetentionStatus {
        require(nowEpochMs >= 0L) { "telemetry retention status time is invalid" }
        val lastSuccess = retentionLastSuccessAt
        val reference = lastSuccess ?: retentionStartedAt.takeIf { it > 0L }
        val overdue = reference != null &&
            nowEpochMs - reference > CLIENT_TELEMETRY_RETENTION_OVERDUE_MILLIS
        return TelemetryRetentionStatus(
            lastSuccessAt = lastSuccess,
            backlog = retentionBacklog.get(),
            overdue = overdue,
        )
    }

    override fun search(query: TelemetrySearchQuery, offset: Int, limit: Int): TelemetrySearchPage {
        require(offset >= 0 && limit > 0 && offset.toLong() + limit <= MAX_TELEMETRY_COLLECTION_WINDOW) {
            "telemetry search exceeds the $MAX_TELEMETRY_COLLECTION_WINDOW-hit collection window"
        }
        require(query.receivedAtFrom <= query.receivedAtUntil) { "telemetry time range is inverted" }
        requireTelemetrySearchText(query.keyword)
        // 调用方控制的数值范围是校验错误，不是 Lucene 运行时故障。
        // 把它们留在 withSearcher 之外，使坏的管理员查询无法终结事件存储。
        requireValidOutgoingQueueQuery(query)
        return withSearcher { searcher ->
            val openedAnalyzer = analyzer ?: throw TelemetrySearchUnavailableException()
            searchLeaseHookForTest?.invoke()
            searchTelemetryEvents(searcher, openedAnalyzer, query, offset, limit)
        }
    }

    override fun isAvailable(): Boolean =
        accepting.get() && writer != null && searchers != null && !closed.get()

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            accepting.set(false)
            commands.close()
            retentionCommands.close()
        }
        var closeFailure: Throwable? = null
        fun capture(failure: Throwable) {
            val previous = closeFailure
            if (previous == null) closeFailure = failure else previous.addSuppressed(failure)
        }
        runCatching { runBlocking { writerJob?.join() } }.exceptionOrNull()?.let(::capture)
        synchronized(lifecycleLock) {
            closeRuntime(rollback = false)?.let(::capture)
        }
        terminalFailure.get()?.let(::capture)
        runCatching { dispatcher.close() }.exceptionOrNull()?.let(::capture)
        closeFailure?.let { throw IllegalStateException("client telemetry event store did not close cleanly", it) }
    }

    private suspend fun writerLoop() {
        var ingestClosed = false
        var retentionClosed = false
        while (!ingestClosed || !retentionClosed) {
            val prioritized = retentionCommands.tryReceive().getOrNull()
            val first = prioritized ?: select<WriteCommand?> {
                if (!retentionClosed) {
                    retentionCommands.onReceiveCatching { result ->
                        if (result.isClosed) retentionClosed = true
                        result.getOrNull()
                    }
                }
                if (!ingestClosed) {
                    commands.onReceiveCatching { result ->
                        if (result.isClosed) ingestClosed = true
                        result.getOrNull()
                    }
                }
            } ?: continue
            val group = ArrayList<WriteCommand>(maxGroupCommands)
            group += first
            if (first is IngestCommand && groupCommitDelayMillis > 0L) delay(groupCommitDelayMillis)
            while (first is IngestCommand && group.size < maxGroupCommands) {
                val next = commands.tryReceive().getOrNull() ?: break
                group += next
            }
            try {
                terminalFailure.get()?.let { throw it }
                processGroup(group)
            } catch (failure: Exception) {
                val unavailable = publishTerminalFailure(failure)
                logger.warn("Client telemetry writer failed; refusing new telemetry until restart", unavailable)
                group.forEach { it.fail(unavailable) }
                synchronized(lifecycleLock) {
                    closeRuntime(rollback = true)?.let(unavailable::addSuppressed)
                }
                failQueuedCommands(unavailable)
                break
            } finally {
                group.forEach(WriteCommand::releaseReservation)
            }
        }
        terminalFailure.get()?.let { unavailable ->
            synchronized(lifecycleLock) {
                closeRuntime(rollback = true)?.let(unavailable::addSuppressed)
            }
            failQueuedCommands(unavailable)
        }
    }

    private fun processGroup(group: List<WriteCommand>) {
        val ingestCommands = group.filterIsInstance<IngestCommand>()
        if (ingestCommands.isNotEmpty()) processIngests(ingestCommands)
        group.filterIsInstance<RetentionCommand>().forEach { command ->
            command.completion.complete(processRetention(command.receivedBefore))
        }
    }

    private fun processIngests(group: List<IngestCommand>) {
        terminalFailure.get()?.let { throw it }
        val primaries = coalesceIngestCommands(group)
        if (primaries.isEmpty()) return
        val acceptedPrimaries = resolveIngestEventKeyConflicts(primaries)
        if (acceptedPrimaries.isEmpty()) return

        val documents = buildIngestDocuments(acceptedPrimaries)
        val projection = admitIngestCapacity(
            acceptedPrimaries = acceptedPrimaries,
            candidateAccountedBytes = documents.candidateAccountedBytes,
            candidateDocuments = documents.eventDocuments.size.toLong() + documents.receiptDocuments.size,
        ) ?: return

        val openedWriter = writer ?: throw TelemetrySearchUnavailableException()
        searchMutationGate.write {
            terminalFailure.get()?.let { throw it }
            documents.receiptDocuments.forEach { candidate ->
                openedWriter.addDocument(candidate.document)
            }
            documents.eventDocuments.values.forEach { candidate ->
                openedWriter.addDocument(candidate.document)
            }
            commitState(openedWriter, documents.nextRecordId, projection.projectedBytes, projection.projectedDocuments)
        }
        acceptedPrimaries.values.forEach { pending ->
            val sequence = pending.primary.batch.events.last().sequence
            pending.primary.completion.complete(
                TelemetryIngestResult(TelemetryIngestStatus.ACCEPTED, sequence, pending.primary.receivedAt),
            )
            pending.followers.forEach { follower ->
                follower.completion.complete(
                    TelemetryIngestResult(TelemetryIngestStatus.DUPLICATE, sequence, pending.primary.receivedAt),
                )
            }
        }
    }

    /**
     * 入场去重与合并：按收据键查已提交批次（冲突/重复直接完成 future），同批多命令按
     * payload hash 归并为一主多从（followers 与主命令共享终态）。
     */
    private fun coalesceIngestCommands(group: List<IngestCommand>): LinkedHashMap<String, PendingBatch> {
        val primaries = LinkedHashMap<String, PendingBatch>()
        group.forEach { command ->
            val key = telemetryReceiptKey(command.uid, command.deviceId, command.batch.batchId)
            val committed = withSearcher { searcher -> findTelemetryReceipt(searcher, key) }
            if (committed != null) {
                if (committed.payloadSha256 != command.batch.payloadSha256) {
                    command.completion.completeExceptionally(TelemetryBatchConflictException())
                } else {
                    command.completion.complete(
                        TelemetryIngestResult(
                            TelemetryIngestStatus.DUPLICATE,
                            committed.acceptedThroughSequence,
                            committed.receivedAt,
                        ),
                    )
                }
                return@forEach
            }
            val pending = primaries[key]
            if (pending == null) {
                primaries[key] = PendingBatch(command)
            } else if (pending.primary.batch.payloadSha256 != command.batch.payloadSha256) {
                command.completion.completeExceptionally(TelemetryBatchConflictException())
            } else {
                pending.followers += command
            }
        }
        return primaries
    }

    /** 事件键冲突裁决：批内重复、与既有事件或先前已认领批次冲突的整批拒绝，其余放行。 */
    private fun resolveIngestEventKeyConflicts(
        primaries: LinkedHashMap<String, PendingBatch>,
    ): LinkedHashMap<String, PendingBatch> {
        val eventKeys = primaries.values.flatMapTo(linkedSetOf()) { pending ->
            pending.primary.batch.events.map {
                telemetryEventKey(pending.primary.uid, pending.primary.deviceId, it.eventId)
            }
        }
        val existingEventKeys = withSearcher { searcher ->
            findExistingTelemetryEventKeys(searcher, eventKeys)
        }
        val acceptedPrimaries = LinkedHashMap<String, PendingBatch>()
        val claimedEventKeys = HashSet<String>()
        primaries.forEach { (receiptKey, pending) ->
            val command = pending.primary
            val batchEventKeys = command.batch.events.map {
                telemetryEventKey(command.uid, command.deviceId, it.eventId)
            }
            if (batchEventKeys.toSet().size != batchEventKeys.size ||
                batchEventKeys.any { it in existingEventKeys || it in claimedEventKeys }
            ) {
                val failure = TelemetryBatchConflictException()
                command.completion.completeExceptionally(failure)
                pending.followers.forEach { it.completion.completeExceptionally(failure) }
            } else {
                claimedEventKeys += batchEventKeys
                acceptedPrimaries[receiptKey] = pending
            }
        }
        return acceptedPrimaries
    }

    /** 一次准入批次的待写文档与候选记账量。 */
    private data class IngestDocuments(
        val eventDocuments: LinkedHashMap<String, TelemetryAccountedDocument>,
        val receiptDocuments: List<TelemetryAccountedDocument>,
        val candidateAccountedBytes: Long,
        val nextRecordId: Long,
    )

    private fun buildIngestDocuments(
        acceptedPrimaries: LinkedHashMap<String, PendingBatch>,
    ): IngestDocuments {
        val eventDocuments = LinkedHashMap<String, TelemetryAccountedDocument>()
        var candidateNextRecordId = nextRecordId
        acceptedPrimaries.values.forEach { pending ->
            val command = pending.primary
            command.batch.events.forEach { event ->
                val key = telemetryEventKey(command.uid, command.deviceId, event.eventId)
                eventDocuments[key] = telemetryEventDocument(
                    uid = command.uid,
                    deviceId = command.deviceId,
                    batch = command.batch,
                    event = event,
                    receivedAt = command.receivedAt,
                    key = key,
                    recordId = candidateNextRecordId++,
                )
            }
        }
        val receiptDocuments = acceptedPrimaries.map { (key, pending) ->
            telemetryReceiptDocument(pending.primary.batch, pending.primary.receivedAt, key)
        }
        val candidateAccountedBytes = eventDocuments.values.sumOf(TelemetryAccountedDocument::accountedBytes) +
            receiptDocuments.sumOf(TelemetryAccountedDocument::accountedBytes)
        return IngestDocuments(eventDocuments, receiptDocuments, candidateAccountedBytes, candidateNextRecordId)
    }

    /** 投影后的账面字节/文档数；容量不足且无回收余地时完成全部 future 并返回 null。 */
    private data class IngestCapacityProjection(val projectedBytes: Long, val projectedDocuments: Long)

    /** 容量准入三段回退：保留期回收重算 → 物理阻塞时重置可弃置七日索引 → 仍不足则整批失败关闭。 */
    private fun admitIngestCapacity(
        acceptedPrimaries: LinkedHashMap<String, PendingBatch>,
        candidateAccountedBytes: Long,
        candidateDocuments: Long,
    ): IngestCapacityProjection? {
        var baseBytes = accountedBytes
        var baseDocuments = documentCount
        var projectedBytes = baseBytes + candidateAccountedBytes
        var projectedDocuments = baseDocuments + candidateDocuments
        if (projectedBytes > maxAccountedBytes ||
            projectedDocuments > maxDocuments ||
            !hasPhysicalCapacity(candidateAccountedBytes) ||
            !hasDiskCapacity(candidateAccountedBytes)
        ) {
            val cutoff = acceptedPrimaries.values.maxOf { it.primary.receivedAt } - TelemetryStoragePolicy.RETENTION_MILLIS
            val expired = collectStats(LongPoint.newRangeQuery(FIELD_RECEIVED_AT, Long.MIN_VALUE, cutoff - 1L))
            if (expired.documents > 0L) {
                processRetention(cutoff)
                baseBytes = accountedBytes
                baseDocuments = documentCount
                projectedBytes = baseBytes + candidateAccountedBytes
                projectedDocuments = baseDocuments + candidateDocuments
            }
        }

        val candidateFitsEmpty = candidateAccountedBytes <= maxAccountedBytes && candidateDocuments <= maxDocuments
        val candidateFitsPhysicalEmpty = telemetryCandidateFitsPhysicalEmpty(
            candidateAccountedBytes,
            maxPhysicalBytes,
        )
        if (!hasPhysicalCapacity(candidateAccountedBytes) && candidateFitsEmpty && candidateFitsPhysicalEmpty) {
            logger.warn(
                "Client telemetry physical capacity remains blocked; " +
                    "clearing disposable seven-day events",
            )
            resetDisposableIndexForRetention()
            baseBytes = 0L
            baseDocuments = 0L
            projectedBytes = candidateAccountedBytes
            projectedDocuments = candidateDocuments
        }

        if (projectedBytes > maxAccountedBytes ||
            projectedDocuments > maxDocuments ||
            !hasPhysicalCapacity(candidateAccountedBytes) ||
            !hasDiskCapacity(candidateAccountedBytes)
        ) {
            val failure = TelemetryStoreCapacityException()
            acceptedPrimaries.values.forEach { pending ->
                pending.primary.completion.completeExceptionally(failure)
                pending.followers.forEach { it.completion.completeExceptionally(failure) }
            }
            return null
        }
        return IngestCapacityProjection(projectedBytes, projectedDocuments)
    }

    private fun processRetention(receivedBefore: Long): Boolean {
        val query = if (receivedBefore == Long.MIN_VALUE) {
            LongPoint.newExactQuery(FIELD_RECEIVED_AT, Long.MIN_VALUE)
        } else {
            LongPoint.newRangeQuery(FIELD_RECEIVED_AT, Long.MIN_VALUE, receivedBefore - 1L)
        }
        val expired = collectStats(query)
        if (expired.documents == 0L && !hasDeletedDocuments()) {
            markRetentionSuccess()
            return true
        }

        if (!hasPhysicalCapacity(0L) || !hasDiskCapacity(0L)) {
            logger.warn(
                "Client telemetry retention lacks deterministic merge headroom; " +
                    "clearing disposable seven-day events",
            )
            resetDisposableIndexForRetention()
            markRetentionSuccess()
            return true
        }

        try {
            val openedWriter = writer ?: throw TelemetrySearchUnavailableException()
            searchMutationGate.write {
                if (expired.documents > 0L) openedWriter.deleteDocuments(query)
                openedWriter.forceMergeDeletes(true)
                commitState(
                    openedWriter,
                    nextRecordId,
                    (accountedBytes - expired.accountedBytes).coerceAtLeast(0L),
                    (documentCount - expired.documents).coerceAtLeast(0L),
                )
                require(!hasDeletedDocuments()) {
                    "telemetry retention left physically readable deleted documents"
                }
                require(collectStats(query).documents == 0L) {
                    "telemetry retention left expired live documents"
                }
            }
        } catch (failure: TelemetrySearchUnavailableException) {
            throw failure
        } catch (failure: Exception) {
            logger.warn(
                "Client telemetry physical retention failed; clearing disposable seven-day events",
                failure,
            )
            try {
                resetDisposableIndexForRetention()
            } catch (resetFailure: Exception) {
                resetFailure.addSuppressed(failure)
                throw resetFailure
            }
        }
        markRetentionSuccess()
        return true
    }

    private fun resetDisposableIndexForRetention() {
        accepting.set(false)
        closeRuntime(rollback = true)?.let { failure ->
            throw TelemetrySearchUnavailableException(failure)
        }
        openRuntime(reset = true)
        check(!closed.get()) { "client telemetry event store closed during retention reset" }
        accepting.set(true)
    }

    private fun markRetentionSuccess() {
        retentionLastSuccessAt = clock()
        retentionBacklog.set(false)
    }

    private fun hasDeletedDocuments(): Boolean = withSearcher { searcher ->
        searcher.indexReader.hasDeletions()
    }

    private fun commitState(
        openedWriter: IndexWriter,
        committedNextRecordId: Long,
        committedBytes: Long,
        committedDocuments: Long,
    ) {
        check(searchMutationGate.isWriteLockedByCurrentThread) {
            "telemetry commit requires exclusive search generation ownership"
        }
        openedWriter.setLiveCommitData(
            telemetryCommitMetadata(committedNextRecordId, committedBytes, committedDocuments).entries,
        )
        openedWriter.commit()
        searchers?.maybeRefreshBlocking() ?: throw TelemetrySearchUnavailableException()
        if (currentPhysicalBytes() > maxPhysicalBytes) {
            throw TelemetrySearchUnavailableException(
                IllegalStateException("client telemetry index exceeded its physical capacity fence"),
            )
        }
        durableCommitCount++
        nextRecordId = committedNextRecordId
        accountedBytes = committedBytes
        documentCount = committedDocuments
    }

    private fun collectStats(query: Query): TelemetryStoredStats =
        withSearcher { searcher -> collectTelemetryStats(searcher, query) }

    private fun openRuntime(reset: Boolean) {
        indexDir.mkdirs()
        var openedAnalyzer: Analyzer? = null
        var openedDirectory: Directory? = null
        var openedWriter: IndexWriter? = null
        var openedSearchers: SearcherManager? = null
        try {
            openedDirectory = PhysicalQuotaDirectory(
                delegateDirectory = FSDirectory.open(indexDir.toPath()),
                indexRoot = indexDir,
                maxPhysicalBytes = maxPhysicalBytes,
            )
            openedAnalyzer = IKAnalyzer(true)
            if (reset) {
                val resetMetadata = telemetryCommitMetadata(1L, 0L, 0L)
                IndexWriter(
                    openedDirectory,
                    telemetryWriterConfig(openedAnalyzer, IndexWriterConfig.OpenMode.CREATE),
                ).use { resetWriter ->
                    resetWriter.setLiveCommitData(resetMetadata.entries)
                    resetWriter.commit()
                }
            }
            if (!DirectoryReader.indexExists(openedDirectory)) error("telemetry index is missing")
            val commit = DirectoryReader.listCommits(openedDirectory).maxByOrNull { it.generation }
                ?: error("telemetry commit is missing")
            val metadata = requireValidTelemetryCommit(commit.userData)
            validateCommittedTelemetryIndex(
                openedDirectory,
                metadata,
                maxDocuments,
                maxAccountedBytes,
                maxPhysicalBytes,
            )
            openedWriter = IndexWriter(
                openedDirectory,
                telemetryWriterConfig(openedAnalyzer, IndexWriterConfig.OpenMode.CREATE_OR_APPEND),
            )
            openedSearchers = SearcherManager(openedDirectory, SearcherFactory())
            analyzer = openedAnalyzer
            directory = openedDirectory
            writer = openedWriter
            searchers = openedSearchers
            nextRecordId = telemetryNextRecordId(metadata)
            accountedBytes = telemetryAccountedBytes(metadata)
            documentCount = telemetryDocumentCount(metadata)
        } catch (failure: Exception) {
            runCatching { openedSearchers?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { openedWriter?.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { openedDirectory?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { openedAnalyzer?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun closeRuntime(rollback: Boolean): Throwable? = searchMutationGate.write {
        val openedSearchers = searchers
        val openedWriter = writer
        val openedDirectory = directory
        val openedAnalyzer = analyzer
        searchers = null
        writer = null
        directory = null
        analyzer = null
        var closeFailure: Throwable? = null
        fun capture(failure: Throwable) {
            val previous = closeFailure
            if (previous == null) closeFailure = failure else previous.addSuppressed(failure)
        }
        runCatching { openedSearchers?.close() }.exceptionOrNull()?.let(::capture)
        if (rollback) {
            runCatching { openedWriter?.rollback() }.exceptionOrNull()?.let(::capture)
        } else {
            runCatching { openedWriter?.close() }.exceptionOrNull()?.let(::capture)
        }
        runCatching { openedDirectory?.close() }.exceptionOrNull()?.let(::capture)
        runCatching { openedAnalyzer?.close() }.exceptionOrNull()?.let(::capture)
        closeFailure
    }

    private fun <T> withSearcher(block: (org.apache.lucene.search.IndexSearcher) -> T): T =
        searchMutationGate.read {
            terminalFailure.get()?.let { throw it }
            val openedSearchers = searchers ?: throw TelemetrySearchUnavailableException()
            var searcher: org.apache.lucene.search.IndexSearcher? = null
            var primaryFailure: Throwable? = null
            try {
                openedSearchers.maybeRefreshBlocking()
                searcher = openedSearchers.acquire()
                block(searcher)
            } catch (cancelled: CancellationException) {
                primaryFailure = cancelled
                throw cancelled
            } catch (failure: Exception) {
                val unavailable = terminalizeCurrentGeneration(openedSearchers, failure)
                primaryFailure = unavailable
                throw unavailable
            } finally {
                searcher?.let { acquired ->
                    try {
                        openedSearchers.release(acquired)
                    } catch (releaseFailure: Exception) {
                        val unavailable = terminalizeCurrentGeneration(openedSearchers, releaseFailure)
                        val primary = primaryFailure
                        if (primary == null) throw unavailable
                        primary.addSuppressed(unavailable)
                    }
                }
            }
        }

    private fun terminalizeCurrentGeneration(
        openedSearchers: SearcherManager,
        failure: Exception,
    ): TelemetrySearchUnavailableException {
        val unavailable = if (failure is TelemetrySearchUnavailableException) {
            failure
        } else {
            TelemetrySearchUnavailableException(failure)
        }
        if (searchers !== openedSearchers || closed.get()) return unavailable
        return publishTerminalFailure(unavailable)
    }

    private fun publishTerminalFailure(failure: Exception): TelemetrySearchUnavailableException {
        val unavailable = if (failure is TelemetrySearchUnavailableException) {
            failure
        } else {
            TelemetrySearchUnavailableException(failure)
        }
        val terminal = checkNotNull(terminalFailure.updateAndGet { existing -> existing ?: unavailable })
        accepting.set(false)
        commands.close(terminal)
        retentionCommands.close(terminal)
        return terminal
    }

    private fun failQueuedCommands(failure: TelemetrySearchUnavailableException) {
        while (true) {
            val pending = commands.tryReceive().getOrNull() ?: break
            pending.fail(failure)
            pending.releaseReservation()
        }
        while (true) {
            val pending = retentionCommands.tryReceive().getOrNull() ?: break
            pending.fail(failure)
            pending.releaseReservation()
        }
    }

    private fun hasPhysicalCapacity(candidateAccountedBytes: Long): Boolean {
        return telemetryHasPhysicalCapacity(
            currentPhysicalBytes(),
            candidateAccountedBytes,
            maxPhysicalBytes,
        )
    }

    private fun hasDiskCapacity(candidateAccountedBytes: Long): Boolean {
        return telemetryHasDiskCapacity(indexDir, currentPhysicalBytes(), candidateAccountedBytes)
    }

    private fun currentPhysicalBytes(openedDirectory: Directory =
        directory ?: throw TelemetrySearchUnavailableException()
    ): Long = telemetryPhysicalBytes(openedDirectory)

    companion object {
        const val DEFAULT_MAX_QUEUED_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_MAX_DOCUMENTS = 2_000_000L
        const val DEFAULT_MAX_ACCOUNTED_BYTES = 8L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_PHYSICAL_BYTES = 16L * 1024L * 1024L * 1024L
        const val DEFAULT_GROUP_COMMIT_DELAY_MILLIS = 20L
        const val DEFAULT_MAX_GROUP_COMMANDS = 32
        private const val DEFAULT_QUEUE_COMMANDS = 128
    }
}
