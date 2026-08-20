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
    private val pending = mutableMapOf<String, CompletableDeferred<MessageAckPayload>>()

    fun register(clientMsgId: String): CompletableDeferred<MessageAckPayload> {
        val deferred = CompletableDeferred<MessageAckPayload>()
        check(clientMsgId !in pending) {
            "Duplicate pending clientMsgId: $clientMsgId"
        }
        pending[clientMsgId] = deferred
        return deferred
    }

    fun complete(ack: MessageAckPayload): Boolean =
        pending.remove(ack.clientMsgId)?.complete(ack) == true

    fun remove(
        clientMsgId: String,
        expected: CompletableDeferred<MessageAckPayload>,
    ) {
        if (pending[clientMsgId] === expected) pending.remove(clientMsgId)
    }

    fun cancelAll() {
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(AckTransportDisconnectedException())
        }
        pending.clear()
    }
}
