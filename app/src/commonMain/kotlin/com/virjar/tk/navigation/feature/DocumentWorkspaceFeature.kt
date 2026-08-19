package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class DocumentFolderCrumb(val nodeId: String?, val name: String)

/** 一个打开的文档标签。草稿与服务端基线分离，切换空间或标签不会丢失未保存内容。 */
data class DocumentTabState(
    val tabId: String,
    val documentId: String?,
    val spaceId: String,
    val parentId: String?,
    val savedTitle: String,
    val savedMarkdown: String,
    val draftTitle: String,
    val draftMarkdown: String,
    val revision: Long?,
    val dirty: Boolean = false,
    val creating: Boolean = false,
) {
    companion object {
        fun from(document: Document) = DocumentTabState(
            tabId = document.documentId,
            documentId = document.documentId,
            spaceId = document.spaceId,
            parentId = document.parentId,
            savedTitle = document.title,
            savedMarkdown = document.markdown,
            draftTitle = document.title,
            draftMarkdown = document.markdown,
            revision = document.revision,
        )
    }
}

/** 企业文档工作台状态；不依赖聊天上下文，可同时保留来自多个空间的文档标签。 */
class DocumentWorkspaceFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
) {
    var spaces by mutableStateOf(emptyList<DocumentSpace>())
        private set
    var selectedSpaceId by mutableStateOf<String?>(null)
        private set
    val selectedSpace get() = spaces.firstOrNull { it.spaceId == selectedSpaceId }
    var nodes by mutableStateOf(emptyList<DocumentNode>())
        private set
    var folderPath by mutableStateOf(listOf(DocumentFolderCrumb(null, "全部文档")))
        private set
    val currentFolderId get() = folderPath.lastOrNull()?.nodeId

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
    var loadingNodes by mutableStateOf(false)
        private set
    var loadingDocument by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set

    private var draftCounter = 0L

    suspend fun open() {
        loading = true
        try {
            spaces = session.documentRepo.listSpaces().getOrThrow()
            loadOrganizationDirectory()
            val target = selectedSpaceId?.takeIf { id -> spaces.any { it.spaceId == id } }
                ?: spaces.firstOrNull()?.spaceId
            if (target != null) selectSpaceNow(target) else clearSpaceSelection()
        } catch (e: Exception) {
            reportError(e, "加载文档空间失败")
        } finally {
            loading = false
        }
    }

    fun refresh() = scope.launch { open() }

    fun createSpace(name: String, description: String?) = scope.launch {
        try {
            val created = session.documentRepo.createSpace(name, description).getOrThrow()
            spaces = listOf(created) + spaces.filterNot { it.spaceId == created.spaceId }
            selectSpaceNow(created.spaceId)
        } catch (e: Exception) {
            reportError(e, "创建文档空间失败")
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
            spaces.firstOrNull()?.spaceId?.let { selectSpaceNow(it) } ?: clearSpaceSelection()
        } catch (e: Exception) {
            reportError(e, "归档文档空间失败")
        }
    }

    fun selectSpace(spaceId: String) = scope.launch {
        try {
            selectSpaceNow(spaceId)
        } catch (e: Exception) {
            reportError(e, "打开文档空间失败")
        }
    }

    fun enterFolder(folder: DocumentNode) = scope.launch {
        if (folder.nodeType != DocumentNode.TYPE_FOLDER) return@launch
        folderPath = folderPath + DocumentFolderCrumb(folder.nodeId, folder.name)
        loadNodes()
    }

    fun openCrumb(index: Int) = scope.launch {
        if (index !in folderPath.indices) return@launch
        folderPath = folderPath.take(index + 1)
        loadNodes()
    }

    fun createFolder(name: String) = scope.launch {
        val spaceId = selectedSpaceId ?: return@launch
        try {
            session.documentRepo.createFolder(spaceId, currentFolderId, name).getOrThrow()
            loadNodes()
        } catch (e: Exception) {
            reportError(e, "创建文件夹失败")
        }
    }

    fun beginDocument() {
        val spaceId = selectedSpaceId ?: return
        val tabId = "draft-${++draftCounter}-${System.currentTimeMillis()}"
        val tab = DocumentTabState(
            tabId = tabId,
            documentId = null,
            spaceId = spaceId,
            parentId = currentFolderId,
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
    }

    fun openDocument(node: DocumentNode) = scope.launch {
        if (node.nodeType != DocumentNode.TYPE_DOCUMENT) return@launch
        tabs.firstOrNull { it.documentId == node.nodeId }?.let {
            activeTabId = it.tabId
            return@launch
        }
        loadingDocument = true
        try {
            val document = session.documentRepo.getDocument(node.spaceId, node.nodeId).getOrThrow()
            val tab = DocumentTabState.from(document)
            tabs = tabs + tab
            activeTabId = tab.tabId
            closeHistory()
        } catch (e: Exception) {
            reportError(e, "加载文档失败")
        } finally {
            loadingDocument = false
        }
    }

    fun selectTab(tabId: String) {
        if (tabs.any { it.tabId == tabId }) activeTabId = tabId
        closeHistory()
    }

    fun updateDraft(tabId: String, title: String, markdown: String, dirty: Boolean) {
        tabs = tabs.map { tab ->
            if (tab.tabId == tabId) tab.copy(
                draftTitle = title,
                draftMarkdown = markdown,
                dirty = dirty || tab.creating,
            ) else tab
        }
    }

    fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.tabId == tabId }
        if (index < 0) return
        tabs = tabs.filterNot { it.tabId == tabId }
        if (activeTabId == tabId) activeTabId = tabs.getOrNull((index - 1).coerceAtLeast(0))?.tabId
            ?: tabs.firstOrNull()?.tabId
        closeHistory()
    }

    fun saveActive() = scope.launch {
        val current = activeTab ?: return@launch
        saving = true
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
            val replacement = DocumentTabState.from(saved)
            tabs = tabs.map { if (it.tabId == current.tabId) replacement else it }
            activeTabId = replacement.tabId
            if (selectedSpaceId == saved.spaceId && currentFolderId == saved.parentId) loadNodes()
            closeHistory()
        } catch (e: Exception) {
            reportError(e, "保存文档失败")
        } finally {
            saving = false
        }
    }

    fun deleteActive() = scope.launch {
        val current = activeTab ?: return@launch
        if (current.documentId == null || current.revision == null) {
            closeTab(current.tabId)
            return@launch
        }
        try {
            session.documentRepo.deleteNode(current.spaceId, current.documentId, current.revision).getOrThrow()
            closeTab(current.tabId)
            if (selectedSpaceId == current.spaceId && currentFolderId == current.parentId) loadNodes()
        } catch (e: Exception) {
            reportError(e, "删除文档失败")
        }
    }

    fun showHistory() = scope.launch {
        val current = activeTab ?: return@launch
        val documentId = current.documentId ?: return@launch
        try {
            revisions = session.documentRepo.listRevisions(current.spaceId, documentId).getOrThrow()
            revisionPreview = null
        } catch (e: Exception) {
            reportError(e, "加载版本历史失败")
        }
    }

    fun openRevision(summary: DocumentRevisionSummary) = scope.launch {
        val current = activeTab ?: return@launch
        val documentId = current.documentId ?: return@launch
        try {
            revisionPreview = session.documentRepo.getRevision(current.spaceId, documentId, summary.revision).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "加载文档版本失败")
        }
    }

    fun restorePreview() = scope.launch {
        val current = activeTab ?: return@launch
        val documentId = current.documentId ?: return@launch
        val preview = revisionPreview ?: return@launch
        saving = true
        try {
            val restored = session.documentRepo.updateDocument(
                current.spaceId,
                documentId,
                preview.title,
                preview.markdown,
                current.revision ?: return@launch,
            ).getOrThrow()
            val replacement = DocumentTabState.from(restored)
            tabs = tabs.map { if (it.tabId == current.tabId) replacement else it }
            activeTabId = replacement.tabId
            revisionPreview = null
            revisions = session.documentRepo.listRevisions(current.spaceId, documentId).getOrThrow()
            if (selectedSpaceId == current.spaceId && currentFolderId == current.parentId) loadNodes()
        } catch (e: Exception) {
            reportError(e, "恢复文档版本失败")
        } finally {
            saving = false
        }
    }

    fun closeHistory() {
        revisions = emptyList()
        revisionPreview = null
    }

    fun closeRevisionPreview() {
        revisionPreview = null
    }

    fun refreshGrants() = scope.launch {
        val space = selectedSpace ?: return@launch
        if (space.myRole < DocumentSpace.ROLE_ADMIN) {
            grants = emptyList()
            return@launch
        }
        try {
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

    private suspend fun selectSpaceNow(spaceId: String) {
        require(spaces.any { it.spaceId == spaceId }) { "文档空间不存在" }
        selectedSpaceId = spaceId
        folderPath = listOf(DocumentFolderCrumb(null, "全部文档"))
        loadNodes()
        val space = selectedSpace
        grants = if (space != null && space.myRole >= DocumentSpace.ROLE_ADMIN) {
            session.documentRepo.listGrants(spaceId).getOrThrow()
        } else emptyList()
    }

    private suspend fun loadNodes() {
        val spaceId = selectedSpaceId ?: return
        loadingNodes = true
        try {
            nodes = session.documentRepo.listNodes(spaceId, currentFolderId).getOrThrow()
        } finally {
            loadingNodes = false
        }
    }

    private suspend fun loadOrganizationDirectory() {
        organizationUnits = session.organizationRepo.listUnits().getOrThrow()
        val roots = organizationUnits.filter { it.parentId == null }
        organizationMembers = roots.flatMap { root ->
            session.organizationRepo.listMembers(root.unitId, recursive = true).getOrThrow()
        }.distinctBy { it.uid }
    }

    private fun clearSpaceSelection() {
        selectedSpaceId = null
        nodes = emptyList()
        folderPath = listOf(DocumentFolderCrumb(null, "全部文档"))
        grants = emptyList()
    }
}
