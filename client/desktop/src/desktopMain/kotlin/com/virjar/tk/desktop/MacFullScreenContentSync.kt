package com.virjar.tk.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowPlacement
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer
import org.jetbrains.skiko.SkiaLayer

/**
 * 让 Compose 的重型 Skia 画布与原生 macOS 全屏 NSWindow 保持对齐。
 *
 * 某些 JBR/Skiko 组合在原生或 AWT 独占窗口达到全屏边界时，不会重新布局 root pane 与 Skia 画布。
 * 只调整内容层级的尺寸，这样恢复窗口本身的责任仍由 placement owner 承担。
 */
internal fun installMacFullScreenContentSync(
    window: ComposeWindow,
    placement: () -> WindowPlacement = { window.placement },
): AutoCloseable {
    if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        return AutoCloseable { }
    }
    return MacFullScreenContentSync(window, placement).also { it.install() }
}

internal fun needsMacFullScreenContentSync(
    placement: WindowPlacement,
    contentWidth: Int,
    contentHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): Boolean = placement == WindowPlacement.Fullscreen &&
    (contentWidth != screenWidth || contentHeight != screenHeight)

private class MacFullScreenContentSync(
    private val window: ComposeWindow,
    private val placement: () -> WindowPlacement,
) : AutoCloseable {
    private var contentWasForced = false
    private val placementTimer = Timer(200) { syncCurrentPlacement() }.apply {
        isRepeats = true
        initialDelay = 200
    }

    private val componentListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent?) {
            // 原生全屏在 macOS 上并不总是会更新 JFrame bounds。Skiko 也使用同一事件刷新其原生全屏标志，
            // 因此要在它的监听器之后运行。
            EventQueue.invokeLater(::syncCurrentPlacement)
        }
    }

    fun install() {
        check(EventQueue.isDispatchThread()) { "macOS window integration must be installed on EDT" }
        window.addComponentListener(componentListener)
        placementTimer.start()
        syncCurrentPlacement()
    }

    override fun close() {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(::close)
            return
        }
        window.removeComponentListener(componentListener)
        placementTimer.stop()
    }

    private fun syncCurrentPlacement() {
        if (!window.isDisplayable) return
        val currentPlacement = placement()
        val screen = window.graphicsConfiguration.bounds
        val needsSync = needsMacFullScreenContentSync(
            placement = currentPlacement,
            contentWidth = window.rootPane.width,
            contentHeight = window.rootPane.height,
            screenWidth = screen.width,
            screenHeight = screen.height,
        )
        if (needsSync) {
            forceFullScreenContent()
        } else if (contentWasForced && currentPlacement != WindowPlacement.Fullscreen) {
            restoreWindowContent()
        }
    }

    private fun forceFullScreenContent() {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(::forceFullScreenContent)
            return
        }
        if (!window.isDisplayable) return
        val screen = window.graphicsConfiguration.bounds
        layoutContent(Dimension(screen.width, screen.height))
        contentWasForced = true
    }

    private fun restoreWindowContent() {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(::restoreWindowContent)
            return
        }
        if (!window.isDisplayable) return
        val insets = window.insets
        layoutContent(
            Dimension(
                (window.width - insets.left - insets.right).coerceAtLeast(0),
                (window.height - insets.top - insets.bottom).coerceAtLeast(0),
            ),
        )
        contentWasForced = false
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun layoutContent(size: Dimension) {
        val rootPane = window.rootPane
        rootPane.setBounds(0, 0, size.width, size.height)
        rootPane.doLayout()
        window.contentPane.setBounds(0, 0, size.width, size.height)
        layoutRecursively(window.contentPane)
        rootPane.repaint()
        window.renderImmediately()
        findSkiaLayer(rootPane)?.let { layer ->
            layer.needRender()
        }
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach(::layoutRecursively)
    }

    private fun findSkiaLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component !is Container) return null
        component.components.forEach { child ->
            findSkiaLayer(child)?.let { return it }
        }
        return null
    }
}
