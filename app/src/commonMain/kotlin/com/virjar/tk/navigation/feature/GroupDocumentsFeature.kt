package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 群协作文档状态。v1 在打开和变更后拉取，并通过 revision 显式暴露冲突。 */
class GroupDocumentsFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
) {
    var chatId by mutableStateOf<String?>(null)
        private set
    var documents by mutableStateOf(emptyList<DocumentSummary>())
        private set
    var selected by mutableStateOf<Document?>(null)
        private set
    var creating by mutableStateOf(false)
        private set
    var revisions by mutableStateOf(emptyList<DocumentRevisionSummary>())
        private set
    var revisionPreview by mutableStateOf<DocumentRevision?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingDocument by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set

    internal suspend fun open(chatId: String) {
        this.chatId = chatId
        selected = null
        creating = false
        closeHistory()
        refresh()
    }

    suspend fun refresh() {
        val targetChatId = chatId ?: return
        loading = true
        try {
            documents = session.documentRepo.list(Document.SCOPE_GROUP_CHAT, targetChatId).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "加载群文档失败")
        } finally {
            loading = false
        }
    }

    fun beginCreate() {
        selected = null
        creating = true
        closeHistory()
    }

    fun openDocument(summary: DocumentSummary) = scope.launch {
        val targetChatId = chatId ?: return@launch
        loadingDocument = true
        try {
            selected = session.documentRepo.get(Document.SCOPE_GROUP_CHAT, targetChatId, summary.documentId).getOrThrow()
            creating = false
            closeHistory()
        } catch (e: Exception) {
            reportError(e, "加载文档失败")
        } finally {
            loadingDocument = false
        }
    }

    fun closeEditor() {
        selected = null
        creating = false
        closeHistory()
    }

    fun save(title: String, markdown: String) = scope.launch {
        val targetChatId = chatId ?: return@launch
        saving = true
        try {
            val current = selected
            val saved = if (creating || current == null) {
                session.documentRepo.create(Document.SCOPE_GROUP_CHAT, targetChatId, title, markdown).getOrThrow()
            } else {
                session.documentRepo.update(
                    Document.SCOPE_GROUP_CHAT,
                    targetChatId,
                    current.documentId,
                    title,
                    markdown,
                    current.revision,
                ).getOrThrow()
            }
            selected = saved
            creating = false
            closeHistory()
            refresh()
        } catch (e: Exception) {
            reportError(e, "保存文档失败")
        } finally {
            saving = false
        }
    }

    fun deleteCurrent() = scope.launch {
        val targetChatId = chatId ?: return@launch
        val current = selected ?: return@launch
        try {
            session.documentRepo.delete(
                Document.SCOPE_GROUP_CHAT,
                targetChatId,
                current.documentId,
                current.revision,
            ).getOrThrow()
            closeEditor()
            refresh()
        } catch (e: Exception) {
            reportError(e, "删除文档失败")
        }
    }

    fun showHistory() = scope.launch {
        val targetChatId = chatId ?: return@launch
        val current = selected ?: return@launch
        try {
            revisions = session.documentRepo.listRevisions(
                Document.SCOPE_GROUP_CHAT,
                targetChatId,
                current.documentId,
            ).getOrThrow()
            revisionPreview = null
        } catch (e: Exception) {
            reportError(e, "加载版本历史失败")
        }
    }

    fun openRevision(summary: DocumentRevisionSummary) = scope.launch {
        val targetChatId = chatId ?: return@launch
        val current = selected ?: return@launch
        try {
            revisionPreview = session.documentRepo.getRevision(
                Document.SCOPE_GROUP_CHAT,
                targetChatId,
                current.documentId,
                summary.revision,
            ).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "加载文档版本失败")
        }
    }

    fun restorePreview() = scope.launch {
        val targetChatId = chatId ?: return@launch
        val current = selected ?: return@launch
        val preview = revisionPreview ?: return@launch
        saving = true
        try {
            val restored = session.documentRepo.update(
                Document.SCOPE_GROUP_CHAT,
                targetChatId,
                current.documentId,
                preview.title,
                preview.markdown,
                current.revision,
            ).getOrThrow()
            selected = restored
            revisionPreview = null
            revisions = session.documentRepo.listRevisions(
                Document.SCOPE_GROUP_CHAT,
                targetChatId,
                restored.documentId,
            ).getOrThrow()
            refresh()
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
}
