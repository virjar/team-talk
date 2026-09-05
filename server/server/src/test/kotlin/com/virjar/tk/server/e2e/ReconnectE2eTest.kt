package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                    onAuthResult = { success, _, username, _, _, _, _, reason ->
                        if (success) { authCount++; lastUsername = username }
                        else authFailReason = reason
                    },
                )
                val eventProjection = imClient.installE2eEventProjection(env.syncDatasetId)
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
                eventProjection.close()
                imClient.destroy()
            }
        }
    }
}


/** AUTH_FAILED 终态：失效 token 认证失败后不再自动重连（F30 风暴根治）。 */
class AuthTerminalE2eTest {

    @Test
    fun `失效 token 认证失败后停止自动重连`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                var failCount = 0
                val client = ImClient(onAuthResult = { ok, _, _, _, _, _, _, _ -> if (!ok) failCount++ })
                client.authenticate("nonexistent-uid", "invalid-token", "dev-t", "Test", "127.0.0.1", env.tcpPort)
                withTimeout(5_000) { client.state.first { it == ConnectionState.AUTH_FAILED } }
                // 风暴期表现：断连→自动重连→再 AUTH_FAILED 循环；终态后应稳定无新失败
                delay(5_000)
                assertEquals(1, failCount, "认证失败应恰好一次（曾循环失败踢翻登录窗）")
                assertEquals(ConnectionState.AUTH_FAILED, client.state.value, "应保持终态")
                client.destroy()
            }
        }
    }
}
