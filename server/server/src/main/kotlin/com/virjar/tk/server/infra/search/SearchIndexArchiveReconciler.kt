package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.message.DEFAULT_MESSAGE_ARCHIVE_PAGE_BYTES
import com.virjar.tk.server.domain.message.DEFAULT_MESSAGE_ARCHIVE_PAGE_SIZE
import com.virjar.tk.server.domain.message.MessageArchiveCursor
import com.virjar.tk.server.domain.message.MessageArchiveEntry
import com.virjar.tk.server.domain.message.MessageArchiveReader
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.runtime.RuntimeFailureCollector
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.PostingsEnum
import org.apache.lucene.index.ReaderUtil
import org.apache.lucene.index.Term
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.LongPoint
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import org.slf4j.Logger
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal enum class SearchIndexStartupAction {
    NOT_AUDITED,
    VERIFIED,
    REBUILT,
}

internal data class SearchIndexStartupAudit(
    val action: SearchIndexStartupAction,
    val authoritativeMessages: Long,
    val encodedBytes: Long,
)

/**
 * 权威 Rocks 消息与派生 Lucene 索引之间的仅启动对账。
 * 此拥有者运行期间不接受任何服务器写入器，因此一次稳定的游标扫描就是一个完整的
 * 快照。重建只在已提交侧目录通过第二次精确审计之后才会发布。
 */
internal class SearchIndexArchiveReconciler(
    indexDir: Path,
    private val archive: MessageArchiveReader,
    private val logger: Logger,
) {
    private val paths = ManagedSearchIndexPaths(indexDir)
    val activePath: Path get() = paths.active

    fun reconcile(): SearchIndexStartupAudit {
        paths.convergeInterruptedSwitch()
        val current = validate(paths.active, requireCompletionMarker = true)
        if (current is Validation.Valid) {
            logger.info(
                "Lucene startup consistency result=VERIFIED authoritativeMessages={} encodedBytes={}",
                current.stats.entries,
                current.stats.encodedBytes,
            )
            return SearchIndexStartupAudit(
                SearchIndexStartupAction.VERIFIED,
                current.stats.entries,
                current.stats.encodedBytes,
            )
        }

        val reason = (current as Validation.Invalid).reason
        logger.warn("Lucene startup consistency audit requires a bounded rebuild: {}", reason)
        val rebuilt = rebuildSideDirectory()
        paths.publishCompletedSideDirectory()
        logger.info(
            "Lucene startup consistency result=REBUILT authoritativeMessages={} encodedBytes={}",
            rebuilt.entries,
            rebuilt.encodedBytes,
        )
        return SearchIndexStartupAudit(
            SearchIndexStartupAction.REBUILT,
            rebuilt.entries,
            rebuilt.encodedBytes,
        )
    }

    private fun rebuildSideDirectory(): ArchiveScanStats {
        paths.prepareEmptySideDirectory()
        try {
            val built = writeSideDirectory()
            val validation = validate(paths.side, requireCompletionMarker = false)
            check(validation is Validation.Valid) {
                "New Lucene side directory failed its exact audit: ${(validation as Validation.Invalid).reason}"
            }
            check(validation.stats == built) {
                "New Lucene side directory audit changed the authoritative archive snapshot"
            }
            paths.markSideDirectoryComplete()
            return built
        } catch (failure: Throwable) {
            var observed = failure
            try {
                paths.deleteSideDirectory()
            } catch (cleanupFailure: Throwable) {
                observed = mergeRuntimeFailure(observed, cleanupFailure)
            }
            throw observed
        }
    }

    private fun writeSideDirectory(): ArchiveScanStats {
        var openedAnalyzer: Analyzer? = null
        var openedDirectory: FSDirectory? = null
        var openedWriter: IndexWriter? = null
        var result: ArchiveScanStats? = null
        var failure: Throwable? = null
        try {
            val analyzer = IKAnalyzer(true)
            openedAnalyzer = analyzer
            val directory = FSDirectory.open(paths.side)
            openedDirectory = directory
            val writer = IndexWriter(
                directory,
                IndexWriterConfig(analyzer).apply {
                    openMode = IndexWriterConfig.OpenMode.CREATE
                    setCommitOnClose(false)
                },
            )
            openedWriter = writer
            val generation = UUID.randomUUID().toString()
            result = scanArchive { entry ->
                writer.addDocument(
                    buildSearchDocument(
                        message = entry.message,
                        revision = entry.revision,
                        text = authoritativeSearchText(entry.message),
                    ),
                )
            }
            writer.setLiveCommitData(
                linkedMapOf(
                    SEARCH_COMMIT_SCHEMA_KEY to SEARCH_INDEX_SCHEMA_VERSION,
                    SEARCH_COMMIT_GENERATION_KEY to generation,
                ).entries,
            )
            // 刻意只有一次最终提交，绝不按消息或页面各提交一次。没有之后
            // 完成标记的侧目录绝无发布资格。
            writer.commit()
        } catch (error: Throwable) {
            failure = error
        }

        val cleanup = RuntimeFailureCollector()
        openedWriter?.let { writer -> cleanup.capture { writer.close() } }
        openedDirectory?.let { directory -> cleanup.capture { directory.close() } }
        openedAnalyzer?.let { analyzer -> cleanup.capture { analyzer.close() } }
        cleanup.failureOrNull()?.let { failure = mergeRuntimeFailure(failure, it) }
        failure?.let { throw it }
        return checkNotNull(result) { "Lucene side build produced no archive scan result" }
    }

    private fun validate(path: Path, requireCompletionMarker: Boolean): Validation {
        if (!paths.isManagedDirectory(path)) return Validation.Invalid("active-directory-missing")
        if (requireCompletionMarker && !paths.hasCompletionMarker(path)) {
            return Validation.Invalid("completion-marker-missing")
        }

        var openedDirectory: FSDirectory? = null
        var openedReader: DirectoryReader? = null
        var result: Validation? = null
        var originalFailure: Throwable? = null
        try {
            val directory = FSDirectory.open(path)
            openedDirectory = directory
            if (!DirectoryReader.indexExists(directory)) {
                result = Validation.Invalid("lucene-commit-missing")
            } else {
                val reader = DirectoryReader.open(directory)
                openedReader = reader
                result = inspectReader(reader)
            }
        } catch (failure: Throwable) {
            originalFailure = failure
        }

        val cleanup = RuntimeFailureCollector()
        openedReader?.let { reader -> cleanup.capture { reader.close() } }
        openedDirectory?.let { directory -> cleanup.capture { directory.close() } }
        cleanup.failureOrNull()?.let { cleanupFailure ->
            throw mergeRuntimeFailure(originalFailure, cleanupFailure)
        }
        originalFailure?.let { failure ->
            if (failure is IOException) return Validation.Invalid("lucene-directory-unreadable")
            throw failure
        }
        return checkNotNull(result) { "Lucene validation produced no result" }
    }

    private fun inspectReader(reader: DirectoryReader): Validation {
        val commitData = reader.indexCommit.userData
        if (commitData[SEARCH_COMMIT_SCHEMA_KEY] != SEARCH_INDEX_SCHEMA_VERSION) {
            return Validation.Invalid("schema-version-mismatch")
        }
        val generation = commitData[SEARCH_COMMIT_GENERATION_KEY]
        if (generation.isNullOrBlank() || generation.length > MAX_GENERATION_LENGTH) {
            return Validation.Invalid("generation-missing")
        }
        singleValuedExactTermMismatch(reader)?.let { return Validation.Invalid(it) }

        val searcher = IndexSearcher(reader)
        val mismatch = arrayOfNulls<String>(1)
        val stats = scanArchive { entry ->
            if (mismatch[0] == null) {
                mismatch[0] = try {
                    validateEntry(searcher, entry)
                } catch (failure: IOException) {
                    throw failure
                } catch (_: Exception) {
                    "lucene-document-unreadable"
                }
            }
        }
        mismatch[0]?.let { return Validation.Invalid(it) }
        if (reader.numDocs().toLong() != stats.entries) {
            return Validation.Invalid("live-document-count-mismatch")
        }
        return Validation.Valid(stats)
    }

    /**
     * 已存储的诊断无法揭示同一文档上额外的 `Store.NO` term。搜索
     * 授权与墓碑过滤使用这些精确字段，因此每个活跃文档必须
     * 在每个字段中恰好参与一个 term posting，期望值查询才算充分。
     */
    private fun singleValuedExactTermMismatch(reader: DirectoryReader): String? {
        for (field in SINGLE_VALUED_EXACT_TERM_FIELDS) {
            for (leaf in reader.leaves()) {
                val leafReader = leaf.reader()
                if (leafReader.numDocs() == 0) continue
                val terms = leafReader.terms(field) ?: return "indexed-term-missing:$field"
                val liveDocs = leafReader.liveDocs
                var livePostingCount = 0L
                val termsIterator = terms.iterator()
                while (termsIterator.next() != null) {
                    val postings = termsIterator.postings(null, PostingsEnum.NONE.toInt())
                    var documentId = postings.nextDoc()
                    while (documentId != DocIdSetIterator.NO_MORE_DOCS) {
                        if (liveDocs == null || liveDocs.get(documentId)) {
                            livePostingCount += 1L
                            if (livePostingCount > leafReader.numDocs().toLong()) {
                                return "indexed-term-multiple:$field"
                            }
                        }
                        documentId = postings.nextDoc()
                    }
                }
                // 随后的权威逐条目查询会识别出补偿性的
                // 多余+缺失对。这个聚合的第一遍只需要常量内存。
                if (livePostingCount != leafReader.numDocs().toLong()) {
                    return "indexed-term-missing:$field"
                }
            }
        }
        return null
    }

    private fun validateEntry(searcher: IndexSearcher, entry: MessageArchiveEntry): String? {
        val message = entry.message
        val projectionKey = MessageProjectionOperation.stableKey(message.chatId, message.serverSeq)
        val keyQuery = TermQuery(Term(FIELD_MESSAGE_KEY, projectionKey))
        val documents = searcher.search(keyQuery, MAX_DOCUMENTS_PER_PROJECTION_KEY)
        if (documents.totalHits.value != 1L) return "message-identity-mismatch"

        val documentId = documents.scoreDocs.single().doc
        val document = searcher.storedFields().document(documentId)
        val text = authoritativeSearchText(message)
        searchDocumentMismatch(document, message, entry.revision, text)?.let { return it }

        val searchable = message.flags and com.virjar.tk.protocol.model.Message.FLAG_REVOKED == 0 && !text.isNullOrBlank()
        // 比较过滤使用的所有精确/点字段，而不只是其已存储的诊断。
        // Lucene 原子地写一个文档，而全文的已存储源在上面已比较；
        // 这些检查合在一起覆盖每个当前字段与时间戳排序值。
        val indexedFieldsQuery = BooleanQuery.Builder()
            .add(keyQuery, BooleanClause.Occur.FILTER)
            .add(
                TermQuery(Term(FIELD_SEARCHABLE, if (searchable) SEARCHABLE_TRUE else SEARCHABLE_FALSE)),
                BooleanClause.Occur.FILTER,
            )
            .add(TermQuery(Term(FIELD_CLIENT_MESSAGE_ID, message.clientMsgId)), BooleanClause.Occur.FILTER)
            .add(TermQuery(Term(FIELD_CHAT_ID, message.chatId)), BooleanClause.Occur.FILTER)
            .add(TermQuery(Term(FIELD_SENDER_UID, message.senderUid)), BooleanClause.Occur.FILTER)
            .add(LongPoint.newExactQuery(FIELD_SEQUENCE, message.serverSeq), BooleanClause.Occur.FILTER)
            .add(LongPoint.newExactQuery(FIELD_TIMESTAMP, message.timestamp), BooleanClause.Occur.FILTER)
            .add(IntPoint.newExactQuery(FIELD_MESSAGE_TYPE, message.messageType), BooleanClause.Occur.FILTER)
            .build()
        if (searcher.count(indexedFieldsQuery) != 1) return "indexed-field-mismatch"

        val leaves = searcher.indexReader.leaves()
        val leafIndex = ReaderUtil.subIndex(documentId, leaves)
        val leaf = leaves[leafIndex]
        val timestampValues = DocValues.getNumeric(leaf.reader(), FIELD_TIMESTAMP)
        val localDocumentId = documentId - leaf.docBase
        if (!timestampValues.advanceExact(localDocumentId) || timestampValues.longValue() != message.timestamp) {
            return "timestamp-sort-value-mismatch"
        }
        return null
    }

    private fun scanArchive(consumer: (MessageArchiveEntry) -> Unit): ArchiveScanStats {
        var cursor: MessageArchiveCursor? = null
        var entries = 0L
        var encodedBytes = 0L
        while (true) {
            val page = archive.readArchivePage(
                after = cursor,
                limit = DEFAULT_MESSAGE_ARCHIVE_PAGE_SIZE,
                maxEncodedBytes = DEFAULT_MESSAGE_ARCHIVE_PAGE_BYTES,
            )
            check(page.entries.size <= DEFAULT_MESSAGE_ARCHIVE_PAGE_SIZE) {
                "Message archive reader exceeded its count budget"
            }
            check(page.entries.isEmpty() || page.encodedBytes > 0L) {
                "Non-empty message archive page reported no encoded bytes"
            }
            check(page.entries.size == 1 || page.encodedBytes <= DEFAULT_MESSAGE_ARCHIVE_PAGE_BYTES) {
                "Message archive reader exceeded its byte budget"
            }
            check(page.entries.zipWithNext().all { (left, right) -> left.cursor < right.cursor }) {
                "Message archive page did not preserve strict storage order"
            }
            cursor?.let { previous ->
                check(page.entries.firstOrNull()?.cursor?.let { it > previous } != false) {
                    "Message archive continuation did not advance"
                }
            }
            page.entries.forEach(consumer)
            entries = Math.addExact(entries, page.entries.size.toLong())
            encodedBytes = Math.addExact(encodedBytes, page.encodedBytes)

            val next = page.nextCursor ?: return ArchiveScanStats(entries, encodedBytes)
            val previous = cursor
            check(previous == null || next > previous) { "Message archive continuation did not advance" }
            cursor = next
        }
    }

    private sealed interface Validation {
        data class Valid(val stats: ArchiveScanStats) : Validation
        data class Invalid(val reason: String) : Validation
    }

    private data class ArchiveScanStats(
        val entries: Long,
        val encodedBytes: Long,
    )

    private companion object {
        const val MAX_GENERATION_LENGTH = 128
        const val MAX_DOCUMENTS_PER_PROJECTION_KEY = 2
        val SINGLE_VALUED_EXACT_TERM_FIELDS = arrayOf(
            FIELD_MESSAGE_KEY,
            FIELD_SEARCHABLE,
            FIELD_CLIENT_MESSAGE_ID,
            FIELD_CHAT_ID,
            FIELD_SENDER_UID,
        )
    }
}

/** 固定、仅兄弟路径与不跟随链接的清理，用于被中断重建的收敛。 */
private class ManagedSearchIndexPaths(configuredIndexDir: Path) {
    private val parent: Path
    val active: Path
    val side: Path
    private val backup: Path

    init {
        val configured = configuredIndexDir.toAbsolutePath().normalize()
        val configuredParent = requireNotNull(configured.parent) { "Lucene index directory requires a parent" }
        val fileName = requireNotNull(configured.fileName).toString()
            .takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Lucene index directory requires a fixed name")
        Files.createDirectories(configuredParent)
        parent = configuredParent.toRealPath()
        active = managedSibling(fileName)
        side = managedSibling("$fileName.teamtalk-rebuild-v2")
        backup = managedSibling("$fileName.teamtalk-backup-v2")
        check(setOf(active, side, backup).size == 3) { "Lucene managed paths must be distinct" }
    }

    fun convergeInterruptedSwitch() {
        rejectSymbolicLink(active)
        rejectSymbolicLink(side)
        rejectSymbolicLink(backup)

        // 环境会急切地创建配置的活跃目录。在
        // active -> backup 与 side -> active 之间崩溃后，该占位目录绝不能掩盖可恢复的
        // 产物。只有恰好为空的目录才被如此对待；非空的旧索引
        // 会保留，直到随后的权威审计决定是否重建它。
        if (
            isEmptyManagedDirectory(active) &&
            (Files.exists(side, LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(backup, LinkOption.NOFOLLOW_LINKS))
        ) {
            deleteManaged(active)
        }

        if (hasCompletionMarker(side)) {
            // 侧目录已提交，并在其标记被强制写入之前通过了权威审计。
            // 即使在第一次 active -> backup 重命名之前发生崩溃，也优先使用它。
            publishCompletedSideDirectory()
            return
        }

        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
            deleteManaged(side)
            deleteManaged(backup)
            return
        }

        deleteManaged(side)
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            atomicMove(backup, active)
        }
    }

    fun prepareEmptySideDirectory() {
        rejectSymbolicLink(side)
        deleteManaged(side)
        Files.createDirectory(side)
    }

    fun deleteSideDirectory() = deleteManaged(side)

    fun publishCompletedSideDirectory() {
        check(hasCompletionMarker(side)) { "Lucene side directory is not complete" }
        rejectSymbolicLink(active)
        rejectSymbolicLink(backup)

        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
            atomicMove(side, active)
            // active -> backup 之后的崩溃必须保留该回退，直到 side 发布获胜。
            deleteManaged(backup)
            return
        }

        deleteManaged(backup)
        atomicMove(active, backup)
        try {
            atomicMove(side, active)
        } catch (failure: Throwable) {
            var observed = failure
            try {
                if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS) &&
                    Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                ) {
                    atomicMove(backup, active)
                }
            } catch (rollbackFailure: Throwable) {
                observed = mergeRuntimeFailure(observed, rollbackFailure)
            }
            throw observed
        }
        deleteManaged(backup)
    }

    fun markSideDirectoryComplete() {
        check(isManagedDirectory(side)) { "Lucene side directory is missing" }
        val marker = marker(side)
        rejectSymbolicLink(marker)
        FileChannel.open(
            marker,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val content = COMPLETION_MARKER_CONTENT.encodeToByteArray()
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    fun hasCompletionMarker(directory: Path): Boolean {
        if (!isManagedDirectory(directory)) return false
        val marker = marker(directory)
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            val size = Files.size(marker)
            if (size != COMPLETION_MARKER_CONTENT.encodeToByteArray().size.toLong()) return false
            val bytes = ByteArray(size.toInt())
            FileChannel.open(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return false
                }
            }
            bytes.decodeToString(throwOnInvalidSequence = true) == COMPLETION_MARKER_CONTENT
        } catch (_: Exception) {
            false
        }
    }

    fun isManagedDirectory(path: Path): Boolean {
        assertManaged(path)
        return !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    }

    private fun marker(directory: Path): Path {
        assertManaged(directory)
        val result = directory.resolve(COMPLETION_MARKER_NAME).normalize()
        check(result.parent == directory) { "Lucene marker escaped its managed directory" }
        return result
    }

    private fun isEmptyManagedDirectory(path: Path): Boolean {
        if (!isManagedDirectory(path)) return false
        Files.newDirectoryStream(path).use { entries -> return !entries.iterator().hasNext() }
    }

    private fun managedSibling(name: String): Path {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Invalid Lucene managed directory name"
        }
        val path = parent.resolve(name).normalize()
        check(path.parent == parent) { "Lucene managed directory escaped its fixed parent" }
        return path
    }

    private fun atomicMove(source: Path, target: Path) {
        assertManaged(source)
        assertManaged(target)
        rejectSymbolicLink(source)
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Lucene atomic switch target already exists"
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deleteManaged(path: Path) {
        assertManaged(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymbolicLink(path)
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // walkFileTree 默认不跟随链接。嵌套符号链接会作为
                    // 一个目录条目被删除，绝不会把访问器移出受管根之外。
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun rejectSymbolicLink(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw IllegalStateException("Lucene managed path must not be a symbolic link")
        }
    }

    private fun assertManaged(path: Path) {
        check(path == active || path == side || path == backup) {
            "Refusing to mutate a path outside the fixed Lucene siblings"
        }
        check(path.parent == parent) { "Lucene managed path parent changed" }
    }

    private companion object {
        const val COMPLETION_MARKER_NAME = ".teamtalk-search-rebuild-complete"
        const val COMPLETION_MARKER_CONTENT = "teamtalk-search-index-v2\n"
    }
}
