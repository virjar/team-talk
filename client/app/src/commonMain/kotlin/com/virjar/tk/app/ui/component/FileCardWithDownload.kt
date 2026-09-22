package com.virjar.tk.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.ui.platform.formatUiFileSize

/**
 * 文件卡的下载状态包装：小文件（≤ [FileDownloadController.AUTO_DOWNLOAD_LIMIT]）
 * 收到即静默下载；大文件显示「点击下载」提示，点击后进度环动画，完成自动打开。
 */
@Composable
internal fun FileCardWithDownload(
    controller: FileDownloadController,
    attachment: Attachment,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 下载完成后的“保存到文件系统”入口（内测 T042）；null 时隐藏按钮。 */
    onSave: ((Attachment) -> Unit)? = null,
) {
    val state = controller.states[attachment.path] ?: FileDownloadState.Checking

    // ensure 只启动平台缓存探测；自动下载必须等待明确的 Idle miss。
    LaunchedEffect(controller, attachment) {
        controller.ensure(attachment)
    }
    LaunchedEffect(controller, attachment, state) {
        if (controller.automaticDownloadLedger.claim(attachment, state)) {
            controller.download(attachment)
        }
    }

    val sizeText = when (val s = state) {
        is FileDownloadState.Checking -> "${formatUiFileSize(attachment.size)} · 正在检查本地文件…"
        is FileDownloadState.Downloading ->
            if (s.progress >= 0) "下载中 ${(s.progress * 100).toInt()}%" else "下载中…"
        is FileDownloadState.Failed -> "${s.reason ?: "下载失败"} · 点击重试"
        is FileDownloadState.Idle ->
            if (attachment.size > FileDownloadController.AUTO_DOWNLOAD_LIMIT) formatUiFileSize(attachment.size) + " · 点击下载"
            else formatUiFileSize(attachment.size)
        is FileDownloadState.Done -> formatUiFileSize(attachment.size)
    }

    FileCard(
        fileName = attachment.name,
        sizeText = sizeText,
        onClick = { controller.openOrDownload(attachment) },
        onLongClick = onLongClick,
        downloadState = state,
        onSave = onSave?.let { handler -> { handler(attachment) } },
        modifier = modifier,
    )
}
