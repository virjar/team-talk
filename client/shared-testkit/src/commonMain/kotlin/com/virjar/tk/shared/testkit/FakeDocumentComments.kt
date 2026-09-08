package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.shared.client.DocumentCommentPageKey
import com.virjar.tk.shared.client.LocalDocumentComments
import com.virjar.tk.shared.client.PendingDocumentComment
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeDocumentComments : LocalDocumentComments {
    override val changes = MutableStateFlow(0L)
    private var generation = 0L
    private val pages = linkedMapOf<DocumentCommentPageKey, DocumentCommentPage>()
    private val pending = linkedMapOf<String, PendingDocumentComment>()
    override fun page(key: DocumentCommentPageKey) = pages[key]
    override fun generation() = generation
    override fun applyPage(key: DocumentCommentPageKey, page: DocumentCommentPage, generation: Long): Boolean {
        if (generation != this.generation) return false
        pages[key] = page
        changes.value++
        return true
    }
    override fun invalidate(spaceId: String?, documentId: String?, purge: Boolean) {
        if (purge) pages.keys.removeAll { (spaceId == null || it.spaceId == spaceId) && (documentId == null || it.documentId == documentId) }
        generation++
        changes.value++
    }
    override fun pending() = pending.values.toList()
    override fun prepare(command: PendingDocumentComment): PendingDocumentComment {
        command.requireValid()
        pending[command.commentId]?.let { check(it == command); return it }
        check(pending.size < 256)
        pending[command.commentId] = command
        changes.value++
        return command
    }
    override fun fail(commentId: String, reason: String) {
        pending[commentId]?.let { pending[commentId] = it.copy(failure = reason) }
        changes.value++
    }
    override fun retry(commentId: String) {
        pending[commentId]?.let { pending[commentId] = it.copy(failure = null) }
        changes.value++
    }
    override fun discard(commentId: String) { pending.remove(commentId); changes.value++ }
    override fun acknowledge(command: PendingDocumentComment, comment: DocumentComment, generation: Long) {
        if (generation == this.generation) pages.entries.forEach { entry ->
            if (entry.key.spaceId == comment.spaceId && entry.key.documentId == comment.documentId) {
                val old = entry.value
                if (old.items.any { it.commentId == comment.commentId } || entry.key.beforeSequence == 0L) {
                    entry.setValue(old.copy(items = (old.items.filterNot { it.commentId == comment.commentId } + comment).sortedByDescending { it.sequence }))
                }
            }
        }
        discard(command.commentId)
    }
}
