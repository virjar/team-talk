package com.virjar.tk.desktop.tray

import com.virjar.tk.app.identity.ClientIdentity
import com.virjar.tk.desktop.DesktopIconResources
import com.virjar.tk.shared.log.AppLog

import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.concurrent.FutureTask

/**
 * 系统托盘图标。
 *
 * 用 JDK 内置 [java.awt.SystemTray] + [java.awt.TrayIcon]，零外部依赖。
 * 托盘图标从 classpath:/icon/ 加载（与 [com.virjar.tk.desktop.setTeamTalkIcon] 同一资源）。
 */
object AppTray {

    @Volatile
    private var trayIcon: TrayIcon? = null
    private var windowsMenu: WindowsTrayMenu? = null
    private var onShow: (() -> Unit)? = null

    /** 创建并显示托盘图标。返回 false 时调用方不能再把唯一主窗口隐藏。 */
    fun create(onShow: () -> Unit, onQuit: () -> Unit): Boolean = onEventThread {
        if (trayIcon != null) {
            this.onShow = onShow
            return@onEventThread true
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            AppLog.trace("AppTray", "System tray is unavailable in this desktop environment")
            return@onEventThread false
        }

        try {
            val image = DesktopIconResources.load(16)
            if (image == null) {
                AppLog.fault("AppTray", "Cannot load tray icon resource /icon/icon-16.png")
                return@onEventThread false
            }
            this.onShow = onShow
            val newTrayIcon = TrayIcon(image, ClientIdentity.DISPLAY_NAME).apply {
                isImageAutoSize = true
            }
            // 平台确认的托盘动作才算真实用户动作；通知展示本身不会走这里。
            newTrayIcon.addActionListener { this.onShow?.invoke() }
            if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
                windowsMenu = WindowsTrayMenu(newTrayIcon, { this.onShow?.invoke() }, onQuit)
            } else {
                newTrayIcon.popupMenu = nativeMenu(onQuit)
            }
            SystemTray.getSystemTray().add(newTrayIcon)
            trayIcon = newTrayIcon
            AppLog.trace("AppTray", "System tray created")
            true
        } catch (failure: Exception) {
            runCatching { windowsMenu?.close() }.onFailure {
                AppLog.fault("AppTray", "Cannot close a failed tray menu", it)
            }
            windowsMenu = null
            this.onShow = null
            AppLog.fault("AppTray", "Cannot create system tray", failure)
            false
        }
    }

    /** 销毁托盘图标。登出时调用。 */
    fun remove() = onEventThread {
        windowsMenu?.close()
        windowsMenu = null
        trayIcon?.let {
            runCatching { SystemTray.getSystemTray().remove(it) }
            trayIcon = null
        }
        onShow = null
    }

    /** 更新 tooltip，通常显示连接状态 + 未读消息数。 */
    fun setTooltip(text: String) {
        withActiveTray { it.toolTip = text }
    }

    /** 显示系统通知。 */
    fun showNotification(title: String, message: String) {
        if (System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true)) {
            postMacOsNotification(title, message)
        } else {
            withActiveTray { it.displayMessage(title, message, TrayIcon.MessageType.INFO) }
        }
    }

    /**
     * macOS 26 起 AWT [TrayIcon.displayMessage] 依赖的旧 NSUserNotification 已被系统移除，
     * 调用无错误但通知静默消失。优先走原生壳绑定的 UNUserNotificationCenter（身份与
     * 图标跟随应用 bundle）；无壳的开发运行回退 osascript 通道。
     */
    private fun postMacOsNotification(title: String, message: String) {
        // 无壳运行时 DesktopNativeBridge 类本身可能缺失（compileOnly），一并捕获。
        val posted = runCatching {
            if (!com.virjar.tk.desktop.shell.DesktopNativeBridge.available()) return@runCatching false
            com.virjar.tk.desktop.shell.DesktopNativeBridge.postMacNotification(title, message)
            true
        }.getOrDefault(false)
        if (posted) return
        spawnOsascriptNotification(title, message)
    }

    private fun spawnOsascriptNotification(title: String, message: String) {
        Thread({
            try {
                val script = "display notification \"${appleScriptText(message)}\" " +
                    "with title \"${appleScriptText(title)}\" sound name \"default\""
                val process = ProcessBuilder("/usr/bin/osascript", "-e", script).start()
                process.waitFor()
                if (process.exitValue() != 0) {
                    AppLog.fault("AppTray", "osascript notification exited with ${process.exitValue()}")
                }
            } catch (failure: Throwable) {
                AppLog.fault("AppTray", "Cannot post osascript notification", failure)
            }
        }, "mac-notification").apply { isDaemon = true }.start()
    }

    private fun appleScriptText(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 托盘图标是否已创建并可见。 */
    val isActive: Boolean get() = trayIcon != null

    /** macOS/Linux 保留原生菜单；Windows 的中文菜单由 Java2D 绘制。 */
    private fun nativeMenu(onQuit: () -> Unit) = PopupMenu().apply {
        add(MenuItem("打开 ${ClientIdentity.DISPLAY_NAME}").apply {
            addActionListener { onShow?.invoke() }
        })
        addSeparator()
        add(MenuItem("在线").apply { isEnabled = false })
        addSeparator()
        // Quit 是应用级动作，保留事件捕获的稳定闭包，不因会话移除托盘而丢失退出请求。
        add(MenuItem("退出").apply { addActionListener { onQuit() } })
    }

    private fun withActiveTray(action: (TrayIcon) -> Unit) {
        val expected = trayIcon ?: return
        EventQueue.invokeLater { if (trayIcon === expected) action(expected) }
    }

    // 创建结果影响主窗口能否隐藏，必须同步返回；不能在持有对象锁时等待 EDT。
    private fun <T> onEventThread(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val task = FutureTask(action)
        EventQueue.invokeAndWait(task)
        return task.get()
    }
}
