package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec

/**
 * 失败 outgoing 投影的持久准入加用户导向恢复。
 *
 * 拥有方 [LocalMessageStore] 提供其精确锁、持久化与常驻发布回调，因此 SQLite 变更保持相同的事务/
 * 加锁顺序，而这个有界关注点保持可独立审查。
 */
internal class LocalOutgoingRecoveryStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val admitActive: (payloadBytes: Long, fingerprintBytes: Long) -> Unit,
    private val persistMessage: (Message) -> Unit,
    private val supersedeOptimisticEdit: (chatId: String, clientMsgId: String) -> Unit,
    private val upsertResident: (Message) -> Unit,
    private val deleteResident: (chatId: String, clientMsgId: String) -> Unit,
    private val replaceResident: (chatId: String, clientMsgId: String, replacement: Message) -> Unit,
) {
    fun enqueue(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = cacheUseGate.use {
        val canonical = canonicalizeOutboundMessage(message)
        require(canonical.serverSeq == 0L) { "Only unacknowledged messages can enter the outbox" }
        validateOutgoingRequestFingerprint(requestFingerprint)
        val payload = ProtoCodec.encode(canonical)
        synchronized(stateLock) {
            lateinit var persisted: com.virjar.tk.shared.database.Outgoing_message
            var projection: Message? = null
            queries.transaction {
                val existing = queries.selectOutgoingMessageById(
                    canonical.chatId,
                    canonical.clientMsgId,
                ).executeAsOneOrNull()
                if (existing == null) {
                    val authoritative = queries.selectMessageById(
                        canonical.chatId,
                        canonical.clientMsgId,
                    ).executeAsOneOrNull()
                    if ((authoritative?.server_seq ?: 0L) > 0L) {
                        throw OutgoingMessageConflictException(
                            "clientMsgId already belongs to an authoritative server message",
                        )
                    }
                    admitActive(
                        payload.size.toLong(),
                        requestFingerprint?.size?.toLong() ?: 0L,
                    )
                    queries.enqueueOutgoingMessage(
                        canonical.clientMsgId,
                        canonical.chatId,
                        canonical.senderUid,
                        payload,
                        requestFingerprint,
                        now,
                        now,
                    )
                } else {
                    existing.requireSameOutgoingRequest(payload, requestFingerprint)
                }
                persisted = queries.selectOutgoingMessageById(
                    canonical.chatId,
                    canonical.clientMsgId,
                ).executeAsOne()
                // 同样关闭不太可能发生的多连接 INSERT OR IGNORE 竞争。
                persisted.requireSameOutgoingRequest(payload, requestFingerprint)
                val existingProjection = queries.selectMessageById(
                    canonical.chatId,
                    canonical.clientMsgId,
                ).executeAsOneOrNull()
                if (
                    persisted.state != OutgoingMessageState.SUCCESS.code &&
                    (existingProjection?.server_seq ?: 0L) == 0L
                ) {
                    projection = persisted.toProjectionMessage().also { restored ->
                        persistMessage(restored)
                        if (persisted.state == OutgoingMessageState.TERMINAL_FAILED.code) {
                            queries.updateMessageTerminalFailure(
                                requireNotNull(persisted.failure_code) {
                                    "Terminal outgoing receipt has no stable failure code"
                                },
                                persisted.chat_id,
                                persisted.client_msg_id,
                            )
                        }
                    }
                }
            }
            projection?.let(upsertResident)
            persisted.toLocalModel()
        }
    }

    fun get(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = cacheUseGate.use {
        validateOutgoingRequestFingerprint(requestFingerprint)
        synchronized(stateLock) {
            queries.selectOutgoingMessageById(chatId, clientMsgId).executeAsOneOrNull()?.also { row ->
                if (requestFingerprint != null) row.requireRequestFingerprint(requestFingerprint)
            }?.toLocalModel()
        }
    }

    fun findFailureCode(chatId: String, clientMsgId: String): OutgoingFailureCode? =
        cacheUseGate.use {
            synchronized(stateLock) {
                val receipt = queries.selectOutgoingMessageById(chatId, clientMsgId)
                    .executeAsOneOrNull()
                if (receipt != null) {
                    return@synchronized receipt.failure_code?.let(OutgoingFailureCode::fromStorageCode)
                }
                queries.selectMessageById(chatId, clientMsgId).executeAsOneOrNull()
                    ?.takeIf { projection ->
                        projection.server_seq == 0L &&
                            projection.send_status?.toInt() == Message.SEND_STATUS_FAILED
                    }
                    ?.outgoing_failure_code
                    ?.let(OutgoingFailureCode::fromStorageCode)
            }
        }

    /** 仅元数据聚合：该查询从不选择或 decode 规范载荷。 */
    fun snapshot(now: Long): OutgoingQueueSnapshot = cacheUseGate.use {
        synchronized(stateLock) {
            val row = queries.selectOutgoingQueueSnapshot().executeAsOne()
            OutgoingQueueSnapshot(
                pendingOrInFlightCount = row.pending_or_inflight_count,
                retryWaitCount = row.retry_wait_count,
                terminalFailedCount = row.terminal_failed_count,
                oldestActiveAgeMs = row.oldest_active_created_at?.let { createdAt ->
                    (now - createdAt).coerceAtLeast(0L)
                },
                maxAttemptCount = row.max_attempt_count,
            )
        }
    }

    fun discard(ownerUid: String, chatId: String, clientMsgId: String): Boolean = cacheUseGate.use {
        requireRecoveryIdentity(ownerUid, chatId, clientMsgId)
        synchronized(stateLock) {
            var discarded = false
            queries.transaction {
                val source = terminalFailureSourceLocked(ownerUid, chatId, clientMsgId)
                    ?: return@transaction
                source.receipt?.let { queries.deleteOutgoingMessage(it.local_ordinal) }
                queries.deleteFailedOptimisticMessage(chatId, clientMsgId, ownerUid)
                discarded = true
            }
            if (discarded) {
                supersedeOptimisticEdit(chatId, clientMsgId)
                deleteResident(chatId, clientMsgId)
            }
            discarded
        }
    }

    fun replace(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = cacheUseGate.use {
        requireRecoveryIdentity(ownerUid, chatId, clientMsgId)
        val canonical = canonicalizeOutboundMessage(replacement)
        require(canonical.chatId == chatId) { "replacement must stay in the failed message chat" }
        require(canonical.senderUid == ownerUid) { "replacement owner must match the session owner" }
        require(canonical.clientMsgId != clientMsgId) { "replacement must use a fresh clientMsgId" }
        require(canonical.serverSeq == 0L) { "replacement must be unacknowledged" }
        validateOutgoingRequestFingerprint(requestFingerprint)
        val payload = ProtoCodec.encode(canonical)
        synchronized(stateLock) {
            var replacementRow: com.virjar.tk.shared.database.Outgoing_message? = null
            var replacementProjection: Message? = null
            queries.transaction {
                val source = terminalFailureSourceLocked(ownerUid, chatId, clientMsgId)
                    ?: return@transaction
                if (source.failureCode?.allowsFreshClientMsgIdReplacement != true) {
                    return@transaction
                }
                if (
                    queries.selectOutgoingMessageById(chatId, canonical.clientMsgId)
                        .executeAsOneOrNull() != null ||
                    queries.selectMessageById(chatId, canonical.clientMsgId)
                        .executeAsOneOrNull() != null
                ) {
                    throw OutgoingMessageConflictException("replacement clientMsgId is not fresh")
                }
                admitActive(
                    payload.size.toLong(),
                    requestFingerprint?.size?.toLong() ?: 0L,
                )
                queries.insertFreshOutgoingReplacement(
                    canonical.clientMsgId,
                    canonical.chatId,
                    canonical.senderUid,
                    payload,
                    requestFingerprint,
                    now,
                    now,
                )
                val persistedReplacement = queries.selectOutgoingMessageById(
                    chatId,
                    canonical.clientMsgId,
                ).executeAsOne()
                persistedReplacement.requireSameOutgoingRequest(payload, requestFingerprint)
                replacementRow = persistedReplacement
                replacementProjection = persistedReplacement.toProjectionMessage().also(persistMessage)
                source.receipt?.let { queries.deleteOutgoingMessage(it.local_ordinal) }
                queries.deleteFailedOptimisticMessage(chatId, clientMsgId, ownerUid)
            }
            replacementProjection?.let { projection ->
                supersedeOptimisticEdit(chatId, clientMsgId)
                supersedeOptimisticEdit(chatId, projection.clientMsgId)
                replaceResident(chatId, clientMsgId, projection)
            }
            replacementRow?.toLocalModel()
        }
    }

    private fun requireRecoveryIdentity(ownerUid: String, chatId: String, clientMsgId: String) {
        require(ownerUid.isNotBlank()) { "recovery owner uid must not be blank" }
        require(chatId.isNotBlank()) { "recovery chatId must not be blank" }
        require(clientMsgId.isNotBlank()) { "recovery clientMsgId must not be blank" }
    }

    /** 调用方持有 [stateLock] 与外层事务。 */
    private fun terminalFailureSourceLocked(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
    ): TerminalFailureSource? {
        val projection = queries.selectMessageById(chatId, clientMsgId).executeAsOneOrNull()
            ?: return null
        if (
            projection.server_seq != 0L ||
            projection.sender_uid != ownerUid ||
            projection.send_status?.toInt() != Message.SEND_STATUS_FAILED
        ) {
            return null
        }
        val receipt = queries.selectOutgoingMessageById(chatId, clientMsgId).executeAsOneOrNull()
        if (
            receipt != null &&
            (receipt.sender_uid != ownerUid || receipt.state != OutgoingMessageState.TERMINAL_FAILED.code)
        ) {
            return null
        }
        val projectionFailureCode = projection.outgoing_failure_code
            ?.let(OutgoingFailureCode::fromStorageCode)
        val receiptFailureCode = receipt?.failure_code
            ?.let(OutgoingFailureCode::fromStorageCode)
        val replacementAuthority = if (receipt == null) {
            projectionFailureCode
        } else if (receiptFailureCode != null && receiptFailureCode == projectionFailureCode) {
            receiptFailureCode
        } else {
            null
        }
        return TerminalFailureSource(receipt, replacementAuthority)
    }
}

private data class TerminalFailureSource(
    val receipt: com.virjar.tk.shared.database.Outgoing_message?,
    val failureCode: OutgoingFailureCode?,
)
