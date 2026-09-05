package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.model.Conversation

internal data class DraftObservation(val draft: String?)

internal data class DraftOverride(
    val draft: String?,
    val generation: Long,
    val mirrored: Boolean,
    val observedAuthority: DraftObservation? = null,
)

internal data class ConversationMergePlan(
    val conversation: Conversation,
    val draftOverride: DraftOverride?,
    val clearDraftOverride: Boolean,
)

internal fun prepareFakeConversationMerge(
    local: Conversation?,
    remote: Conversation,
    draftOverride: DraftOverride?,
    pendingReadSeq: Long? = null,
): ConversationMergePlan {
    val observedOverride = draftOverride?.let { override ->
        if (!override.mirrored) {
            override.copy(observedAuthority = DraftObservation(remote.draft))
        } else {
            override
        }
    }
    val clearOverride = observedOverride?.let { it.mirrored && it.draft == remote.draft } == true
    val effectiveOverride = observedOverride.takeUnless { clearOverride }
    val incoming = remote.copy(
        draft = if (effectiveOverride != null) effectiveOverride.draft else remote.draft,
    )
    val merged = if (local == null) incoming else mergeFakeConversation(local, incoming, effectiveOverride)
    val withPendingRead = pendingReadSeq?.let { applyFakeConversationRead(merged, it) } ?: merged
    return ConversationMergePlan(withPendingRead, effectiveOverride, clearOverride)
}

internal fun replaceFakeConversationSorted(
    current: List<Conversation>,
    conversation: Conversation,
): List<Conversation> {
    val result = current.toMutableList()
    val index = result.indexOfFirst { it.chatId == conversation.chatId }
    if (index >= 0) result[index] = conversation else result.add(conversation)
    return sortFakeConversations(result)
}

internal fun sortFakeConversations(conversations: List<Conversation>): List<Conversation> =
    conversations.sortedWith(
        compareByDescending<Conversation> { it.isPinned }
            .thenByDescending { it.lastMsgTimestamp ?: 0L },
    )

private fun mergeFakeConversation(
    local: Conversation,
    remote: Conversation,
    draftOverride: DraftOverride?,
): Conversation {
    val mergedReadSeq = maxOf(local.readSeq, remote.readSeq)
    val latestMessage = if (remote.lastSeq >= local.lastSeq) remote else local
    return remote.copy(
        lastMessage = latestMessage.lastMessage,
        lastMessageType = latestMessage.lastMessageType,
        lastMsgTimestamp = latestMessage.lastMsgTimestamp,
        lastSeq = latestMessage.lastSeq,
        readSeq = mergedReadSeq,
        unreadCount = (latestMessage.lastSeq - mergedReadSeq)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt(),
        peerReadSeq = maxOf(local.peerReadSeq, remote.peerReadSeq),
        draft = if (draftOverride != null) draftOverride.draft else remote.draft,
    )
}

private fun applyFakeConversationRead(conversation: Conversation, readSeq: Long): Conversation {
    val mergedReadSeq = maxOf(conversation.readSeq, readSeq)
    return conversation.copy(
        readSeq = mergedReadSeq,
        unreadCount = (conversation.lastSeq - mergedReadSeq)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt(),
    )
}
