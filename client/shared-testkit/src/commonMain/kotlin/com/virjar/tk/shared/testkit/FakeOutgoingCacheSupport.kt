package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.OutgoingFailureCode
import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.OutgoingMessageConflictException
import com.virjar.tk.shared.client.OutgoingQueueSnapshot
import com.virjar.tk.shared.client.canonicalizeOutboundMessage
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload

/** [FakeLocalCache] 的可靠发件箱/恢复切片，共享其完全一致的消息 map 监视器锁。 */
internal class FakeOutgoingCacheSupport(
    private val cacheUseGate: FakeCacheUseGate,
    private val messagesMap: MutableMap<String, MutableList<Message>>,
    private val outgoingStore: FakeOutgoingMessageStore,
    private val optimisticMessageEdits: FakeOptimisticMessageEditStore,
    private val onChatChanged: (String) -> Unit,
) {
    fun enqueue(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = cacheUseGate.use {
        enqueueFakeOutgoingMessage(messagesMap, outgoingStore, message, now, requestFingerprint)
    }

    fun get(chatId: String, clientMsgId: String, requestFingerprint: ByteArray?): OutgoingMessage? =
        cacheUseGate.use { outgoingStore.get(chatId, clientMsgId, requestFingerprint) }

    fun findFailureCode(chatId: String, clientMsgId: String): OutgoingFailureCode? =
        cacheUseGate.use { outgoingStore.findFailureCode(chatId, clientMsgId) }

    fun snapshot(now: Long): OutgoingQueueSnapshot = cacheUseGate.use { outgoingStore.snapshot(now) }

    fun discard(ownerUid: String, chatId: String, clientMsgId: String): Boolean = cacheUseGate.use {
        requireRecoveryIdentity(ownerUid, chatId, clientMsgId)
        synchronized(messagesMap) {
            val messages = messagesMap[chatId] ?: return@synchronized false
            val source = messages.firstOrNull { it.clientMsgId == clientMsgId }
                ?: return@synchronized false
            if (
                source.serverSeq != 0L || source.senderUid != ownerUid ||
                source.sendStatus != Message.SEND_STATUS_FAILED ||
                !outgoingStore.canRecoverTerminalFailure(ownerUid, chatId, clientMsgId)
            ) {
                return@synchronized false
            }
            outgoingStore.removeTerminalFailureReceipt(ownerUid, chatId, clientMsgId)
            messages.removeAll { it.clientMsgId == clientMsgId }
            optimisticMessageEdits.supersede(chatId, clientMsgId)
            onChatChanged(chatId)
            true
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
        synchronized(messagesMap) {
            val messages = messagesMap[chatId] ?: return@synchronized null
            val source = messages.firstOrNull { it.clientMsgId == clientMsgId }
                ?: return@synchronized null
            if (
                source.serverSeq != 0L || source.senderUid != ownerUid ||
                source.sendStatus != Message.SEND_STATUS_FAILED ||
                !outgoingStore.canReplaceTerminalFailure(ownerUid, chatId, clientMsgId)
            ) {
                return@synchronized null
            }
            if (
                messages.any { it.clientMsgId == canonical.clientMsgId } ||
                outgoingStore.get(chatId, canonical.clientMsgId, null) != null
            ) {
                throw OutgoingMessageConflictException("replacement clientMsgId is not fresh")
            }
            val admitted = outgoingStore.enqueue(
                canonical,
                now,
                requestFingerprint,
                publishProjection = false,
            )
            outgoingStore.removeTerminalFailureReceipt(ownerUid, chatId, clientMsgId)
            messages.removeAll {
                it.clientMsgId == clientMsgId || it.clientMsgId == canonical.clientMsgId
            }
            messages += admitted.message.copy(sendStatus = Message.SEND_STATUS_QUEUED)
            messages.sortWith(fakeMessageOrder)
            optimisticMessageEdits.supersede(chatId, clientMsgId)
            optimisticMessageEdits.supersede(chatId, canonical.clientMsgId)
            onChatChanged(chatId)
            admitted
        }
    }

    fun recoverMessages(now: Long): List<OutgoingMessage> = cacheUseGate.use {
        synchronized(messagesMap) {
            repairProjection(now)
            outgoingStore.recover(now)
        }
    }

    fun recoverState(now: Long) = cacheUseGate.use {
        synchronized(messagesMap) {
            repairProjection(now)
            outgoingStore.recoverState(now)
        }
    }

    fun peek(): OutgoingMessage? = cacheUseGate.use { outgoingStore.peek() }

    fun claim(now: Long): OutgoingMessage? = cacheUseGate.use { outgoingStore.claim(now) }

    fun retry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
        failureCode: OutgoingFailureCode,
    ) = cacheUseGate.use {
        outgoingStore.retry(localOrdinal, error, nextAttemptAt, now, failureCode)
    }

    fun fail(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int?,
        failureCode: OutgoingFailureCode,
    ) = cacheUseGate.use {
        outgoingStore.fail(localOrdinal, error, now, terminalCode, failureCode)
    }

    fun complete(localOrdinal: Long, ack: MessageAckPayload, now: Long) = cacheUseGate.use {
        outgoingStore.complete(localOrdinal, ack, now)
    }

    fun cancel(reason: String, now: Long) = cacheUseGate.use { outgoingStore.cancel(reason, now) }

    /** 镜像真实的基于集合的重置：原地保留被回执 GC 回收的失败投影。 */
    fun projectionAfterReset(): List<Message> = cacheUseGate.use {
        synchronized(messagesMap) {
            val outboxKeys = outgoingStore.projectionKeys()
            val retained = linkedMapOf<Pair<String, String>, Message>()
            messagesMap.values.asSequence().flatten()
                .filter { message ->
                    message.serverSeq == 0L &&
                        message.sendStatus == Message.SEND_STATUS_FAILED &&
                        (message.chatId to message.clientMsgId) !in outboxKeys
                }
                .forEach { message -> retained[message.chatId to message.clientMsgId] = message }
            outgoingStore.projectionAfterReset().forEach { message ->
                retained[message.chatId to message.clientMsgId] = message
            }
            retained.values.toList()
        }
    }

    /** 调用方持有 [messagesMap] 锁。 */
    private fun repairProjection(now: Long) {
        failFakeOrphanedMessages(messagesMap, outgoingStore.projectionKeys(), onChatChanged)
        reconcileFakeAuthoritativeOutgoing(messagesMap, outgoingStore, now)
    }

    private fun requireRecoveryIdentity(ownerUid: String, chatId: String, clientMsgId: String) {
        require(ownerUid.isNotBlank()) { "recovery owner uid must not be blank" }
        require(chatId.isNotBlank()) { "recovery chatId must not be blank" }
        require(clientMsgId.isNotBlank()) { "recovery clientMsgId must not be blank" }
    }
}
