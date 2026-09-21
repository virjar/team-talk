package com.virjar.tk.app.ui.theme

import platform.Foundation.NSUserDefaults

private const val THEME_KEY = "teamtalk.theme.mode"
internal actual fun loadThemeMode(): ThemeMode =
    NSUserDefaults.standardUserDefaults.stringForKey(THEME_KEY)?.let { stored ->
        ThemeMode.entries.firstOrNull { it.name == stored }
    } ?: ThemeMode.SYSTEM

internal actual fun persistThemeMode(mode: ThemeMode) {
    NSUserDefaults.standardUserDefaults.setObject(mode.name, forKey = THEME_KEY)
}
