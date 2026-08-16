package com.virjar.tk.e2e

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * SDK 断线重连回归（真实 PG + embedded TcpServer）。
 * 锁定：重连认证升级为 refresh-token（历史 bug：重放 register 撞"用户名已存在"永久掉线）。
 */
class ReconnectE2eTest {

    @Test
    fun `网络断开后自动重连并重新认证`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                var authFailReason: String? = null
                var authCount = 0
                var lastUsername: String? = null
                val imClient = ImClient(
                    onAuthResult = { success, _, username, _, _, _, reason ->
                        if (success) { authCount++; lastUsername = username }
                        else authFailReason = reason
                    },
                )
                val username = "recon-${System.nanoTime()}"
                imClient.register(username, "password123", "R", "dev-1", "Test", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { imClient.state.first { it == ConnectionState.AUTHENTICATED } }

                imClient.simulateNetworkDrop()
                withTimeout(5_000) { imClient.state.first { it == ConnectionState.DISCONNECTED } }

                // 重连退避 1s 起 + 握手 + refresh 认证
                withTimeout(20_000) { imClient.state.first { it == ConnectionState.AUTHENTICATED } }
                assertEquals(null, authFailReason, "重连认证不应失败: $authFailReason")
                // 第二次认证是 refresh 路径：响应必须携带 username/name（曾漏发，
                // 客户端自动登录后 UserSession 身份为空，头像/昵称退化为 uid）
                assertEquals(2, authCount, "应恰好认证两次（注册 + refresh 重连）")
                assertEquals(username, lastUsername, "refresh 认证响应必须携带 username")
                imClient.destroy()
            }
        }
    }
}
