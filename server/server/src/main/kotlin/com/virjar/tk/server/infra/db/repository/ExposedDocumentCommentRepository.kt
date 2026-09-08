package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.server.domain.document.DocumentCommentRecord
import com.virjar.tk.server.domain.document.DocumentCommentRepository
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentComments
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.server.infra.db.requireExposedTransaction
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedDocumentCommentRepository : DocumentCommentRepository {
    override fun list(transaction: PgReadTransactionContext, spaceId: String, documentId: String, before: Long, limit: Int): List<DocumentComment> {
        transaction.requireExposedReadTransaction()
        return DocumentComments.selectAll().where {
            (DocumentComments.spaceId eq spaceId) and (DocumentComments.documentId eq documentId) and
                (if (before > 0) DocumentComments.sequence less before else org.jetbrains.exposed.sql.Op.TRUE)
        }.orderBy(DocumentComments.sequence, SortOrder.DESC).limit(limit).map(::comment)
    }

    override fun find(transaction: PgReadTransactionContext, commentId: String): DocumentCommentRecord? {
        transaction.requireExposedReadTransaction()
        return DocumentComments.selectAll().where { DocumentComments.commentId eq commentId }.singleOrNull()?.let {
            DocumentCommentRecord(comment(it), it[DocumentComments.creationFingerprint])
        }
    }

    override fun count(transaction: PgReadTransactionContext, spaceId: String, documentId: String): Long {
        transaction.requireExposedReadTransaction()
        return DocumentComments.selectAll().where { (DocumentComments.spaceId eq spaceId) and (DocumentComments.documentId eq documentId) }.count()
    }

    override fun insert(transaction: PgWriteTransactionContext, comment: DocumentComment, creationFingerprint: String): DocumentComment {
        transaction.requireExposedTransaction()
        DocumentComments.insert {
            it[commentId] = comment.commentId
            it[spaceId] = comment.spaceId
            it[documentId] = comment.documentId
            it[authorUid] = comment.authorUid
            it[authorName] = comment.authorName
            it[replyToId] = comment.replyToId
            it[body] = comment.body
            it[revision] = comment.revision
            it[createdAt] = comment.createdAt
            it[updatedAt] = comment.updatedAt
            it[deleted] = comment.deleted
            it[DocumentComments.creationFingerprint] = creationFingerprint
        }
        return checkNotNull(find(transaction, comment.commentId)).comment
    }

    override fun update(transaction: PgWriteTransactionContext, comment: DocumentComment): DocumentComment {
        transaction.requireExposedTransaction()
        check(DocumentComments.update({ DocumentComments.commentId eq comment.commentId }) {
            it[body] = comment.body
            it[revision] = comment.revision
            it[updatedAt] = comment.updatedAt
            it[deleted] = comment.deleted
        } == 1)
        return comment
    }

    private fun comment(row: ResultRow) = with(DocumentComments) {
        DocumentComment(row[commentId], row[spaceId], row[documentId], row[sequence], row[authorUid], row[authorName],
            row[replyToId], row[body], row[revision], row[createdAt], row[updatedAt], row[deleted])
    }
}
