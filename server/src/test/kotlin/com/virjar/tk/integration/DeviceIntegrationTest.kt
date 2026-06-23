package com.virjar.tk.integration

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
}
