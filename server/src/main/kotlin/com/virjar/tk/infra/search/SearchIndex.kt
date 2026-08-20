package com.virjar.tk.infra.search

import com.virjar.tk.domain.message.MessageOperationType
import com.virjar.tk.domain.message.MessageProjectionOperation
import com.virjar.tk.domain.message.MessageSearch
import com.virjar.tk.domain.message.MessageSearchHit
import com.virjar.tk.domain.message.MessageSearchPage
import com.virjar.tk.model.Message
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.*
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import org.apache.lucene.store.FSDirectory
import org.slf4j.LoggerFactory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.File

/**
 * 基于 Lucene + IK 中文分词的消息全文搜索索引。
 */
class SearchIndex(private val indexDir: File) : MessageSearch {

    private val logger = LoggerFactory.getLogger(SearchIndex::class.java)

    private var directory: FSDirectory? = null
    private var analyzer: Analyzer? = null
    private var writer: IndexWriter? = null
    private val indexedRevisions = mutableMapOf<String, Long>()

    val isRunning: Boolean get() = writer != null

    @Synchronized
    fun start() {
        if (writer != null) return
        var openedAnalyzer: Analyzer? = null
        var openedDirectory: FSDirectory? = null
        var openedWriter: IndexWriter? = null
        try {
            val newAnalyzer = IKAnalyzer(true)
            openedAnalyzer = newAnalyzer
            val newDirectory = FSDirectory.open(indexDir.toPath())
            openedDirectory = newDirectory
            val config = IndexWriterConfig(newAnalyzer).apply {
                openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
            }
            val newWriter = IndexWriter(newDirectory, config)
            openedWriter = newWriter
            val durableRevisions = loadIndexedRevisions(newWriter)
            analyzer = newAnalyzer
            directory = newDirectory
            writer = newWriter
            indexedRevisions.clear()
            indexedRevisions.putAll(durableRevisions)
        } catch (error: Throwable) {
            runCatching { openedWriter?.close() }
            runCatching { openedDirectory?.close() }
            runCatching { openedAnalyzer?.close() }
            throw error
        }
        logger.info("Lucene search index opened at: {}", indexDir.absolutePath)
    }

    @Synchronized
    fun stop() {
        val openedWriter = writer
        val openedDirectory = directory
        val openedAnalyzer = analyzer
        writer = null
        directory = null
        analyzer = null
        indexedRevisions.clear()
        var failure: Throwable? = null
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        closePart { openedWriter?.commit() }
        closePart { openedWriter?.close() }
        closePart { openedDirectory?.close() }
        closePart { openedAnalyzer?.close() }
        logger.info("Lucene search index closed")
        failure?.let { throw it }
    }

    @Synchronized
    fun commit() {
        writer?.commit()
    }

    /**
     * Apply by the immutable server message identity, never by a caller-supplied client id.
     * Empty/revoked projections remain as revision-bearing tombstones so an older replay cannot
     * make their previous text searchable again.
     */
    @Synchronized
    override fun applyProjection(operation: MessageProjectionOperation, text: String?): Boolean {
        val w = checkNotNull(writer) { "Search index is not running" }
        val durableRevision = indexedRevisions[operation.projectionKey] ?: 0L
        if (operation.revision <= durableRevision) return false

        val message = operation.message
        val searchable = operation.operation != MessageOperationType.REVOKE &&
            message.flags and Message.FLAG_REVOKED == 0 &&
            !text.isNullOrBlank()

        val doc = Document().apply {
            add(StringField(FIELD_MESSAGE_KEY, operation.projectionKey, Field.Store.YES))
            add(StoredField(FIELD_PROJECTION_REVISION, operation.revision))
            add(
                StringField(
                    FIELD_SEARCHABLE,
                    if (searchable) SEARCHABLE_TRUE else SEARCHABLE_FALSE,
                    Field.Store.NO,
                ),
            )
            add(StringField(FIELD_CLIENT_MESSAGE_ID, message.clientMsgId, Field.Store.YES))
            add(StringField(FIELD_CHAT_ID, message.chatId, Field.Store.YES))
            add(LongPoint(FIELD_SEQUENCE, message.serverSeq))
            add(StoredField(FIELD_SEQUENCE, message.serverSeq))
            add(StringField(FIELD_SENDER_UID, message.senderUid, Field.Store.YES))
            if (searchable) add(TextField(FIELD_TEXT, text!!, Field.Store.YES))
            add(LongPoint(FIELD_TIMESTAMP, message.timestamp))
            add(StoredField(FIELD_TIMESTAMP, message.timestamp))
            add(NumericDocValuesField(FIELD_TIMESTAMP, message.timestamp))
            add(IntPoint(FIELD_MESSAGE_TYPE, message.messageType))
            add(StoredField(FIELD_MESSAGE_TYPE, message.messageType))
        }

        w.updateDocument(Term(FIELD_MESSAGE_KEY, operation.projectionKey), doc)
        w.commit()
        indexedRevisions[operation.projectionKey] = operation.revision
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
        val w = writer ?: return MessageSearchPage(0, emptyList())
        w.commit()

        val dir = directory ?: return MessageSearchPage(0, emptyList())
        val reader = DirectoryReader.open(dir)
        return reader.use { reader ->
            val searcher = IndexSearcher(reader)
            val luceneQuery = buildQuery(query, chatIds, senderUid, startTimestamp, endTimestamp)

            val sort = Sort(SortField(FIELD_TIMESTAMP, SortField.Type.LONG, true))
            val topDocs: TopDocs = searcher.search(luceneQuery, offset + limit, sort)

            val highlighter = Highlighter(
                SimpleHTMLFormatter("<em>", "</em>"),
                QueryScorer(luceneQuery)
            )

            val results = topDocs.scoreDocs
                .drop(offset)
                .map { scoreDoc ->
                    val doc = searcher.doc(scoreDoc.doc)
                    val text = doc.get(FIELD_TEXT) ?: ""
                    val highlighted = try {
                        highlighter.getBestFragment(analyzer, FIELD_TEXT, text) ?: text.take(200)
                    } catch (_: Exception) {
                        text.take(200)
                    }

                    MessageSearchHit(
                        clientMsgId = doc.get(FIELD_CLIENT_MESSAGE_ID) ?: "",
                        chatId = doc.get(FIELD_CHAT_ID) ?: "",
                        senderUid = doc.get(FIELD_SENDER_UID) ?: "",
                        messageType = doc.get(FIELD_MESSAGE_TYPE)?.toIntOrNull() ?: 0,
                        seq = doc.get(FIELD_SEQUENCE)?.toLongOrNull() ?: 0L,
                        timestamp = doc.get(FIELD_TIMESTAMP)?.toLongOrNull() ?: 0L,
                        highlight = highlighted,
                    )
                }

            MessageSearchPage(topDocs.totalHits.value.toInt(), results)
        }
    }

    private fun buildQuery(
        q: String,
        chatIds: Set<String>,
        senderUid: String?,
        startTimestamp: Long?,
        endTimestamp: Long?,
    ): Query {
        val analyzerInstance = analyzer!!
        val builder = BooleanQuery.Builder()

        // Tombstones carry the latest revision but must never appear in keyword or browse results.
        builder.add(
            TermQuery(Term(FIELD_SEARCHABLE, SEARCHABLE_TRUE)),
            BooleanClause.Occur.FILTER,
        )

        // 空关键词/裸 "*" = 浏览模式（match-all）：经典 QueryParser 拒绝首字符通配，
        // 且 Highlighter 的 QueryScorer 对 MatchAll 无片段——管理员"空条件浏览最近消息"场景
        if (q.isBlank() || q.trim() == "*") {
            builder.add(org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        } else {
            val textQuery = QueryParser(FIELD_TEXT, analyzerInstance).parse(q)
            builder.add(textQuery, BooleanClause.Occur.MUST)
        }

        if (chatIds.isNotEmpty()) {
            val channelBuilder = BooleanQuery.Builder()
            for (chatId in chatIds) {
                channelBuilder.add(TermQuery(Term(FIELD_CHAT_ID, chatId)), BooleanClause.Occur.SHOULD)
            }
            builder.add(channelBuilder.build(), BooleanClause.Occur.MUST)
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

    /** Rebuild the in-memory idempotency fence from committed live documents on every startup. */
    private fun loadIndexedRevisions(openedWriter: IndexWriter): Map<String, Long> =
        DirectoryReader.open(openedWriter).use { reader ->
            if (reader.numDocs() == 0) return@use emptyMap()
            val searcher = IndexSearcher(reader)
            buildMap {
                for (scoreDoc in searcher.search(MatchAllDocsQuery(), reader.numDocs()).scoreDocs) {
                    val document = searcher.doc(scoreDoc.doc)
                    val messageKey = checkNotNull(document.get(FIELD_MESSAGE_KEY)) {
                        "Lucene message document is missing its stable projection key"
                    }
                    val revision = checkNotNull(document.getField(FIELD_PROJECTION_REVISION)?.numericValue()) {
                        "Lucene message document '$messageKey' is missing its projection revision"
                    }.toLong()
                    val previous = get(messageKey)
                    if (previous == null || revision > previous) put(messageKey, revision)
                }
            }
        }

    private companion object {
        const val FIELD_MESSAGE_KEY = "messageKey"
        const val FIELD_PROJECTION_REVISION = "projectionRevision"
        const val FIELD_SEARCHABLE = "searchable"
        const val FIELD_CLIENT_MESSAGE_ID = "clientMsgId"
        const val FIELD_CHAT_ID = "chatId"
        const val FIELD_SEQUENCE = "seq"
        const val FIELD_SENDER_UID = "senderUid"
        const val FIELD_TEXT = "text"
        const val FIELD_TIMESTAMP = "timestamp"
        const val FIELD_MESSAGE_TYPE = "messageType"
        const val SEARCHABLE_TRUE = "1"
        const val SEARCHABLE_FALSE = "0"
    }
}
