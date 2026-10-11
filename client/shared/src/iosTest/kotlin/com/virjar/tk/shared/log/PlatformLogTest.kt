package com.virjar.tk.shared.log

import kotlin.test.Test

class PlatformLogTest {
    @Test
    fun nativeLoggerBridgesStringsWithUnicodePercentSignsAndExceptions() {
        val logger = PlatformOnlyTkLogger("原生日志 %@")
        // 走真实 Foundation 边界：非法的 %@ 参数会使测试进程崩溃。
        logger.trace("中文消息 📄，完成 100%，字面格式 %s %@ %d %%")
        logger.fault(
            "刷新失败，保留进度 50%，字面格式 %@ %s",
            IllegalStateException("中文异常 100% %@", IllegalArgumentException("底层异常 %s")),
        )
    }
}
