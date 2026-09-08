package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteScope

/** 仅首次创建才能发布事件；已提交命令重放只返回原创建确认。 */
data class DocumentSpaceCreation(val result: DocumentSpaceCreateResult, val created: Boolean)

/** 一次实际空间访问变更的提交结果；被撤权者不接收新的空间或文档信息。 */
data class DocumentSpaceAudienceChange(
    val spaceId: String,
    val policyRevision: Long,
    val readersBefore: Set<String>,
    val readersAfter: Set<String>,
)

/** 使用既有 PgUnitOfWork 用户事件流，不另建文档队列或复制正文。 */
class DocumentChangePublisher(private val repository: DocumentRepository) {
    fun readers(transaction: PgReadTransactionContext, spaceId: String): Set<String> =
        repository.listReadableUserIds(transaction, spaceId)

    fun publishNodeChange(
        scope: PgWriteScope,
        spaceId: String,
        nodeId: String,
        revision: Long,
        kind: Int = DocumentChangedPayload.NODE_UPSERT,
    ) {
        val space = repository.findSpace(scope.transaction, spaceId)
            ?: throw DocumentNotFoundException("文档空间不存在")
        val event = DocumentChangedPayload(spaceId, nodeId, kind, revision, space.policyRevision)
        readers(scope.transaction, spaceId).forEach { uid ->
            scope.appendEvent(uid, NotifyType.DOCUMENT_CHANGED, event)
        }
    }

    fun publishCommentsChanged(scope: PgWriteScope, spaceId: String, nodeId: String) =
        publishNodeChange(scope, spaceId, nodeId, 0L, DocumentChangedPayload.COMMENTS_CHANGED)

    fun publishSpaceChange(
        scope: PgWriteScope,
        spaceId: String,
        readersBefore: Set<String> = emptySet(),
        archivedPolicyRevision: Long? = null,
    ) {
        val current = repository.findSpace(scope.transaction, spaceId)
        val revision = current?.policyRevision ?: requireNotNull(archivedPolicyRevision)
        emitSpaceChange(
            scope,
            DocumentSpaceAudienceChange(
                spaceId, revision, readersBefore,
                if (current == null) emptySet() else readers(scope.transaction, spaceId),
            ),
        )
    }

    companion object {
        fun emitSpaceChange(scope: PgWriteScope, change: DocumentSpaceAudienceChange) {
            val changed = DocumentChangedPayload(
                change.spaceId, null, DocumentChangedPayload.SPACE_CHANGED, 0L, change.policyRevision,
            )
            val revoked = changed.copy(kind = DocumentChangedPayload.SPACE_REVOKED)
            (change.readersBefore + change.readersAfter).sorted().forEach { uid ->
                scope.appendEvent(
                    uid, NotifyType.DOCUMENT_CHANGED,
                    if (uid in change.readersAfter) changed else revoked,
                )
            }
        }
    }
}
