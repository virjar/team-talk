package com.virjar.tk.server.domain.chat

import kotlinx.coroutines.sync.Mutex

/**
 * 在一个服务器进程内串行化同一聊天的生命周期敏感工作。
 *
 * 机器人授权（grant）绝不能写在撤回聊天的外部领域状态与停用聊天本身之间。固定的分片
 * 使闸门保持有界，同时允许无关的聊天独立推进。
 */
class ChatLifecycleGate(stripeCount: Int = DEFAULT_STRIPE_COUNT) {
    private val stripes = Array(stripeCount.coerceAtLeast(1)) { Mutex() }

    suspend fun <T> withChat(chatId: String, block: suspend () -> T): T =
        withChats(chatId, block = block)

    /**
     * 针对每个提供的聊天串行化一个聚合操作。
     *
     * 锁按稳定的分片下标获取，而不是按调用方顺序，因此诸如 `forward(a, b)` 与
     * `forward(b, a)` 的操作不会互相死锁。相等或仅仅碰撞到同一分片的聊天 id 只会获取
     * 一次该不可重入的 [Mutex]。
     */
    suspend fun <T> withChats(vararg chatIds: String, block: suspend () -> T): T {
        val stripeIndexes = chatIds
            .asSequence()
            .map(::stripeIndex)
            .distinct()
            .sorted()
            .toList()
        return withStripes(stripeIndexes, 0, block)
    }

    private suspend fun <T> withStripes(
        stripeIndexes: List<Int>,
        position: Int,
        block: suspend () -> T,
    ): T {
        if (position == stripeIndexes.size) return block()

        val mutex = stripes[stripeIndexes[position]]
        mutex.lock()
        return try {
            withStripes(stripeIndexes, position + 1, block)
        } finally {
            mutex.unlock()
        }
    }

    private fun stripeIndex(chatId: String): Int =
        (chatId.hashCode() and Int.MAX_VALUE) % stripes.size

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
    }
}
