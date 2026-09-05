package com.virjar.tk.desktop.test

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.window.WindowPlacement
import com.sun.net.httpserver.HttpExchange
import com.virjar.tk.desktop.desktopWindowPlacementOwner
import com.virjar.tk.desktop.dispatchWindowEscape
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** 把稳定的测试 HTTP 契约映射到窗口与 semantics 操作上。 */
internal class TestHttpRoutes(
    private val automation: TestHttpAutomation,
    private val semantics: TestSemanticsDriver,
) {
    private val windows get() = automation.windows
    private val preFullscreenPlacements get() = automation.preFullscreenPlacements
    private val robot get() = automation.robot
    private val robotLock get() = automation.robotLock

    internal fun handlePing(exchange: HttpExchange) {
        exchange.send(200, """{"status":"ok","instanceToken":"${TestHttpServer.instanceToken}","pid":${ProcessHandle.current().pid()}}""")
    }

    /** 提升所选原生窗口，但不削弱任何产品的前台检查。 */
    internal fun handleWindowActivate(exchange: HttpExchange) {
        val windowId = exchange.queryParams()["window"] ?: "main"
        val window = automation.activateWindow(windowId)
        if (window == null) {
            exchange.send(404, """{"error":"no window"}""")
            return
        }
        var attempts = 0
        while (!window.isActive && attempts < 10) {
            Thread.sleep(25)
            attempts += 1
        }
        exchange.send(
            200,
            """{"window":"${windowId.escape()}","active":${window.isActive}}""",
        )
    }

    /** 调试端点：dump 指定节点的 config（逐个已知 key 检查，避免遍历报错）。/debug?text=注册 */
    internal fun handleDebug(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"error":"not found"}""")
            return
        }
        val cfg = node.config
        val hasOnClick = safeContains(cfg, SemanticsActions.OnClick)
        val onClickAction: AccessibilityAction<*>? =
            if (hasOnClick) safeGet(cfg, SemanticsActions.OnClick) else null
        val sb = StringBuilder("{\"id\":${node.id}")
        sb.append(",\"hasOnClick\":$hasOnClick")
        sb.append(",\"onClickLabel\":\"${onClickAction?.label ?: ""}\"")
        sb.append(",\"onClickHasAction\":${onClickAction?.action != null}")
        sb.append(",\"parentClickable\":${node.parent?.let { safeContains(it.config, SemanticsActions.OnClick) } ?: "null"}")
        sb.append("}")
        exchange.send(200, sb.toString())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    internal fun handleSemantics(exchange: HttpExchange) {
        val wid = exchange.queryParams()["window"] ?: "main"
        val roots = semantics.allRoots(wid)
        if (roots.isEmpty()) {
            exchange.send(404, """{"error":"no semantics root"}""")
            return
        }
        // 合并所有 owners（主窗口 + Popup）的子节点到一个 JSON 数组
        if (roots.size == 1) {
            exchange.send(200, semantics.nodeToJson(roots[0]))
        } else {
            // 多个 root（含 Popup）：合并为一个虚拟根
            val sb = StringBuilder("{\"id\":-1,\"children\":[")
            roots.forEachIndexed { i, root ->
                if (i > 0) sb.append(",")
                sb.append(semantics.nodeToJson(root))
            }
            sb.append("]}")
            exchange.send(200, sb.toString())
        }
    }

    internal fun handleClick(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val targetWindow = windows[wid] ?: windows["main"]
        if (targetWindow != null && !targetWindow.isActive) {
            EventQueue.invokeAndWait {
                targetWindow.toFront()
                targetWindow.requestFocus()
            }
            Thread.sleep(50)
        }
        val x = params["x"]?.toFloatOrNull()
        val y = params["y"]?.toFloatOrNull()
        if (x != null && y != null) {
            // 坐标点击：优先按坐标找 clickable 节点走语义 action（绕过 Robot/系统权限）
            val node = semantics.findNodeAt(x, y, wid)
            val target = if (node != null) semantics.findClickableAncestor(node) else null
            val invoked = target != null && semantics.invokeClickAction(target)
            if (invoked) {
                exchange.send(200, """{"clicked":[$x,$y],"method":"action"}""")
            } else {
                // fallback Robot 坐标点击（macOS 需辅助功能权限）
                automation.clickScreen(x, y, wid)
                exchange.send(200, """{"clicked":[$x,$y],"method":"robot"}""")
            }
            return
        }
        // 按 testTag 或 text 查找节点点击
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"error":"node not found"}""")
            return
        }
        // 优先用语义 action 直接调用（绕过 Robot/系统权限，最可靠）
        // 若节点本身无 OnClick（如 Button 内部 Text），向上找带 OnClick 的祖先
        val clickTarget = semantics.findClickableAncestor(node)
        if (clickTarget != null && safeContains(clickTarget.config, SemanticsProperties.Disabled)) {
            exchange.send(409, """{"clicked":false,"error":"node disabled"}""")
            return
        }
        val invoked = clickTarget != null && semantics.invokeClickAction(clickTarget)
        if (invoked) {
            exchange.send(
                200,
                """{"clicked":true,"method":"action","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""",
            )
            return
        }
        // BasicTextField/RichTextEditor 本身没有 OnClick，但会暴露 RequestFocus。
        // 直接调用语义动作，保持整条自动化链路在进程内，不依赖 Robot。
        if (semantics.invokeRequestFocus(node)) {
            exchange.send(
                200,
                """{"clicked":true,"method":"focus-action","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""",
            )
            return
        }
        // fallback：坐标点击（需系统辅助功能权限）
        val bounds = node.boundsInWindow
        val centerX = (bounds.left + bounds.right) / 2
        val centerY = (bounds.top + bounds.bottom) / 2
        automation.clickScreen(centerX, centerY, wid)
        exchange.send(
            200,
            """{"clicked":["$centerX","$centerY"],"method":"robot","testTag":"${params["testTag"] ?: ""}"}""",
        )
    }

    /**
     * 原生双击必须在同一个 handler 内连续派发；两次独立 HTTP /click 可能超过系统多击阈值。
     * 该端点始终走 Robot，确保验证的是 PointerEvent/AWT 真实手势链，而不是直接调用业务回调。
     */
    internal fun handleDoubleClick(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        if (windows[wid] == null) {
            exchange.send(404, """{"error":"window not found"}""")
            return
        }

        val requestedX = params["x"]?.toFloatOrNull()
        val requestedY = params["y"]?.toFloatOrNull()
        val (x, y) = if (requestedX != null && requestedY != null) {
            requestedX to requestedY
        } else {
            val node = findNode(params["testTag"], params["text"], wid)
            if (node == null) {
                exchange.send(404, """{"error":"node not found"}""")
                return
            }
            val bounds = node.boundsInWindow
            ((bounds.left + bounds.right) / 2f) to ((bounds.top + bounds.bottom) / 2f)
        }

        automation.clickScreen(x, y, wid, clickCount = 2)
        exchange.send(
            200,
            """{"doubleclicked":[$x,$y],"method":"robot","window":"$wid","testTag":"${params["testTag"] ?: ""}"}""",
        )
    }

    /**
     * 返回窗口从 AWT 到 Skia 的完整尺寸链。
     *
     * macOS 原生全屏可能只扩大 NSWindow，而 Java 侧 JFrame 仍保留普通态 bounds；同时读取每一层
     * 才能区分业务布局留白与原生渲染层没有跟随全屏的问题。
     */
    internal fun handleWindowState(exchange: HttpExchange) {
        val wid = exchange.queryParams()["window"] ?: "main"
        val target = windows[wid]
        if (target == null) {
            exchange.send(404, """{"error":"window not found"}""")
            return
        }

        var payload = ""
        EventQueue.invokeAndWait {
            val transform = target.graphicsConfiguration.defaultTransform
            val skiaLayer = automation.findSkiaLayer(target)
            val placementOwner = target.desktopWindowPlacementOwner()
            val awtPlacement = target.placement
            val reportedPlacement = placementOwner?.effectivePlacement ?: awtPlacement
            val screen = target.graphicsConfiguration.bounds
            val insets = target.insets
            val exclusiveFullscreen = target.graphicsConfiguration.device.fullScreenWindow === target
            payload = buildString {
                append("{\"window\":\"").append(wid.escape()).append("\"")
                append(",\"placement\":\"").append(reportedPlacement.name).append("\"")
                append(",\"awtPlacement\":\"").append(awtPlacement.name).append("\"")
                append(",\"ownerPlacement\":\"").append(
                    placementOwner?.placement?.name ?: awtPlacement.name,
                ).append("\"")
                append(",\"ownerPlacementRole\":\"")
                    .append(if (placementOwner != null) "desired" else "actual")
                    .append("\"")
                append(",\"transitioning\":").append(placementOwner?.isTransitioning ?: false)
                append(",\"exclusiveFullscreen\":").append(exclusiveFullscreen)
                append(",\"x\":").append(target.x)
                append(",\"y\":").append(target.y)
                append(",\"width\":").append(target.width)
                append(",\"height\":").append(target.height)
                append(",\"scaleX\":").append(transform.scaleX)
                append(",\"scaleY\":").append(transform.scaleY)
                append(",\"visible\":").append(target.isVisible)
                append(",\"active\":").append(target.isActive)
                append(",\"extendedState\":").append(target.extendedState)
                append(",\"insets\":{")
                append("\"top\":").append(insets.top)
                append(",\"left\":").append(insets.left)
                append(",\"bottom\":").append(insets.bottom)
                append(",\"right\":").append(insets.right).append("}")
                append(",\"screen\":").append(
                    componentBoundsJson(screen.x, screen.y, screen.width, screen.height),
                )
                append(",\"rootPane\":").append(componentStateJson(target.rootPane))
                append(",\"contentPane\":").append(componentStateJson(target.contentPane))
                append(",\"composePanel\":").append(componentStateJson(target.contentPane.components.firstOrNull()))
                append(",\"skiaLayer\":").append(componentStateJson(skiaLayer))
                append(",\"skiaCanvas\":").append(componentStateJson(skiaLayer?.canvas))
                append(",\"skiaFullscreen\":").append(skiaLayer?.fullscreen ?: false)
                append("}")
            }
        }
        exchange.send(200, payload)
    }

    /** 请求 Compose 窗口进入、退出或切换全屏，供退出恢复与跨平台自动化使用。 */
    internal fun handleWindowFullscreen(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val target = windows[wid]
        if (target == null) {
            exchange.send(404, """{"error":"window not found"}""")
            return
        }
        val action = params["action"]?.lowercase() ?: "toggle"
        if (action !in setOf("enter", "exit", "leave", "toggle")) {
            exchange.send(400, """{"error":"action must be enter, exit or toggle"}""")
            return
        }

        var requestedPlacement = WindowPlacement.Floating
        EventQueue.invokeAndWait {
            val placementOwner = target.desktopWindowPlacementOwner()
            if (placementOwner != null) {
                requestedPlacement = when (action) {
                    "enter" -> WindowPlacement.Fullscreen
                    "exit", "leave" -> WindowPlacement.Maximized
                    else -> if (placementOwner.placement == WindowPlacement.Fullscreen) {
                        WindowPlacement.Maximized
                    } else {
                        WindowPlacement.Fullscreen
                    }
                }
                if (requestedPlacement == WindowPlacement.Fullscreen) {
                    placementOwner.enterFullscreen()
                } else {
                    placementOwner.restoreMaximized()
                }
                return@invokeAndWait
            }

            val current = target.placement
            val restorePlacement = preFullscreenPlacements[wid]
                ?: if ((target.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0) {
                    WindowPlacement.Maximized
                } else {
                    WindowPlacement.Floating
                }
            if (
                current != WindowPlacement.Fullscreen &&
                action in setOf("enter", "toggle")
            ) {
                preFullscreenPlacements[wid] = current
            }
            requestedPlacement = when (action) {
                "enter" -> WindowPlacement.Fullscreen
                "exit", "leave" -> if (current == WindowPlacement.Fullscreen) {
                    restorePlacement
                } else {
                    current
                }
                else -> if (current == WindowPlacement.Fullscreen) {
                    restorePlacement
                } else {
                    WindowPlacement.Fullscreen
                }
            }
            if (
                current == WindowPlacement.Fullscreen &&
                requestedPlacement != WindowPlacement.Fullscreen
            ) {
                preFullscreenPlacements.remove(wid)
            }
            if (current == WindowPlacement.Fullscreen && requestedPlacement != WindowPlacement.Fullscreen) {
                requestFullScreenExit(target, requestedPlacement)
            } else {
                target.placement = requestedPlacement
            }
        }
        exchange.send(
            200,
            """{"requested":"$action","requestedPlacement":"${requestedPlacement.name}","window":"${wid.escape()}"}""",
        )
    }

    /** macOS 必须先离开原生 Fullscreen，动画完成后才能恢复进入前的 Maximized 状态。 */
    private fun requestFullScreenExit(target: ComposeWindow, restorePlacement: WindowPlacement) {
        target.placement = WindowPlacement.Floating
        if (restorePlacement == WindowPlacement.Floating) return

        var attempts = 0
        val restoreTimer = javax.swing.Timer(100, null)
        restoreTimer.addActionListener {
            attempts++
            when {
                !target.isDisplayable -> restoreTimer.stop()
                target.placement != WindowPlacement.Fullscreen -> {
                    target.placement = restorePlacement
                    restoreTimer.stop()
                }
                attempts >= 50 -> restoreTimer.stop()
            }
        }
        restoreTimer.initialDelay = 100
        restoreTimer.start()
    }

    /** 长按：调用节点的 OnLongClick 语义 action（触发 combinedClickable 的长按菜单）。 */
    internal fun handleLongClick(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        // 坐标长按：按坐标找节点，向上找带 OnLongClick 的祖先
        val x = params["x"]?.toFloatOrNull()
        val y = params["y"]?.toFloatOrNull()
        if (x != null && y != null) {
            val node = semantics.findNodeAt(x, y, wid)
            if (node != null) {
                val invoked = semantics.invokeLongClickAction(node)
                exchange.send(200, """{"longclicked":$invoked,"method":"coord"}""")
                return
            }
            exchange.send(404, """{"error":"no node at [$x,$y]"}""")
            return
        }
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"error":"node not found"}""")
            return
        }
        val invoked = semantics.invokeLongClickAction(node)
        if (invoked) {
            exchange.send(
                200,
                """{"longclicked":true,"method":"action","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""",
            )
        } else {
            exchange.send(
                200,
                """{"longclicked":false,"method":"none","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""",
            )
        }
    }

    /** 右键点击：Robot BUTTON3（桌面上下文菜单手势，e2e 用；macOS 需辅助功能权限）。 */
    internal fun handleRightClick(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val x = params["x"]?.toFloatOrNull()
        val y = params["y"]?.toFloatOrNull()
        if (x == null || y == null) {
            exchange.send(400, """{"error":"need x,y"}""")
            return
        }
        val window = automation.window(exchange) ?: run {
            exchange.send(404, """{"error":"no window"}""")
            return
        }
        synchronized(robotLock) {
            if (!window.isActive) {
                window.toFront(); window.requestFocus(); Thread.sleep(150)
            }
            // Compose 语义 bounds 在 Retina 上使用设备像素，AWT Window/Robot 使用逻辑点。
            // 必须除以当前屏幕 transform；直接使用语义坐标会让右半窗口的点击落到窗外。
            val (screenX, screenY) = automation.composePointToScreen(window, x, y)
            robot.mouseMove(screenX, screenY)
            Thread.sleep(50)
            robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
            Thread.sleep(20)
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
            robot.waitForIdle()
            Thread.sleep(150)
        }
        exchange.send(200, """{"rightclicked":[$x,$y]}""")
    }

    /**
     * /scroll?testTag=xxx&direction=down&amount=3   在节点中心滚动真实滚轮
     * /scroll?x=N&y=N&direction=up                  在坐标处滚动；缺省目标为窗口中心
     * 始终走 Robot 滚轮：可滚动容器（verticalScroll/LazyColumn）没有可直接调用的
     * 语义动作端点，滚轮链路同时验证真实手势。macOS 需要辅助功能权限（与双击一致）。
     */
    internal fun handleScroll(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val window = windows[wid] ?: windows["main"]
        if (window == null) {
            exchange.send(404, """{"error":"window not found"}""")
            return
        }
        val direction = params["direction"]?.lowercase() ?: "down"
        if (direction !in setOf("up", "down")) {
            exchange.send(400, """{"error":"direction must be up or down"}""")
            return
        }
        val notches = (params["amount"]?.toIntOrNull() ?: 3).coerceIn(1, 20)

        val requestedX = params["x"]?.toFloatOrNull()
        val requestedY = params["y"]?.toFloatOrNull()
        val (x, y) = when {
            requestedX != null && requestedY != null -> requestedX to requestedY
            else -> {
                val node = findNode(params["testTag"], params["text"], wid)
                    ?: run {
                        exchange.send(404, """{"error":"node not found"}""")
                        return
                    }
                val bounds = node.boundsInWindow
                ((bounds.left + bounds.right) / 2f) to ((bounds.top + bounds.bottom) / 2f)
            }
        }
        synchronized(robotLock) {
            // 滚轮跟随指针下的窗口（macOS 语义），不激活 `window` 参数指向的窗口：
            // 模态弹窗是独立的 Dialog 场景窗口且不在 windows 注册表里，抢主窗口
            // 焦点反而会把滚轮从弹窗内容上抢走。
            val (screenX, screenY) = automation.composePointToScreen(window, x, y)
            robot.mouseMove(screenX, screenY)
            Thread.sleep(80)
            robot.mouseWheel(if (direction == "down") notches else -notches)
            robot.waitForIdle()
            Thread.sleep(150)
        }
        exchange.send(
            200,
            """{"scrolled":true,"direction":"$direction","amount":$notches,"at":[$x,$y],"window":"${wid.escape()}"}""",
        )
    }

    internal fun handleInput(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val text = exchange.readBody().ifEmpty { params["text"] ?: "" }
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"]?.takeIf { params["testTag"] == null }, wid)
        if (node == null) {
            exchange.send(404, """{"error":"node not found"}""")
            return
        }
        // 优先用 SetText 语义 action 直接设置文本（绕过 Robot/剪贴板/系统权限）
        val setOk = semantics.invokeSetTextAction(node, text)
        if (setOk) {
            exchange.send(200, """{"input":"${text.escape()}","method":"action"}""")
            return
        }
        // fallback：Robot 激活焦点 + 剪贴板粘贴（需系统辅助功能权限）。共享 Robot 的整段
        // 手势必须串行，避免与双击/右键请求交错后留下按键或鼠标按钮未释放。
        synchronized(robotLock) {
            if (!semantics.invokeClickAction(node)) {
                val bounds = node.boundsInWindow
                automation.clickScreen((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
            }
            Thread.sleep(150)
            // 清空 + 粘贴
            robot.keyPress(KeyEvent.VK_META); robot.keyPress(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_META)
            Thread.sleep(30)
            robot.keyPress(KeyEvent.VK_DELETE); robot.keyRelease(KeyEvent.VK_DELETE)
            Thread.sleep(50)
            automation.pasteText(text)
        }
        exchange.send(200, """{"input":"${text.escape()}","method":"robot"}""")
    }

    internal fun handleSetProgress(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val value = params["value"]?.toFloatOrNull()
        if (value == null || !value.isFinite()) {
            exchange.send(400, """{"error":"finite value is required"}""")
            return
        }
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"error":"node not found"}""")
            return
        }
        val invoked = semantics.invokeSetProgressAction(node, value)
        if (!invoked) {
            exchange.send(409, """{"progress":false,"error":"SetProgress unavailable"}""")
            return
        }
        exchange.send(
            200,
            """{"progress":true,"value":$value,"testTag":"${params["testTag"] ?: ""}"}""",
        )
    }

    internal fun handleScreenshot(exchange: HttpExchange) {
        val window = automation.window(exchange) ?: run {
            exchange.send(404, """{"error":"no window"}""")
            return
        }
        val screenMode = exchange.queryParams()["mode"] == "screen"
        val wasAlwaysOnTop = window.isAlwaysOnTop
        val bytes = try {
            // ComposeWindow 使用独立的 Skia 硬件层，Swing paintAll 只能得到窗口背景。
            // 直接读取当前 Skia 渲染帧，避免系统录屏权限、窗口遮挡和 macOS Space 影响截图。
            val captured = arrayOfNulls<ByteArray>(1)
            EventQueue.invokeAndWait {
                if ((window.extendedState and java.awt.Frame.ICONIFIED) != 0) {
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
                window.isVisible = true
                window.toFront()
                window.requestFocus()
                if (!screenMode) {
                    window.isAlwaysOnTop = true
                    if (window.placement != WindowPlacement.Fullscreen) window.validate()
                    val skiaLayer = automation.findSkiaLayer(window)
                        ?: error("Compose Skia layer not found")
                    skiaLayer.renderImmediately()
                    skiaLayer.screenshot()?.use { bitmap ->
                        Image.makeFromBitmap(bitmap).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)?.use { data ->
                                captured[0] = data.bytes
                            } ?: error("PNG encoding failed")
                        }
                    }
                }
            }
            if (screenMode) {
                automation.captureScreenPng(window)
            } else {
                captured[0] ?: error("window capture failed")
            }
        } catch (e: Exception) {
            exchange.send(500, """{"error":"${e.message?.escape()}"}""")
            return
        } finally {
            EventQueue.invokeLater { window.isAlwaysOnTop = wasAlwaysOnTop }
        }
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    internal fun handleFind(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"found":false}""")
            return
        }
        val bounds = node.boundsInWindow
        exchange.send(
            200,
            """{"found":true,"bounds":[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]}""",
        )
    }

    /**
     * /wait?testTag=xxx&timeout=10        等待节点出现
     * /wait?text=登录&timeout=10           等待节点出现
     * /wait?text=登录&timeout=10&gone=true 等待节点消失
     * 返回 {"met":true/false}
     */
    internal fun handleWait(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val timeoutSec = (params["timeout"] ?: "10").toIntOrNull() ?: 10
        val gone = params["gone"]?.toBoolean() == true
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        var met = false
        while (System.currentTimeMillis() < deadline) {
            val found = findNode(params["testTag"], params["text"], params["window"] ?: "main") != null
            if (gone && !found) {
                met = true
                break
            }
            if (!gone && found) {
                met = true
                break
            }
            Thread.sleep(300)
        }
        exchange.send(200, """{"met":$met}""")
    }

    /**
     * /keypress?key=ESCAPE          注入键盘事件
     * /keypress?key=A&meta=true     带 Cmd（macOS）/Ctrl 修饰键（全选等组合）
     * 支持：ESCAPE / ENTER / TAB / BACKSPACE / A..Z / 0..9
     */
    internal fun handleKeypress(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val windowId = params["window"] ?: "main"
        val targetWindow = windows[windowId] ?: windows["main"]
        if (targetWindow == null) {
            exchange.send(404, """{"error":"window not found"}""")
            return
        }
        val keyName = params["key"]?.uppercase() ?: "ESCAPE"
        val meta = params["meta"]?.toBoolean() == true
        val keyCode = when (keyName) {
            "ESCAPE" -> KeyEvent.VK_ESCAPE
            "ENTER" -> KeyEvent.VK_ENTER
            "TAB" -> KeyEvent.VK_TAB
            "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
            "SPACE" -> KeyEvent.VK_SPACE
            else -> KeyEvent.getExtendedKeyCodeForChar(keyName.firstOrNull()?.code ?: ' '.code)
        }
        val modifiers = if (meta) {
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                InputEvent.META_DOWN_MASK
            } else {
                InputEvent.CTRL_DOWN_MASK
            }
        } else {
            0
        }

        // 直接在进程内分发到指定 AWT 窗口。Robot 会把按键交给当前系统焦点，
        // 遇到 macOS 权限弹窗或多个子窗口时会发错目标，也违背内嵌服务的确定性。
        var handled = false
        EventQueue.invokeAndWait {
            if (keyCode == KeyEvent.VK_ESCAPE && !meta) {
                handled = dispatchWindowEscape(targetWindow)
            } else {
                val source = targetWindow.focusOwner ?: targetWindow.contentPane
                val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                val now = System.currentTimeMillis()
                handled = manager.dispatchKeyEvent(
                    KeyEvent(source, KeyEvent.KEY_PRESSED, now, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED),
                )
                // AWT 文本输入依赖 KEY_TYPED；只派发 pressed/released 可以触发
                // Compose 快捷键，却不会真正向编辑器写入换行。组合键不产生字符事件。
                val typedChar = when {
                    meta -> null
                    keyName == "ENTER" -> '\n'
                    keyName == "SPACE" -> ' '
                    keyName.length == 1 && keyName[0].isLetterOrDigit() -> keyName.lowercase()[0]
                    else -> null
                }
                if (typedChar != null) {
                    handled = manager.dispatchKeyEvent(
                        KeyEvent(
                            source,
                            KeyEvent.KEY_TYPED,
                            now,
                            0,
                            KeyEvent.VK_UNDEFINED,
                            typedChar,
                        ),
                    ) || handled
                }
                manager.dispatchKeyEvent(
                    KeyEvent(source, KeyEvent.KEY_RELEASED, now, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED),
                )
            }
        }
        exchange.send(
            200,
            """{"key":"$keyName","meta":$meta,"code":$keyCode,"method":"dispatch","window":"$windowId","handled":$handled}""",
        )
    }

    private fun findNode(testTag: String?, text: String?, windowId: String): SemanticsNode? =
        semantics.findNode(testTag, text, windowId)

    private fun <T> safeContains(
        cfg: SemanticsConfiguration,
        key: SemanticsPropertyKey<T>,
    ): Boolean = semantics.safeContains(cfg, key)

    private fun <T> safeGet(
        cfg: SemanticsConfiguration,
        key: SemanticsPropertyKey<T>,
    ): T? = semantics.safeGet(cfg, key)
}
