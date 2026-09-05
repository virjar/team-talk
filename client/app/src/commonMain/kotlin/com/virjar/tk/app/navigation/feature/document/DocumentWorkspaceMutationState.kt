package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentPolicy
import java.util.UUID

internal const val DOCUMENT_REVISION_CONFLICT_STATUS = 409
internal const val DOCUMENT_INITIAL_REVISION = 1L

/** 一次文档写请求捕获的标签身份与本地/服务端世代。 */
internal data class DocumentTabRequest(
    val tabId: String,
    val instanceId: Long,
    val documentId: String?,
    val spaceId: String,
    val revision: Long?,
    val editGeneration: Long,
) {
    fun targets(tab: DocumentTabState): Boolean =
        tab.tabId == tabId && tab.instanceId == instanceId &&
            tab.documentId == documentId && tab.spaceId == spaceId

    fun targetsUnchanged(tab: DocumentTabState): Boolean = targets(tab) &&
        tab.revision == revision && tab.editGeneration == editGeneration

    companion object {
        fun capture(tab: DocumentTabState, editGeneration: Long = tab.editGeneration) = DocumentTabRequest(
            tabId = tab.tabId,
            instanceId = tab.instanceId,
            documentId = tab.documentId,
            spaceId = tab.spaceId,
            revision = tab.revision,
            editGeneration = editGeneration,
        )
    }
}

internal sealed interface DocumentRevisionConflictState {
    val request: DocumentTabRequest

    data class Loading(override val request: DocumentTabRequest) : DocumentRevisionConflictState
    data class Ready(
        override val request: DocumentTabRequest,
        val remote: Document,
    ) : DocumentRevisionConflictState
    /** 等待其持久墓碑的、不可逆的本地丢弃选择。 */
    data class Adopting(
        val ready: Ready,
    ) : DocumentRevisionConflictState {
        override val request: DocumentTabRequest get() = ready.request
    }
    data class LoadFailed(
        override val request: DocumentTabRequest,
        val message: String,
    ) : DocumentRevisionConflictState
}

/** 删除请求在 launch 前捕获；之后切换活动标签不能偷换 RPC 目标。 */
internal data class DocumentDeleteRequest(
    val instanceId: Long,
    val documentId: String,
    val spaceId: String,
    val parentId: String?,
    val revision: Long,
) {
    companion object {
        fun capture(tab: DocumentTabState): DocumentDeleteRequest? {
            if (tab.remoteMissing) return null
            val documentId = tab.documentId ?: return null
            val revision = tab.revision ?: return null
            return DocumentDeleteRequest(
                instanceId = tab.instanceId,
                documentId = documentId,
                spaceId = tab.spaceId,
                parentId = tab.parentId,
                revision = revision,
            )
        }
    }
}

/** 在协程派发之前同步捕获的一条 move 命令。 */
internal data class DocumentMoveRequest(
    val instanceId: Long,
    val documentId: String,
    val spaceId: String,
    val revision: Long,
    val editGeneration: Long,
    val oldParentId: String?,
    val targetParentId: String?,
    val title: String,
    /** 编辑器在持久准入之后改变标题时，仅用于恢复的所有权位。 */
    val preserveDraftTitle: Boolean = false,
) {
    fun targets(tab: DocumentTabState): Boolean =
        tab.instanceId == instanceId && tab.documentId == documentId &&
            tab.spaceId == spaceId && tab.revision == revision && tab.parentId == oldParentId

    companion object {
        fun capture(
            tab: DocumentTabState,
            targetParentId: String?,
        ): DocumentMoveRequest? {
            val documentId = tab.documentId ?: return null
            val revision = tab.revision ?: return null
            if (tab.creating || tab.dirty || !tab.pathResolved || targetParentId == tab.parentId) return null
            return DocumentMoveRequest(
                instanceId = tab.instanceId,
                documentId = documentId,
                spaceId = tab.spaceId,
                revision = revision,
                editGeneration = tab.editGeneration,
                oldParentId = tab.parentId,
                targetParentId = targetParentId,
                title = DocumentPolicy.normalizeNodeName(tab.draftTitle),
            )
        }

        fun captureRename(tab: DocumentTabState, name: String): DocumentMoveRequest? {
            val documentId = tab.documentId ?: return null
            val revision = tab.revision ?: return null
            val canonicalName = DocumentPolicy.normalizeNodeName(name)
            if (tab.creating || tab.remoteMissing || !tab.pathResolved ||
                canonicalName == tab.savedTitle
            ) return null
            return DocumentMoveRequest(
                instanceId = tab.instanceId,
                documentId = documentId,
                spaceId = tab.spaceId,
                revision = revision,
                editGeneration = tab.editGeneration,
                oldParentId = tab.parentId,
                targetParentId = tab.parentId,
                title = canonicalName,
            )
        }
    }
}

/** 必须先于内容 CAS 的结构性重命名的确切结果。 */
internal sealed interface DocumentRenameBeforeSaveResult {
    /** 持久 move 命令存在，恢复机制拥有它的最终完成。 */
    data object Pending : DocumentRenameBeforeSaveResult

    /** 当前 tab 不能安全准入一条重命名命令。没有任何东西被排队或发送。 */
    data object NotAdmitted : DocumentRenameBeforeSaveResult

    /** 命令已被确认，但更新的本地 tab 帧拒绝了它的投影。 */
    data object Superseded : DocumentRenameBeforeSaveResult

    /** 重命名已在远端提交，但它的新 revision 未被准入到本地持久化。 */
    data object LocalPersistenceRejected : DocumentRenameBeforeSaveResult

    data class Acknowledged(
        val projection: DocumentMoveResult,
    ) : DocumentRenameBeforeSaveResult
}

/** 跨确认框/异步响应始终用稳定 instanceId 重新解析仍然存在的标签。 */
internal fun documentTabIdByInstance(
    tabs: List<DocumentTabState>,
    instanceId: Long,
): String? = tabs.firstOrNull { it.instanceId == instanceId }?.tabId

/** 删除成功后，同一远端文档在请求期间重新打开的实例也已经失效，必须一并关闭。 */
internal fun documentTabIdsInvalidatedByDelete(
    tabs: List<DocumentTabState>,
    request: DocumentDeleteRequest,
): List<String> = tabs.filter { tab ->
    tab.instanceId == request.instanceId ||
        (tab.spaceId == request.spaceId && tab.documentId == request.documentId)
}.map(DocumentTabState::tabId)

/** 可观察的、按 tab 划分的写所有权；无关的 tab 可以独立保存。 */
internal class DocumentPendingMutationTracker {
    data class Ticket(val id: Long, val request: DocumentTabRequest)

    private var sequence = 0L
    private var pending by mutableStateOf<Map<Long, DocumentTabRequest>>(emptyMap())

    fun begin(tab: DocumentTabState, editGeneration: Long? = null): Ticket? {
        if (pending.values.any { it.targets(tab) }) return null
        check(sequence < Long.MAX_VALUE) { "文档写入票据代次已耗尽" }
        val ticket = Ticket(
            ++sequence,
            DocumentTabRequest.capture(tab, editGeneration ?: tab.editGeneration),
        )
        pending = pending + (ticket.id to ticket.request)
        return ticket
    }

    fun end(ticket: Ticket) {
        pending = pending - ticket.id
    }

    fun hasFor(tab: DocumentTabState): Boolean = pending.values.any { it.targets(tab) }
}

/** 写响应合并结果；新建文档的客户端资源 ID 在首次保存后保持不变。 */
internal data class DocumentTabMerge(
    val request: DocumentTabRequest,
    val tabs: List<DocumentTabState>,
    val tab: DocumentTabState,
) {
    val requestTabId: String get() = request.tabId
}

/** 只为一次修改捕获的确切资源身份接受返回的路径。 */
internal fun Document.matchesDocumentMutationRequest(request: DocumentTabRequest): Boolean =
    hasValidDocumentPath() && spaceId == request.spaceId && documentId == request.tabId &&
        (request.documentId == null || documentId == request.documentId)

/**
 * 将服务端保存结果合并回仍然存在的同一标签。
 *
 * 服务端 revision/saved baseline 总是采用成功响应；若请求发出后用户又编辑过，则保留最新
 * draft 并继续标脏。标签已经关闭、被另一响应推进 revision，或身份不一致时直接丢弃迟到响应。
 */
internal fun mergeDocumentMutationResponse(
    tabs: List<DocumentTabState>,
    request: DocumentTabRequest,
    saved: Document,
): DocumentTabMerge? {
    if (!saved.matchesDocumentMutationRequest(request)) return null

    val index = tabs.indexOfFirst { request.targets(it) }
    if (index < 0) return null
    val latest = tabs[index]
    if (latest.revision != request.revision) return null

    val editedAfterRequest = latest.editGeneration != request.editGeneration
    val merged = DocumentTabState.from(
        saved,
        instanceId = latest.instanceId,
        editGeneration = latest.editGeneration,
        recoveryId = latest.recoveryId,
    ).let { baseline ->
        if (!editedAfterRequest) baseline else baseline.copy(
            draftTitle = latest.draftTitle,
            draftMarkdown = latest.draftMarkdown,
            draftAssets = latest.draftAssets,
            dirty = true,
        )
    }
    return DocumentTabMerge(
        request = request,
        tabs = tabs.toMutableList().also { it[index] = merged },
        tab = merged,
    )
}

/**
 * 在一次持久化挂起之后，把一个成功响应重放进最新的工作区。
 *
 * [DocumentTabMerge.tabs] 只是挂起之前观察到的快照，绝不能整份赋值。
 * 这个辅助函数只修补 [latestTabs] 中确切的请求 tab，保留每一个无关的 tab 修改。
 * 在旧恢复身份被守护期间捕获的编辑器帧是完整快照，
 * 因此最新帧可以重新变基到新的服务器 revision 上。
 */
internal fun mergeDocumentMutationAfterDurableCleanup(
    latestTabs: List<DocumentTabState>,
    merge: DocumentTabMerge,
    deferredUpdate: DocumentDraftUpdate?,
    rotateRecoveryIdentity: Boolean,
    freshRecoveryId: String = UUID.randomUUID().toString(),
): DocumentTabMerge? {
    val index = latestTabs.indexOfFirst(merge.request::targets)
    if (index < 0) return null
    val current = latestTabs[index]
    if (current.revision != merge.request.revision ||
        current.editGeneration != merge.tab.editGeneration ||
        current.recoveryId != merge.tab.recoveryId
    ) return null

    val patched = merge.tab.copy(
        recoveryId = if (rotateRecoveryIdentity) freshRecoveryId else merge.tab.recoveryId,
    )
    var nextTabs: List<DocumentTabState> = latestTabs.toMutableList().also { it[index] = patched }
    if (deferredUpdate != null) {
        nextTabs = updateDocumentDraftTabs(
            nextTabs,
            deferredUpdate.copy(revision = patched.revision),
        )
    }
    val resolved = nextTabs.firstOrNull { tab ->
        tab.instanceId == patched.instanceId && tab.tabId == patched.tabId
    } ?: return null
    return DocumentTabMerge(merge.request, nextTabs, resolved)
}

/** 把最新的完整编辑器帧应用到已经合并的服务器基线上。 */
internal fun rebaseDeferredDocumentDraftUpdate(
    merge: DocumentTabMerge,
    deferredUpdate: DocumentDraftUpdate,
): DocumentTabMerge? {
    val nextTabs = updateDocumentDraftTabs(
        merge.tabs,
        deferredUpdate.copy(revision = merge.tab.revision),
    )
    val resolved = nextTabs.firstOrNull { tab ->
        tab.instanceId == merge.tab.instanceId && tab.tabId == merge.tab.tabId
    } ?: return null
    return merge.copy(tabs = nextTabs, tab = resolved)
}

/** 把一个存活的 tab 从已经持久的取消身份旋转离开。 */
internal fun rotateDocumentTabRecoveryIdentity(
    tabs: List<DocumentTabState>,
    request: DocumentTabRequest,
    retiredRecoveryId: String,
    freshRecoveryId: String = UUID.randomUUID().toString(),
): List<DocumentTabState> {
    val index = tabs.indexOfFirst { request.targets(it) && it.recoveryId == retiredRecoveryId }
    if (index < 0) return tabs
    return tabs.toMutableList().also { next ->
        next[index] = next[index].copy(recoveryId = freshRecoveryId)
    }
}

/**
 * 把本地保留的创建草稿绑定到服务器已提交的稳定身份上。
 *
 * 这里不接受任何响应正文或路径：调用方只知道幂等的创建命令已提交。
 * 本地正文刻意保持 dirty，路径保持未解析，这样恢复的行通过一次 CAS update/refresh
 * 收敛，而不是发出第二次创建。新的恢复身份是强制性的，因为 creating 纪元
 * 与其 outbox 命令一起被持久地退役。
 */
internal data class CommittedDocumentCreateBinding(
    val tabs: List<DocumentTabState>,
    val tab: DocumentTabState,
    val retiredRecoveryKey: String,
)

internal fun removeMatchingCommittedCreateDeferredFrame(
    deferredFrames: MutableMap<String, DocumentDraftUpdate>,
    binding: CommittedDocumentCreateBinding,
): DocumentDraftUpdate? {
    val candidate = deferredFrames[binding.retiredRecoveryKey] ?: return null
    if (candidate.tabId != binding.tab.tabId ||
        candidate.instanceId != binding.tab.instanceId ||
        candidate.revision != null
    ) return null
    deferredFrames.remove(binding.retiredRecoveryKey)
    return candidate
}

/** 使在被守护的 creating 纪元下捕获的帧成为第一次持久替换的一部分。 */
internal fun rebaseCommittedDocumentCreateBinding(
    binding: CommittedDocumentCreateBinding,
    deferredFrame: DocumentDraftUpdate,
): CommittedDocumentCreateBinding {
    if (deferredFrame.tabId != binding.tab.tabId ||
        deferredFrame.instanceId != binding.tab.instanceId
    ) return binding
    val nextTabs = updateDocumentDraftTabs(
        binding.tabs,
        deferredFrame.copy(revision = binding.tab.revision),
    ).map { tab ->
        if (tab.instanceId == binding.tab.instanceId) tab.copy(dirty = true) else tab
    }
    val rebound = nextTabs.first { tab -> tab.instanceId == binding.tab.instanceId }
    return binding.copy(tabs = nextTabs, tab = rebound)
}

internal fun bindCommittedDocumentCreateIdentity(
    tabs: List<DocumentTabState>,
    command: PendingDocumentCreateCommand,
    freshRecoveryId: String = UUID.randomUUID().toString(),
): CommittedDocumentCreateBinding? {
    val index = tabs.indexOfFirst(command::matches)
    if (index < 0) return null
    val creating = tabs[index]
    require(freshRecoveryId != creating.recoveryId) {
        "Committed create must rotate its recovery identity"
    }
    val bound = creating.copy(
        recoveryId = freshRecoveryId,
        documentId = command.documentId,
        pathResolved = false,
        remoteMissing = false,
        // 一个没有投影的确切确认仍然证明不可变的创建载荷已经提交。
        // 保留其规范标题可以避免在路径被重新校验之前，
        // 把下一次仅正文的 CAS 路由经过一条 move 命令。
        savedTitle = command.title,
        revision = DOCUMENT_INITIAL_REVISION,
        // 响应投影被围栏隔离。即使它仍然等于原始创建命令的载荷，
        // 也要把用户的正文排队等待一次显式的 CAS update。
        dirty = true,
        creating = false,
    )
    return CommittedDocumentCreateBinding(
        tabs = tabs.toMutableList().also { it[index] = bound },
        tab = bound,
        retiredRecoveryKey = creating.draftRecoveryKey(),
    )
}

/**
 * 只把一次成功的 move 应用到命令捕获的那个确切的已保存 tab 实例和 revision。
 * 节点和它的 root 到 parent 链都来自同一个权威服务器结果；
 * 在 RPC 之前捕获的目标路径在响应到达时可能已经过期。
 */
internal fun mergeDocumentMoveResponse(
    tabs: List<DocumentTabState>,
    request: DocumentMoveRequest,
    moved: DocumentMoveResult,
): List<DocumentTabState>? {
    if (!moved.matchesDocumentMoveRequest(request)) return null
    val node = moved.node
    val index = tabs.indexOfFirst(request::targets)
    if (index < 0) return null
    val current = tabs[index]
    val editedAfterRequest = request.preserveDraftTitle ||
        current.editGeneration != request.editGeneration
    val updated = current.copy(
        parentId = node.parentId,
        ancestorIds = moved.ancestorIds,
        pathResolved = true,
        savedTitle = node.name,
        draftTitle = if (editedAfterRequest) current.draftTitle else node.name,
        draftMarkdown = current.draftMarkdown,
        revision = node.revision,
        dirty = current.draftTitle != node.name ||
            current.draftMarkdown != current.savedMarkdown ||
            current.draftAssets != current.savedAssets,
    )
    return tabs.mapIndexed { tabIndex, tab ->
        when {
            tabIndex == index -> updated
            tab.spaceId != node.spaceId -> tab
            // 本地创建没有可重新校验的服务器节点。它的正文必须离线保持可及；
            // 当稳定 ID 创建命令最终被准入时，服务器会解析它当前的 parent 链。
            tab.creating || tab.documentId == null -> tab
            // parent revision 不对子树成员关系做版本化。另一个客户端可能恰好在这条命令之前
            // 把这个 tab 移入或移出子树，因此不能从调用方过期的本地树合成任何后代路径。
            else -> tab.copy(pathResolved = false)
        }
    }
}

/** 独立于原始 tab 是否仍然打开，校验返回的路径。 */
internal fun DocumentMoveResult.matchesDocumentMoveRequest(request: DocumentMoveRequest): Boolean {
    val returnedNode = node
    if (returnedNode.nodeId != request.documentId ||
        returnedNode.spaceId != request.spaceId ||
        returnedNode.parentId != request.targetParentId ||
        returnedNode.name != request.title ||
        returnedNode.revision != request.revision + 1L
    ) return false
    if (ancestorIds.any(String::isBlank) ||
        ancestorIds.distinct().size != ancestorIds.size ||
        returnedNode.nodeId in ancestorIds
    ) return false
    if (returnedNode.parentId == null && ancestorIds.isNotEmpty()) return false
    if (returnedNode.parentId != null &&
        ancestorIds.lastOrNull() != returnedNode.parentId
    ) return false
    return true
}

/** 在一次显式的冲突恢复选择之后，采用权威快照。 */
internal fun adoptDocumentConflictServerVersion(
    tabs: List<DocumentTabState>,
    conflict: DocumentRevisionConflictState.Ready,
): List<DocumentTabState>? {
    val index = tabs.indexOfFirst(conflict.request::targetsUnchanged)
    if (index < 0 || conflict.remote.documentId != conflict.request.documentId ||
        conflict.remote.spaceId != conflict.request.spaceId || !conflict.remote.hasValidDocumentPath()
    ) return null
    val current = tabs[index]
    val adopted = DocumentTabState.from(
        conflict.remote,
        instanceId = current.instanceId,
        editGeneration = current.editGeneration,
    )
    return tabs.toMutableList().also { it[index] = adopted }
}

/**
 * 在不发送写入的情况下，把用户捕获的草稿重新变基到最新的远程 revision 上。
 * 因此下一次显式保存就是对 [Document.revision] 的一次普通 CAS。
 */
internal fun rebaseDocumentConflictKeepingDraft(
    tabs: List<DocumentTabState>,
    conflict: DocumentRevisionConflictState.Ready,
): List<DocumentTabState>? {
    val index = tabs.indexOfFirst(conflict.request::targetsUnchanged)
    if (index < 0 || conflict.remote.documentId != conflict.request.documentId ||
        conflict.remote.spaceId != conflict.request.spaceId || !conflict.remote.hasValidDocumentPath()
    ) return null
    val current = tabs[index]
    val rebased = DocumentTabState.from(
        conflict.remote,
        instanceId = current.instanceId,
        editGeneration = current.editGeneration,
        recoveryId = current.recoveryId,
    ).copy(
        draftTitle = current.draftTitle,
        draftMarkdown = current.draftMarkdown,
        draftAssets = current.draftAssets,
        dirty = true,
    )
    return tabs.toMutableList().also { it[index] = rebased }
}

/** 历史/修订请求只以稳定文档身份为目标，不随正文编辑世代改变。 */
internal data class DocumentRequestTarget(
    val tabId: String,
    val documentId: String,
    val spaceId: String,
) {
    fun targets(tab: DocumentTabState?): Boolean = tab != null &&
        tab.tabId == tabId && tab.documentId == documentId && tab.spaceId == spaceId

    companion object {
        fun from(tab: DocumentTabState): DocumentRequestTarget? =
            tab.documentId?.takeUnless { tab.remoteMissing }?.let { documentId ->
            DocumentRequestTarget(tab.tabId, documentId, tab.spaceId)
        }
    }
}
