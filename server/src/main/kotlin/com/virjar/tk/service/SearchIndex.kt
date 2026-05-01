package com.virjar.tk.service

import com.virjar.tk.env.ThreadIOGuard
import com.virjar.tk.db.MessageStore
import com.virjar.tk.protocol.payload.Message
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TopDocs
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import org.apache.lucene.store.FSDirectory
import org.slf4j.LoggerFactory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.File

class SearchIndex(private val indexDir: File) {

    private val logger = LoggerFactory.getLogger(SearchIndex::class.java)

    private var directory: FSDirectory? = null
    private var analyzer: Analyzer? = null
    private var writer: IndexWriter? = null

    fun start() {
        analyzer = IKAnalyzer(true)
        directory = FSDirectory.open(indexDir.toPath())
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
        }
        writer = IndexWriter(directory, config)
        logger.info("Lucene search index opened at: {}", indexDir.absolutePath)
    }

    val isRunning: Boolean get() = writer != null

    fun stop() {
        try { writer?.commit() } catch (_: Exception) {}
        runCatching { writer?.close() }
        runCatching { directory?.close() }
        logger.info("Lucene search index closed")
    }

    fun indexMessage(message: Message) {
        ThreadIOGuard.check("Lucene")
        val text = PayloadTextExtractor.extract(message) ?: return
        val w = writer ?: return

        val doc = Document().apply {
            add(StringField("messageId", message.messageId ?: "", Field.Store.YES))
            add(StringField("channelId", message.channelId, Field.Store.YES))
            add(IntPoint("channelType", message.channelType.code))
            add(StoredField("channelType", message.channelType.code))
            add(StringField("senderUid", message.senderUid ?: "", Field.Store.YES))
            add(TextField("text", text, Field.Store.YES))
            add(LongPoint("timestamp", message.timestamp))
            add(StoredField("timestamp", message.timestamp))
            add(IntPoint("messageType", message.packetType.code.toInt()))
            add(StoredField("messageType", message.packetType.code.toInt()))
            add(LongPoint("seq", message.serverSeq))
            add(StoredField("seq", message.serverSeq))
        }

        w.updateDocument(Term("messageId", message.messageId ?: ""), doc)
        logger.debug("Indexed message {} in channel {}", message.messageId, message.channelId)
    }

    fun deleteMessage(messageId: String) {
        ThreadIOGuard.check("Lucene")
        val w = writer ?: return
        w.deleteDocuments(Term("messageId", messageId))
        logger.debug("Deleted message {} from index", messageId)
    }

    fun search(
        q: String,
        channelIds: Set<String>,
        senderUid: String? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): Pair<Int, List<SearchResult>> {
        ThreadIOGuard.check("Lucene")
        val w = writer ?: return Pair(0, emptyList())
        w.commit()

        val reader = DirectoryReader.open(directory!!)
        return try {
            val searcher = IndexSearcher(reader)
            val query = buildQuery(q, channelIds, senderUid, startTimestamp, endTimestamp)

            val sort = Sort(SortField("timestamp", SortField.Type.LONG, true))
            val topDocs: TopDocs = searcher.search(query, offset + limit, sort)

            val highlighter = Highlighter(
                SimpleHTMLFormatter("<em>", "</em>"),
                QueryScorer(query)
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
                        messageId = doc.get("messageId")!!,
                        channelId = doc.get("channelId")!!,
                        channelType = doc.get("channelType")?.toIntOrNull() ?: 0,
                        senderUid = doc.get("senderUid") ?: "",
                        messageType = doc.get("messageType")?.toIntOrNull() ?: 0,
                        seq = doc.get("seq")?.toLongOrNull() ?: 0L,
                        timestamp = doc.get("timestamp")?.toLongOrNull() ?: 0L,
                        highlight = highlighted,
                    )
                }

            Pair(topDocs.totalHits.value.toInt(), results)
        } finally {
            reader.close()
        }
    }

    fun rebuildIndex(messageStore: MessageStore) {
        ThreadIOGuard.check("Lucene")
        val w = writer ?: return
        logger.info("Starting full index rebuild from RocksDB...")
        w.deleteAll()

        var count = 0
        messageStore.iterateAll { message ->
            indexMessage(message)
            count++
        }

        w.commit()
        logger.info("Index rebuild complete: {} messages indexed", count)
    }

    private fun buildQuery(
        q: String,
        channelIds: Set<String>,
        senderUid: String?,
        startTimestamp: Long?,
        endTimestamp: Long?,
    ): org.apache.lucene.search.Query {
        val analyzerInstance = analyzer!!
        val builder = BooleanQuery.Builder()

        val textQuery = QueryParser("text", analyzerInstance).parse(q)
        builder.add(textQuery, BooleanClause.Occur.MUST)

        if (channelIds.isNotEmpty()) {
            val channelBuilder = BooleanQuery.Builder()
            for (channelId in channelIds) {
                channelBuilder.add(TermQuery(Term("channelId", channelId)), BooleanClause.Occur.SHOULD)
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

data class SearchResult(
    val messageId: String,
    val channelId: String,
    val channelType: Int,
    val senderUid: String,
    val messageType: Int,
    val seq: Long,
    val timestamp: Long,
    val highlight: String,
)
