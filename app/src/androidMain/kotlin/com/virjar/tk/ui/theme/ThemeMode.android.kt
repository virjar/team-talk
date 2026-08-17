package com.virjar.tk.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Android 主题持久化（SharedPreferences）。
 * 需在 Activity setContent 前调 [initThemeStore]（MainActivity.onCreate）。
 */
private var prefs: SharedPreferences? = null

fun initThemeStore(context: Context) {
    prefs = context.getSharedPreferences("tk_theme", Context.MODE_PRIVATE)
}

@SuppressLint("ApplySharedPref")
internal actual fun loadThemeMode(): ThemeMode {
    val name = prefs?.getString(PREF_KEY, null) ?: return ThemeMode.SYSTEM
    return runCatching { ThemeMode.valueOf(name) }.getOrNull() ?: ThemeMode.SYSTEM
}

internal actual fun persistThemeMode(mode: ThemeMode) {
    prefs?.edit()?.putString(PREF_KEY, mode.name)?.apply()
}

private const val PREF_KEY = "mode"
