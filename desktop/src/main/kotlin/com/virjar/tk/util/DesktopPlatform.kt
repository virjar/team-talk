package com.virjar.tk.util

enum class DesktopPlatform {
    WINDOWS, MACOS, LINUX, UNKNOWN;

    companion object {
        val current: DesktopPlatform by lazy {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("win") -> WINDOWS
                os.contains("mac") -> MACOS
                os.contains("nix") || os.contains("nux") || os.contains("aix") -> LINUX
                else -> UNKNOWN
            }
        }
    }

    val isMac: Boolean get() = this == MACOS
    val isWindows: Boolean get() = this == WINDOWS
    val isLinux: Boolean get() = this == LINUX
}
