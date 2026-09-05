package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Conversation
import kotlinx.coroutines.flow.Flow

internal data class ServerCheckpointConversationPlan(
    val conversations: LinkedHashMap<String, Conversation>,
    val draftOverrides: LinkedHashMap<String, LocalDraftOverride>,
    val clearedDraftChatIds: Set<String>,
)

/**
 * 会话拥有的 conversation 投影与持久本地镜像（草稿与已读水位）。
 *
 * 完整会话列表刻意保持常驻，但其权威可变形式按 chatId 为 key。UI 发布是唯一物化并排序 List 的
 * 操作。草稿与已读可靠发件箱是常驻的本地可靠事实，构造期间加载一次。
 */
internal class LocalConversationProjectionStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val outboxLimits: LocalOutboxLimits,
    private val materializeTransientPeerUsersLocked: (Set<String>) -> Unit = {},
) {
    private val conversationsFlow = RetirableProjectionState<List<Conversation>>(emptyList())
    private val conversationsById = LinkedHashMap<String, Conversation>()
    private val snapshotFence = ConversationSnapshotFence()

    /** 存在一个 key 且值为 null 表示一次显式本地清空。 */
    private val localDraftOverrides = LinkedHashMap<String, LocalDraftOverride>()
    private val pendingReadsByChatId = LinkedHashMap<String, Long>()
    private var draftCharacterCount = 0L
    private var draftUtf8ByteCount = 0L
    /** 会话级唯一性隔断迟到的 ACK，而不为每个访问过的 chat 保留一个计数器。 */
    private var draftOperationGeneration = 0L

    init {
        queries.selectAllConversations().executeAsList().forEach { row ->
            val conversation = row.toLocalModel()
            conversationsById[conversation.chatId] = conversation
        }
        replaceAllDraftOverridesLocked(
            queries.selectAllConversationDraftOutbox().executeAsList().associate { row ->
                row.chat_id to LocalDraftOverride(
                    draft = row.draft,
                    generation = row.generation,
                    state = row.state,
                )
            },
        )
        queries.selectAllConversationReadOutbox().executeAsList().forEach { row ->
            pendingReadsByChatId[row.chat_id] = row.read_seq
        }
        check(localDraftOverrides.size <= outboxLimits.draftCount) {
            "Persisted conversation draft outbox exceeds its entry budget"
        }
        check(draftCharacterCount <= outboxLimits.draftCharacters) {
            "Persisted conversation draft outbox exceeds its character budget"
        }
        check(draftUtf8ByteCount <= outboxLimits.draftUtf8Bytes) {
            "Persisted conversation draft outbox exceeds its UTF-8 byte budget"
        }
        check(pendingReadsByChatId.size <= outboxLimits.readCount) {
            "Persisted conversation read outbox exceeds its entry budget"
        }
        draftOperationGeneration = localDraftOverrides.values.maxOfOrNull(LocalDraftOverride::generation) ?: 0L
        publishConversations()
    }

    fun getConversations(): List<Conversation> = cacheUseGate.use {
        conversationsFlow.value
    }

    fun observeConversations(): Flow<List<Conversation>> = cacheUseGate.use {
        conversationsFlow.observe()
    }

    fun upsertConversation(conversation: Conversation) = cacheUseGate.use {
        synchronized(stateLock) {
            val plan = prepareConversationMerge(
                local = conversationsById[conversation.chatId],
                remote = conversation,
                draftOverride = localDraftOverrides[conversation.chatId],
                pendingReadSeq = pendingReadsByChatId[conversation.chatId],
            )
            // 首次加载的临时对端必须先于其更旧的 conversation 身份持久化。
            // 如果 user 持久化失败，conversation 行与常驻列表都不会推进。
            materializeTransientPeerUsersLocked(plan.conversation.personalPeerUids())
            queries.transaction {
                if (plan.clearDraftOverride) {
                    queries.deleteConversationDraftOutbox(conversation.chatId)
                }
                // 同时持久化合并后的草稿；重启绝不能短暂复活一个更旧的事件。
                persistConversation(plan.conversation)
            }
            if (plan.clearDraftOverride) {
                replaceDraftOverrideLocked(conversation.chatId, null)
            } else if (plan.draftOverride != null) {
                replaceDraftOverrideLocked(conversation.chatId, plan.draftOverride)
            }
            conversationsById[conversation.chatId] = plan.conversation
            publishConversations()
            markConversationMutatedLocked(conversation.chatId)
        }
    }

    fun beginConversationSnapshot(): Long = cacheUseGate.use {
        synchronized(stateLock) { snapshotFence.beginSnapshot() }
    }

    fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean = cacheUseGate.use {
        require(snapshotGeneration > 0L) { "snapshotGeneration must be positive" }
        val snapshotById = LinkedHashMap<String, Conversation>(conversations.size)
        conversations.forEach { conversation -> snapshotById[conversation.chatId] = conversation }
        synchronized(stateLock) {
            // 与任何事件/本地事实重叠的响应会被整体拒绝。仓库用新代际重试，因此任何安全子集
            // 都不能掩盖必需的重试。
            if (!snapshotFence.canApply(snapshotGeneration)) {
                return@synchronized false
            }

            val projectedConversations = LinkedHashMap(conversationsById)
            val projectedOverrides = LinkedHashMap(localDraftOverrides)
            val mergePlans = ArrayList<ConversationMergePlan>(snapshotById.size)
            snapshotById.values.forEach { remote ->
                val plan = prepareConversationMerge(
                    local = projectedConversations[remote.chatId],
                    remote = remote,
                    draftOverride = projectedOverrides[remote.chatId],
                    pendingReadSeq = pendingReadsByChatId[remote.chatId],
                )
                mergePlans += plan
                projectedConversations[remote.chatId] = plan.conversation
                if (plan.clearDraftOverride) {
                    projectedOverrides.remove(remote.chatId)
                } else if (plan.draftOverride != null) {
                    projectedOverrides[remote.chatId] = plan.draftOverride
                }
            }

            // 包含被中断操作或更旧构建留下的孤儿本地事实。
            val remoteIds = snapshotById.keys
            val removableIds = buildSet {
                projectedConversations.keys.forEach { if (it !in remoteIds) add(it) }
                projectedOverrides.keys.forEach { if (it !in remoteIds) add(it) }
                pendingReadsByChatId.keys.forEach { if (it !in remoteIds) add(it) }
            }

            // 先提升对端，这样 user 写入失败就不能提交或发布过期的 conversation 身份。
            // 有界覆盖层对重试保持可用。
            materializeTransientPeerUsersLocked(
                mergePlans.flatMapTo(linkedSetOf()) { it.conversation.personalPeerUids() },
            )
            queries.transaction {
                mergePlans.forEach { plan ->
                    if (plan.clearDraftOverride) {
                        queries.deleteConversationDraftOutbox(plan.conversation.chatId)
                    }
                    persistConversation(plan.conversation)
                }
                removableIds.forEach { chatId ->
                    queries.deleteConversationDraftOutbox(chatId)
                    queries.deleteConversationReadOutbox(chatId)
                    queries.deleteConversation(chatId)
                }
            }

            // 只在每一条 SQL 语句提交之后发布。上面的副本在事务失败时可丢弃，因此内存绝不会
            // 领先于 SQLite。
            removableIds.forEach { chatId ->
                projectedConversations.remove(chatId)
                projectedOverrides.remove(chatId)
                pendingReadsByChatId.remove(chatId)
            }
            conversationsById.clear()
            conversationsById.putAll(projectedConversations)
            replaceAllDraftOverridesLocked(projectedOverrides)
            snapshotFence.markApplied(snapshotGeneration)
            publishConversations()
            true
        }
    }

    fun deleteConversation(chatId: String) = cacheUseGate.use {
        synchronized(stateLock) {
            queries.transaction {
                queries.deleteConversationDraftOutbox(chatId)
                queries.deleteConversationReadOutbox(chatId)
                queries.deleteConversation(chatId)
            }
            replaceDraftOverrideLocked(chatId, null)
            pendingReadsByChatId.remove(chatId)
            conversationsById.remove(chatId)
            publishConversations()
            // 通过常量空间代际隔断保留删除，即使不存在任何行。
            markConversationMutatedLocked(chatId)
        }
    }

    fun enqueueConversationRead(chatId: String, readSeq: Long): Long = cacheUseGate.use {
        require(readSeq > 0L) { "readSeq must be positive" }
        synchronized(stateLock) {
            if (
                chatId !in pendingReadsByChatId &&
                pendingReadsByChatId.size >= outboxLimits.readCount
            ) {
                localOutboxCapacityExceeded(
                    LocalOutboxKind.CONVERSATION_READ,
                    LocalOutboxCapacityDimension.ENTRY_COUNT,
                    outboxLimits.readCount.toLong(),
                )
            }
            val mergedReadSeq = maxOf(pendingReadsByChatId[chatId] ?: 0L, readSeq)
            queries.transaction {
                queries.ensureConversationReadOutbox(chatId, readSeq)
                queries.advanceConversationReadOutbox(readSeq, chatId)
                queries.markConversationRead(readSeq, chatId)
            }
            pendingReadsByChatId[chatId] = mergedReadSeq
            conversationsById[chatId]?.let { conversation ->
                conversationsById[chatId] = applyConversationRead(conversation, readSeq)
                publishConversations()
            }
            // 即使服务器投影行尚不存在，持久可靠发件箱本身也是一次本地变更。隔断一个过期的
            // 空快照，防止其删除该孤儿事实。
            markConversationMutatedLocked(chatId)
            mergedReadSeq
        }
    }

    fun getPendingConversationReads(): List<PendingConversationRead> = cacheUseGate.use {
        synchronized(stateLock) {
            pendingReadsByChatId.entries
                .sortedBy { it.key }
                .map { (chatId, readSeq) -> PendingConversationRead(chatId, readSeq) }
        }
    }

    fun getPendingConversationRead(chatId: String): PendingConversationRead? = cacheUseGate.use {
        synchronized(stateLock) {
            pendingReadsByChatId[chatId]?.let { readSeq -> PendingConversationRead(chatId, readSeq) }
        }
    }

    fun markConversationReadMirrored(chatId: String, readSeq: Long) = cacheUseGate.use {
        require(readSeq > 0L) { "readSeq must be positive" }
        synchronized(stateLock) {
            queries.ackConversationReadOutbox(chatId, readSeq)
            val pendingReadSeq = pendingReadsByChatId[chatId]
            if (pendingReadSeq != null && pendingReadSeq <= readSeq) {
                pendingReadsByChatId.remove(chatId)
            }
            Unit
        }
    }

    fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) = cacheUseGate.use {
        synchronized(stateLock) state@{
            val existing = conversationsById[chatId] ?: return@state
            val mergedPeerReadSeq = maxOf(existing.peerReadSeq, peerReadSeq)
            if (mergedPeerReadSeq == existing.peerReadSeq) return@state
            queries.updatePeerReadSeq(mergedPeerReadSeq, chatId)
            conversationsById[chatId] = existing.copy(peerReadSeq = mergedPeerReadSeq)
            publishConversations()
            markConversationMutatedLocked(chatId)
        }
    }

    fun setConversationDraft(chatId: String, draft: String?): Long = cacheUseGate.use {
        synchronized(stateLock) {
            admitConversationDraftLocked(chatId, draft)
            check(draftOperationGeneration < Long.MAX_VALUE) { "conversation draft generation exhausted" }
            draftOperationGeneration += 1L
            val generation = draftOperationGeneration
            val override = LocalDraftOverride(draft, generation, DRAFT_MIRROR_PENDING)
            queries.transaction {
                queries.upsertConversationDraftOutbox(chatId, draft, generation, DRAFT_MIRROR_PENDING)
                queries.setConversationDraft(draft, chatId)
            }
            replaceDraftOverrideLocked(chatId, override)
            conversationsById[chatId]?.let { conversation ->
                conversationsById[chatId] = conversation.copy(draft = draft)
                publishConversations()
            }
            markConversationMutatedLocked(chatId)
            generation
        }
    }

    fun getPendingConversationDrafts(): List<PendingConversationDraft> = cacheUseGate.use {
        synchronized(stateLock) {
            localDraftOverrides.mapNotNull { (chatId, override) ->
                if (override.state == DRAFT_MIRROR_PENDING) {
                    PendingConversationDraft(chatId, override.draft, override.generation)
                } else {
                    null
                }
            }
        }
    }

    fun getPendingConversationDraft(chatId: String): PendingConversationDraft? = cacheUseGate.use {
        synchronized(stateLock) {
            localDraftOverrides[chatId]?.takeIf { it.state == DRAFT_MIRROR_PENDING }?.let { override ->
                PendingConversationDraft(chatId, override.draft, override.generation)
            }
        }
    }

    fun markConversationDraftMirrored(chatId: String, generation: Long) = cacheUseGate.use {
        synchronized(stateLock) state@{
            val current = localDraftOverrides[chatId] ?: return@state
            if (current.generation != generation || current.state != DRAFT_MIRROR_PENDING) return@state
            val matchingAuthorityAlreadyObserved =
                current.observedAuthority?.draft == current.draft && current.observedAuthority != null
            queries.transaction {
                if (matchingAuthorityAlreadyObserved) {
                    // 代际在 stateLock 下仍然精确，因此这里只删除它自己的行。
                    queries.deleteConversationDraftOutbox(chatId)
                } else {
                    queries.markConversationDraftOutboxAcked(chatId, generation)
                }
            }
            if (matchingAuthorityAlreadyObserved) {
                replaceDraftOverrideLocked(chatId, null)
            } else {
                replaceDraftOverrideLocked(chatId, current.copy(state = DRAFT_MIRROR_ACKED))
            }
        }
    }

    /** 调用方持有 [stateLock]；SQL 删除由 [LocalCacheImpl] 拥有。 */
    fun removeChatProjectionLocked(chatId: String) {
        replaceDraftOverrideLocked(chatId, null)
        pendingReadsByChatId.remove(chatId)
        conversationsById.remove(chatId)
        publishConversations()
    }

    /**
     * 调用方持有 [stateLock]。构建一个可丢弃的替代品，不变更 SQL 或常驻状态，因此跨投影事务中
     * 之后的失败仍让内存保持对齐。
     */
    fun prepareServerCheckpointLocked(
        remoteConversations: List<Conversation>,
    ): ServerCheckpointConversationPlan {
        val projected = LinkedHashMap<String, Conversation>(remoteConversations.size)
        val projectedOverrides = LinkedHashMap(localDraftOverrides)
        val clearedDraftChatIds = linkedSetOf<String>()
        remoteConversations.forEach { remote ->
            val checkpointConversation = preserveNewerConversationIdentity(
                local = conversationsById[remote.chatId],
                checkpoint = remote,
            )
            val plan = prepareConversationMerge(
                local = null,
                remote = checkpointConversation,
                draftOverride = projectedOverrides[remote.chatId],
                pendingReadSeq = pendingReadsByChatId[remote.chatId],
            )
            projected[remote.chatId] = plan.conversation
            if (plan.clearDraftOverride) {
                projectedOverrides.remove(remote.chatId)
                clearedDraftChatIds += remote.chatId
            } else if (plan.draftOverride != null) {
                projectedOverrides[remote.chatId] = plan.draftOverride
            }
        }
        return ServerCheckpointConversationPlan(
            conversations = projected,
            draftOverrides = projectedOverrides,
            clearedDraftChatIds = clearedDraftChatIds,
        )
    }

    /** 调用方持有 [stateLock] 与外层检查点 SQL 事务。 */
    fun persistServerCheckpointLocked(plan: ServerCheckpointConversationPlan) {
        queries.deleteAllConversations()
        plan.clearedDraftChatIds.forEach(queries::deleteConversationDraftOutbox)
        plan.conversations.values.forEach(::persistConversation)
    }

    /** 调用方持有 [stateLock]；检查点事务已提交。 */
    fun publishServerCheckpointLocked(plan: ServerCheckpointConversationPlan) {
        conversationsById.clear()
        conversationsById.putAll(plan.conversations)
        replaceAllDraftOverridesLocked(plan.draftOverrides)
        snapshotFence.resetServerProjection()
        publishConversations()
    }

    /**
     * 调用方持有 [stateLock]；SQL reset 由 [LocalCacheImpl] 拥有。
     *
     * 只有可重放的服务器行被清除。草稿覆盖与已读可靠发件箱是本地可靠事实，保持可用于叠加第一个
     * 重放的 conversation 快照。
     */
    fun clearServerProjectionLocked() {
        conversationsById.clear()
        publishConversations()
        snapshotFence.resetServerProjection()
        // 跨服务器 reset 保留草稿状态与标量代际作为过期 ACK 隔断。
    }

    /** 调用方持有 [stateLock]；缓存关闭释放每个保留投影与收集器。 */
    fun closeResidentLocked() {
        conversationsById.clear()
        localDraftOverrides.clear()
        pendingReadsByChatId.clear()
        draftCharacterCount = 0L
        draftUtf8ByteCount = 0L
        conversationsFlow.retire(emptyList())
    }

    /** 调用方必须持有 [stateLock]。 */
    fun markConversationMutatedLocked(@Suppress("UNUSED_PARAMETER") chatId: String) {
        snapshotFence.markMutation()
    }

    private fun persistConversation(conversation: Conversation) {
        val avatar = conversation.chatAvatar
        queries.upsertConversation(
            conversation.chatId,
            conversation.chatType.toLong(),
            conversation.peerUid,
            conversation.peerRevision,
            conversation.chatName,
            avatar?.path,
            avatar?.name,
            avatar?.contentType,
            avatar?.size,
            conversation.lastMessage,
            conversation.lastMessageType?.toLong(),
            conversation.lastMsgTimestamp,
            conversation.lastSeq,
            conversation.readSeq,
            conversation.peerReadSeq,
            conversation.unreadCount.toLong(),
            if (conversation.isPinned) 1L else 0L,
            if (conversation.isMuted) 1L else 0L,
            conversation.draft,
        )
    }

    /** 调用方持有 [stateLock]。容量拒绝发生在代际、SQL 或内存变更之前。 */
    private fun admitConversationDraftLocked(chatId: String, draft: String?) {
        val existing = localDraftOverrides[chatId]
        if (existing == null && localDraftOverrides.size >= outboxLimits.draftCount) {
            localOutboxCapacityExceeded(
                LocalOutboxKind.CONVERSATION_DRAFT,
                LocalOutboxCapacityDimension.ENTRY_COUNT,
                outboxLimits.draftCount.toLong(),
            )
        }
        val oldStorage = existing?.let { it.draft.storageSize() } ?: EMPTY_DRAFT_STORAGE_SIZE
        val newStorage = draft.storageSize()
        val nextCharacters = draftCharacterCount - oldStorage.characters + newStorage.characters
        if (nextCharacters > outboxLimits.draftCharacters) {
            localOutboxCapacityExceeded(
                LocalOutboxKind.CONVERSATION_DRAFT,
                LocalOutboxCapacityDimension.CHARACTER_COUNT,
                outboxLimits.draftCharacters,
            )
        }
        val nextUtf8Bytes = draftUtf8ByteCount - oldStorage.utf8Bytes + newStorage.utf8Bytes
        if (nextUtf8Bytes > outboxLimits.draftUtf8Bytes) {
            localOutboxCapacityExceeded(
                LocalOutboxKind.CONVERSATION_DRAFT,
                LocalOutboxCapacityDimension.STORED_BYTES,
                outboxLimits.draftUtf8Bytes,
            )
        }
    }

    /** 调用方持有 [stateLock]。 */
    private fun replaceDraftOverrideLocked(chatId: String, replacement: LocalDraftOverride?) {
        val previous = localDraftOverrides[chatId]
        val previousStorage = previous?.let { it.draft.storageSize() } ?: EMPTY_DRAFT_STORAGE_SIZE
        val replacementStorage = replacement?.let { it.draft.storageSize() } ?: EMPTY_DRAFT_STORAGE_SIZE
        draftCharacterCount =
            draftCharacterCount - previousStorage.characters + replacementStorage.characters
        draftUtf8ByteCount =
            draftUtf8ByteCount - previousStorage.utf8Bytes + replacementStorage.utf8Bytes
        if (replacement == null) {
            localDraftOverrides.remove(chatId)
        } else {
            localDraftOverrides[chatId] = replacement
        }
    }

    /** 调用方持有 [stateLock]，或构造尚未逃逸。 */
    private fun replaceAllDraftOverridesLocked(replacements: Map<String, LocalDraftOverride>) {
        localDraftOverrides.clear()
        localDraftOverrides.putAll(replacements)
        draftCharacterCount = 0L
        draftUtf8ByteCount = 0L
        replacements.values.forEach { override ->
            val storage = override.draft.storageSize()
            draftCharacterCount += storage.characters
            draftUtf8ByteCount += storage.utf8Bytes
        }
    }

    private fun publishConversations() {
        conversationsFlow.value = sortConversations(conversationsById.values)
    }
}

private fun Conversation.personalPeerUids(): Set<String> {
    if (peerRevision == null) return emptySet()
    val uid = peerUid ?: return emptySet()
    return setOf(uid)
}

private data class DraftStorageSize(
    val characters: Long,
    val utf8Bytes: Long,
)

private val EMPTY_DRAFT_STORAGE_SIZE = DraftStorageSize(characters = 0L, utf8Bytes = 0L)

private fun String?.storageSize(): DraftStorageSize = if (this == null) {
    EMPTY_DRAFT_STORAGE_SIZE
} else {
    DraftStorageSize(
        characters = length.toLong(),
        utf8Bytes = encodeToByteArray().size.toLong(),
    )
}
