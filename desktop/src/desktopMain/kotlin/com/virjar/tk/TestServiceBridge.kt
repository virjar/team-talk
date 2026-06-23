package com.virjar.tk

import androidx.compose.ui.awt.ComposeWindow

/**
 * 测试 HTTP 服务的隔离入口。
 *
 * 用反射调用 TestHttpServer，避免 LoginWindow 编译期硬依赖 TestHttpServer 类。
 * 这样打包时可以从 jar 物理删除 test 包（com.virjar.tk.test.*），LoginWindow
 * 不会 NoClassDefFoundError——反射找不到类时静默跳过。
 *
 * 调用方用 [startIfEnabled] / [registerWindowIfEnabled]，不直接引用 TestHttpServer。
 */
object TestServiceBridge {

    private val testHttpClass: Class<*>? by lazy {
        try {
            Class.forName("com.virjar.tk.test.TestHttpServer")
        } catch (_: ClassNotFoundException) {
            null  // 打包产物已删除 test 包
        }
    }

    /** 启动测试 HTTP 服务（若存在且启用）。 */
    fun startIfEnabled() {
        if (!BuildConfig.TEST_HTTP_SERVER) return
        try {
            testHttpClass?.getMethod("startDefault")?.invoke(null)
        } catch (e: Exception) {
            println("[TestServiceBridge] start failed: ${e.message}")
        }
    }

    /** 注册窗口（若服务存在）。 */
    fun registerWindowIfEnabled(window: ComposeWindow) {
        if (!BuildConfig.TEST_HTTP_SERVER) return
        try {
            testHttpClass?.getMethod("registerWindow", ComposeWindow::class.java)
                ?.invoke(null, window)
        } catch (e: Exception) {
            println("[TestServiceBridge] registerWindow failed: ${e.message}")
        }
    }

    /** 注册子窗口（指定 id，供多窗口测试驱动）。 */
    fun registerWindowWithId(id: String, window: ComposeWindow) {
        if (!BuildConfig.TEST_HTTP_SERVER) return
        try {
            testHttpClass?.getMethod("registerWindow", String::class.java, ComposeWindow::class.java)
                ?.invoke(null, id, window)
        } catch (e: Exception) {
            println("[TestServiceBridge] registerWindowWithId failed: ${e.message}")
        }
    }

    /** 注销子窗口（关闭时调用，避免内存泄漏）。 */
    fun unregisterWindow(id: String) {
        if (!BuildConfig.TEST_HTTP_SERVER) return
        try {
            testHttpClass?.getMethod("unregisterWindow", String::class.java)
                ?.invoke(null, id)
        } catch (e: Exception) {
            println("[TestServiceBridge] unregisterWindow failed: ${e.message}")
        }
    }
}
