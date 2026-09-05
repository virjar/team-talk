package com.virjar.tk.desktop.tray

import java.awt.*

/**
 * 系统托盘图标。
 *
 * 用 JDK 内置 [java.awt.SystemTray] + [java.awt.TrayIcon]，零外部依赖。
 * 托盘图标从 classpath:/icon/ 加载（与 [com.virjar.tk.desktop.setTeamTalkIcon] 同一资源）。
 */
object AppTray {

    @Volatile
    private var trayIcon: TrayIcon? = null
    private var menuShow: MenuItem? = null
    private var menuStatus: MenuItem? = null
    @Volatile
    private var onShow: (() -> Unit)? = null

    /** 创建并显示托盘图标。返回 false 时调用方不能再把唯一主窗口隐藏。 */
    @Synchronized
    fun create(onShow: () -> Unit, onQuit: () -> Unit): Boolean {
        if (trayIcon != null) {
            this.onShow = onShow
            return true
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return false

        val image = loadTrayImage() ?: return false
        this.onShow = onShow

        val popup = PopupMenu()

        menuShow = MenuItem("打开 TeamTalk").also { popup.add(it) }
        popup.addSeparator()
        menuStatus = MenuItem("在线").also { popup.add(it); it.isEnabled = false }
        popup.addSeparator()
        val menuQuit = MenuItem("退出").also { popup.add(it) }

        val newTrayIcon = TrayIcon(image, "TeamTalk", popup).apply {
            isImageAutoSize = true
        }
        // 平台确认的托盘动作才算真实用户动作；通知展示本身不会走这里。
        newTrayIcon.addActionListener { this.onShow?.invoke() }

        menuShow?.addActionListener { this.onShow?.invoke() }
        // Quit 是应用级的。在这个原生监听器中捕获稳定的闭包，
        // 这样会话级的 remove/recomposition 不会让已被保留的 AWT 事件变成 null。
        menuQuit.addActionListener { onQuit() }

        try {
            SystemTray.getSystemTray().add(newTrayIcon)
        } catch (_: Exception) {
            this.onShow = null
            menuShow = null
            menuStatus = null
            return false
        }
        trayIcon = newTrayIcon
        return true
    }

    /** 销毁托盘图标。登出时调用。 */
    @Synchronized
    fun remove() {
        trayIcon?.let {
            runCatching { SystemTray.getSystemTray().remove(it) }
            trayIcon = null
        }
        menuShow = null
        menuStatus = null
        onShow = null
    }

    /** 更新 tooltip，通常显示连接状态 + 未读消息数。 */
    fun setTooltip(text: String) {
        trayIcon?.toolTip = text
    }

    /** 显示系统通知（通过托盘气泡）。 */
    fun showNotification(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    /** 托盘图标是否已创建并可见。 */
    val isActive: Boolean get() = trayIcon != null

    /** 从 classpath 加载 16x16 PNG 作为托盘图标。 */
    private fun loadTrayImage(): Image? {
        val url = Thread.currentThread().contextClassLoader?.getResource("icon/icon-16.png")
        return url?.let { Toolkit.getDefaultToolkit().getImage(it) }
    }
}
