package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.ProfilePatchValue
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val user = ctx.registerHuman(uniqueUsername("reg"), "password123", "TestUser")
        assertNotNull(user.uid)
        assertEquals("TestUser", user.name)
    }

    @Test
    fun `network registration requires a bounded display name before creating the user`() = runTest {
        listOf("   ", "n".repeat(AuthRules.DISPLAY_NAME_MAX_LENGTH + 1)).forEach { displayName ->
            val username = uniqueUsername("invalid-register-name")
            val response = ctx.authService.handleAuth(
                AuthRequestPayload(
                    authType = 1,
                    username = username,
                    password = "password123",
                    name = displayName,
                    deviceId = "registration-device",
                    deviceName = "Integration device",
                    correlationId = "auth-correlation-$username",
                    connectionGeneration = 1L,
                ),
            )

            assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, response.code)
            assertNull(ctx.userRepo.findByUsername(username))
        }
    }

    @Test
    fun `network registration validates every device field before creating the user`() = runTest {
        val invalidDevices = listOf(
            AuthRequestPayload(
                authType = 1,
                deviceId = "../device",
                deviceName = "Integration device",
                correlationId = "auth-invalid-device-0001",
                connectionGeneration = 1L,
            ),
            AuthRequestPayload(
                authType = 1,
                deviceId = "registration-device",
                deviceName = "d".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH + 1),
                correlationId = "auth-invalid-device-0002",
                connectionGeneration = 2L,
            ),
            AuthRequestPayload(
                authType = 1,
                deviceId = "registration-device",
                deviceModel = "m".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH + 1),
                correlationId = "auth-invalid-device-0003",
                connectionGeneration = 3L,
            ),
            AuthRequestPayload(
                authType = 1,
                deviceId = "registration-device",
                deviceFlag = AuthRules.DEVICE_FLAG_DESKTOP + 1,
                correlationId = "auth-invalid-device-0004",
                connectionGeneration = 4L,
            ),
        )

        invalidDevices.forEach { invalidDevice ->
            val username = uniqueUsername("invalid-register-device")
            val response = ctx.authService.handleAuth(
                invalidDevice.copy(
                    username = username,
                    password = "password123",
                    name = "Valid User",
                ),
            )

            assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, response.code)
            assertNull(ctx.userRepo.findByUsername(username))
        }
    }

    @Test
    fun `network registration rejects nul device metadata without a half registered user`() = runTest {
        val invalidDevices = listOf(
            AuthRequestPayload(
                authType = 1,
                deviceId = "registration-device",
                deviceName = "Office\u0000Desktop",
                correlationId = "auth-nul-device-0001",
                connectionGeneration = 1L,
            ),
            AuthRequestPayload(
                authType = 1,
                deviceId = "registration-device",
                deviceModel = "Model\u0000One",
                correlationId = "auth-nul-device-0002",
                connectionGeneration = 2L,
            ),
        )

        invalidDevices.forEach { invalidDevice ->
            val username = uniqueUsername("nul-register-device")
            val response = ctx.authService.handleAuth(
                invalidDevice.copy(
                    username = username,
                    password = "password123",
                    name = "Valid User",
                ),
            )

            assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, response.code)
            assertTrue(response.reason?.contains("不支持的字符") == true)
            assertNull(
                ctx.userRepo.findByUsername(username),
                "device metadata validation must run before registration transaction admission",
            )
        }
    }

    @Test
    fun `network authentication rejects nul identity fields before postgres`() = runTest {
        val invalidUsername = uniqueUsername("nul-username") + "\u0000suffix"
        val usernameResponse = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 1,
                username = invalidUsername,
                password = "password123",
                name = "Valid User",
                deviceId = "nul-identity-device",
                correlationId = "auth-nul-identity-0001",
                connectionGeneration = 1L,
            ),
        )
        assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, usernameResponse.code)
        assertTrue(usernameResponse.reason?.contains("不支持的字符") == true)

        val validUsername = uniqueUsername("nul-display-name")
        val displayNameResponse = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 1,
                username = validUsername,
                password = "password123",
                name = "Alice\u0000Office",
                deviceId = "nul-identity-device",
                correlationId = "auth-nul-identity-0002",
                connectionGeneration = 2L,
            ),
        )
        assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, displayNameResponse.code)
        assertTrue(displayNameResponse.reason?.contains("不支持的字符") == true)
        assertNull(ctx.userRepo.findByUsername(validUsername))

        val loginResponse = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = invalidUsername,
                password = "password123",
                deviceId = "nul-identity-device",
                correlationId = "auth-nul-identity-0003",
                connectionGeneration = 3L,
            ),
        )
        assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, loginResponse.code)
        assertTrue(loginResponse.reason?.contains("不支持的字符") == true)
    }

    @Test
    fun `network registration accepts exact display and device metadata boundaries`() = runTest {
        val username = uniqueUsername("register-boundary")
        val response = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 1,
                username = username,
                password = "password123",
                name = "n".repeat(AuthRules.DISPLAY_NAME_MAX_LENGTH),
                deviceId = "registration-boundary-device",
                deviceName = "d".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH),
                deviceModel = "m".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH),
                deviceFlag = AuthRules.DEVICE_FLAG_DESKTOP,
                correlationId = "auth-register-boundary-0001",
                connectionGeneration = 1L,
            ),
        )

        assertEquals(AuthResponsePayload.CODE_OK, response.code)
        assertNotNull(ctx.userRepo.findByUsername(username))
    }

    @Test
    fun `register rejects duplicate username`() = runTest {
        val username = uniqueUsername("dup")
        ctx.registerHuman(username, "password123", "Test")
        try {
            ctx.registerHuman(username, "password456", "Test2")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("已存在"))
        }
    }

    @Test
    fun `register rejects short password`() = runTest {
        try {
            ctx.registerHuman(uniqueUsername("short"), "12345", "Test")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("6"))
        }
    }

    @Test
    fun `login with correct password`() = runTest {
        val username = uniqueUsername("login")
        ctx.registerHuman(username, "password123", "LoginUser")
        val user = ctx.userService.login(username, "password123")
        assertEquals(username, user.username)
    }

    @Test
    fun `login rejects wrong password`() = runTest {
        val username = uniqueUsername("wrong")
        ctx.registerHuman(username, "password123", "WrongUser")
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
        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                name = ProfilePatchValue.Set("NewName"),
                sex = ProfilePatchValue.Set(1),
            ),
        )
        val updated = ctx.userService.getProfile(uid)
        assertEquals("NewName", updated.name)
        assertEquals(1, updated.sex)
    }

    @Test
    fun `profile updates are visible through all user indexes and release the old phone`() = runTest {
        val username = uniqueUsername("fresh-user")
        val oldPhone = "13${System.nanoTime().toString().takeLast(9).padStart(9, '0')}"
        val newPhone = "15${System.nanoTime().toString().takeLast(9).padStart(9, '0')}"
        val user = ctx.registerHuman(username, "password123", "Before", oldPhone)

        // 同一资料可按 UID、用户名和手机号查到；变更后三个入口都应反映新值。
        assertEquals(user.uid, ctx.userRepo.findByUid(user.uid)?.uid)
        assertEquals(user.uid, ctx.userRepo.findByUsername(username)?.uid)
        assertEquals(user.uid, ctx.userRepo.findByPhone(oldPhone)?.uid)

        ctx.pgUnitOfWork.write {
            ctx.userRepo.updateProfile(
                transaction,
                user.uid,
                ProfilePatch(
                    name = ProfilePatchValue.Set("After"),
                    phone = ProfilePatchValue.Set(newPhone),
                ),
            )
        }

        assertEquals("After", ctx.userRepo.findByUid(user.uid)?.name)
        assertEquals(newPhone, ctx.userRepo.findByUsername(username)?.phone)
        assertEquals(user.uid, ctx.userRepo.findByPhone(newPhone)?.uid)
        assertEquals(null, ctx.userRepo.findByPhone(oldPhone))
        val reused = ctx.registerHuman(uniqueUsername("reused-phone"), "password123", "Reused", oldPhone)
        assertEquals(oldPhone, reused.phone)
    }

    @Test
    fun `search users`() = runTest {
        val username = uniqueUsername("searchable")
        ctx.registerHuman(username, "password123", "SearchMe")
        val results = ctx.userService.search(username)
        assertTrue(results.any { it.username == username })
    }

    @Test
    fun `search treats SQL wildcard characters literally and rejects wildcard-only enumeration`() = runTest {
        val percent = ctx.registerHuman(
            uniqueUsername("search-percent"),
            "password123",
            "Literal%Needle",
        )
        val underscore = ctx.registerHuman(
            uniqueUsername("search-underscore"),
            "password123",
            "Literal_Needle",
        )
        ctx.registerHuman(uniqueUsername("search-control"), "password123", "LiteralXNeedle")

        assertEquals(setOf(percent.uid), ctx.userService.search("%Needle").map { it.uid }.toSet())
        assertEquals(setOf(underscore.uid), ctx.userService.search("_Needle").map { it.uid }.toSet())
        listOf("%%%", "%a%", "_a_", "__").forEach { keyword ->
            assertFailsWith<IllegalArgumentException> { ctx.userService.search(keyword) }
        }
        assertFailsWith<IllegalArgumentException> { ctx.userService.search("a".repeat(101)) }
        assertFailsWith<IllegalArgumentException> { ctx.userService.search("literal", limit = 21) }
    }

    @Test
    fun `change password`() = runTest {
        val username = uniqueUsername("chpwd")
        val oldPassword = "oldpass123"
        val newPassword = "newpass123"
        val uid = ctx.registerHuman(username, oldPassword, "ChPwd").uid
        val before = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = oldPassword,
                deviceId = "password-device",
                correlationId = "auth-password-change-0001",
                connectionGeneration = 1L,
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
                correlationId = "auth-password-change-0002",
                connectionGeneration = 2L,
            ),
        ).code)
        assertEquals(1, ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = oldPassword,
                deviceId = "password-device",
                correlationId = "auth-password-change-0003",
                connectionGeneration = 3L,
            ),
        ).code)
        assertEquals(0, ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = newPassword,
                deviceId = "password-device",
                correlationId = "auth-password-change-0004",
                connectionGeneration = 4L,
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
