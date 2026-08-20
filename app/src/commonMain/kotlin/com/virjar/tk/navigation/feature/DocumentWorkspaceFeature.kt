package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentHomeItem
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录树当前可见的一行。子目录只在展开后按需从服务端加载。 */
data class DocumentTreeRow(val node: DocumentNode, val depth: Int)

/** 一个打开的文档标签。草稿与服务端基线分离，切换空间或标签不会丢失未保存内容。 */
data class DocumentTabState(
    val tabId: String,
    /** 每次打开标签都分配新实例，关闭后重开同一 documentId 也不会复用。 */
    val instanceId: Long,
    val documentId: String?,
    val spaceId: String,
    val parentId: String?,
    /** 目录链固定为 root → parent，不包含文档自身。 */
    val ancestorIds: List<String>,
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
) {
    companion object {
        fun from(document: Document, instanceId: Long, editGeneration: Long = 0) = DocumentTabState(
            tabId = document.documentId,
            instanceId = instanceId,
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
        )
    }
}

/** 关闭活动标签后只在原空间内选择替补，避免编辑上下文暗中跳到另一个空间。 */
internal fun replacementDocumentTab(
    remainingTabs: List<DocumentTabState>,
    closedSpaceId: String,
): DocumentTabState? = remainingTabs.lastOrNull { it.spaceId == closedSpaceId }

/** 关闭编辑器后目录仍定位到替补文档或刚关闭文档所在的文件夹。 */
internal fun selectedFolderAfterClosingDocumentTab(
    closing: DocumentTabState,
    replacement: DocumentTabState?,
): String? = replacement?.parentId ?: closing.parentId

/** 根据已加载节点恢复某个目录的 root → folder 路径，供新建草稿保存导航上下文。 */
internal fun folderAncestorIds(
    folderId: String?,
    treeChildren: Map<String?, List<DocumentNode>>,
): List<String> {
    if (folderId == null) return emptyList()
    val nodesById = treeChildren.values.flatten().associateBy(DocumentNode::nodeId)
    val reversed = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var currentId: String? = folderId
    while (currentId != null) {
        if (!visited.add(currentId)) return emptyList()
        val node = nodesById[currentId] ?: return emptyList()
        if (node.nodeType != DocumentNode.TYPE_FOLDER) return emptyList()
        reversed += currentId
        currentId = node.parentId
    }
    return reversed.asReversed()
}

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

    companion object {
        fun capture(tab: DocumentTabState) = DocumentTabRequest(
            tabId = tab.tabId,
            instanceId = tab.instanceId,
            documentId = tab.documentId,
            spaceId = tab.spaceId,
            revision = tab.revision,
            editGeneration = tab.editGeneration,
        )
    }
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

/** tabId 会在新建保存后迁移，跨确认框/异步响应只能用稳定 instanceId 重新解析。 */
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

/** 写响应合并结果；新建文档时标签 ID 会从本地草稿 ID 迁移为服务端文档 ID。 */
internal data class DocumentTabMerge(
    val requestTabId: String,
    val tabs: List<DocumentTabState>,
    val tab: DocumentTabState,
)

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
    if (saved.spaceId != request.spaceId) return null
    if (request.documentId != null && saved.documentId != request.documentId) return null

    val index = tabs.indexOfFirst { request.targets(it) }
    if (index < 0) return null
    val latest = tabs[index]
    if (latest.revision != request.revision) return null

    val editedAfterRequest = latest.editGeneration != request.editGeneration
    val merged = DocumentTabState.from(
        saved,
        instanceId = latest.instanceId,
        editGeneration = latest.editGeneration,
    ).let { baseline ->
        if (!editedAfterRequest) baseline else baseline.copy(
            draftTitle = latest.draftTitle,
            draftMarkdown = latest.draftMarkdown,
            dirty = true,
        )
    }
    return DocumentTabMerge(
        requestTabId = request.tabId,
        tabs = tabs.toMutableList().also { it[index] = merged },
        tab = merged,
    )
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
        fun from(tab: DocumentTabState): DocumentRequestTarget? = tab.documentId?.let { documentId ->
            DocumentRequestTarget(tab.tabId, documentId, tab.spaceId)
        }
    }
}

/** 企业文档工作台状态；不依赖聊天上下文，可同时保留来自多个空间的文档标签。 */
class DocumentWorkspaceFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val draftStore: DocumentDraftStore = DocumentDraftStore(),
) {
    internal val draftLifecycleBridge = DocumentDraftLifecycleBridge()

    var spaces by mutableStateOf(emptyList<DocumentSpace>())
        private set
    var selectedSpaceId by mutableStateOf<String?>(null)
        private set
    val selectedSpace get() = spaces.firstOrNull { it.spaceId == selectedSpaceId }

    var recentDocuments by mutableStateOf(emptyList<DocumentHomeItem>())
        private set
    var recentlyCreatedDocuments by mutableStateOf(emptyList<DocumentHomeItem>())
        private set

    var treeChildren by mutableStateOf<Map<String?, List<DocumentNode>>>(emptyMap())
        private set
    var expandedFolderIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectedFolderId by mutableStateOf<String?>(null)
        private set
    val treeRows: List<DocumentTreeRow>
        get() = buildList {
            fun append(parentId: String?, depth: Int) {
                treeChildren[parentId].orEmpty().forEach { node ->
                    add(DocumentTreeRow(node, depth))
                    if (node.nodeType == DocumentNode.TYPE_FOLDER && node.nodeId in expandedFolderIds) {
                        append(node.nodeId, depth + 1)
                    }
                }
            }
            append(null, 0)
        }

    var tabs by mutableStateOf(emptyList<DocumentTabState>())
        private set
    var activeTabId by mutableStateOf<String?>(null)
        private set
    val activeTab get() = tabs.firstOrNull { it.tabId == activeTabId }

    var grants by mutableStateOf(emptyList<DocumentSpaceGrant>())
        private set
    var organizationUnits by mutableStateOf(emptyList<OrganizationUnit>())
        private set
    var organizationMembers by mutableStateOf(emptyList<OrganizationMember>())
        private set
    var revisions by mutableStateOf(emptyList<DocumentRevisionSummary>())
        private set
    var revisionPreview by mutableStateOf<DocumentRevision?>(null)
        private set

    var loading by mutableStateOf(false)
        private set
    var loadingHome by mutableStateOf(false)
        private set
    var loadingNodes by mutableStateOf(false)
        private set
    var loadingDocument by mutableStateOf(false)
        private set

    /** 仍保留 UI 现有布尔接口，但含义收口为“当前活动标签是否有写请求在途”。 */
    val saving: Boolean
        get() {
            val current = activeTab ?: return false
            return pendingMutations.values.any { it.targets(current) }
        }

    private var draftCounter = 0L
    private var draftRestorationLoaded = false
    private var pendingDraftRestoration: DocumentWorkspaceDraftSnapshot? = null
    private var tabInstanceSequence = 0L
    private val navigationGeneration = GenerationGate()
    private var mutationSequence = 0L
    private var pendingMutations by mutableStateOf<Map<Long, DocumentTabRequest>>(emptyMap())
    private var historyTarget: DocumentRequestTarget? = null
    private val historyListGate = LatestRequestGate<DocumentRequestTarget>()
    private val revisionPreviewGate = LatestRequestGate<DocumentRequestTarget>()

    suspend fun open() {
        val generation = beginNavigation()
        loading = true
        try {
            // A persisted body can be several MiB; read and deserialize it away from the UI
            // dispatcher, and only when the user actually enters Documents.
            val restoration = loadInitialDraftRestoration()
            if (!isCurrentNavigation(generation)) return
            // 一级导航进入“文档”时总是回到资产首页；已打开标签和草稿仍保留在工作台会话中。
            // Activity/process 重建后的新 feature 例外：只在第一次 open 恢复本地编辑现场。
            if (restoration == null) clearSpaceSelection()
            val loadedSpaces = session.documentRepo.listSpaces().getOrThrow()
            if (!isCurrentNavigation(generation)) return
            spaces = loadedSpaces
            if (restoration != null) {
                restoreDraftWorkspace(restoration, generation)
                if (!isCurrentNavigation(generation)) return
                pendingDraftRestoration = null
            }
            loadHome(generation)
        } catch (e: Exception) {
            if (isCurrentNavigation(generation)) reportError(e, "加载文档首页失败")
        } finally {
            if (isCurrentNavigation(generation)) loading = false
        }
    }

    fun refresh() = beginNavigation().let { generation ->
        val spaceIdBeforeRefresh = selectedSpaceId
        val selectedFolderBeforeRefresh = selectedFolderId
        val selectedFolderPath = folderAncestorIds(selectedFolderBeforeRefresh, treeChildren)
        scope.launch {
            loading = true
            try {
                val loadedSpaces = session.documentRepo.listSpaces().getOrThrow()
                if (!isCurrentNavigation(generation)) return@launch
                spaces = loadedSpaces
                loadHome(generation)
                if (!isCurrentNavigation(generation)) return@launch

                val spaceId = spaceIdBeforeRefresh ?: return@launch
                if (spaces.none { it.spaceId == spaceId }) {
                    clearSpaceSelection()
                    return@launch
                }
                if (selectedSpaceId != spaceId) return@launch
                if (!loadTreeRoot(generation)) return@launch

                val currentTab = activeTab?.takeIf { it.spaceId == spaceId }
                if (currentTab != null) {
                    selectedFolderId = currentTab.parentId
                    revealDocumentPath(currentTab.ancestorIds, generation)
                } else if (selectedFolderBeforeRefresh != null) {
                    selectedFolderId = selectedFolderBeforeRefresh
                    revealDocumentPath(selectedFolderPath, generation)
                }
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "刷新文档工作台失败")
            } finally {
                if (isCurrentNavigation(generation)) loading = false
            }
        }
    }

    fun showHome() {
        val generation = beginNavigation()
        clearSpaceSelection()
        scope.launch {
            try {
                loadHome(generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "刷新文档首页失败")
            }
        }
    }

    fun createSpace(name: String, description: String?) = beginNavigation().let { generation ->
        scope.launch {
            try {
                val created = session.documentRepo.createSpace(name, description).getOrThrow()
                spaces = listOf(created) + spaces.filterNot { it.spaceId == created.spaceId }
                if (isCurrentNavigation(generation)) selectSpaceNow(created.spaceId, generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "创建文档空间失败")
            }
        }
    }

    fun updateSpace(name: String, description: String?) = scope.launch {
        val spaceId = selectedSpaceId ?: return@launch
        try {
            val updated = session.documentRepo.updateSpace(spaceId, name, description).getOrThrow()
            spaces = spaces.map { if (it.spaceId == spaceId) updated else it }
        } catch (e: Exception) {
            reportError(e, "更新文档空间失败")
        }
    }

    fun archiveSelectedSpace() = scope.launch {
        val spaceId = selectedSpaceId ?: return@launch
        try {
            session.documentRepo.archiveSpace(spaceId).getOrThrow()
            spaces = spaces.filterNot { it.spaceId == spaceId }
            tabs = tabs.filterNot { it.spaceId == spaceId }
            if (activeTabId !in tabs.map { it.tabId }) activeTabId = tabs.lastOrNull()?.tabId
            persistDraftSnapshot()
            clearSpaceSelection()
            loadHome()
        } catch (e: Exception) {
            reportError(e, "归档文档空间失败")
        }
    }

    fun selectSpace(spaceId: String) = beginNavigation().let { generation ->
        closeHistory()
        scope.launch {
            try {
                selectSpaceNow(spaceId, generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "打开文档空间失败")
            }
        }
    }

    fun selectRootFolder() {
        selectedFolderId = null
        persistDraftSnapshot()
    }

    fun toggleFolder(folder: DocumentNode) = scope.launch {
        if (folder.nodeType != DocumentNode.TYPE_FOLDER) return@launch
        val spaceId = selectedSpaceId ?: return@launch
        if (folder.spaceId != spaceId) return@launch
        selectedFolderId = folder.nodeId
        persistDraftSnapshot()
        if (folder.nodeId in expandedFolderIds) {
            expandedFolderIds = expandedFolderIds - folder.nodeId
            return@launch
        }
        try {
            if (!treeChildren.containsKey(folder.nodeId) && !loadChildren(folder.nodeId)) return@launch
            if (selectedSpaceId != spaceId) return@launch
            expandedFolderIds = expandedFolderIds + folder.nodeId
        } catch (e: Exception) {
            reportError(e, "展开文档目录失败")
        }
    }

    fun createFolder(name: String) = scope.launch {
        val spaceId = selectedSpaceId ?: return@launch
        val parentId = selectedFolderId
        try {
            session.documentRepo.createFolder(spaceId, parentId, name).getOrThrow()
            if (selectedSpaceId == spaceId) reloadChildren(parentId)
        } catch (e: Exception) {
            reportError(e, "创建文件夹失败")
        }
    }

    fun beginDocument() {
        val spaceId = selectedSpaceId ?: return
        // 新草稿就是新的活动导航目标，阻止此前仍在等待的“打开文档”覆盖它。
        beginNavigation()
        val tabId = "draft-${++draftCounter}-${System.currentTimeMillis()}"
        val tab = DocumentTabState(
            tabId = tabId,
            instanceId = nextTabInstanceId(),
            documentId = null,
            spaceId = spaceId,
            parentId = selectedFolderId,
            ancestorIds = folderAncestorIds(selectedFolderId, treeChildren),
            savedTitle = "",
            savedMarkdown = "",
            draftTitle = "无标题文档",
            draftMarkdown = "",
            revision = null,
            dirty = true,
            creating = true,
        )
        tabs = tabs + tab
        activeTabId = tabId
        closeHistory()
        persistDraftSnapshot()
    }

    fun openDocument(node: DocumentNode) = beginNavigation().let { generation ->
        closeHistory()
        scope.launch {
            if (node.nodeType != DocumentNode.TYPE_DOCUMENT) return@launch
            try {
                if (selectedSpaceId != node.spaceId && !selectSpaceNow(node.spaceId, generation)) return@launch
                if (!isCurrentNavigation(generation, node.spaceId)) return@launch
                openDocumentNow(node.spaceId, node.nodeId, generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "加载文档失败")
            }
        }
    }

    fun openHomeDocument(item: DocumentHomeItem) = beginNavigation().let { generation ->
        closeHistory()
        scope.launch {
            try {
                if (selectedSpaceId != item.spaceId && !selectSpaceNow(item.spaceId, generation)) return@launch
                if (!isCurrentNavigation(generation, item.spaceId)) return@launch
                openDocumentNow(item.spaceId, item.documentId, generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "加载文档失败")
            }
        }
    }

    fun selectTab(tabId: String) {
        val tab = tabs.firstOrNull { it.tabId == tabId } ?: return
        val generation = beginNavigation()
        // 点击标签即宣告旧文档历史失效，不能等目录展开的远端调用完成后才清理。
        closeHistory()
        scope.launch {
            try {
                if (selectedSpaceId != tab.spaceId && !selectSpaceNow(tab.spaceId, generation)) return@launch
                if (!isCurrentNavigation(generation, tab.spaceId)) return@launch
                activeTabId = tabId
                selectedFolderId = tab.parentId
                persistDraftSnapshot()
                revealDocumentPath(tab.ancestorIds, generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "切换文档标签失败")
            }
        }
    }

    fun updateDraft(tabId: String, title: String, markdown: String, dirty: Boolean) {
        tabs = tabs.map { tab ->
            if (tab.tabId != tabId) return@map tab
            val contentChanged = tab.draftTitle != title || tab.draftMarkdown != markdown
            tab.copy(
                draftTitle = title,
                draftMarkdown = markdown,
                dirty = dirty || tab.creating,
                editGeneration = if (contentChanged) tab.editGeneration + 1 else tab.editGeneration,
            )
        }
        persistDraftSnapshot()
    }

    fun closeTab(tabId: String) {
        val closing = tabs.firstOrNull { it.tabId == tabId } ?: return
        val remainingTabs = tabs.filterNot { it.tabId == tabId }
        tabs = remainingTabs
        if (activeTabId != tabId) {
            persistDraftSnapshot()
            return
        }

        val generation = beginNavigation()
        val replacement = replacementDocumentTab(remainingTabs, closing.spaceId)
        if (replacement == null) {
            activeTabId = null
            // 关闭最后一篇文档后回到它所在的目录，而不是把用户弹回空间根目录。
            // 打开该文档时 revealDocumentPath 已展开 ancestorIds，目录上下文可以直接复用。
            selectedFolderId = selectedFolderAfterClosingDocumentTab(closing, replacement)
            closeHistory()
            persistDraftSnapshot()
            return
        }

        if (selectedSpaceId == replacement.spaceId) {
            activeTabId = replacement.tabId
            selectedFolderId = selectedFolderAfterClosingDocumentTab(closing, replacement)
        } else {
            activeTabId = null
            selectedFolderId = null
        }
        closeHistory()
        persistDraftSnapshot()
        scope.launch {
            try {
                if (selectedSpaceId != replacement.spaceId &&
                    !selectSpaceNow(replacement.spaceId, generation)
                ) return@launch
                if (!isCurrentNavigation(generation, replacement.spaceId)) return@launch
                activeTabId = replacement.tabId
                selectedFolderId = replacement.parentId
                persistDraftSnapshot()
                revealDocumentPath(replacement.ancestorIds, generation)
            } catch (e: Exception) {
                if (isCurrentNavigation(generation)) reportError(e, "恢复文档标签失败")
            }
        }
    }

    fun closeTabByInstance(instanceId: Long) {
        val currentTabId = documentTabIdByInstance(tabs, instanceId) ?: return
        closeTab(currentTabId)
    }

    fun saveActive() {
        // 必须在调用时同步捕获活动标签；若放进 launch，紧接着的标签切换会把保存目标偷换掉。
        val current = activeTab ?: return
        val mutation = beginMutation(current) ?: return
        scope.launch {
            try {
                val saved = if (current.creating || current.documentId == null) {
                    session.documentRepo.createDocument(
                        current.spaceId,
                        current.parentId,
                        current.draftTitle,
                        current.draftMarkdown,
                    ).getOrThrow()
                } else {
                    session.documentRepo.updateDocument(
                        current.spaceId,
                        current.documentId,
                        current.draftTitle,
                        current.draftMarkdown,
                        current.revision ?: 1,
                    ).getOrThrow()
                }
                val merge = mergeDocumentMutationResponse(tabs, mutation.request, saved)
                if (merge != null) {
                    val shouldRemainActive = activeTabId == merge.requestTabId
                    tabs = merge.tabs
                    if (shouldRemainActive) {
                        activeTabId = merge.tab.tabId
                        selectedFolderId = merge.tab.parentId
                        closeHistory()
                    }
                    persistDraftSnapshot()
                }
                // 即使标签已关闭，服务端写入仍然成功；资产首页/目录应反映真实远端状态。
                if (selectedSpaceId == saved.spaceId) reloadChildren(saved.parentId)
                loadHome()
            } catch (e: Exception) {
                if (requestStillTargetsOpenTab(mutation.request)) reportError(e, "保存文档失败")
            } finally {
                endMutation(mutation.id)
            }
        }
    }

    fun deleteActive() {
        // 必须在 launch 前同步捕获；否则用户紧接着切换到 B 时，协程可能误删 B。
        val current = activeTab ?: return
        val request = DocumentDeleteRequest.capture(current)
        if (request == null) {
            closeTabByInstance(current.instanceId)
            return
        }
        scope.launch {
            try {
                session.documentRepo.deleteNode(
                    request.spaceId,
                    request.documentId,
                    request.revision,
                ).getOrThrow()
                // 请求期间用户可能已关闭旧实例并重新打开同一文档；远端删除成功后这些实例都已失效。
                documentTabIdsInvalidatedByDelete(tabs, request).forEach(::closeTab)
                if (selectedSpaceId == request.spaceId) reloadChildren(request.parentId)
                loadHome()
            } catch (e: Exception) {
                reportError(e, "删除文档失败")
            }
        }
    }

    fun showHistory() {
        val target = activeTab?.let { DocumentRequestTarget.from(it) } ?: return
        historyTarget = target
        revisions = emptyList()
        revisionPreview = null
        revisionPreviewGate.invalidate()
        val token = historyListGate.begin(target)
        scope.launch {
            try {
                val loaded = session.documentRepo.listRevisions(target.spaceId, target.documentId).getOrThrow()
                if (acceptHistoryResponse(token)) {
                    revisions = loaded.filter { it.documentId == target.documentId }
                    revisionPreview = null
                }
            } catch (e: Exception) {
                if (acceptHistoryResponse(token)) reportError(e, "加载版本历史失败")
            }
        }
    }

    fun openRevision(summary: DocumentRevisionSummary) {
        val target = historyTarget ?: return
        if (!target.targets(activeTab) || summary.documentId != target.documentId) return
        val token = revisionPreviewGate.begin(target)
        scope.launch {
            try {
                val loaded = session.documentRepo.getRevision(
                    target.spaceId,
                    target.documentId,
                    summary.revision,
                ).getOrThrow()
                if (acceptRevisionResponse(token) &&
                    loaded.documentId == target.documentId && loaded.revision == summary.revision
                ) {
                    revisionPreview = loaded
                }
            } catch (e: Exception) {
                if (acceptRevisionResponse(token)) reportError(e, "加载文档版本失败")
            }
        }
    }

    fun restorePreview() {
        val current = activeTab ?: return
        val target = DocumentRequestTarget.from(current) ?: return
        val preview = revisionPreview ?: return
        if (historyTarget != target || preview.documentId != target.documentId) return
        val expectedRevision = current.revision ?: return
        val mutation = beginMutation(current) ?: return
        scope.launch {
            try {
                val restored = session.documentRepo.updateDocument(
                    target.spaceId,
                    target.documentId,
                    preview.title,
                    preview.markdown,
                    expectedRevision,
                ).getOrThrow()
                val merge = mergeDocumentMutationResponse(tabs, mutation.request, restored)
                if (merge != null) {
                    val shouldRemainActive = activeTabId == merge.requestTabId
                    tabs = merge.tabs
                    if (shouldRemainActive) {
                        activeTabId = merge.tab.tabId
                        selectedFolderId = merge.tab.parentId
                        // 只刷新仍属于该文档的历史；切换后的 B 文档状态绝不能被 A 的恢复响应触碰。
                        if (historyTarget == target) showHistory()
                    }
                    persistDraftSnapshot()
                }
                if (selectedSpaceId == restored.spaceId) reloadChildren(restored.parentId)
                loadHome()
            } catch (e: Exception) {
                if (requestStillTargetsOpenTab(mutation.request)) reportError(e, "恢复文档版本失败")
            } finally {
                endMutation(mutation.id)
            }
        }
    }

    fun closeHistory() {
        historyTarget = null
        historyListGate.invalidate()
        revisionPreviewGate.invalidate()
        revisions = emptyList()
        revisionPreview = null
    }

    fun closeRevisionPreview() {
        revisionPreviewGate.invalidate()
        revisionPreview = null
    }

    fun refreshGrants() = scope.launch {
        val space = selectedSpace ?: return@launch
        if (space.myRole < DocumentSpace.ROLE_ADMIN) {
            grants = emptyList()
            return@launch
        }
        try {
            if (organizationUnits.isEmpty() && organizationMembers.isEmpty()) loadOrganizationDirectory()
            grants = session.documentRepo.listGrants(space.spaceId).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "加载空间权限失败")
        }
    }

    fun upsertGrant(principalType: Int, principalId: String, role: Int, includeDescendants: Boolean) = scope.launch {
        val spaceId = selectedSpaceId ?: return@launch
        try {
            session.documentRepo.upsertGrant(spaceId, principalType, principalId, role, includeDescendants).getOrThrow()
            grants = session.documentRepo.listGrants(spaceId).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "更新空间权限失败")
        }
    }

    fun removeGrant(grant: DocumentSpaceGrant) = scope.launch {
        try {
            session.documentRepo.removeGrant(grant.spaceId, grant.principalType, grant.principalId).getOrThrow()
            grants = grants.filterNot {
                it.principalType == grant.principalType && it.principalId == grant.principalId
            }
        } catch (e: Exception) {
            reportError(e, "移除空间权限失败")
        }
    }

    private suspend fun selectSpaceNow(spaceId: String, generation: Long): Boolean {
        if (!isCurrentNavigation(generation)) return false
        require(spaces.any { it.spaceId == spaceId }) { "文档空间不存在" }
        selectedSpaceId = spaceId
        // 直接进入空间先展示空间概览，避免沿用另一个空间的活动标签造成左右上下文错位。
        activeTabId = null
        closeHistory()
        selectedFolderId = null
        expandedFolderIds = emptySet()
        treeChildren = emptyMap()
        grants = emptyList()
        val loaded = loadTreeRoot(generation)
        persistDraftSnapshot()
        return loaded
    }

    private suspend fun loadTreeRoot(generation: Long): Boolean {
        if (!isCurrentNavigation(generation, selectedSpaceId)) return false
        treeChildren = emptyMap()
        expandedFolderIds = emptySet()
        selectedFolderId = null
        return loadChildren(null, generation)
    }

    private suspend fun loadChildren(parentId: String?, generation: Long? = null): Boolean {
        val spaceId = selectedSpaceId ?: return false
        if (generation != null && !isCurrentNavigation(generation, spaceId)) return false
        loadingNodes = true
        try {
            val children = session.documentRepo.listNodes(spaceId, parentId).getOrThrow()
            if (selectedSpaceId != spaceId ||
                (generation != null && !isCurrentNavigation(generation, spaceId))
            ) return false
            treeChildren = treeChildren + (parentId to children)
            return true
        } finally {
            if (selectedSpaceId == spaceId &&
                (generation == null || isCurrentNavigation(generation, spaceId))
            ) loadingNodes = false
        }
    }

    private suspend fun reloadChildren(parentId: String?) {
        if (selectedSpaceId == null) return
        loadChildren(parentId)
    }

    private suspend fun openDocumentNow(spaceId: String, documentId: String, generation: Long) {
        if (!isCurrentNavigation(generation, spaceId)) return
        loadingDocument = true
        try {
            tabs.firstOrNull { it.documentId == documentId }?.let { existing ->
                // 显式再次打开也要经过实时 ACL 并刷新最近访问；保留本地草稿，不以服务端快照覆盖。
                val verified = session.documentRepo.getDocument(spaceId, documentId).getOrThrow()
                if (!isCurrentNavigation(generation, spaceId)) return
                val refreshed = refreshRestoredDocumentPath(existing, verified) ?: return
                tabs = tabs.map { if (it.tabId == existing.tabId) refreshed else it }
                activeTabId = refreshed.tabId
                selectedFolderId = refreshed.parentId
                persistDraftSnapshot()
                revealDocumentPath(refreshed.ancestorIds, generation)
                return
            }
            val document = session.documentRepo.getDocument(spaceId, documentId).getOrThrow()
            if (!isCurrentNavigation(generation, spaceId)) return
            val tab = DocumentTabState.from(document, instanceId = nextTabInstanceId())
            tabs = tabs + tab
            activeTabId = tab.tabId
            selectedFolderId = tab.parentId
            persistDraftSnapshot()
            revealDocumentPath(tab.ancestorIds, generation)
        } finally {
            if (isCurrentNavigation(generation, spaceId)) loadingDocument = false
        }
    }

    private suspend fun loadHome(generation: Long? = null) {
        if (generation != null && !isCurrentNavigation(generation)) return
        loadingHome = true
        try {
            val (recent, created) = coroutineScope {
                val recent = async { session.documentRepo.listRecentDocuments(HOME_LIMIT).getOrThrow() }
                val created = async { session.documentRepo.listRecentlyCreatedDocuments(HOME_LIMIT).getOrThrow() }
                recent.await() to created.await()
            }
            if (generation != null && !isCurrentNavigation(generation)) return
            recentDocuments = recent
            recentlyCreatedDocuments = created
        } finally {
            if (generation == null || isCurrentNavigation(generation)) loadingHome = false
        }
    }

    /**
     * 按服务端返回的 root → parent 链逐层加载并展开。不能跳层猜测父目录，否则深层文档会打开正文却
     * 无法在树中定位；每一次远端等待后也必须重新确认导航世代和空间。
     */
    private suspend fun revealDocumentPath(ancestorIds: List<String>, generation: Long): Boolean {
        val spaceId = selectedSpaceId ?: return false
        if (!isCurrentNavigation(generation, spaceId)) return false
        var parentId: String? = null
        val visited = mutableSetOf<String>()
        for (ancestorId in ancestorIds) {
            if (!visited.add(ancestorId) || !isCurrentNavigation(generation, spaceId)) return false
            if (!treeChildren.containsKey(parentId) && !loadChildren(parentId, generation)) return false
            if (!isCurrentNavigation(generation, spaceId)) return false
            val folder = treeChildren[parentId].orEmpty().firstOrNull {
                it.nodeId == ancestorId && it.nodeType == DocumentNode.TYPE_FOLDER
            } ?: return false
            if (folder.spaceId != spaceId) return false

            expandedFolderIds = expandedFolderIds + ancestorId
            if (!treeChildren.containsKey(ancestorId) && !loadChildren(ancestorId, generation)) return false
            if (!isCurrentNavigation(generation, spaceId)) return false
            parentId = ancestorId
        }
        return isCurrentNavigation(generation, spaceId)
    }

    private suspend fun loadOrganizationDirectory() {
        organizationUnits = session.organizationRepo.listUnits().getOrThrow()
        val roots = organizationUnits.filter { it.parentId == null }
        organizationMembers = roots.flatMap { root ->
            session.organizationRepo.listMembers(root.unitId, recursive = true).getOrThrow()
        }.distinctBy { it.uid }
    }

    private data class PendingMutation(val id: Long, val request: DocumentTabRequest)

    private suspend fun loadInitialDraftRestoration(): DocumentWorkspaceDraftSnapshot? {
        if (draftRestorationLoaded) return pendingDraftRestoration
        val restored = withContext(Dispatchers.Default) {
            draftStore.restore(session.userSession.uid)?.normalized()
        }
        pendingDraftRestoration = restored
        draftRestorationLoaded = true
        tabInstanceSequence = maxOf(
            tabInstanceSequence,
            restored?.tabs?.maxOfOrNull(DocumentTabState::instanceId) ?: 0L,
        )
        return restored
    }

    /**
     * Rehydrates only unsaved tabs retained by the same uid. Space/ACL metadata is still loaded
     * from the server, while the restored title and Markdown remain the local source of truth.
     */
    private suspend fun restoreDraftWorkspace(
        rawSnapshot: DocumentWorkspaceDraftSnapshot,
        generation: Long,
    ) {
        val snapshot = rawSnapshot.normalized() ?: return
        tabs = snapshot.tabs
        tabInstanceSequence = maxOf(
            tabInstanceSequence,
            snapshot.tabs.maxOfOrNull(DocumentTabState::instanceId) ?: 0L,
        )

        val targetSpaceId = snapshot.selectedSpaceId
        if (targetSpaceId == null || spaces.none { it.spaceId == targetSpaceId }) {
            activeTabId = null
            selectedSpaceId = null
            selectedFolderId = null
            treeChildren = emptyMap()
            expandedFolderIds = emptySet()
            persistDraftSnapshot()
            return
        }

        selectedSpaceId = targetSpaceId
        activeTabId = null
        selectedFolderId = null
        treeChildren = emptyMap()
        expandedFolderIds = emptySet()
        grants = emptyList()
        closeHistory()

        val active = snapshot.activeTabInstanceId?.let { instanceId ->
            tabs.firstOrNull { it.instanceId == instanceId && it.spaceId == targetSpaceId }
        }
        var directoryFailure: Exception? = null
        try {
            if (loadTreeRoot(generation) && active != null) {
                revealDocumentPath(active.ancestorIds, generation)
            }
        } catch (e: Exception) {
            // Directory state is recoverable; never sacrifice the user's local body because the
            // network/tree request failed during an Activity recreation.
            directoryFailure = e
        }
        if (!isCurrentNavigation(generation, targetSpaceId)) return

        activeTabId = active?.tabId
        selectedFolderId = active?.parentId ?: snapshot.selectedFolderId
        persistDraftSnapshot()
        directoryFailure?.let { reportError(it, "恢复文档目录失败，草稿已保留") }
    }

    private fun persistDraftSnapshot() {
        draftStore.save(
            uid = session.userSession.uid,
            tabs = tabs,
            activeTabId = activeTabId,
            selectedSpaceId = selectedSpaceId,
            selectedFolderId = selectedFolderId,
        )
    }

    /**
     * Publishes the editor's last visual/source frame into the immutable workspace snapshot.
     * Platform shells call this synchronously, then enqueue their own non-blocking durability
     * barrier; the common feature never performs filesystem waits on the UI thread.
     */
    fun captureDrafts(): Boolean = draftLifecycleBridge.captureLatest()

    private fun nextTabInstanceId(): Long = ++tabInstanceSequence

    private fun beginMutation(tab: DocumentTabState): PendingMutation? {
        // 同一标签同一时刻只允许一个保存/恢复请求；切换标签后其他标签仍可独立保存。
        if (pendingMutations.values.any { it.targets(tab) }) return null
        val request = DocumentTabRequest.capture(tab)
        val id = ++mutationSequence
        pendingMutations = pendingMutations + (id to request)
        return PendingMutation(id, request)
    }

    private fun endMutation(id: Long) {
        pendingMutations = pendingMutations - id
    }

    private fun requestStillTargetsOpenTab(request: DocumentTabRequest): Boolean =
        tabs.any(request::targets)

    private fun acceptHistoryResponse(
        token: LatestRequestGate.Token<DocumentRequestTarget>,
    ): Boolean = historyListGate.isCurrent(token) &&
        historyTarget == token.target && token.target.targets(activeTab)

    private fun acceptRevisionResponse(
        token: LatestRequestGate.Token<DocumentRequestTarget>,
    ): Boolean = revisionPreviewGate.isCurrent(token) &&
        historyTarget == token.target && token.target.targets(activeTab)

    private fun clearSpaceSelection() {
        selectedSpaceId = null
        treeChildren = emptyMap()
        expandedFolderIds = emptySet()
        selectedFolderId = null
        loadingNodes = false
        grants = emptyList()
        closeHistory()
        persistDraftSnapshot()
    }

    private fun beginNavigation(): Long {
        val generation = navigationGeneration.next()
        // 旧世代的 finally 不再拥有这些指示器；由新世代按需重新开启。
        loading = false
        loadingHome = false
        loadingNodes = false
        loadingDocument = false
        return generation
    }

    private fun isCurrentNavigation(generation: Long, spaceId: String? = null): Boolean =
        navigationGeneration.isCurrent(generation) && (spaceId == null || selectedSpaceId == spaceId)

    private companion object {
        const val HOME_LIMIT = 12
    }
}
