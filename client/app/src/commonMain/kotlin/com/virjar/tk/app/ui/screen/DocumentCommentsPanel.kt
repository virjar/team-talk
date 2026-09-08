package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virjar.tk.app.navigation.feature.document.DocumentCommentsFeature
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.shared.client.PendingDocumentComment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 评论覆盖层保留底下的正文编辑器；手机和桌面共用讨论语义。 */
@Composable
internal fun DocumentCommentsPanel(
    feature: DocumentCommentsFeature,
    tab: DocumentTabState,
    role: Int,
    admission: UiActionAdmission,
) {
    var expanded by remember(tab.instanceId) { mutableStateOf(false) }
    val documentId = tab.documentId
    val available = documentId != null && tab.revision != null && !tab.creating && !tab.remoteMissing && role >= DocumentSpace.ROLE_VIEWER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = admission.guard { expanded = true }, enabled = available,
            modifier = Modifier.testTag("documents.comments.open")) { Text("评论") }
    }
    if (!expanded || documentId == null) return
    DisposableEffect(tab.spaceId, documentId) {
        feature.open(tab.spaceId, documentId)
        onDispose { feature.close(tab.spaceId, documentId) }
    }
    Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 700.dp).fillMaxWidth().fillMaxHeight(0.9f).padding(12.dp),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).testTag("documents.comments.panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("文档评论", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { expanded = false }, modifier = Modifier.testTag("documents.comments.close")) { Text("关闭") }
                }
                if (feature.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                feature.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("documents.comments.error"))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("documents.comments.list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (feature.items.isEmpty() && !feature.loading) item {
                        Text(if (feature.error == null) "暂无评论，写下第一条讨论" else "暂无可离线查看的评论", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(feature.items, key = { it.commentId }) { comment ->
                        val inFlight = feature.pending.any { it.commentId == comment.commentId }
                        val reply = comment.replyToId?.let { id -> feature.items.firstOrNull { it.commentId == id } }
                        Column(Modifier.fillMaxWidth().testTag("documents.comment.${comment.commentId}")) {
                            Text(comment.authorName, style = MaterialTheme.typography.titleSmall)
                            val time = remember(comment.createdAt) {
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(comment.createdAt))
                            }
                            Text(time + if (comment.revision > 1 && !comment.deleted) " · 已编辑" else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            comment.replyToId?.let { replyId ->
                                Text("回复 ${reply?.authorName ?: "评论 ${replyId.take(8)}"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            SelectionContainer { Text(if (comment.deleted) "评论已删除" else comment.body) }
                            if (!comment.deleted && available) Row {
                                TextButton(onClick = admission.guard { feature.reply(comment) },
                                    modifier = Modifier.testTag("documents.comment.reply.${comment.commentId}")) { Text("回复") }
                                if (comment.authorUid == feature.ownerUid) {
                                    TextButton(onClick = admission.guard { feature.edit(comment) }, enabled = !inFlight,
                                        modifier = Modifier.testTag("documents.comment.edit.${comment.commentId}")) { Text("编辑") }
                                }
                                if (comment.authorUid == feature.ownerUid || role >= DocumentSpace.ROLE_ADMIN) {
                                    TextButton(onClick = admission.guard { feature.delete(comment) }, enabled = !inFlight,
                                        modifier = Modifier.testTag("documents.comment.delete.${comment.commentId}")) { Text("删除") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    items(feature.pending, key = { "pending-${it.commentId}" }) { command ->
                        Column(Modifier.fillMaxWidth().testTag("documents.comment.pending.${command.commentId}")) {
                            val action = when (command.kind) {
                                PendingDocumentComment.UPDATE -> "编辑"
                                PendingDocumentComment.DELETE -> "删除"
                                else -> "发送"
                            }
                            Text(command.failure ?: "等待${action}确认 · 已保存在本机", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                            if (command.body.isNotEmpty()) SelectionContainer { Text(command.body) }
                            if (command.failure != null) Row {
                                TextButton(onClick = admission.guard { feature.retry(command) },
                                    modifier = Modifier.testTag("documents.comment.retry.${command.commentId}")) { Text("重试") }
                                TextButton(onClick = admission.guard { feature.discard(command) },
                                    modifier = Modifier.testTag("documents.comment.discard.${command.commentId}")) { Text("丢弃操作") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = admission.guard(feature::newer), enabled = feature.hasNewer && !feature.loading,
                        modifier = Modifier.testTag("documents.comments.newer")) { Text("较新评论") }
                    TextButton(onClick = admission.guard(feature::refresh), enabled = !feature.loading,
                        modifier = Modifier.testTag("documents.comments.refresh")) { Text("刷新") }
                    TextButton(onClick = admission.guard(feature::earlier), enabled = feature.hasEarlier && !feature.loading,
                        modifier = Modifier.testTag("documents.comments.earlier")) { Text("更早评论") }
                }
                if (feature.composer.editing != null || feature.composer.replyToId != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (feature.composer.editing != null) "编辑自己的评论" else "回复评论")
                        TextButton(onClick = admission.guard(feature::clearComposer)) { Text("取消") }
                    }
                }
                OutlinedTextField(value = feature.composer.body, onValueChange = admission.guard(feature::changeBody),
                    enabled = available, minLines = 2, maxLines = 5, label = { Text("写评论") },
                    supportingText = { Text("${feature.composer.body.length} / ${DocumentComment.MAX_BODY_LENGTH}") },
                    modifier = Modifier.fillMaxWidth().testTag("documents.comments.input"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = admission.guard(feature::clearComposer), enabled = feature.composer.body.isNotEmpty()) { Text("清空") }
                    Button(onClick = admission.guard(feature::submit),
                        enabled = available && !feature.submitting && feature.composer.body.isNotBlank(),
                        modifier = Modifier.testTag("documents.comments.submit")) {
                        Text(if (feature.composer.editing != null) "保存修改" else "发送评论")
                    }
                }
            }
        }
    }
}
