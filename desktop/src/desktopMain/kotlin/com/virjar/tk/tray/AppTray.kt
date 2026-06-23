package com.virjar.tk.tray

import java.awt.*
import java.awt.event.ActionListener

/**
 * 系统托盘图标。
 *
 * 用 JDK 内置 [java.awt.SystemTray] + [java.awt.TrayIcon]，零外部依赖。
 * 托盘图标从 classpath:/icon/ 加载（与 [com.virjar.tk.setTeamTalkIcon] 同一资源）。
 */
object AppTray {

    private var trayIcon: TrayIcon? = null
    private var menuShow: MenuItem? = null
    private var menuStatus: MenuItem? = null
    private var onShow: (() -> Unit)? = null
    private var onQuit: (() -> Unit)? = null

    /** 创建并显示托盘图标。登录成功后调用。 */
    fun create(onShow: () -> Unit, onQuit: () -> Unit) {
        if (!SystemTray.isSupported()) return
        this.onShow = onShow
        this.onQuit = onQuit

        val image = loadTrayImage() ?: return

        val popup = PopupMenu()

        menuShow = MenuItem("打开 TeamTalk").also { popup.add(it) }
        popup.addSeparator()
        menuStatus = MenuItem("在线").also { popup.add(it); it.isEnabled = false }
        popup.addSeparator()
        val menuQuit = MenuItem("退出").also { popup.add(it) }

        trayIcon = TrayIcon(image, "TeamTalk", popup).apply {
            isImageAutoSize = true
        }
        // 双击/单击托盘图标 → 打开主窗口
        trayIcon!!.addActionListener { onShow() }

        menuShow?.addActionListener { onShow() }
        menuQuit.addActionListener { onQuit() }

        try {
            SystemTray.getSystemTray().add(trayIcon)
        } catch (_: Exception) {
            // 托盘不可用时静默（例如 headless 环境）
        }
    }

    /** 销毁托盘图标。登出时调用。 */
    fun remove() {
        trayIcon?.let {
            SystemTray.getSystemTray().remove(it)
            trayIcon = null
        }
        onShow = null
        onQuit = null
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
