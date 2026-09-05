package com.virjar.tk.server.e2e

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.bot.ImBot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用户搜索防枚举：短关键词必须被服务端拒绝（26 字母 + 数千常用汉字可穷举
 * 召回全部注册用户），纯汉字 ≥2、含字母/数字 ≥3 才进入搜索。
 */
class UserSearchEnumerationGuardTest {

    @Test
    fun `单字母双字母单汉字被拒绝`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val a = ImBot.register(
                    "127.0.0.1", env.tcpPort, "enumguard-a",
                    password = TEST_IM_BOT_PASSWORD,
                    cacheOwner = testImBotCacheOwner,
                )
                try {
                    for (kw in listOf("a", "ab", "1", "张", "微")) {
                        val failure = runCatching { a.searchUsers(kw) }.exceptionOrNull()
                        assertTrue(failure != null, "关键词 '$kw'（${kw.length} 字符）必须被服务端拒绝")
                        assertTrue(
                            failure.message?.contains("太短") == true,
                            "拒绝理由应指向长度规则，实际: ${failure.message}",
                        )
                    }
                } finally { a.shutdown() }
            }
        }
    }

    @Test
    fun `三字母数字与两汉字正常进入搜索`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register(
                    "127.0.0.1", env.tcpPort, "enumguard-b",
                    password = TEST_IM_BOT_PASSWORD,
                    cacheOwner = testImBotCacheOwner,
                )
                try {
                    // 注册一个目标用户后用 ≥3 前缀召回（0 结果也不是拒绝）
                    val target = ImBot.register(
                        "127.0.0.1", env.tcpPort, "enumguard-target",
                        password = TEST_IM_BOT_PASSWORD,
                        cacheOwner = testImBotCacheOwner,
                    )
                    target.shutdown()
                    val alpha = b.searchUsers("enumguard")
                    assertTrue(alpha.isNotEmpty() && alpha.any { it.username.startsWith("enumguard-target") }, "3+ 字母数字应放行并召回目标: $alpha")
                    b.searchUsers("张三") // 2 个纯汉字放行（结果多少不限，不抛即通过）
                } finally { b.shutdown() }
            }
        }
    }
}
