package com.virjar.tk.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 管理后台鉴权器单元测试（HTTP 路由层由远程业务验收覆盖）。
 */
class AdminApiTest {

    private fun auth(
        user: String = "admin",
        pass: String = "test-only-password",
        clock: () -> Long = { 1_000L },
        maxActiveTokens: Int = AdminAuthConfig.DEFAULT_MAX_ACTIVE_TOKENS,
    ) = AdminAuthConfig(user, pass, clock, maxActiveTokens)

    @Test
    fun `正确凭据换 token，错误凭据 null`() {
        val a = auth()
        val token = a.login("admin", "test-only-password")
        assertTrue(!token.isNullOrBlank(), "正确凭据应返回 token")
        assertNull(a.login("admin", "wrong"), "错误密码拒绝")
        assertNull(a.login("nobody", "test-only-password"), "未知用户拒绝")
        assertNull(AdminAuthConfig(username = null, password = null).login("admin", "admin"))
    }

    @Test
    fun `token 校验与拒绝`() {
        val a = auth()
        val token = a.login("admin", "test-only-password")!!
        assertTrue(a.validate(token))
        assertTrue(!a.validate(null))
        assertTrue(!a.validate(""))
        assertTrue(!a.validate("Bearer $token"), "带前缀的原始值不是 token")
    }

    @Test
    fun `token 随机不重复`() {
        val a = auth()
        val t1 = a.login("admin", "test-only-password")!!
        val t2 = a.login("admin", "test-only-password")!!
        assertNotEquals(t1, t2)
        assertEquals(AdminAuthConfig.TOKEN_TTL_MS, 12 * 3600 * 1000L, "12h 过期")
    }

    @Test
    fun `token 有上限且过期后回收`() {
        var now = 10_000L
        val a = auth(clock = { now }, maxActiveTokens = 2)
        val first = a.login("admin", "test-only-password")!!
        val second = a.login("admin", "test-only-password")!!
        val third = a.login("admin", "test-only-password")!!

        assertEquals(2, a.activeTokenCount())
        assertTrue(!a.validate(first), "超过上限时最早 token 被撤销")
        assertTrue(a.validate(second))
        assertTrue(a.validate(third))

        now += AdminAuthConfig.TOKEN_TTL_MS
        assertTrue(!a.validate(second))
        a.login("admin", "test-only-password")
        assertEquals(1, a.activeTokenCount(), "下一次登录清理其他过期 token")
    }
}
