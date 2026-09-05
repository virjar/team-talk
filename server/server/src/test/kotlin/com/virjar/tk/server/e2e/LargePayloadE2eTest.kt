package com.virjar.tk.server.e2e

import com.virjar.tk.shared.bot.ImBot
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 大包帧限回归（真实 PG + embedded TcpServer）。
 *
 * 锁定 F23：客户端认证成功后必须放开 PacketCodec 的 4KB 未认证帧限——
 * 离线事件补发（sync_events 批量 NOTIFY，实测可达 100KB+）曾因未放开被
 * CorruptedFrameException 断连并形成重连风暴。
 *
 * 场景：B 断线期间 A 发多条消息 → 事件累积 → B 重连认证 → 显式分页同步超过 4KB。
 */
class LargePayloadE2eTest {

    @Test
    fun `离线事件补发大包不断连`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                // B 用裸 ImClient：需要控制断线/观测连接状态（uid 从认证回调捕获）
                var bUid: String? = null
                var bRefreshToken: String? = null
                val b = ImClient(onAuthResult = { ok, uid, _, _, refreshToken, _, _, _ ->
                    if (ok) {
                        bUid = uid
                        bRefreshToken = refreshToken
                    }
                })
                val bEvents = b.installE2eEventProjection(env.syncDatasetId)
                b.register("large-b-${System.nanoTime()}", "password123", "B", "dev-b", "Test", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { b.state.first { it == ConnectionState.AUTHENTICATED } }

                val a = ImBot.register("127.0.0.1", env.tcpPort, "large-a", TEST_IM_BOT_PASSWORD, testImBotCacheOwner)
                try {
                    val chatId = a.createPersonalChat(bUid ?: error("uid missing"))
                    // 主动保持离线直到 backlog 完整形成。如果用 1s 自动重连，性能较快
                    // 时会在发送循环中途恢复，测试实际只覆盖多个小 live NOTIFY。
                    b.disconnect()
                    withTimeout(5_000) { b.state.first { it == ConnectionState.DISCONNECTED } }
                    repeat(20) { a.sendText(chatId, "补发内容".repeat(30)) } // 20 × 480B 事件，批量包 > 4KB

                    b.authenticate(
                        uid = bUid ?: error("uid missing"),
                        token = bRefreshToken ?: error("refresh token missing"),
                        deviceId = "dev-b",
                        deviceName = "Test",
                        host = "127.0.0.1",
                        port = env.tcpPort,
                    )
                    // B 重连 + 认证 + 一次大批次同步。
                    withTimeout(20_000) { b.state.first { it == ConnectionState.AUTHENTICATED } }
                    // 修复前：补发大包触发 CorruptedFrameException → 再次断线循环；
                    // 稳定窗口内保持 AUTHENTICATED 即为修复生效
                    delay(4_000)
                    assertEquals(ConnectionState.AUTHENTICATED, b.state.value, "补发大包不应导致断连")
                } finally {
                    a.shutdown()
                    bEvents.close()
                    b.destroy()
                }
            }
        }
    }
}
