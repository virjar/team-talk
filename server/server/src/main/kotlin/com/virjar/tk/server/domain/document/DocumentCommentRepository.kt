package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/** 调用方持有文档空间写锁；正文修订与评论修订互相独立。 */
interface DocumentCommentRepository {
    fun list(transaction: PgReadTransactionContext, spaceId: String, documentId: String, before: Long, limit: Int): List<DocumentComment>
    fun find(transaction: PgReadTransactionContext, commentId: String): DocumentCommentRecord?
    fun count(transaction: PgReadTransactionContext, spaceId: String, documentId: String): Long
    fun insert(transaction: PgWriteTransactionContext, comment: DocumentComment, creationFingerprint: String): DocumentComment
    fun update(transaction: PgWriteTransactionContext, comment: DocumentComment): DocumentComment
}

data class DocumentCommentRecord(val comment: DocumentComment, val creationFingerprint: String)
