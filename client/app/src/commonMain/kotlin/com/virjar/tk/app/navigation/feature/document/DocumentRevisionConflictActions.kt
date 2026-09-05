package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.LatestRequestGate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.protocol.model.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 仅在解析文档 revision 冲突之后使用的投影刷新。 */
internal class DocumentRevisionConflictPort(
    val refreshDocumentBranches: suspend (Document, Set<String?>) -> Unit,
)

/** 拥有单一的 revision 冲突决策槽位及其被围栏隔离的服务器版本加载。 */
internal class DocumentRevisionConflictActions(
    private val repository: DocumentRepositoryBoundary,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val state: DocumentWorkspaceMutationStatePort,
    private val port: DocumentRevisionConflictPort,
) {
    var revisionConflict by mutableStateOf<DocumentRevisionConflictState?>(null)
        private set
    private val conflictGate = LatestRequestGate<DocumentTabRequest>()

    /** 在 update CAS=409 之后启动一次被围栏隔离的重新加载，同时保持本地草稿不动。 */
    fun handleSaveConflict(request: DocumentTabRequest): Boolean {
        val currentConflict = revisionConflict
        if (currentConflict is DocumentRevisionConflictState.Adopting) {
            // 单一 modal 正在不可逆地退役 A 的本地身份。B 上并发的冲突
            // 仍然必须到达 B 的错误边界，而不是被 A 静默认领。
            return currentConflict.request == request
        }
        val documentId = request.documentId ?: return false
        // UI 只暴露一个冲突决策槽位。后台 tab A 的迟到 409 绝不能
        // 覆盖活动 tab B 的 modal 状态，也不能被报告为已处理而没有任何选择。
        if (state.activeTabId() != request.tabId) return false
        if (state.tabs().none(request::targetsUnchanged)) return true
        revisionConflict = DocumentRevisionConflictState.Loading(request)
        val token = conflictGate.begin(request)
        scope.launch {
            try {
                val remote = repository.call(
                    spaceId = request.spaceId,
                ) {
                    getDocument(request.spaceId, documentId).getOrThrow()
                }
                if (acceptConflict(token)) {
                    revisionConflict = DocumentRevisionConflictState.Ready(request, remote)
                } else {
                    clearAbandonedLoading(token)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (acceptConflict(token)) {
                    revisionConflict = DocumentRevisionConflictState.LoadFailed(
                        request,
                        failure.message ?: "读取服务器版本失败",
                    )
                } else {
                    clearAbandonedLoading(token)
                }
            }
        }
        return true
    }

    fun retryConflict() {
        if (revisionConflict is DocumentRevisionConflictState.Adopting) return
        val request = revisionConflict?.request ?: return
        handleSaveConflict(request)
    }

    fun readyConflict(): DocumentRevisionConflictState.Ready? =
        revisionConflict as? DocumentRevisionConflictState.Ready

    /** 在调用方启动墓碑屏障之前，冻结每一个竞争的冲突选择。 */
    fun beginAdoptingServerVersion(
        ready: DocumentRevisionConflictState.Ready,
    ): DocumentRevisionConflictState.Adopting? {
        if (revisionConflict != ready) return null
        conflictGate.invalidate()
        return DocumentRevisionConflictState.Adopting(ready).also { revisionConflict = it }
    }

    /** 调用方已经持久地退役了旧的恢复身份。 */
    fun completeAdoptingServerVersion(adopting: DocumentRevisionConflictState.Adopting): Boolean =
        resolveConflict(
            expectedState = adopting,
            ready = adopting.ready,
            // 调用方已经给被有意丢弃的本地身份立了墓碑。即使清单无法重写，
            // 干净的服务器投影也可以保留在内存中。
            requireDurableDraftProjection = false,
            merge = ::adoptDocumentConflictServerVersion,
        )

    /** 一次失败的墓碑让原始的本地选择和身份保持完全可用。 */
    fun cancelAdoptingServerVersion(adopting: DocumentRevisionConflictState.Adopting) {
        if (revisionConflict == adopting) revisionConflict = adopting.ready
    }

    fun keepDraftOnLatestVersion() {
        readyConflict()?.let { ready ->
            resolveConflict(
                expectedState = ready,
                ready = ready,
                requireDurableDraftProjection = true,
                merge = ::rebaseDocumentConflictKeepingDraft,
            )
        }
    }

    fun dismissStaleConflict() {
        if (revisionConflict is DocumentRevisionConflictState.Adopting) return
        val current = revisionConflict ?: return
        if (state.tabs().none(current.request::targetsUnchanged)) clearConflict()
    }

    fun clearConflict() {
        if (revisionConflict is DocumentRevisionConflictState.Adopting) return
        finishConflict()
    }

    private fun finishConflict() {
        conflictGate.invalidate()
        revisionConflict = null
    }

    private fun resolveConflict(
        expectedState: DocumentRevisionConflictState,
        ready: DocumentRevisionConflictState.Ready,
        requireDurableDraftProjection: Boolean,
        merge: (List<DocumentTabState>, DocumentRevisionConflictState.Ready) -> List<DocumentTabState>?,
    ): Boolean {
        if (revisionConflict != expectedState) return false
        val current = state.tabs().firstOrNull(ready.request::targets) ?: return false
        if (state.captureActiveDraft(current) == null) return false
        val oldParentId = state.tabs().firstOrNull(ready.request::targets)?.parentId
        val merged = merge(state.tabs(), ready) ?: run {
            finishConflict()
            return false
        }
        val resolved = merged.firstOrNull { it.instanceId == ready.request.instanceId }
            ?: run {
                finishConflict()
                return false
            }
        val persisted = state.persistTabs(merged)
        if (!persisted && requireDurableDraftProjection) return false
        state.replaceTabs(merged)
        resolved.takeIf { state.activeTabId() == it.tabId }?.let(state.updateActiveLocation)
        resolved.parentId?.let(state.expandParent)
        finishConflict()
        state.prepareDocumentBranches(ready.remote, setOf(oldParentId))
        scope.launch {
            try {
                if (state.selectedSpaceId() == ready.request.spaceId) {
                    port.refreshDocumentBranches(ready.remote, setOf(oldParentId))
                }
                state.refreshHome()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportError(failure, "刷新冲突恢复后的文档位置失败")
            }
        }
        return true
    }

    private fun acceptConflict(token: LatestRequestGate.Token<DocumentTabRequest>): Boolean =
        conflictGate.isCurrent(token) && revisionConflict?.request == token.target &&
            state.activeTabId() == token.target.tabId &&
            state.tabs().any(token.target::targetsUnchanged)

    private fun clearAbandonedLoading(token: LatestRequestGate.Token<DocumentTabRequest>) {
        val loading = revisionConflict as? DocumentRevisionConflictState.Loading ?: return
        if (conflictGate.isCurrent(token) && loading.request == token.target) finishConflict()
    }
}
