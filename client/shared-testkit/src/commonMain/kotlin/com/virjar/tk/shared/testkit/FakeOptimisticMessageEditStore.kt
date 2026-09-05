package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.OptimisticMessageEditLease
import com.virjar.tk.protocol.model.Message

/** 内存版 LocalCache 测试替身所共用的精确乐观编辑租约行为。 */
internal class FakeOptimisticMessageEditStore(
    private val messages: MutableMap<String, MutableList<Message>>,
    private val publishFlow: (String) -> Unit,
) {
    private val owner = Any()
    private var nextToken = 0L
    private val pendingByToken = linkedMapOf<Long, PendingEdit>()
    private val tokenByMessage = mutableMapOf<Pair<String, String>, Long>()

    fun reserve(message: Message): OptimisticMessageEditLease? = synchronized(messages) {
        require(message.chatId.isNotBlank()) { "optimistic edit chatId must not be blank" }
        require(message.clientMsgId.isNotBlank()) { "optimistic edit clientMsgId must not be blank" }
        require(message.serverSeq > 0L) { "only confirmed messages can be edited" }
        val key = message.chatId to message.clientMsgId
        if (key in tokenByMessage) return@synchronized null
        val previous = messages[message.chatId]
            ?.firstOrNull { it.clientMsgId == message.clientMsgId }
            ?: return@synchronized null
        if (
            previous.serverSeq != message.serverSeq ||
            previous.senderUid != message.senderUid ||
            previous.timestamp != message.timestamp ||
            previous.flags and Message.FLAG_REVOKED != 0
        ) {
            return@synchronized null
        }
        val optimistic = previous.copy(
            messageType = message.messageType,
            body = message.body,
            flags = previous.flags or Message.FLAG_EDITED,
            uploadProgress = 0f,
        ).asFakeAuthoritativeProjection()
        check(pendingByToken.size < MAX_PENDING_EDITS) {
            "Too many concurrent optimistic message edits"
        }
        check(nextToken < Long.MAX_VALUE) { "optimistic message edit token exhausted" }
        val lease = Lease(owner = owner, tokenId = ++nextToken)
        pendingByToken[lease.tokenId] = PendingEdit(
            lease = lease,
            key = key,
            previous = previous,
            optimistic = optimistic,
        )
        check(tokenByMessage.put(key, lease.tokenId) == null)
        lease
    }

    fun publish(lease: OptimisticMessageEditLease): Boolean = synchronized(messages) {
        val pending = current(lease) ?: return@synchronized false
        if (pending.published || pending.superseded) return@synchronized false
        val list = messages[pending.key.first] ?: return@synchronized false
        val index = list.indexOfFirst { it.clientMsgId == pending.key.second }
        if (index < 0 || list[index] != pending.previous) {
            pending.superseded = true
            return@synchronized false
        }
        pending.published = true
        list[index] = pending.optimistic
        list.sortWith(fakeMessageOrder)
        publishFlow(pending.key.first)
        current(lease) === pending && !pending.superseded
    }

    fun commit(lease: OptimisticMessageEditLease): Boolean = synchronized(messages) {
        val pending = current(lease) ?: return@synchronized false
        remove(pending)
        true
    }

    fun rollback(lease: OptimisticMessageEditLease): Boolean = synchronized(messages) {
        val pending = current(lease) ?: return@synchronized false
        remove(pending)
        if (pending.published && !pending.superseded) {
            val list = messages[pending.key.first]
            val index = list?.indexOfFirst { it.clientMsgId == pending.key.second } ?: -1
            if (list != null && index >= 0 && list[index] == pending.optimistic) {
                list[index] = pending.previous
                list.sortWith(fakeMessageOrder)
                publishFlow(pending.key.first)
            }
        }
        true
    }

    fun supersede(chatId: String, clientMsgId: String) = synchronized(messages) {
        val token = tokenByMessage[chatId to clientMsgId] ?: return@synchronized
        pendingByToken[token]?.superseded = true
    }

    fun supersedeChat(chatId: String) = synchronized(messages) {
        pendingByToken.values.forEach { pending ->
            if (pending.key.first == chatId) pending.superseded = true
        }
    }

    fun supersedeAll() = synchronized(messages) {
        pendingByToken.values.forEach { it.superseded = true }
    }

    fun close() = synchronized(messages) {
        pendingByToken.clear()
        tokenByMessage.clear()
    }

    /** 调用方持有 [messages] 锁。 */
    private fun current(lease: OptimisticMessageEditLease): PendingEdit? {
        val owned = lease as? Lease ?: return null
        if (owned.owner !== owner) return null
        return pendingByToken[owned.tokenId]?.takeIf { it.lease === owned }
    }

    /** 调用方持有 [messages] 锁。 */
    private fun remove(pending: PendingEdit) {
        pendingByToken.remove(pending.lease.tokenId, pending)
        tokenByMessage.remove(pending.key, pending.lease.tokenId)
    }

    private data class PendingEdit(
        val lease: Lease,
        val key: Pair<String, String>,
        val previous: Message,
        val optimistic: Message,
        var published: Boolean = false,
        var superseded: Boolean = false,
    )

    private class Lease(
        val owner: Any,
        val tokenId: Long,
    ) : OptimisticMessageEditLease

    private companion object {
        const val MAX_PENDING_EDITS = 16
    }
}
