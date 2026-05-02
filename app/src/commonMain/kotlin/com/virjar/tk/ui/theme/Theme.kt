package com.virjar.tk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFF4CAF50),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D47A1),
    secondary = Color(0xFF81C784),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

@Immutable
data class ExtendedColors(
    val mutedIcon: Color,
    val inputBorder: Color,
    val onlineIndicator: Color,
    val recordingActive: Color,
    val disabledControl: Color,
    val messageLink: Color,
    val bubbleOtherText: Color,
    val messageTimestamp: Color,
)

private val LightExtendedColors = ExtendedColors(
    mutedIcon = Color(0xFF999999),
    inputBorder = Color(0xFFDDDDDD),
    onlineIndicator = Color(0xFF4CAF50),
    recordingActive = Color(0xFFE53935),
    disabledControl = Color(0xFFCCCCCC),
    messageLink = Color(0xFF4FC3F7),
    bubbleOtherText = Color(0xFF666666),
    messageTimestamp = Color(0xFF000000),
)

private val DarkExtendedColors = ExtendedColors(
    mutedIcon = Color(0xFFAAAAAA),
    inputBorder = Color(0xFF444444),
    onlineIndicator = Color(0xFF4CAF50),
    recordingActive = Color(0xFFE53935),
    disabledControl = Color(0xFF666666),
    messageLink = Color(0xFF4FC3F7),
    bubbleOtherText = Color(0xFFBBBBBB),
    messageTimestamp = Color(0xFFEEEEEE),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

@Composable
fun TeamTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            content = content,
        )
    }
}

val MaterialTheme.extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current
