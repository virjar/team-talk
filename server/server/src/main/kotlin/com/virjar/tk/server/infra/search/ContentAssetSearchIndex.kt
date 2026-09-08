package com.virjar.tk.server.infra.search

import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.server.domain.search.*
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** A rebuildable asset projection. PostgreSQL remains the owner of content, ACL and pending work. */
class ContentAssetSearchIndex(
    private val path: File,
    private val datasetId: String,
    private val source: ContentAssetRepository,
    private val afterCommitBeforeAck: (ContentAssetPending) -> Unit = {},
) : ContentAssetIndex, ContentAssetProjectionRecovery, AutoCloseable {
    @Volatile private var opened: Resources? = null
    private class Resources(val analyzer: IKAnalyzer, val directory: FSDirectory, val writer: IndexWriter, val searchers: SearcherManager)

    @Synchronized
    fun start() {
        if (opened != null) return
        require(!Files.isSymbolicLink(path.toPath())) { "Asset index path cannot be a symbolic link" }
        Files.createDirectories(path.toPath())
        val analyzer = IKAnalyzer(true)
        var directory: FSDirectory? = null
        var writer: IndexWriter? = null
        var searchers: SearcherManager? = null
        try {
            val dir = FSDirectory.open(path.toPath()).also { directory = it }
            val valid = compatibleCommit(dir)
            val activeWriter = IndexWriter(dir, IndexWriterConfig(analyzer).apply {
                openMode = if (valid) IndexWriterConfig.OpenMode.APPEND else IndexWriterConfig.OpenMode.CREATE
            }).also { writer = it }
            if (!valid) {
                // Search is unpublished during this scan. A failed build has no completed marker;
                // the next startup rebuilds again from bounded pages of current PostgreSQL values.
                for (kind in 1..2) {
                    var after: String? = null
                    while (true) {
                        val page = source.scan(kind, after, 8)
                        if (page.isEmpty()) break
                        page.forEach { activeWriter.addDocument(document(it)) }
                        after = page.last().key.id
                    }
                }
            }
            activeWriter.setLiveCommitData(mapOf("asset.schema" to SCHEMA, "asset.dataset" to datasetId).entries)
            activeWriter.commit()
            val manager = SearcherManager(dir, SearcherFactory()).also { searchers = it }
            opened = Resources(analyzer, dir, activeWriter, manager)
        } catch (failure: Throwable) {
            listOf<() -> Unit>({ searchers?.close() }, { writer?.rollback() }, { directory?.close() }, { analyzer.close() })
                .forEach { close -> try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
            throw failure
        }
    }

    private fun compatibleCommit(directory: FSDirectory): Boolean = try {
        DirectoryReader.open(directory).use { reader ->
            reader.indexCommit.userData["asset.schema"] == SCHEMA && reader.indexCommit.userData["asset.dataset"] == datasetId &&
                matchesAuthority(reader)
        }
    } catch (_: IndexNotFoundException) { false }
      catch (_: CorruptIndexException) { false }
      catch (_: IndexFormatTooOldException) { false }
      catch (_: IndexFormatTooNewException) { false }

    /** Compare bounded current PG pages with committed identities before reusing an index. */
    private fun matchesAuthority(reader: DirectoryReader): Boolean {
        val searcher = IndexSearcher(reader)
        val fields = searcher.storedFields()
        var expectedCount = 0
        for (kind in 1..2) {
            var after: String? = null
            while (true) {
                val page = source.scan(kind, after, 8)
                if (page.isEmpty()) break
                for (projection in page) {
                    val found = searcher.search(TermQuery(Term(KEY, key(projection.key))), 2)
                    if (found.totalHits.value != 1L) return false
                    val stored = fields.document(found.scoreDocs.single().doc)
                    if (stored.get(DIGEST) != fingerprint(projection) || stored.get(ID) != projection.key.id ||
                        stored.get(SCOPE) != projection.scopeId || stored.getField(REVISION)?.numericValue()?.toLong() != projection.revision ||
                        stored.getField(UPDATED)?.numericValue()?.toLong() != projection.updatedAt) return false
                    expectedCount++
                }
                after = page.last().key.id
            }
        }
        return reader.numDocs() == expectedCount
    }

    /** Startup owns publication and may drain the complete backlog in bounded batches. */
    fun recoverBeforeServing() {
        while (!drainPending(256)) { /* each batch commits and releases PostgreSQL resources */ }
    }

    override fun catchUp() {
        if (!drainPending(256)) throw ContentSearchUnavailableException()
    }

    @Synchronized
    private fun drainPending(maxBatches: Int): Boolean {
        val resources = opened ?: throw ContentSearchUnavailableException()
        repeat(maxBatches) {
            val pending = source.pending(8)
            if (pending.isEmpty()) return true
            resources.searchers.maybeRefreshBlocking()
            for (item in pending) {
                val projection = source.projection(item.key) ?: error("Pending search resource is missing")
                check(projection.revision >= item.revision) { "Search resource revision moved backwards" }
                if (projection.revision > durableRevision(resources, item.key)) {
                    resources.writer.updateDocument(Term(KEY, key(item.key)), document(projection))
                }
            }
            resources.writer.commit()
            resources.searchers.maybeRefreshBlocking()
            pending.forEach {
                afterCommitBeforeAck(it)
                // A newer committed mutation may have replaced this slot while Lucene was writing.
                source.acknowledge(it)
            }
        }
        return source.pending(1).isEmpty()
    }

    /** Used by recovery verification: a durable tombstone rejects older index operations. */
    @Synchronized
    internal fun applyProjection(value: ContentAssetProjection): Boolean {
        val resources = opened ?: throw ContentSearchUnavailableException()
        resources.searchers.maybeRefreshBlocking()
        if (value.revision <= durableRevision(resources, value.key)) return false
        resources.writer.updateDocument(Term(KEY, key(value.key)), document(value))
        resources.writer.commit()
        resources.searchers.maybeRefreshBlocking()
        return true
    }

    override fun search(request: ContentSearchRequest, scopes: Set<String>, after: ContentAssetAnchor?, limit: Int): List<ContentAssetMatch> {
        require(request.kind in 1..2 && limit in 1..100 && scopes.size <= 10_000)
        if (scopes.isEmpty()) return emptyList()
        val resources = opened ?: throw ContentSearchUnavailableException()
        // SearcherManager leases keep committed readers alive across refresh/close. Never take
        // the projection monitor while the caller holds a PG snapshot: the projector uses PG too.
        val searcher = try { resources.searchers.acquire() }
            catch (_: org.apache.lucene.store.AlreadyClosedException) { throw ContentSearchUnavailableException() }
        return try {
            val query = BooleanQuery.Builder()
                .add(TermQuery(Term(KIND, request.kind.toString())), BooleanClause.Occur.FILTER)
                .add(TermQuery(Term(ACTIVE, "1")), BooleanClause.Occur.FILTER)
                .add(TermInSetQuery(SCOPE, scopes.map(::BytesRef)), BooleanClause.Occur.FILTER)
            if (request.keyword.isNotEmpty() && (request.kind == 2 || request.keyword.isNotBlank())) {
                val textQuery = if (request.kind == 1) {
                    IKAnalyzer(true).use { analyzer ->
                        MultiFieldQueryParser(arrayOf(TITLE, TEXT), analyzer).parse(QueryParser.escape(request.keyword))
                    }
                } else {
                    WildcardQuery(Term(FILE_NAME, "*" + wildcardLiteral(request.keyword.lowercase(Locale.ROOT)) + "*"))
                }
                query.add(textQuery, BooleanClause.Occur.MUST)
            }
            if (request.fileType != 0) query.add(TermQuery(Term(FILE_TYPE, request.fileType.toString())), BooleanClause.Occur.FILTER)
            if (after != null) {
                val older = BooleanQuery.Builder()
                if (after.updatedAt > 0) older.add(LongPoint.newRangeQuery(UPDATED, 0, after.updatedAt - 1), BooleanClause.Occur.SHOULD)
                older.add(BooleanQuery.Builder()
                    .add(LongPoint.newExactQuery(UPDATED, after.updatedAt), BooleanClause.Occur.FILTER)
                    .add(TermRangeQuery.newStringRange(ID, after.id, null, false, false), BooleanClause.Occur.FILTER)
                    .build(), BooleanClause.Occur.SHOULD)
                query.add(older.build(), BooleanClause.Occur.FILTER)
            }
            val hits = searcher.search(query.build(), limit, Sort(SortField(UPDATED, SortField.Type.LONG, true), SortField(ID, SortField.Type.STRING)))
            val stored = searcher.storedFields()
            hits.scoreDocs.map { score ->
                val doc = stored.document(score.doc)
                ContentAssetMatch(ContentAssetKey(request.kind, doc.get(ID)), doc.get(SCOPE),
                    doc.getField(REVISION).numericValue().toLong(), doc.getField(UPDATED).numericValue().toLong())
            }
        } finally { resources.searchers.release(searcher) }
    }

    private fun durableRevision(resources: Resources, value: ContentAssetKey): Long {
        val searcher = resources.searchers.acquire()
        return try {
            val found = searcher.search(TermQuery(Term(KEY, key(value))), 2)
            check(found.totalHits.value <= 1) { "Duplicate asset index key" }
            found.scoreDocs.singleOrNull()?.let { searcher.storedFields().document(it.doc).getField(REVISION).numericValue().toLong() } ?: 0
        } finally { resources.searchers.release(searcher) }
    }

    private fun document(value: ContentAssetProjection): Document = Document().apply {
        require(value.revision > 0 && value.updatedAt >= 0)
        add(StringField(KEY, key(value.key), Field.Store.YES))
        add(StoredField(DIGEST, fingerprint(value)))
        add(StringField(KIND, value.key.kind.toString(), Field.Store.NO))
        add(StringField(ID, value.key.id, Field.Store.YES))
        add(SortedDocValuesField(ID, BytesRef(value.key.id)))
        add(StringField(SCOPE, value.scopeId, Field.Store.YES))
        add(StoredField(REVISION, value.revision))
        add(LongPoint(UPDATED, value.updatedAt))
        add(StoredField(UPDATED, value.updatedAt))
        add(NumericDocValuesField(UPDATED, value.updatedAt))
        add(StringField(ACTIVE, if (value.active) "1" else "0", Field.Store.NO))
        if (value.active) {
            add(TextField(TITLE, value.title, Field.Store.NO))
            if (value.key.kind == 1) add(TextField(TEXT, value.text, Field.Store.NO))
            else {
                add(StringField(FILE_NAME, value.title.lowercase(Locale.ROOT), Field.Store.NO))
                add(StringField(FILE_TYPE, fileType(value.mimeType).toString(), Field.Store.NO))
            }
        }
    }

    @Synchronized
    override fun close() {
        val resources = opened ?: return
        opened = null
        var failure: Throwable? = null
        listOf<() -> Unit>({ resources.searchers.close() }, { resources.writer.close() }, { resources.directory.close() }, { resources.analyzer.close() }).forEach { action ->
            try { action() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val SCHEMA = "1"
        private const val KEY = "key"
        private const val DIGEST = "projectionDigest"
        private const val KIND = "kind"
        private const val ID = "id"
        private const val SCOPE = "scope"
        private const val REVISION = "revision"
        private const val UPDATED = "updated"
        private const val ACTIVE = "active"
        private const val TITLE = "title"
        private const val TEXT = "text"
        private const val FILE_NAME = "fileName"
        private const val FILE_TYPE = "fileType"
        private fun fingerprint(value: ContentAssetProjection) = reliableCommandFingerprint(
            value.key.kind.toString(), value.key.id, value.scopeId, value.revision.toString(), value.updatedAt.toString(),
            value.active.toString(), value.title, value.text, value.mimeType,
        )
        private fun key(key: ContentAssetKey) = "${key.kind}:${key.id}"
        private fun wildcardLiteral(value: String) = buildString {
            value.forEach { if (it == '*' || it == '?' || it == '\\') append('\\'); append(it) }
        }
        private fun fileType(mime: String?): Int {
            val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when { type.startsWith("image/") -> 1; type.startsWith("video/") -> 2; type.startsWith("audio/") -> 3; else -> 4 }
        }
    }
}
