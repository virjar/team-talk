package com.virjar.tk.ui.theme

import java.util.prefs.Preferences

/**
 * Desktop 主题持久化（java.util.prefs）。
 *
 * dev 覆盖参数 `-Dteamtalk.theme=dark|light`：不落盘、优先于持久值，
 * 供暗色走查截图（:desktop:run 无法直接切系统外观时强制主题）。
 */
internal actual fun loadThemeMode(): ThemeMode {
    System.getProperty("teamtalk.theme")?.lowercase()?.let {
        return when (it) {
            "dark" -> ThemeMode.DARK
            "light" -> ThemeMode.LIGHT
            else -> ThemeMode.SYSTEM
        }
    }
    return runCatching {
        Preferences.userNodeForPackage(TkTheme::class.java).get(PREF_KEY, null)
    }.getOrNull()?.let { name ->
        runCatching { ThemeMode.valueOf(name) }.getOrNull()
    } ?: ThemeMode.SYSTEM
}

internal actual fun persistThemeMode(mode: ThemeMode) {
    runCatching { Preferences.userNodeForPackage(TkTheme::class.java).put(PREF_KEY, mode.name) }
}

private const val PREF_KEY = "theme.mode"
