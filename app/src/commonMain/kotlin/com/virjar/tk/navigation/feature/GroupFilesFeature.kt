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

internal data class GroupFileLocation(val chatId: String, val parentId: String?)

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

    private val entriesGate = GroupRequestGate<GroupFileLocation>()
    private val versionsGate = GroupRequestGate<Pair<String, String>>()

    val parentId: String? get() = path.lastOrNull()?.entryId

    internal suspend fun open(chatId: String) {
        this.chatId = chatId
        path = emptyList()
        entries = emptyList()
        selectedFile = null
        versions = emptyList()
        loading = false
        versionsGate.invalidate()
        refresh()
    }

    suspend fun refresh() {
        val location = currentLocation() ?: return
        refresh(location)
    }

    private suspend fun refresh(location: GroupFileLocation) {
        if (currentLocation() != location) return
        val token = entriesGate.begin(location)
        loading = true
        try {
            val loaded = session.groupFileRepo.list(location.chatId, location.parentId).getOrThrow()
            if (!entriesGate.isCurrent(token) || currentLocation() != location) return
            if (loaded.any { it.chatId != location.chatId || it.parentId != location.parentId }) {
                reportError(IllegalStateException("群文件响应身份不匹配"), "加载群文件失败")
                return
            }
            entries = loaded
        } catch (e: Exception) {
            if (entriesGate.isCurrent(token)) reportError(e, "加载群文件失败")
        } finally {
            if (entriesGate.isCurrent(token)) loading = false
        }
    }

    fun enter(folder: GroupFileEntry) {
        val location = currentLocation() ?: return
        if (folder.kind != GroupFileEntry.KIND_FOLDER ||
            folder.chatId != location.chatId || folder.parentId != location.parentId
        ) return
        scope.launch {
            if (currentLocation() != location) return@launch
            path = path + folder
            selectedFile = null
            versions = emptyList()
            versionsGate.invalidate()
            refresh()
        }
    }

    fun up() {
        val location = currentLocation() ?: return
        val targetPath = path.dropLast(1)
        if (path.isEmpty()) return
        scope.launch {
            if (currentLocation() != location) return@launch
            path = targetPath
            selectedFile = null
            versions = emptyList()
            versionsGate.invalidate()
            refresh()
        }
    }

    fun createFolder(name: String) {
        val location = currentLocation() ?: return
        scope.launch {
            if (currentLocation() != location) return@launch
            mutate(location, "创建目录失败") { target ->
                session.groupFileRepo.createFolder(target.chatId, target.parentId, name).getOrThrow()
            }
        }
    }

    suspend fun publish(name: String, attachment: Attachment): Boolean {
        val location = currentLocation() ?: return false
        return try {
            session.groupFileRepo.createFile(
                location.chatId,
                location.parentId,
                name,
                attachment,
            ).getOrThrow()
            if (currentLocation() == location) refresh(location)
            true
        } catch (e: Exception) {
            reportError(e, "发布群文件失败")
            false
        }
    }

    suspend fun addVersion(entry: GroupFileEntry, attachment: Attachment): Boolean {
        val location = currentLocation() ?: return false
        if (entry.chatId != location.chatId) return false
        return try {
            session.groupFileRepo.addVersion(
                location.chatId,
                entry.entryId,
                attachment,
                entry.revision,
            ).getOrThrow()
            if (currentLocation() == location) {
                refresh(location)
                showVersions(entries.firstOrNull { it.entryId == entry.entryId })
            }
            true
        } catch (e: Exception) {
            reportError(e, "上传新版本失败")
            false
        }
    }

    fun rename(entry: GroupFileEntry, name: String) {
        val location = currentLocation() ?: return
        if (entry.chatId != location.chatId || entry.parentId != location.parentId) return
        scope.launch {
            if (currentLocation() != location) return@launch
            mutate(location, "重命名失败") { target ->
                session.groupFileRepo.rename(target.chatId, entry.entryId, name, entry.revision).getOrThrow()
            }
        }
    }

    fun delete(entry: GroupFileEntry) {
        val location = currentLocation() ?: return
        if (entry.chatId != location.chatId || entry.parentId != location.parentId) return
        scope.launch {
            if (currentLocation() != location) return@launch
            mutate(location, "删除失败") { target ->
                session.groupFileRepo.delete(target.chatId, entry.entryId, entry.revision).getOrThrow()
            }
        }
    }

    fun showVersions(entry: GroupFileEntry?) {
        val targetChatId = chatId
        if (entry != null && (targetChatId == null || entry.chatId != targetChatId)) return
        selectedFile = entry
        versions = emptyList()
        if (entry == null) {
            versionsGate.invalidate()
            return
        }
        val token = versionsGate.begin(entry.chatId to entry.entryId)
        scope.launch {
            try {
                val loaded = session.groupFileRepo.listVersions(entry.chatId, entry.entryId).getOrThrow()
                if (versionsGate.isCurrent(token) &&
                    chatId == entry.chatId && selectedFile?.entryId == entry.entryId &&
                    loaded.all { it.entryId == entry.entryId }
                ) {
                    versions = loaded
                }
            } catch (e: Exception) {
                if (versionsGate.isCurrent(token)) reportError(e, "加载文件版本失败")
            }
        }
    }

    fun reportUploadError(error: Throwable) {
        reportError(error, "上传群文件失败")
    }

    private suspend fun mutate(
        location: GroupFileLocation,
        fallback: String,
        action: suspend (GroupFileLocation) -> Unit,
    ) {
        try {
            action(location)
            if (currentLocation() == location) refresh(location)
        } catch (e: Exception) {
            if (currentLocation() == location) reportError(e, fallback)
        }
    }

    private fun currentLocation(): GroupFileLocation? = chatId?.let { GroupFileLocation(it, parentId) }
}
