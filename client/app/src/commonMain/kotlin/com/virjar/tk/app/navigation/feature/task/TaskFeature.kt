package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.shared.platform.platformCurrentTimeMillis
import com.virjar.tk.shared.platform.platformRandomUuid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.viewmodel.ConversationViewModel
import com.virjar.tk.protocol.model.*
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.PendingTaskCommand
import com.virjar.tk.shared.client.TaskQueryKey
import com.virjar.tk.shared.client.TaskPageKey
import com.virjar.tk.shared.client.TaskReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 会话内唯一任务工作台；已确认投影和待发送意图由 SDK 持久化，本类只持有交互状态。 */
class TaskFeature internal constructor(
    internal val session: ClientSession,
    internal val scope: CoroutineScope,
    internal val localData: UiLocalDataBoundary,
    internal val conversations: ConversationViewModel,
) {
    internal val repo get() = session.taskRepo
    var supportsTaskDetails by mutableStateOf(repo.supportsTaskDetails)
        private set
    val attention = TaskAttentionState(repo, scope, localData)
    val myUid: String get() = session.ownerUid
    internal var opened = false
    var workspaceRequested by mutableStateOf(false)
        private set
    internal var listOwner = 0L
    internal var detailOwner = 0L
    internal var pageKeys = listOf(TaskPageKey(TaskPolicy.VIEW_ASSIGNED))
    internal var queryKeys = listOf(TaskQueryKey(TaskQuery.ASSIGNED))
    var groupFilter by mutableStateOf<String?>(null)
        private set
    var onlyOpen by mutableStateOf(false)
        private set
    var onlyStarted by mutableStateOf(false)
        private set
    var summary by mutableStateOf<TaskSummary?>(null)
        private set
    var details by mutableStateOf<TaskDetails?>(null)
        private set
    var deferrals by mutableStateOf(emptyMap<Long, TaskDeferral>())
        private set
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var auditJob: Job? = null
    private var observedGeneration: Long? = null

    var view by mutableStateOf(TaskPolicy.VIEW_ASSIGNED)
        private set
    var items by mutableStateOf(emptyList<WorkTask>())
        private set
    var nextCursor by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var stale by mutableStateOf(false)
        private set
    var listError by mutableStateOf<String?>(null)
        private set
    var selectedTaskId by mutableStateOf<String?>(null)
        private set
    var task by mutableStateOf<WorkTask?>(null)
        private set
    var loadingTask by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var audits by mutableStateOf(emptyList<TaskAudit>())
        private set
    var auditCursor by mutableStateOf<String?>(null)
        private set
    var loadingAudit by mutableStateOf(false)
        private set
    var auditError by mutableStateOf<String?>(null)
        private set
    var pending by mutableStateOf(emptyList<PendingTaskCommand>())
        private set
    var reminders by mutableStateOf(emptyList<TaskReminder>())
        private set
    internal var reminderTasks by mutableStateOf(emptyMap<String, WorkTask>())
    internal var editor by mutableStateOf<TaskEditorState?>(null)
    val materialsEditorKey: String? get() = editor?.editorKey
    internal var editorError by mutableStateOf<String?>(null)
    internal var posting by mutableStateOf(false)
    internal var pendingAction by mutableStateOf<String?>(null)
    internal var notice by mutableStateOf<String?>(null)
    private var waitingNotice: TaskWaitingNotice? = null
    internal var users by mutableStateOf(emptyMap<String, User>())
    internal var assigneeCandidates by mutableStateOf(emptyList<User>())
    internal var assigneeQuery by mutableStateOf("")
    internal var findingAssignees by mutableStateOf(false)
    internal var assigneeError by mutableStateOf<String?>(null)
    internal var assigneeJob: Job? = null
    internal var assigneeOwner = 0L
    internal var contextOptions by mutableStateOf(emptyList<TaskContextOption>())
    internal var loadingContexts by mutableStateOf(false)
    internal var contextError by mutableStateOf<String?>(null)
    internal var sharing by mutableStateOf<WorkTask?>(null)
    internal var shareOptions by mutableStateOf(emptyList<TaskShareOption>())
    internal var sharingTo by mutableStateOf<String?>(null)
    internal var shareError by mutableStateOf<String?>(null)
    internal val failedShareMessages = mutableMapOf<Pair<String, String>, Message>()
    internal val requestedUsers = mutableSetOf<String>()

    init {
        scope.launch {
            repo.local.changes.collect {
                val generation = localData.run { repo.local.generation() }
                val invalidated = observedGeneration?.let { previous -> previous != generation } == true
                observedGeneration = generation
                reloadLocal()
                if (invalidated) attention.refresh()
                if (invalidated && opened) {
                    refresh()
                    selectedTaskId?.let(::refreshTask)
                }
            }
        }
        scope.launch {
            var previous = session.connectionState.value
            session.connectionState.collect { state ->
                // 恢复表单可能先于协议协商完成；能力变化必须让材料和周期组件重新组合。
                supportsTaskDetails = repo.supportsTaskDetails
                val reconnected = state == ConnectionState.AUTHENTICATED && state != previous
                previous = state
                if (reconnected) attention.refresh()
                if (opened && reconnected) {
                    refresh()
                    selectedTaskId?.let(::refreshTask)
                }
                if (state != ConnectionState.AUTHENTICATED && items.isNotEmpty()) stale = true
                attention.reload(offline = state != ConnectionState.AUTHENTICATED)
            }
        }
    }

    /** 栏目初始化不重置外部引用选中的详情，冷启动与重复进入使用相同入口。 */
    suspend fun open() {
        workspaceRequested = false
        opened = true
        reloadLocal()
        refresh()
    }

    fun selectView(value: Int) {
        if (value == view && !onlyOpen && !onlyStarted) return
        require(value == TaskPolicy.VIEW_ASSIGNED || value == TaskPolicy.VIEW_CREATED)
        view = value
        groupFilter = null
        onlyOpen = false
        onlyStarted = false
        items = emptyList()
        nextCursor = null
        refresh()
    }

    fun openAssignedTodos() {
        workspaceRequested = true
        showList()
        view = TaskQuery.ASSIGNED
        groupFilter = null
        onlyOpen = true
        onlyStarted = true
        opened = true
        refresh()
    }

    fun openGroupTodos(groupId: String) {
        if (!supportsTaskDetails) return
        workspaceRequested = true
        showList()
        view = TaskQuery.GROUP
        groupFilter = groupId
        onlyOpen = true
        onlyStarted = false
        opened = true
        refresh()
    }

    fun updateOpenFilter(value: Boolean) {
        if (onlyOpen == value) return
        onlyOpen = value
        onlyStarted = false
        refresh()
    }

    fun refresh() = requestPage(append = false)
    fun loadMore() {
        if (!loading && nextCursor != null) requestPage(append = true)
    }

    private fun requestPage(append: Boolean) {
        if (supportsTaskDetails) requestQueryPage(append) else {
            // 重连到旧节点时退回旧协议支持的个人列表。
            if (view == TaskQuery.GROUP) {
                view = TaskPolicy.VIEW_ASSIGNED
                groupFilter = null
                items = emptyList()
                nextCursor = null
            }
            onlyOpen = false
            onlyStarted = false
            summary = null
            requestLegacyPage(append)
        }
    }

    private fun requestQueryPage(append: Boolean) {
        val cursor = if (append) nextCursor ?: return else null
        val key = TaskQueryKey(view, groupFilter, openOnly = onlyOpen, startedOnly = onlyStarted, cursor = cursor)
        val owner = ++listOwner
        listJob?.cancel()
        val changedQuery = !append && queryKeys.firstOrNull() != key
        if (!append) queryKeys = listOf(key)
        if (changedQuery) {
            items = emptyList()
            summary = null
            nextCursor = null
        }
        loading = true
        listError = null
        listJob = scope.launch {
            try {
                // 筛选切换先展示目标查询自己的缓存，失败时不能留下上一群的内容。
                if (changedQuery) reloadLocal()
                if (!localData.run { repo.queryRefresh(key).getOrThrow() }) {
                    if (listOwner == owner) listError = "待办正在变化，请刷新后重试"
                    return@launch
                }
                if (listOwner != owner) return@launch
                if (append) queryKeys = (queryKeys + key).takeLast(MAX_VISIBLE_TASKS / TaskPolicy.DEFAULT_PAGE_SIZE)
                reloadLocal()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (listOwner == owner) {
                    stale = true
                    listError = taskFailureText(failure, "待办列表暂不可用，已保留本地内容")
                }
            } finally { if (listOwner == owner) loading = false }
        }
    }

    private fun requestLegacyPage(append: Boolean) {
        val cursor = if (append) nextCursor ?: return else null
        val key = TaskPageKey(view, cursor)
        val owner = ++listOwner
        listJob?.cancel()
        if (!append) pageKeys = listOf(key)
        loading = true
        listError = null
        listJob = scope.launch {
            try {
                if (!localData.run { repo.refresh(key).getOrThrow() }) {
                    if (listOwner == owner) listError = "任务正在变化，请刷新列表后重试"
                    return@launch
                }
                if (listOwner != owner) return@launch
                val page = localData.run { repo.local.page(key) }
                    ?: throw IllegalStateException("Task page was invalidated")
                check(page.nextCursor == null || page.nextCursor != cursor) { "Task cursor did not advance" }
                if (append) pageKeys = (pageKeys + key).takeLast(MAX_VISIBLE_TASKS / TaskPolicy.DEFAULT_PAGE_SIZE)
                reloadLocal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (listOwner == owner) {
                    stale = true
                    listError = taskFailureText(failure, "任务列表暂不可用，已保留本地内容")
                }
            } finally {
                if (listOwner == owner) loading = false
            }
        }
    }

    fun openTask(taskId: String) {
        opened = true
        editor = null
        editorError = null
        selectedTaskId = taskId
        task = null
        details = null
        deferrals = emptyMap()
        audits = emptyList()
        auditCursor = null
        refreshTask(taskId)
    }

    fun refreshTask() { selectedTaskId?.let(::refreshTask) }

    private fun refreshTask(taskId: String) {
        if (selectedTaskId != taskId) return
        val owner = ++detailOwner
        detailJob?.cancel()
        auditJob?.cancel()
        loadingTask = true
        detailError = null
        detailJob = scope.launch {
            try {
                val (cached, awaitingCreate) = localData.run {
                    repo.local.task(taskId) to repo.local.pending().any {
                        it.taskId == taskId && it.kind == TaskCommand.CREATE
                    }
                }
                if (detailOwner != owner) return@launch
                task = cached
                // 首次创建尚未 ACK 的身份还没有远端对象，404 不能解释为创建失败。
                if (cached == null && awaitingCreate) return@launch
                val remote = localData.run { repo.getDetails(taskId).getOrThrow() }
                if (detailOwner != owner) return@launch
                details = remote
                task = remote.task
                rememberTaskUsers(listOf(remote.task))
                if (remote.task.contextKind != TaskPolicy.CONTEXT_NONE) loadTaskChoices()
                loadAudit(append = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (detailOwner == owner) {
                    if ((failure as? AppError.Business)?.code in setOf(403, 404)) {
                        task = null
                        details = null
                        deferrals = emptyMap()
                        audits = emptyList()
                    }
                    detailError = taskFailureText(failure, "当前无法同步任务，已保留本地内容")
                }
            } finally {
                if (detailOwner == owner) loadingTask = false
            }
        }
    }

    fun showList() {
        ++detailOwner
        detailJob?.cancel()
        auditJob?.cancel()
        selectedTaskId = null
        task = null
        details = null
        deferrals = emptyMap()
        audits = emptyList()
        loadingTask = false
        loadingAudit = false
        editor = null
        editorError = null
    }

    fun handleBack(): Boolean = when {
        sharing != null && sharingTo == null -> { sharing = null; true }
        editor != null && !posting -> { editor = null; editorError = null; true }
        selectedTaskId != null && !posting -> { showList(); true }
        else -> false
    }

    internal suspend fun reloadLocal() {
        val keys = pageKeys.toList()
        val queries = queryKeys.toList()
        val extended = supportsTaskDetails
        val listNavigation = listOwner
        val detailNavigation = detailOwner
        val selected = selectedTaskId
        val observedNotice = waitingNotice
        val snapshot = localData.run {
            val pages = if (extended) emptyList() else keys.map { repo.local.page(it) }
            val queryPages = if (extended) queries.map { repo.local.queryPage(it) } else emptyList()
            val pending = repo.local.pending()
            val reminders = repo.local.reminders().filterNot { it.seen }.take(20)
            TaskWorkspaceSnapshot(pages, queryPages,
                if (extended) queries.any { repo.local.isQueryPageStale(it) } else keys.any { repo.local.isPageStale(it) },
                selected?.let(repo.local::task), selected?.let(repo.local::details), pending, reminders,
                reminders.mapNotNull { reminder -> repo.local.task(reminder.taskId)?.let { reminder.taskId to it } }.toMap())
        }
        attention.reload(offline = session.connectionState.value != ConnectionState.AUTHENTICATED)
        pending = snapshot.pending
        if (observedNotice != null && waitingNotice === observedNotice && snapshot.pending.none {
                it.taskId == observedNotice.taskId && it.operationId == observedNotice.operationId && it.failure == null
            }) {
            // 消失可能是 ACK，也可能是明确放弃；只清理等待提示，不推断操作成功。
            // 新的分享或其他提示拥有自己的内容，不由旧任务的完成覆盖。
            if (notice == observedNotice.text) notice = null
            waitingNotice = null
        }
        reminders = snapshot.reminders
        reminderTasks = snapshot.reminderTasks
        if (keys == pageKeys && queries == queryKeys && listNavigation == listOwner) {
            if (extended) {
                val contiguous = snapshot.queryPages.takeWhile { it != null }.filterNotNull()
                items = contiguous.flatMap { it.items }.map { it.task }.distinctBy(WorkTask::taskId).take(MAX_VISIBLE_TASKS)
                nextCursor = contiguous.lastOrNull()?.nextCursor
                summary = contiguous.firstOrNull()?.summary
            } else {
                val contiguous = snapshot.pages.takeWhile { it != null }.filterNotNull()
                items = contiguous.flatMap(TaskPage::items).distinctBy(WorkTask::taskId).take(MAX_VISIBLE_TASKS)
                nextCursor = contiguous.lastOrNull()?.nextCursor
                summary = null
            }
            stale = snapshot.stale || session.connectionState.value != ConnectionState.AUTHENTICATED
            rememberTaskUsers(items)
        }
        if (selected == selectedTaskId && detailNavigation == detailOwner) {
            task = snapshot.selected
            details = snapshot.details
            if (task != null) rememberTaskUsers(listOf(requireNotNull(task)))
        }
    }

    internal fun beginCreate() {
        editor = TaskEditorState.create(myUid)
        editorError = null
        notice = null
        loadTaskChoices()
    }

    internal fun beginEdit() {
        val current = task ?: return
        if (current.creatorUid != myUid || pending.any { it.taskId == current.taskId }) return
        val currentDetails = details?.takeIf { it.task.revision == current.revision }
        if (supportsTaskDetails && currentDetails == null) {
            detailError = "请先同步待办详情后再编辑"
            return
        }
        editor = if (currentDetails != null) TaskEditorState.from(currentDetails) else TaskEditorState.from(current)
        editorError = null
        loadTaskChoices()
    }

    internal fun updateEditor(value: TaskEditorState) { if (!posting) { editor = value; editorError = null } }

    /** 平台 Saver 在保存时直接读取当前表单，不维护会滞后的第二份编辑状态。 */
    fun saveEditorSnapshot(): String? {
        val current = editor ?: return null
        return taskEditorSavedStateJson.encodeToString(SavedTaskEditor(
            session.deploymentIdentity.fingerprint, session.datasetId, myUid, current,
        ))
    }

    /** 在注册文件选择器前恢复；本地读取仍走现有 IO 边界。 */
    suspend fun restoreEditorSnapshot(payload: String) {
        if (editor != null || posting || selectedTaskId != null) return
        val saved = runCatching { taskEditorSavedStateJson.decodeFromString<SavedTaskEditor>(payload) }.getOrNull() ?: return
        if (saved.version != 1 || saved.deploymentFingerprint != session.deploymentIdentity.fingerprint ||
            saved.datasetId != session.datasetId || saved.ownerUid != myUid) return
        val recovered = saved.editor.copy(uploading = false)
        if (runCatching { TaskPolicy.requireId(recovered.editorKey) }.isFailure ||
            (recovered.original != null && recovered.original.creatorUid != myUid) ||
            (recovered.originalDetails != null && recovered.originalDetails.task != recovered.original)) return
        val targetId = recovered.original?.taskId ?: recovered.editorKey
        val navigation = detailOwner
        val local = try {
            localData.run {
                Triple(repo.local.pending(targetId), repo.local.task(targetId), repo.local.details(targetId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        // 已驻留的表单或用户的明确导航，优先于旧 Activity 快照。
        if (editor != null || posting || detailOwner != navigation) return
        if (local?.first != null || (recovered.original == null && local?.second != null)) {
            openTask(targetId)
            notice = if (local?.first != null) "此表单已保存为待发送操作，请查看现有任务，不会重复提交"
                else "此表单已提交，已打开现有任务"
            return
        }
        editor = recovered
        editorError = null
        selectedTaskId = recovered.original?.taskId
        task = local?.second ?: recovered.original
        details = local?.third ?: recovered.originalDetails
        notice = if (local == null) "已恢复输入；上次提交状态暂不可用，请刷新任务后确认"
            else "已恢复尚未保存的任务输入"
        loadTaskChoices()
    }

    internal fun saveEditor() {
        val captured = editor ?: return
        if (posting || captured.uploading) return
        val draft: TaskDraft
        val options: TaskOptions
        val recurrenceRule: TaskRecurrenceRule?
        try {
            draft = captured.draft()
            options = captured.optionsForSave()
            recurrenceRule = captured.recurrenceRule()
        } catch (failure: IllegalArgumentException) {
            editorError = failure.message ?: "请检查任务内容"
            return
        }
        posting = true
        val navigation = detailOwner
        scope.launch {
            try {
                val id = recordSubmission("任务操作已保存，等待同步") {
                    // 即使旧 Activity 快照对应的 ACK 投影已淘汰，CREATE 也沿用原身份，避免重复创建。
                    if (supportsTaskDetails) {
                        if (captured.original == null) repo.enqueue(TaskDetailsCommand(
                            platformRandomUuid(), platformCurrentTimeMillis(), captured.editorKey,
                            0, TaskDetailsCommand.CREATE, draft, options, recurrenceRule = recurrenceRule,
                        )).getOrThrow()
                        else repo.edit(requireNotNull(captured.originalDetails), draft, options).getOrThrow()
                    } else {
                        if (captured.original == null) repo.enqueue(TaskCommand(
                            platformRandomUuid(), platformCurrentTimeMillis(), captured.editorKey,
                            0, TaskCommand.CREATE, draft,
                        )).getOrThrow()
                        else repo.edit(captured.original, draft).getOrThrow()
                    }
                }
                if (editor === captured) editor = null
                if (detailOwner == navigation) openTask(id)
                reloadLocal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (editor === captured) editorError = taskFailureText(failure, "任务未能保存，请重试")
            } finally { posting = false }
        }
    }

    internal fun changeStatus(status: Int) {
        val current = task ?: return
        if (posting || pending.any { it.taskId == current.taskId }) return
        posting = true
        scope.launch {
            try {
                recordSubmission("状态操作已保存，等待同步") { repo.setStatus(current, status).getOrThrow() }
                reloadLocal()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (selectedTaskId == current.taskId) detailError = taskFailureText(failure, "任务状态未能保存，请重试")
            }
            finally { posting = false }
        }
    }

    internal fun deferTask(original: TaskDetails, dueAt: Long, reason: String) {
        if (posting || pending.any { it.taskId == original.task.taskId }) return
        posting = true
        scope.launch {
            try {
                recordSubmission("延期已保存，等待同步") { repo.defer(original, dueAt, reason).getOrThrow() }
                reloadLocal()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (selectedTaskId == original.task.taskId) detailError = taskFailureText(failure, "延期未能保存，请重试")
            } finally { posting = false }
        }
    }

    internal fun setSeriesEnabled(series: TaskSeries, enabled: Boolean) {
        if (posting || pending.any { it.taskId == series.seriesId }) return
        posting = true
        scope.launch {
            try {
                recordSubmission("周期设置已保存，等待同步") { repo.setSeriesEnabled(series, enabled).getOrThrow() }
                reloadLocal()
                refreshTask()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { detailError = taskFailureText(failure, "周期设置未能保存，请重试") }
            finally { posting = false }
        }
    }

    private suspend fun recordSubmission(text: String, submit: suspend () -> String): String {
        val (taskId, operationId) = localData.run {
            val id = submit()
            id to repo.local.pending().firstOrNull { it.taskId == id }?.operationId
        }
        // ACK 可能在提交返回前完成；没有 pending 身份时，随后读取会立即清除等待提示。
        waitingNotice = TaskWaitingNotice(taskId, operationId, text)
        notice = text
        return taskId
    }

    internal fun retryPending(id: String) = actOnPending(id, discard = false)
    internal fun discardPending(id: String) = actOnPending(id, discard = true)
    private fun actOnPending(id: String, discard: Boolean) {
        if (pendingAction != null) return
        pendingAction = id
        scope.launch {
            try {
                localData.run { if (discard) repo.discardRejected(id) else repo.retry(id) }
                reloadLocal()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = taskFailureText(failure, "待发送操作暂时无法处理") }
            finally { pendingAction = null }
        }
    }

    internal fun markReminderSeen(reminder: TaskReminder) {
        scope.launch {
            try { localData.run { repo.local.markReminderSeen(reminder.taskId, reminder.remindedAt) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "提醒未能标记已读，请重试" }
        }
    }

    internal fun loadAudit(append: Boolean) {
        val id = selectedTaskId ?: return
        val cursor = if (append) auditCursor ?: return else null
        val owner = detailOwner
        auditJob?.cancel()
        loadingAudit = true
        auditError = null
        auditJob = scope.launch {
            try {
                val page = localData.run {
                    if (supportsTaskDetails) repo.history(id, cursor).getOrThrow()
                    else repo.audit(id, cursor).getOrThrow().let { old -> TaskHistoryPage(old.items.map { TaskHistoryEntry(it) }, old.nextCursor) }
                }
                if (detailOwner != owner || selectedTaskId != id) return@launch
                audits = ((if (append) audits else emptyList()) + page.items.map { it.audit }).distinctBy { it.revision }.takeLast(MAX_VISIBLE_TASKS)
                deferrals = ((if (append) deferrals else emptyMap()) + page.items.mapNotNull { entry -> entry.deferral?.let { it.revision to it } }).filterKeys { revision -> audits.any { it.revision == revision } }
                auditCursor = page.nextCursor
                rememberUsers(audits.map(TaskAudit::actorUid) + audits.mapNotNull(TaskAudit::assigneeUid))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (detailOwner == owner) auditError = taskFailureText(failure, "操作记录暂不可用") }
            finally { if (detailOwner == owner) loadingAudit = false }
        }
    }

    internal fun userName(uid: String): String = users[uid]?.let { it.name.ifBlank { it.username } }
        ?: if (uid == myUid) session.userSession.name?.takeIf(String::isNotBlank) ?: "我" else "成员"
    internal fun contextName(kind: Int, id: String): String = if (kind == TaskPolicy.CONTEXT_NONE) "无关联"
        else contextOptions.firstOrNull { it.kind == kind && it.id == id }?.name
            ?: if (kind == TaskPolicy.CONTEXT_GROUP) "关联群" else "关联部门"

    companion object { internal const val MAX_VISIBLE_TASKS = 200 }
}

private val taskEditorSavedStateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private data class TaskWorkspaceSnapshot(
    val pages: List<TaskPage?>,
    val queryPages: List<TaskQueryPage?>,
    val stale: Boolean,
    val selected: WorkTask?,
    val details: TaskDetails?,
    val pending: List<PendingTaskCommand>,
    val reminders: List<TaskReminder>,
    val reminderTasks: Map<String, WorkTask>,
)

private data class TaskWaitingNotice(val taskId: String, val operationId: String?, val text: String)

internal fun taskFailureText(failure: Throwable, fallback: String): String = when ((failure as? AppError.Business)?.code) {
    400 -> "任务信息无效，请检查内容后重试"
    403, 404 -> "任务不可访问或已删除"
    409 -> "任务已有新版本，请读取当前内容后重新编辑"
    else -> fallback
}
