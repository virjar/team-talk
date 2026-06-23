package com.virjar.tk.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/**
 * [AuthRules] 校验规则单测（纯逻辑，无基础设施依赖）。
 *
 * 验证客户端 SDK 在发送认证请求前能正确拦截非法参数，
 * 避免无效请求发到服务端被静默拒绝（用户拿不到失败原因）。
 */
class AuthRulesTest {

    // ── 用户名校验 ──

    @Test
    fun `username within range is valid`() {
        assertNull(AuthRules.validateUsername("abc"))           // 下界
        assertNull(AuthRules.validateUsername("a".repeat(50)))  // 上界
        assertNull(AuthRules.validateUsername("normal_user-1"))
    }

    @Test
    fun `username too short is rejected`() {
        assertEquals("用户名长度不能少于${AuthRules.USERNAME_MIN_LENGTH}位", AuthRules.validateUsername("ab"))
        assertEquals("用户名不能为空", AuthRules.validateUsername(""))
    }

    @Test
    fun `username too long is rejected`() {
        // 完整 UUID 拼前缀会超长（这是之前 demo 测试踩过的坑）
        val tooLong = "zcode-demo-" + "a".repeat(50)
        assertEquals("用户名长度不能超过${AuthRules.USERNAME_MAX_LENGTH}位", AuthRules.validateUsername(tooLong))
    }

    // ── 密码校验 ──

    @Test
    fun `password meets minimum length is valid`() {
        assertNull(AuthRules.validatePassword("123456"))        // 下界
        assertNull(AuthRules.validatePassword("a-very-long-password"))
    }

    @Test
    fun `password too short is rejected`() {
        assertEquals("密码长度不能少于${AuthRules.PASSWORD_MIN_LENGTH}位", AuthRules.validatePassword("12345"))
        assertEquals("密码不能为空", AuthRules.validatePassword(""))
    }

    // ── 组合校验（register / login 入口）──

    @Test
    fun `validateRegister throws on invalid username`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            AuthRules.validateRegister("a".repeat(51), "validpass")
        }
        assertEquals("用户名长度不能超过${AuthRules.USERNAME_MAX_LENGTH}位", ex.message)
    }

    @Test
    fun `validateRegister throws on invalid password`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            AuthRules.validateRegister("validuser", "123")
        }
        assertEquals("密码长度不能少于${AuthRules.PASSWORD_MIN_LENGTH}位", ex.message)
    }

    @Test
    fun `validateRegister passes with valid args`() {
        // 不抛异常即通过
        AuthRules.validateRegister("validuser", "validpass")
    }

    @Test
    fun `validateLogin throws on invalid args`() {
        assertFailsWith<IllegalArgumentException> { AuthRules.validateLogin("", "validpass") }
        assertFailsWith<IllegalArgumentException> { AuthRules.validateLogin("validuser", "") }
    }
}
