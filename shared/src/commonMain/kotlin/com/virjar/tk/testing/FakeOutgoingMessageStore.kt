package com.virjar.tk.testing

import com.virjar.tk.client.OutgoingMessage
import com.virjar.tk.client.OutgoingMessageConflictException
import com.virjar.tk.client.OutgoingMessageState
import com.virjar.tk.client.canonicalizeOutboundMessage
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.MessageAckPayload

/** Durable-outbox semantics for [FakeLocalCache], isolated to keep the general fake bounded. */
internal class FakeOutgoingMessageStore(
    /** Shared with the fake message projection so every combined transition has one lock order. */
    private val lock: Any,
    private val upsertProjection: (Message) -> Unit,
    private val updateProjectionStatus: (Message, Int) -> Unit,
    private val completeProjection: (Message, Long) -> Unit,
    private val markAuthoritativeProjectionSent: (Message) -> Unit = {},
    private val successReceiptLimit: Int = com.virjar.tk.client.DEFAULT_OUTGOING_SUCCESS_RECEIPTS,
) {
    private val rows = sortedMapOf<Long, OutgoingMessage>()
    private val requestFingerprints = mutableMapOf<Long, ByteArray?>()
    private var nextOrdinal = 1L

    init {
        require(successReceiptLimit > 0)
    }

    fun enqueue(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage = synchronized(lock) {
        val canonical = canonicalizeOutboundMessage(message)
        require(canonical.serverSeq == 0L)
        require(requestFingerprint == null || requestFingerprint.isNotEmpty())
        val wire = ProtoCodec.decode(Message, ProtoCodec.encode(canonical))
        val existing = rows.values.firstOrNull {
            it.message.chatId == wire.chatId && it.message.clientMsgId == wire.clientMsgId
        }
        if (existing != null) {
            requireSameRequest(existing, wire, requestFingerprint)
            if (existing.state != OutgoingMessageState.SUCCESS) {
                upsertProjection(existing.toProjectionMessage())
            }
            return@synchronized existing
        }
        check(nextOrdinal < Long.MAX_VALUE) { "outgoing ordinal exhausted" }
        OutgoingMessage(
            localOrdinal = nextOrdinal++,
            message = wire,
            state = OutgoingMessageState.PENDING,
            attemptCount = 0L,
            lastError = null,
            nextAttemptAt = 0L,
            createdAt = now,
            updatedAt = now,
        ).also { outgoing ->
            rows[outgoing.localOrdinal] = outgoing
            requestFingerprints[outgoing.localOrdinal] = requestFingerprint?.copyOf()
            upsertProjection(canonical.copy(sendStatus = Message.SEND_STATUS_QUEUED))
        }
    }

    fun get(chatId: String, clientMsgId: String, requestFingerprint: ByteArray?): OutgoingMessage? =
        synchronized(lock) {
            rows.values.firstOrNull {
                it.message.chatId == chatId && it.message.clientMsgId == clientMsgId
            }?.also { existing ->
                if (requestFingerprint != null) {
                    val stored = requestFingerprints[existing.localOrdinal]
                    if (stored == null || !stored.contentEquals(requestFingerprint)) conflict()
                }
            }
        }

    fun recover(now: Long): List<OutgoingMessage> = synchronized(lock) {
        rows.entries.forEach { (ordinal, row) ->
            if (row.state == OutgoingMessageState.IN_FLIGHT) {
                val recovered = row.copy(
                    state = OutgoingMessageState.PENDING,
                    lastError = "interrupted before durable response",
                    nextAttemptAt = 0L,
                    updatedAt = now,
                )
                rows[ordinal] = recovered
                updateProjectionStatus(recovered.message, Message.SEND_STATUS_QUEUED)
            }
        }
        rows.values.toList()
    }

    fun peek(): OutgoingMessage? = synchronized(lock) {
        rows.values.firstOrNull { it.isActive() }
    }

    fun projectionKeys(): Set<Pair<String, String>> = synchronized(lock) {
        rows.values.mapTo(mutableSetOf()) { it.message.chatId to it.message.clientMsgId }
    }

    fun claim(now: Long): OutgoingMessage? = synchronized(lock) {
        val head = rows.values.firstOrNull { it.isActive() } ?: return@synchronized null
        if (head.nextAttemptAt > now) return@synchronized null
        head.copy(
            state = OutgoingMessageState.IN_FLIGHT,
            attemptCount = head.attemptCount + 1L,
            lastError = null,
            updatedAt = now,
        ).also { claimed ->
            rows[claimed.localOrdinal] = claimed
            updateProjectionStatus(claimed.message, Message.SEND_STATUS_SENDING)
        }
    }

    fun retry(localOrdinal: Long, error: String, nextAttemptAt: Long, now: Long) = synchronized(lock) {
        val row = rows[localOrdinal]?.takeIf { it.state == OutgoingMessageState.IN_FLIGHT }
            ?: return@synchronized
        rows[localOrdinal] = row.copy(
            state = OutgoingMessageState.RETRY_WAIT,
            lastError = error,
            nextAttemptAt = nextAttemptAt,
            updatedAt = now,
        )
        updateProjectionStatus(row.message, Message.SEND_STATUS_QUEUED)
    }

    fun fail(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int? = null,
    ) = synchronized(lock) {
        val row = rows[localOrdinal]?.takeIf { it.state == OutgoingMessageState.IN_FLIGHT }
            ?: return@synchronized
        val completedAt = nextCompletionTime(now)
        rows[localOrdinal] = row.copy(
            state = OutgoingMessageState.TERMINAL_FAILED,
            lastError = error,
            nextAttemptAt = 0L,
            updatedAt = now,
            terminalCode = terminalCode,
            completedAt = completedAt,
        )
        updateProjectionStatus(row.message, Message.SEND_STATUS_FAILED)
    }

    fun complete(localOrdinal: Long, ack: MessageAckPayload, now: Long = 0L) = synchronized(lock) {
        require(ack.code == 0 && ack.serverSeq > 0L)
        val row = rows[localOrdinal]?.takeIf { it.state == OutgoingMessageState.IN_FLIGHT }
            ?: return@synchronized
        require(row.message.clientMsgId == ack.clientMsgId)
        val completedAt = nextCompletionTime(now)
        rows[localOrdinal] = row.copy(
            state = OutgoingMessageState.SUCCESS,
            lastError = null,
            nextAttemptAt = 0L,
            updatedAt = now,
            serverSeq = ack.serverSeq,
            terminalCode = null,
            completedAt = completedAt,
        )
        completeProjection(row.message, ack.serverSeq)
        pruneSuccessReceipts()
    }

    fun promoteFromAuthority(message: Message, now: Long) = synchronized(lock) {
        if (message.serverSeq <= 0L) return@synchronized
        markAuthoritativeProjectionSent(message)
        val entry = rows.entries.firstOrNull { (_, row) ->
            row.message.chatId == message.chatId &&
                row.message.clientMsgId == message.clientMsgId &&
                row.message.senderUid == message.senderUid
        } ?: return@synchronized
        if (entry.value.state == OutgoingMessageState.SUCCESS) return@synchronized
        val completedAt = nextCompletionTime(now)
        rows[entry.key] = entry.value.copy(
            state = OutgoingMessageState.SUCCESS,
            lastError = null,
            nextAttemptAt = 0L,
            updatedAt = now,
            serverSeq = message.serverSeq,
            terminalCode = null,
            completedAt = completedAt,
        )
        pruneSuccessReceipts()
    }

    fun cancel(reason: String, now: Long) = synchronized(lock) {
        val active = rows.values.filter { it.isActive() || it.state == OutgoingMessageState.IN_FLIGHT }
        val completedAt = if (active.isEmpty()) null else nextCompletionTime(now)
        rows.entries.forEach { (ordinal, row) ->
            if (row.isActive() || row.state == OutgoingMessageState.IN_FLIGHT) {
                rows[ordinal] = row.copy(
                    state = OutgoingMessageState.TERMINAL_FAILED,
                    lastError = reason,
                    nextAttemptAt = 0L,
                    updatedAt = now,
                    terminalCode = 499,
                    completedAt = completedAt,
                )
                updateProjectionStatus(row.message, Message.SEND_STATUS_FAILED)
            }
        }
    }

    fun deleteChat(chatId: String) = synchronized(lock) {
        val removed = rows.filterValues { it.message.chatId == chatId }.keys
        rows.keys.removeAll(removed)
        requestFingerprints.keys.removeAll(removed)
    }

    fun projectionAfterReset(): List<Message> = synchronized(lock) {
        rows.values.filter { it.state != OutgoingMessageState.SUCCESS }.map { outgoing ->
            outgoing.toProjectionMessage()
        }
    }

    private fun OutgoingMessage.isActive(): Boolean =
        state == OutgoingMessageState.PENDING || state == OutgoingMessageState.RETRY_WAIT

    private fun OutgoingMessage.projectionSendStatus(): Int = when (state) {
        OutgoingMessageState.IN_FLIGHT -> Message.SEND_STATUS_SENDING
        OutgoingMessageState.TERMINAL_FAILED -> Message.SEND_STATUS_FAILED
        OutgoingMessageState.SUCCESS -> Message.SEND_STATUS_SENT
        OutgoingMessageState.PENDING,
        OutgoingMessageState.RETRY_WAIT -> Message.SEND_STATUS_QUEUED
    }

    private fun OutgoingMessage.toProjectionMessage(): Message = message.copy(
        serverSeq = if (state == OutgoingMessageState.SUCCESS) requireNotNull(serverSeq) else 0L,
        sendStatus = projectionSendStatus(),
    )

    private fun requireSameRequest(
        existing: OutgoingMessage,
        candidate: Message,
        candidateFingerprint: ByteArray?,
    ) {
        val storedFingerprint = requestFingerprints[existing.localOrdinal]
        if (storedFingerprint != null || candidateFingerprint != null) {
            if (
                storedFingerprint == null || candidateFingerprint == null ||
                !storedFingerprint.contentEquals(candidateFingerprint)
            ) conflict()
            return
        }
        if (existing.message != candidate) conflict()
    }

    private fun pruneSuccessReceipts() {
        val retained = rows.values.sortedWith(
            compareByDescending<OutgoingMessage> { it.completedAt ?: Long.MIN_VALUE }
                .thenByDescending(OutgoingMessage::localOrdinal),
        )
            .filter { it.state == OutgoingMessageState.SUCCESS }
            .take(successReceiptLimit)
            .mapTo(mutableSetOf()) { it.localOrdinal }
        val removed = rows.values.filter {
            it.state == OutgoingMessageState.SUCCESS && it.localOrdinal !in retained
        }.mapTo(mutableSetOf()) { it.localOrdinal }
        rows.keys.removeAll(removed)
        requestFingerprints.keys.removeAll(removed)
    }

    private fun nextCompletionTime(now: Long): Long {
        val previous = rows.values.asSequence().mapNotNull(OutgoingMessage::completedAt).maxOrNull()
        return if (previous == null || now > previous) {
            now
        } else {
            check(previous < Long.MAX_VALUE) { "outgoing completion clock exhausted" }
            previous + 1L
        }
    }

    private fun conflict(): Nothing = throw OutgoingMessageConflictException(
        "clientMsgId already names a different durable outgoing request",
    )
}
