package com.virjar.tk.desktop.test

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.sun.net.httpserver.HttpExchange
import java.awt.Component
import java.awt.Container
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.jetbrains.skiko.SkiaLayer

/** 持有内嵌自动化服务使用的窗口与原生输入资源。 */
internal class TestHttpAutomation {
    internal val windows = ConcurrentHashMap<String, ComposeWindow>()
    internal val preFullscreenPlacements = ConcurrentHashMap<String, WindowPlacement>()
    internal val robot: Robot by lazy { Robot() }
    internal val robotLock = Any()

    internal val semantics = TestSemanticsDriver(this)
    internal val routes = TestHttpRoutes(this, semantics)

    internal fun registerWindow(id: String, window: ComposeWindow) {
        windows[id] = window
    }

    internal fun unregisterWindow(id: String) {
        windows.remove(id)
        preFullscreenPlacements.remove(id)
    }

    internal fun clear() {
        windows.clear()
        preFullscreenPlacements.clear()
    }

    /** 获取指定窗口（默认 main）。 */
    internal fun window(exchange: HttpExchange): ComposeWindow? {
        val id = exchange.queryParams()["window"] ?: "main"
        return windows[id] ?: windows["main"]
    }

    /**
     * 为测试窗口请求真实操作系统级的前台持有权。
     *
     * Compose semantics 动作可以在不激活原生应用的情况下操作控件，这无法真实地检验
     * 由 [java.awt.Window.isActive] 守护的产品行为，例如回执与正在输入提示。
     * 在 macOS 上，仅靠 `toFront()` 也无法把 Gradle 启动的 JVM 提升到前台。
     */
    internal fun activateWindow(windowId: String = "main"): ComposeWindow? {
        val window = windows[windowId] ?: windows["main"] ?: return null
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                desktop.requestForeground(true)
            }
        }
        EventQueue.invokeAndWait {
            window.toFront()
            window.requestFocus()
        }
        return window
    }

    /** 点击屏幕坐标（窗口内坐标 → 屏幕坐标转换）。先置前窗口再点击。 */
    internal fun clickScreen(
        x: Float,
        y: Float,
        windowId: String = "main",
        clickCount: Int = 1,
    ) {
        synchronized(robotLock) {
            val window = windows[windowId] ?: windows["main"] ?: return
            // 确保窗口在前台（Robot 点击需要窗口聚焦）
            if (!window.isActive) {
                window.toFront()
                window.requestFocus()
                Thread.sleep(100)
            }
            val (sx, sy) = composePointToScreen(window, x, y)
            // 移动到目标位置并短暂停留（某些系统需要 mouseMove 触发 hover）
            robot.mouseMove(sx, sy)
            Thread.sleep(50)
            repeat(clickCount.coerceAtLeast(1)) { index ->
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(20)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                if (index < clickCount - 1) Thread.sleep(75)
            }
            robot.waitForIdle()
        }
    }

    /** Compose 窗口内设备像素 → AWT 屏幕逻辑点。 */
    internal fun composePointToScreen(window: ComposeWindow, x: Float, y: Float): Pair<Int, Int> {
        val fullScreenUsesStaleFrameBounds = window.placement == WindowPlacement.Fullscreen &&
            (window.width != window.rootPane.width || window.height != window.rootPane.height)
        val screen = window.graphicsConfiguration.bounds
        val originX = if (fullScreenUsesStaleFrameBounds) screen.x else window.locationOnScreen.x
        val originY = if (fullScreenUsesStaleFrameBounds) screen.y else window.locationOnScreen.y
        val transform = window.graphicsConfiguration.defaultTransform
        val scaleX = transform.scaleX.takeIf { it > 0.0 } ?: 1.0
        val scaleY = transform.scaleY.takeIf { it > 0.0 } ?: 1.0
        return (originX + (x / scaleX).roundToInt()) to
            (originY + (y / scaleY).roundToInt())
    }

    /** 通过剪贴板粘贴文本（支持中文，绕过 IME 和逐字符限制）。 */
    internal fun pasteText(text: String) {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val clipboard = toolkit.systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        Thread.sleep(50)
        // Cmd+V 粘贴
        robot.keyPress(KeyEvent.VK_META)
        robot.keyPress(KeyEvent.VK_V)
        robot.keyRelease(KeyEvent.VK_V)
        robot.keyRelease(KeyEvent.VK_META)
        robot.waitForIdle()
        Thread.sleep(50)
    }

    /** 系统全屏视觉验收使用物理屏幕截图，才能看见 NSWindow 周围是否仍暴露黑色底层。 */
    internal fun captureScreenPng(window: ComposeWindow): ByteArray {
        var screenBounds = Rectangle()
        EventQueue.invokeAndWait { screenBounds = window.graphicsConfiguration.bounds }
        val multiResolution = synchronized(robotLock) {
            robot.createMultiResolutionScreenCapture(screenBounds)
        }
        val image = multiResolution.resolutionVariants.maxByOrNull { candidate ->
            candidate.getWidth(null).toLong() * candidate.getHeight(null).toLong()
        } ?: error("screen capture failed")
        val buffered = if (image is BufferedImage) {
            image
        } else {
            BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also { target ->
                val graphics = target.createGraphics()
                try {
                    graphics.drawImage(image, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(buffered, "png", output)) { "screen PNG encoding failed" }
            output.toByteArray()
        }
    }

    /** ComposeWindow 把 SkiaLayer 藏在内部容器后面；无需反射即可找到它。 */
    internal fun findSkiaLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component !is Container) return null
        component.components.forEach { child ->
            findSkiaLayer(child)?.let { return it }
        }
        return null
    }
}
