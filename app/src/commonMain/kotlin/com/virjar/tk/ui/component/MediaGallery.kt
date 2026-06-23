package com.virjar.tk.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 全屏沉浸式媒体画廊（对标 Telegram/Signal）。
 *
 * 作为 Composable overlay 嵌入任意页面，不需要单独 Activity。
 * 使用 AnimatedVisibility 实现淡入/缩放进入动画。
 *
 * - HorizontalPager 左右滑动切换图片/视频
 * - 单指未缩放时：左右滑动切页，单击关闭
 * - 缩放态（scale > 1）：双指缩放 + 拖拽平移
 * - 双击切换 1x ↔ 2.5x
 * - 顶部页码指示器 + 关闭按钮
 *
 * @param visible 是否显示画廊
 * @param items 媒体列表
 * @param initialIndex 初始页面索引
 * @param onDismiss 关闭回调
 * @param imageRenderer 平台注入的图片渲染
 * @param videoRenderer 平台注入的视频渲染
 */
@Composable
fun MediaGallery(
    visible: Boolean,
    items: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    imageRenderer: @Composable (url: String, modifier: Modifier) -> Unit,
    videoRenderer: @Composable (url: String, modifier: Modifier) -> Unit,
) {
    AnimatedVisibility(
        visible = visible && items.isNotEmpty(),
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.92f),
    ) {
        MediaGalleryContent(
            items = items,
            initialIndex = initialIndex,
            onDismiss = onDismiss,
            imageRenderer = imageRenderer,
            videoRenderer = videoRenderer,
        )
    }
}

@Composable
private fun MediaGalleryContent(
    items: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    imageRenderer: @Composable (url: String, modifier: Modifier) -> Unit,
    videoRenderer: @Composable (url: String, modifier: Modifier) -> Unit,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.size - 1),
    ) { items.size }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
    ) {
        // ── 主体：水平滑动页 ──
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val pageModifier = Modifier.fillMaxSize()

            when (item.type) {
                "video" -> videoRenderer(item.url, pageModifier)
                else -> ZoomableImagePage(
                    url = item.url,
                    imageRenderer = imageRenderer,
                    onSingleTap = onDismiss,
                    modifier = pageModifier,
                )
            }
        }

        // ── 顶部覆盖层：页码 + 关闭 ──
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${items.size}",
                color = Color.White.copy(alpha = 0.8f),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = onDismiss) {
                Text("✕", color = Color.White)
            }
        }
    }
}

/**
 * 可缩放图片页。
 *
 * 关键设计：缩放手势只在 scale > 1 时激活。
 * scale == 1 时，单指滑动穿透到 HorizontalPager 做切页，
 * 单击穿透到 onSingleTap 关闭画廊。
 * 只有双指 pinch 或已放大后的拖拽才被此处消费。
 */
@Composable
private fun ZoomableImagePage(
    url: String,
    imageRenderer: @Composable (String, Modifier) -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 仅在已缩放时启用拖拽/缩放手势；未缩放时手势穿透到 Pager / 单击关闭
    val zoomModifier = if (scale > 1.01f) {
        Modifier.pointerInput(scale) {
            detectTransformGestures { _, pan, zoom, _ ->
                val newScale = (scale * zoom).coerceIn(1f, 5f)
                if (newScale <= 1.01f) {
                    scale = 1f
                    offset = Offset.Zero
                } else {
                    scale = newScale
                    offset = Offset(offset.x + pan.x, offset.y + pan.y)
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(zoomModifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        imageRenderer(url, Modifier.fillMaxSize())
    }
}
