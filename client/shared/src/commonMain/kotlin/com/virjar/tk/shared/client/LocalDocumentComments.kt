package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

data class DocumentCommentPageKey(val spaceId: String, val documentId: String, val beforeSequence: Long = 0)

/** 待发正文属于账号可靠事实，服务器投影清理不删除这些意图。 */
@Serializable
data class PendingDocumentComment(
    val commentId: String,
    val spaceId: String,
    val documentId: String,
    val kind: Int,
    val body: String = "",
    val replyToId: String? = null,
    val expectedRevision: Long = 0,
    val failure: String? = null,
) {
    fun requireValid(): PendingDocumentComment {
        require(commentId.length == 36 && spaceId.isNotBlank() && documentId.isNotBlank())
        require(kind in CREATE..DELETE && body.length <= DocumentComment.MAX_BODY_LENGTH)
        require(if (kind == CREATE) expectedRevision == 0L else expectedRevision > 0L)
        require(kind == DELETE || body.isNotBlank())
        return this
    }
    companion object { const val CREATE = 1; const val UPDATE = 2; const val DELETE = 3 }
}

/** 有界分页缓存与评论发送队列，与 LocalCache 共用数据库和关闭边界。 */
interface LocalDocumentComments {
    val changes: StateFlow<Long>
    fun page(key: DocumentCommentPageKey): DocumentCommentPage?
    fun generation(): Long
    fun applyPage(key: DocumentCommentPageKey, page: DocumentCommentPage, generation: Long): Boolean
    fun invalidate(spaceId: String? = null, documentId: String? = null, purge: Boolean = false)
    fun pending(): List<PendingDocumentComment>
    fun prepare(command: PendingDocumentComment): PendingDocumentComment
    fun fail(commentId: String, reason: String)
    fun retry(commentId: String)
    fun discard(commentId: String)
    fun acknowledge(command: PendingDocumentComment, comment: DocumentComment, generation: Long)
}
