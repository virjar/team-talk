package com.virjar.tk.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals

class SystemIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `health check returns response with components`() = runTest {
        val result = ctx.healthChecker.check()
        // 测试环境下 TCP 未启动，整体状态可能不是 UP，但核心组件应该正常
        assertEquals("UP", result.components["postgres"]?.status)
        assertEquals("UP", result.components["rocksdb"]?.status)
        assertEquals("UP", result.components["lucene"]?.status)
        assertEquals("UP", result.components["file-storage"]?.status)
    }
}
