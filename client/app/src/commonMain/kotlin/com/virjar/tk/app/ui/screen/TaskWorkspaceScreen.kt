package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.virjar.tk.app.navigation.feature.task.*
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.TaskAudit
import com.virjar.tk.protocol.model.TaskCommand
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.shared.client.PendingTaskCommand
import kotlinx.coroutines.delay

/** 双端共用任务工作台；平台只负责进入任务栏目和从消息引用转入某个任务。 */
@Composable
fun TaskWorkspaceScreen(
    feature: TaskFeature,
    actionAdmission: UiActionAdmission,
    onShareTask: ((WorkTask) -> Unit)? = null,
    modifier: Modifier = Modifier,
    compactMode: Boolean = false,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(feature) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    Column(modifier.fillMaxSize().testTag("task.workspace")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            Text("任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actionAdmission.guard(feature::refresh), modifier = Modifier.testTag("task.refresh")) { Text("刷新") }
            Button(onClick = actionAdmission.guard(feature::beginCreate), enabled = !feature.posting,
                modifier = Modifier.testTag("task.new")) { Text("新建任务") }
        }
        feature.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(Tk.spacing.sm),
                verticalAlignment = Alignment.CenterVertically) {
                Text(notice, modifier = Modifier.weight(1f).testTag("task.notice"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actionAdmission.guard { feature.notice = null }) { Text("知道了") }
            }
        }
        HorizontalDivider(color = Tk.colors.divider)
        val detailVisible = feature.selectedTaskId != null || feature.editor != null
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (!compactMode || !detailVisible) {
                TaskList(feature, actionAdmission, now,
                    if (compactMode) Modifier.fillMaxSize() else Modifier.width(Tk.dimens.listPaneWidth).fillMaxHeight())
            }
            if (!compactMode) VerticalDivider(color = Tk.colors.divider)
            if (!compactMode || detailVisible) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val editor = feature.editor
                    when {
                        editor != null -> TaskEditorPane(feature, editor, actionAdmission)
                        feature.selectedTaskId != null -> TaskDetail(feature, actionAdmission, now,
                            onShare = onShareTask ?: feature::beginShare)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("选择一项任务，或创建新的任务", color = Tk.colors.metaText)
                        }
                    }
                }
            }
        }
    }
    TaskShareDialog(feature, actionAdmission)
}

@Composable
private fun TaskList(feature: TaskFeature, admission: UiActionAdmission, now: Long, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(Tk.spacing.sm), horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            listOf(TaskPolicy.VIEW_ASSIGNED to "分配给我", TaskPolicy.VIEW_CREATED to "我创建的").forEach { (view, label) ->
                FilterChip(selected = feature.view == view, onClick = admission.guard { feature.selectView(view) },
                    label = { Text(label) }, modifier = Modifier.testTag(if (view == TaskPolicy.VIEW_ASSIGNED) "task.view.assigned" else "task.view.created"))
            }
        }
        if (feature.stale) Text("显示本地内容，联网后同步", modifier = Modifier.padding(horizontal = Tk.spacing.lg).testTag("task.stale"),
            style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
        feature.listError?.let { TaskError(it, Modifier.padding(Tk.spacing.lg).testTag("task.list.error")) }
        if (feature.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("task.list.loading"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("task.list")) {
            if (feature.reminders.isNotEmpty()) item { TaskSectionLabel("到期提醒") }
            items(feature.reminders, key = { "reminder.${it.taskId}.${it.remindedAt}" }) { reminder ->
                Column(Modifier.fillMaxWidth().padding(Tk.spacing.md).testTag("task.reminder.${reminder.taskId}")) {
                    Text(feature.reminderTasks[reminder.taskId]?.title ?: "任务到期提醒", fontWeight = FontWeight.Medium)
                    Row {
                        TextButton(onClick = admission.guard { feature.openTask(reminder.taskId); feature.markReminderSeen(reminder) },
                            modifier = Modifier.testTag("task.reminder.open.${reminder.taskId}")) { Text("查看任务") }
                        TextButton(onClick = admission.guard { feature.markReminderSeen(reminder) },
                            modifier = Modifier.testTag("task.reminder.seen.${reminder.taskId}")) { Text("标记已读") }
                    }
                }
                HorizontalDivider(color = Tk.colors.divider)
            }
            if (feature.pending.isNotEmpty()) item { TaskSectionLabel("待发送操作") }
            items(feature.pending, key = { "pending.${it.command.taskId}" }) { pending ->
                TaskPendingRow(feature, pending, admission)
            }
            if (feature.items.isEmpty() && !feature.loading && feature.listError == null) item {
                Text("暂无任务", Modifier.fillMaxWidth().padding(Tk.spacing.xxl).testTag("task.empty"), color = Tk.colors.metaText)
            }
            items(feature.items, key = WorkTask::taskId) { task ->
                Column(Modifier.fillMaxWidth()
                    .background(if (feature.selectedTaskId == task.taskId) Tk.colors.selected else MaterialTheme.colorScheme.surface)
                    .clickable(onClick = admission.guard { feature.openTask(task.taskId) })
                    .padding(Tk.spacing.lg).testTag("task.row.${task.taskId}"), verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${taskStatusLabel(task.status)} · ${feature.userName(task.assigneeUid)}", color = Tk.colors.metaText,
                        style = MaterialTheme.typography.labelMedium)
                    task.dueAt?.let { Text((if (taskIsOverdue(task, now)) "已逾期 · " else "截止 · ") + taskDateTimeLabel(it),
                        color = if (taskIsOverdue(task, now)) MaterialTheme.colorScheme.error else Tk.colors.metaText,
                        style = MaterialTheme.typography.labelSmall) }
                }
                HorizontalDivider(color = Tk.colors.divider)
            }
            if (feature.nextCursor != null) item {
                TextButton(onClick = admission.guard(feature::loadMore), enabled = !feature.loading,
                    modifier = Modifier.fillMaxWidth().testTag("task.more")) { Text("加载更多") }
            }
            if (feature.items.size >= TaskFeature.MAX_VISIBLE_TASKS) item {
                Text("列表保留最近加载的 ${TaskFeature.MAX_VISIBLE_TASKS} 项，刷新可返回第一页", Modifier.padding(Tk.spacing.lg),
                    style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskDetail(feature: TaskFeature, admission: UiActionAdmission, now: Long, onShare: (WorkTask) -> Unit) {
    val task = feature.task
    val taskId = feature.selectedTaskId ?: return
    val pending = feature.pending.firstOrNull { it.command.taskId == taskId }
    var cancelling by remember(taskId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("task.detail.$taskId")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = admission.guard(feature::showList), modifier = Modifier.testTag("task.back")) { Text("返回列表") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = admission.guard(feature::refreshTask), modifier = Modifier.testTag("task.detail.refresh")) { Text("刷新") }
            if (task != null) TextButton(onClick = admission.guard { onShare(task) }, modifier = Modifier.testTag("task.share")) { Text("分享") }
        }
        if (feature.loadingTask) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("task.detail.loading"))
        LazyColumn(Modifier.fillMaxSize().testTag("task.detail.content"), contentPadding = PaddingValues(Tk.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Tk.spacing.md)) {
            feature.detailError?.let { item { TaskError(it, Modifier.testTag("task.detail.error")) } }
            if (pending != null) item { TaskPendingRow(feature, pending, admission) }
            if (task == null) {
                item {
                    Text(pending?.command?.draft?.title ?: if (feature.loadingTask) "正在读取任务…" else "任务当前不可用",
                        style = MaterialTheme.typography.titleMedium)
                    pending?.command?.draft?.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                }
            } else {
                item { Text(task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("task.title")) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                        Text(taskStatusLabel(task.status) + if (taskIsOverdue(task, now)) " · 已逾期" else "",
                            color = if (taskIsOverdue(task, now)) MaterialTheme.colorScheme.error else Tk.colors.secondaryText,
                            modifier = Modifier.testTag("task.status"))
                        Text("执行人：${feature.userName(task.assigneeUid)}", modifier = Modifier.testTag("task.assignee"))
                        Text("创建人：${feature.userName(task.creatorUid)}", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
                        Text("截止：${taskDateTimeLabel(task.dueAt)}", modifier = Modifier.testTag("task.deadline"))
                        if (task.contextKind != TaskPolicy.CONTEXT_NONE) Text("关联：${feature.contextName(task.contextKind, task.contextId)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.context"))
                        Text("版本 ${task.revision}", style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
                    }
                }
                if (task.description.isNotBlank()) item { Text(task.description, modifier = Modifier.testTag("task.description")) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm), verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs)) {
                        if (task.creatorUid == feature.myUid) OutlinedButton(onClick = admission.guard(feature::beginEdit),
                            enabled = pending == null && !feature.posting, modifier = Modifier.testTag("task.edit")) { Text("编辑任务") }
                        taskStatusActions(task, feature.myUid).forEach { (status, label) ->
                            OutlinedButton(onClick = admission.guard {
                                if (status == TaskPolicy.CANCELLED) cancelling = true else feature.changeStatus(status)
                            }, enabled = pending == null && !feature.posting, modifier = Modifier.testTag("task.status.$status")) { Text(label) }
                        }
                    }
                }
                item { HorizontalDivider(color = Tk.colors.divider); TaskSectionLabel("操作记录") }
                feature.auditError?.let { item { TaskError(it); TextButton(onClick = admission.guard { feature.loadAudit(false) }) { Text("重试") } } }
                items(feature.audits, key = TaskAudit::revision) { audit ->
                    Text("${feature.userName(audit.actorUid)} ${taskAuditLabel(audit, feature)}\n${taskDateTimeLabel(audit.createdAt)}",
                        style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText, modifier = Modifier.testTag("task.audit.${audit.revision}"))
                }
                if (feature.loadingAudit) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (feature.auditCursor != null) item {
                    TextButton(onClick = admission.guard { feature.loadAudit(true) }, enabled = !feature.loadingAudit,
                        modifier = Modifier.testTag("task.audit.more")) { Text("更多记录") }
                }
            }
        }
    }
    if (cancelling) AlertDialog(onDismissRequest = { cancelling = false }, title = { Text("取消任务？") },
        text = { Text("取消后停止到期提醒，只有创建人可以重新打开。") },
        confirmButton = { TextButton(onClick = admission.guard { cancelling = false; feature.changeStatus(TaskPolicy.CANCELLED) },
            modifier = Modifier.testTag("task.cancel.confirm")) { Text("取消任务") } },
        dismissButton = { TextButton(onClick = { cancelling = false }) { Text("继续保留") } })
}

@Composable
private fun TaskPendingRow(feature: TaskFeature, pending: PendingTaskCommand, admission: UiActionAdmission) {
    val id = pending.command.taskId
    var discarding by remember(pending.command.operationId) { mutableStateOf(false) }
    var inspecting by remember(pending.command.operationId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(Tk.spacing.sm).testTag("task.pending.$id"), verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs)) {
        Text(pending.command.draft?.title ?: "任务状态变更", style = MaterialTheme.typography.bodyMedium)
        Text(if (pending.failure == null) "已保存在本机，等待发送" else pending.failure.orEmpty(),
            style = MaterialTheme.typography.bodySmall, color = if (pending.failure == null) Tk.colors.metaText else MaterialTheme.colorScheme.error)
        if (pending.failure != null) Text("放弃仅移除这次未完成操作，不会删除服务器任务。",
            style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
        Row {
            TextButton(onClick = admission.guard { feature.openTask(id) }) { Text("查看") }
            TextButton(onClick = { inspecting = true }, modifier = Modifier.testTag("task.pending.intent.$id")) { Text("原内容") }
            if (pending.failure != null) {
                TextButton(onClick = admission.guard { feature.retryPending(id) }, enabled = feature.pendingAction == null,
                    modifier = Modifier.testTag("task.pending.retry.$id")) { Text("重试") }
                TextButton(onClick = admission.guard { discarding = true }, enabled = feature.pendingAction == null,
                    modifier = Modifier.testTag("task.pending.discard.$id")) { Text("放弃") }
            }
        }
    }
    if (inspecting) AlertDialog(onDismissRequest = { inspecting = false }, title = { Text("保留的原内容") },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("task.pending.intent.content"),
                    verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                    val command = pending.command
                    Text(when (command.kind) { TaskCommand.CREATE -> "创建任务"; TaskCommand.EDIT -> "编辑任务"; else -> "变更任务状态" })
                    command.draft?.let { draft ->
                        Text(draft.title, style = MaterialTheme.typography.titleMedium)
                        Text(draft.description.ifBlank { "无描述" })
                        Text("执行人：${feature.userName(draft.assigneeUid)}（${draft.assigneeUid}）")
                        Text("截止：${taskDateTimeLabel(draft.dueAt)}")
                        Text("关联：${feature.contextName(draft.contextKind, draft.contextId)}")
                    }
                    command.status?.let { Text("目标状态：${taskStatusLabel(it)}") }
                    if (command.expectedRevision > 0) Text("基于版本 ${command.expectedRevision}")
                    Text("保存于 ${taskDateTimeLabel(command.issuedAt)}", style = MaterialTheme.typography.bodySmall)
                    pending.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { inspecting = false }) { Text("关闭") } })
    if (discarding) AlertDialog(onDismissRequest = { discarding = false }, title = { Text("放弃这次操作？") },
        text = { Text("本机待发送内容将被移除。服务器已有任务保持不变，可读取当前任务后重新编辑。") },
        confirmButton = { TextButton(onClick = admission.guard { discarding = false; feature.discardPending(id) },
            modifier = Modifier.testTag("task.pending.discard.confirm")) { Text("放弃操作") } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text("保留") } })
}

@Composable
internal fun TaskError(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TaskSectionLabel(text: String) {
    Text(text, Modifier.padding(Tk.spacing.md), style = MaterialTheme.typography.labelLarge, color = Tk.colors.metaText)
}

internal fun taskStatusActions(task: WorkTask, uid: String): List<Pair<Int, String>> {
    if (uid != task.creatorUid && uid != task.assigneeUid) return emptyList()
    if (uid != task.creatorUid && task.status == TaskPolicy.CANCELLED) return emptyList()
    return listOf(TaskPolicy.TODO to "重新打开", TaskPolicy.IN_PROGRESS to "开始处理", TaskPolicy.DONE to "完成任务",
        TaskPolicy.CANCELLED to "取消任务").filter { (status, _) ->
        status != task.status && (status != TaskPolicy.CANCELLED || uid == task.creatorUid)
    }
}

private fun taskAuditLabel(audit: TaskAudit, feature: TaskFeature): String = when (audit.action) {
    TaskAudit.CREATED -> "创建了任务"
    TaskAudit.EDITED -> if (audit.previousAssigneeUid != audit.assigneeUid && audit.assigneeUid != null)
        "将任务分配给 ${feature.userName(requireNotNull(audit.assigneeUid))}" else "修改了任务信息"
    TaskAudit.STATUS_CHANGED -> "将状态设为${audit.toStatus?.let(::taskStatusLabel) ?: "未知"}"
    else -> "更新了任务"
}
