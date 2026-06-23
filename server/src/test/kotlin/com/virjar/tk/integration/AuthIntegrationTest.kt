package com.virjar.tk.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `register with valid data`() = runTest {
        val user = ctx.userService.register(uniqueUsername("reg"), "password123", "TestUser")
        assertNotNull(user.uid)
        assertEquals("TestUser", user.name)
    }

    @Test
    fun `register rejects duplicate username`() = runTest {
        val username = uniqueUsername("dup")
        ctx.userService.register(username, "password123", "Test")
        try {
            ctx.userService.register(username, "password456", "Test2")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("已存在"))
        }
    }

    @Test
    fun `register rejects short password`() = runTest {
        try {
            ctx.userService.register(uniqueUsername("short"), "12345", "Test")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("6"))
        }
    }

    @Test
    fun `login with correct password`() = runTest {
        val username = uniqueUsername("login")
        ctx.userService.register(username, "password123", "LoginUser")
        val user = ctx.userService.login(username, "password123")
        assertEquals(username, user.username)
    }

    @Test
    fun `login rejects wrong password`() = runTest {
        val username = uniqueUsername("wrong")
        ctx.userService.register(username, "password123", "WrongUser")
        try {
            ctx.userService.login(username, "wrongpass")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("错误"))
        }
    }

    @Test
    fun `get profile`() = runTest {
        val uid = ctx.registerUser()
        val profile = ctx.userService.getProfile(uid)
        assertNotNull(profile)
        assertEquals(uid, profile.uid)
    }

    @Test
    fun `update profile`() = runTest {
        val uid = ctx.registerUser()
        ctx.userService.updateProfile(uid, name = "NewName", sex = 1)
        val updated = ctx.userService.getProfile(uid)
        assertEquals("NewName", updated.name)
        assertEquals(1, updated.sex)
    }

    @Test
    fun `search users`() = runTest {
        val username = uniqueUsername("searchable")
        ctx.userService.register(username, "password123", "SearchMe")
        val results = ctx.userService.search(username)
        assertTrue(results.any { it.username == username })
    }

    @Test
    fun `change password`() = runTest {
        val username = uniqueUsername("chpwd")
        ctx.userService.register(username, "oldpass123", "ChPwd")
        ctx.userService.changePassword(
            ctx.userService.login(username, "oldpass123").uid,
            "oldpass123", "newpass123"
        )
        // 验证新密码可以登录
        val user = ctx.userService.login(username, "newpass123")
        assertNotNull(user)
    }

    @Test
    fun `change password rejects wrong old password`() = runTest {
        val uid = ctx.registerUser()
        try {
            ctx.userService.changePassword(uid, "wrongold", "newpass123")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("旧密码错误"))
        }
    }
}
