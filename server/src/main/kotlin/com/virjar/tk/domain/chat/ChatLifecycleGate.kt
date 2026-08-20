package com.virjar.tk.domain.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        stripes[(chatId.hashCode() and Int.MAX_VALUE) % stripes.size].withLock { block() }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
    }
}
