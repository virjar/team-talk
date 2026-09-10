package com.virjar.tk.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.delay

/** 检查更新对话框的界面状态机。 */
internal sealed interface AndroidUpgradeUiState {
    data object Checking : AndroidUpgradeUiState
    data object UpToDate : AndroidUpgradeUiState
    data class Available(val info: AndroidUpgradeInfo) : AndroidUpgradeUiState
    data class Downloading(val downloadId: Long, val info: AndroidUpgradeInfo) : AndroidUpgradeUiState
    data object Failed : AndroidUpgradeUiState
}

/**
 * 应用内升级闭环（内测 T024）：检查 → 下载（系统 DownloadManager，带通知栏进度）
 * → 下载完成后弹出系统安装器。首次安装需在系统弹窗里授予「安装未知应用」权限。
 */
@Composable
internal fun AndroidUpgradeDialog(
    serverBaseUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<AndroidUpgradeUiState>(AndroidUpgradeUiState.Checking) }
    var currentVersion by remember { mutableStateOf("") }

    LaunchedEffect(serverBaseUrl) {
        currentVersion = BuildConfig.VERSION_NAME
        state = try {
            val info = AndroidAppUpgrade.fetchLatest(serverBaseUrl)
            when {
                info == null -> AndroidUpgradeUiState.Failed
                AndroidAppUpgrade.isNewer(info.version, BuildConfig.VERSION_NAME) ->
                    AndroidUpgradeUiState.Available(info)
                else -> AndroidUpgradeUiState.UpToDate
            }
        } catch (_: Exception) {
            AndroidUpgradeUiState.Failed
        }
    }

    // 下载阶段轮询 DownloadManager 状态；成功即自动拉起系统安装器。
    LaunchedEffect(state) {
        val downloading = state as? AndroidUpgradeUiState.Downloading ?: return@LaunchedEffect
        while (true) {
            delay(500)
            val manager = context.getSystemService(android.app.DownloadManager::class.java) ?: return@LaunchedEffect
            val cursor = manager.query(android.app.DownloadManager.Query().setFilterById(downloading.downloadId))
            cursor.use { cursor ->
                if (!cursor.moveToFirst()) return@LaunchedEffect
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                when (status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                        AndroidAppUpgrade.installDownload(context, downloading.downloadId)
                        onDismiss()
                        return@LaunchedEffect
                    }
                    android.app.DownloadManager.STATUS_FAILED -> {
                        state = AndroidUpgradeUiState.Failed
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("检查更新") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (val current = state) {
                    AndroidUpgradeUiState.Checking -> {
                        Text("正在检查新版本…")
                    }
                    AndroidUpgradeUiState.UpToDate -> {
                        Text("当前已是最新版本（$currentVersion）")
                    }
                    is AndroidUpgradeUiState.Available -> {
                        val channelMark = current.info.channelLabel?.let { "（$it）" } ?: ""
                        Text("发现新版本 ${current.info.version}$channelMark，当前版本 $currentVersion")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "下载完成后将弹出安装确认。",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AndroidUpgradeUiState.Downloading -> {
                        Text("正在下载 ${current.info.version}…")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("upgrade.progress"))
                    }
                    AndroidUpgradeUiState.Failed -> {
                        Text("检查或下载失败，请稍后重试。")
                    }
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                is AndroidUpgradeUiState.Available -> TextButton(
                    onClick = {
                        try {
                            val downloadId = AndroidAppUpgrade.enqueueDownload(context, serverBaseUrl, current.info)
                            state = AndroidUpgradeUiState.Downloading(downloadId, current.info)
                        } catch (_: Exception) {
                            state = AndroidUpgradeUiState.Failed
                        }
                    },
                    modifier = Modifier.testTag("upgrade.download"),
                ) { Text("下载并安装") }
                AndroidUpgradeUiState.Checking,
                is AndroidUpgradeUiState.Downloading,
                -> TextButton(onClick = onDismiss) { Text("后台运行") }
                else -> TextButton(onClick = {
                    state = AndroidUpgradeUiState.Checking
                }) { Text("重试") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
