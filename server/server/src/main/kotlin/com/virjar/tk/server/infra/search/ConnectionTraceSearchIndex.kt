package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventDraft
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTracePage
import com.virjar.tk.server.domain.telemetry.ConnectionTraceQuery
import com.virjar.tk.server.domain.telemetry.ConnectionTraceStoragePolicy
import com.virjar.tk.server.domain.telemetry.ConnectionTraceStoreSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.LongPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 可丢弃的七天连接诊断。IM 线程只调用 [tryAppend]，它执行一次
 * 有界内存预留与一次非阻塞队列入队。每个 Lucene 变更都运行在唯一的
 * 私有写入线程上；容量或存储失败会丢弃诊断，而不影响 IM。
 */
class ConnectionTraceSearchIndex(
    private val indexDir: File,
    private val maxQueuedEvents: Int = DEFAULT_MAX_QUEUED_EVENTS,
    private val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
    private val maxEventBytes: Long = DEFAULT_MAX_EVENT_BYTES,
    private val maxDocuments: Long = DEFAULT_MAX_DOCUMENTS,
    private val maxAccountedBytes: Long = DEFAULT_MAX_ACCOUNTED_BYTES,
    private val maxPhysicalBytes: Long = DEFAULT_MAX_PHYSICAL_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectionTraceEventStore {
    private val logger = LoggerFactory.getLogger(ConnectionTraceSearchIndex::class.java)
    private val lifecycleLock = Any()
    private val searchMutationGate = ReentrantReadWriteLock(true)
    private val accepting = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val droppedEvents = AtomicLong(0L)
    private val queueBudget = TraceQueueBudget(maxQueuedBytes)
    private val queue = ArrayBlockingQueue<TraceCommand>(maxQueuedEvents)

    @Volatile
    private var directory: Directory? = null
    @Volatile
    private var analyzer: Analyzer? = null
    @Volatile
    private var writer: IndexWriter? = null
    @Volatile
    private var searchers: SearcherManager? = null
    @Volatile
    private var writerThread: Thread? = null
    @Volatile
    private var state = ConnectionTraceCommitState(1L, 0L, 0L)
    @Volatile
    private var lastRetentionSuccessAt: Long? = null
    @Volatile
    internal var appendHookForTest: (() -> Unit)? = null

    init {
        require(maxQueuedEvents > 0)
        require(maxQueuedBytes > 0L && maxEventBytes > 0L)
        require(maxDocuments > 0L && maxAccountedBytes > 0L && maxPhysicalBytes > 0L)
    }

    override fun start(): Boolean = synchronized(lifecycleLock) {
        check(!closed.get()) { "connection trace store is closed" }
        if (isAvailable()) return@synchronized true
        if (terminal.get()) return@synchronized false
        val opened = try {
            openRuntime(reset = false)
            true
        } catch (failure: Exception) {
            closeRuntime(rollback = true)?.let(failure::addSuppressed)
            logger.warn("Connection trace index is invalid or corrupt; resetting disposable diagnostics")
            runCatching { openRuntime(reset = true) }
                .onFailure { resetFailure ->
                    closeRuntime(rollback = true)?.let(resetFailure::addSuppressed)
                    logger.warn("Connection trace index reset failed")
                }
                .isSuccess
        }
        if (opened) {
            accepting.set(true)
            writerThread = Thread(::writerLoop, "connection-trace-writer").apply {
                isDaemon = true
                start()
            }
        }
        opened
    }

    override fun tryAppend(event: ConnectionTraceEventDraft): Boolean {
        if (!isAvailable()) {
            droppedEvents.incrementAndGet()
            return false
        }
        val estimatedBytes = estimateQueuedBytes(event)
        if (estimatedBytes > maxEventBytes || !queueBudget.reserve(estimatedBytes)) {
            droppedEvents.incrementAndGet()
            return false
        }
        val command = TraceCommand.Append(event, estimatedBytes)
        if (!queue.offer(command)) {
            queueBudget.release(estimatedBytes)
            droppedEvents.incrementAndGet()
            return false
        }
        return true
    }

    override fun query(query: ConnectionTraceQuery): ConnectionTracePage = searchMutationGate.read {
        if (!isAvailable()) throw TelemetrySearchUnavailableException()
        try {
            val manager = searchers ?: throw TelemetrySearchUnavailableException()
            manager.maybeRefreshBlocking()
            val searcher = manager.acquire()
            try {
                val luceneQuery = BooleanQuery.Builder().apply {
                    add(TermQuery(Term(TRACE_FIELD_UID, query.uid)), BooleanClause.Occur.FILTER)
                    add(TermQuery(Term(TRACE_FIELD_DEVICE_ID, query.deviceId)), BooleanClause.Occur.FILTER)
                    add(
                        TermQuery(Term(TRACE_FIELD_CORRELATION_ID, query.correlationId)),
                        BooleanClause.Occur.FILTER,
                    )
                    add(TermQuery(Term(TRACE_FIELD_TRACE_ID, query.traceId)), BooleanClause.Occur.FILTER)
                    add(TermQuery(Term(TRACE_FIELD_SESSION_ID, query.sessionId)), BooleanClause.Occur.FILTER)
                    add(
                        LongPoint.newExactQuery(TRACE_FIELD_GENERATION, query.connectionGeneration),
                        BooleanClause.Occur.FILTER,
                    )
                    add(
                        LongPoint.newExactQuery(TRACE_FIELD_POLICY_REVISION, query.policyRevision),
                        BooleanClause.Occur.FILTER,
                    )
                    add(
                        LongPoint.newRangeQuery(
                            TRACE_FIELD_OCCURRED_AT,
                            query.occurredAtFrom,
                            query.occurredAtUntil,
                        ),
                        BooleanClause.Occur.FILTER,
                    )
                }.build()
                val hits = searcher.search(
                    luceneQuery,
                    query.limit + 1,
                    Sort(
                        SortField(TRACE_FIELD_OCCURRED_AT, SortField.Type.LONG, false),
                        SortField(TRACE_FIELD_ID, SortField.Type.LONG, false),
                    ),
                ).scoreDocs
                val fields = searcher.storedFields()
                ConnectionTracePage(
                    events = hits.take(query.limit).map { fields.document(it.doc).toStoredConnectionTraceEvent() },
                    truncated = hits.size > query.limit,
                )
            } finally {
                manager.release(searcher)
            }
        } catch (failure: TelemetrySearchUnavailableException) {
            throw failure
        } catch (failure: Exception) {
            terminalize(failure)
            throw TelemetrySearchUnavailableException(failure)
        }
    }

    override fun deleteBefore(occurredBefore: Long): Boolean {
        require(occurredBefore >= 0L) { "connection trace retention time is invalid" }
        if (!isAvailable()) return false
        val completion = CompletableFuture<Boolean>()
        if (!queue.offer(TraceCommand.Retention(occurredBefore, completion))) return false
        return try {
            completion.get(RETENTION_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun snapshot(): ConnectionTraceStoreSnapshot = ConnectionTraceStoreSnapshot(
        available = isAvailable(),
        queuedEvents = queue.count { it is TraceCommand.Append },
        queuedBytes = queueBudget.current(),
        documentCount = state.documentCount,
        accountedBytes = state.accountedBytes,
        physicalBytes = runCatching { directory?.let(::telemetryPhysicalBytes) ?: 0L }.getOrDefault(0L),
        droppedEvents = droppedEvents.get(),
        lastRetentionSuccessAt = lastRetentionSuccessAt,
    )

    override fun isAvailable(): Boolean =
        accepting.get() && !closed.get() && !terminal.get() && writer != null && searchers != null

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            accepting.set(false)
        }
        val ownedThread = writerThread
        ownedThread?.interrupt()
        try {
            ownedThread?.join(CLOSE_WAIT_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while closing connection trace store", interrupted)
        }
        check(ownedThread?.isAlive != true) {
            "connection trace writer did not terminate before its storage close boundary"
        }
        drainQueue()
        val closeFailure = synchronized(lifecycleLock) { closeRuntime(rollback = terminal.get()) }
        closeFailure?.let {
            throw IllegalStateException("connection trace store did not close cleanly", it)
        }
    }

    private fun writerLoop() {
        try {
            while (!closed.get() || queue.isNotEmpty()) {
                val first = try {
                    queue.poll(GROUP_COMMIT_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    if (closed.get()) null else continue
                } ?: continue
                when (first) {
                    is TraceCommand.Append -> processAppendGroup(first)
                    is TraceCommand.Retention -> processRetentionCommand(first)
                }
            }
        } catch (failure: Exception) {
            terminalize(failure)
        } finally {
            drainQueue()
        }
    }

    private fun processAppendGroup(first: TraceCommand.Append) {
        val commands = ArrayList<TraceCommand.Append>(MAX_GROUP_EVENTS)
        commands += first
        while (commands.size < MAX_GROUP_EVENTS) {
            val next = queue.peek() as? TraceCommand.Append ?: break
            if (!queue.remove(next)) break
            commands += next
        }
        try {
            appendAccepted(commands)
        } catch (failure: Exception) {
            droppedEvents.addAndGet(commands.size.toLong())
            throw failure
        } finally {
            commands.forEach { queueBudget.release(it.reservedBytes) }
        }
    }

    private fun appendAccepted(commands: List<TraceCommand.Append>) {
        val documents = commands.mapIndexed { index, command ->
            connectionTraceDocument(state.nextId + index.toLong(), command.event)
        }
        val candidateBytes = documents.sumOf(ConnectionTraceDocument::accountedBytes)
        val candidateDocuments = documents.size.toLong()
        if (!fitsCapacity(candidateBytes, candidateDocuments)) {
            processRetention(clock() - ConnectionTraceStoragePolicy.RETENTION_MILLIS)
        }
        if (!fitsCapacity(candidateBytes, candidateDocuments)) {
            droppedEvents.addAndGet(candidateDocuments)
            return
        }
        val openedWriter = writer ?: throw IllegalStateException("connection trace writer is unavailable")
        searchMutationGate.write {
            appendHookForTest?.invoke()
            documents.forEach { openedWriter.addDocument(it.document) }
            commit(
                ConnectionTraceCommitState(
                    nextId = state.nextId + candidateDocuments,
                    documentCount = state.documentCount + candidateDocuments,
                    accountedBytes = state.accountedBytes + candidateBytes,
                ),
            )
        }
    }

    private fun processRetentionCommand(command: TraceCommand.Retention) {
        try {
            command.completion.complete(processRetention(command.occurredBefore))
        } catch (failure: Exception) {
            command.completion.complete(false)
            throw failure
        }
    }

    private fun processRetention(occurredBefore: Long): Boolean {
        val range = if (occurredBefore == Long.MIN_VALUE) {
            LongPoint.newExactQuery(TRACE_FIELD_OCCURRED_AT, Long.MIN_VALUE)
        } else {
            LongPoint.newRangeQuery(TRACE_FIELD_OCCURRED_AT, Long.MIN_VALUE, occurredBefore - 1L)
        }
        try {
            val openedWriter = writer ?: return false
            searchMutationGate.write {
                openedWriter.deleteDocuments(range)
                openedWriter.forceMergeDeletes(true)
                openedWriter.commit()
                searchers?.maybeRefreshBlocking()
                val stats = calculateLiveState(state.nextId)
                commit(stats)
            }
            lastRetentionSuccessAt = clock()
            return true
        } catch (_: Exception) {
            // 该目录只包含诊断。失败的物理清理重置更安全，
            // 而不是保留已删除段或让它威胁核心服务。
            searchMutationGate.write {
                closeRuntime(rollback = true)?.let {
                    throw IllegalStateException("connection trace reset close failed", it)
                }
                openRuntime(reset = true)
            }
            lastRetentionSuccessAt = clock()
            return true
        }
    }

    private fun calculateLiveState(nextId: Long): ConnectionTraceCommitState {
        val manager = searchers ?: throw IllegalStateException("connection trace searcher is unavailable")
        manager.maybeRefreshBlocking()
        val searcher = manager.acquire()
        return try {
            val fields = searcher.storedFields()
            var bytes = 0L
            for (leaf in searcher.indexReader.leaves()) {
                val liveDocs = leaf.reader().liveDocs
                for (docId in 0 until leaf.reader().maxDoc()) {
                    if (liveDocs == null || liveDocs.get(docId)) {
                        bytes = Math.addExact(
                            bytes,
                            requireNotNull(fields.document(leaf.docBase + docId)
                                .getField(TRACE_FIELD_ACCOUNTED_BYTES)?.numericValue()).toLong(),
                        )
                    }
                }
            }
            ConnectionTraceCommitState(nextId, searcher.indexReader.numDocs().toLong(), bytes)
        } finally {
            manager.release(searcher)
        }
    }

    private fun commit(next: ConnectionTraceCommitState) {
        val openedWriter = writer ?: throw IllegalStateException("connection trace writer is unavailable")
        openedWriter.setLiveCommitData(connectionTraceCommitMetadata(next).entries)
        openedWriter.commit()
        searchers?.maybeRefreshBlocking()
        require((directory?.let(::telemetryPhysicalBytes) ?: Long.MAX_VALUE) <= maxPhysicalBytes) {
            "connection trace physical capacity fence was exceeded"
        }
        state = next
    }

    private fun fitsCapacity(candidateBytes: Long, candidateDocuments: Long): Boolean {
        if (candidateBytes > maxAccountedBytes || candidateDocuments > maxDocuments) return false
        if (state.accountedBytes > maxAccountedBytes - candidateBytes) return false
        if (state.documentCount > maxDocuments - candidateDocuments) return false
        val openedDirectory = directory ?: return false
        val currentPhysical = telemetryPhysicalBytes(openedDirectory)
        return telemetryHasPhysicalCapacity(currentPhysical, candidateBytes, maxPhysicalBytes) &&
            telemetryHasDiskCapacity(indexDir, currentPhysical, candidateBytes)
    }

    private fun openRuntime(reset: Boolean) {
        indexDir.mkdirs()
        var openedAnalyzer: Analyzer? = null
        var openedDirectory: Directory? = null
        var openedWriter: IndexWriter? = null
        var openedSearchers: SearcherManager? = null
        try {
            openedDirectory = PhysicalQuotaDirectory(
                FSDirectory.open(indexDir.toPath()),
                indexDir,
                maxPhysicalBytes,
            )
            openedAnalyzer = StandardAnalyzer()
            if (reset) {
                openedDirectory.listAll().forEach(openedDirectory::deleteFile)
                IndexWriter(
                    openedDirectory,
                    telemetryWriterConfig(openedAnalyzer, IndexWriterConfig.OpenMode.CREATE),
                ).use { resetWriter ->
                    resetWriter.setLiveCommitData(
                        connectionTraceCommitMetadata(ConnectionTraceCommitState(1L, 0L, 0L)).entries,
                    )
                    resetWriter.commit()
                }
            }
            require(DirectoryReader.indexExists(openedDirectory)) { "connection trace index is missing" }
            val commit = DirectoryReader.listCommits(openedDirectory).maxByOrNull { it.generation }
                ?: error("connection trace commit is missing")
            val loadedState = requireConnectionTraceCommit(commit.userData, maxDocuments, maxAccountedBytes)
            validateConnectionTraceIndex(openedDirectory, loadedState, maxPhysicalBytes)
            openedWriter = IndexWriter(
                openedDirectory,
                telemetryWriterConfig(openedAnalyzer, IndexWriterConfig.OpenMode.CREATE_OR_APPEND),
            )
            openedSearchers = SearcherManager(openedDirectory, SearcherFactory())
            analyzer = openedAnalyzer
            directory = openedDirectory
            writer = openedWriter
            searchers = openedSearchers
            state = loadedState
        } catch (failure: Exception) {
            runCatching { openedSearchers?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { openedWriter?.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { openedDirectory?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { openedAnalyzer?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun closeRuntime(rollback: Boolean): Throwable? {
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
        return closeFailure
    }

    private fun terminalize(failure: Exception) {
        if (!terminal.compareAndSet(false, true)) return
        accepting.set(false)
        logger.warn("Connection trace diagnostics disabled after a storage failure", failure)
        drainQueue()
    }

    private fun drainQueue() {
        while (true) {
            when (val command = queue.poll() ?: break) {
                is TraceCommand.Append -> {
                    queueBudget.release(command.reservedBytes)
                    droppedEvents.incrementAndGet()
                }
                is TraceCommand.Retention -> command.completion.complete(false)
            }
        }
    }

    private fun estimateQueuedBytes(event: ConnectionTraceEventDraft): Long = 512L + listOfNotNull(
        event.uid,
        event.deviceId,
        event.correlationId,
        event.traceId,
        event.sessionId,
        event.detail,
    ).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

    private sealed interface TraceCommand {
        data class Append(val event: ConnectionTraceEventDraft, val reservedBytes: Long) : TraceCommand
        data class Retention(
            val occurredBefore: Long,
            val completion: CompletableFuture<Boolean>,
        ) : TraceCommand
    }

    private class TraceQueueBudget(private val maxBytes: Long) {
        private val lock = Any()
        private var bytes = 0L

        fun reserve(candidate: Long): Boolean = synchronized(lock) {
            if (candidate <= 0L || candidate > maxBytes || bytes > maxBytes - candidate) return false
            bytes += candidate
            true
        }

        fun release(released: Long) = synchronized(lock) {
            bytes = (bytes - released).coerceAtLeast(0L)
        }

        fun current(): Long = synchronized(lock) { bytes }
    }

    companion object {
        const val DEFAULT_MAX_QUEUED_EVENTS = 4_096
        const val DEFAULT_MAX_QUEUED_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_MAX_EVENT_BYTES = 16L * 1024L
        const val DEFAULT_MAX_DOCUMENTS = 1_000_000L
        const val DEFAULT_MAX_ACCOUNTED_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_PHYSICAL_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_GROUP_EVENTS = 32
        private const val GROUP_COMMIT_DELAY_MILLIS = 20L
        private const val RETENTION_WAIT_SECONDS = 30L
        private const val CLOSE_WAIT_MILLIS = 30_000L
    }
}
