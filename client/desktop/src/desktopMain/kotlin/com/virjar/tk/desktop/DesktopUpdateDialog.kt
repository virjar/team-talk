package com.virjar.tk.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.update.DesktopUpdateSession
import com.virjar.tk.shared.update.DesktopUpdateUiState
import java.net.URI

/**
 * 桌面“检查更新”对话框：状态机由共享 DesktopUpdateSession 驱动
 * （check → 文件级增量下载 → 原子切换 → 重启生效）。
 *
 * dev/裸 JVM 运行时没有壳上下文，会话返回 NotManaged；入口在设置菜单中据此隐藏。
 */
@Composable
internal fun DesktopUpdateDialog(
    serverBaseUrl: String,
    onDismiss: () -> Unit,
    onExitForRestart: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(serverBaseUrl) {
        DesktopUpdateSession(serverBaseUrl = serverBaseUrl, scope = scope, onExitForRestart = onExitForRestart)
    }
    val state by session.state.collectAsState()

    LaunchedEffect(session) { session.startCheck() }
    DisposableEffect(session) { onDispose { session.dismiss() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("软件更新") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val s = state) {
                    is DesktopUpdateUiState.Checking, DesktopUpdateUiState.Idle -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在检查更新……")
                    }

                    is DesktopUpdateUiState.UpToDate -> Text("已是最新版本。")
                    is DesktopUpdateUiState.NotManaged -> Text(
                        "当前以开发模式运行，不支持应用内更新；请使用发布包运行。",
                    )

                    is DesktopUpdateUiState.Available -> {
                        Text(
                            "发现新版本 v${s.version}（build ${s.build}）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val updateNotes = s.notes
                        if (!updateNotes.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                updateNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (s.shellUpdateRequired) {
                                "本次更新包含底层组件调整，需要下载新的安装包完成升级。"
                            } else {
                                "实际更新仅下载变化的文件，下载时显示进度。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    is DesktopUpdateUiState.Downloading -> {
                        Text("正在下载更新……")
                        Spacer(Modifier.height(8.dp))
                        val fraction = if (s.totalBytes > 0) {
                            s.completedBytes.toFloat() / s.totalBytes.toFloat()
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().testTag("update.download.progress"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatBytes(s.completedBytes)} / ${formatBytes(s.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    is DesktopUpdateUiState.ReadyToRestart -> Text("v${s.version} 已就绪，重启应用后生效。")

                    is DesktopUpdateUiState.Failed -> Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is DesktopUpdateUiState.Available -> {
                    if (s.shellUpdateRequired) {
                        TextButton(
                            onClick = { openDownloadPage(serverBaseUrl) },
                            modifier = Modifier.testTag("update.open.downloads"),
                        ) { Text("打开下载页") }
                    } else {
                        TextButton(
                            onClick = session::startDownload,
                            modifier = Modifier.testTag("update.download.confirm"),
                        ) { Text("立即更新") }
                    }
                }

                is DesktopUpdateUiState.ReadyToRestart -> TextButton(
                    onClick = session::restartNow,
                    modifier = Modifier.testTag("update.restart"),
                ) { Text("立即重启") }

                is DesktopUpdateUiState.Failed -> TextButton(onClick = session::startCheck) { Text("重试") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            // 下载中不允许关闭：半成品永远只在暂存目录，关闭不会破坏状态，但进度会被取消。
            if (state !is DesktopUpdateUiState.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text(
                        when (state) {
                            is DesktopUpdateUiState.Available -> "稍后再说"
                            is DesktopUpdateUiState.ReadyToRestart -> "稍后重启"
                            else -> "关闭"
                        },
                    )
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    else -> "${(bytes.coerceAtLeast(0) / 1024.0).toInt()} KB"
}

private fun openDownloadPage(serverBaseUrl: String) {
    runCatching {
        java.awt.Desktop.getDesktop().browse(URI(serverBaseUrl.trimEnd('/') + "/downloads"))
    }
}
