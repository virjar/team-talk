package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.protocol.model.Message

/** epoch-3 已回执投递日志的内存版等价实现。 */
internal class FakeBotMessageLog {
    private data class Delivery(val message: Message, var acked: Boolean = false)

    val lock = PlatformLock()
    private val rows = mutableMapOf<Long, Delivery>()

    fun enqueue(eventId: Long, message: Message) {
        synchronized(lock) {
            require(eventId > 0L) { "eventId must be positive" }
            require(message.serverSeq > 0L) { "durable bot messages require a positive serverSeq" }
            rows.getOrPut(eventId) { Delivery(message) }
        }
    }

    fun peek(): PendingBotMessage? = synchronized(lock) {
        rows.entries.filter { !it.value.acked }.minByOrNull { it.key }?.let { (eventId, delivery) ->
            PendingBotMessage(eventId, delivery.message)
        }
    }

    fun ack(eventId: Long) = synchronized(lock) { rows[eventId]?.acked = true }

    fun list(afterEventId: Long, chatId: String?, limit: Int): List<PendingBotMessage> = synchronized(lock) {
        require(afterEventId >= 0L)
        require(limit > 0)
        rows.asSequence()
            .filter { (eventId, delivery) ->
                eventId > afterEventId && (chatId == null || delivery.message.chatId == chatId)
            }
            .sortedBy { it.key }
            .take(limit)
            .map { (eventId, delivery) -> PendingBotMessage(eventId, delivery.message) }
            .toList()
    }

    fun maxEventId(): Long = synchronized(lock) { rows.keys.maxOrNull() ?: 0L }

    fun deleteChat(chatId: String) = synchronized(lock) {
        rows.entries.removeAll { (_, delivery) -> delivery.message.chatId == chatId }
    }

    fun reset() = synchronized(lock) { rows.clear() }
}
