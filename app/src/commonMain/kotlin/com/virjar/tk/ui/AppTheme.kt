package com.virjar.tk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 飞书/钉钉风格配色 ──
// 主色：靛蓝（Lark Blue #3370FF）
// 辅色：青绿（Teal #00B89A）

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
    error = Color(0xFFF53F3F),
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

// ── 自定义排版（飞书风格）──

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp),
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
