package com.virjar.tk.testing

import com.virjar.tk.client.PendingBotMessage
import com.virjar.tk.model.Message

/** In-memory equivalent of the epoch-3 acked delivery log. */
internal class FakeBotMessageLog {
    private data class Delivery(val message: Message, var acked: Boolean = false)

    private val rows = sortedMapOf<Long, Delivery>()

    fun enqueue(eventId: Long, message: Message) {
        synchronized(rows) {
            require(eventId > 0L) { "eventId must be positive" }
            require(message.serverSeq > 0L) { "durable bot messages require a positive serverSeq" }
            rows.putIfAbsent(eventId, Delivery(message))
        }
    }

    fun peek(): PendingBotMessage? = synchronized(rows) {
        rows.entries.firstOrNull { !it.value.acked }?.let { (eventId, delivery) ->
            PendingBotMessage(eventId, delivery.message)
        }
    }

    fun ack(eventId: Long) = synchronized(rows) { rows[eventId]?.acked = true }

    fun list(afterEventId: Long, chatId: String?, limit: Int): List<PendingBotMessage> = synchronized(rows) {
        require(afterEventId >= 0L)
        require(limit > 0)
        rows.asSequence()
            .filter { (eventId, delivery) ->
                eventId > afterEventId && (chatId == null || delivery.message.chatId == chatId)
            }
            .take(limit)
            .map { (eventId, delivery) -> PendingBotMessage(eventId, delivery.message) }
            .toList()
    }

    fun maxEventId(): Long = synchronized(rows) { rows.keys.lastOrNull() ?: 0L }

    fun deleteChat(chatId: String) = synchronized(rows) {
        rows.entries.removeAll { (_, delivery) -> delivery.message.chatId == chatId }
    }

    fun reset() = synchronized(rows) { rows.clear() }
}
