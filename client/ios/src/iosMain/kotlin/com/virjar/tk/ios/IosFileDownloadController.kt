@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class IosFileDownloadController(private val resources: IosMediaResources, private val native: IosNativeMedia) : FileDownloadController {
    override val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()
    override val automaticDownloadLedger = AutomaticFileDownloadLedger()
    private val scope = resources.childScope("file-download")
    override fun ensure(attachment: Attachment) {
        if (states.containsKey(attachment.path) || !resources.canDeliverUiResult()) return
        states[attachment.path] = FileDownloadState.Checking
        scope.launch {
            val cached = resources.isCached(attachment)
            if (resources.canDeliverUiResult()) states[attachment.path] = if (cached) FileDownloadState.Done else FileDownloadState.Idle
        }
    }
    override fun download(attachment: Attachment) { obtain(attachment, false) }
    override fun openOrDownload(attachment: Attachment) { obtain(attachment, true) }
    override fun exportToUserLocation(attachment: Attachment): Boolean {
        if (!resources.canDeliverUiResult()) return false
        obtain(attachment, true, export = true)
        return true
    }
    private fun obtain(attachment: Attachment, open: Boolean, export: Boolean = false) {
        if (!resources.canDeliverUiResult()) return
        scope.launch {
            try {
                states[attachment.path] = FileDownloadState.Downloading(0f)
                val lease = resources.acquire(attachment) { progress ->
                    scope.launch { if (resources.canDeliverUiResult()) states[attachment.path] = FileDownloadState.Downloading(progress) }
                }
                if (!resources.canDeliverUiResult()) { lease.close(); return@launch }
                states[attachment.path] = FileDownloadState.Done
                if (export) native.share(lease, attachment.name) else if (open) native.preview(lease, attachment.name) else lease.close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (resources.canDeliverUiResult()) states[attachment.path] = FileDownloadState.Failed("下载或打开失败，请重试") }
        }
    }
    override fun close() { scope.cancel(); states.clear() }
}
