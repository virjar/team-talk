package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Conversation

internal const val DRAFT_MIRROR_PENDING = 0L
internal const val DRAFT_MIRROR_ACKED = 1L

internal data class LocalDraftOverride(
    val draft: String?,
    val generation: Long,
    val state: Long,
    val observedAuthority: AuthoritativeDraftObservation? = null,
)

/** 区分“没有观察到事件”与权威的 null 草稿。 */
internal data class AuthoritativeDraftObservation(val draft: String?)

/** Highest requested read and the independently confirmed part of that same local intent. */
internal data class LocalConversationRead(val readSeq: Long, val validatedReadSeq: Long = 0L)

internal data class ConversationMergePlan(
    val conversation: Conversation,
    val draftOverride: LocalDraftOverride?,
    val clearDraftOverride: Boolean,
    val pendingRead: LocalConversationRead?,
)

/**
 * 完整会话快照与本地/事件变更之间的常量空间隔断。
 *
 * 只有最新发起、尚未应用且期间没有变更的快照可以提交。每次发起或变更都推进同一个代际；
 * 应用、变更和 reset 都关闭本次快照，不另存已经能够由这两个字段推导出的历史水位。
 *
 * owner 用 LocalCache 状态锁串行化每个方法。
 */
internal class ConversationSnapshotFence {
    private var projectionGeneration = 0L
    private var snapshotPending = false

    fun beginSnapshot(): Long {
        val generation = nextGeneration()
        snapshotPending = true
        return generation
    }

    fun canApply(snapshotGeneration: Long): Boolean {
        require(snapshotGeneration > 0L) { "snapshotGeneration must be positive" }
        return snapshotPending && snapshotGeneration == projectionGeneration
    }

    fun markApplied(snapshotGeneration: Long) {
        check(canApply(snapshotGeneration)) { "conversation snapshot is no longer current" }
        snapshotPending = false
    }

    fun markMutation() {
        nextGeneration()
        snapshotPending = false
    }

    /** 一次 reset 的应用代际隔断在 reset 之前准入的每一个响应。 */
    fun resetServerProjection() = markMutation()

    private fun nextGeneration(): Long {
        check(projectionGeneration < Long.MAX_VALUE) {
            "conversation projection generation exhausted"
        }
        projectionGeneration += 1L
        return projectionGeneration
    }
}

/** 纯合并接缝：待处理的本地事实从常驻的按 key 状态提供，绝不查询。 */
internal fun prepareConversationMerge(
    local: Conversation?,
    remote: Conversation,
    draftOverride: LocalDraftOverride?,
    pendingRead: LocalConversationRead?,
    confirmedMessageSeq: Long = 0L,
    authoritativeMessageHead: Long? = null,
): ConversationMergePlan {
    // 记录在当前本地代际创建之后观察到的最新权威。
    val observedOverride = draftOverride?.let { override ->
        if (override.state == DRAFT_MIRROR_PENDING) {
            override.copy(observedAuthority = AuthoritativeDraftObservation(remote.draft))
        } else {
            override
        }
    }
    // RPC 成功本身不是投影收敛。保留该覆盖，直到其匹配的权威 Conversation 快照也已被消费。
    val clearAcknowledgedOverride = observedOverride?.let { override ->
        override.state == DRAFT_MIRROR_ACKED && override.draft == remote.draft
    } == true
    val effectiveOverride = observedOverride.takeUnless { clearAcknowledgedOverride }
    val incoming = remote.copy(
        draft = if (effectiveOverride != null) effectiveOverride.draft else remote.draft,
    )
    // PG conversation snapshots can lag RocksDB history even after a checkpoint. Local evidence
    // proves a read possible; only the exact chat's newly fetched message head disproves old data.
    val confirmedLastSeq = maxOf(local?.lastSeq ?: 0L, remote.lastSeq, confirmedMessageSeq,
        pendingRead?.validatedReadSeq ?: 0L)
    val upperBound = authoritativeMessageHead?.let { maxOf(it, confirmedLastSeq) }
    val invalidLocalRead = upperBound != null && local != null && local.readSeq > upperBound
    val invalidPendingRead = upperBound != null && pendingRead != null && pendingRead.readSeq > upperBound
    val pendingSeq = if (invalidPendingRead) pendingRead?.validatedReadSeq?.takeIf { it > 0L }
        else pendingRead?.readSeq
    val nextPendingRead = pendingSeq?.let { seq ->
        LocalConversationRead(seq, if (seq <= (upperBound ?: confirmedLastSeq)) seq
            else pendingRead?.validatedReadSeq ?: 0L)
    }
    val validLocal = if (invalidLocalRead) local?.copy(readSeq = remote.readSeq) else local
    val merged = if (validLocal == null) incoming else mergeConversation(validLocal, incoming, effectiveOverride)
    val withPendingRead = nextPendingRead?.let { applyConversationRead(merged, it.readSeq) } ?: merged
    return ConversationMergePlan(
        conversation = withPendingRead,
        draftOverride = effectiveOverride,
        clearDraftOverride = clearAcknowledgedOverride,
        pendingRead = nextPendingRead,
    )
}

/**
 * 检查点拥有会话计数器与内容，但其身份快照可能落后于本会话中已经安装的 USER_UPDATED/
 * list-conversations 事实。只保留版本化的对端元组；每个无关字段仍归检查点所有。
 */
internal fun preserveNewerConversationIdentity(
    local: Conversation?,
    checkpoint: Conversation,
): Conversation {
    val localRevision = local?.peerRevision ?: return checkpoint
    val checkpointRevision = checkpoint.peerRevision ?: return checkpoint
    if (
        local.chatType != ChatType.PERSONAL.code ||
        checkpoint.chatType != ChatType.PERSONAL.code ||
        localRevision <= checkpointRevision
    ) {
        return checkpoint
    }
    return checkpoint.copy(
        peerUid = local.peerUid,
        peerRevision = local.peerRevision,
        chatName = local.chatName,
        chatAvatar = local.chatAvatar,
    )
}

internal fun sortConversations(conversations: Collection<Conversation>): List<Conversation> =
    conversations.sortedWith(
        compareByDescending<Conversation> { it.isPinned }
            .thenByDescending { it.lastMsgTimestamp ?: 0L }
            .thenBy(Conversation::chatId),
    )

internal fun applyConversationRead(conversation: Conversation, readSeq: Long): Conversation {
    val mergedReadSeq = maxOf(conversation.readSeq, readSeq)
    val mergedUnread = (conversation.lastSeq - mergedReadSeq)
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
    return conversation.copy(unreadCount = mergedUnread, readSeq = mergedReadSeq)
}

private fun mergeConversation(
    local: Conversation,
    remote: Conversation,
    draftOverride: LocalDraftOverride?,
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
