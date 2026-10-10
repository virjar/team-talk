package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.protocol.model.ScheduledMessage
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours

/**
 * 定时发送对话框（MSG-01/F3）：同屏承载既有待发列表（可取消）与新建表单。
 * 时间语义与任务域一致——日期/时间为文本输入（本地时区），快捷项只回填字段。
 * 提交取当前输入框的权威 Markdown 组装富文本正文；服务端到点走完整发送管道。
 */
@Composable
internal fun ChatScheduleSendDialog(
    viewModel: ChatViewModel,
    composerMarkdown: String,
    buildBody: (markdown: String) -> com.virjar.tk.protocol.body.MessageBody?,
    onDismiss: () -> Unit,
    onScheduled: (deliverAt: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<List<ScheduledMessage>>(emptyList()) }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        pending = viewModel.loadScheduledMessages()
    }
    LaunchedEffect(Unit) { refresh() }

    fun applyQuick(daysAhead: Long, fixedTime: LocalTime?) {
        val zone = TimeZone.currentSystemDefault()
        val now = kotlin.time.Instant.fromEpochMilliseconds(
            com.virjar.tk.shared.platform.platformCurrentTimeMillis(),
        ).toLocalDateTime(zone)
        val targetDate = now.date.plus(daysAhead, DateTimeUnit.DAY)
        val target = targetDate.atTime(fixedTime ?: LocalTime(now.hour + 1, now.minute))
            .toInstant(zone)
        val local = target.toLocalDateTime(zone)
        date = local.date.toString()
        time = "%02d:%02d".format(local.hour, local.minute)
        inputError = null
    }

    fun submit() {
        val deliverAt = parseScheduleTime(date, time)
        if (deliverAt == null) {
            inputError = "日期或时间格式无效（yyyy-MM-dd HH:mm）"
            return
        }
        if (deliverAt <= com.virjar.tk.shared.platform.platformCurrentTimeMillis()) {
            inputError = "投递时间必须晚于当前时间"
            return
        }
        val body = buildBody(composerMarkdown)
        if (body == null) {
            inputError = "消息正文组装失败，请检查输入"
            return
        }
        submitting = true
        scope.launch {
            val ok = viewModel.scheduleMessage(body, deliverAt)
            submitting = false
            if (ok) {
                refresh()
                onScheduled(deliverAt)
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时发送") },
        text = {
            Column(
                modifier = Modifier
                    .testTag("chat.schedule.dialog")
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm),
            ) {
                Text("消息将以当前输入框内容为准，到点由服务器投递。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.xs)) {
                    listOf(
                        "1小时后" to { applyQuick(0, null) },
                        "明天 9:00" to { applyQuick(1, LocalTime(9, 0)) },
                        "后天 9:00" to { applyQuick(2, LocalTime(9, 0)) },
                    ).forEach { (label, apply) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { apply() }
                                .padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.xs)
                                .testTag("chat.schedule.quick.$label"),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it; inputError = null },
                        label = { Text("日期 yyyy-MM-dd") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("chat.schedule.date"),
                    )
                    OutlinedTextField(
                        value = time,
                        onValueChange = { time = it; inputError = null },
                        label = { Text("时间 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("chat.schedule.time"),
                    )
                }
                if (inputError != null) {
                    Text(inputError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (pending.isNotEmpty()) {
                    Text("已定时（${pending.size}）", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(pending, key = { it.scheduledId }) { item ->
                            val statusLabel = if (item.status == 3) " · 投递失败" else ""
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = Tk.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        formatChatTime(item.deliverAt) + statusLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (item.status == 3) MaterialTheme.colorScheme.error else Tk.colors.secondaryText,
                                    )
                                    Text(
                                        item.body?.let { body ->
                                            (body as? RichTextBody)?.markdown?.lines()
                                                ?.firstOrNull { it.isNotBlank() } ?: "（消息）"
                                        } ?: "（消息）",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (item.status == 0) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                if (viewModel.cancelScheduledMessage(item.scheduledId, item.operationId)) refresh()
                                            }
                                        },
                                        modifier = Modifier.testTag("chat.schedule.cancel.${item.scheduledId.take(8)}"),
                                    ) { Text("取消") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::submit,
                enabled = !submitting && composerMarkdown.isNotBlank(),
                modifier = Modifier.testTag("chat.schedule.submit"),
            ) { Text("定时发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        modifier = modifier,
    )
}

private fun parseScheduleTime(date: String, time: String): Long? = runCatching {
    LocalDate.parse(date.trim()).atTime(LocalTime.parse(time.trim())).toInstant(TimeZone.currentSystemDefault())
        .toEpochMilliseconds()
}.getOrNull()
