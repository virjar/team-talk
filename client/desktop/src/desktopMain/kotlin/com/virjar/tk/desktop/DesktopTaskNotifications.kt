package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.virjar.tk.desktop.tray.AppTray
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** 系统展示与任务已读使用不同的持久收据，重新启动不重复弹出同一次提醒。 */
@Composable
internal fun DesktopTaskNotifications(
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    windowActive: Boolean,
    connectionState: ConnectionState,
) {
    val repo = nav.taskRepository
    val presentation by rememberUpdatedState(windowActive to connectionState)
    LaunchedEffect(nav) {
        combine(repo.local.changes, snapshotFlow { presentation }) { _, state -> state }.collect { (active, connection) ->
            if (active || connection != ConnectionState.AUTHENTICATED || !AppTray.isActive) return@collect
            nav.runAdmittedUiAction(presentationGate, onClosed = {}) {
                val reminders = withContext(Dispatchers.IO) { repo.local.reminders().filterNot { it.seen || it.notified } }
                for (reminder in reminders) {
                    val task = (withContext(Dispatchers.IO) { repo.get(reminder.taskId) } as? Outcome.Success)?.value ?: continue
                    if (task.assigneeUid != nav.userSession.uid || task.remindedAt != reminder.remindedAt ||
                        task.status !in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS)) continue
                    if (!presentationGate.isOpen || presentation.first || presentation.second != ConnectionState.AUTHENTICATED) break
                    AppTray.showNotification("任务已到截止时间", task.title)
                    withContext(Dispatchers.IO) { repo.local.markReminderNotified(task.taskId, reminder.remindedAt) }
                }
            }
        }
    }
}
