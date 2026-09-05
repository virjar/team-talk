package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.PendingConversationDraft
import com.virjar.tk.shared.client.PendingConversationRead
import com.virjar.tk.shared.client.LocalOutboxCapacityDimension
import com.virjar.tk.shared.client.LocalOutboxCapacityExceededException
import com.virjar.tk.shared.client.LocalOutboxKind
import com.virjar.tk.shared.client.MAX_CONVERSATION_DRAFT_CHARACTERS
import com.virjar.tk.shared.client.MAX_CONVERSATION_DRAFT_OUTBOX_ENTRIES
import com.virjar.tk.shared.client.MAX_CONVERSATION_DRAFT_UTF8_BYTES
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [FakeLocalCache] 的会话投影与本地草稿/已读事实。
 *
 * 传入的锁与测试替身聊天投影共享，因为聊天墓穴与聊天快照刻意在同一边界上对会话状态设门禁。
 * 草稿/已读可靠发件箱在服务器投影重置后依然保留，与生产缓存的本地可靠事实契约保持一致。
 */
internal class FakeConversationProjectionStore(
    private val lock: Any,
) {
    private val projection = MutableStateFlow<List<Conversation>>(emptyList())
    private var projectionGeneration = 0L
    private var lastAppliedSnapshotGeneration = 0L
    private val mutationGenerations = mutableMapOf<String, Long>()
    private val draftOverrides = mutableMapOf<String, DraftOverride>()
    private var draftCharacterCount = 0L
    private var draftUtf8ByteCount = 0L
    private val draftGenerationHighWatermarks = mutableMapOf<String, Long>()
    private val reads = FakeConversationReadState(
        conversations = projection,
        onProjectionChanged = ::markMutatedLocked,
    )

    fun get(): List<Conversation> = synchronized(lock) { projection.value }

    fun observe(): Flow<List<Conversation>> = projection

    fun upsert(conversation: Conversation) = synchronized(lock) {
        val plan = prepareFakeConversationMerge(
            local = projection.value.firstOrNull { it.chatId == conversation.chatId },
            remote = conversation,
            draftOverride = draftOverrides[conversation.chatId],
            pendingReadSeq = reads.watermark(conversation.chatId),
        )
        if (plan.clearDraftOverride) {
            replaceDraftOverride(conversation.chatId, null)
        } else if (plan.draftOverride != null) {
            replaceDraftOverride(conversation.chatId, plan.draftOverride)
        }
        projection.value = replaceFakeConversationSorted(projection.value, plan.conversation)
        markMutatedLocked(conversation.chatId)
    }

    fun beginSnapshot(): Long = synchronized(lock) { nextGenerationLocked() }

    fun applySnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean {
        require(snapshotGeneration > 0L) { "snapshotGeneration must be positive" }
        val snapshot = conversations.associateBy(Conversation::chatId).values.toList()
        return synchronized(lock) {
            if (snapshotGeneration <= lastAppliedSnapshotGeneration) return@synchronized false

            var projectedConversations = projection.value
            val projectedOverrides = draftOverrides.toMutableMap()
            var hadConflict = mutationGenerations.values.any { generation ->
                generation > snapshotGeneration
            }
            snapshot.forEach { remote ->
                if (wasMutatedAfterLocked(remote.chatId, snapshotGeneration)) {
                    hadConflict = true
                } else {
                    val plan = prepareFakeConversationMerge(
                        local = projectedConversations.firstOrNull { it.chatId == remote.chatId },
                        remote = remote,
                        draftOverride = projectedOverrides[remote.chatId],
                        pendingReadSeq = reads.watermark(remote.chatId),
                    )
                    projectedConversations = replaceFakeConversationSorted(
                        projectedConversations,
                        plan.conversation,
                    )
                    if (plan.clearDraftOverride) {
                        projectedOverrides.remove(remote.chatId)
                    } else if (plan.draftOverride != null) {
                        projectedOverrides[remote.chatId] = plan.draftOverride
                    }
                }
            }

            val remoteIds = snapshot.mapTo(mutableSetOf(), Conversation::chatId)
            val absentIds = buildSet {
                projectedConversations.forEach { if (it.chatId !in remoteIds) add(it.chatId) }
                projectedOverrides.keys.forEach { if (it !in remoteIds) add(it) }
                reads.chatIds().forEach { if (it !in remoteIds) add(it) }
            }
            val removableIds = absentIds.filterTo(mutableSetOf()) { chatId ->
                val safeToRemove = !wasMutatedAfterLocked(chatId, snapshotGeneration)
                if (!safeToRemove) hadConflict = true
                safeToRemove
            }

            projectedConversations = projectedConversations.filterNot { it.chatId in removableIds }
            removableIds.forEach { projectedOverrides.remove(it) }
            removableIds.forEach(reads::remove)
            replaceAllDraftOverrides(projectedOverrides)
            projection.value = sortFakeConversations(projectedConversations)
            projectionGeneration = maxOf(projectionGeneration, snapshotGeneration)
            lastAppliedSnapshotGeneration = snapshotGeneration
            mutationGenerations.entries.removeAll { (_, generation) ->
                generation <= snapshotGeneration
            }
            !hadConflict
        }
    }

    fun setDraft(chatId: String, draft: String?): Long = synchronized(lock) {
        admitDraft(chatId, draft)
        val generation = (draftGenerationHighWatermarks[chatId] ?: 0L) + 1L
        draftGenerationHighWatermarks[chatId] = generation
        replaceDraftOverride(chatId, DraftOverride(draft, generation, mirrored = false))
        projection.value = projection.value.map {
            if (it.chatId == chatId) it.copy(draft = draft) else it
        }
        markMutatedLocked(chatId)
        generation
    }

    fun pendingDrafts(): List<PendingConversationDraft> = synchronized(lock) {
        draftOverrides.mapNotNull { (chatId, override) ->
            if (!override.mirrored) {
                PendingConversationDraft(chatId, override.draft, override.generation)
            } else {
                null
            }
        }
    }

    fun pendingDraft(chatId: String): PendingConversationDraft? = synchronized(lock) {
        draftOverrides[chatId]?.takeIf { !it.mirrored }?.let { override ->
            PendingConversationDraft(chatId, override.draft, override.generation)
        }
    }

    fun markDraftMirrored(chatId: String, generation: Long) = synchronized(lock) draft@{
        val current = draftOverrides[chatId] ?: return@draft
        if (current.generation == generation && !current.mirrored) {
            if (current.observedAuthority?.draft == current.draft && current.observedAuthority != null) {
                replaceDraftOverride(chatId, null)
            } else {
                replaceDraftOverride(chatId, current.copy(mirrored = true))
            }
        }
    }

    fun delete(chatId: String) = synchronized(lock) { deleteLocked(chatId) }

    /** 调用方持有 [lock]，作为跨投影聊天墓穴事务的一部分。 */
    fun deleteForChatTombstoneLocked(chatId: String) = deleteLocked(chatId)

    fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) = synchronized(lock) update@{
        val existing = projection.value.firstOrNull { it.chatId == chatId } ?: return@update
        val mergedPeerReadSeq = maxOf(existing.peerReadSeq, peerReadSeq)
        if (mergedPeerReadSeq == existing.peerReadSeq) return@update
        projection.value = projection.value.map {
            if (it.chatId == chatId) it.copy(peerReadSeq = mergedPeerReadSeq) else it
        }
        markMutatedLocked(chatId)
    }

    fun enqueueRead(chatId: String, readSeq: Long): Long = synchronized(lock) {
        reads.enqueue(chatId, readSeq)
    }

    fun pendingReads(): List<PendingConversationRead> = synchronized(lock) { reads.pending() }

    fun pendingRead(chatId: String): PendingConversationRead? = synchronized(lock) { reads.pending(chatId) }

    fun markReadMirrored(chatId: String, readSeq: Long) = synchronized(lock) {
        reads.acknowledge(chatId, readSeq)
    }

    /** 调用方持有 [lock]。聊天投影的变更会对较早的会话列表快照设门禁。 */
    fun markMutatedLocked(chatId: String) {
        mutationGenerations[chatId] = nextGenerationLocked()
    }

    /**
     * 调用方持有 [lock]。仅清除服务器拥有的行；草稿/已读可靠事实与草稿
     * 代次高水位保留，用于在下一个权威快照之上重放。
     */
    fun resetServerProjectionLocked() {
        projection.value = emptyList()
        lastAppliedSnapshotGeneration = nextGenerationLocked()
        mutationGenerations.clear()
    }

    /** 调用方持有 [lock]。仅替换服务器行，同时保留草稿/已读本地事实。 */
    fun applyServerCheckpointLocked(conversations: List<Conversation>) {
        val projectedOverrides = draftOverrides.toMutableMap()
        val projected = conversations.map { remote ->
            val checkpointConversation = preserveNewerFakeConversationIdentity(
                local = projection.value.firstOrNull { it.chatId == remote.chatId },
                checkpoint = remote,
            )
            val plan = prepareFakeConversationMerge(
                local = null,
                remote = checkpointConversation,
                draftOverride = projectedOverrides[remote.chatId],
                pendingReadSeq = reads.watermark(remote.chatId),
            )
            if (plan.clearDraftOverride) {
                projectedOverrides.remove(remote.chatId)
            } else if (plan.draftOverride != null) {
                projectedOverrides[remote.chatId] = plan.draftOverride
            }
            plan.conversation
        }
        replaceAllDraftOverrides(projectedOverrides)
        projection.value = sortFakeConversations(projected)
        lastAppliedSnapshotGeneration = nextGenerationLocked()
        mutationGenerations.clear()
    }

    /** 调用方持有 [lock]。 */
    private fun deleteLocked(chatId: String) {
        replaceDraftOverride(chatId, null)
        reads.remove(chatId)
        projection.value = projection.value.filter { it.chatId != chatId }
        markMutatedLocked(chatId)
        // 保留草稿代次高水位，使过期的 ACK 无法占用更晚的草稿。
    }

    /** 调用方持有 [lock]。 */
    private fun nextGenerationLocked(): Long {
        check(projectionGeneration < Long.MAX_VALUE) {
            "conversation projection generation exhausted"
        }
        projectionGeneration += 1L
        return projectionGeneration
    }

    /** 调用方持有 [lock]。 */
    private fun wasMutatedAfterLocked(chatId: String, generation: Long): Boolean =
        (mutationGenerations[chatId] ?: 0L) > generation

    private fun admitDraft(chatId: String, draft: String?) {
        if (
            chatId !in draftOverrides &&
            draftOverrides.size >= MAX_CONVERSATION_DRAFT_OUTBOX_ENTRIES
        ) {
            capacityExceeded(
                LocalOutboxCapacityDimension.ENTRY_COUNT,
                MAX_CONVERSATION_DRAFT_OUTBOX_ENTRIES.toLong(),
            )
        }
        val old = draftOverrides[chatId]?.let { fakeDraftStorage(it.draft) } ?: FAKE_EMPTY_DRAFT_STORAGE
        val replacement = fakeDraftStorage(draft)
        if (draftCharacterCount - old.characters + replacement.characters > MAX_CONVERSATION_DRAFT_CHARACTERS) {
            capacityExceeded(
                LocalOutboxCapacityDimension.CHARACTER_COUNT,
                MAX_CONVERSATION_DRAFT_CHARACTERS,
            )
        }
        if (draftUtf8ByteCount - old.utf8Bytes + replacement.utf8Bytes > MAX_CONVERSATION_DRAFT_UTF8_BYTES) {
            capacityExceeded(
                LocalOutboxCapacityDimension.STORED_BYTES,
                MAX_CONVERSATION_DRAFT_UTF8_BYTES,
            )
        }
    }

    private fun replaceDraftOverride(chatId: String, replacement: DraftOverride?) {
        val old = draftOverrides[chatId]?.let { fakeDraftStorage(it.draft) } ?: FAKE_EMPTY_DRAFT_STORAGE
        val next = replacement?.let { fakeDraftStorage(it.draft) } ?: FAKE_EMPTY_DRAFT_STORAGE
        draftCharacterCount = draftCharacterCount - old.characters + next.characters
        draftUtf8ByteCount = draftUtf8ByteCount - old.utf8Bytes + next.utf8Bytes
        if (replacement == null) draftOverrides.remove(chatId) else draftOverrides[chatId] = replacement
    }

    private fun replaceAllDraftOverrides(replacements: Map<String, DraftOverride>) {
        draftOverrides.clear()
        draftOverrides.putAll(replacements)
        draftCharacterCount = 0L
        draftUtf8ByteCount = 0L
        replacements.values.forEach { override ->
            val storage = fakeDraftStorage(override.draft)
            draftCharacterCount += storage.characters
            draftUtf8ByteCount += storage.utf8Bytes
        }
    }

    private fun capacityExceeded(dimension: LocalOutboxCapacityDimension, limit: Long): Nothing =
        throw LocalOutboxCapacityExceededException(LocalOutboxKind.CONVERSATION_DRAFT, dimension, limit)
}

private fun preserveNewerFakeConversationIdentity(
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

private data class FakeDraftStorage(val characters: Long, val utf8Bytes: Long)

private val FAKE_EMPTY_DRAFT_STORAGE = FakeDraftStorage(0L, 0L)

private fun fakeDraftStorage(draft: String?): FakeDraftStorage = if (draft == null) {
    FAKE_EMPTY_DRAFT_STORAGE
} else {
    FakeDraftStorage(draft.length.toLong(), draft.encodeToByteArray().size.toLong())
}
