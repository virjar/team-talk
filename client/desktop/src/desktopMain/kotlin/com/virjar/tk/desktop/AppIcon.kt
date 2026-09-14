package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.WindowScope
import com.virjar.tk.shared.log.AppLog
import java.awt.Image
import java.awt.Taskbar

/**
 * Desktop 窗口图标加载。
 *
 * 从 classpath:/icon/ 下加载多尺寸 PNG，组装成 AWT 多分辨率 Image，
 * 交给 [java.awt.Window.setIconImages]，让系统按场景（任务栏/标题栏/Alt-Tab）选最佳尺寸。
 */
private fun loadIconImages(): List<Image> {
    return listOf(16, 32, 48, 64, 128, 256, 512).mapNotNull(DesktopIconResources::load)
}

/**
 * 在 [WindowScope] 内为本窗口设置图标。
 * 在 Composable 进入组合后调用 [getWindow] 取底层 AWT 窗口，注入图标。
 */
@Composable
fun WindowScope.setTeamTalkIcon() {
    LaunchedEffect(Unit) {
        runCatching {
            val images = loadIconImages()
            if (images.isEmpty()) {
                AppLog.fault("AppIcon", "Cannot load application icon resources")
                return@runCatching
            }
            window.setIconImages(images)
            // macOS 的 Dock 图标不跟随 Window.iconImages。负载更新也要修复旧壳的 Java 图标。
            if (!Taskbar.isTaskbarSupported()) {
                AppLog.trace("AppIcon", "Taskbar unavailable; dock icon left to the launcher")
            } else {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = images.last()
                    AppLog.trace("AppIcon", "dock taskbar icon updated")
                } else {
                    AppLog.trace("AppIcon", "Taskbar ICON_IMAGE unsupported on this platform")
                }
            }
        }.onFailure { AppLog.fault("AppIcon", "Cannot set application icon", it) }
    }
}
