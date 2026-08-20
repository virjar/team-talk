package com.virjar.tk.domain.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthDeviceIdPolicyTest {

    @Test
    fun `device ids are stable path-safe installation identifiers`() {
        assertTrue(isValidDeviceId("android-550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(isValidDeviceId("desktop_install.1"))
        assertFalse(isValidDeviceId(""))
        assertFalse(isValidDeviceId(".."))
        assertFalse(isValidDeviceId("../../logs"))
        assertFalse(isValidDeviceId("device/child"))
        assertFalse(isValidDeviceId("device id"))
        assertFalse(isValidDeviceId("x".repeat(101)))
    }
}
