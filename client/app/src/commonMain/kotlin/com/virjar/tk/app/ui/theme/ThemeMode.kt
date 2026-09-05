package com.virjar.tk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主题模式（应用内可切，跨窗口/跨 Activity 单一事实源）。
 * 持久化 expect/actual：Desktop 用 java.util.prefs，Android 用 SharedPreferences。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

object TkTheme {
    var mode by mutableStateOf(loadThemeMode())
        private set

    fun set(newMode: ThemeMode) {
        mode = newMode
        persistThemeMode(newMode)
    }

    /** 当前是否暗色（SYSTEM 跟随系统设置）。AppTheme 的 darkTheme 事实源。 */
    @Composable
    fun isDark(): Boolean = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

internal expect fun loadThemeMode(): ThemeMode

internal expect fun persistThemeMode(mode: ThemeMode)
