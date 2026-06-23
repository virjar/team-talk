package com.virjar.tk.infra.search

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
class SearchIndex(private val indexDir: File) {

    private val logger = LoggerFactory.getLogger(SearchIndex::class.java)

    private var directory: FSDirectory? = null
    private var analyzer: Analyzer? = null
    private var writer: IndexWriter? = null

    val isRunning: Boolean get() = writer != null

    fun start() {
        analyzer = IKAnalyzer(true)
        directory = FSDirectory.open(indexDir.toPath())
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
        }
        writer = IndexWriter(directory, config)
        logger.info("Lucene search index opened at: {}", indexDir.absolutePath)
    }

    fun stop() {
        try { writer?.commit() } catch (_: Exception) {}
        runCatching { writer?.close() }
        runCatching { directory?.close() }
        logger.info("Lucene search index closed")
    }

    fun commit() {
        writer?.commit()
    }

    /**
     * 索引一条消息。
     */
    fun indexMessage(message: Message, text: String?) {
        if (text.isNullOrBlank()) return
        val w = writer ?: return

        val doc = Document().apply {
            add(StringField("clientMsgId", message.clientMsgId, Field.Store.YES))
            add(StringField("chatId", message.chatId, Field.Store.YES))
            add(LongPoint("seq", message.serverSeq))
            add(StoredField("seq", message.serverSeq))
            add(StringField("senderUid", message.senderUid, Field.Store.YES))
            add(TextField("text", text, Field.Store.YES))
            add(LongPoint("timestamp", message.timestamp))
            add(StoredField("timestamp", message.timestamp))
            add(NumericDocValuesField("timestamp", message.timestamp))
            add(IntPoint("messageType", message.messageType))
            add(StoredField("messageType", message.messageType))
        }

        w.updateDocument(Term("clientMsgId", message.clientMsgId), doc)
    }

    fun deleteMessage(clientMsgId: String) {
        val w = writer ?: return
        w.deleteDocuments(Term("clientMsgId", clientMsgId))
    }

    data class SearchResult(
        val clientMsgId: String,
        val chatId: String,
        val senderUid: String,
        val messageType: Int,
        val seq: Long,
        val timestamp: Long,
        val highlight: String,
    )

    fun search(
        query: String,
        chatIds: Set<String>,
        senderUid: String? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): Pair<Int, List<SearchResult>> {
        val w = writer ?: return Pair(0, emptyList())
        w.commit()

        val dir = directory ?: return Pair(0, emptyList())
        val reader = DirectoryReader.open(dir)
        return reader.use { reader ->
            val searcher = IndexSearcher(reader)
            val luceneQuery = buildQuery(query, chatIds, senderUid, startTimestamp, endTimestamp)

            val sort = Sort(SortField("timestamp", SortField.Type.LONG, true))
            val topDocs: TopDocs = searcher.search(luceneQuery, offset + limit, sort)

            val highlighter = Highlighter(
                SimpleHTMLFormatter("<em>", "</em>"),
                QueryScorer(luceneQuery)
            )

            val results = topDocs.scoreDocs
                .drop(offset)
                .map { scoreDoc ->
                    val doc = searcher.doc(scoreDoc.doc)
                    val text = doc.get("text") ?: ""
                    val highlighted = try {
                        highlighter.getBestFragment(analyzer, "text", text) ?: text.take(200)
                    } catch (_: Exception) {
                        text.take(200)
                    }

                    SearchResult(
                        clientMsgId = doc.get("clientMsgId") ?: "",
                        chatId = doc.get("chatId") ?: "",
                        senderUid = doc.get("senderUid") ?: "",
                        messageType = doc.get("messageType")?.toIntOrNull() ?: 0,
                        seq = doc.get("seq")?.toLongOrNull() ?: 0L,
                        timestamp = doc.get("timestamp")?.toLongOrNull() ?: 0L,
                        highlight = highlighted,
                    )
                }

            Pair(topDocs.totalHits.value.toInt(), results)
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

        val textQuery = QueryParser("text", analyzerInstance).parse(q)
        builder.add(textQuery, BooleanClause.Occur.MUST)

        if (chatIds.isNotEmpty()) {
            val channelBuilder = BooleanQuery.Builder()
            for (chatId in chatIds) {
                channelBuilder.add(TermQuery(Term("chatId", chatId)), BooleanClause.Occur.SHOULD)
            }
            builder.add(channelBuilder.build(), BooleanClause.Occur.MUST)
        }

        if (senderUid != null) {
            builder.add(TermQuery(Term("senderUid", senderUid)), BooleanClause.Occur.MUST)
        }

        if (startTimestamp != null || endTimestamp != null) {
            val start = startTimestamp ?: 0L
            val end = endTimestamp ?: Long.MAX_VALUE
            builder.add(LongPoint.newRangeQuery("timestamp", start, end), BooleanClause.Occur.MUST)
        }

        return builder.build()
    }
}
