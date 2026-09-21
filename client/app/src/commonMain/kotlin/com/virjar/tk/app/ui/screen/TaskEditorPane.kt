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
import com.virjar.tk.app.ui.component.rich.MarkdownText
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.TaskOptions
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditorPane(
    feature: TaskFeature,
    editor: TaskEditorState,
    admission: UiActionAdmission,
    materials: TaskMaterialsUi? = null,
) {
    var choosingAssignee by remember(editor.editorKey) { mutableStateOf(false) }
    var choosingContext by remember(editor.editorKey) { mutableStateOf(false) }
    var previewDescription by remember(editor.editorKey) { mutableStateOf(false) }
    val extensions = feature.supportsTaskDetails
    fun update(change: (TaskEditorState) -> TaskEditorState) {
        val current = feature.editor?.takeIf { it.editorKey == editor.editorKey } ?: return
        val changed = change(current)
        if (changed !== current) feature.updateEditor(changed)
    }
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
            OutlinedTextField(value = editor.title, onValueChange = admission.guard { value: String -> update { it.copy(title = value) } },
                enabled = !feature.posting, singleLine = true, label = { Text("任务标题") },
                supportingText = { Text("${editor.title.length}/${TaskPolicy.MAX_TITLE_LENGTH}") },
                modifier = Modifier.fillMaxWidth().testTag("task.editor.title"))
            if (extensions) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = editor.options.descriptionFormat == TaskOptions.MARKDOWN,
                        onClick = admission.guard { update { it.copy(options = it.options.copy(descriptionFormat = TaskOptions.MARKDOWN)) } },
                        enabled = !feature.posting, label = { Text("Markdown") }, modifier = Modifier.testTag("task.editor.format.markdown"))
                    FilterChip(selected = editor.options.descriptionFormat == TaskOptions.TEXT,
                        onClick = admission.guard { update { it.copy(options = it.options.copy(descriptionFormat = TaskOptions.TEXT)) } },
                        enabled = !feature.posting, label = { Text("纯文本") }, modifier = Modifier.testTag("task.editor.format.text"))
                    TextButton(onClick = { previewDescription = !previewDescription },
                        modifier = Modifier.testTag("task.editor.description.preview")) { Text(if (previewDescription) "编辑描述" else "预览") }
                }
            }
            if (extensions && previewDescription) {
                if (editor.options.descriptionFormat == TaskOptions.MARKDOWN) MarkdownText(editor.description,
                    modifier = Modifier.fillMaxWidth().testTag("task.editor.description.rendered"))
                else Text(editor.description.ifBlank { "无描述" }, modifier = Modifier.testTag("task.editor.description.rendered"))
            } else OutlinedTextField(value = editor.description, onValueChange = admission.guard { value: String -> update { it.copy(description = value) } },
                enabled = !feature.posting, minLines = 4, maxLines = 10, label = { Text("任务描述（可选）") },
                supportingText = { Text("${editor.description.length}/${TaskPolicy.MAX_DESCRIPTION_LENGTH}") },
                modifier = Modifier.fillMaxWidth().testTag("task.editor.description"))
            if (extensions && materials != null) TaskMaterialsEditor(
                editorKey = editor.editorKey,
                documentRefs = editor.options.documentRefs,
                attachments = editor.options.attachments,
                onDocumentRefsChange = { references -> admission.runIfOpen {
                    update { it.copy(options = it.options.copy(documentRefs = references)) }
                } },
                onAddAttachment = { attachment -> admission.runIfOpen {
                    update { current ->
                        when {
                            current.options.attachments.any { it.path == attachment.path } -> current
                            current.options.attachments.size >= TaskOptions.MAX_MATERIALS -> {
                                feature.editorError = "最多添加 ${TaskOptions.MAX_MATERIALS} 个附件"
                                current
                            }
                            else -> current.copy(options = current.options.copy(attachments = current.options.attachments + attachment))
                        }
                    }
                } },
                onRemoveAttachment = { attachment -> admission.runIfOpen {
                    update { it.copy(options = it.options.copy(attachments = it.options.attachments.filterNot { value -> value.path == attachment.path })) }
                } },
                onUploadingChange = { uploading -> admission.runIfOpen { update { it.copy(uploading = uploading) } } },
                materials = materials,
                enabled = !feature.posting,
            )
            OutlinedButton(onClick = admission.guard { feature.searchAssignees(""); choosingAssignee = true },
                enabled = !feature.posting, modifier = Modifier.fillMaxWidth().testTag("task.editor.assignee")) {
                Text("执行人：${feature.userName(editor.assigneeUid)}")
            }
            if (extensions && editor.original == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editor.recurrenceInput.enabled, onCheckedChange = admission.guard { checked: Boolean ->
                        update { it.copy(recurrenceInput = it.recurrenceInput.copy(enabled = checked)) }
                    }, enabled = !feature.posting, modifier = Modifier.testTag("task.editor.recurrence.enabled"))
                    Text("重复任务", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (extensions && editor.original == null && editor.recurrenceInput.enabled) {
                TaskRecurrenceFields(editor.recurrenceInput, !feature.posting) { change ->
                    admission.runIfOpen { update { it.copy(recurrenceInput = change(it.recurrenceInput)) } }
                }
            } else {
                if (extensions) TaskDateTimeFields("开始时间（可选）", editor.start, "task.editor.start", "task.editor.start.clear", "清除开始", !feature.posting) { change ->
                    admission.runIfOpen { update { it.copy(start = change(it.start)) } }
                }
                TaskDateTimeFields("截止时间（可选）", editor.deadline, "task.editor", "task.editor.deadline.clear", "清除截止", !feature.posting) { change ->
                    admission.runIfOpen { update { it.copy(deadline = change(it.deadline)) } }
                }
                if (editor.originalDetails?.series != null) Text("这里只修改本期任务，不改变重复计划或其他期次。",
                    style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            }
            Text("关联上下文（可选）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                listOf(TaskPolicy.CONTEXT_NONE to "无关联", TaskPolicy.CONTEXT_GROUP to "群聊", TaskPolicy.CONTEXT_ORGANIZATION to "部门")
                    .forEach { (kind, label) ->
                        FilterChip(selected = editor.contextKind == kind, onClick = admission.guard {
                            if (kind != editor.contextKind) update { current ->
                                current.copy(contextKind = kind, contextId = "", options = current.options.copy(
                                    shareToGroup = current.original == null && kind == TaskPolicy.CONTEXT_GROUP))
                            }
                            choosingContext = kind != TaskPolicy.CONTEXT_NONE
                        }, enabled = !feature.posting, label = { Text(label) }, modifier = Modifier.testTag("task.editor.context.$kind"))
                    }
            }
            if (editor.contextKind != TaskPolicy.CONTEXT_NONE) OutlinedButton(onClick = admission.guard { choosingContext = true },
                enabled = !feature.posting, modifier = Modifier.fillMaxWidth().testTag("task.editor.context.choose")) {
                Text(if (editor.contextId.isEmpty()) "请选择${if (editor.contextKind == TaskPolicy.CONTEXT_GROUP) "群聊" else "部门"}"
                    else feature.contextName(editor.contextKind, editor.contextId))
            }
            if (extensions && editor.contextKind == TaskPolicy.CONTEXT_GROUP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editor.options.shareToGroup, onCheckedChange = admission.guard { checked: Boolean ->
                        update { it.copy(options = it.options.copy(shareToGroup = checked)) }
                    }, enabled = !feature.posting, modifier = Modifier.testTag("task.editor.shareToGroup"))
                    Text("向当前群成员公开此任务", style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (editor.options.shareToGroup) "群成员可查看任务与附件，文档仍按原权限打开；离群后失去群共享访问。" else "仅创建人与当前执行人可查看，不计入群待办卡片。",
                    style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            } else Text("关联用于说明任务背景。任务仅创建人与当前执行人可访问。", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            Spacer(Modifier.height(Tk.spacing.sm))
        }
        HorizontalDivider(color = Tk.colors.divider)
        Button(onClick = admission.guard(feature::saveEditor), enabled = !feature.posting && !editor.uploading,
            modifier = Modifier.fillMaxWidth().padding(Tk.spacing.lg).testTag("task.editor.save")) {
            Text(if (feature.posting) "正在保存…" else if (editor.uploading) "请完成或移除未完成附件" else "保存任务")
        }
    }
    if (choosingAssignee) TaskAssigneeDialog(feature, admission, onDismiss = { choosingAssignee = false })
    if (choosingContext) TaskContextDialog(feature, admission, onDismiss = { choosingContext = false })
}

@Composable
private fun TaskDateTimeFields(
    label: String, input: TaskDeadlineInput, tag: String, clearTag: String, clearLabel: String,
    enabled: Boolean, onChange: ((TaskDeadlineInput) -> TaskDeadlineInput) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
        OutlinedTextField(value = input.date, onValueChange = { value -> onChange { it.copy(date = value) } },
            enabled = enabled, singleLine = true, label = { Text("日期") }, placeholder = { Text("2026-09-08") },
            modifier = Modifier.weight(1.2f).testTag("$tag.date"))
        OutlinedTextField(value = input.time, onValueChange = { value -> onChange { it.copy(time = value) } },
            enabled = enabled, singleLine = true, label = { Text("时间") }, placeholder = { Text("18:00") },
            modifier = Modifier.weight(1f).testTag("$tag.time"))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("本地时区：${TimeZone.currentSystemDefault().id}", style = MaterialTheme.typography.labelSmall,
            color = Tk.colors.metaText, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange { TaskDeadlineInput() } }, enabled = enabled,
            modifier = Modifier.testTag(clearTag)) { Text(clearLabel) }
    }
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
