package com.virjar.tk.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** 检查更新对话框的界面状态机。 */
internal sealed interface AndroidUpgradeUiState {
    data object Checking : AndroidUpgradeUiState
    data object UpToDate : AndroidUpgradeUiState
    data class Available(val info: AndroidUpgradeInfo) : AndroidUpgradeUiState
    data class Downloading(val info: AndroidUpgradeInfo) : AndroidUpgradeUiState
    data class ReadyToInstall(val file: File) : AndroidUpgradeUiState
    data object Failed : AndroidUpgradeUiState
}

/**
 * 应用内升级闭环（内测 T024 重做）：检查 → 应用内下载（进度可见、可取消）
 * → 下载完成直接拉起系统安装器 → 升级安装重启后的首次启动清理暂存包。
 * 旧实现把下载交给系统 DownloadManager，完成事件回不到应用且后台拉不起安装器，
 * 用户下载后毫无感知；现改为应用内闭环。首次安装需在系统弹窗里授予
 * 「安装未知应用」权限，对话框会引导往返，授权后自动继续。
 */
@Composable
internal fun AndroidUpgradeDialog(
    serverBaseUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AndroidUpgradeUiState>(AndroidUpgradeUiState.Checking) }
    var currentVersion by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var installHint by remember { mutableStateOf<String?>(null) }
    /** 等待用户从「安装未知应用」系统开关返回后继续安装的暂存包。 */
    var awaitingPermissionFor by remember { mutableStateOf<File?>(null) }
    /** 自动拉起安装只做一次；授权往返与手动重试显式触发，避免设置页死循环。 */
    var autoInstallAttempted by remember { mutableStateOf(false) }
    /** 检查尝试序号：key 进 LaunchedEffect，「重试」按钮递增即可真正重查。 */
    var checkAttempt by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = awaitingPermissionFor
        awaitingPermissionFor = null
        if (file != null && AndroidApkInstaller.canRequestInstall(context)) {
            if (AndroidApkInstaller.install(context, file)) {
                onDismiss()
                return@rememberLauncherForActivityResult
            }
        }
        installHint = "尚未获得安装授权；开启「允许安装未知应用」后点「安装」重试。"
    }

    fun attemptInstall(file: File) {
        if (AndroidApkInstaller.canRequestInstall(context)) {
            if (AndroidApkInstaller.install(context, file)) {
                onDismiss()
            } else {
                installHint = "无法启动系统安装器，请稍后重试。"
            }
            return
        }
        val settings = AndroidApkInstaller.installPermissionSettingsIntent(context)
        if (settings == null) {
            installHint = "无法启动系统安装器，请稍后重试。"
            return
        }
        awaitingPermissionFor = file
        installHint = "安装前需允许 TeamTalk「安装未知应用」，正在打开系统设置…"
        permissionLauncher.launch(settings)
    }

    LaunchedEffect(serverBaseUrl, checkAttempt) {
        state = AndroidUpgradeUiState.Checking
        currentVersion = BuildConfig.VERSION_NAME
        state = try {
            val info = AndroidAppUpgrade.fetchLatest(serverBaseUrl)
            when {
                info == null -> AndroidUpgradeUiState.Failed
                info.version?.let { remote -> AndroidAppUpgrade.isNewer(remote, BuildConfig.VERSION_NAME) } == true ->
                    AndroidUpgradeUiState.Available(info)
                else -> AndroidUpgradeUiState.UpToDate
            }
        } catch (_: Exception) {
            AndroidUpgradeUiState.Failed
        }
    }

    // 下载完成自动拉起安装器（仅一次）；失败提示保留手动「安装」按钮兜底。
    LaunchedEffect(state) {
        val ready = state as? AndroidUpgradeUiState.ReadyToInstall ?: return@LaunchedEffect
        if (!autoInstallAttempted) {
            autoInstallAttempted = true
            attemptInstall(ready.file)
        }
    }

    fun startDownload(info: AndroidUpgradeInfo) {
        downloadProgress = null
        installHint = null
        state = AndroidUpgradeUiState.Downloading(info)
        scope.launch {
            try {
                val file = AndroidAppUpgrade.downloadUpgradePackage(
                    context = context,
                    serverBaseUrl = serverBaseUrl,
                    info = info,
                ) { progress ->
                    downloadProgress = progress
                }
                state = AndroidUpgradeUiState.ReadyToInstall(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = AndroidUpgradeUiState.Failed
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
                            "下载在应用内完成，完成后将直接弹出安装确认。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AndroidUpgradeUiState.Downloading -> {
                        val progress = downloadProgress
                        Text("正在下载 ${current.info.version}…")
                        Spacer(Modifier.height(8.dp))
                        if (progress != null && progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().testTag("upgrade.progress"),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "已下载 ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().testTag("upgrade.progress"),
                            )
                        }
                    }
                    is AndroidUpgradeUiState.ReadyToInstall -> {
                        Text("下载完成，正在打开系统安装器…")
                        installHint?.let { hint ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Start,
                            )
                        }
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
                    onClick = { startDownload(current.info) },
                    modifier = Modifier.testTag("upgrade.download"),
                ) { Text("下载并安装") }
                is AndroidUpgradeUiState.ReadyToInstall -> TextButton(
                    onClick = { attemptInstall(current.file) },
                    modifier = Modifier.testTag("upgrade.install"),
                ) { Text("安装") }
                AndroidUpgradeUiState.Checking,
                AndroidUpgradeUiState.UpToDate,
                -> {}
                AndroidUpgradeUiState.Failed -> TextButton(
                    onClick = { checkAttempt += 1 },
                ) { Text("重试") }
                is AndroidUpgradeUiState.Downloading -> {}
            }
        },
        dismissButton = {
            when (state) {
                is AndroidUpgradeUiState.Downloading -> TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("upgrade.background"),
                ) { Text("取消下载") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
