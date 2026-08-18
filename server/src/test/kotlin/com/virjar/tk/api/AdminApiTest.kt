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

    private fun auth(user: String = "admin", pass: String = "admin-change-me") =
        AdminAuthConfig().also { /* env 不可注入，默认值即测试值 */ }

    @Test
    fun `正确凭据换 token，错误凭据 null`() {
        val a = auth()
        val token = a.login("admin", "admin-change-me")
        assertTrue(!token.isNullOrBlank(), "正确凭据应返回 token")
        assertNull(a.login("admin", "wrong"), "错误密码拒绝")
        assertNull(a.login("nobody", "admin-change-me"), "未知用户拒绝")
    }

    @Test
    fun `token 校验与拒绝`() {
        val a = auth()
        val token = a.login("admin", "admin-change-me")!!
        assertTrue(a.validate(token))
        assertTrue(!a.validate(null))
        assertTrue(!a.validate(""))
        assertTrue(!a.validate("Bearer $token"), "带前缀的原始值不是 token")
    }

    @Test
    fun `token 随机不重复`() {
        val a = auth()
        val t1 = a.login("admin", "admin-change-me")!!
        val t2 = a.login("admin", "admin-change-me")!!
        assertNotEquals(t1, t2)
        assertEquals(AdminAuthConfig.TOKEN_TTL_MS, 12 * 3600 * 1000L, "12h 过期")
    }
}
