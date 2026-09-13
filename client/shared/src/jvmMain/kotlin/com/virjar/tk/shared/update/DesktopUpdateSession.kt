package com.virjar.tk.shared.update

import com.virjar.tk.protocol.http.ClientReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 桌面“检查更新”会话状态机（对齐 Android 的 AndroidUpgradeDialog 语义，
 * 供桌面 Compose 对话框直接订阅）。
 */
sealed class DesktopUpdateUiState {
    data object Idle : DesktopUpdateUiState()
    data object Checking : DesktopUpdateUiState()
    data object UpToDate : DesktopUpdateUiState()
    data object NotManaged : DesktopUpdateUiState()
    data class Available(
        val release: ClientReleaseInfo,
        val shellUpdateRequired: Boolean,
    ) : DesktopUpdateUiState()

    data class Downloading(val completedBytes: Long, val totalBytes: Long) : DesktopUpdateUiState()
    data class ReadyToRestart(val version: String, val build: Long) : DesktopUpdateUiState()
    data class Failed(val message: String) : DesktopUpdateUiState()
}

class DesktopUpdateSession(
    private val serverBaseUrl: String,
    channel: String? = null,
    private val onUpdateApplied: () -> Unit = {},
    private val onExitForRestart: (() -> Unit)? = null,
    httpClient: UpdateHttpClient = JdkUpdateHttpClient,
    context: DesktopUpdateContext? = DesktopUpdateContext.fromSystem(),
    private val scope: CoroutineScope,
) {
    private val updater = context?.let { DesktopUpdater(it, httpClient) }
    private val updateChannel = channel ?: context?.descriptor?.channel ?: "stable"
    private val _state = MutableStateFlow<DesktopUpdateUiState>(DesktopUpdateUiState.Idle)
    val state: StateFlow<DesktopUpdateUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun startCheck() {
        if (updater == null) {
            _state.value = DesktopUpdateUiState.NotManaged
            return
        }
        job?.cancel()
        _state.value = DesktopUpdateUiState.Checking
        job = scope.launch {
            try {
                when (val outcome = updater.check(serverBaseUrl, updateChannel)) {
                    DesktopUpdater.CheckOutcome.UpToDate -> _state.value = DesktopUpdateUiState.UpToDate
                    DesktopUpdater.CheckOutcome.ChannelDisabled -> _state.value = DesktopUpdateUiState.UpToDate
                    is DesktopUpdater.CheckOutcome.ShellUpdateRequired ->
                        _state.value = DesktopUpdateUiState.Available(outcome.release, true)
                    is DesktopUpdater.CheckOutcome.UpdateAvailable ->
                        _state.value = DesktopUpdateUiState.Available(outcome.release, false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = DesktopUpdateUiState.Failed(failure.message ?: "检查更新失败")
            }
        }
    }

    fun startDownload() {
        val availableState = _state.value as? DesktopUpdateUiState.Available ?: return
        if (updater == null || availableState.shellUpdateRequired) return
        job?.cancel()
        // 增量大小由清单与本地文件 diff 得出，发布的 totalBytes 还包含全量安装器。
        _state.value = DesktopUpdateUiState.Downloading(0, 0)
        job = scope.launch {
            try {
                // 下载前重查通道停用/切换，但只能安装用户刚确认的不可变发布。
                val outcome = updater.check(serverBaseUrl, updateChannel)
                val currentRelease = (outcome as? DesktopUpdater.CheckOutcome.UpdateAvailable)?.release
                val release = availableState.release
                if (currentRelease == null || currentRelease.id != release.id ||
                    currentRelease.buildIdentity != release.buildIdentity) {
                    throw DesktopUpdateException("发布已变化，请重新检查更新")
                }
                val result = updater.downloadAndApply(serverBaseUrl, release) { progress ->
                    _state.value = DesktopUpdateUiState.Downloading(progress.completedBytes, progress.totalBytes)
                }
                _state.value = DesktopUpdateUiState.ReadyToRestart(result.version, result.build)
                onUpdateApplied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = DesktopUpdateUiState.Failed(failure.message ?: "下载更新失败")
            }
        }
    }

    fun restartNow() {
        val ready = _state.value as? DesktopUpdateUiState.ReadyToRestart ?: return
        if (onExitForRestart != null && updater?.relaunch() == true) {
            onExitForRestart.invoke()
        } else {
            _state.value = DesktopUpdateUiState.Failed("无法重启应用，请手动重启 ${ready.version}")
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = DesktopUpdateUiState.Idle
    }
}

/** 供 shell/bootstrap 侧诊断：当前指针与目录是否一致。 */
fun diagnosePayloadRoot(versionsRoot: File): String {
    val pointer = PayloadLayout.readCurrentPointer(versionsRoot)
        ?: return "no current pointer at ${versionsRoot.absolutePath}"
    val descriptor = PayloadLayout.readDescriptor(File(versionsRoot, pointer.directory))
        ?: return "pointer says build ${pointer.build} but descriptor is missing"
    return "build=${descriptor.build} version=${descriptor.version} files=${descriptor.files.size}"
}
