package com.virjar.tk.server.domain.user

import java.security.SecureRandom

/**
 * 8 位 base62 短 UID 生成器。
 *
 * 生成 [0-9a-zA-Z] 共 62 个字符的 8 位短码（约 218 万亿组合），
 * 用 [SecureRandom] 保证不可预测，避免攻击者枚举用户 uid。
 *
 * 本对象只生成候选值，不做会竞态的存储预查。注册仓储以 PostgreSQL uid 唯一约束作为事实源，
 * 只在精确命中该约束时从预生成的有界候选列表继续重试，耗尽后明确失败。
 */
object ShortUidGenerator {
    private const val LENGTH = 8
    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val random = SecureRandom()

    /** 生成一个 8 位 base62 候选短码；全局唯一性由数据库约束决定。 */
    fun next(): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) {
            sb.append(BASE62[random.nextInt(BASE62.length)])
        }
        return sb.toString()
    }
}
