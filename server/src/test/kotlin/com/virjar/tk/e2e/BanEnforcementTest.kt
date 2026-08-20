package com.virjar.tk.e2e

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 封禁 enforcement（管理后台的硬前提）：
 * ban 后 login 拒（理由"账号已被封禁"）→ unban 恢复。
 */
class BanEnforcementTest {

    @Test
    fun `封禁后登录被拒且理由正确，解封恢复`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                // 注册并等认证成功
                var failReason: String? = null
                val c1 = ImClient(onAuthResult = { ok, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                val c1Events = c1.installE2eEventProjection()
                val username = "ban-${System.nanoTime()}"
                c1.register(username, "password123", "B", "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c1.state.first { it == ConnectionState.AUTHENTICATED } }
                val uid = env.uidOf(username)

                // 直接置库封禁（等价 admin ban 的 status 写入）
                env.jdbcExec("UPDATE users SET status = 2 WHERE uid = '$uid'")
                c1.disconnect()
                c1Events.close()
                c1.destroy()

                // 重新登录：被拒
                val c2 = ImClient(onAuthResult = { ok, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                val c2Events = c2.installE2eEventProjection()
                c2.login(username, "password123", "d2", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c2.state.first { it == ConnectionState.AUTH_FAILED } }
                assertEquals("账号已被封禁", failReason)
                c2Events.close()
                c2.destroy()

                // 解封恢复
                env.jdbcExec("UPDATE users SET status = 1 WHERE uid = '$uid'")
                val c3 = ImClient(onAuthResult = { ok, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                val c3Events = c3.installE2eEventProjection()
                c3.login(username, "password123", "d3", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c3.state.first { it == ConnectionState.AUTHENTICATED } }
                c3Events.close()
                c3.destroy()
            }
        }
    }
}
