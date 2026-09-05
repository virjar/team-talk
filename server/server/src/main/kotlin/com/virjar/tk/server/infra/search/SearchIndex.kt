package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageArchiveReader
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_CHAT_FILTERS
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_COLLECTION_WINDOW
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageSearch
import com.virjar.tk.server.domain.message.MessageSearchHit
import com.virjar.tk.server.domain.message.MessageSearchPage
import com.virjar.tk.server.domain.message.requireValidMessageSearchQuery
import com.virjar.tk.protocol.model.ConversationWirePolicy
import com.virjar.tk.server.runtime.RuntimeFailureCollector
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.LongPoint
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.*
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import org.slf4j.LoggerFactory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.File

internal enum class SearchIndexLifecycleStep {
    ANALYZER_ACQUIRED,
    DIRECTORY_ACQUIRED,
    WRITER_ACQUIRED,
    SEARCHERS_ACQUIRED,
    WRITER_COMMIT,
    CLOSE_SEARCHERS,
    CLOSE_WRITER,
    CLOSE_DIRECTORY,
    CLOSE_ANALYZER,
}

/**
 * 模块内部的动作边界，用于确定性的原生生命周期所有权。直接策略
 * 立即执行每个动作；替代策略可以在真实清理之后观察失败。
 */
internal class SearchIndexLifecycleActions(
    private val afterAcquireAction: (SearchIndexLifecycleStep) -> Unit = {},
    private val cleanupAction: (SearchIndexLifecycleStep, () -> Unit) -> Unit = { _, action -> action() },
) {
    fun afterAcquire(step: SearchIndexLifecycleStep) {
        afterAcquireAction(step)
    }

    fun cleanup(step: SearchIndexLifecycleStep, action: () -> Unit) {
        cleanupAction(step, action)
    }
}

/**
 * 基于 Lucene + IK 中文分词的消息全文搜索索引。
 */
class SearchIndex : MessageSearch {

    private val indexDir: File
    private val archive: MessageArchiveReader?
    private val lifecycleActions: SearchIndexLifecycleActions

    private val logger = LoggerFactory.getLogger(SearchIndex::class.java)

    @Volatile
    private var opened: OpenIndex? = null
    private var terminalLifecycleFailure: Throwable? = null
    @Volatile
    internal var startupAudit: SearchIndexStartupAudit = SearchIndexStartupAudit(
        SearchIndexStartupAction.NOT_AUDITED,
        authoritativeMessages = 0L,
        encodedBytes = 0L,
    )
        private set

    /** 仅供隔离的 Lucene 投影/生命周期测试使用的低级构造器。 */
    internal constructor(indexDir: File) : this(indexDir, null, SearchIndexLifecycleActions())

    /** 生产构造器：在归档对账之前，[start] 不能发布写入器。 */
    constructor(indexDir: File, archive: MessageArchiveReader) :
        this(indexDir, archive, SearchIndexLifecycleActions())

    internal constructor(
        indexDir: File,
        lifecycleActions: SearchIndexLifecycleActions,
    ) : this(indexDir, null, lifecycleActions)

    private constructor(
        indexDir: File,
        archive: MessageArchiveReader?,
        lifecycleActions: SearchIndexLifecycleActions,
    ) {
        this.indexDir = indexDir
        this.archive = archive
        this.lifecycleActions = lifecycleActions
    }

    val isRunning: Boolean get() = opened != null

    @Synchronized
    fun start() {
        terminalLifecycleFailure?.let { throw it }
        if (opened != null) return
        var runtimeIndexPath = indexDir.toPath()
        archive?.let { authoritativeArchive ->
            try {
                val reconciler = SearchIndexArchiveReconciler(
                    indexDir.toPath(),
                    authoritativeArchive,
                    logger,
                )
                startupAudit = reconciler.reconcile()
                runtimeIndexPath = reconciler.activePath
            } catch (failure: Throwable) {
                // 对账在抛出之前拥有并排空每个临时原生资源。
                // 把不确定的启动审计/切换视为本实例的终结。
                terminalLifecycleFailure = failure
                throw failure
            }
        }
        var openedAnalyzer: Analyzer? = null
        var openedDirectory: FSDirectory? = null
        var openedWriter: IndexWriter? = null
        var openedSearchers: SearcherManager? = null
        val ready = try {
            val newAnalyzer = IKAnalyzer(true)
            openedAnalyzer = newAnalyzer
            lifecycleActions.afterAcquire(SearchIndexLifecycleStep.ANALYZER_ACQUIRED)
            val newDirectory = FSDirectory.open(runtimeIndexPath)
            openedDirectory = newDirectory
            lifecycleActions.afterAcquire(SearchIndexLifecycleStep.DIRECTORY_ACQUIRED)
            val config = IndexWriterConfig(newAnalyzer).apply {
                openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
            }
            val newWriter = IndexWriter(newDirectory, config)
            openedWriter = newWriter
            lifecycleActions.afterAcquire(SearchIndexLifecycleStep.WRITER_ACQUIRED)
            // 管理器由目录支撑，因此精确修订读取只能观察到已提交的
            // 文档。由写入器支撑的 NRT reader 可能暴露一个提交刚失败的更新，
            // 并在重试时错误地将其确认为持久。
            newWriter.commit()
            val newSearchers = SearcherManager(newDirectory, SearcherFactory())
            openedSearchers = newSearchers
            lifecycleActions.afterAcquire(SearchIndexLifecycleStep.SEARCHERS_ACQUIRED)
            logger.info("Lucene search index opened at: {}", indexDir.absolutePath)
            OpenIndex(newAnalyzer, newDirectory, newWriter, newSearchers)
        } catch (error: Throwable) {
            val cleanupFailures = RuntimeFailureCollector()
            openedSearchers?.let { resource ->
                cleanupFailures.capture {
                    lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_SEARCHERS) { resource.close() }
                }
            }
            openedWriter?.let { resource ->
                cleanupFailures.capture {
                    lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_WRITER) { resource.close() }
                }
            }
            openedDirectory?.let { resource ->
                cleanupFailures.capture {
                    lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_DIRECTORY) { resource.close() }
                }
            }
            openedAnalyzer?.let { resource ->
                cleanupFailures.capture {
                    lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_ANALYZER) { resource.close() }
                }
            }
            val cleanupFailure = cleanupFailures.failureOrNull()
            val observedFailure = cleanupFailure?.let { mergeRuntimeFailure(error, it) } ?: error
            if (cleanupFailure != null) terminalLifecycleFailure = observedFailure
            throw observedFailure
        }

        // 只发布完整资源组；业务方法不必再推断多个可空字段是否属于同一次启动。
        opened = ready
    }

    @Synchronized
    fun stop() {
        terminalLifecycleFailure?.let { throw it }
        val resources = opened ?: return
        opened = null

        val failures = RuntimeFailureCollector()
        failures.capture {
            lifecycleActions.cleanup(SearchIndexLifecycleStep.WRITER_COMMIT) { resources.writer.commit() }
        }
        failures.capture {
            lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_SEARCHERS) { resources.searchers.close() }
        }
        failures.capture {
            lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_WRITER) { resources.writer.close() }
        }
        failures.capture {
            lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_DIRECTORY) { resources.directory.close() }
        }
        failures.capture {
            lifecycleActions.cleanup(SearchIndexLifecycleStep.CLOSE_ANALYZER) { resources.analyzer.close() }
        }
        failures.failureOrNull()?.let { failure ->
            terminalLifecycleFailure = failure
            throw failure
        }
        try {
            logger.info("Lucene search index closed")
        } catch (failure: Throwable) {
            terminalLifecycleFailure = failure
            throw failure
        }
    }

    @Synchronized
    fun commit() {
        val resources = opened ?: return
        resources.writer.commit()
        resources.searchers.maybeRefreshBlocking()
    }

    /**
     * 按不可变的服务器消息身份应用，绝不按调用方提供的客户端 id。
     * 空/已撤回的投影保持为携带版本的墓碑，使更旧的重放无法
     * 让它们先前的文本再次可被搜索。
     */
    @Synchronized
    override fun applyProjection(operation: MessageProjectionOperation, text: String?): Boolean {
        val resources = checkNotNull(opened) { "Search index is not running" }
        // 先前的更新可能已到达持久提交，而刷新却失败了。先刷新，
        // 使返回值始终描述已提交的索引，而不是过时的进程本地视图。
        resources.searchers.maybeRefreshBlocking()
        val durableRevision = durableRevision(resources.searchers, operation.projectionKey)
        if (operation.revision <= durableRevision) return false

        val message = operation.message
        val doc = buildSearchDocument(
            message = message,
            revision = operation.revision,
            text = text,
            forceTombstone = operation.operation == MessageOperationType.REVOKE,
        )

        resources.writer.updateDocument(Term(FIELD_MESSAGE_KEY, operation.projectionKey), doc)
        resources.writer.commit()
        // 把刚提交的文档发布给精确修订查找和普通搜索。
        // SearcherManager 只保留 Lucene reader 状态；它不按消息镜像条目。
        resources.searchers.maybeRefreshBlocking()
        return true
    }

    @Synchronized
    override fun search(
        query: String,
        chatIds: Set<String>,
        senderUid: String?,
        startTimestamp: Long?,
        endTimestamp: Long?,
        limit: Int,
        offset: Int,
    ): MessageSearchPage {
        require(limit > 0) { "Search result limit must be positive" }
        require(offset >= 0 && offset.toLong() + limit.toLong() <= MAX_MESSAGE_SEARCH_COLLECTION_WINDOW) {
            "Search offset and limit exceed the $MAX_MESSAGE_SEARCH_COLLECTION_WINDOW-hit collection window"
        }
        requireValidMessageSearchQuery(query)
        require(chatIds.size <= MAX_MESSAGE_SEARCH_CHAT_FILTERS) {
            "Search chat filter count exceeds $MAX_MESSAGE_SEARCH_CHAT_FILTERS"
        }
        require(chatIds.all(::isCanonicalSearchIdentity)) {
            "Search chat filter contains an invalid identity"
        }
        require(senderUid == null || isCanonicalSearchIdentity(senderUid)) {
            "Search sender filter contains an invalid identity"
        }
        require(startTimestamp == null || endTimestamp == null || startTimestamp <= endTimestamp) {
            "Search timestamp range is inverted"
        }
        val resources = opened ?: return MessageSearchPage(0, emptyList())
        resources.searchers.maybeRefreshBlocking()
        val searcher = resources.searchers.acquire()
        return try {
            val luceneQuery = buildQuery(resources.analyzer, query, chatIds, senderUid, startTimestamp, endTimestamp)

            val sort = Sort(SortField(FIELD_TIMESTAMP, SortField.Type.LONG, true))
            val topDocs: TopDocs = searcher.search(luceneQuery, Math.addExact(offset, limit), sort)

            val highlighter = Highlighter(
                SimpleHTMLFormatter("<em>", "</em>"),
                QueryScorer(luceneQuery)
            )
            val storedFields = searcher.storedFields()

            val results = topDocs.scoreDocs
                .drop(offset)
                .map { scoreDoc ->
                    val doc = storedFields.document(scoreDoc.doc)
                    val text = doc.get(FIELD_TEXT) ?: ""
                    val highlighted = try {
                        highlighter.getBestFragment(resources.analyzer, FIELD_TEXT, text) ?: text.take(200)
                    } catch (_: Exception) {
                        text.take(200)
                    }

                    MessageSearchHit(
                        clientMsgId = doc.get(FIELD_CLIENT_MESSAGE_ID) ?: "",
                        chatId = doc.get(FIELD_CHAT_ID) ?: "",
                        senderUid = doc.get(FIELD_SENDER_UID) ?: "",
                        messageType = doc.getField(FIELD_MESSAGE_TYPE)?.numericValue()?.toInt() ?: 0,
                        seq = doc.getField(FIELD_SEQUENCE)?.numericValue()?.toLong() ?: 0L,
                        timestamp = doc.getField(FIELD_TIMESTAMP)?.numericValue()?.toLong() ?: 0L,
                        highlight = highlighted,
                    )
                }

            MessageSearchPage(
                total = topDocs.totalHits.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                hits = results,
            )
        } finally {
            resources.searchers.release(searcher)
        }
    }

    private fun buildQuery(
        analyzer: Analyzer,
        q: String,
        chatIds: Set<String>,
        senderUid: String?,
        startTimestamp: Long?,
        endTimestamp: Long?,
    ): Query {
        val builder = BooleanQuery.Builder()

        // 墓碑携带最新版本，但绝不能出现在关键词或浏览结果中。
        builder.add(
            TermQuery(Term(FIELD_SEARCHABLE, SEARCHABLE_TRUE)),
            BooleanClause.Occur.FILTER,
        )

        // 空关键词/裸 "*" = 浏览模式（match-all）：经典 QueryParser 拒绝首字符通配，
        // 且 Highlighter 的 QueryScorer 对 MatchAll 无片段——管理员"空条件浏览最近消息"场景
        if (q.isBlank() || q.trim() == "*") {
            builder.add(org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        } else {
            // 产品搜索接受文本，不接受 Lucene 的字段/布尔查询语言。转义
            // 使标点保持字面量，并防止用户输入扩展成子句树。
            val textQuery = QueryParser(FIELD_TEXT, analyzer).parse(QueryParser.escape(q))
            builder.add(textQuery, BooleanClause.Occur.MUST)
        }

        if (chatIds.isNotEmpty()) {
            // 用户合法归属的聊天数可能远超 Lucene BooleanQuery 的子句
            // 上限。这是一个精确字段集合成员谓词，而不是每个聊天一个逻辑子句；
            // TermInSetQuery 使正确性与内存都独立于该上限。
            builder.add(
                TermInSetQuery(FIELD_CHAT_ID, chatIds.map { chatId -> BytesRef(chatId) }),
                BooleanClause.Occur.FILTER,
            )
        }

        if (senderUid != null) {
            builder.add(TermQuery(Term(FIELD_SENDER_UID, senderUid)), BooleanClause.Occur.MUST)
        }

        if (startTimestamp != null || endTimestamp != null) {
            val start = startTimestamp ?: 0L
            val end = endTimestamp ?: Long.MAX_VALUE
            builder.add(LongPoint.newRangeQuery(FIELD_TIMESTAMP, start, end), BooleanClause.Occur.MUST)
        }

        return builder.build()
    }

    /** 按其不可变投影身份读取一个持久版本；绝不扫描无关文档。 */
    private fun durableRevision(openedSearchers: SearcherManager, projectionKey: String): Long {
        val searcher = openedSearchers.acquire()
        return try {
            val documents = searcher.search(
                TermQuery(Term(FIELD_MESSAGE_KEY, projectionKey)),
                MAX_DOCUMENTS_PER_PROJECTION_KEY,
            )
            check(documents.totalHits.value <= 1L) {
                "Lucene projection '$projectionKey' has duplicate live documents"
            }
            val scoreDoc = documents.scoreDocs.singleOrNull() ?: return 0L
            val document = searcher.storedFields().document(scoreDoc.doc)
            checkNotNull(document.getField(FIELD_PROJECTION_REVISION)?.numericValue()) {
                "Lucene message document '$projectionKey' is missing its projection revision"
            }.toLong()
        } finally {
            openedSearchers.release(searcher)
        }
    }

    private fun isCanonicalSearchIdentity(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= ConversationWirePolicy.MAX_CHAT_ID_LENGTH &&
            value.none { character -> character.isISOControl() || character.isWhitespace() }

    /** 一次成功启动共同取得、共同退役的完整 Lucene 资源。 */
    private class OpenIndex(
        val analyzer: Analyzer,
        val directory: FSDirectory,
        val writer: IndexWriter,
        val searchers: SearcherManager,
    )

    private companion object {
        const val MAX_DOCUMENTS_PER_PROJECTION_KEY = 2
    }
}
