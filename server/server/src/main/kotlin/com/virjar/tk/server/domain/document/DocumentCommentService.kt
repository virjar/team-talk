package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import java.util.UUID

/** 文档级讨论：可阅读的成员可评论，作者可编辑，作者或空间管理员可删除。 */
class DocumentCommentService(
    private val documents: DocumentRepository,
    private val comments: DocumentCommentRepository,
    unitOfWork: PgUnitOfWork,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val access = DocumentAccessControl(documents, unitOfWork)
    private val changes = DocumentChangePublisher(documents)

    suspend fun list(actorUid: String, spaceId: String, documentId: String, before: Long, limit: Int): DocumentCommentPage {
        require(before >= 0 && limit in 1..DocumentCommentPage.MAX_PAGE_SIZE) { "评论分页参数无效" }
        return access.readAuthorized(actorUid, spaceId, DocumentCapability.READ) { _, _ ->
            requireDocument(transaction, spaceId, documentId)
            val rows = comments.list(transaction, spaceId, documentId, before, limit + 1)
            DocumentCommentPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].sequence else 0L)
        }
    }

    suspend fun create(actorUid: String, spaceId: String, documentId: String, commentId: String, replyToId: String?, body: String): DocumentComment {
        requireUuid(commentId)
        replyToId?.let(::requireUuid)
        val text = validateBody(body)
        val fingerprint = reliableCommandFingerprint("document-comment", actorUid, spaceId, documentId, replyToId, text)
        return access.writeAuthorized(actorUid, spaceId, DocumentCapability.COMMENT) { _, _ ->
            requireDocument(transaction, spaceId, documentId)
            val existing = comments.find(transaction, commentId)
            if (existing != null) {
                if (existing.creationFingerprint != fingerprint) throw ReliableCommandConflictException("评论标识已用于其他内容")
                return@writeAuthorized existing.comment
            }
            replyToId?.let { parent ->
                requireComment(transaction, spaceId, documentId, parent)
            }
            require(comments.count(transaction, spaceId, documentId) < DocumentComment.MAX_COMMENTS_PER_DOCUMENT) { "本篇文档评论数量已达上限" }
            val now = clock()
            val comment = comments.insert(
                transaction,
                DocumentComment(commentId, spaceId, documentId, 0L, actorUid,
                    checkNotNull(documents.findUser(transaction, actorUid)).name,
                    replyToId, text, 1L, now, now, false),
                fingerprint,
            )
            changes.publishCommentsChanged(this, spaceId, documentId)
            comment
        }
    }

    suspend fun update(actorUid: String, spaceId: String, documentId: String, commentId: String, body: String, expectedRevision: Long): DocumentComment {
        val text = validateBody(body)
        require(expectedRevision in 1 until Long.MAX_VALUE) { "评论修订无效" }
        return access.writeAuthorized(actorUid, spaceId, DocumentCapability.COMMENT) { _, _ ->
            requireDocument(transaction, spaceId, documentId)
            val current = requireComment(transaction, spaceId, documentId, commentId)
            if (current.authorUid != actorUid) throw DocumentAccessDeniedException("只能编辑自己的评论")
            if (!current.deleted && current.revision == expectedRevision + 1 && current.body == text) return@writeAuthorized current
            if (current.deleted || current.revision != expectedRevision) throw DocumentRevisionConflictException("评论已变化，请查看最新内容后重试")
            val changed = comments.update(transaction, current.copy(body = text, revision = current.revision + 1, updatedAt = clock()))
            changes.publishCommentsChanged(this, spaceId, documentId)
            changed
        }
    }

    suspend fun delete(actorUid: String, spaceId: String, documentId: String, commentId: String, expectedRevision: Long): DocumentComment {
        require(expectedRevision in 1 until Long.MAX_VALUE) { "评论修订无效" }
        return access.writeAuthorized(actorUid, spaceId, DocumentCapability.COMMENT) { _, role ->
            requireDocument(transaction, spaceId, documentId)
            val current = requireComment(transaction, spaceId, documentId, commentId)
            if (current.authorUid != actorUid && role < DocumentSpace.ROLE_ADMIN) throw DocumentAccessDeniedException("只能删除自己的评论")
            if (current.deleted) return@writeAuthorized current
            if (current.revision != expectedRevision) throw DocumentRevisionConflictException("评论已变化，请查看最新内容后重试")
            val changed = comments.update(transaction, current.copy(body = "", deleted = true, revision = current.revision + 1, updatedAt = clock()))
            changes.publishCommentsChanged(this, spaceId, documentId)
            changed
        }
    }

    private fun requireDocument(transaction: PgReadTransactionContext, spaceId: String, documentId: String) {
        if (documents.findNode(transaction, spaceId, documentId) == null) throw DocumentNotFoundException("文档不存在")
    }

    private fun requireComment(transaction: PgReadTransactionContext, spaceId: String, documentId: String, commentId: String): DocumentComment =
        comments.find(transaction, commentId)?.comment?.takeIf { it.spaceId == spaceId && it.documentId == documentId }
            ?: throw DocumentNotFoundException("评论不存在")

    private fun validateBody(body: String): String {
        require(body.isNotBlank() && body.length <= DocumentComment.MAX_BODY_LENGTH && '\u0000' !in body) { "评论请输入 1 至 ${DocumentComment.MAX_BODY_LENGTH} 个字符" }
        return body
    }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "评论标识无效" }
    }
}
