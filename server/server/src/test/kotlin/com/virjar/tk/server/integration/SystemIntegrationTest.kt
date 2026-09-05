package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.PostgresHealthProbePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SystemIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `health check returns response with components`() = runTest {
        ctx.syncEventDispatcher.start()
        withContext(Dispatchers.IO) {
            withTimeout(5_000L) { ctx.syncEventDispatcher.awaitStartupScan() }
        }
        val result = ctx.healthChecker.check()
        // 测试环境下 TCP 未启动，整体状态可能不是 UP，但核心组件应该正常
        assertEquals(
            setOf(
                "postgres",
                "rocksdb",
                "lucene",
                "message-projection",
                "managed-chat-projection",
                "sync-event-dispatcher",
                "client-telemetry",
                "file-storage",
                "tcp",
            ),
            result.components.keys,
        )
        assertEquals("UP", result.components["postgres"]?.status)
        assertEquals("UP", result.components["rocksdb"]?.status)
        assertEquals("UP", result.components["lucene"]?.status)
        assertEquals("UP", result.components["message-projection"]?.status)
        assertEquals("UP", result.components["sync-event-dispatcher"]?.status)
        assertEquals("UP", result.components["client-telemetry"]?.status)
        assertEquals("UP", result.components["file-storage"]?.status)
    }

    @Test
    fun `postgres health statement timeout cancels stalled query and releases connection`() = runTest {
        assertEquals("UP", ctx.healthChecker.check().components["postgres"]?.status)
        val elapsed = measureTimeMillis {
            assertFails {
                withContext(Dispatchers.IO) {
                    transaction(ctx.database) {
                        PostgresHealthProbePolicy.run(this) {
                            exec("SELECT pg_sleep(5)")
                        }
                    }
                }
            }
        }

        assertTrue(elapsed < 4_500L, "health query must fail before the five-second server delay")
        assertEquals("UP", ctx.healthChecker.check().components["postgres"]?.status)
    }
}
