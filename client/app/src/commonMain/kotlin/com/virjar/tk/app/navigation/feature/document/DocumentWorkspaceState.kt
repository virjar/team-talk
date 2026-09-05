package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import java.util.UUID

internal const val MAX_OPEN_DOCUMENT_TABS = 24

/** 用于区分切换驻留 tab 与打开新 tab 的稳定远程身份。 */
internal data class DocumentTabTarget(
    val spaceId: String,
    val documentId: String,
)

/** 每一个可能把编辑器正文加入内存的操作的纯准入结果。 */
internal sealed interface DocumentTabOpenDecision {
    data class ReuseResident(val tab: DocumentTabState) : DocumentTabOpenDecision
    data object AdmitNew : DocumentTabOpenDecision
    data class RejectAtCapacity(
        val openTabCount: Int,
        val maxOpenTabs: Int,
    ) : DocumentTabOpenDecision
}

/**
 * 决定是否可以添加编辑器，而不修改或裁剪当前 tab 列表。
 *
 * [target] 只有在从未保存的本地草稿时才为 null。驻留目标始终保持可用，
 * 包括旧客户端恢复的快照已经超过今天上限的情形。
 */
internal fun decideDocumentTabOpen(
    tabs: List<DocumentTabState>,
    target: DocumentTabTarget?,
    maxOpenTabs: Int = MAX_OPEN_DOCUMENT_TABS,
): DocumentTabOpenDecision {
    require(maxOpenTabs > 0) { "Document tab limit must be positive" }
    target?.let { identity ->
        tabs.firstOrNull { tab ->
            tab.spaceId == identity.spaceId && tab.documentId == identity.documentId
        }?.let { return DocumentTabOpenDecision.ReuseResident(it) }
    }
    return if (tabs.size < maxOpenTabs || tabs.any { !it.dirty && !it.creating }) {
        // 驻留正文规划器可以在发布之前退役一个干净的非活动缓存条目。
        // dirty/creating 的 tab 保持受保护，因此全 dirty 的边界仍然 fail closed。
        DocumentTabOpenDecision.AdmitNew
    } else {
        DocumentTabOpenDecision.RejectAtCapacity(
            openTabCount = tabs.size,
            maxOpenTabs = maxOpenTabs,
        )
    }
}

internal fun DocumentTabOpenDecision.RejectAtCapacity.userMessage(): String =
    "最多同时打开 $maxOpenTabs 篇文档，请先保存正在编辑的内容并关闭一个标签后再试"

internal fun DocumentTabOpenDecision.RejectAtCapacity.asFailure(): IllegalStateException =
    IllegalStateException(
        "Document tab capacity reached: open=$openTabCount, limit=$maxOpenTabs",
    )

/** 一次 tab 退役请求的终止结果。每个调用方恰好收到一个结果。 */
internal enum class DocumentTabCloseOutcome {
    CLOSED,
    BLOCKED_BY_SAVE,
    BLOCKED_BY_DISCARD,
    PERSISTENCE_FAILED,
}

/** 一个打开的文档标签。草稿与服务端基线分离，切换空间或标签不会丢失未保存内容。 */
data class DocumentTabState(
    val tabId: String,
    /** 每次打开标签都分配新实例，关闭后重开同一 documentId 也不会复用。 */
    val instanceId: Long,
    /**
     * 这个 tab 的一个可恢复纪元的稳定身份。持久墓碑永久退役该纪元，
     * 因此一个仍然打开的干净 tab 在它可能再次变 dirty 之前会收到一个新值。
     */
    val recoveryId: String = UUID.randomUUID().toString(),
    val documentId: String?,
    val spaceId: String,
    val parentId: String?,
    /** 目录链固定为 root → parent，不包含文档自身。 */
    val ancestorIds: List<String>,
    /** false 意味着在 getDocument 重新校验之前，绝不能使用缓存的路径。 */
    val pathResolved: Boolean = true,
    /** 服务器权威地返回了 404；只有显式的 create-as-new 才能写这个正文。 */
    val remoteMissing: Boolean = false,
    val savedTitle: String,
    val savedMarkdown: String,
    val draftTitle: String,
    val draftMarkdown: String,
    val revision: Long?,
    val dirty: Boolean = false,
    val creating: Boolean = false,
    /**
     * 本地草稿世代。每次标题或正文实际改变时递增；远端写请求只允许清理自己捕获的世代。
     * 这不是服务端 revision，二者分别描述“本地是否又编辑过”和“服务端当前版本”。
     */
    val editGeneration: Long = 0,
    val savedAssets: List<EmbeddedAsset> = emptyList(),
    val draftAssets: List<EmbeddedAsset> = emptyList(),
) {
    companion object {
        fun from(
            document: Document,
            instanceId: Long,
            editGeneration: Long = 0,
            recoveryId: String = UUID.randomUUID().toString(),
        ): DocumentTabState {
            require(document.hasValidDocumentPath()) { "服务器返回了非法文档路径" }
            return DocumentTabState(
                tabId = document.documentId,
                instanceId = instanceId,
                recoveryId = recoveryId,
                documentId = document.documentId,
                spaceId = document.spaceId,
                parentId = document.parentId,
                ancestorIds = document.ancestorIds,
                savedTitle = document.title,
                savedMarkdown = document.markdown,
                draftTitle = document.title,
                draftMarkdown = document.markdown,
                revision = document.revision,
                editGeneration = editGeneration,
                savedAssets = document.assets,
                draftAssets = document.assets,
            )
        }
    }
}

/** 创建新的本地文档 tab 的不可变初始状态。 */
internal fun newDocumentDraftTab(
    tabId: String,
    instanceId: Long,
    spaceId: String,
    location: DocumentCreationLocation,
): DocumentTabState = DocumentTabState(
    tabId = tabId,
    instanceId = instanceId,
    documentId = null,
    spaceId = spaceId,
    parentId = location.parentId,
    ancestorIds = location.ancestorIds,
    savedTitle = "",
    savedMarkdown = "",
    draftTitle = "无标题文档",
    draftMarkdown = "",
    revision = null,
    dirty = true,
    creating = true,
)

/** 把保留的、远端已删除的草稿转换成一个持久的、幂等的创建身份。 */
internal fun prepareRemoteMissingDocumentCreate(
    tab: DocumentTabState,
    newDocumentId: String,
    location: DocumentCreationLocation,
): DocumentTabState? {
    if (!tab.remoteMissing || tab.creating || tab.documentId == null || tab.revision == null ||
        !tab.dirty
    ) return null
    val canonicalId = runCatching { UUID.fromString(newDocumentId).toString() }.getOrNull()
        ?: return null
    if (canonicalId != newDocumentId) return null
    return tab.copy(
        tabId = newDocumentId,
        documentId = null,
        parentId = location.parentId,
        ancestorIds = location.ancestorIds,
        pathResolved = true,
        remoteMissing = false,
        savedTitle = "",
        savedMarkdown = "",
        savedAssets = emptyList(),
        revision = null,
        dirty = true,
        creating = true,
    )
}

/** 标识允许发布一个本地帧的确切编辑器基线。 */
internal data class DocumentDraftUpdate(
    val tabId: String,
    val instanceId: Long,
    val revision: Long?,
    val title: String,
    val markdown: String,
    /** null 意味着仅文本的编辑器帧；非 null 发布编辑器上传的清单。 */
    val assets: List<EmbeddedAsset>? = null,
)

/** 按首次引用顺序保留描述符，绝不解析到这个 tab 之外。 */
internal fun projectDocumentDraftAssets(
    markdown: String,
    candidates: List<EmbeddedAsset>,
): List<EmbeddedAsset> {
    val byId = candidates.distinctBy(EmbeddedAsset::assetId).associateBy(EmbeddedAsset::assetId)
    return runCatching {
        MarkdownAssetPolicy.references(markdown)
            .mapNotNull { it.assetId }
            .distinct()
            .mapNotNull(byId::get)
    }.getOrElse { emptyList() }
}

/** 应用一个编辑器帧，而不原地修改可观察的 tab 列表。 */
internal fun updateDocumentDraftTabs(
    tabs: List<DocumentTabState>,
    update: DocumentDraftUpdate,
): List<DocumentTabState> {
    val index = tabs.indexOfFirst { tab ->
        tab.tabId == update.tabId && tab.instanceId == update.instanceId &&
            tab.revision == update.revision
    }
    if (index < 0) return tabs
    val tab = tabs[index]
    val projectedAssets = projectDocumentDraftAssets(
        markdown = update.markdown,
        candidates = update.assets.orEmpty() + tab.draftAssets + tab.savedAssets,
    )
    val contentChanged = tab.draftTitle != update.title || tab.draftMarkdown != update.markdown ||
        tab.draftAssets != projectedAssets
    val draftTitle = if (update.title == tab.savedTitle) tab.savedTitle else update.title
    val draftMarkdown = if (update.markdown == tab.savedMarkdown) {
        // 一个干净的 tab 拥有一个正文 String，而不是两个相等的分配。这也使驻留记账
        // 在用户回到基线之后反映实际的后备内容。
        tab.savedMarkdown
    } else {
        update.markdown
    }
    val updated = tab.copy(
        draftTitle = draftTitle,
        draftMarkdown = draftMarkdown,
        draftAssets = projectedAssets,
        dirty = tab.creating || update.title != tab.savedTitle || update.markdown != tab.savedMarkdown ||
            projectedAssets != tab.savedAssets,
        editGeneration = if (contentChanged) tab.editGeneration + 1 else tab.editGeneration,
    )
    if (updated == tab) return tabs
    return tabs.toMutableList().also { it[index] = updated }
}

/** 关闭活动标签后只在原空间内选择替补，避免编辑上下文暗中跳到另一个空间。 */
internal fun replacementDocumentTab(
    remainingTabs: List<DocumentTabState>,
    closedSpaceId: String,
): DocumentTabState? = remainingTabs.lastOrNull { it.spaceId == closedSpaceId }

/** 未解析的缓存路径绝不能成为工作区选中的目录事实。 */
internal fun DocumentTabState.resolvedParentIdForNavigation(): String? =
    parentId.takeIf { pathResolved }

/** 关闭编辑器后目录仅记住已经服务端校验的父页面。 */
internal fun selectedParentAfterClosingDocumentTab(
    closing: DocumentTabState,
    replacement: DocumentTabState?,
): String? = if (replacement != null) {
    replacement.resolvedParentIdForNavigation()
} else {
    closing.resolvedParentIdForNavigation()
}
