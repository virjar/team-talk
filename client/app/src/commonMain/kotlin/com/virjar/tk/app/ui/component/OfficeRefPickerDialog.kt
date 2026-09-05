package com.virjar.tk.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.navigation.feature.MessageActionsFeature
import com.virjar.tk.app.navigation.feature.OfficeReferenceKind
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.Message
import java.util.UUID

/**
 * 类型化办公对象引用选择器（CONTENT-08）：列出当前用户可引用的对象，点击即发送引用消息。
 * 双端共用候选加载与消息构造；[onSend] 接入聊天页的普通持久发送路径，不单独等待 ACK。
 */
@Composable
fun OfficeRefPickerDialog(
    kind: OfficeReferenceKind,
    chatId: String,
    myUid: String,
    actions: MessageActionsFeature,
    onSend: (Message) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var candidates by remember(kind, chatId, actions) { mutableStateOf<List<OfficeRefBody>>(emptyList()) }
    LaunchedEffect(kind, chatId, actions) {
        candidates = actions.loadReferenceCandidates(kind, chatId)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(when (kind) {
                OfficeReferenceKind.DOCUMENT -> "引用文档"
                OfficeReferenceKind.GROUP_FILE -> "引用群文件"
            })
        },
        text = {
            if (candidates.isEmpty()) {
                Text("暂无可引用的内容", style = MaterialTheme.typography.bodyMedium, color = Tk.colors.metaText)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(candidates, key = { it.targetId }) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat.officeref.pick.${candidate.targetId.take(12)}")
                                .clickable {
                                    onDismiss()
                                    onSend(
                                        Message(
                                            chatId = chatId,
                                            clientMsgId = UUID.randomUUID().toString(),
                                            senderUid = myUid,
                                            messageType = MessageType.OFFICE_REF.code,
                                            timestamp = System.currentTimeMillis(),
                                            body = candidate,
                                        ),
                                    )
                                }
                                .padding(vertical = Tk.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                candidate.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(Tk.spacing.sm))
                            Text(
                                candidate.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Tk.colors.metaText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
