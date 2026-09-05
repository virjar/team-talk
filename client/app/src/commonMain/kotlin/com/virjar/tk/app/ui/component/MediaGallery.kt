package com.virjar.tk.app.ui.component

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable

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
 * @param videoRenderer 平台注入的视频渲染；第二个参数只在该页是 currentPage 时为 true，
 * 平台播放器必须据此收敛播放所有权，预加载页不得发声。
 * @param onToggleFullscreen 平台窗口提供的全屏切换；null 时不显示全屏入口。
 * @param showPageNavigationControls Desktop 等非触屏平台可显示上一页/下一页按钮。
 * @param animateEnterExit 是否执行缩放进入/退出动画。Android 独立画廊窗口会关闭该动画，
 * 避免部分设备的视频纹理层在窗口切换期间与 Compose 变换不同步。
 */
@Composable
fun MediaGallery(
    visible: Boolean,
    items: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    imageRenderer: @Composable (attachment: Attachment, modifier: Modifier) -> Unit,
    videoRenderer: @Composable (attachment: Attachment, isCurrentPage: Boolean, modifier: Modifier) -> Unit,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    showPageNavigationControls: Boolean = false,
    animateEnterExit: Boolean = true,
) {
    if (!animateEnterExit) {
        if (visible && items.isNotEmpty()) {
            MediaGalleryContent(
                items = items,
                initialIndex = initialIndex,
                onDismiss = onDismiss,
                imageRenderer = imageRenderer,
                videoRenderer = videoRenderer,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                showPageNavigationControls = showPageNavigationControls,
            )
        }
        return
    }

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
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            showPageNavigationControls = showPageNavigationControls,
        )
    }
}

@Composable
private fun MediaGalleryContent(
    items: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    imageRenderer: @Composable (attachment: Attachment, modifier: Modifier) -> Unit,
    videoRenderer: @Composable (attachment: Attachment, isCurrentPage: Boolean, modifier: Modifier) -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    showPageNavigationControls: Boolean,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.size - 1),
    ) { items.size }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .testTag("media.gallery.root"),
    ) {
        // ── 主体：水平滑动页 ──
        HorizontalPager(
            state = pagerState,
            // 保留一个相邻页，使视频在常规的单页滑动时不会丢失播放意图/进度。
            // 下方每页都有硬裁剪边界，防止被保留的图片或原生视频图层绘制到已就位的当前页之上。
            beyondViewportPageCount = 1,
            key = { page -> "${items[page].stableId}:$page" },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val isCurrentPage = pagerState.currentPage == page
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .testTag("media.gallery.page.$page"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isCurrentPage) Modifier.testTag("media.gallery.page.current")
                            else Modifier,
                        ),
                ) {
                    when (item.type) {
                        GalleryMediaType.VIDEO -> videoRenderer(
                            item.attachment,
                            isCurrentPage,
                            Modifier.fillMaxSize(),
                        )
                        else -> ZoomableImagePage(
                            attachment = item.attachment,
                            imageRenderer = imageRenderer,
                            onSingleTap = onDismiss,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isCurrentPage) Modifier.testTag("media.gallery.image")
                                    else Modifier,
                                ),
                        )
                    }
                }
            }
        }

        if (showPageNavigationControls && pagerState.currentPage > 0) {
            GalleryPageButton(
                previous = true,
                onClick = {
                    scope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) }
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        if (showPageNavigationControls && pagerState.currentPage < items.lastIndex) {
            GalleryPageButton(
                previous = false,
                onClick = {
                    scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // ── 顶部覆盖层：页码 + 关闭 ──
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .zIndex(2f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${items.size}",
                color = Color.White.copy(alpha = 0.8f),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("media.gallery.pageCounter"),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onToggleFullscreen != null) {
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.testTag("media.gallery.fullscreen"),
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) {
                                Icons.Filled.FullscreenExit
                            } else {
                                Icons.Filled.Fullscreen
                            },
                            contentDescription = if (isFullscreen) "退出全屏" else "全屏显示",
                            tint = Color.White,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("media.gallery.close"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭媒体画廊",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryPageButton(
    previous: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.48f))
            .testTag(if (previous) "media.gallery.previous" else "media.gallery.next")
            .zIndex(2f),
    ) {
        Icon(
            imageVector = if (previous) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
            contentDescription = if (previous) "上一项媒体" else "下一项媒体",
            tint = Color.White,
        )
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
    attachment: Attachment,
    imageRenderer: @Composable (Attachment, Modifier) -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(attachment.path) { mutableFloatStateOf(1f) }
    var offset by remember(attachment.path) { mutableStateOf(Offset.Zero) }

    // 捏合缩放（多指）+ 双击缩放；单指拖拽仅在放大后生效，否则穿透到 Pager 翻页。
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset = if (newScale <= 1.01f) Offset.Zero else offset + panChange
    }

    // 双击：1x ↔ 2.5x
    // 拖拽平移：仅放大后生效；未放大时手势穿透到 Pager 翻页
    // 硬裁剪：放大后的位图绝不绘制到相邻 pager 页上
    Box(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(attachment.path) {
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
                .transformable(transformState)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            contentAlignment = Alignment.Center,
        ) {
            imageRenderer(attachment, Modifier.fillMaxSize())
        }
    }
}
