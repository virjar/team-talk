package com.virjar.tk.client

import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.CompletableDeferred

/** A retryable transport loss, distinct from cancellation of the caller/session coroutine. */
internal class AckTransportDisconnectedException : IllegalStateException(
    "Connection closed before message ACK",
)

/**
 * Event-loop-owned registry for MESSAGE_ACK waiters.
 *
 * This class deliberately contains no dispatcher or lock: [PacketRouter] creates, completes and
 * removes entries only from the active transport's single EventLoop. Keeping the map behind this
 * boundary prevents connection teardown and packet routing from growing separate ACK owners.
 */
internal class PendingAckRegistry {
    private data class Entry(
        val sessionOwner: Any,
        val sessionLease: SessionOutboundLease?,
        val deferred: CompletableDeferred<MessageAckPayload>,
    )

    private val pending = mutableMapOf<String, Entry>()

    fun register(
        clientMsgId: String,
        sessionOwner: Any,
        sessionLease: SessionOutboundLease?,
    ): CompletableDeferred<MessageAckPayload> {
        check(sessionLease?.isActive() != false) { "Outbound session is retired" }
        val deferred = CompletableDeferred<MessageAckPayload>()
        check(clientMsgId !in pending) {
            "Duplicate pending clientMsgId: $clientMsgId"
        }
        pending[clientMsgId] = Entry(sessionOwner, sessionLease, deferred)
        return deferred
    }

    fun complete(ack: MessageAckPayload): Boolean {
        val entry = pending.remove(ack.clientMsgId) ?: return false
        // Never resume an arbitrary continuation while holding SessionOutboundLease: the caller's
        // post-ACK lifecycle check takes the lifecycle lock, while quiesce retires in the opposite
        // order. EventLoop-owned cancelOwner plus that publication check are the ACK boundary.
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
        clientMsgId: String,
        expected: CompletableDeferred<MessageAckPayload>,
    ) {
        if (pending[clientMsgId]?.deferred === expected) pending.remove(clientMsgId)
    }

    fun cancelOwner(sessionOwner: Any) {
        val retired = pending.entries
            .filter { (_, entry) -> entry.sessionOwner === sessionOwner }
            .map { (clientMsgId, entry) -> clientMsgId to entry.deferred }
        retired.forEach { (clientMsgId, deferred) ->
            pending.remove(clientMsgId)
            deferred.completeExceptionally(
                kotlinx.coroutines.CancellationException("Outbound session retired before ACK"),
            )
        }
    }

    fun cancelAll() {
        pending.values.forEach { entry ->
            entry.deferred.completeExceptionally(AckTransportDisconnectedException())
        }
        pending.clear()
    }
}
