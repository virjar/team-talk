package com.virjar.tk.shared.client

import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.CompletableDeferred

internal class PendingAckCapacityExceededException(capacity: Int) : IllegalStateException(
    "Pending message ACK capacity $capacity is exhausted",
)

/**
 * Event-loop 拥有的 MESSAGE_ACK 等待者注册表。
 *
 * 该类刻意不含 dispatcher 或锁：[PacketRouter] 只在活跃 transport 的单一 EventLoop 上创建、完成与
 * 移除条目。把 map 保持在此边界之后，防止连接拆除与包路由滋生出独立的 ACK owner。
 */
internal class PendingAckRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private data class MessageIdentity(val chatId: String, val clientMsgId: String)

    private data class Entry(
        val sessionOwner: Any,
        val sessionLease: SessionOutboundLease?,
        val deferred: CompletableDeferred<MessageAckPayload>,
    )

    private val pending = mutableMapOf<MessageIdentity, Entry>()

    init {
        require(capacity > 0) { "Pending ACK capacity must be positive" }
    }

    fun register(
        chatId: String,
        clientMsgId: String,
        sessionOwner: Any,
        sessionLease: SessionOutboundLease?,
    ): CompletableDeferred<MessageAckPayload> {
        check(sessionLease?.isActive() != false) { "Outbound session is retired" }
        val deferred = CompletableDeferred<MessageAckPayload>()
        val identity = MessageIdentity(chatId, clientMsgId)
        check(identity !in pending) {
            "Duplicate pending message identity: $chatId/$clientMsgId"
        }
        if (pending.size >= capacity) throw PendingAckCapacityExceededException(capacity)
        pending[identity] = Entry(sessionOwner, sessionLease, deferred)
        return deferred
    }

    fun complete(ack: MessageAckPayload): Boolean {
        val entry = pending.remove(MessageIdentity(ack.chatId, ack.clientMsgId)) ?: return false
        // 绝不持有 SessionOutboundLease 恢复任意 continuation：调用方的 ACK 后生命周期检查获取
        // 生命周期锁，而 quiesce 按相反顺序退役。EventLoop 拥有的 cancelOwner 加那次发布检查
        // 就是 ACK 边界。
        return if (entry.sessionLease?.isActive() == false) {
            entry.deferred.completeExceptionally(
                kotlinx.coroutines.CancellationException("Outbound session retired before ACK"),
            )
            false
        } else {
            entry.deferred.complete(ack)
        }
    }

    fun remove(
        chatId: String,
        clientMsgId: String,
        expected: CompletableDeferred<MessageAckPayload>,
    ) {
        val identity = MessageIdentity(chatId, clientMsgId)
        if (pending[identity]?.deferred === expected) pending.remove(identity)
    }

    fun cancelOwner(sessionOwner: Any) {
        val retired = pending.entries
            .filter { (_, entry) -> entry.sessionOwner === sessionOwner }
            .map { (identity, entry) -> identity to entry.deferred }
        retired.forEach { (identity, deferred) ->
            pending.remove(identity)
            deferred.completeExceptionally(
                kotlinx.coroutines.CancellationException("Outbound session retired before ACK"),
            )
        }
    }

    fun cancelAll() {
        pending.values.forEach { entry ->
            entry.deferred.completeExceptionally(
                TransportUnavailableException("Connection closed before message ACK"),
            )
        }
        pending.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 1_024
    }
}
