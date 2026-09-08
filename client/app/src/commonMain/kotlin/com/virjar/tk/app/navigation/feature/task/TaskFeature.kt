package com.virjar.tk.app.navigation.feature.task

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
import com.virjar.tk.shared.client.TaskPageKey
import com.virjar.tk.shared.client.TaskReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 会话内唯一任务工作台；已确认投影和待发送意图由 SDK 持久化，本类只持有交互状态。 */
class TaskFeature internal constructor(
    internal val session: ClientSession,
    internal val scope: CoroutineScope,
    internal val localData: UiLocalDataBoundary,
    internal val conversations: ConversationViewModel,
) {
    internal val repo get() = session.taskRepo
    val myUid: String get() = session.ownerUid
    internal var opened = false
    internal var listOwner = 0L
    internal var detailOwner = 0L
    internal var pageKeys = listOf(TaskPageKey(TaskPolicy.VIEW_ASSIGNED))
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
    internal var editorError by mutableStateOf<String?>(null)
    internal var posting by mutableStateOf(false)
    internal var pendingAction by mutableStateOf<String?>(null)
    internal var notice by mutableStateOf<String?>(null)
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
                if (invalidated && opened) {
                    refresh()
                    selectedTaskId?.let(::refreshTask)
                }
            }
        }
        scope.launch {
            var previous = session.connectionState.value
            session.connectionState.collect { state ->
                val reconnected = state == ConnectionState.AUTHENTICATED && state != previous
                previous = state
                if (opened && reconnected) {
                    refresh()
                    selectedTaskId?.let(::refreshTask)
                }
                if (state != ConnectionState.AUTHENTICATED && items.isNotEmpty()) stale = true
            }
        }
    }

    /** 栏目初始化不重置外部引用选中的详情，冷启动与重复进入使用相同入口。 */
    suspend fun open() {
        opened = true
        reloadLocal()
        refresh()
    }

    fun selectView(value: Int) {
        if (value == view) return
        require(value == TaskPolicy.VIEW_ASSIGNED || value == TaskPolicy.VIEW_CREATED)
        view = value
        items = emptyList()
        nextCursor = null
        refresh()
    }

    fun refresh() = requestPage(append = false)
    fun loadMore() {
        if (!loading && nextCursor != null) requestPage(append = true)
    }

    private fun requestPage(append: Boolean) {
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
                        it.command.taskId == taskId && it.command.kind == TaskCommand.CREATE
                    }
                }
                if (detailOwner != owner) return@launch
                task = cached
                // 首次创建尚未 ACK 的身份还没有远端对象，404 不能解释为创建失败。
                if (cached == null && awaitingCreate) return@launch
                val remote = localData.run { repo.get(taskId).getOrThrow() }
                if (detailOwner != owner) return@launch
                task = remote
                rememberTaskUsers(listOf(remote))
                if (remote.contextKind != TaskPolicy.CONTEXT_NONE) loadTaskChoices()
                loadAudit(append = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (detailOwner == owner) {
                    if ((failure as? AppError.Business)?.code in setOf(403, 404)) {
                        task = null
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
        val listNavigation = listOwner
        val detailNavigation = detailOwner
        val selected = selectedTaskId
        val snapshot = localData.run {
            val pages = keys.map { repo.local.page(it) }
            val pending = repo.local.pending()
            val reminders = repo.local.reminders().filterNot { it.seen }.take(20)
            TaskWorkspaceSnapshot(pages, keys.any { repo.local.isPageStale(it) },
                selected?.let(repo.local::task), pending, reminders,
                reminders.mapNotNull { reminder -> repo.local.task(reminder.taskId)?.let { reminder.taskId to it } }.toMap())
        }
        pending = snapshot.pending
        reminders = snapshot.reminders
        reminderTasks = snapshot.reminderTasks
        if (keys == pageKeys && listNavigation == listOwner) {
            val contiguous = snapshot.pages.takeWhile { it != null }.filterNotNull()
            items = contiguous.flatMap(TaskPage::items).distinctBy(WorkTask::taskId).take(MAX_VISIBLE_TASKS)
            nextCursor = contiguous.lastOrNull()?.nextCursor
            stale = snapshot.stale || session.connectionState.value != ConnectionState.AUTHENTICATED
            rememberTaskUsers(items)
        }
        if (selected == selectedTaskId && detailNavigation == detailOwner) {
            task = snapshot.selected
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
        if (current.creatorUid != myUid || pending.any { it.command.taskId == current.taskId }) return
        editor = TaskEditorState.from(current)
        editorError = null
        loadTaskChoices()
    }

    internal fun updateEditor(value: TaskEditorState) { if (!posting) { editor = value; editorError = null } }

    internal fun saveEditor() {
        val captured = editor ?: return
        if (posting) return
        val draft = try { captured.draft() } catch (failure: IllegalArgumentException) {
            editorError = failure.message ?: "请检查任务内容"
            return
        }
        posting = true
        val navigation = detailOwner
        scope.launch {
            try {
                val id = localData.run {
                    if (captured.original == null) repo.create(draft).getOrThrow()
                    else repo.edit(captured.original, draft).getOrThrow()
                }
                if (editor === captured) editor = null
                if (detailOwner == navigation) openTask(id)
                notice = "任务操作已保存，等待同步"
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
        if (posting || pending.any { it.command.taskId == current.taskId }) return
        posting = true
        scope.launch {
            try {
                localData.run { repo.setStatus(current, status).getOrThrow() }
                notice = "状态操作已保存，等待同步"
                reloadLocal()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (selectedTaskId == current.taskId) detailError = taskFailureText(failure, "任务状态未能保存，请重试")
            }
            finally { posting = false }
        }
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
                val page = localData.run { repo.audit(id, cursor).getOrThrow() }
                if (detailOwner != owner || selectedTaskId != id) return@launch
                audits = ((if (append) audits else emptyList()) + page.items).distinctBy { it.revision }.takeLast(MAX_VISIBLE_TASKS)
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

private data class TaskWorkspaceSnapshot(
    val pages: List<TaskPage?>,
    val stale: Boolean,
    val selected: WorkTask?,
    val pending: List<PendingTaskCommand>,
    val reminders: List<TaskReminder>,
    val reminderTasks: Map<String, WorkTask>,
)

internal fun taskFailureText(failure: Throwable, fallback: String): String = when ((failure as? AppError.Business)?.code) {
    400 -> "任务信息无效，请检查内容后重试"
    403, 404 -> "任务不可访问或已删除"
    409 -> "任务已有新版本，请读取当前内容后重新编辑"
    else -> fallback
}
