package com.virjar.tk.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.virjar.tk.app.navigation.feature.MessageActionsFeature
import com.virjar.tk.app.navigation.feature.task.taskStatusLabel
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.WorkTask
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 只选择现有任务；发送接入聊天的持久发件箱，引用本身不增加接收方权限。 */
@Composable
fun TaskRefPickerDialog(
    chatId: String,
    myUid: String,
    actions: MessageActionsFeature,
    onSend: (Message) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember(chatId, actions) { mutableIntStateOf(TaskPolicy.VIEW_ASSIGNED) }
    var requestCursor by remember(view, chatId, actions) { mutableStateOf<String?>(null) }
    var nextCursor by remember(view, chatId, actions) { mutableStateOf<String?>(null) }
    var tasks by remember(view, chatId, actions) { mutableStateOf(emptyList<WorkTask>()) }
    var refresh by remember(view, chatId, actions) { mutableIntStateOf(0) }
    var loading by remember(view, chatId, actions) { mutableStateOf(true) }
    var error by remember(view, chatId, actions) { mutableStateOf<String?>(null) }
    LaunchedEffect(view, chatId, actions, requestCursor, refresh) {
        loading = true
        error = null
        try {
            val page = actions.loadTaskReferences(view, requestCursor)
            tasks = ((if (requestCursor == null) emptyList() else tasks) + page.items)
                .distinctBy(WorkTask::taskId).takeLast(200)
            nextCursor = page.nextCursor
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "任务列表暂不可用，请重试" }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("task.reference.picker"),
        title = { Text("引用任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
                    listOf(TaskPolicy.VIEW_ASSIGNED to "分配给我", TaskPolicy.VIEW_CREATED to "我创建的").forEach { (value, label) ->
                        FilterChip(selected = view == value, onClick = { view = value }, label = { Text(label) },
                            modifier = Modifier.testTag("task.reference.view.$value"))
                    }
                }
                Text("分享不会授予任务访问权限", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("task.reference.error"))
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("重试") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = Tk.dimens.listItemHeight * 6)) {
                    if (!loading && error == null && tasks.isEmpty()) item { Text("暂无可引用的任务") }
                    items(tasks, key = WorkTask::taskId) { task ->
                        Column(Modifier.fillMaxWidth().testTag("chat.taskref.pick.${task.taskId.take(12)}")
                            .clickable {
                                onSend(Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(), senderUid = myUid,
                                    messageType = MessageType.TASK_REF.code, timestamp = System.currentTimeMillis(),
                                    body = TaskRefBody(task.taskId, task.title, taskStatusLabel(task.status))))
                                onDismiss()
                            }.padding(vertical = Tk.spacing.sm)) {
                            Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(taskStatusLabel(task.status), style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
                        }
                    }
                    if (nextCursor != null) item {
                        TextButton(onClick = { requestCursor = nextCursor }, enabled = !loading,
                            modifier = Modifier.testTag("task.reference.more")) { Text("加载更多") }
                    }
                    if (tasks.size >= 200) item { Text("保留最近加载的 200 项，切换列表可返回第一页", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
