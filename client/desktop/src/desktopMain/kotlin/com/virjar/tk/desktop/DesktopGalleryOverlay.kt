package com.virjar.tk.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.virjar.tk.app.ui.component.GalleryItem
import com.virjar.tk.app.ui.component.MediaGallery
import com.virjar.tk.desktop.media.DesktopSessionResources

/**
 * 主窗口处于 macOS 原生全屏（独占 Space）时的媒体画廊宿主（内测 T020）。
 *
 * 独立画廊窗口会以 Maximized 落在原普通 Space，而原生全屏主窗口独占另一个 Space，
 * 用户点击图片后画廊永远不可见。因此全屏期间画廊改为主窗口内全幅覆盖层：
 * 与聊天同窗口、同 Space，随主窗口退出全屏一并恢复。
 *
 * 请求持有认证会话资源（presentationGate/resources/下载控制器），因此随会话内容组合的
 * 销毁一并清空：会话退役后下一个会话绝不渲染已死会话的资源。
 */
internal object DesktopGalleryOverlayHost {
    var request by mutableStateOf<DesktopGalleryOverlayRequest?>(null)
        private set

    fun show(request: DesktopGalleryOverlayRequest) {
        this.request = request
    }

    fun clear() {
        request = null
    }
}

/** 覆盖层的渲染输入；生命周期跟随主窗口，资源由认证会话所有。 */
internal class DesktopGalleryOverlayRequest(
    val items: List<GalleryItem>,
    val initialIndex: Int,
    val presentationGate: DesktopSessionPresentationGate,
    val resources: DesktopSessionResources,
    val fileDownloads: DesktopFileDownloadController?,
    val onDismiss: () -> Unit,
)

/** 主窗口全屏画廊覆盖层：铺满主窗口内容顶层，Esc/点击关闭均走 [MediaGallery] 自身交互。 */
@Composable
internal fun DesktopGalleryOverlay() {
    val request = DesktopGalleryOverlayHost.request ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MediaGallery(
            visible = true,
            items = request.items,
            initialIndex = request.initialIndex,
            onDismiss = {
                if (DesktopGalleryOverlayHost.request === request) DesktopGalleryOverlayHost.clear()
                request.onDismiss()
            },
            onSaveCurrent = request.fileDownloads?.let { controller ->
                { attachment: com.virjar.tk.protocol.model.Attachment ->
                    controller.exportToUserLocation(attachment)
                }
            },
            imageRenderer = { attachment, modifier ->
                com.virjar.tk.desktop.media.CachedImageContent(
                    attachment = attachment,
                    resources = request.resources,
                    actionAdmission = request.presentationGate,
                    modifier = modifier,
                    progressOverlay = true,
                    contentScale = ContentScale.Fit,
                )
            },
            videoRenderer = { attachment, isCurrentPage, modifier ->
                DesktopVideoPage(
                    attachment = attachment,
                    isCurrentPage = isCurrentPage,
                    presentationGate = request.presentationGate,
                    resources = request.resources,
                    isFullscreen = true,
                    onToggleFullscreen = {},
                    modifier = modifier,
                )
            },
            isFullscreen = true,
            onToggleFullscreen = {},
            showPageNavigationControls = true,
        )
    }
}
