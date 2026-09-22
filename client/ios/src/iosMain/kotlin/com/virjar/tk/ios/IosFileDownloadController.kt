@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.classifyMediaFailure
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadCore
import com.virjar.tk.app.ui.component.FileDownloadCoreAdapter
import com.virjar.tk.app.ui.component.FileDownloadPendingAction
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.AppError
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSLog

/**
 * iOS 文件附件下载控制器：委托三端共享的 [FileDownloadCore] 编排
 * （准入/代次、inFlight 去重、AuthExpired 终态降级、遥测与进度节流），
 * 平台侧只提供账号缓存租约与 UIKit 打开/分享动作。
 */
internal class IosFileDownloadController(
    private val resources: IosMediaResources,
    private val native: IosNativeMedia,
    telemetry: ClientUiTelemetrySink,
) : FileDownloadController {
    private val core = FileDownloadCore<IosMediaLease>(IosAdapter(), telemetry)

    override val states: SnapshotStateMap<String, FileDownloadState>
        get() = core.states
    override val automaticDownloadLedger: AutomaticFileDownloadLedger
        get() = core.automaticDownloadLedger

    override fun ensure(attachment: Attachment) = core.ensure(attachment)
    override fun download(attachment: Attachment) = core.download(attachment)
    override fun openOrDownload(attachment: Attachment) = core.act(attachment, FileDownloadPendingAction.OPEN)

    override fun exportToUserLocation(attachment: Attachment): Boolean {
        if (!resources.canDeliverUiResult()) return false
        core.act(attachment, FileDownloadPendingAction.EXPORT)
        return true
    }

    override fun close() = core.close()

    private inner class IosAdapter : FileDownloadCoreAdapter<IosMediaLease> {
        override val uiScope: CoroutineScope = resources.scope

        override fun createWorkerScope(): CoroutineScope =
            // 缓存与下载在 IosMediaResources 内部自行切换 IO；这里只需独立的工作域。
            CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("ios-file-download-worker"))

        override fun isOwnerThread(): Boolean =
            !Dispatchers.Main.immediate.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)

        override fun isOwnerCurrent(): Boolean = resources.canDeliverUiResult()

        override fun runIfOpen(action: () -> Unit): Boolean {
            if (!resources.canDeliverUiResult()) return false
            action()
            return true
        }

        override suspend fun probeCached(attachment: Attachment): Boolean = resources.isCached(attachment)

        override suspend fun cachedLease(attachment: Attachment): IosMediaLease? =
            resources.cachedLease(attachment)

        override suspend fun downloadToCache(attachment: Attachment, onProgress: (Float) -> Unit): IosMediaLease =
            resources.acquire(attachment, onProgress)

        override fun closeLease(lease: IosMediaLease) = lease.close()

        override suspend fun performPendingAction(
            action: FileDownloadPendingAction,
            lease: IosMediaLease,
            attachment: Attachment,
        ): Boolean = when (action) {
            // UIKit 呈现接管租约：回调或失败路径都会关闭，核心不再关闭。
            FileDownloadPendingAction.EXPORT -> {
                native.share(lease, attachment.name)
                true
            }
            FileDownloadPendingAction.PREVIEW, FileDownloadPendingAction.OPEN -> {
                native.preview(lease, attachment.name)
                true
            }
        }

        override fun classifyFailure(failure: Throwable): MediaFailureReason =
            classifyIosMediaFailure(failure)

        override fun warn(message: String) {
            NSLog("IosFileDownload: %s", message)
        }
    }
}

/** 仅按类型分类：不观察异常消息、URI 或本地文件名；公共分类核心在 app 层共享。 */
internal fun classifyIosMediaFailure(failure: Throwable): MediaFailureReason = classifyMediaFailure(failure) { error ->
    when (error) {
        is AppError.AuthExpired -> MediaFailureReason.SESSION
        else -> null
    }
}
