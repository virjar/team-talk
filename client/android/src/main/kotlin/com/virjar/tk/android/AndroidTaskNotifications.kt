package com.virjar.tk.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val TASK_CHANNEL = "teamtalk.tasks"

internal fun clearAndroidTaskNotifications(context: Context) {
    val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    manager.activeNotifications.filter { it.notification.channelId == TASK_CHANNEL }
        .forEach { manager.cancel(it.tag, it.id) }
}

/** 会话拥有通知生命周期；提醒来自服务端持久事实，不在手机上推算截止时间。 */
internal class AndroidTaskNotifications(
    private val context: Context,
    private val session: ClientSession,
    private val foreground: StateFlow<Boolean>,
) : AutoCloseable {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private val posted = mutableSetOf<String>()
    private val prefix = "task:${session.deploymentIdentity.fingerprint}:${session.datasetId}:${session.ownerUid}:"

    init {
        manager.createNotificationChannel(NotificationChannel(TASK_CHANNEL, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            val local = session.taskRepo.local
            combine(local.changes, session.connectionState, foreground) { _, connection, active -> connection to active }
                .collect { (connection, active) ->
                    val reminders = withContext(Dispatchers.IO) { local.reminders() }
                    val eligible = reminders.filterNot { it.seen }.mapTo(mutableSetOf()) { it.taskId }
                    posted.toList().filter { active || it !in eligible }.forEach(::cancel)
                    if (connection != ConnectionState.AUTHENTICATED || active || !manager.areNotificationsEnabled()) return@collect
                    for (reminder in reminders.filterNot { it.seen || it.notified }) {
                        val task = (withContext(Dispatchers.IO) { session.taskRepo.get(reminder.taskId) } as? Outcome.Success)?.value ?: continue
                        if (task.assigneeUid != session.ownerUid || task.remindedAt != reminder.remindedAt ||
                            task.status !in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS)) continue
                        if (closed || foreground.value || session.connectionState.value != ConnectionState.AUTHENTICATED) break
                        val target = AndroidNotificationTarget(session.deploymentIdentity.fingerprint, session.datasetId,
                            session.ownerUid, chatId = "", taskId = task.taskId)
                        val pendingIntent = PendingIntent.getActivity(context, 0, target.intent(context),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val notification = Notification.Builder(context, TASK_CHANNEL)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("任务已到截止时间")
                            .setContentText(task.title)
                            .setCategory(Notification.CATEGORY_REMINDER)
                            .setVisibility(Notification.VISIBILITY_PRIVATE)
                            .setAutoCancel(true).setContentIntent(pendingIntent).build()
                        try {
                            manager.notify(prefix + task.taskId, 0, notification)
                            posted += task.taskId
                            withContext(Dispatchers.IO) { local.markReminderNotified(task.taskId, reminder.remindedAt) }
                        } catch (_: SecurityException) {
                            // 权限拒绝后仍保留应用内未读提醒。
                        }
                    }
                }
        }
    }

    private fun cancel(taskId: String) {
        manager.cancel(prefix + taskId, 0)
        posted -= taskId
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        manager.activeNotifications.filter { it.tag?.startsWith(prefix) == true }
            .forEach { manager.cancel(it.tag, it.id) }
        posted.clear()
    }
}
