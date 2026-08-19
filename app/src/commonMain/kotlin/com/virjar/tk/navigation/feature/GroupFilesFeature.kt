package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 群文件页面状态。v1 采用打开/修改后拉取，不伪装成实时同步。 */
class GroupFilesFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
) {
    var chatId by mutableStateOf<String?>(null)
        private set
    var path by mutableStateOf(emptyList<GroupFileEntry>())
        private set
    var entries by mutableStateOf(emptyList<GroupFileEntry>())
        private set
    var versions by mutableStateOf(emptyList<GroupFileVersion>())
        private set
    var selectedFile by mutableStateOf<GroupFileEntry?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    val parentId: String? get() = path.lastOrNull()?.entryId

    internal suspend fun open(chatId: String) {
        this.chatId = chatId
        path = emptyList()
        selectedFile = null
        versions = emptyList()
        refresh()
    }

    suspend fun refresh() {
        val targetChatId = chatId ?: return
        loading = true
        try {
            entries = session.groupFileRepo.list(targetChatId, parentId).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "加载群文件失败")
        } finally {
            loading = false
        }
    }

    fun enter(folder: GroupFileEntry) = scope.launch {
        if (folder.kind != GroupFileEntry.KIND_FOLDER) return@launch
        path = path + folder
        selectedFile = null
        versions = emptyList()
        refresh()
    }

    fun up() = scope.launch {
        if (path.isEmpty()) return@launch
        path = path.dropLast(1)
        selectedFile = null
        versions = emptyList()
        refresh()
    }

    fun createFolder(name: String) = scope.launch {
        mutate("创建目录失败") { chat -> session.groupFileRepo.createFolder(chat, parentId, name).getOrThrow() }
    }

    suspend fun publish(name: String, attachment: Attachment): Boolean = try {
        val targetChatId = chatId ?: return false
        session.groupFileRepo.createFile(targetChatId, parentId, name, attachment).getOrThrow()
        refresh()
        true
    } catch (e: Exception) {
        reportError(e, "发布群文件失败")
        false
    }

    suspend fun addVersion(entry: GroupFileEntry, attachment: Attachment): Boolean = try {
        val targetChatId = chatId ?: return false
        session.groupFileRepo.addVersion(targetChatId, entry.entryId, attachment, entry.revision).getOrThrow()
        refresh()
        showVersions(entries.firstOrNull { it.entryId == entry.entryId })
        true
    } catch (e: Exception) {
        reportError(e, "上传新版本失败")
        false
    }

    fun rename(entry: GroupFileEntry, name: String) = scope.launch {
        mutate("重命名失败") { chat ->
            session.groupFileRepo.rename(chat, entry.entryId, name, entry.revision).getOrThrow()
        }
    }

    fun delete(entry: GroupFileEntry) = scope.launch {
        mutate("删除失败") { chat ->
            session.groupFileRepo.delete(chat, entry.entryId, entry.revision).getOrThrow()
        }
    }

    fun showVersions(entry: GroupFileEntry?) = scope.launch {
        selectedFile = entry
        versions = if (entry == null) emptyList() else try {
            session.groupFileRepo.listVersions(entry.chatId, entry.entryId).getOrThrow()
        } catch (e: Exception) {
            reportError(e, "加载文件版本失败")
            emptyList()
        }
    }

    fun reportUploadError(error: Throwable) {
        reportError(error, "上传群文件失败")
    }

    private suspend fun mutate(fallback: String, action: suspend (String) -> Unit) {
        val targetChatId = chatId ?: return
        try {
            action(targetChatId)
            refresh()
        } catch (e: Exception) {
            reportError(e, fallback)
        }
    }
}
