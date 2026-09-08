package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.virjar.tk.app.navigation.feature.task.*
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.TaskPolicy
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditorPane(feature: TaskFeature, editor: TaskEditorState, admission: UiActionAdmission) {
    var choosingAssignee by remember(editor.original?.taskId) { mutableStateOf(false) }
    var choosingContext by remember(editor.original?.taskId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("task.editor")) {
        Row(Modifier.fillMaxWidth().padding(Tk.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(if (editor.original == null) "新建任务" else "编辑任务", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = admission.guard { feature.handleBack() }, enabled = !feature.posting,
                modifier = Modifier.testTag("task.editor.cancel")) { Text("取消") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Tk.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Tk.spacing.md)) {
            feature.editorError?.let { TaskError(it, Modifier.testTag("task.editor.error")) }
            if (editor.original != null && feature.task?.revision?.let { it > editor.original.revision } == true) {
                Text("任务已有新版本，当前输入已保留；保存时将检查冲突。", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.editor.remoteChanged"))
            }
            OutlinedTextField(value = editor.title, onValueChange = admission.guard { value: String -> feature.updateEditor(editor.copy(title = value)) },
                enabled = !feature.posting, singleLine = true, label = { Text("任务标题") },
                supportingText = { Text("${editor.title.length}/${TaskPolicy.MAX_TITLE_LENGTH}") },
                modifier = Modifier.fillMaxWidth().testTag("task.editor.title"))
            OutlinedTextField(value = editor.description, onValueChange = admission.guard { value: String -> feature.updateEditor(editor.copy(description = value)) },
                enabled = !feature.posting, minLines = 4, maxLines = 10, label = { Text("任务描述（可选）") },
                supportingText = { Text("${editor.description.length}/${TaskPolicy.MAX_DESCRIPTION_LENGTH}") },
                modifier = Modifier.fillMaxWidth().testTag("task.editor.description"))
            OutlinedButton(onClick = admission.guard { feature.searchAssignees(""); choosingAssignee = true },
                enabled = !feature.posting, modifier = Modifier.fillMaxWidth().testTag("task.editor.assignee")) {
                Text("执行人：${feature.userName(editor.assigneeUid)}")
            }
            Text("截止时间（可选）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                OutlinedTextField(value = editor.deadline.date, onValueChange = admission.guard { value: String ->
                    feature.updateEditor(editor.copy(deadline = editor.deadline.copy(date = value)))
                }, enabled = !feature.posting, singleLine = true, label = { Text("日期") }, placeholder = { Text("2026-09-08") },
                    modifier = Modifier.weight(1.2f).testTag("task.editor.date"))
                OutlinedTextField(value = editor.deadline.time, onValueChange = admission.guard { value: String ->
                    feature.updateEditor(editor.copy(deadline = editor.deadline.copy(time = value)))
                }, enabled = !feature.posting, singleLine = true, label = { Text("时间") }, placeholder = { Text("18:00") },
                    modifier = Modifier.weight(1f).testTag("task.editor.time"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本地时区：${ZoneId.systemDefault().id}", style = MaterialTheme.typography.labelSmall,
                    color = Tk.colors.metaText, modifier = Modifier.weight(1f))
                TextButton(onClick = admission.guard { feature.updateEditor(editor.copy(deadline = TaskDeadlineInput())) },
                    enabled = !feature.posting, modifier = Modifier.testTag("task.editor.deadline.clear")) { Text("清除截止") }
            }
            Text("关联上下文（可选）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                listOf(TaskPolicy.CONTEXT_NONE to "无关联", TaskPolicy.CONTEXT_GROUP to "群聊", TaskPolicy.CONTEXT_ORGANIZATION to "部门")
                    .forEach { (kind, label) ->
                        FilterChip(selected = editor.contextKind == kind, onClick = admission.guard {
                            if (kind != editor.contextKind) feature.updateEditor(editor.copy(contextKind = kind, contextId = ""))
                            choosingContext = kind != TaskPolicy.CONTEXT_NONE
                        }, enabled = !feature.posting, label = { Text(label) }, modifier = Modifier.testTag("task.editor.context.$kind"))
                    }
            }
            if (editor.contextKind != TaskPolicy.CONTEXT_NONE) OutlinedButton(onClick = admission.guard { choosingContext = true },
                enabled = !feature.posting, modifier = Modifier.fillMaxWidth().testTag("task.editor.context.choose")) {
                Text(if (editor.contextId.isEmpty()) "请选择${if (editor.contextKind == TaskPolicy.CONTEXT_GROUP) "群聊" else "部门"}"
                    else feature.contextName(editor.contextKind, editor.contextId))
            }
            Text("关联用于说明任务背景。任务仅创建人与当前执行人可访问。", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            Spacer(Modifier.height(Tk.spacing.sm))
        }
        HorizontalDivider(color = Tk.colors.divider)
        Button(onClick = admission.guard(feature::saveEditor), enabled = !feature.posting,
            modifier = Modifier.fillMaxWidth().padding(Tk.spacing.lg).testTag("task.editor.save")) {
            Text(if (feature.posting) "正在保存…" else "保存任务")
        }
    }
    if (choosingAssignee) TaskAssigneeDialog(feature, admission, onDismiss = { choosingAssignee = false })
    if (choosingContext) TaskContextDialog(feature, admission, onDismiss = { choosingContext = false })
}

@Composable
private fun TaskAssigneeDialog(feature: TaskFeature, admission: UiActionAdmission, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择执行人") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            OutlinedTextField(value = feature.assigneeQuery, onValueChange = admission.guard(feature::searchAssignees),
                singleLine = true, label = { Text("搜索姓名、用户名或手机号") }, modifier = Modifier.fillMaxWidth().testTag("task.assignee.query"))
            if (feature.findingAssignees) LinearProgressIndicator(Modifier.fillMaxWidth())
            feature.assigneeError?.let { TaskError(it) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = Tk.dimens.listItemHeight * 5)) {
                item {
                    Text("分配给自己", Modifier.fillMaxWidth().clickable(onClick = admission.guard {
                        feature.editor?.let { feature.updateEditor(it.copy(assigneeUid = feature.myUid)) }; onDismiss()
                    }).padding(Tk.spacing.md).testTag("task.assignee.self"))
                }
                items(feature.assigneeCandidates.filterNot { it.uid == feature.myUid }, key = { it.uid }) { user ->
                    Column(Modifier.fillMaxWidth().clickable(onClick = admission.guard {
                        feature.editor?.let { feature.updateEditor(it.copy(assigneeUid = user.uid)) }; onDismiss()
                    }).padding(Tk.spacing.md).testTag("task.assignee.${user.uid}")) {
                        Text(user.name.ifBlank { user.username })
                        Text(user.username, style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
                    }
                }
                if (feature.assigneeQuery.isNotBlank() && !feature.findingAssignees && feature.assigneeCandidates.isEmpty() && feature.assigneeError == null)
                    item { Text("没有找到成员", Modifier.padding(Tk.spacing.md), color = Tk.colors.metaText) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TaskContextDialog(feature: TaskFeature, admission: UiActionAdmission, onDismiss: () -> Unit) {
    val kind = feature.editor?.contextKind ?: return
    var query by remember(kind) { mutableStateOf("") }
    val matching = feature.contextOptions.filter { it.kind == kind && it.name.contains(query, ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (kind == TaskPolicy.CONTEXT_GROUP) "关联群聊" else "关联部门") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, singleLine = true,
                label = { Text("按名称筛选") }, modifier = Modifier.fillMaxWidth().testTag("task.context.query"))
            if (feature.loadingContexts) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (kind == TaskPolicy.CONTEXT_ORGANIZATION) feature.contextError?.let {
                TaskError(it)
                TextButton(onClick = admission.guard(feature::loadTaskChoices)) { Text("重新加载") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = Tk.dimens.listItemHeight * 5)) {
                items(matching.take(100), key = TaskContextOption::id) { option ->
                    Text(option.name, Modifier.fillMaxWidth().clickable(onClick = admission.guard {
                        feature.editor?.let { feature.updateEditor(it.copy(contextKind = kind, contextId = option.id)) }; onDismiss()
                    }).padding(Tk.spacing.md).testTag("task.context.${option.id}"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (matching.isEmpty() && !feature.loadingContexts) item { Text("暂无可选内容", Modifier.padding(Tk.spacing.md), color = Tk.colors.metaText) }
                if (matching.size > 100) item { Text("匹配较多，请缩小筛选范围", Modifier.padding(Tk.spacing.md), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun TaskShareDialog(feature: TaskFeature, admission: UiActionAdmission) {
    val task = feature.sharing ?: return
    var query by remember(task.taskId) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (feature.sharingTo == null) feature.sharing = null }, title = { Text("分享到会话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("引用不会改变任务权限，接收者仍需有权访问。", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, singleLine = true,
                label = { Text("筛选会话") }, modifier = Modifier.fillMaxWidth().testTag("task.share.query"))
            feature.shareError?.let { TaskError(it, Modifier.testTag("task.share.error")) }
            if (feature.sharingTo != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = Tk.dimens.listItemHeight * 5)) {
                items(feature.shareOptions.filter { it.name.contains(query, ignoreCase = true) }.take(100), key = TaskShareOption::chatId) { option ->
                    Text(option.name, Modifier.fillMaxWidth().clickable(enabled = feature.sharingTo == null,
                        onClick = admission.guard { feature.share(task, option.chatId) })
                        .padding(Tk.spacing.md).testTag("task.share.chat.${option.chatId}"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { feature.sharing = null }, enabled = feature.sharingTo == null) { Text("取消") } })
}
