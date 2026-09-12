package com.virjar.tk.server.infra.search

import java.io.File
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexCommit
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory

/**
 * Lucene 索引资源四件套（analyzer/directory/writer/searchers）装配完成后的唯一持有者；
 * [loaded] 携带引擎从最近 commit 装载的自身状态。
 */
internal class LuceneIndexRuntimeOpened<T>(
    val loaded: T,
    val analyzer: Analyzer,
    val directory: Directory,
    val writer: IndexWriter,
    val searchers: SearcherManager,
)

/**
 * 两个搜索引擎（客户端遥测/连接追踪）共享的 Lucene 资源生命周期。
 *
 * open 按"打开配额目录→analyzer→可选 reset 提交→校验最近 commit→CREATE_OR_APPEND 写者→
 * SearcherManager"的固定次序装配；任一步失败按与关闭相同的次序回滚已打开资源并附加
 * suppressed。close 固定按 searchers→writer（rollback 时回滚）→directory→analyzer 释放，
 * 首个失败返回、其余附加 suppressed——释放次序修复只需改这一处。
 */
internal fun <T> openLuceneIndexRuntime(
    indexDir: File,
    maxPhysicalBytes: Long,
    label: String,
    analyzerFactory: () -> Analyzer,
    reset: Boolean,
    resetBlock: (directory: Directory, analyzer: Analyzer) -> Unit,
    validateBlock: (directory: Directory, commit: IndexCommit) -> T,
): LuceneIndexRuntimeOpened<T> {
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
        openedAnalyzer = analyzerFactory()
        if (reset) {
            resetBlock(openedDirectory, openedAnalyzer)
        }
        if (!DirectoryReader.indexExists(openedDirectory)) error("$label index is missing")
        val commit = DirectoryReader.listCommits(openedDirectory).maxByOrNull { it.generation }
            ?: error("$label commit is missing")
        val loaded = validateBlock(openedDirectory, commit)
        openedWriter = IndexWriter(
            openedDirectory,
            telemetryWriterConfig(openedAnalyzer, IndexWriterConfig.OpenMode.CREATE_OR_APPEND),
        )
        openedSearchers = SearcherManager(openedDirectory, SearcherFactory())
        return LuceneIndexRuntimeOpened(
            loaded = loaded,
            analyzer = openedAnalyzer,
            directory = openedDirectory,
            writer = openedWriter,
            searchers = openedSearchers,
        )
    } catch (failure: Exception) {
        runCatching { openedSearchers?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { openedWriter?.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { openedDirectory?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { openedAnalyzer?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

internal fun closeLuceneIndexRuntime(
    opened: LuceneIndexRuntimeOpened<*>?,
    rollback: Boolean,
): Throwable? {
    if (opened == null) return null
    var closeFailure: Throwable? = null
    fun capture(failure: Throwable) {
        val previous = closeFailure
        if (previous == null) closeFailure = failure else previous.addSuppressed(failure)
    }
    runCatching { opened.searchers.close() }.exceptionOrNull()?.let(::capture)
    if (rollback) {
        runCatching { opened.writer.rollback() }.exceptionOrNull()?.let(::capture)
    } else {
        runCatching { opened.writer.close() }.exceptionOrNull()?.let(::capture)
    }
    runCatching { opened.directory.close() }.exceptionOrNull()?.let(::capture)
    runCatching { opened.analyzer.close() }.exceptionOrNull()?.let(::capture)
    return closeFailure
}
