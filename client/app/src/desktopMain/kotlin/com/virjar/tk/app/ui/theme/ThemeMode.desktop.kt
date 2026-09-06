package com.virjar.tk.app.ui.theme

import com.virjar.tk.app.identity.ClientIdentity
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
        themePreferences().get(PREF_KEY, null)
    }.getOrNull()?.let { name ->
        runCatching { ThemeMode.valueOf(name) }.getOrNull()
    } ?: ThemeMode.SYSTEM
}

internal actual fun persistThemeMode(mode: ThemeMode) {
    runCatching { themePreferences().put(PREF_KEY, mode.name) }
}

/** 公版保留原偏好节点；私版按稳定应用 ID 隔离，不因展示名称变化而丢失选择。 */
private fun themePreferences(): Preferences = if (ClientIdentity.APPLICATION_ID == "com.virjar.tk") {
    Preferences.userNodeForPackage(TkTheme::class.java)
} else {
    Preferences.userRoot().node("/${ClientIdentity.APPLICATION_ID.replace('.', '/')}/teamtalk/theme")
}

private const val PREF_KEY = "theme.mode"
