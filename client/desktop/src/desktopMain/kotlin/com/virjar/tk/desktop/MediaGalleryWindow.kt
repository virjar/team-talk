package com.virjar.tk.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.ui.component.GalleryItem
import com.virjar.tk.app.ui.component.MediaGallery

/**
 * Desktop 全屏媒体画廊窗口。
 *
 * 包装 commonMain 的 [MediaGallery] 组件，注入 Desktop 平台渲染器：
 * - 图片：统一会话缓存下载 + Skia 解码 + Compose Image 显示
 * - 视频：认证媒体缓存完整下载、校验并原子发布后，仅把本地文件交给内嵌播放器
 */
@Composable
internal fun MediaGalleryWindow(
    visible: Boolean,
    items: List<GalleryItem>,
    initialIndex: Int,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    telemetry: ClientUiTelemetrySink,
    onDismiss: () -> Unit,
) {
    if (!presentationGate.isOpen || !visible || items.isEmpty()) return

    val windowState = rememberWindowState(
        position = WindowPosition.PlatformDefault,
        placement = WindowPlacement.Maximized,
    )
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val placementOwnerHolder = remember { arrayOfNulls<DesktopWindowPlacementOwner>(1) }
    val dismissGallery = {
        placementOwnerHolder[0]?.close()
        presentationGate.runIfOpen { latestOnDismiss() }
        Unit
    }

    Window(
        onCloseRequest = dismissGallery,
        title = "媒体预览",
        state = windowState,
        undecorated = true,  // 无边框，沉浸式
    ) {
        if (!presentationGate.isOpen) return@Window
        var logicalPlacement by remember(window) { mutableStateOf(windowState.placement) }
        val placementOwner = remember(window) {
            DesktopWindowPlacementOwner(
                window = window,
                state = windowState,
                fullscreenBackend = desktopFullscreenBackend(System.getProperty("os.name")),
                onPlacementChanged = { logicalPlacement = it },
            )
        }
        placementOwnerHolder[0] = placementOwner
        val isFullscreen = logicalPlacement == WindowPlacement.Fullscreen
        val toggleFullscreen = {
            presentationGate.runIfOpen {
                if (placementOwner.placement == WindowPlacement.Fullscreen) {
                    placementOwner.restoreMaximized()
                } else {
                    placementOwner.enterFullscreen()
                }
            }
            Unit
        }
        DisposableEffect(window, placementOwner) {
            placementOwner.install()
            TestServiceBridge.registerWindowWithId(MEDIA_GALLERY_WINDOW_ID, window)
            val fullScreenContentSync = installMacFullScreenContentSync(window) {
                placementOwner.effectivePlacement
            }
            val unregisterEscape = registerEscapeInterceptor(window) {
                presentationGate.runIfOpen {
                    if (placementOwner.placement == WindowPlacement.Fullscreen) {
                        placementOwner.restoreMaximized()
                    } else {
                        dismissGallery()
                    }
                }
            }
            onDispose {
                unregisterEscape()
                TestServiceBridge.unregisterWindow(MEDIA_GALLERY_WINDOW_ID)
                placementOwner.close()
                if (placementOwnerHolder[0] === placementOwner) placementOwnerHolder[0] = null
                fullScreenContentSync.close()
            }
        }
        DesktopWindowTelemetry(
            window = window,
            page = ClientUiPage.MEDIA_GALLERY,
            telemetry = telemetry,
            disposalExitReason = {
                desktopWindowDisposalExitReason(presentationGate.isOpen)
            },
        )
        MediaGallery(
            visible = true,
            items = items,
            initialIndex = initialIndex,
            onDismiss = dismissGallery,
            imageRenderer = { attachment, modifier ->
                // 原图按需：缓存命中直接渲染；未命中画廊内进度覆盖层，下载完成才展示
                com.virjar.tk.desktop.media.CachedImageContent(
                    attachment = attachment,
                    resources = resources,
                    actionAdmission = presentationGate,
                    modifier = modifier,
                    progressOverlay = true,
                    contentScale = ContentScale.Fit,
                )
            },
            videoRenderer = { attachment, isCurrentPage, modifier ->
                DesktopVideoPage(
                    attachment = attachment,
                    isCurrentPage = isCurrentPage,
                    presentationGate = presentationGate,
                    resources = resources,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = toggleFullscreen,
                    modifier = modifier,
                )
            },
            isFullscreen = isFullscreen,
            onToggleFullscreen = toggleFullscreen,
            showPageNavigationControls = true,
        )
    }
}
private const val MEDIA_GALLERY_WINDOW_ID = "media-gallery"
