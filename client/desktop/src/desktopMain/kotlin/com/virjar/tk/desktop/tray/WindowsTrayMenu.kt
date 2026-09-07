package com.virjar.tk.desktop.tray

import com.virjar.tk.app.identity.ClientIdentity
import com.virjar.tk.shared.log.AppLog
import java.awt.EventQueue
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Windows AWT 原生菜单在部分中文环境不能显示 CJK；只替换菜单绘制，不替换系统托盘。
 * 菜单及承载它的无任务栏窗口由本对象共同持有，所有调用都在 EDT 上执行。
 */
internal class WindowsTrayMenu(
    private val trayIcon: TrayIcon,
    onShow: () -> Unit,
    onQuit: () -> Unit,
) : AutoCloseable {
    private var anchor: JFrame? = null
    private var closed = false
    private var pendingMenuRequest: Any? = null
    private val popup = JPopupMenu().apply {
        // 托盘不属于应用窗口，独立 popup 才能可靠接收键盘和菜单选择。
        isLightWeightPopupEnabled = false
        add(JMenuItem("打开 ${ClientIdentity.DISPLAY_NAME}").apply {
            addActionListener { dismiss(); onShow() }
        })
        addSeparator()
        add(JMenuItem("在线").apply { isEnabled = false })
        addSeparator()
        add(JMenuItem("退出").apply { addActionListener { dismiss(); onQuit() } })
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = hideAnchor()
            override fun popupMenuCanceled(event: PopupMenuEvent) = hideAnchor()
        })
    }
    private val mouseListener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) = maybeShow(event)
        override fun mouseReleased(event: MouseEvent) = maybeShow(event)

        private fun maybeShow(event: MouseEvent) {
            if (!event.isPopupTrigger || closed) return
            try {
                showAt(event.locationOnScreen)
            } catch (failure: Exception) {
                AppLog.fault("WindowsTrayMenu", "Cannot prepare the tray menu", failure)
                dismiss()
            }
        }
    }

    init {
        trayIcon.addMouseListener(mouseListener)
    }

    private fun showAt(point: Point) {
        if (popup.isVisible || pendingMenuRequest != null) {
            dismiss()
            return
        }
        // AWT 鼠标与 GraphicsConfiguration 使用同一坐标空间，不再手动乘 DPI 比例。
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val configuration = environment.screenDevices.map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(point) }
            ?: environment.defaultScreenDevice.defaultConfiguration
        val host = anchor?.takeIf { it.isDisplayable && it.graphicsConfiguration == configuration }
            ?: createAnchor(configuration).also {
                anchor?.dispose()
                anchor = it
            }
        val bounds = usableBounds(configuration)
        val size = popup.preferredSize
        val x = (point.x - size.width).coerceIn(bounds.x, maxOf(bounds.x, bounds.x + bounds.width - size.width))
        val y = (point.y - size.height).coerceIn(bounds.y, maxOf(bounds.y, bounds.y + bounds.height - size.height))
        host.setLocation(x, y)
        host.isVisible = true
        host.toFront()
        host.requestFocus()
        // 主窗口隐藏后，在 TrayIcon 鼠标回调里同步 show 可静默失败。
        // 先让位于工作区内的锚点显示，下一拍再打开菜单；登出、收起或新请求会使该回调失效。
        val request = Any()
        pendingMenuRequest = request
        EventQueue.invokeLater {
            if (closed || pendingMenuRequest !== request || anchor !== host) return@invokeLater
            pendingMenuRequest = null
            if (!host.isDisplayable || !host.isShowing) {
                hideAnchor()
                AppLog.fault("WindowsTrayMenu", "Cannot show the tray menu: anchor is no longer showing")
                return@invokeLater
            }
            try {
                popup.show(host.contentPane, 0, 0)
                AppLog.trace(
                    "WindowsTrayMenu",
                    "Tray menu requested: anchorShowing=${host.isShowing}, menuVisible=${popup.isVisible}",
                )
            } catch (failure: Exception) {
                AppLog.fault("WindowsTrayMenu", "Cannot show the tray menu", failure)
                dismiss()
            }
        }
    }

    private fun createAnchor(configuration: GraphicsConfiguration) = JFrame(configuration).apply {
        type = Window.Type.UTILITY
        isUndecorated = true
        isAlwaysOnTop = true
        setSize(1, 1)
        // 不依赖透明窗口能力；这一像素位于菜单下面，菜单收起时一起隐藏。
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) {
                EventQueue.invokeLater {
                    if (event.window !== anchor) return@invokeLater
                    val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                    val menuOwnsFocus = generateSequence(focused) { it.owner }.any { it === anchor }
                    if (!closed && popup.isVisible && !menuOwnsFocus) dismiss()
                }
            }
        })
    }

    private fun usableBounds(configuration: GraphicsConfiguration): Rectangle {
        val bounds = Rectangle(configuration.bounds)
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
        return bounds
    }

    private fun dismiss() {
        popup.isVisible = false
        hideAnchor()
    }

    private fun hideAnchor() {
        pendingMenuRequest = null
        anchor?.isVisible = false
    }

    override fun close() {
        closed = true
        trayIcon.removeMouseListener(mouseListener)
        dismiss()
        popup.invoker = null
        anchor?.dispose()
        anchor = null
    }
}
