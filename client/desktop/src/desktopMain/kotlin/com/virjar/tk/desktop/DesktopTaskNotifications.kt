package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.virjar.tk.app.navigation.feature.task.forEachDueTaskReminder
import com.virjar.tk.desktop.tray.AppTray
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
                forEachDueTaskReminder(repo, nav.userSession.uid, stillEligible = {
                    presentationGate.isOpen && !presentation.first && presentation.second == ConnectionState.AUTHENTICATED
                }) { task, remindedAt ->
                    AppTray.showNotification("任务已到截止时间", task.title)
                    withContext(Dispatchers.IO) { repo.local.markReminderNotified(task.taskId, remindedAt) }
                }
            }
        }
    }
}
