package com.virjar.tk.testing

import com.virjar.tk.client.OutgoingMessage
import com.virjar.tk.client.OutgoingMessageConflictException
import com.virjar.tk.model.Message

internal fun Message.asFakeAuthoritativeProjection(): Message =
    if (serverSeq > 0L && sendStatus != Message.SEND_STATUS_SENT) {
        copy(sendStatus = Message.SEND_STATUS_SENT)
    } else {
        this
    }

internal fun upsertFakeInboundMessage(
    messagesByChat: MutableMap<String, MutableList<Message>>,
    outgoingStore: FakeOutgoingMessageStore,
    message: Message,
    onChatChanged: (String) -> Unit,
) = synchronized(messagesByChat) {
    val projection = message.asFakeAuthoritativeProjection()
    val messages = messagesByChat.getOrPut(projection.chatId) { mutableListOf() }
    val index = messages.indexOfFirst { it.clientMsgId == projection.clientMsgId }
    if (projection.serverSeq == 0L && index >= 0 && messages[index].serverSeq > 0L) {
        throw OutgoingMessageConflictException(
            "local message cannot replace an authoritative server projection",
        )
    }
    if (index >= 0) messages[index] = projection else messages.add(projection)
    messages.sortWith(fakeMessageOrder)
    outgoingStore.promoteFromAuthority(projection, System.currentTimeMillis())
    onChatChanged(projection.chatId)
}

internal fun enqueueFakeOutgoingMessage(
    messagesByChat: MutableMap<String, MutableList<Message>>,
    outgoingStore: FakeOutgoingMessageStore,
    message: Message,
    now: Long,
    requestFingerprint: ByteArray?,
): OutgoingMessage = synchronized(messagesByChat) {
    val retained = outgoingStore.get(message.chatId, message.clientMsgId, null)
    val authority = messagesByChat[message.chatId]
        ?.firstOrNull { it.clientMsgId == message.clientMsgId && it.serverSeq > 0L }
    if (retained == null && authority != null) {
        throw OutgoingMessageConflictException(
            "clientMsgId already belongs to an authoritative server message",
        )
    }
    outgoingStore.enqueue(message, now, requestFingerprint)
}

internal fun applyFakeHistoryProjection(
    messagesByChat: MutableMap<String, MutableList<Message>>,
    outgoingStore: FakeOutgoingMessageStore,
    chatId: String,
    messages: List<Message>,
    resetResidentWindow: Boolean,
    onChatChanged: (String) -> Unit,
) {
    val page = messages.map { it.asFakeAuthoritativeProjection() }
    page.forEach { require(it.chatId == chatId) { "history page contains another chat: ${it.chatId}" } }
    synchronized(messagesByChat) {
        val current = messagesByChat[chatId]?.toList() ?: emptyList()
        val pageMaxSeq = page.asSequence().map(Message::serverSeq).filter { it > 0L }.maxOrNull()
        val base = if (resetResidentWindow) {
            current.filter { it.serverSeq <= 0L || (pageMaxSeq != null && it.serverSeq > pageMaxSeq) }
        } else {
            current
        }
        val merged = LinkedHashMap<String, Message>(base.size + page.size)
        base.forEach { merged[it.clientMsgId] = it }
        page.forEach { merged[it.clientMsgId] = it }
        messagesByChat[chatId] = merged.values.sortedWith(fakeMessageOrder).toMutableList()
        val now = System.currentTimeMillis()
        page.forEach { outgoingStore.promoteFromAuthority(it, now) }
        onChatChanged(chatId)
    }
}

internal fun reconcileFakeAuthoritativeOutgoing(
    messagesByChat: MutableMap<String, MutableList<Message>>,
    outgoingStore: FakeOutgoingMessageStore,
    now: Long,
) = synchronized(messagesByChat) {
    messagesByChat.values.asSequence().flatten().filter { it.serverSeq > 0L }.forEach {
        outgoingStore.promoteFromAuthority(it.asFakeAuthoritativeProjection(), now)
    }
}

/** Mirrors the epoch-3 startup repair for optimistic projections which have no durable outbox. */
internal fun failFakeOrphanedMessages(
    messagesByChat: MutableMap<String, MutableList<Message>>,
    outboxKeys: Set<Pair<String, String>>,
    onChatChanged: (String) -> Unit,
) {
    messagesByChat.forEach { (chatId, messages) ->
        var changed = false
        messages.indices.forEach { index ->
            val message = messages[index]
            val orphanedStatus = message.sendStatus == Message.SEND_STATUS_SENDING ||
                message.sendStatus == Message.SEND_STATUS_UPLOADING ||
                message.sendStatus == Message.SEND_STATUS_QUEUED
            if (message.serverSeq == 0L && orphanedStatus &&
                (chatId to message.clientMsgId) !in outboxKeys
            ) {
                messages[index] = message.copy(sendStatus = Message.SEND_STATUS_FAILED)
                changed = true
            }
        }
        if (changed) onChatChanged(chatId)
    }
}
