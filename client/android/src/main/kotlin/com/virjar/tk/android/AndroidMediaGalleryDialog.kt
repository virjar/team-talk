package com.virjar.tk.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virjar.tk.app.ui.component.GalleryItem
import com.virjar.tk.app.ui.component.MediaGallery
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink

/**
 * Android 画廊必须脱离聊天页的 IME padding 和 NavHost 转场单独占用一个窗口；
 * 不响应点击外部关闭，退出动画由聊天页转场统一承担。
 */
/**
 * 先完成输入法收起动作，再发布画廊可见状态。这样系统返回键不会先被残留 IME 消费。
 */
internal fun openAndroidMediaGallery(
    items: List<GalleryItem>,
    requestedIndex: Int,
    hideIme: () -> Unit,
    present: (items: List<GalleryItem>, index: Int) -> Unit,
) {
    if (items.isEmpty()) return
    hideIme()
    present(items, requestedIndex.coerceIn(items.indices))
}

@Composable
internal fun AndroidMediaGalleryDialog(
    visible: Boolean,
    items: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    mediaSession: AndroidMediaSession,
    telemetry: ClientUiTelemetrySink,
    onSaveCurrent: ((com.virjar.tk.protocol.model.Attachment) -> Unit)? = null,
) {
    if (!visible || items.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Dialog 是独立 Compose 窗口，必须在这里重新开启 testTag → resourceId 映射。
        TestTagEnabler {
            // 明确由最上层画廊消费 Back；聊天路由不会收到同一次返回事件。
            BackHandler(enabled = true, onBack = onDismiss)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                MediaGallery(
                    visible = true,
                    items = items,
                    initialIndex = initialIndex,
                    onDismiss = onDismiss,
                    imageRenderer = { attachment, mod ->
                        rememberAsyncThumb(
                            attachment = attachment,
                            mediaSession = mediaSession,
                            modifier = mod,
                            placeholderColor = android.graphics.Color.BLACK,
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER,
                        )
                    },
                    videoRenderer = { attachment, isCurrentPage, mod ->
                        rememberVideoPlayer(
                            attachment = attachment,
                            mediaSession = mediaSession,
                            isCurrentPage = isCurrentPage,
                            telemetry = telemetry,
                            modifier = mod,
                        )
                    },
                    animateEnterExit = false,
                    onSaveCurrent = onSaveCurrent,
                )
            }
        }
    }
}
