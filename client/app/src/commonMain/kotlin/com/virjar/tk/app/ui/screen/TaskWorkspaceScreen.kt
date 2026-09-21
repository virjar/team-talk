package com.virjar.tk.app.ui.screen

import com.virjar.tk.shared.platform.platformCurrentTimeMillis

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
import com.virjar.tk.protocol.model.TaskOptions
import com.virjar.tk.protocol.model.TaskQuery
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
    materials: TaskMaterialsUi? = null,
) {
    var now by remember { mutableLongStateOf(platformCurrentTimeMillis()) }
    LaunchedEffect(feature) { while (true) { delay(30_000); now = platformCurrentTimeMillis() } }
    Column(modifier.fillMaxSize().testTag("task.workspace")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            Text("待办任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                        editor != null -> TaskEditorPane(feature, editor, actionAdmission, materials)
                        feature.selectedTaskId != null -> TaskDetail(feature, actionAdmission, now,
                            onShare = onShareTask ?: feature::beginShare, materials = materials)
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
        if (feature.groupFilter != null) Text("群内共享待办", Modifier.padding(horizontal = Tk.spacing.lg), style = MaterialTheme.typography.labelLarge)
        if (feature.supportsTaskDetails) {
            FilterChip(selected = feature.onlyOpen, onClick = admission.guard { feature.updateOpenFilter(!feature.onlyOpen) },
                label = { Text(if (feature.onlyStarted) "只看已开始的未完成" else "只看未完成") }, modifier = Modifier.padding(horizontal = Tk.spacing.sm).testTag("task.filter.open"))
            feature.summary?.let { summary ->
                Text("共 ${summary.totalCount} 项 · 未完成 ${summary.openCount} 项 · 逾期 ${summary.overdueCount} 项",
                    Modifier.padding(horizontal = Tk.spacing.lg).testTag("task.summary"), style = MaterialTheme.typography.labelSmall)
                if (!feature.onlyOpen && summary.completedCount > 0) Text(
                    "已完成 ${summary.completedCount} 项 · 平均处理 " + (summary.averageProcessingMillis?.let(::taskDurationLabel) ?: "暂无完整记录"),
                    Modifier.padding(horizontal = Tk.spacing.lg).testTag("task.summary.processing"), style = MaterialTheme.typography.labelSmall,
                    color = Tk.colors.metaText)
            }
        }
        if (feature.stale) Text("显示本地内容，联网后同步", modifier = Modifier.padding(horizontal = Tk.spacing.lg).testTag("task.stale"),
            style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
        feature.listError?.let { TaskError(it, Modifier.padding(Tk.spacing.lg).testTag("task.list.error")) }
        if (feature.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("task.list.loading"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("task.list")) {
            if (feature.reminders.isNotEmpty()) item { TaskSectionLabel("待办提醒") }
            items(feature.reminders, key = { "reminder.${it.taskId}.${it.remindedAt}" }) { reminder ->
                Column(Modifier.fillMaxWidth().padding(Tk.spacing.md).testTag("task.reminder.${reminder.taskId}")) {
                    Text(feature.reminderTasks[reminder.taskId]?.title ?: "任务待办提醒", fontWeight = FontWeight.Medium)
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
            items(feature.pending, key = { "pending.${it.taskId}" }) { pending ->
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
private fun TaskDetail(feature: TaskFeature, admission: UiActionAdmission, now: Long, onShare: (WorkTask) -> Unit, materials: TaskMaterialsUi?) {
    val task = feature.task
    val taskId = feature.selectedTaskId ?: return
    val pending = feature.pending.firstOrNull { it.taskId == taskId }
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
                    Text(pending?.draft?.title ?: if (feature.loadingTask) "正在读取任务…" else "任务当前不可用",
                        style = MaterialTheme.typography.titleMedium)
                    pending?.draft?.description?.takeIf(String::isNotBlank)?.let { Text(it) }
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
                if (task.description.isNotBlank()) item {
                    if (feature.details?.options?.descriptionFormat == TaskOptions.MARKDOWN) {
                        com.virjar.tk.app.ui.component.rich.MarkdownText(task.description, modifier = Modifier.testTag("task.description"))
                    } else Text(task.description, modifier = Modifier.testTag("task.description"))
                }
                feature.details?.let { details ->
                    item { TaskMaterialsContent(details.options.documentRefs, details.options.attachments, materials) }
                    item { TaskDetailExtras(feature, admission) }
                }
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
                    Column(Modifier.testTag("task.audit.${audit.revision}"), verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs)) {
                        val deferral = feature.deferrals[audit.revision]
                        Text("${feature.userName(audit.actorUid)} ${if (deferral != null) "延期了待办" else taskAuditLabel(audit, feature)}\n${taskDateTimeLabel(audit.createdAt)}",
                            style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
                        if (deferral != null) {
                            Text("${taskDateTimeLabel(deferral.previousDueAt)} → ${taskDateTimeLabel(deferral.newDueAt)}", style = MaterialTheme.typography.bodySmall)
                            Text("理由：${deferral.reason}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.deferral.${audit.revision}"))
                        }
                    }
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
        text = { Text("取消后停止提醒，只有创建人可以重新打开。") },
        confirmButton = { TextButton(onClick = admission.guard { cancelling = false; feature.changeStatus(TaskPolicy.CANCELLED) },
            modifier = Modifier.testTag("task.cancel.confirm")) { Text("取消任务") } },
        dismissButton = { TextButton(onClick = { cancelling = false }) { Text("继续保留") } })
}

@Composable
private fun TaskPendingRow(feature: TaskFeature, pending: PendingTaskCommand, admission: UiActionAdmission) {
    val id = pending.taskId
    var discarding by remember(pending.operationId) { mutableStateOf(false) }
    var inspecting by remember(pending.operationId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(Tk.spacing.sm).testTag("task.pending.$id"), verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs)) {
        Text(pending.draft?.title ?: pendingTaskActionLabel(pending), style = MaterialTheme.typography.bodyMedium)
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
                    val command = pending
                    Text(pendingTaskActionLabel(command))
                    command.draft?.let { draft ->
                        Text(draft.title, style = MaterialTheme.typography.titleMedium)
                        Text(draft.description.ifBlank { "无描述" })
                        Text("执行人：${feature.userName(draft.assigneeUid)}（${draft.assigneeUid}）")
                        Text("截止：${taskDateTimeLabel(draft.dueAt)}")
                        Text("关联：${feature.contextName(draft.contextKind, draft.contextId)}")
                    }
                    command.status?.let { Text("目标状态：${taskStatusLabel(it)}") }
                    command.detailsCommand?.let { detail ->
                        detail.deferDueAt?.let { Text("延期至：${taskDateTimeLabel(it)}") }
                        detail.reason?.let { Text("延期理由：$it") }
                        detail.options?.let { options ->
                            Text("开始：${taskDateTimeLabel(options.startsAt)}")
                            if (options.shareToGroup) Text("群成员可查看")
                            options.documentRefs.forEach { Text("文档：${it.title}") }
                            options.attachments.forEach { Text("附件：${it.name}") }
                        }
                        detail.recurrenceRule?.let { Text("${taskRecurrenceLabel(it)} ${it.startLocalTime} 开始，${it.dueLocalTime} 截止（${it.timeZone}）") }
                    }
                    command.seriesCommand?.let { Text(if (it.enabled) "恢复未来期次" else "停止未来期次") }
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

private fun pendingTaskActionLabel(pending: PendingTaskCommand): String = when {
    pending.seriesCommand != null -> "变更周期待办"
    pending.detailsCommand?.kind == com.virjar.tk.protocol.model.TaskDetailsCommand.DEFER -> "延期待办"
    pending.kind == TaskCommand.CREATE -> "创建待办"
    pending.kind == TaskCommand.EDIT -> "编辑待办"
    else -> "变更待办状态"
}
