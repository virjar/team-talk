package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.LocalOutboxCapacityDimension
import com.virjar.tk.shared.client.LocalOutboxCapacityExceededException
import com.virjar.tk.shared.client.LocalOutboxKind
import com.virjar.tk.shared.client.MAX_ACTIVE_OUTGOING_MESSAGES
import com.virjar.tk.shared.client.MAX_ACTIVE_OUTGOING_STORED_BYTES
import com.virjar.tk.shared.client.MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES
import com.virjar.tk.shared.client.MAX_TERMINAL_OUTGOING_RECEIPTS
import com.virjar.tk.shared.client.MAX_TERMINAL_OUTGOING_STORED_BYTES
import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.OutgoingMessageConflictException
import com.virjar.tk.shared.client.OutgoingFailureCode
import com.virjar.tk.shared.client.OutgoingQueueSnapshot
import com.virjar.tk.shared.client.OutgoingMessageState
import com.virjar.tk.shared.client.canonicalizeOutboundMessage
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.MessageAckPayload

/** [FakeLocalCache] 的持久化可靠发件箱语义，隔离出来以保持通用测试替身有界。 */
internal class FakeOutgoingMessageStore(
    /** 与测试替身消息投影共享，使每次组合状态转移只有一种加锁顺序。 */
    private val lock: Any,
    private val upsertProjection: (Message) -> Unit,
    private val updateProjectionStatus: (Message, Int) -> Unit,
    private val completeProjection: (Message, Long) -> Unit,
    private val markAuthoritativeProjectionSent: (Message) -> Unit = {},
    private val terminalReceiptLimit: Int = MAX_TERMINAL_OUTGOING_RECEIPTS,
) {
    private val rows = sortedMapOf<Long, OutgoingMessage>()
    private val requestFingerprints = mutableMapOf<Long, ByteArray?>()
    private val storedBytes = mutableMapOf<Long, Long>()
    /** 稳定的失败投影权威信息在终态回执被 GC 后依然保留；原始诊断信息从不保留。 */
    private val projectionFailureCodes = mutableMapOf<Pair<String, String>, OutgoingFailureCode>()
    private var nextOrdinal = 1L

    init {
        require(terminalReceiptLimit in 1..MAX_TERMINAL_OUTGOING_RECEIPTS)
    }

    fun enqueue(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray? = null,
        publishProjection: Boolean = true,
    ): OutgoingMessage = synchronized(lock) {
        val canonical = canonicalizeOutboundMessage(message)
        require(canonical.serverSeq == 0L)
        validateFingerprint(requestFingerprint)
        val encoded = ProtoCodec.encode(canonical)
        val wire = ProtoCodec.decode(Message, encoded)
        val existing = rows.values.firstOrNull {
            it.message.chatId == wire.chatId && it.message.clientMsgId == wire.clientMsgId
        }
        if (existing != null) {
            requireSameRequest(existing, wire, requestFingerprint)
            if (publishProjection && existing.state != OutgoingMessageState.SUCCESS) {
                upsertProjection(existing.toProjectionMessage())
            }
            return@synchronized existing
        }
        admitActive(
            encoded.size.toLong() + (requestFingerprint?.size?.toLong() ?: 0L),
        )
        check(nextOrdinal < Long.MAX_VALUE) { "outgoing ordinal exhausted" }
        OutgoingMessage(
            localOrdinal = nextOrdinal++,
            message = wire,
            state = OutgoingMessageState.PENDING,
            attemptCount = 0L,
            nextAttemptAt = 0L,
            createdAt = now,
            updatedAt = now,
        ).also { outgoing ->
            projectionFailureCodes.remove(outgoing.message.chatId to outgoing.message.clientMsgId)
            rows[outgoing.localOrdinal] = outgoing
            requestFingerprints[outgoing.localOrdinal] = requestFingerprint?.copyOf()
            storedBytes[outgoing.localOrdinal] =
                encoded.size.toLong() + (requestFingerprint?.size?.toLong() ?: 0L)
            if (publishProjection) {
                upsertProjection(canonical.copy(sendStatus = Message.SEND_STATUS_QUEUED))
            }
        }
    }

    fun get(chatId: String, clientMsgId: String, requestFingerprint: ByteArray?): OutgoingMessage? =
        synchronized(lock) {
            validateFingerprint(requestFingerprint)
            rows.values.firstOrNull {
                it.message.chatId == chatId && it.message.clientMsgId == clientMsgId
            }?.also { existing ->
                if (requestFingerprint != null) {
                    val stored = requestFingerprints[existing.localOrdinal]
                    if (stored == null || !stored.contentEquals(requestFingerprint)) conflict()
                }
            }
        }

    fun recoverState(now: Long) = synchronized(lock) {
        recoverLocked(now)
        Unit
    }

    fun recover(now: Long): List<OutgoingMessage> = synchronized(lock) {
        recoverLocked(now)
        rows.values.toList()
    }

    private fun recoverLocked(now: Long) {
        rows.entries.forEach { (ordinal, row) ->
            if (row.state == OutgoingMessageState.IN_FLIGHT) {
                val recovered = row.copy(
                    state = OutgoingMessageState.PENDING,
                    failureCode = OutgoingFailureCode.PROCESS_INTERRUPTED,
                    nextAttemptAt = 0L,
                    updatedAt = now,
                )
                rows[ordinal] = recovered
                projectionFailureCodes.remove(
                    recovered.message.chatId to recovered.message.clientMsgId,
                )
                updateProjectionStatus(recovered.message, Message.SEND_STATUS_QUEUED)
            }
        }
        pruneTerminalReceipts()
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
            failureCode = null,
            updatedAt = now,
        ).also { claimed ->
            rows[claimed.localOrdinal] = claimed
            projectionFailureCodes.remove(claimed.message.chatId to claimed.message.clientMsgId)
            updateProjectionStatus(claimed.message, Message.SEND_STATUS_SENDING)
        }
    }

    fun retry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
        failureCode: OutgoingFailureCode = OutgoingFailureCode.UNEXPECTED_FAILURE,
    ) = synchronized(lock) {
        val row = rows[localOrdinal]?.takeIf { it.state == OutgoingMessageState.IN_FLIGHT }
            ?: return@synchronized
        rows[localOrdinal] = row.copy(
            state = OutgoingMessageState.RETRY_WAIT,
            failureCode = failureCode,
            nextAttemptAt = nextAttemptAt,
            updatedAt = now,
        )
        projectionFailureCodes.remove(row.message.chatId to row.message.clientMsgId)
        updateProjectionStatus(row.message, Message.SEND_STATUS_QUEUED)
    }

    fun fail(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int? = null,
        failureCode: OutgoingFailureCode = OutgoingFailureCode.REMOTE_REJECTED,
    ) = synchronized(lock) {
        val row = rows[localOrdinal]?.takeIf { it.state == OutgoingMessageState.IN_FLIGHT }
            ?: return@synchronized
        val completedAt = nextCompletionTime(now)
        rows[localOrdinal] = row.copy(
            state = OutgoingMessageState.TERMINAL_FAILED,
            failureCode = failureCode,
            nextAttemptAt = 0L,
            updatedAt = now,
            terminalCode = terminalCode,
            completedAt = completedAt,
        )
        projectionFailureCodes[row.message.chatId to row.message.clientMsgId] = failureCode
        updateProjectionStatus(row.message, Message.SEND_STATUS_FAILED)
        pruneTerminalReceipts()
    }

    fun complete(localOrdinal: Long, ack: MessageAckPayload, now: Long = 0L) = synchronized(lock) {
        require(ack.code == 0 && ack.serverSeq > 0L)
        val row = rows[localOrdinal]?.takeIf { it.state == OutgoingMessageState.IN_FLIGHT }
            ?: return@synchronized
        require(row.message.clientMsgId == ack.clientMsgId)
        val completedAt = nextCompletionTime(now)
        rows[localOrdinal] = row.copy(
            state = OutgoingMessageState.SUCCESS,
            failureCode = null,
            nextAttemptAt = 0L,
            updatedAt = now,
            serverSeq = ack.serverSeq,
            terminalCode = null,
            completedAt = completedAt,
        )
        projectionFailureCodes.remove(row.message.chatId to row.message.clientMsgId)
        completeProjection(row.message, ack.serverSeq)
        pruneTerminalReceipts()
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
            failureCode = null,
            nextAttemptAt = 0L,
            updatedAt = now,
            serverSeq = message.serverSeq,
            terminalCode = null,
            completedAt = completedAt,
        )
        projectionFailureCodes.remove(message.chatId to message.clientMsgId)
        pruneTerminalReceipts()
    }

    fun cancel(reason: String, now: Long) = synchronized(lock) {
        val active = rows.values.filter { it.isActive() || it.state == OutgoingMessageState.IN_FLIGHT }
        val completedAt = if (active.isEmpty()) null else nextCompletionTime(now)
        rows.entries.forEach { (ordinal, row) ->
            if (row.isActive() || row.state == OutgoingMessageState.IN_FLIGHT) {
                rows[ordinal] = row.copy(
                    state = OutgoingMessageState.TERMINAL_FAILED,
                    failureCode = OutgoingFailureCode.SESSION_RETIRED,
                    nextAttemptAt = 0L,
                    updatedAt = now,
                    terminalCode = 499,
                    completedAt = completedAt,
                )
                projectionFailureCodes[row.message.chatId to row.message.clientMsgId] =
                    OutgoingFailureCode.SESSION_RETIRED
                updateProjectionStatus(row.message, Message.SEND_STATUS_FAILED)
            }
        }
        pruneTerminalReceipts()
    }

    fun snapshot(now: Long): OutgoingQueueSnapshot = synchronized(lock) {
        val active = rows.values.filter { it.occupiesActiveCapacity() }
        OutgoingQueueSnapshot(
            pendingOrInFlightCount = rows.values.count {
                it.state == OutgoingMessageState.PENDING || it.state == OutgoingMessageState.IN_FLIGHT
            }.toLong(),
            retryWaitCount = rows.values.count { it.state == OutgoingMessageState.RETRY_WAIT }.toLong(),
            terminalFailedCount = rows.values.count {
                it.state == OutgoingMessageState.TERMINAL_FAILED
            }.toLong(),
            oldestActiveAgeMs = active.minOfOrNull(OutgoingMessage::createdAt)?.let {
                (now - it).coerceAtLeast(0L)
            },
            maxAttemptCount = rows.values.filter { it.state != OutgoingMessageState.SUCCESS }
                .maxOfOrNull(OutgoingMessage::attemptCount) ?: 0L,
        )
    }

    fun canRecoverTerminalFailure(ownerUid: String, chatId: String, clientMsgId: String): Boolean =
        synchronized(lock) {
            val row = rows.values.firstOrNull {
                it.message.chatId == chatId && it.message.clientMsgId == clientMsgId
            }
            row == null ||
                (row.message.senderUid == ownerUid && row.state == OutgoingMessageState.TERMINAL_FAILED)
        }

    fun findFailureCode(chatId: String, clientMsgId: String): OutgoingFailureCode? =
        synchronized(lock) {
            rows.values.firstOrNull {
                it.message.chatId == chatId && it.message.clientMsgId == clientMsgId
            }?.failureCode ?: projectionFailureCodes[chatId to clientMsgId]
        }

    fun canReplaceTerminalFailure(ownerUid: String, chatId: String, clientMsgId: String): Boolean =
        synchronized(lock) {
            val projectionCode = projectionFailureCodes[chatId to clientMsgId]
                ?: return@synchronized false
            val row = rows.values.firstOrNull {
                it.message.chatId == chatId && it.message.clientMsgId == clientMsgId
            }
            val stableCode = if (row == null) {
                projectionCode
            } else if (
                row.message.senderUid == ownerUid &&
                row.state == OutgoingMessageState.TERMINAL_FAILED &&
                row.failureCode == projectionCode
            ) {
                projectionCode
            } else {
                return@synchronized false
            }
            stableCode.allowsFreshClientMsgIdReplacement
        }

    fun removeTerminalFailureReceipt(ownerUid: String, chatId: String, clientMsgId: String) =
        synchronized(lock) {
            val entry = rows.entries.firstOrNull { (_, row) ->
                row.message.chatId == chatId && row.message.clientMsgId == clientMsgId
            }
            if (entry != null) {
                check(
                    entry.value.message.senderUid == ownerUid &&
                        entry.value.state == OutgoingMessageState.TERMINAL_FAILED,
                ) { "outgoing receipt is not a recoverable terminal failure" }
                rows.remove(entry.key)
                requestFingerprints.remove(entry.key)
                storedBytes.remove(entry.key)
            }
            projectionFailureCodes.remove(chatId to clientMsgId)
        }

    fun deleteChat(chatId: String) = synchronized(lock) {
        val removed = rows.filterValues { it.message.chatId == chatId }.keys
        rows.keys.removeAll(removed)
        requestFingerprints.keys.removeAll(removed)
        storedBytes.keys.removeAll(removed)
        projectionFailureCodes.keys.removeAll { (storedChatId, _) -> storedChatId == chatId }
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

    private fun pruneTerminalReceipts() {
        val terminal = rows.values.filter {
            it.state == OutgoingMessageState.SUCCESS || it.state == OutgoingMessageState.TERMINAL_FAILED
        }.sortedWith(
            compareByDescending<OutgoingMessage> { it.completedAt ?: Long.MIN_VALUE }
                .thenByDescending(OutgoingMessage::localOrdinal),
        )
        val retained = mutableSetOf<Long>()
        var retainedBytes = 0L
        var retaining = true
        terminal.forEach { row ->
            val rowBytes = checkNotNull(storedBytes[row.localOrdinal])
            if (
                retaining &&
                retained.size < terminalReceiptLimit &&
                rowBytes <= MAX_TERMINAL_OUTGOING_STORED_BYTES - retainedBytes
            ) {
                retained += row.localOrdinal
                retainedBytes += rowBytes
            } else {
                retaining = false
            }
        }
        val removed = terminal.filter { it.localOrdinal !in retained }
            .mapTo(mutableSetOf(), OutgoingMessage::localOrdinal)
        rows.keys.removeAll(removed)
        requestFingerprints.keys.removeAll(removed)
        storedBytes.keys.removeAll(removed)
    }

    private fun admitActive(requestedBytes: Long) {
        val active = rows.values.filter { row -> row.occupiesActiveCapacity() }
        if (active.size >= MAX_ACTIVE_OUTGOING_MESSAGES) {
            capacityExceeded(LocalOutboxCapacityDimension.ENTRY_COUNT, MAX_ACTIVE_OUTGOING_MESSAGES.toLong())
        }
        val activeBytes = active.sumOf { row -> checkNotNull(storedBytes[row.localOrdinal]) }
        if (requestedBytes > MAX_ACTIVE_OUTGOING_STORED_BYTES - activeBytes) {
            capacityExceeded(LocalOutboxCapacityDimension.STORED_BYTES, MAX_ACTIVE_OUTGOING_STORED_BYTES)
        }
    }

    private fun OutgoingMessage.occupiesActiveCapacity(): Boolean =
        state == OutgoingMessageState.PENDING ||
            state == OutgoingMessageState.IN_FLIGHT ||
            state == OutgoingMessageState.RETRY_WAIT

    private fun validateFingerprint(fingerprint: ByteArray?) {
        require(fingerprint == null || fingerprint.size in 1..MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES) {
            "requestFingerprint must contain 1..$MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES bytes"
        }
    }

    private fun capacityExceeded(dimension: LocalOutboxCapacityDimension, limit: Long): Nothing =
        throw LocalOutboxCapacityExceededException(LocalOutboxKind.OUTGOING_MESSAGE, dimension, limit)

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
