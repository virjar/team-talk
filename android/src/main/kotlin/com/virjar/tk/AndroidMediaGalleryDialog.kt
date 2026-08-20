package com.virjar.tk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.MediaGallery

/**
 * Android 画廊必须脱离聊天页的 IME padding 和 NavHost 转场单独占用一个窗口。
 * 视频纹理层也不参与 Compose 缩放动画，避免部分设备在窗口切换时出现坐标错位或穿透。
 */
internal data class AndroidMediaGalleryPolicy(
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = false,
    val usePlatformDefaultWidth: Boolean = false,
    val decorFitsSystemWindows: Boolean = false,
    val animateEnterExit: Boolean = false,
    val videoSurfaceType: AndroidGalleryVideoSurfaceType = AndroidGalleryVideoSurfaceType.TEXTURE_VIEW,
)

internal enum class AndroidGalleryVideoSurfaceType {
    /** TextureView 与普通 View 一样参与 Compose/Dialog/Pager 的测量、裁剪和坐标变换。 */
    TEXTURE_VIEW,
}

internal val androidMediaGalleryPolicy = AndroidMediaGalleryPolicy()

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
    imageRenderer: @Composable (url: String, modifier: Modifier) -> Unit,
    videoRenderer: @Composable (url: String, isCurrentPage: Boolean, modifier: Modifier) -> Unit,
) {
    if (!visible || items.isEmpty()) return

    val policy = androidMediaGalleryPolicy
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = policy.dismissOnBackPress,
            dismissOnClickOutside = policy.dismissOnClickOutside,
            usePlatformDefaultWidth = policy.usePlatformDefaultWidth,
            decorFitsSystemWindows = policy.decorFitsSystemWindows,
        ),
    ) {
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
                imageRenderer = imageRenderer,
                videoRenderer = videoRenderer,
                animateEnterExit = policy.animateEnterExit,
            )
        }
    }
}
