package com.virjar.tk.test

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.InetSocketAddress
import javax.imageio.ImageIO

/**
 * Desktop UI 自动化测试 HTTP 服务。
 *
 * 在真实 Desktop app 进程内嵌入轻量 HTTP 服务，导出 Compose 语义树并接收操作指令，
 * 供外部测试脚本（Python/HTTP）端到端驱动 app。模式同 Android uiautomator2。
 *
 * ⚠️ 安全：仅在测试构建启用。production 构建 BuildConfig.TEST_HTTP_SERVER=false，
 * 经 ProGuard 死代码消除移除整个类（const val 传播 + if(false) 块消除）。
 * 由 gmazzo buildconfig 插件按 Gradle property (-PenableTestHttp) 控制常量值。
 *
 * HTTP API：
 *   GET  /ping                      健康检查
 *   GET  /semantics                 导出语义树 JSON（id/testTag/text/clickable/bounds/children）
 *   POST /click?x=N&y=N             点击屏幕坐标（用 Robot 模拟真实鼠标）
 *   POST /click?testTag=xxx         按 testTag 查找节点并点击其中心
 *   POST /click?text=xxx            按 text 查找节点并点击其中心
 *   POST /input?testTag=xxx         向 testTag 节点输入文本（清空后输入）
 *   POST /input?text=xxx           同上，按 text 定位
 *   GET  /screenshot                返回窗口截图 PNG
 *   GET  /find?testTag=xxx          查找节点是否存在，返回 {found, bounds}
 */
object TestHttpServer {

    // 编译期常量：dev/demo 构建为 true（启用测试服务），production 为 false（ProGuard 删除整个类）
    private const val ENABLED = com.virjar.tk.BuildConfig.TEST_HTTP_SERVER

    /** 已注册窗口映射：id → ComposeWindow。主窗口 id="main"，子窗口用 SubScreen 名。 */
    private val windows = java.util.concurrent.ConcurrentHashMap<String, ComposeWindow>()
    private var server: HttpServer? = null
    private val robot: Robot by lazy { Robot() }

    /** 反射调用的无参入口（供 TestServiceBridge 用，规避 Kotlin 默认参数的方法签名问题）。 */
    @JvmStatic
    fun startDefault() = start()

    @JvmStatic
    fun enabled(): Boolean = ENABLED

    /** app 创建窗口后调用，注册窗口供语义树访问。 */
    @JvmStatic
    fun registerWindow(window: ComposeWindow) {
        registerWindow("main", window)
    }

    /** 注册窗口（指定 id，子窗口用 SubScreen 名作为 id）。 */
    @JvmStatic
    fun registerWindow(id: String, window: ComposeWindow) {
        if (!ENABLED) return
        windows[id] = window
    }

    /** 注销窗口（子窗口关闭时调用）。 */
    @JvmStatic
    fun unregisterWindow(id: String) {
        if (!ENABLED) return
        windows.remove(id)
    }

    /** 获取指定窗口（默认 main）。 */
    private fun window(exchange: HttpExchange): ComposeWindow? {
        val id = exchange.queryParams()["window"] ?: "main"
        return windows[id] ?: windows["main"]
    }

    /** 在 application{} 启动时调用，启动 HTTP 服务。 */
    fun start(port: Int = 18080) {
        if (!ENABLED) return
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        // 所有 handler 用 safe 包裹：异常返回 500 而非挂起连接
        s.createContext("/ping", ::handlePing)
        s.createContext("/debug", safe(::handleDebug))
        s.createContext("/semantics", safe(::handleSemantics))
        s.createContext("/click", safe(::handleClick))
        s.createContext("/longclick", safe(::handleLongClick))
        s.createContext("/input", safe(::handleInput))
        s.createContext("/screenshot", safe(::handleScreenshot))
        s.createContext("/find", safe(::handleFind))
        s.createContext("/wait", safe(::handleWait))
        s.createContext("/keypress", safe(::handleKeypress))
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.start()
        server = s
        println("[TestHttpServer] listening on http://127.0.0.1:$port")
    }

    /** handler 错误兜底：任何异常返回 500 JSON，保证连接不挂起。 */
    private fun safe(handler: (HttpExchange) -> Unit): com.sun.net.httpserver.HttpHandler {
        return { exchange ->
            try {
                handler(exchange)
            } catch (e: Exception) {
                try {
                    exchange.send(500, """{"error":"${e.message?.escape() ?: e.javaClass.simpleName}"}""")
                } catch (_: Exception) {
                    // 连接已关闭等，忽略
                }
            }
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // ───────── HTTP handlers ─────────

    private fun handlePing(exchange: HttpExchange) {
        exchange.send(200, """{"status":"ok"}""")
    }

    /** 调试端点：dump 指定节点的 config（逐个已知 key 检查，避免遍历报错）。/debug?text=注册 */
    private fun handleDebug(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"error":"not found"}""")
            return
        }
        val cfg = node.config
        val hasOnClick = safeContains(cfg, SemanticsActions.OnClick)
        val onClickAction: androidx.compose.ui.semantics.AccessibilityAction<*>? =
            if (hasOnClick) safeGet(cfg, SemanticsActions.OnClick) else null
        val sb = StringBuilder("{\"id\":${node.id}")
        sb.append(",\"hasOnClick\":$hasOnClick")
        sb.append(",\"onClickLabel\":\"${onClickAction?.label ?: ""}\"")
        sb.append(",\"onClickHasAction\":${onClickAction?.action != null}")
        sb.append(",\"parentClickable\":${node.parent?.let { safeContains(it.config, SemanticsActions.OnClick) } ?: "null"}")
        sb.append("}")
        exchange.send(200, sb.toString())
    }

    /** 安全 contains（catch 内部异常，避免合并节点遍历越界）。 */
    private fun <T> safeContains(
        cfg: androidx.compose.ui.semantics.SemanticsConfiguration,
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
    ): Boolean = try {
        cfg.contains(key)
    } catch (e: Exception) {
        println("[debug] contains failed: ${e.message}")
        false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> safeGet(
        cfg: androidx.compose.ui.semantics.SemanticsConfiguration,
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
    ): T? = try {
        if (cfg.contains(key)) cfg.get(key) else null
    } catch (e: Exception) {
        println("[debug] get failed: ${e.message}")
        null
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun handleSemantics(exchange: HttpExchange) {
        val wid = exchange.queryParams()["window"] ?: "main"
        val roots = allRoots(wid)
        if (roots.isEmpty()) {
            exchange.send(404, """{"error":"no semantics root"}""")
            return
        }
        // 合并所有 owners（主窗口 + Popup）的子节点到一个 JSON 数组
        if (roots.size == 1) {
            exchange.send(200, nodeToJson(roots[0]))
        } else {
            // 多个 root（含 Popup）：合并为一个虚拟根
            val sb = StringBuilder("{\"id\":-1,\"children\":[")
            roots.forEachIndexed { i, r ->
                if (i > 0) sb.append(",")
                sb.append(nodeToJson(r))
            }
            sb.append("]}")
            exchange.send(200, sb.toString())
        }
    }

    private fun handleClick(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val x = params["x"]?.toFloatOrNull()
        val y = params["y"]?.toFloatOrNull()
        if (x != null && y != null) {
            // 坐标点击：优先按坐标找 clickable 节点走语义 action（绕过 Robot/系统权限）
            val wid = params["window"] ?: "main"
            val node = findNodeAt(x, y, wid)
            val target = if (node != null) findClickableAncestor(node) else null
            val invoked = target != null && invokeClickAction(target)
            if (invoked) {
                exchange.send(200, """{"clicked":[$x,$y],"method":"action"}""")
            } else {
                // fallback Robot 坐标点击（macOS 需辅助功能权限）
                clickScreen(x, y)
                exchange.send(200, """{"clicked":[$x,$y],"method":"robot"}""")
            }
            return
        }
        // 按 testTag 或 text 查找节点点击
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"error":"node not found"}""")
            return
        }
        // 优先用语义 action 直接调用（绕过 Robot/系统权限，最可靠）
        // 若节点本身无 OnClick（如 Button 内部 Text），向上找带 OnClick 的祖先
        val clickTarget = findClickableAncestor(node)
        val invoked = clickTarget != null && invokeClickAction(clickTarget)
        if (invoked) {
            exchange.send(200, """{"clicked":true,"method":"action","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""")
            return
        }
        // fallback：坐标点击（需系统辅助功能权限）
        val b = node.boundsInWindow
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        clickScreen(cx, cy)
        exchange.send(200, """{"clicked":["$cx","$cy"],"method":"robot","testTag":"${params["testTag"] ?: ""}"}""")
    }

    /** 长按：调用节点的 OnLongClick 语义 action（触发 combinedClickable 的长按菜单）。 */
    private fun handleLongClick(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        // 坐标长按：按坐标找节点，向上找带 OnLongClick 的祖先
        val x = params["x"]?.toFloatOrNull()
        val y = params["y"]?.toFloatOrNull()
        if (x != null && y != null) {
            val node = findNodeAt(x, y, wid)
            if (node != null) {
                val invoked = invokeLongClickAction(node)
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
        val invoked = invokeLongClickAction(node)
        if (invoked) {
            exchange.send(200, """{"longclicked":true,"method":"action","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""")
        } else {
            exchange.send(200, """{"longclicked":false,"method":"none","testTag":"${params["testTag"] ?: params["text"] ?: ""}"}""")
        }
    }

    private fun handleInput(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val text = exchange.readBody().ifEmpty { params["text"] ?: "" }
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"]?.takeIf { params["testTag"] == null }, wid)
        if (node == null) {
            exchange.send(404, """{"error":"node not found"}""")
            return
        }
        // 优先用 SetText 语义 action 直接设置文本（绕过 Robot/剪贴板/系统权限）
        val setOk = invokeSetTextAction(node, text)
        if (setOk) {
            exchange.send(200, """{"input":"${text.escape()}","method":"action"}""")
            return
        }
        // fallback：Robot 激活焦点 + 剪贴板粘贴（需系统辅助功能权限）
        if (!invokeClickAction(node)) {
            val b = node.boundsInWindow
            clickScreen((b.left + b.right) / 2, (b.top + b.bottom) / 2)
        }
        Thread.sleep(150)
        // 清空 + 粘贴
        robot.keyPress(KeyEvent.VK_META); robot.keyPress(KeyEvent.VK_A)
        robot.keyRelease(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_META)
        Thread.sleep(30)
        robot.keyPress(KeyEvent.VK_DELETE); robot.keyRelease(KeyEvent.VK_DELETE)
        Thread.sleep(50)
        pasteText(text)
        exchange.send(200, """{"input":"${text.escape()}","method":"robot"}""")
    }

    /** 调用 TextField 的 SetText 语义 action，直接设置文本。 */
    @Suppress("UNCHECKED_CAST")
    private fun invokeSetTextAction(node: SemanticsNode, text: String): Boolean {
        // SetText action 可能在本节点或祖先（TextField 合并语义）
        val target = findNodeWithAction(node, SemanticsActions.SetText) ?: return false
        val cfg = target.config
        if (!safeContains(cfg, SemanticsActions.SetText)) return false
        val action = safeGet(cfg, SemanticsActions.SetText) ?: return false
        val lambda = action.action ?: return false
        return try {
            // SetText 是 (AnnotatedString) -> Boolean
            val annotated = androidx.compose.ui.text.AnnotatedString(text)
            (lambda as kotlin.Function1<Any?, *>).invoke(annotated)
            true
        } catch (e: Exception) {
            println("[TestHttpServer] SetText action failed: ${e.message}")
            false
        }
    }

    /** 从 node 向上找带指定 action 的祖先（含自身）。 */
    private fun findNodeWithAction(
        node: SemanticsNode,
        action: androidx.compose.ui.semantics.SemanticsPropertyKey<*>,
    ): SemanticsNode? {
        var current: SemanticsNode? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (safeContains(current.config, action)) return current
            current = current.parent
            depth++
        }
        return null
    }

    // ───────── 语义 action 调用（绕过系统权限）─────────

    /** 直接调用节点的 OnClick 语义 action，返回是否成功。 */
    @Suppress("UNCHECKED_CAST")
    private fun invokeClickAction(node: SemanticsNode): Boolean {
        val cfg = node.config
        if (!safeContains(cfg, SemanticsActions.OnClick)) return false
        val action = safeGet(cfg, SemanticsActions.OnClick) ?: return false
        val lambda = action.action ?: return false
        return try {
            // OnClick action 是 () -> Boolean，统一作为 Function0 调用
            (lambda as kotlin.Function0<*>).invoke()
            true
        } catch (e: ClassCastException) {
            // 类型转换失败，尝试反射兜底
            try {
                lambda.javaClass.getMethod("invoke").invoke(lambda)
                true
            } catch (e2: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 调用节点的 OnLongClick 语义 action（用于 combinedClickable 的长按菜单）。 */
    private fun invokeLongClickAction(node: SemanticsNode): Boolean {
        var current: SemanticsNode? = node
        // 向上查找带 OnLongClick 的节点（与 findClickableAncestor 类似）
        while (current != null) {
            val cfg = current.config
            if (safeContains(cfg, SemanticsActions.OnLongClick)) {
                val action = safeGet(cfg, SemanticsActions.OnLongClick) ?: return false
                val lambda = action.action ?: return false
                return try {
                    (lambda as kotlin.Function0<*>).invoke()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            current = current.parent
        }
        return false
    }

    /** 通过剪贴板粘贴文本（支持中文，绕过 IME 和逐字符限制）。 */
    private fun pasteText(text: String) {
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

    private fun handleScreenshot(exchange: HttpExchange) {
        val window = window(exchange) ?: run {
            exchange.send(404, """{"error":"no window"}""")
            return
        }
        val img = try {
            // 截取窗口内容
            val bounds = window.bounds
            java.awt.Robot().createScreenCapture(bounds)
        } catch (e: Exception) {
            exchange.send(500, """{"error":"${e.message?.escape()}"}""")
            return
        }
        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        val bytes = baos.toByteArray()
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleFind(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val wid = params["window"] ?: "main"
        val node = findNode(params["testTag"], params["text"], wid)
        if (node == null) {
            exchange.send(404, """{"found":false}""")
            return
        }
        val b = node.boundsInWindow
        exchange.send(200, """{"found":true,"bounds":[${b.left},${b.top},${b.right},${b.bottom}]}""")
    }

    /**
     * /wait?testTag=xxx&timeout=10        等待节点出现
     * /wait?text=登录&timeout=10           等待节点出现
     * /wait?text=登录&timeout=10&gone=true 等待节点消失
     * 返回 {"met":true/false}
     */
    private fun handleWait(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val timeoutSec = (params["timeout"] ?: "10").toIntOrNull() ?: 10
        val gone = params["gone"]?.toBoolean() == true
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        var met = false
        while (System.currentTimeMillis() < deadline) {
            val found = findNode(params["testTag"], params["text"], params["window"] ?: "main") != null
            if (gone && !found) { met = true; break }
            if (!gone && found) { met = true; break }
            Thread.sleep(300)
        }
        exchange.send(200, """{"met":$met}""")
    }

    /**
     * /keypress?key=ESCAPE  注入键盘事件（ESC 等，用于关闭面板/对话框）
     * 支持：ESCAPE / ENTER / TAB / BACKSPACE
     */
    private fun handleKeypress(exchange: HttpExchange) {
        val params = exchange.queryParams()
        val keyName = params["key"]?.uppercase() ?: "ESCAPE"
        val keyCode = when (keyName) {
            "ESCAPE" -> KeyEvent.VK_ESCAPE
            "ENTER" -> KeyEvent.VK_ENTER
            "TAB" -> KeyEvent.VK_TAB
            "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
            else -> KeyEvent.VK_ESCAPE
        }
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        robot.waitForIdle()
        Thread.sleep(50)
        exchange.send(200, """{"key":"$keyName","code":$keyCode}""")
    }

    // ───────── 语义树访问 ─────────

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun allRoots(windowId: String = "main"): List<SemanticsNode> {
        val window = windows[windowId] ?: windows["main"] ?: return emptyList()
        val owners = try { window.semanticsOwners } catch (e: Exception) { return emptyList() }
        return owners.mapNotNull { it.rootSemanticsNode }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Deprecated("Use allRoots for Popup support", ReplaceWith("allRoots(windowId)"))
    private fun root(windowId: String = "main"): SemanticsNode? = allRoots(windowId).firstOrNull()

    /** 在语义树中查找节点：优先 testTag，其次 text。遍历所有 owners（含 Popup）。 */
    private fun findNode(testTag: String?, text: String?, windowId: String = "main"): SemanticsNode? {
        for (r in allRoots(windowId)) {
            val found = findInTree(r, testTag, text)
            if (found != null) return found
        }
        return null
    }

    /** 从 node 开始向上找第一个带 OnClick action 的祖先（含自身）。 */
    private fun findClickableAncestor(node: SemanticsNode): SemanticsNode? {
        var current: SemanticsNode? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (safeContains(current.config, SemanticsActions.OnClick)) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** 在语义树中找包含坐标 (x,y) 的最深层节点（用于坐标点击走语义 action）。 */
    private fun findNodeAt(x: Float, y: Float, windowId: String = "main"): SemanticsNode? {
        // 遍历所有 owners（含 Popup），找到坐标命中的节点
        for (r in allRoots(windowId)) {
            val found = findNodeAtInTree(r, x, y)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeAtInTree(node: SemanticsNode, x: Float, y: Float): SemanticsNode? {
        val b = node.boundsInWindow
        val contains = x >= b.left && x <= b.right && y >= b.top && y <= b.bottom
        if (!contains) return null
        // 优先返回最深层（子节点）的匹配
        for (child in node.children) {
            val deeper = findNodeAtInTree(child, x, y)
            if (deeper != null) return deeper
        }
        return node
    }

    private fun findInTree(node: SemanticsNode, testTag: String?, text: String?): SemanticsNode? {
        val cfg = node.config
        if (testTag != null) {
            val tag = if (cfg.contains(SemanticsProperties.TestTag)) cfg[SemanticsProperties.TestTag] as? String else null
            if (tag == testTag) return node
        } else if (text != null) {
            val t = if (cfg.contains(SemanticsProperties.Text)) {
                (cfg[SemanticsProperties.Text] as? List<*>)?.joinToString("") { it.toString() }
            } else null
            if (t == text) return node
        }
        for (child in node.children) {
            findInTree(child, testTag, text)?.let { return it }
        }
        return null
    }

    // ───────── 语义树 → JSON ─────────

    private fun nodeToJson(node: SemanticsNode, depth: Int = 0): String {
        if (depth > 50) return "{}"
        val cfg = node.config
        val tag: String? = if (cfg.contains(SemanticsProperties.TestTag)) cfg[SemanticsProperties.TestTag] as? String else null
        val text: String? = if (cfg.contains(SemanticsProperties.Text)) {
            (cfg[SemanticsProperties.Text] as? List<*>)?.joinToString("") { it.toString() }
        } else null
        val editableText: String? = if (cfg.contains(SemanticsProperties.EditableText)) {
            cfg[SemanticsProperties.EditableText].toString()
        } else null
        val clickable = cfg.contains(SemanticsActions.OnClick)
        val b: Rect = node.boundsInWindow
        val children = node.children
        val sb = StringBuilder("{")
        sb.append("\"id\":${node.id}")
        tag?.let { sb.append(",\"testTag\":\"${it.escape()}\"") }
        text?.let { sb.append(",\"text\":\"${it.escape()}\"") }
        editableText?.let { sb.append(",\"editableText\":\"${it.escape()}\"") }
        sb.append(",\"clickable\":$clickable")
        sb.append(",\"bounds\":[${b.left},${b.top},${b.right},${b.bottom}]")
        if (children.isNotEmpty()) {
            sb.append(",\"children\":[")
            sb.append(children.joinToString(",") { nodeToJson(it, depth + 1) })
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    // ───────── 输入模拟 ─────────

    /** 点击屏幕坐标（窗口内坐标 → 屏幕坐标转换）。先置前窗口再点击。 */
    private fun clickScreen(x: Float, y: Float, windowId: String = "main") {
        val window = windows[windowId] ?: windows["main"] ?: return
        // 确保窗口在前台（Robot 点击需要窗口聚焦）
        if (!window.isActive) {
            window.toFront()
            window.requestFocus()
            Thread.sleep(100)
        }
        val loc = window.locationOnScreen
        val sx = loc.x + x.toInt()
        val sy = loc.y + y.toInt()
        // 移动到目标位置并短暂停留（某些系统需要 mouseMove 触发 hover）
        robot.mouseMove(sx, sy)
        Thread.sleep(50)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(20)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.waitForIdle()
    }

    // ───────── 工具 ─────────

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun HttpExchange.queryParams(): Map<String, String> {
        val q = requestURI.query ?: return emptyMap()
        return q.split("&").mapNotNull {
            val idx = it.indexOf("=")
            if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
        }.toMap()
    }

    private fun HttpExchange.readBody(): String =
        requestBody.bufferedReader().use { it.readText() }

    private fun HttpExchange.send(code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
