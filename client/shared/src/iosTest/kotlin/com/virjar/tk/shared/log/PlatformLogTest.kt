package com.virjar.tk.shared.log

import kotlin.test.Test

class PlatformLogTest {
    @Test
    fun nativeLoggerBridgesStringsWithUnicodePercentSignsAndExceptions() {
        val logger = PlatformOnlyTkLogger("原生日志 %@")
        // Exercise the real Foundation boundary: an invalid %@ argument crashes the test process.
        logger.trace("中文消息 📄，完成 100%，字面格式 %s %@ %d %%")
        logger.fault(
            "刷新失败，保留进度 50%，字面格式 %@ %s",
            IllegalStateException("中文异常 100% %@", IllegalArgumentException("底层异常 %s")),
        )
    }
}
