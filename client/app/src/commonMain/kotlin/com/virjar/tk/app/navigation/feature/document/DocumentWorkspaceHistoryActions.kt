package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.LatestRequestGate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionPage
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 独立于工作区导航地拥有 revision 列表和预览请求的 generation。 */
internal class DocumentWorkspaceHistoryActions(
    private val repository: DocumentRepositoryBoundary,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val activeTab: () -> DocumentTabState?,
) {
    var revisions by mutableStateOf(emptyList<DocumentRevisionSummary>())
        private set
    var revisionPreview by mutableStateOf<DocumentRevision?>(null)
        private set
    var loadingRevisions by mutableStateOf(false)
        private set
    var loadingMoreRevisions by mutableStateOf(false)
        private set
    private var revisionNextBefore by mutableStateOf(DocumentRevisionPage.END_CURSOR)
    val hasMoreRevisions: Boolean get() = revisionNextBefore != DocumentRevisionPage.END_CURSOR

    private var target: DocumentRequestTarget? = null
    private val listGate = LatestRequestGate<DocumentRequestTarget>()
    private val previewGate = LatestRequestGate<DocumentRequestTarget>()

    fun show() {
        val requestTarget = activeTab()?.let { DocumentRequestTarget.from(it) } ?: return
        target = requestTarget
        revisions = emptyList()
        revisionNextBefore = DocumentRevisionPage.END_CURSOR
        revisionPreview = null
        loadingRevisions = true
        loadingMoreRevisions = false
        previewGate.invalidate()
        val token = listGate.begin(requestTarget)
        scope.launch {
            try {
                val loaded = repository.call(
                    spaceId = requestTarget.spaceId,
                ) {
                    listRevisions(
                        requestTarget.spaceId,
                        requestTarget.documentId,
                        beforeRevision = DocumentRevisionPage.FIRST_PAGE_CURSOR,
                        limit = DocumentRevisionPage.DEFAULT_PAGE_SIZE,
                    ).getOrThrow()
                }
                if (acceptList(token)) {
                    revisions = loaded.items.filter { it.documentId == requestTarget.documentId }
                    revisionNextBefore = loaded.nextBeforeRevision
                    revisionPreview = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (acceptList(token)) reportError(failure, "加载版本历史失败")
            } finally {
                if (acceptList(token)) loadingRevisions = false
            }
        }
    }

    fun loadMore() {
        val requestTarget = target ?: return
        val beforeRevision = revisionNextBefore
        if (beforeRevision == DocumentRevisionPage.END_CURSOR || loadingRevisions || loadingMoreRevisions) return
        if (!requestTarget.targets(activeTab())) return
        loadingMoreRevisions = true
        val token = listGate.begin(requestTarget)
        scope.launch {
            try {
                val loaded = repository.call(
                    spaceId = requestTarget.spaceId,
                ) {
                    listRevisions(
                        requestTarget.spaceId,
                        requestTarget.documentId,
                        beforeRevision = beforeRevision,
                        limit = DocumentRevisionPage.DEFAULT_PAGE_SIZE,
                    ).getOrThrow()
                }
                if (acceptList(token) && revisionNextBefore == beforeRevision) {
                    val known = revisions.mapTo(mutableSetOf()) { it.revision }
                    revisions = revisions + loaded.items.filter {
                        it.documentId == requestTarget.documentId && known.add(it.revision)
                    }
                    revisionNextBefore = loaded.nextBeforeRevision
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (acceptList(token)) reportError(failure, "加载更多版本失败")
            } finally {
                if (acceptList(token)) loadingMoreRevisions = false
            }
        }
    }

    fun open(summary: DocumentRevisionSummary) {
        val requestTarget = target ?: return
        if (!requestTarget.targets(activeTab()) || summary.documentId != requestTarget.documentId) return
        val token = previewGate.begin(requestTarget)
        scope.launch {
            try {
                val loaded = repository.call(
                    spaceId = requestTarget.spaceId,
                ) {
                    getRevision(
                        requestTarget.spaceId,
                        requestTarget.documentId,
                        summary.revision,
                    ).getOrThrow()
                }
                if (acceptPreview(token) && loaded.documentId == requestTarget.documentId &&
                    loaded.revision == summary.revision
                ) {
                    revisionPreview = loaded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (acceptPreview(token)) reportError(failure, "加载文档版本失败")
            }
        }
    }

    fun previewFor(requestTarget: DocumentRequestTarget): DocumentRevision? =
        revisionPreview?.takeIf { target == requestTarget && it.documentId == requestTarget.documentId }

    fun refreshIfShowing(requestTarget: DocumentRequestTarget) {
        if (target == requestTarget && requestTarget.targets(activeTab())) show()
    }

    fun close() {
        target = null
        listGate.invalidate()
        previewGate.invalidate()
        revisions = emptyList()
        revisionNextBefore = DocumentRevisionPage.END_CURSOR
        revisionPreview = null
        loadingRevisions = false
        loadingMoreRevisions = false
    }

    fun closePreview() {
        previewGate.invalidate()
        revisionPreview = null
    }

    private fun acceptList(token: LatestRequestGate.Token<DocumentRequestTarget>): Boolean =
        listGate.isCurrent(token) && target == token.target && token.target.targets(activeTab())

    private fun acceptPreview(token: LatestRequestGate.Token<DocumentRequestTarget>): Boolean =
        previewGate.isCurrent(token) && target == token.target && token.target.targets(activeTab())
}
