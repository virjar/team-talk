package com.virjar.tk.desktop.test

import androidx.compose.ui.awt.ComposeWindow
import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

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
 *   GET  /window-state              读取窗口与 Compose/Skia 各层 placement、位置和尺寸
 *   POST /window-activate           请求操作系统将目标窗口置为真实前台窗口
 *   POST /window-fullscreen         请求窗口进入、退出或切换全屏
 *   POST /click?x=N&y=N             点击屏幕坐标（用 Robot 模拟真实鼠标）
 *   POST /click?testTag=xxx         按 testTag 查找节点并点击其中心
 *   POST /click?text=xxx            按 text 查找节点并点击其中心
 *   POST /doubleclick?testTag=xxx   在节点中心派发一次真实鼠标双击
 *   POST /scroll?testTag=xxx        在节点中心滚动滚轮（direction=up|down，amount=格数）
 *   POST /input?testTag=xxx         向 testTag 节点输入文本（清空后输入）
 *   POST /input?text=xxx           同上，按 text 定位
 *   POST /set-progress?testTag=xxx  设置 Slider/进度语义值
 *   GET  /screenshot                返回窗口 Skia 截图 PNG；mode=screen 截取所在物理屏幕
 *   GET  /find?testTag=xxx          查找节点是否存在，返回 {found, bounds}
 */
object TestHttpServer {

    // 编译期常量：开发运行时为 true，发布包通过 ProGuard 删除整个类。
    private const val ENABLED = com.virjar.tk.desktop.BuildConfig.TEST_HTTP_SERVER

    private val automation = TestHttpAutomation()
    private var server: HttpServer? = null
    private var serverExecutor: ExecutorService? = null

    /** 反射调用的无参入口（供 TestServiceBridge 用，规避 Kotlin 默认参数的方法签名问题）。 */
    @JvmStatic
    fun startDefault() = start()

    /**
     * 本次进程实例的随机令牌：自动化验收用它区分"刚启动的实例"与占用端口的僵尸实例。
     * 每次进程启动随机生成；[TestHttpAutomation] 的所有响应头也携带该值。
     */
    @JvmStatic
    val instanceToken: String = System.getProperty("tk.desktop.instance.token")
        ?: java.util.UUID.randomUUID().toString().take(12)

    @JvmStatic
    fun instanceToken(): String = instanceToken

    private fun instanceTokenFilter(): Filter = object : Filter() {
        override fun doFilter(exchange: HttpExchange, chain: Filter.Chain) {
            exchange.responseHeaders.add("X-Instance-Token", instanceToken)
            chain.doFilter(exchange)
        }

        override fun description() = "instance-token"
    }

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
        automation.registerWindow(id, window)
    }

    /** 注销窗口（子窗口关闭时调用）。 */
    @JvmStatic
    fun unregisterWindow(id: String) {
        if (!ENABLED) return
        automation.unregisterWindow(id)
    }

    /** 在 application{} 启动时调用，启动 HTTP 服务。 */
    @Synchronized
    fun start(port: Int = 18080) {
        if (!ENABLED) return
        if (server != null) return
        val routes = automation.routes
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        // 每个 response 附带 X-Instance-Token：验收工具无需依赖具体端点即可识别当前实例，
        // 防止僵尸进程占用 18080 时把旧实例误当成刚启动的新实例。
        // Filter 挂在每个 HttpContext 上；逐 context 注入响应头令牌。
        val tokenFilter = instanceTokenFilter()
        fun context(path: String, handler: HttpHandler) {
            s.createContext(path, handler).getFilters().add(tokenFilter)
        }
        // 所有 handler 用 safe 包裹：异常返回 500 而非挂起连接
        context("/ping", routes::handlePing)
        context("/debug", safe(routes::handleDebug))
        context("/semantics", safe(routes::handleSemantics))
        context("/window-state", safe(routes::handleWindowState))
        context("/window-activate", safe(routes::handleWindowActivate))
        context("/window-fullscreen", safe(routes::handleWindowFullscreen))
        context("/click", safe(routes::handleClick))
        context("/doubleclick", safe(routes::handleDoubleClick))
        context("/longclick", safe(routes::handleLongClick))
        context("/rightclick", safe(routes::handleRightClick))
        context("/scroll", safe(routes::handleScroll))
        context("/input", safe(routes::handleInput))
        context("/set-progress", safe(routes::handleSetProgress))
        context("/screenshot", safe(routes::handleScreenshot))
        context("/find", safe(routes::handleFind))
        context("/wait", safe(routes::handleWait))
        context("/keypress", safe(routes::handleKeypress))
        val executor = createTestHttpExecutor()
        try {
            s.executor = executor
            s.start()
            server = s
            serverExecutor = executor
        } catch (failure: Throwable) {
            runCatching { s.stop(0) }.onFailure(failure::addSuppressed)
            runCatching { shutdownTestHttpExecutor(executor) }.onFailure(failure::addSuppressed)
            throw failure
        }
        println("[TestHttpServer] listening on http://127.0.0.1:$port")
    }

    /** handler 错误兜底：任何异常返回 500 JSON，保证连接不挂起。 */
    private fun safe(handler: (HttpExchange) -> Unit): com.sun.net.httpserver.HttpHandler {
        return { exchange ->
            try {
                handler(exchange)
            } catch (e: TestHttpRequestTooLargeException) {
                try {
                    exchange.send(413, """{"error":"${e.message?.escape()}"}""")
                } catch (_: Exception) {
                    // 连接已关闭等，忽略
                }
            } catch (e: Exception) {
                try {
                    exchange.send(500, """{"error":"${e.message?.escape() ?: e.javaClass.simpleName}"}""")
                } catch (_: Exception) {
                    // 连接已关闭等，忽略
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        val stoppingServer = server
        val stoppingExecutor = serverExecutor
        server = null
        serverExecutor = null
        val stopFailure = runCatching { stoppingServer?.stop(0) }.exceptionOrNull()
        val executorFailure = runCatching {
            stoppingExecutor?.let(::shutdownTestHttpExecutor)
        }.exceptionOrNull()
        automation.clear()
        stopFailure?.let { failure ->
            executorFailure?.let(failure::addSuppressed)
            throw failure
        }
        executorFailure?.let { throw it }
    }
}
