package com.virjar.tk.client

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Whether an explicit session retirement keeps durable work for the same account's next session. */
enum class SendQueueCloseDisposition { PRESERVE, CANCEL }

internal enum class OutgoingAckDisposition { SUCCESS, RETRYABLE, TERMINAL }

/** Local timeout/transport-style negative codes and server 5xx preserve the idempotency key. */
internal fun classifyOutgoingAck(
    ack: MessageAckPayload,
    expectedClientMsgId: String,
): OutgoingAckDisposition = when {
    ack.clientMsgId != expectedClientMsgId -> OutgoingAckDisposition.TERMINAL
    ack.code == 0 && ack.serverSeq > 0L -> OutgoingAckDisposition.SUCCESS
    ack.code < 0 || ack.code in 500..599 -> OutgoingAckDisposition.RETRYABLE
    else -> OutgoingAckDisposition.TERMINAL
}

/**
 * Account-owned durable FIFO sender.
 *
 * Admission first commits the optimistic message and immutable canonical wire payload to SQLite.
 * The worker claims only the oldest active local ordinal. Successful ACK projection and durable
 * SUCCESS receipt share one transaction; ambiguous outcomes remain retryable under the same
 * clientMsgId, allowing the server's idempotency boundary to resolve a lost response.
 */
class SendQueue(
    private val ownerUid: String,
    private val localCache: LocalCache,
    private val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>,
    private val sender: MessageSender,
    scope: CoroutineScope,
    private val onQueued: (Message) -> Unit = {},
    private val onSent: (Message, MessageAckPayload) -> Unit = { _, _ -> },
    private val onFailed: (Message, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ackTimeoutMs: Long = 30_000L,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
    )
    private val callbackGate = SessionWorkGate("SendQueue")
    private val callbackLease = callbackGate.lease()

    init {
        require(ownerUid.isNotBlank()) { "SendQueue owner uid must not be blank" }
        require(ackTimeoutMs > 0L) { "ackTimeoutMs must be positive" }
        callbackGate.use(callbackLease) { localCache.recoverOutgoingMessages(clock()) }
        connectionState
            .onEach {
                if (it == ConnectionState.AUTHENTICATED) {
                    callbackGate.runIfActive(callbackLease) { wake.trySend(Unit) }
                }
            }
            .launchIn(this.scope)
        this.scope.launch { workerLoop() }
        wake.trySend(Unit)
    }

    /** Synchronously commits before returning; no in-memory lambda is part of the durable fact. */
    fun enqueue(message: Message, requestFingerprint: ByteArray? = null): OutgoingMessage =
        callbackGate.use(callbackLease) {
            require(message.senderUid == ownerUid) {
                "Outgoing message owner ${message.senderUid} does not match fixed session owner $ownerUid"
            }
            val canonical = canonicalizeOutboundMessage(message)
            val receipt = localCache.enqueueOutgoingMessage(canonical, clock(), requestFingerprint)
            wake.trySend(Unit)
            receipt
        }

    /** Reads a receipt without touching worker state; an expected fingerprint makes mismatch fail closed. */
    fun receipt(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage? = callbackGate.use(callbackLease) {
        localCache.getOutgoingMessage(chatId, clientMsgId, requestFingerprint)
    }

    /**
     * Crosses the callback/cache boundary before cancellation. CANCEL is used for explicit account
     * retirement; PRESERVE leaves an IN_FLIGHT row for deterministic restart recovery.
     */
    fun close(disposition: SendQueueCloseDisposition = SendQueueCloseDisposition.PRESERVE) {
        var boundaryFailure: SessionWorkGateReentrantCloseException? = null
        val newlyClosed = try {
            callbackGate.close()
        } catch (failure: SessionWorkGateReentrantCloseException) {
            boundaryFailure = failure
            true
        }
        if (!newlyClosed) return
        scope.cancel()
        if (disposition == SendQueueCloseDisposition.CANCEL) {
            localCache.cancelOutgoingMessages("cancelled by account retirement", clock())
        }
        boundaryFailure?.let { throw it }
    }

    private suspend fun workerLoop() {
        while (true) {
            var head: OutgoingMessage? = null
            if (!callbackGate.runIfActive(callbackLease) {
                    head = localCache.peekNextOutgoingMessage()
                }
            ) return
            if (head == null) {
                wake.receive()
                continue
            }
            if (connectionState.value != ConnectionState.AUTHENTICATED) {
                if (!callbackGate.runIfActive(callbackLease) { onQueued(checkNotNull(head).message) }) return
                wake.receive()
                continue
            }
            val now = clock()
            if (checkNotNull(head).nextAttemptAt > now) {
                delay((checkNotNull(head).nextAttemptAt - now).coerceAtMost(MAX_RETRY_DELAY_MS))
                continue
            }
            var claimed: OutgoingMessage? = null
            if (!callbackGate.runIfActive(callbackLease) {
                    claimed = localCache.claimNextOutgoingMessage(clock())
                }
            ) return
            claimed ?: continue
            deliver(checkNotNull(claimed))
        }
    }

    private suspend fun deliver(outgoing: OutgoingMessage) {
        var failure: Exception? = null
        val ack = withTimeoutOrNull(ackTimeoutMs) {
            try {
                sender.sendAndWaitAck(outgoing.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (caught: Exception) {
                failure = caught
                null
            }
        }
        val now = clock()
        val ackDisposition = ack?.let { classifyOutgoingAck(it, outgoing.message.clientMsgId) }
        if (ack != null && ackDisposition == OutgoingAckDisposition.SUCCESS) {
            if (!callbackGate.runIfActive(callbackLease) {
                    localCache.completeOutgoingMessage(outgoing.localOrdinal, ack, now)
                    onSent(outgoing.message, ack)
                }
            ) return
            return
        }

        val reason = when {
            ack != null && ack.clientMsgId != outgoing.message.clientMsgId -> "ACK clientMsgId mismatch"
            ack != null && ack.code != 0 -> ack.reason ?: "server rejected message (${ack.code})"
            ack != null && ack.serverSeq <= 0L -> "successful ACK has no server sequence"
            failure != null -> failure.message ?: failure::class.simpleName ?: "send failed"
            else -> "send acknowledgement timed out"
        }
        val terminal = when {
            ack == null -> failure is IllegalArgumentException
            else -> ackDisposition == OutgoingAckDisposition.TERMINAL
        }
        if (terminal) {
            callbackGate.runIfActive(callbackLease) {
                localCache.markOutgoingMessageTerminalFailed(
                    outgoing.localOrdinal,
                    reason,
                    now,
                    terminalCode = ack?.code ?: if (failure is IllegalArgumentException) 400 else null,
                )
                onFailed(outgoing.message, reason)
            }
        } else {
            val nextAttemptAt = now + retryDelayMillis(outgoing.attemptCount)
            callbackGate.runIfActive(callbackLease) {
                localCache.markOutgoingMessageRetry(outgoing.localOrdinal, reason, nextAttemptAt, now)
                onQueued(outgoing.message)
                wake.trySend(Unit)
            }
        }
    }

    private fun retryDelayMillis(attemptCount: Long): Long {
        val shift = (attemptCount - 1L).coerceIn(0L, 6L).toInt()
        return (BASE_RETRY_DELAY_MS shl shift).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private companion object {
        const val BASE_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_DELAY_MS = 30_000L
    }
}
