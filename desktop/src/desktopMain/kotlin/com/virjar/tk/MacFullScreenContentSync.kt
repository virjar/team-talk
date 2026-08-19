package com.virjar.tk

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
 * Keeps Compose's heavyweight Skia canvas aligned with a native macOS full-screen NSWindow.
 *
 * Some JBR/Skiko combinations report the native full-screen flag but retain the floating JFrame
 * bounds. Swing then keeps the root pane and Skia canvas at the old size, exposing the native
 * window's black background on the right and bottom. We intentionally resize only the content
 * hierarchy: the floating JFrame bounds remain the source of truth and macOS can restore them.
 */
internal fun installMacFullScreenContentSync(window: ComposeWindow): AutoCloseable {
    if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        return AutoCloseable { }
    }
    return MacFullScreenContentSync(window).also { it.install() }
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
) : AutoCloseable {
    private var contentWasForced = false
    private val placementTimer = Timer(200) { syncCurrentPlacement() }.apply {
        isRepeats = true
        initialDelay = 200
    }

    private val componentListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent?) {
            // Native full screen does not consistently update JFrame bounds on macOS. Skiko uses
            // this same event to refresh its native full-screen flag, so run after its listener.
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
        val placement = window.placement
        val screen = window.graphicsConfiguration.bounds
        val needsSync = needsMacFullScreenContentSync(
            placement = placement,
            contentWidth = window.rootPane.width,
            contentHeight = window.rootPane.height,
            screenWidth = screen.width,
            screenHeight = screen.height,
        )
        if (needsSync) {
            forceFullScreenContent()
        } else if (contentWasForced && placement != WindowPlacement.Fullscreen) {
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
