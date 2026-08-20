package com.virjar.tk.integration

import com.virjar.tk.protocol.payload.AuthRequestPayload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `register and list devices`() = runTest {
        val uid = ctx.registerUser()
        ctx.deviceRepo.registerDevice(uid, "dev-1", "iPhone", "iPhone 15", 1)
        ctx.deviceRepo.registerDevice(uid, "dev-2", "Desktop", "MacBook", 2)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(2, devices.size)
        assertTrue(devices.any { it.deviceId == "dev-1" && it.deviceName == "iPhone" })
        assertTrue(devices.any { it.deviceId == "dev-2" && it.deviceName == "Desktop" })
    }

    @Test
    fun `register same device updates info`() = runTest {
        val uid = ctx.registerUser()
        ctx.deviceRepo.registerDevice(uid, "dev-same", "Old Name", "Model A", 1)
        ctx.deviceRepo.registerDevice(uid, "dev-same", "New Name", "Model B", 1)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(1, devices.size)
        assertEquals("New Name", devices[0].deviceName)
        assertEquals("Model B", devices[0].deviceModel)
    }

    @Test
    fun `kick device removes it`() = runTest {
        val uid = ctx.registerUser()
        ctx.deviceRepo.registerDevice(uid, "dev-kick", "Phone", "Pixel", 1)
        assertEquals(1, ctx.deviceRepo.getDevices(uid).size)

        ctx.deviceRepo.kickDevice(uid, "dev-kick")
        assertEquals(0, ctx.deviceRepo.getDevices(uid).size)
    }

    @Test
    fun `devices sorted by last login desc`() = runTest {
        val uid = ctx.registerUser()
        ctx.deviceRepo.registerDevice(uid, "dev-old", "Old", "Model", 1)
        Thread.sleep(10)
        ctx.deviceRepo.registerDevice(uid, "dev-new", "New", "Model", 1)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(2, devices.size)
        assertEquals("dev-new", devices[0].deviceId)
    }

    @Test
    fun `successful authentication registers and refreshes the declared device`() = runTest {
        val username = uniqueUsername("device-auth")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)

        val login = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = "android-install-1",
                deviceName = "Xiaomi Phone",
                deviceModel = "2312DRA50C",
                deviceFlag = 1,
            ),
        )
        assertEquals(0, login.code)
        assertEquals(uid, login.uid)
        assertEquals(
            listOf("android-install-1"),
            ctx.deviceRepo.getDevices(uid).map { it.deviceId },
        )

        val refresh = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = login.refreshToken,
                deviceId = "android-install-1",
                deviceName = "Xiaomi Phone (renamed)",
                deviceModel = "2312DRA50C",
                deviceFlag = 1,
            ),
        )
        assertEquals(0, refresh.code)
        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(1, devices.size)
        assertEquals("Xiaomi Phone (renamed)", devices.single().deviceName)

        ctx.authService.logout(uid, refresh.refreshToken, "android-install-1")
        assertTrue(ctx.deviceRepo.getDevices(uid).isEmpty())
    }

    @Test
    fun `refresh and logout cannot relabel another device`() = runTest {
        val username = uniqueUsername("device-binding")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val login = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = "trusted-device",
                deviceName = "Trusted phone",
                deviceFlag = 1,
            ),
        )

        val forgedRefresh = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = login.refreshToken,
                deviceId = "forged-device",
                deviceName = "Forged phone",
                deviceFlag = 1,
            ),
        )
        assertEquals(1, forgedRefresh.code)
        assertEquals(listOf("trusted-device"), ctx.deviceRepo.getDevices(uid).map { it.deviceId })

        // A mismatched attempt must not consume the one-time refresh token of the real device.
        val validRefresh = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = login.refreshToken,
                deviceId = "trusted-device",
                deviceName = "Trusted phone",
                deviceFlag = 1,
            ),
        )
        assertEquals(0, validRefresh.code)

        // A forged logout target cannot revoke or remove the token's real device.
        ctx.authService.logout(uid, validRefresh.refreshToken, "forged-device")
        assertEquals(listOf("trusted-device"), ctx.deviceRepo.getDevices(uid).map { it.deviceId })
        val stillValid = ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = validRefresh.refreshToken,
                deviceId = "trusted-device",
                deviceName = "Trusted phone",
                deviceFlag = 1,
            ),
        )
        assertEquals(0, stillValid.code)
    }
}
