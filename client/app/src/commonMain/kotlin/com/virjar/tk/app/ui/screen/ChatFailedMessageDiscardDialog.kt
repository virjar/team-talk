package com.virjar.tk.app.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.protocol.model.Message

/** 删除终态、仅本地的发送失败消息的确认边界。 */
@Composable
internal fun ChatFailedMessageDiscardDialog(
    candidate: Message?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Message) -> Unit,
) {
    val failed = candidate ?: return
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("丢弃失败消息？") },
        text = { Text("这条本地失败消息将被移除。该操作不会删除任何已送达的服务端消息。") },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = { if (!saving) onConfirm(failed) },
                modifier = Modifier.testTag("chat.failed.discard.confirm"),
            ) { Text(if (saving) "正在丢弃…" else "确认丢弃") }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss,
                modifier = Modifier.testTag("chat.failed.discard.cancel"),
            ) { Text("取消") }
        },
        modifier = Modifier.testTag("chat.failed.discard.dialog"),
    )
}
