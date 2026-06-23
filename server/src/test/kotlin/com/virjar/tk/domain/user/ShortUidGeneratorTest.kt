package com.virjar.tk.domain.user

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ShortUidGenerator 纯单测——不依赖 DB，验证格式、唯一性、字符集。
 */
class ShortUidGeneratorTest {

    private val base62 = Regex("^[0-9a-zA-Z]+$")

    @Test
    fun `生成的 uid 始终是 8 位`() {
        repeat(1000) {
            val uid = ShortUidGenerator.next()
            assertEquals(8, uid.length, "uid 长度应为 8: $uid")
        }
    }

    @Test
    fun `生成的 uid 只含 base62 字符`() {
        repeat(1000) {
            val uid = ShortUidGenerator.next()
            assertTrue(uid.matches(base62), "uid 含非 base62 字符: $uid")
        }
    }

    @Test
    fun `批量生成 10000 个 uid 无碰撞`() {
        val uids = HashSet<String>(10000)
        repeat(10000) {
            val uid = ShortUidGenerator.next()
            assertTrue(uids.add(uid), "uid 碰撞: $uid（前 ${uids.size} 个中）")
        }
        assertEquals(10000, uids.size)
    }

    @Test
    fun `字符分布大致均匀`() {
        // 生成 10000 个 uid，统计各字符出现频率，验证 SecureRandom 不是退化分布
        val counts = IntArray(62)
        val base62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(10000) {
            val uid = ShortUidGenerator.next()
            uid.forEach { c -> counts[base62.indexOf(c)]++ }
        }
        // 10000 个 uid × 8 位 = 80000 字符，期望每个字符约 80000/62 ≈ 1290 次
        // 允许较大容差（±50%），只验证没有字符完全缺席
        counts.forEachIndexed { idx, count ->
            assertTrue(count > 400, "字符 '${base62[idx]}' 出现次数过低: $count")
        }
    }
}
