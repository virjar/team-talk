package com.virjar.tk.server.domain.auth

/**
 * CPU 密集的密码派生边界。
 *
 * 实现必须把工作放在显式有界的 CPU 责任者上运行。给 [verify] 传 `null` 表示对不存在或
 * 策略不允许的身份请求等效的假工作；调用方因此可以拒绝该身份，而不暴露是否存在可用的
 * 密码校验器。
 */
interface PasswordHasher {
    suspend fun hash(rawPassword: String): String

    suspend fun verify(rawPassword: String, encodedHash: String?): Boolean
}
