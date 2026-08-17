package com.virjar.tk.e2e

import com.virjar.tk.bot.ImBot
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
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
 * 场景：B 断线期间 A 发多条消息 → 事件累积 → B 重连认证 → 服务端单包补发超 4KB。
 */
class LargePayloadE2eTest {

    @Test
    fun `离线事件补发大包不断连`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                // B 用裸 ImClient：需要控制断线/观测连接状态（uid 从认证回调捕获）
                var bUid: String? = null
                val b = ImClient(onAuthResult = { ok, uid, _, _, _, _, _ -> if (ok) bUid = uid })
                b.register("large-b-${System.nanoTime()}", "password123", "B", "dev-b", "Test", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { b.state.first { it == ConnectionState.AUTHENTICATED } }

                val a = ImBot.register("127.0.0.1", env.tcpPort, "large-a")
                try {
                    val chatId = a.createPersonalChat(bUid ?: error("uid missing"))
                    // 断线 → 累积事件 → 自动重连
                    b.simulateNetworkDrop()
                    delay(500)
                    repeat(20) { a.sendText(chatId, "补发内容".repeat(30)) } // 20 × 480B 事件，批量包 > 4KB

                    // B 自动重连（退避 1s 起）+ 认证 + 大包补发
                    withTimeout(20_000) { b.state.first { it == ConnectionState.AUTHENTICATED } }
                    // 修复前：补发大包触发 CorruptedFrameException → 再次断线循环；
                    // 稳定窗口内保持 AUTHENTICATED 即为修复生效
                    delay(4_000)
                    assertEquals(ConnectionState.AUTHENTICATED, b.state.value, "补发大包不应导致断连")
                } finally {
                    a.shutdown()
                    b.destroy()
                }
            }
        }
    }
}

