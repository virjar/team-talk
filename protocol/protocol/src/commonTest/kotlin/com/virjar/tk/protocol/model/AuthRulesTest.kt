package com.virjar.tk.protocol.model

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
        // 完整 UUID 拼较长前缀会超过服务端限制
        val tooLong = "integration-test-" + "a".repeat(50)
        assertEquals("用户名长度不能超过${AuthRules.USERNAME_MAX_LENGTH}位", AuthRules.validateUsername(tooLong))
    }

    @Test
    fun `database backed identity fields reject postgres nul`() {
        assertEquals("用户名包含不支持的字符", AuthRules.validateUsername("abc\u0000def"))
        assertEquals("显示名包含不支持的字符", AuthRules.validateDisplayName("Alice\u0000Office"))
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateRegister("abc\u0000def", "validpass", "Valid User")
        }
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateRegister("validuser", "validpass", "Alice\u0000Office")
        }
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateLogin("abc\u0000def", "validpass")
        }
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

    @Test
    fun `password beyond bcrypt utf8 boundary is rejected`() {
        assertEquals(
            "密码 UTF-8 编码后不能超过${AuthRules.PASSWORD_MAX_UTF8_BYTES}字节",
            AuthRules.validatePassword("密".repeat(25)),
        )
        assertNull(AuthRules.validatePassword("a".repeat(AuthRules.PASSWORD_MAX_UTF8_BYTES)))
    }

    @Test
    fun `display name is required and bounded`() {
        assertEquals("显示名不能为空", AuthRules.validateDisplayName("   "))
        assertNull(AuthRules.validateDisplayName("a".repeat(AuthRules.DISPLAY_NAME_MAX_LENGTH)))
        assertEquals(
            "显示名不能超过${AuthRules.DISPLAY_NAME_MAX_LENGTH}个字符",
            AuthRules.validateDisplayName("a".repeat(AuthRules.DISPLAY_NAME_MAX_LENGTH + 1)),
        )
    }

    @Test
    fun `device declaration uses path safe id bounded metadata and known flag`() {
        assertNull(AuthRules.validateDeviceId("android-550e8400-e29b-41d4-a716-446655440000"))
        assertNull(AuthRules.validateDeviceId("desktop_install.1"))
        assertNull(AuthRules.validateDeviceId("x".repeat(AuthRules.DEVICE_ID_MAX_LENGTH)))
        assertNull(AuthRules.validateDeviceName(null))
        assertNull(AuthRules.validateDeviceName(""))
        listOf("", "..", "../../logs", "device/child", "device id", "设备一").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                AuthRules.validateDevice(invalid, null, null, AuthRules.DEVICE_FLAG_UNKNOWN)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateDevice(
                "x".repeat(AuthRules.DEVICE_ID_MAX_LENGTH + 1),
                null,
                null,
                AuthRules.DEVICE_FLAG_UNKNOWN,
            )
        }
        AuthRules.validateDevice(
            "device-1",
            "n".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH),
            "m".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH),
            AuthRules.DEVICE_FLAG_DESKTOP,
        )
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateDevice(
                "device-1",
                "n".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH + 1),
                null,
                AuthRules.DEVICE_FLAG_DESKTOP,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateDevice(
                "device-1",
                null,
                "m".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH + 1),
                AuthRules.DEVICE_FLAG_DESKTOP,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateDevice("device-1", null, null, 3)
        }
    }

    @Test
    fun `device metadata rejects postgres nul while retaining database representable unicode`() {
        assertEquals("设备名包含不支持的字符", AuthRules.validateDeviceName("Office\u0000Desktop"))
        assertEquals("设备型号包含不支持的字符", AuthRules.validateDeviceModel("Model\u0000One"))
        assertNull(AuthRules.validateDeviceName("上海办公室 · 桌面端"))
        assertNull(AuthRules.validateDeviceModel("型号😀"))

        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateDevice(
                "device-1",
                "Office\u0000Desktop",
                null,
                AuthRules.DEVICE_FLAG_DESKTOP,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthRules.validateDevice(
                "device-1",
                null,
                "Model\u0000One",
                AuthRules.DEVICE_FLAG_DESKTOP,
            )
        }
    }
}
