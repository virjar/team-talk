package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.shared.database.AppDatabaseQueries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class LocalDocumentCommentStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) : LocalDocumentComments {
    override val changes = MutableStateFlow(0L)
    private var currentGeneration = 0L

    private fun <T> locked(block: () -> T): T = cacheUseGate.use { synchronized(stateLock) { block() } }
    private fun changed() { changes.value += 1 }
    override fun generation(): Long = locked { currentGeneration }
    override fun page(key: DocumentCommentPageKey): DocumentCommentPage? = locked {
        queries.selectDocumentCommentPage(key.spaceId, key.documentId, key.beforeSequence).executeAsOneOrNull()?.let {
            ProtoCodec.decode(DocumentCommentPage, it)
        }
    }

    override fun applyPage(key: DocumentCommentPageKey, page: DocumentCommentPage, generation: Long): Boolean = cacheUseGate.runIfOpen { synchronized(stateLock) {
        require(page.items.all { it.spaceId == key.spaceId && it.documentId == key.documentId })
        if (generation != currentGeneration) return@synchronized false
        queries.transaction {
            queries.upsertDocumentCommentPage(key.spaceId, key.documentId, key.beforeSequence, ProtoCodec.encode(page), System.currentTimeMillis())
            queries.pruneDocumentCommentPages()
        }
        changed()
        true
    } }

    override fun invalidate(spaceId: String?, documentId: String?, purge: Boolean) = locked {
        if (purge) {
            when {
                spaceId == null -> queries.deleteAllDocumentCommentPages()
                documentId == null -> queries.deleteDocumentCommentSpace(spaceId)
                else -> queries.deleteDocumentCommentPages(spaceId, documentId)
            }
        }
        currentGeneration += 1
        changed()
    }

    override fun pending(): List<PendingDocumentComment> = locked {
        queries.selectPendingDocumentComments().executeAsList().map {
            Json.decodeFromString<PendingDocumentComment>(it).requireValid()
        }.also { check(it.size <= MAX_PENDING) { "本地评论队列超出上限，请保留资料并检查数据库" } }
    }

    override fun prepare(command: PendingDocumentComment): PendingDocumentComment = locked {
        command.requireValid()
        val pending = pending()
        pending.firstOrNull { it.commentId == command.commentId }?.let {
            check(it == command) { "此评论还有一项变更等待确认" }
            return@locked it
        }
        check(pending.size < MAX_PENDING) { "待发送评论数量已达上限" }
        queries.insertPendingDocumentComment(command.commentId, Json.encodeToString(command))
        changed()
        command
    }

    override fun fail(commentId: String, reason: String) = locked {
        val command = pending().firstOrNull { it.commentId == commentId } ?: return@locked
        queries.updatePendingDocumentComment(Json.encodeToString(command.copy(failure = reason.take(200))), commentId)
        changed()
    }

    override fun retry(commentId: String) = locked {
        val command = pending().firstOrNull { it.commentId == commentId } ?: return@locked
        queries.updatePendingDocumentComment(Json.encodeToString(command.copy(failure = null)), commentId)
        changed()
    }

    override fun discard(commentId: String) = locked {
        queries.deletePendingDocumentComment(commentId)
        changed()
    }

    override fun acknowledge(command: PendingDocumentComment, comment: DocumentComment, generation: Long) {
        cacheUseGate.runIfOpen { synchronized(stateLock) {
        require(comment.commentId == command.commentId && comment.spaceId == command.spaceId && comment.documentId == command.documentId)
        queries.transaction {
            if (generation == currentGeneration) {
                queries.selectDocumentCommentPages().executeAsList()
                    .filter { it.space_id == command.spaceId && it.document_id == command.documentId }
                    .forEach { row ->
                        val old = ProtoCodec.decode(DocumentCommentPage, row.payload)
                        val found = old.items.any { it.commentId == comment.commentId }
                        if (found || (row.before_sequence == 0L && command.kind == PendingDocumentComment.CREATE)) {
                            val replacement = (old.items.filterNot { it.commentId == comment.commentId } + comment)
                                .sortedByDescending { it.sequence }.take(DocumentCommentPage.MAX_PAGE_SIZE)
                            val next = if (replacement.size < old.items.size + (if (found) 0 else 1)) replacement.last().sequence else old.nextBeforeSequence
                            queries.upsertDocumentCommentPage(row.space_id, row.document_id, row.before_sequence,
                                ProtoCodec.encode(DocumentCommentPage(replacement, next)), System.currentTimeMillis())
                        }
                    }
            }
            queries.deletePendingDocumentComment(command.commentId)
        }
        changed()
        true
        } }
    }

    companion object { const val MAX_PENDING = 256 }
}
