package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.DocumentCommentRpcProxy
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.DocumentCommentPageKey
import com.virjar.tk.shared.client.LocalDocumentComments
import com.virjar.tk.shared.client.PendingDocumentComment
import com.virjar.tk.shared.outcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** RPC 与后台恢复共用一个评论命令 owner；UI 观察持久分页和发送队列。 */
class DocumentCommentRepository(
    rpcClient: RpcInvoker,
    val local: LocalDocumentComments,
    private val onPendingCommitted: () -> Unit = {},
) {
    private val rpc = DocumentCommentRpcProxy(rpcClient)
    private val requests = Mutex()

    suspend fun refresh(key: DocumentCommentPageKey): Outcome<Boolean> = outcome {
        requests.withLock {
            val generation = local.generation()
            val response = try {
                rpc.list(key.spaceId, key.documentId, key.beforeSequence, DocumentCommentPage.DEFAULT_PAGE_SIZE)
            } catch (failure: Exception) {
                invalidateRejectedRead(key, failure)
                throw failure
            }
            local.applyPage(key, response, generation)
        }
    }

    suspend fun create(spaceId: String, documentId: String, body: String, replyToId: String? = null): Outcome<String> =
        enqueue(PendingDocumentComment(UUID.randomUUID().toString(), spaceId, documentId, PendingDocumentComment.CREATE, body, replyToId))

    suspend fun update(comment: DocumentComment, body: String): Outcome<String> = enqueue(
        PendingDocumentComment(comment.commentId, comment.spaceId, comment.documentId, PendingDocumentComment.UPDATE,
            body, expectedRevision = comment.revision),
    )

    suspend fun delete(comment: DocumentComment): Outcome<String> = enqueue(
        PendingDocumentComment(comment.commentId, comment.spaceId, comment.documentId, PendingDocumentComment.DELETE,
            expectedRevision = comment.revision),
    )

    private suspend fun enqueue(command: PendingDocumentComment): Outcome<String> = outcome {
        local.prepare(command)
        onPendingCommitted()
        command.commentId
    }

    fun retry(commentId: String) { local.retry(commentId); onPendingCommitted() }

    /** 只有确定拒绝的意图可丢弃；结果未知的在途评论保留原身份等待确认。 */
    suspend fun discardRejected(commentId: String) = requests.withLock {
        val command = local.pending().firstOrNull { it.commentId == commentId } ?: return@withLock
        check(command.failure != null) { "评论仍在等待服务端确认" }
        local.discard(commentId)
    }

    internal suspend fun retryPending(): Outcome<Unit> = retryPendingMirrors(local.pending().filter { it.failure == null }) { command ->
        outcome {
            requests.withLock {
                if (local.pending().none { it == command }) return@withLock
                val generation = local.generation()
                val response = try {
                    when (command.kind) {
                        PendingDocumentComment.CREATE -> rpc.create(command.spaceId, command.documentId, command.commentId, command.replyToId, command.body)
                        PendingDocumentComment.UPDATE -> rpc.update(command.spaceId, command.documentId, command.commentId, command.body, command.expectedRevision)
                        PendingDocumentComment.DELETE -> rpc.delete(command.spaceId, command.documentId, command.commentId, command.expectedRevision)
                        else -> error("Unknown document comment operation")
                    }
                } catch (failure: Exception) {
                    if (failureStatus(failure) == 403 || failure.isDefinitiveReliableCommandRejection()) {
                        val message = when (failureStatus(failure)) {
                            403 -> "评论操作未获授权，原内容保留在本机"
                            404 -> "文档或评论已不存在，原内容保留在本机"
                            409 -> "评论已变化，请丢弃旧操作后基于最新评论编辑"
                            else -> "评论未获服务端接受，请检查内容后重试"
                        }
                        local.fail(command.commentId, message)
                    }
                    throw failure
                }
                local.acknowledge(command, response, generation)
            }
        }
    }

    private fun invalidateRejectedRead(key: DocumentCommentPageKey, failure: Exception) {
        when (failureStatus(failure)) {
            403 -> local.invalidate(key.spaceId, purge = true)
            404 -> local.invalidate(key.spaceId, key.documentId, purge = true)
        }
    }

    private fun failureStatus(failure: Exception): Int? = when (failure) {
        is com.virjar.tk.protocol.rpc.RpcStatusException -> failure.status
        is AppError.Business -> failure.code
        else -> null
    }
}
