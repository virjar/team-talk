package com.virjar.tk.domain.user

import java.security.SecureRandom

/**
 * 8 位 base62 短 UID 生成器。
 *
 * 生成 [0-9a-zA-Z] 共 62 个字符的 8 位短码（约 218 万亿组合），
 * 用 [SecureRandom] 保证不可预测，避免攻击者枚举用户 uid。
 *
 * 碰撞由调用方通过 store 查重 + 重试处理（见 [UserService.generateShortUid]）。
 * 本对象只负责「生成一个随机短码」，不负责唯一性——后者依赖 store 状态。
 */
object ShortUidGenerator {
    private const val LENGTH = 8
    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val random = SecureRandom()

    /** 生成一个 8 位 base62 随机短码（不保证全局唯一，需调用方查重）。 */
    fun next(): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) {
            sb.append(BASE62[random.nextInt(BASE62.length)])
        }
        return sb.toString()
    }
}
