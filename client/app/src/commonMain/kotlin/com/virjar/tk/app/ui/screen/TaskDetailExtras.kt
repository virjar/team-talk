package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.app.navigation.feature.task.TaskDeadlineInput
import com.virjar.tk.app.navigation.feature.task.TaskFeature
import com.virjar.tk.app.navigation.feature.task.taskDateTimeLabel
import com.virjar.tk.app.navigation.feature.task.taskRecurrenceLabel
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.TaskDetails
import com.virjar.tk.protocol.model.TaskDetailsCommand
import com.virjar.tk.protocol.model.TaskPolicy
import java.time.ZoneId

@Composable
internal fun TaskDetailExtras(feature: TaskFeature, admission: UiActionAdmission) {
    if (!feature.supportsTaskDetails) return
    val details = feature.details?.takeIf { it.task.taskId == feature.task?.taskId && it.task.revision == feature.task?.revision } ?: return
    val task = details.task
    val metrics = details.metrics
    var deferring by remember(task.taskId) { mutableStateOf<TaskDetails?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
        Text("开始：${details.options.startsAt?.let { taskDateTimeLabel(it) } ?: "未设置"}",
            modifier = Modifier.testTag("task.startsAt"))
        Text("原承诺截止：${if (metrics.historyKnown) taskDateTimeLabel(metrics.originalDueAt) else "历史未记录"}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.originalDeadline"))
        Text("延期：${metrics.deferralCount} 次${if (metrics.historyKnown) "" else "（仅统计已有记录）"}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.deferralCount"))
        Text("最近延期：${metrics.lastDeferredAt?.let { taskDateTimeLabel(it) } ?: "暂无记录"}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.lastDeferredAt"))
        Text("处理耗时：${metrics.processingMillis?.let(::taskDurationLabel) ?: if (task.status == TaskPolicy.DONE) "历史未记录" else "尚未完成"}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.processingTime"))
        if (task.dueAt != null && task.assigneeUid == feature.myUid && task.status in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS)) {
            OutlinedButton(onClick = admission.guard { deferring = details },
                enabled = !feature.posting && feature.pending.none { it.taskId == task.taskId },
                modifier = Modifier.testTag("task.defer")) { Text("延期") }
        }
        details.series?.let { series ->
            val rule = series.recurrenceRule
            Text("${taskRecurrenceLabel(rule)} ${rule.startLocalTime} 开始 · ${rule.dueLocalTime} 截止（${rule.timeZone}）",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("task.series.rule"))
            Text("首次日期：${rule.firstDate}", style = MaterialTheme.typography.bodySmall)
            Text(if (series.enabled) "下期开始：${taskDateTimeLabel(series.nextOccurrenceAt, ZoneId.of(rule.timeZone))}" else "重复计划已停用",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task.series.next"))
            Text("每期使用固定模板；修改本期不影响后续期次。停用计划不取消已生成任务。",
                style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            if (series.creatorUid == feature.myUid) TextButton(onClick = admission.guard {
                feature.setSeriesEnabled(series, !series.enabled)
            }, enabled = !feature.posting && feature.pending.none { it.taskId == series.seriesId },
                modifier = Modifier.testTag("task.series.toggle")) { Text(if (series.enabled) "停用重复计划" else "启用重复计划") }
        }
    }
    deferring?.let { original ->
        TaskDeferralDialog(original, feature, admission, onDismiss = { deferring = null })
    }
}

@Composable
private fun TaskDeferralDialog(original: TaskDetails, feature: TaskFeature, admission: UiActionAdmission, onDismiss: () -> Unit) {
    var deadline by remember(original) { mutableStateOf(TaskDeadlineInput.from(original.task.dueAt)) }
    var reason by remember(original) { mutableStateOf("") }
    var error by remember(original) { mutableStateOf<String?>(null) }
    val submitting = feature.posting || feature.pending.any { it.taskId == original.task.taskId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("延期说明") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            Text("当前截止：${taskDateTimeLabel(original.task.dueAt)}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(deadline.date, { deadline = deadline.copy(date = it) }, enabled = !submitting,
                singleLine = true, label = { Text("新的截止日期") }, placeholder = { Text("2026-09-15") },
                modifier = Modifier.fillMaxWidth().testTag("task.defer.date"))
            OutlinedTextField(deadline.time, { deadline = deadline.copy(time = it) }, enabled = !submitting,
                singleLine = true, label = { Text("时间") }, placeholder = { Text("18:00") },
                modifier = Modifier.fillMaxWidth().testTag("task.defer.time"))
            Text("本地时区：${ZoneId.systemDefault().id}", style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
            OutlinedTextField(reason, { reason = it }, enabled = !submitting, minLines = 2, maxLines = 5,
                label = { Text("延期原因（必填）") },
                supportingText = { Text("${reason.length}/${TaskDetailsCommand.MAX_REASON}") },
                modifier = Modifier.fillMaxWidth().testTag("task.defer.reason"))
            if (feature.details?.task?.revision != original.task.revision) Text("任务已有变化，提交后将核对冲突，当前输入不会覆盖他人的更新。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("task.defer.error")) }
        }
    }, confirmButton = {
        TextButton(onClick = admission.guard {
            val dueAt = try { taskDeferralDueAt(deadline, reason, original.task.dueAt) }
            catch (failure: IllegalArgumentException) {
                error = failure.message ?: "请检查延期信息"
                return@guard
            }
            feature.deferTask(original, dueAt, reason.trim())
            onDismiss()
        }, enabled = !submitting, modifier = Modifier.testTag("task.defer.submit")) { Text("提交延期") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** 表单只给明确输入反馈；实际截止、权限和 revision 仍由服务端在事务中裁决。 */
internal fun taskDeferralDueAt(
    deadline: TaskDeadlineInput,
    reason: String,
    previousDueAt: Long?,
    now: Long = System.currentTimeMillis(),
): Long {
    requireNotNull(previousDueAt) { "未设置截止时间的待办不能延期" }
    require(reason.isNotBlank()) { "请填写延期原因" }
    require(reason.trim().length <= TaskDetailsCommand.MAX_REASON) { "延期原因最多 ${TaskDetailsCommand.MAX_REASON} 字" }
    val dueAt = requireNotNull(deadline.epochMillis()) { "请填写新的截止时间" }
    require(dueAt > now && dueAt > previousDueAt) { "新截止必须晚于当前截止，并且在将来" }
    return dueAt
}

internal fun taskDurationLabel(millis: Long): String {
    val minutes = millis.coerceAtLeast(0L) / 60_000
    return when {
        minutes < 1 -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes < 24 * 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        else -> "${minutes / (24 * 60)} 天 ${(minutes / 60) % 24} 小时"
    }
}
