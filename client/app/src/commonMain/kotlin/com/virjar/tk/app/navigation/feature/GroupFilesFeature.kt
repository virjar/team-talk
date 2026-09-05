package com.virjar.tk.app.navigation.feature

import com.virjar.tk.shared.AppError
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.PendingGroupFileCommandKind
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.GroupFileCommandCompletion
import com.virjar.tk.shared.repository.GroupFileCommandCompletionStatus
import com.virjar.tk.shared.repository.GroupFileCommandSubmission
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.startActionAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class GroupFileLocation(val chatId: String, val parentId: String?)

/** 群文件页面状态。当前实现采用打开/修改后拉取，不伪装成实时同步。 */
class GroupFilesFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val localData: UiLocalDataBoundary,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    private val reportFeedback: (UserFeedbackCode) -> Unit = {},
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

    /** true=当前 entries 来自本地投影且尚未被权威页确认（离线 stale 展示）。 */
    var stale by mutableStateOf(false)
        private set

    private val entriesGate = LatestRequestGate<GroupFileLocation>()

    /** 当前 chat 根目录的投影流收集；切换/关闭 chat 时取消。 */
    private var projectionJob: kotlinx.coroutines.Job? = null
    private val versionsGate = LatestRequestGate<Pair<String, String>>()

    init {
        scope.launch {
            session.groupFileRecoveryCompletions.collect(::convergeCompletedCommand)
        }

    }

    val parentId: String? get() = path.lastOrNull()?.entryId

    internal suspend fun open(chatId: String) {
        this.chatId = chatId
        path = emptyList()
        selectedFile = null
        versions = emptyList()
        loading = false
        versionsGate.invalidate()
        // CONTENT-01：缓存优先。离线旧缓存立即渲染（stale 指示由 stale 标志暴露），
        // 权威页随后原子替换投影并收敛 entries。
        entries = session.groupFileRepo.cachedDirectory(chatId, null).orEmpty()
        observeProjection(chatId)
        refresh()
    }

    /**
     * CONTENT-01：行级投影流实时更新根目录列表——GROUP_FILE_CHANGED 事件与目录
     * 快照都通过同一投影发布；权威页刷新仍负责 stale 清除。
     */
    private fun observeProjection(chatId: String) {
        projectionJob?.cancel()
        projectionJob = scope.launch {
            try {
                localData.projection {
                    session.localCache.observeGroupFileEntries(chatId, null)
                }.collect { projected ->
                    // 只在仍停留根目录且同一 chat 时应用（子目录导航由快照路径管理）。
                    if (this@GroupFilesFeature.chatId == chatId && path.isEmpty()) {
                        entries = projected
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.virjar.tk.shared.log.AppLog.trace(
                    "GroupFilesFeature",
                    "projection observe unavailable: ${failure::class.simpleName}",
                )
            }
        }
    }

    suspend fun refresh() {
        val location = currentLocation() ?: return
        refresh(location)
    }

    private suspend fun refresh(location: GroupFileLocation) {
        if (currentLocation() != location) return
        val token = entriesGate.begin(location)
        loading = true
        val hasCachedEntries = entries.isNotEmpty()
        try {
            val loaded = session.groupFileRepo.list(location.chatId, location.parentId).getOrThrow()
            if (!entriesGate.isCurrent(token) || currentLocation() != location) return
            if (loaded.any { it.chatId != location.chatId || it.parentId != location.parentId }) {
                reportError(IllegalStateException("群文件响应身份不匹配"), "加载群文件失败")
                return
            }
            entries = loaded
            stale = false
        } catch (cancelled: CancellationException) {
            if (entriesGate.isCurrent(token)) entriesGate.invalidate()
            throw cancelled
        } catch (e: Exception) {
            if (entriesGate.isCurrent(token)) {
                when ((e as? com.virjar.tk.shared.AppError.Business)?.code) {
                    403, 404 -> {
                        // 权威 403/404：原子清空投影与页面（成员资格/群已不存在）。
                        session.groupFileRepo.purgeProjectionAfterFailure(location.chatId)
                        entries = emptyList()
                        path = emptyList()
                        stale = false
                        reportFeedback(UserFeedbackCode.OPERATION_FAILED)
                        reportError(e, "群文件不可访问")
                    }
                    else -> {
                        if (hasCachedEntries) {
                            // 离线/网络失败：保留缓存做 stale 展示，不作为远端操作依据。
                            com.virjar.tk.shared.log.AppLog.trace(
                                "GroupFilesFeature",
                                "directory refresh stale: ${e::class.simpleName}: ${e.message}",
                            )
                            stale = true
                        } else {
                            reportError(e, "加载群文件失败")
                        }
                    }
                }
            }
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
            entries = emptyList()
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
            entries = emptyList()
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
                localData.run {
                    session.groupFileRepo.createRecoverableFolder(
                        target.chatId,
                        target.parentId,
                        name,
                    ).getOrThrow()
                }
            }
        }
    }

    suspend fun publish(name: String, attachment: Attachment): Boolean {
        val location = currentLocation() ?: return false
        val attempt = telemetry.startActionAttempt(
            ClientUiPage.GROUP_FILES,
            ClientUiAction.PUBLISH_GROUP_FILE,
        )
        return try {
            val submission = localData.run {
                session.groupFileRepo.createRecoverableFile(
                    location.chatId,
                    location.parentId,
                    name,
                    attachment,
                ).getOrThrow()
            }
            if (submission == GroupFileCommandSubmission.PENDING) attempt.queue() else attempt.succeed()
            convergeForegroundSubmission(submission, location)
            true
        } catch (cancelled: CancellationException) {
            attempt.cancel()
            throw cancelled
        } catch (e: Exception) {
            attempt.fail()
            if (currentLocation() == location) reportError(e, "发布群文件失败")
            false
        }
    }

    suspend fun addVersion(entry: GroupFileEntry, attachment: Attachment): Boolean {
        val location = currentLocation() ?: return false
        if (entry.chatId != location.chatId) return false
        val attempt = telemetry.startActionAttempt(
            ClientUiPage.GROUP_FILES,
            ClientUiAction.PUBLISH_GROUP_FILE,
        )
        return try {
            val submission = localData.run {
                session.groupFileRepo.addRecoverableVersion(
                    location.chatId,
                    entry.entryId,
                    attachment,
                    entry.revision,
                ).getOrThrow()
            }
            if (submission == GroupFileCommandSubmission.PENDING) {
                attempt.queue()
                reportPendingSubmission()
            } else if (currentLocation() == location) {
                attempt.succeed()
                refresh(location)
                showVersions(entries.firstOrNull { it.entryId == entry.entryId })
            } else {
                attempt.succeed()
            }
            true
        } catch (cancelled: CancellationException) {
            attempt.cancel()
            throw cancelled
        } catch (e: Exception) {
            attempt.fail()
            if (currentLocation() == location) reportError(e, "上传新版本失败")
            false
        }
    }

    fun rename(entry: GroupFileEntry, name: String) {
        val location = currentLocation() ?: return
        if (entry.chatId != location.chatId || entry.parentId != location.parentId) return
        scope.launch {
            if (currentLocation() != location) return@launch
            mutate(location, "重命名失败") { target ->
                localData.run {
                    session.groupFileRepo.renameRecoverable(
                        chatId = target.chatId,
                        parentId = target.parentId,
                        entryId = entry.entryId,
                        name = name,
                        expectedRevision = entry.revision,
                    ).getOrThrow()
                }
            }
        }
    }

    fun delete(entry: GroupFileEntry) {
        val location = currentLocation() ?: return
        if (entry.chatId != location.chatId || entry.parentId != location.parentId) return
        scope.launch {
            if (currentLocation() != location) return@launch
            mutate(location, "删除失败") { target ->
                localData.run {
                    session.groupFileRepo.deleteRecoverable(
                        chatId = target.chatId,
                        parentId = target.parentId,
                        entryId = entry.entryId,
                        expectedRevision = entry.revision,
                    ).getOrThrow()
                }
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (versionsGate.isCurrent(token)) reportError(e, "加载文件版本失败")
            }
        }
    }

    fun reportUploadError(error: Throwable) {
        // FileRepository 已经为 HTTP 401 提交了一个确切 bearer 的终止点。通过通用 session 回调
        // 重放它，可能退役一个刚轮换的凭据。
        if (shouldReportGroupFileUploadFailure(error)) reportError(error, "上传群文件失败")
    }

    private suspend fun mutate(
        location: GroupFileLocation,
        fallback: String,
        action: suspend (GroupFileLocation) -> GroupFileCommandSubmission,
    ) {
        try {
            convergeForegroundSubmission(action(location), location)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (currentLocation() == location) reportError(e, fallback)
        }
    }

    private suspend fun convergeForegroundSubmission(
        submission: GroupFileCommandSubmission,
        location: GroupFileLocation,
    ) {
        if (submission == GroupFileCommandSubmission.PENDING) {
            reportPendingSubmission()
        } else if (currentLocation() == location) {
            refresh(location)
        }
    }

    private fun reportPendingSubmission() {
        reportFeedback(UserFeedbackCode.RELIABLE_COMMAND_PENDING)
    }

    private suspend fun convergeCompletedCommand(completion: GroupFileCommandCompletion) {
        if (completion.status == GroupFileCommandCompletionStatus.REJECTED) {
            reportFeedback(UserFeedbackCode.RELIABLE_COMMAND_REJECTED)
            currentLocation()?.takeIf { it.chatId == completion.chatId }?.let { refresh(it) }
            return
        }
        val location = currentLocation() ?: return
        when (
            recoveredGroupFilePathConvergence(
                completion = completion,
                location = location,
                pathEntryIds = path.map(GroupFileEntry::entryId),
            )
        ) {
            RecoveredGroupFilePathConvergence.REFRESH_RENAMED_ENTRY -> {
                refreshRecoveredPathEntry(completion)
                return
            }

            RecoveredGroupFilePathConvergence.LEAVE_DELETED_BRANCH -> {
                val deletedIndex = path.indexOfFirst { it.entryId == completion.entryId }
                if (deletedIndex < 0) return
                path = path.take(deletedIndex)
                entries = emptyList()
                selectedFile = null
                versions = emptyList()
                versionsGate.invalidate()
                currentLocation()?.let { refresh(it) }
                return
            }

            RecoveredGroupFilePathConvergence.NONE -> Unit
        }
        val affectsCurrentLocation = shouldConvergeRecoveredGroupFileCommand(
            completion = completion,
            location = location,
            selectedEntryId = selectedFile?.entryId,
            visibleEntryIds = entries.mapTo(hashSetOf(), GroupFileEntry::entryId),
        )
        if (!affectsCurrentLocation) return
        val restoreVersions = selectedFile?.entryId == completion.entryId
        refresh(location)
        if (restoreVersions && currentLocation() == location) {
            showVersions(entries.firstOrNull { it.entryId == completion.entryId })
        }
    }

    private suspend fun refreshRecoveredPathEntry(completion: GroupFileCommandCompletion) {
        val originalPathIds = path.map(GroupFileEntry::entryId)
        val pathIndex = originalPathIds.indexOf(completion.entryId)
        if (pathIndex < 0) return
        try {
            val siblings = session.groupFileRepo.list(completion.chatId, completion.parentId).getOrThrow()
            if (chatId != completion.chatId || path.map(GroupFileEntry::entryId) != originalPathIds) return
            if (siblings.any { it.chatId != completion.chatId || it.parentId != completion.parentId }) {
                reportError(IllegalStateException("群文件路径响应身份不匹配"), "刷新群文件路径失败")
                return
            }
            val renamed = siblings.firstOrNull {
                it.entryId == completion.entryId && it.kind == GroupFileEntry.KIND_FOLDER
            }
            if (renamed != null) {
                path = path.toMutableList().also { it[pathIndex] = renamed }
                currentLocation()?.let { refresh(it) }
            } else {
                // 重命名 ACK 可能在另一个参与者删除该文件夹之后到达。
                // 不要把用户留在服务器不再暴露的分支里。
                path = path.take(pathIndex)
                entries = emptyList()
                selectedFile = null
                versions = emptyList()
                versionsGate.invalidate()
                currentLocation()?.let { refresh(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (chatId == completion.chatId && path.map(GroupFileEntry::entryId) == originalPathIds) {
                reportError(e, "刷新群文件路径失败")
            }
        }
    }

    private fun currentLocation(): GroupFileLocation? = chatId?.let { GroupFileLocation(it, parentId) }
}

internal fun shouldReportGroupFileUploadFailure(error: Throwable): Boolean =
    error !is AppError.AuthExpired

internal enum class RecoveredGroupFilePathConvergence {
    NONE,
    REFRESH_RENAMED_ENTRY,
    LEAVE_DELETED_BRANCH,
}

/** 一次恢复的文件夹修改也必须收敛一个打开的 breadcrumb 分支。 */
internal fun recoveredGroupFilePathConvergence(
    completion: GroupFileCommandCompletion,
    location: GroupFileLocation,
    pathEntryIds: List<String>,
): RecoveredGroupFilePathConvergence {
    if (completion.chatId != location.chatId || completion.entryId !in pathEntryIds) {
        return RecoveredGroupFilePathConvergence.NONE
    }
    return when (completion.kind) {
        PendingGroupFileCommandKind.RENAME -> RecoveredGroupFilePathConvergence.REFRESH_RENAMED_ENTRY
        PendingGroupFileCommandKind.DELETE -> RecoveredGroupFilePathConvergence.LEAVE_DELETED_BRANCH
        PendingGroupFileCommandKind.CREATE_FOLDER,
        PendingGroupFileCommandKind.CREATE_FILE,
        PendingGroupFileCommandKind.ADD_VERSION,
        -> RecoveredGroupFilePathConvergence.NONE
    }
}

/** 迟到的恢复只能刷新命令可能影响的那个确切的页面或文件投影。 */
internal fun shouldConvergeRecoveredGroupFileCommand(
    completion: GroupFileCommandCompletion,
    location: GroupFileLocation,
    selectedEntryId: String?,
    visibleEntryIds: Set<String>,
): Boolean {
    if (completion.chatId != location.chatId) return false
    return when (completion.kind) {
        PendingGroupFileCommandKind.CREATE_FOLDER,
        PendingGroupFileCommandKind.CREATE_FILE,
        PendingGroupFileCommandKind.RENAME,
        PendingGroupFileCommandKind.DELETE,
        -> completion.parentId == location.parentId

        PendingGroupFileCommandKind.ADD_VERSION ->
            selectedEntryId == completion.entryId || completion.entryId in visibleEntryIds
    }
}
