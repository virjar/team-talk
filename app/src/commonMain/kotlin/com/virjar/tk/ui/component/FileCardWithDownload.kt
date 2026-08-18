package com.virjar.tk.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.virjar.tk.util.formatFileSize
import com.virjar.tk.model.Attachment

/**
 * 文件卡的下载状态包装：小文件（≤ [FileDownloadController.AUTO_DOWNLOAD_LIMIT]）
 * 收到即静默下载；大文件显示「点击下载」提示，点击后进度环动画，完成自动打开。
 */
@Composable
internal fun FileCardWithDownload(
    controller: FileDownloadController,
    attachment: Attachment,
) {
    // 首次组合：初始化缓存状态；小文件立即静默下载。单 effect 避免进度更新时反复重启。
    LaunchedEffect(controller, attachment) {
        controller.ensure(attachment)
        if (attachment.size <= FileDownloadController.AUTO_DOWNLOAD_LIMIT &&
            controller.states[attachment.path] is FileDownloadState.Idle
        ) {
            controller.download(attachment)
        }
    }

    val state = controller.states[attachment.path] ?: FileDownloadState.Idle

    val sizeText = when (val s = state) {
        is FileDownloadState.Downloading ->
            if (s.progress >= 0) "下载中 ${(s.progress * 100).toInt()}%" else "下载中…"
        is FileDownloadState.Failed -> "下载失败 · 点击重试"
        is FileDownloadState.Idle ->
            if (attachment.size > FileDownloadController.AUTO_DOWNLOAD_LIMIT) formatFileSize(attachment.size) + " · 点击下载"
            else formatFileSize(attachment.size)
        is FileDownloadState.Done -> formatFileSize(attachment.size)
    }

    FileCard(
        fileName = attachment.name,
        sizeText = sizeText,
        onClick = { controller.openOrDownload(attachment) },
        downloadState = state,
    )
}
