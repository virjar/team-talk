package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.DocumentCommentPageKey
import com.virjar.tk.shared.client.PendingDocumentComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class DocumentCommentComposer(
    val body: String = "",
    val replyToId: String? = null,
    val editing: DocumentComment? = null,
)

/** 单个可见讨论分页；已提交意图属于 SDK，未提交输入在工作台切换时保留。 */
class DocumentCommentsFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val localData: UiLocalDataBoundary,
    private val reportError: (Throwable, String) -> Unit,
) {
    private val repository get() = session.documentCommentRepo
    private var target: DocumentCommentPageKey? = null
    private var pageHistory = emptyList<Long>()
    private val composers = mutableMapOf<Pair<String, String>, DocumentCommentComposer>()
    private var request: Job? = null
    private var observedGeneration = -1L
    private var observedPending = emptySet<String>()
    val ownerUid get() = session.ownerUid
    var items by mutableStateOf(emptyList<DocumentComment>())
        private set
    var pending by mutableStateOf(emptyList<PendingDocumentComment>())
        private set
    internal var composer by mutableStateOf(DocumentCommentComposer())
        private set
    var loading by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasEarlier by mutableStateOf(false)
        private set
    var hasNewer by mutableStateOf(false)
        private set
    private var nextBeforeSequence = 0L

    init {
        scope.launch {
            repository.local.changes.collect {
                val key = target ?: return@collect
                val snapshot = readLocal(key)
                if (target != key) return@collect
                val generationChanged = snapshot.generation != observedGeneration
                val completed = observedPending.any { previous -> snapshot.pending.none { it.commentId == previous } }
                publish(snapshot)
                if (generationChanged || completed) refresh()
            }
        }
    }

    fun open(spaceId: String, documentId: String) {
        val key = DocumentCommentPageKey(spaceId, documentId)
        if (target == key) return
        request?.cancel()
        target = key
        pageHistory = emptyList()
        items = emptyList()
        pending = emptyList()
        observedPending = emptySet()
        observedGeneration = -1L
        hasNewer = false
        hasEarlier = false
        composer = composers[spaceId to documentId] ?: DocumentCommentComposer()
        refresh()
    }

    fun close(spaceId: String, documentId: String) {
        if (target?.let { it.spaceId == spaceId && it.documentId == documentId } != true) return
        request?.cancel()
        target = null
        items = emptyList()
        pending = emptyList()
        loading = false
    }

    fun refresh() {
        val key = target ?: return
        request?.cancel()
        loading = true
        error = null
        request = scope.launch {
            try {
                val cached = readLocal(key)
                if (target != key) return@launch
                publish(cached)
                when (val result = localData.run { repository.refresh(key) }) {
                    is Outcome.Success -> if (target == key) publish(readLocal(key))
                    is Outcome.Failure -> if (target == key) {
                        publish(readLocal(key))
                        error = when (result.error) {
                            AppError.Network, AppError.Timeout -> "当前无法连接，显示已缓存的评论；待发送内容会自动重试"
                            else -> result.error.message
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (target == key) error = "读取评论失败，请重试"
                reportError(failure, "读取文档评论失败")
            } finally {
                if (target == key && request == currentCoroutineContext()[Job]) loading = false
            }
        }
    }

    fun earlier() {
        val current = target ?: return
        if (loading || nextBeforeSequence == 0L) return
        pageHistory = pageHistory + current.beforeSequence
        target = current.copy(beforeSequence = nextBeforeSequence)
        hasNewer = true
        items = emptyList()
        refresh()
    }

    fun newer() {
        val current = target ?: return
        val before = pageHistory.lastOrNull() ?: return
        pageHistory = pageHistory.dropLast(1)
        target = current.copy(beforeSequence = before)
        hasNewer = pageHistory.isNotEmpty()
        items = emptyList()
        refresh()
    }

    fun changeBody(value: String) {
        if (value.length <= DocumentComment.MAX_BODY_LENGTH) changeComposer(composer.copy(body = value))
    }

    fun reply(comment: DocumentComment) {
        if (belongsToTarget(comment)) changeComposer(composer.copy(replyToId = comment.commentId, editing = null))
    }

    fun edit(comment: DocumentComment) {
        if (!belongsToTarget(comment)) return
        if (composer.body.isNotBlank()) { error = "请先发送或清空当前输入"; return }
        changeComposer(DocumentCommentComposer(comment.body, comment.replyToId, comment))
    }

    fun clearComposer() = changeComposer(DocumentCommentComposer())

    fun submit() {
        val key = target ?: return
        val captured = composer
        if (submitting || captured.body.isBlank()) return
        submitting = true
        scope.launch {
            try {
                localData.run {
                    captured.editing?.let { repository.update(it, captured.body) }
                        ?: repository.create(key.spaceId, key.documentId, captured.body, captured.replyToId)
                }.getOrThrow()
                val identity = key.spaceId to key.documentId
                if (composers[identity] == captured) composers.remove(identity)
                if (target?.let { it.spaceId to it.documentId } == identity && composer == captured) {
                    composer = DocumentCommentComposer()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "评论尚未进入发送队列，输入已保留"
                reportError(failure, "保存待发送评论失败")
            } finally {
                submitting = false
            }
        }
    }

    fun delete(comment: DocumentComment) {
        if (!belongsToTarget(comment)) return
        runAction("删除评论失败") { repository.delete(comment).getOrThrow() }
    }

    fun retry(command: PendingDocumentComment) = runAction("重试评论失败") { repository.retry(command.commentId) }
    fun discard(command: PendingDocumentComment) = runAction("丢弃评论操作失败") { repository.discardRejected(command.commentId) }

    private fun runAction(message: String, block: suspend () -> Unit) {
        scope.launch {
            try { localData.run { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = message; reportError(failure, message) }
        }
    }

    private fun belongsToTarget(comment: DocumentComment) =
        target?.let { it.spaceId == comment.spaceId && it.documentId == comment.documentId } == true

    private fun changeComposer(value: DocumentCommentComposer) {
        val key = target?.let { it.spaceId to it.documentId } ?: return
        if (value != DocumentCommentComposer() && key !in composers && composers.size >= 32) {
            error = "未提交评论已达 32 篇，请先发送或清空已有输入"
            return
        }
        if (value == DocumentCommentComposer()) composers.remove(key) else composers[key] = value
        composer = value
    }

    private suspend fun readLocal(key: DocumentCommentPageKey) = localData.run {
        val before = repository.local.generation()
        val page = repository.local.page(key)
        val pending = repository.local.pending().filter { it.spaceId == key.spaceId && it.documentId == key.documentId }
        val after = repository.local.generation()
        CommentSnapshot(page.takeIf { before == after }, pending, after)
    }

    private fun publish(snapshot: CommentSnapshot) {
        items = snapshot.page?.items.orEmpty()
        pending = snapshot.pending
        observedPending = pending.map { it.commentId }.toSet()
        observedGeneration = snapshot.generation
        nextBeforeSequence = snapshot.page?.nextBeforeSequence ?: 0L
        hasEarlier = nextBeforeSequence != 0L
    }

    private data class CommentSnapshot(val page: DocumentCommentPage?, val pending: List<PendingDocumentComment>, val generation: Long)
}
