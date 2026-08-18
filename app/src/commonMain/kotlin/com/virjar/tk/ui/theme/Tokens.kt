package com.virjar.tk.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UI 设计令牌。规范：doc/05-clients/design-system.md（实现与规范不一致时必须收敛）。
 *
 * 用法：`Tk.colors.hover` / `Tk.spacing.md` / `Tk.dimens.listAvatar` / `Tk.avatarShape(size)`。
 * 组件内禁止裸 dp / 裸 Color(0xFF...)，一律走令牌。
 */

// ── 间距（4px 栅格）──
object TkSpacing {
    val xs = 4.dp    // 图标与文字间隙
    val sm = 8.dp    // 头像与文本间隙
    val md = 12.dp   // 气平内边距、列表项上下
    val lg = 16.dp   // 面板左右
    val xl = 20.dp   // 空态留白
    val xxl = 24.dp  // 页面级留白
}

// ── 尺寸（桌面紧凑 / 触控两套，经 CompositionLocal 注入）──
@Immutable
data class TkDimens(
    val railWidth: Dp,
    val listPaneWidth: Dp,
    val listItemHeight: Dp,
    val listAvatar: Dp,
    val chatAvatar: Dp,
    val headerHeight: Dp,
    val inputMinHeight: Dp,
    val bubbleMaxWidth: Dp,
    val iconSize: Dp,
    val appBarHeight: Dp,
    val globalSearchHeight: Dp,
)

val DesktopDimens = TkDimens(
    railWidth = 56.dp,
    listPaneWidth = 300.dp,
    listItemHeight = 64.dp,
    listAvatar = 40.dp,
    chatAvatar = 36.dp,
    headerHeight = 48.dp,
    inputMinHeight = 36.dp,
    bubbleMaxWidth = 420.dp,
    iconSize = 20.dp,
    appBarHeight = 46.dp,
    globalSearchHeight = 32.dp,
)

val TouchDimens = TkDimens(
    railWidth = 56.dp,
    listPaneWidth = 300.dp,
    listItemHeight = 72.dp,
    listAvatar = 48.dp,
    chatAvatar = 36.dp,
    headerHeight = 56.dp,
    inputMinHeight = 44.dp,
    bubbleMaxWidth = 300.dp,
    iconSize = 22.dp,
    appBarHeight = 56.dp,
    globalSearchHeight = 44.dp,
)

val LocalTkDimens = staticCompositionLocalOf { DesktopDimens }

// ── 扩展色板（M3 角色之外的语义色，明暗两套）──
@Immutable
data class TkColors(
    val hover: Color,
    val selected: Color,
    val divider: Color,
    val bubbleIncoming: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingContent: Color,
    val metaText: Color,
    val secondaryText: Color,
    val online: Color,
    val unreadBadge: Color,
    val pinIcon: Color,
    val pinnedBg: Color,
)

val LightTkColors = TkColors(
    hover = Color(0xFFEDEEF1),
    selected = Color(0xFFE1EAFF),
    divider = Color(0xFFE5E6EB),
    bubbleIncoming = Color(0xFFF2F3F5),
    bubbleOutgoing = Color(0xFFE8F0FF),
    bubbleOutgoingContent = Color(0xFF1D2129),
    metaText = Color(0xFF8F959E),
    secondaryText = Color(0xFF646A73),
    online = Color(0xFF34C724),
    unreadBadge = Color(0xFFF54A45),
    pinIcon = Color(0xFF8F959E),
    pinnedBg = Color(0xFFF7F8FA),
)

val DarkTkColors = TkColors(
    hover = Color(0xFF2A2D34),
    selected = Color(0xFF1E2A47),
    divider = Color(0xFF2E3036),
    bubbleIncoming = Color(0xFF272A31),
    bubbleOutgoing = Color(0xFF203A63),
    bubbleOutgoingContent = Color(0xFFEDF3FF),
    metaText = Color(0xFF6B6E75),
    secondaryText = Color(0xFFA8ABB3),
    online = Color(0xFF34C724),
    unreadBadge = Color(0xFFE54548),
    pinIcon = Color(0xFF6B6E75),
    pinnedBg = Color(0xFF202329),
)

val LocalTkColors = staticCompositionLocalOf { LightTkColors }

/** 统一访问入口。 */
object Tk {
    val colors: TkColors
        @Composable get() = LocalTkColors.current

    val dimens: TkDimens
        @Composable get() = LocalTkDimens.current

    val spacing = TkSpacing

    /** 圆角方形头像（飞书特征）：圆角 = 尺寸 × 0.22。 */
    fun avatarShape(size: Dp) = RoundedCornerShape(size * 0.22f)
}
