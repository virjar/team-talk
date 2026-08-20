package com.virjar.tk.domain.chat

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes lifecycle-sensitive work for the same chat inside one server process.
 *
 * Bot grants must not be written between revoking a chat's external-domain state and
 * deactivating the chat itself. Fixed stripes keep the gate bounded while allowing
 * unrelated chats to make progress independently.
 */
class ChatLifecycleGate(stripeCount: Int = DEFAULT_STRIPE_COUNT) {
    private val stripes = Array(stripeCount.coerceAtLeast(1)) { Mutex() }

    suspend fun <T> withChat(chatId: String, block: suspend () -> T): T =
        withChats(chatId, block = block)

    /**
     * Serializes one aggregate operation against every supplied chat.
     *
     * Locks are acquired by stable stripe index rather than caller order, so operations such as
     * `forward(a, b)` and `forward(b, a)` cannot deadlock each other. Chat ids which are equal or
     * merely collide onto the same stripe acquire that non-reentrant [Mutex] only once.
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
