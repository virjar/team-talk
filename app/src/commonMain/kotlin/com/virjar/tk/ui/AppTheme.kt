package com.virjar.tk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.ui.theme.DarkTkColors
import com.virjar.tk.ui.theme.LightTkColors
import com.virjar.tk.ui.theme.LocalTkColors
import com.virjar.tk.ui.theme.LocalTkDimens

// ── 飞书/钉钉风格配色 ──
// 主色：靛蓝（Lark Blue #3370FF）
// 辅色：青绿（Teal #00B89A）
// 完整令牌规范：doc/05-clients/design-system.md

private val LarkBlue = Color(0xFF3370FF)
private val LarkBlueDark = Color(0xFF245BDB)
private val LarkBlueLight = Color(0xFFE1EAFF)
private val Teal = Color(0xFF00B89A)
private val WarmOrange = Color(0xFFFF7D00)

private val LightColorScheme = lightColorScheme(
    primary = LarkBlue,
    onPrimary = Color.White,
    primaryContainer = LarkBlueLight,
    onPrimaryContainer = LarkBlueDark,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4F5EE),
    onSecondaryContainer = Color(0xFF006B5B),
    tertiary = WarmOrange,
    onTertiary = Color.White,
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF1D2129),
    surface = Color.White,
    onSurface = Color(0xFF1D2129),
    surfaceVariant = Color(0xFFF0F1F5),
    onSurfaceVariant = Color(0xFF4E5969),
    surfaceContainerHighest = Color(0xFFE8E9ED),
    outline = Color(0xFFC9CDD4),
    outlineVariant = Color(0xFFE5E6EB),
    error = Color(0xFFF54A45),
    onError = Color.White,
    errorContainer = Color(0xFFFFECE8),
    onErrorContainer = Color(0xFFCB2626),
    scrim = Color(0x99000000),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5B8DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3F8F),
    onPrimaryContainer = Color(0xFFC9D7FF),
    secondary = Color(0xFF33D4B0),
    onSecondary = Color(0xFF003830),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB0F0DE),
    tertiary = Color(0xFFFF9A4D),
    background = Color(0xFF16181D),
    onBackground = Color(0xFFE5E6EB),
    surface = Color(0xFF1D2026),
    onSurface = Color(0xFFE5E6EB),
    surfaceVariant = Color(0xFF272A31),
    onSurfaceVariant = Color(0xFFA8ABB3),
    surfaceContainerHighest = Color(0xFF2A2D34),
    outline = Color(0xFF3D4046),
    outlineVariant = Color(0xFF2E3036),
    error = Color(0xFFF76965),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFD0CC),
    scrim = Color(0xCC000000),
)

// ── 自定义排版（飞书桌面：14px 正文基线）──

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    // bodySmall/labelSmall 是对 M3 默认（12/10sp）的飞书校正：13sp 预览行、11sp 时间戳
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 14.sp),
)

// ── 自定义圆角（飞书风格：中等圆角，不过分圆润）──

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun AppTheme(
    /** 暗色判定事实源：TkTheme（应用内可切，默认跟随系统）。 */
    darkTheme: Boolean = com.virjar.tk.ui.theme.TkTheme.isDark(),
    /** 触控密度（Android：72dp 列表项/48dp 头像）；默认桌面紧凑档 */
    touchDensity: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTkColors provides if (darkTheme) DarkTkColors else LightTkColors,
        LocalTkDimens provides if (touchDensity) com.virjar.tk.ui.theme.TouchDimens
        else com.virjar.tk.ui.theme.DesktopDimens,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
