package com.virjar.tk.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

/**
 * 子窗口根页面有系统关闭按钮，不再额外显示移动端式返回箭头；但仍保留 onBack 供
 * 保存成功和 ESC 关闭窗口。窗口内进入下一层后由宿主恢复返回箭头。
 */
val LocalScreenHeaderBackButtonVisible = staticCompositionLocalOf { true }

/** Desktop 融合式原生标题栏为 macOS 红黄绿按钮预留的横向空间。 */
val LocalScreenHeaderLeadingInset = staticCompositionLocalOf { 0.dp }

/**
 * Android edge-to-edge 页面需要由共享页头消费顶部安全区。Desktop 保持默认关闭，避免原生
 * 标题栏下再次增加一层空白；Android 壳在 NavHost 外显式开启。
 */
val LocalScreenHeaderTopSafeAreaEnabled = staticCompositionLocalOf { false }

/**
 * 子页面通用头部。高度与排版由平台密度令牌决定：
 * Desktop 为 48dp 左对齐工具栏，Android 为 56dp 居中触控页头。
 *
 * 统一封装，避免每个 Screen 各自手写头部导致风格不一致。
 * onBack 非空时具备返回能力；是否显示箭头还受 [LocalScreenHeaderBackButtonVisible] 控制。
 *
 * @param title 标题文字
 * @param onBack 返回回调。null 时不渲染返回按钮
 * @param trailing 右侧操作槽（如"保存"/"确认"按钮），默认空
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val headerHeight = Tk.dimens.headerHeight
    val compactDesktop = headerHeight < 56.dp
    val showBackButton = onBack != null && LocalScreenHeaderBackButtonVisible.current
    val leadingInset: Dp = LocalScreenHeaderLeadingInset.current
    val topSafeAreaModifier = if (LocalScreenHeaderTopSafeAreaEnabled.current) {
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
    } else {
        Modifier
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        // windowInsetsPadding applies only the still-unconsumed portion and consumes it for
        // descendants, so nested Android hosts cannot accidentally double-pad this header.
        androidx.compose.foundation.layout.Column(modifier = topSafeAreaModifier) {
            if (compactDesktop) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .padding(
                            start = leadingInset + if (showBackButton) 4.dp else 16.dp,
                            end = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBackButton) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(headerHeight)) {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        content = trailing,
                    )
                }
            }
            HorizontalDivider(color = Tk.colors.divider)
        }
    }
}
