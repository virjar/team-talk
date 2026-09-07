package com.virjar.tk.desktop.tray

import com.virjar.tk.app.identity.ClientIdentity

import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
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
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return@onEventThread false

        val image = loadTrayImage() ?: return@onEventThread false
        this.onShow = onShow

        val newTrayIcon = TrayIcon(image, ClientIdentity.DISPLAY_NAME).apply {
            isImageAutoSize = true
        }
        // 平台确认的托盘动作才算真实用户动作；通知展示本身不会走这里。
        newTrayIcon.addActionListener { this.onShow?.invoke() }

        try {
            if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
                windowsMenu = WindowsTrayMenu(newTrayIcon, { this.onShow?.invoke() }, onQuit)
            } else {
                newTrayIcon.popupMenu = nativeMenu(onQuit)
            }
            SystemTray.getSystemTray().add(newTrayIcon)
        } catch (_: Exception) {
            windowsMenu?.close()
            windowsMenu = null
            this.onShow = null
            return@onEventThread false
        }
        trayIcon = newTrayIcon
        true
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

    /** 显示系统通知（通过托盘气泡）。 */
    fun showNotification(title: String, message: String) {
        withActiveTray { it.displayMessage(title, message, TrayIcon.MessageType.INFO) }
    }

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

    /** 从 classpath 加载 16x16 PNG 作为托盘图标。 */
    private fun loadTrayImage(): Image? {
        val url = Thread.currentThread().contextClassLoader?.getResource("icon/icon-16.png")
        return url?.let { Toolkit.getDefaultToolkit().getImage(it) }
    }
}
