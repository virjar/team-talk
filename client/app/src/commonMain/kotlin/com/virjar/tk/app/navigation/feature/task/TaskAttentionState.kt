package com.virjar.tk.app.navigation.feature.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.model.TaskQuery
import com.virjar.tk.protocol.model.TaskSummary
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.TaskQueryKey
import com.virjar.tk.shared.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 首页只关注执行人的未完成项和当前群；摘要来自 SDK 中的完整条件查询结果。 */
class TaskAttentionState internal constructor(
    private val repo: TaskRepository,
    private val scope: CoroutineScope,
    private val localData: UiLocalDataBoundary,
) {
    private val assignedKey = TaskQueryKey(TaskQuery.ASSIGNED, openOnly = true, startedOnly = true)
    var groupId by mutableStateOf<String?>(null)
        private set
    var assigned by mutableStateOf<TaskSummary?>(null)
        private set
    var group by mutableStateOf<TaskSummary?>(null)
        private set
    var assignedStale by mutableStateOf(false)
        private set
    var groupStale by mutableStateOf(false)
        private set
    private var refreshJob: Job? = null

    fun watchGroup(id: String?) {
        if (groupId == id) return
        groupId = id
        group = null
        refresh()
    }

    fun clearGroup(expected: String) {
        if (groupId == expected) watchGroup(null)
    }

    fun refresh() {
        if (!repo.supportsTaskDetails) return
        val groupKey = groupKey()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            var unavailable = false
            for (key in listOfNotNull(assignedKey, groupKey)) {
                if (localData.run { repo.queryRefresh(key) } is Outcome.Failure) unavailable = true
            }
            reload(offline = unavailable)
        }
    }

    internal suspend fun reload(offline: Boolean) {
        if (!repo.supportsTaskDetails) {
            assigned = null
            group = null
            return
        }
        val expected = groupId
        val groupKey = groupKey()
        val snapshot = localData.run {
            listOfNotNull(assignedKey, groupKey).map { key ->
                repo.local.queryPage(key)?.summary to (offline || repo.local.isQueryPageStale(key))
            }
        }
        assigned = snapshot.first().first
        assignedStale = snapshot.first().second
        if (groupId == expected) {
            group = snapshot.getOrNull(1)?.first
            groupStale = snapshot.getOrNull(1)?.second ?: false
        }
    }

    private fun groupKey() = groupId?.let { TaskQueryKey(TaskQuery.GROUP, it, openOnly = true) }
}
