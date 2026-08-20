package com.virjar.tk.integration

import com.virjar.tk.protocol.payload.AuthRequestPayload
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
    fun `user facade always reflects repository updates and releases old phone`() = runTest {
        val username = uniqueUsername("fresh-user")
        val oldPhone = "13${System.nanoTime().toString().takeLast(9).padStart(9, '0')}"
        val newPhone = "15${System.nanoTime().toString().takeLast(9).padStart(9, '0')}"
        val user = ctx.userService.register(username, "password123", "Before", oldPhone)

        // Exercise all former cache indexes before changing the authoritative row directly.
        assertEquals(user.uid, ctx.userStore.findByUid(user.uid)?.uid)
        assertEquals(user.uid, ctx.userStore.findByUsername(username)?.uid)
        assertEquals(user.uid, ctx.userStore.findByPhone(oldPhone)?.uid)

        ctx.userRepo.updateProfile(user.uid, name = "After", phone = newPhone)

        assertEquals("After", ctx.userStore.findByUid(user.uid)?.name)
        assertEquals(newPhone, ctx.userStore.findByUsername(username)?.phone)
        assertEquals(user.uid, ctx.userStore.findByPhone(newPhone)?.uid)
        assertEquals(null, ctx.userStore.findByPhone(oldPhone))
        val reused = ctx.userService.register(uniqueUsername("reused-phone"), "password123", "Reused", oldPhone)
        assertEquals(oldPhone, reused.phone)
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
        val oldPassword = "oldpass123"
        val newPassword = "newpass123"
        val uid = ctx.userService.register(username, oldPassword, "ChPwd").uid
        val before = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = oldPassword,
                deviceId = "password-device",
            ),
        )
        assertEquals(0, before.code)

        ctx.authService.changePassword(uid, oldPassword, newPassword)

        assertEquals(null, ctx.accessTokenValidator.validateAccessToken(requireNotNull(before.accessToken)))
        assertEquals(1, ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = before.refreshToken,
                deviceId = "password-device",
            ),
        ).code)
        assertEquals(1, ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = oldPassword,
                deviceId = "password-device",
            ),
        ).code)
        assertEquals(0, ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = newPassword,
                deviceId = "password-device",
            ),
        ).code)
    }

    @Test
    fun `change password rejects wrong old password`() = runTest {
        val uid = ctx.registerUser()
        try {
            ctx.authService.changePassword(uid, "wrongold", "newpass123")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("旧密码错误"))
        }
    }
}
