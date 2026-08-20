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
    fun `authentication registers and lists devices`() = runTest {
        val username = uniqueUsername("device-list")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-1", "iPhone", "iPhone 15", 1)
        login(username, password, "dev-2", "Desktop", "MacBook", 2)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(2, devices.size)
        assertTrue(devices.any { it.deviceId == "dev-1" && it.deviceName == "iPhone" })
        assertTrue(devices.any { it.deviceId == "dev-2" && it.deviceName == "Desktop" })
    }

    @Test
    fun `authenticating same device updates info`() = runTest {
        val username = uniqueUsername("device-update")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-same", "Old Name", "Model A", 1)
        login(username, password, "dev-same", "New Name", "Model B", 1)

        val devices = ctx.deviceRepo.getDevices(uid)
        assertEquals(1, devices.size)
        assertEquals("New Name", devices[0].deviceName)
        assertEquals("Model B", devices[0].deviceModel)
    }

    @Test
    fun `revoking device removes it from active list`() = runTest {
        val username = uniqueUsername("device-revoke")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-kick", "Phone", "Pixel", 1)
        assertEquals(1, ctx.deviceRepo.getDevices(uid).size)

        assertTrue(ctx.authService.revokeDevice(uid, "dev-kick") != null)
        assertEquals(0, ctx.deviceRepo.getDevices(uid).size)
    }

    @Test
    fun `devices sorted by last login desc`() = runTest {
        val username = uniqueUsername("device-order")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        login(username, password, "dev-old", "Old", "Model", 1)
        Thread.sleep(10)
        login(username, password, "dev-new", "New", "Model", 1)

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

        ctx.authService.revokeDevice(uid, "android-install-1")
        assertTrue(ctx.deviceRepo.getDevices(uid).isEmpty())
    }

    @Test
    fun `refresh cannot relabel another device`() = runTest {
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

        assertEquals(listOf("trusted-device"), ctx.deviceRepo.getDevices(uid).map { it.deviceId })
    }

    private suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        deviceModel: String,
        deviceFlag: Int,
    ) = ctx.authService.handleAuth(
        AuthRequestPayload(
            authType = 0,
            username = username,
            password = password,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            deviceFlag = deviceFlag,
        ),
    ).also { response -> assertEquals(0, response.code) }
}
