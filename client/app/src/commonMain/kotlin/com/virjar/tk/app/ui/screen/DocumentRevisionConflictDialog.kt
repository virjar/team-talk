package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.navigation.feature.document.DocumentRevisionConflictState
import com.virjar.tk.app.navigation.feature.document.DocumentTabState

/** 显式的 CAS 恢复；在用户再次按下保存之前，任何选择都不会写入服务器。 */
@Composable
internal fun DocumentRevisionConflictDialog(
    state: DocumentRevisionConflictState?,
    activeTab: DocumentTabState?,
    onRetry: () -> Unit,
    onAdoptServer: () -> Unit,
    onKeepDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    val conflict = state?.takeIf { current ->
        activeTab?.let(current.request::targetsUnchanged) == true
    } ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文档版本冲突") },
        text = {
            when (conflict) {
                is DocumentRevisionConflictState.Loading -> Column {
                    Text("正在读取服务器最新版本；你的本地草稿已保留。")
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                is DocumentRevisionConflictState.LoadFailed -> Text(
                    "读取最新版本失败：${conflict.message}\n本地草稿仍然保留，可以重试。"
                )
                is DocumentRevisionConflictState.Adopting -> Column {
                    Text("正在安全放弃本机草稿并采用服务器版本…")
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                is DocumentRevisionConflictState.Ready -> Text(
                    "服务器版本 ${conflict.remote.revision} 已更新。请选择采用服务器内容，或保留我的内容并基于最新版本继续；后者仍需再次保存才会写入服务器。"
                )
            }
        },
        confirmButton = {
            when (conflict) {
                is DocumentRevisionConflictState.Loading -> Unit
                is DocumentRevisionConflictState.Adopting -> Unit
                is DocumentRevisionConflictState.LoadFailed -> TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("documents.conflict.retry"),
                ) { Text("重试读取") }
                is DocumentRevisionConflictState.Ready -> TextButton(
                    onClick = onAdoptServer,
                    modifier = Modifier.testTag("documents.conflict.use-server"),
                ) { Text("采用服务器版本") }
            }
        },
        dismissButton = {
            if (conflict is DocumentRevisionConflictState.Ready) {
                TextButton(
                    onClick = onKeepDraft,
                    modifier = Modifier.testTag("documents.conflict.keep-mine"),
                ) { Text("保留我的内容") }
            } else if (conflict !is DocumentRevisionConflictState.Adopting) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("documents.conflict.dismiss"),
                ) { Text("继续编辑") }
            }
        },
        modifier = Modifier.testTag("documents.conflict.dialog"),
    )
}
