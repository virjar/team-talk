package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.app.navigation.feature.task.TaskRecurrenceInput
import com.virjar.tk.app.navigation.feature.task.taskRecurrenceLabel
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.TaskRecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskRecurrenceFields(
    input: TaskRecurrenceInput,
    enabled: Boolean,
    onChange: ((TaskRecurrenceInput) -> TaskRecurrenceInput) -> Unit,
) {
    var choosingDate by remember { mutableStateOf(false) }
    Text("重复安排", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
        listOf(TaskRecurrenceRule.WEEKLY to "按周", TaskRecurrenceRule.MONTHLY to "按月").forEach { (frequency, label) ->
            FilterChip(selected = input.frequency == frequency, onClick = { onChange { it.copy(frequency = frequency) } },
                enabled = enabled, label = { Text(label) }, modifier = Modifier.testTag("task.editor.recurrence.frequency.$frequency"))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(input.interval, { value -> onChange { it.copy(interval = value) } }, enabled = enabled,
            singleLine = true, label = { Text("每隔") }, suffix = { Text(if (input.frequency == TaskRecurrenceRule.WEEKLY) "周" else "个月") },
            modifier = Modifier.weight(1f).testTag("task.editor.recurrence.interval"))
        if (input.frequency == TaskRecurrenceRule.WEEKLY) FilterChip(
            selected = input.interval == "2", onClick = { onChange { it.copy(interval = "2") } }, enabled = enabled,
            label = { Text("双周") }, modifier = Modifier.testTag("task.editor.recurrence.biweekly"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(input.firstDate, { value -> onChange { it.copy(firstDate = value) } }, enabled = enabled,
            singleLine = true, label = { Text("首次日期") }, placeholder = { Text("2026-09-14") },
            modifier = Modifier.weight(1f).testTag("task.editor.recurrence.firstDate"))
        TextButton(onClick = { choosingDate = true }, enabled = enabled,
            modifier = Modifier.testTag("task.editor.recurrence.calendar")) { Text("日历选择") }
    }
    runCatching { input.rule() }.getOrNull()?.let { rule ->
        Text("${taskRecurrenceLabel(rule)} · 从 ${rule.firstDate} 起", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("task.editor.recurrence.summary"))
    }
    if (input.frequency == TaskRecurrenceRule.MONTHLY) Text(
        "当月没有指定日期时，在当月最后一天执行；下个月仍按原日期安排。",
        style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
        OutlinedTextField(input.startLocalTime, { value -> onChange { it.copy(startLocalTime = value) } }, enabled = enabled,
            singleLine = true, label = { Text("开始时间") }, modifier = Modifier.weight(1f).testTag("task.editor.recurrence.start"))
        OutlinedTextField(input.dueLocalTime, { value -> onChange { it.copy(dueLocalTime = value) } }, enabled = enabled,
            singleLine = true, label = { Text("当天截止") }, modifier = Modifier.weight(1f).testTag("task.editor.recurrence.due"))
    }
    OutlinedTextField(input.timeZone, { value -> onChange { it.copy(timeZone = value) } }, enabled = enabled, singleLine = true,
        label = { Text("时区") }, placeholder = { Text("Asia/Shanghai") },
        modifier = Modifier.fillMaxWidth().testTag("task.editor.recurrence.zone"))
    Text("创建时，本期尚未截止就从本期开始；已结束的历史期次不补建。每期单独记录，停止计划不会取消已生成任务。",
        style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
    if (choosingDate) {
        // Material DatePicker 的日期是 UTC 零点，不能按设备时区再移动一天。
        val datePicker = rememberDatePickerState(initialSelectedDateMillis = runCatching {
            LocalDate.parse(input.firstDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull())
        DatePickerDialog(onDismissRequest = { choosingDate = false }, confirmButton = {
            TextButton(onClick = {
                datePicker.selectedDateMillis?.let { selected ->
                    onChange { it.copy(firstDate = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                }
                choosingDate = false
            }, enabled = datePicker.selectedDateMillis != null, modifier = Modifier.testTag("task.editor.recurrence.calendar.confirm")) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }) {
            DatePicker(state = datePicker)
        }
    }
}
