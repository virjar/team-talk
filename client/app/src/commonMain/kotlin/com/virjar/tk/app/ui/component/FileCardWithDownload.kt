package com.virjar.tk.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.virjar.tk.protocol.model.Attachment

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
        is FileDownloadState.Checking -> "${formatFileSize(attachment.size)} · 正在检查本地文件…"
        is FileDownloadState.Downloading ->
            if (s.progress >= 0) "下载中 ${(s.progress * 100).toInt()}%" else "下载中…"
        is FileDownloadState.Failed -> "${s.reason ?: "下载失败"} · 点击重试"
        is FileDownloadState.Idle ->
            if (attachment.size > FileDownloadController.AUTO_DOWNLOAD_LIMIT) formatFileSize(attachment.size) + " · 点击下载"
            else formatFileSize(attachment.size)
        is FileDownloadState.Done -> formatFileSize(attachment.size)
    }

    FileCard(
        fileName = attachment.name,
        sizeText = sizeText,
        onClick = { controller.openOrDownload(attachment) },
        onLongClick = onLongClick,
        downloadState = state,
        modifier = modifier,
    )
}

/** 格式化文件卡中显示的大小，字节取整，其余最多显示一位小数。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        val rounded = ((size * 10).toLong().toDouble()) / 10.0
        val s = if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else rounded.toString()
        "$s ${units[unitIndex]}"
    }
}
