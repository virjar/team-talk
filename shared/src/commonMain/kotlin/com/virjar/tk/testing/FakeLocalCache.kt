package com.virjar.tk.testing

import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.CacheUseGate
import com.virjar.tk.client.MessageHistoryLease
import com.virjar.tk.client.MessagePager
import com.virjar.tk.client.OutgoingMessage
import com.virjar.tk.client.PendingBotMessage
import com.virjar.tk.client.PendingConversationDraft
import com.virjar.tk.model.*
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake [LocalCache]，纯内存实现。
 *
 * 消息方法重点实现（支持 Repository/ViewModel 测试）：
 * - [insertMessage] / [updateMessage] / [deleteMessage] 操作内存列表
 * - [getMessages] / [observeMessages] 读取内存列表
 * - [pager] 返回 [SimpleMessagePager]
 *
 * 其他实体（用户/联系人/聊天/成员/会话）用 MutableStateFlow 模拟，可手动设置。
 */
class FakeLocalCache(
    successReceiptLimit: Int = com.virjar.tk.client.DEFAULT_OUTGOING_SUCCESS_RECEIPTS,
) : LocalCache {
    private data class MessageHistoryState(
        var lifecycleGeneration: Long = 0L,
        var historyChainGeneration: Long = 0L,
        var committedHistoryChainGeneration: Long = 0L,
        var pendingNewestChainGeneration: Long = 0L,
        var newestRequestGeneration: Long = 0L,
        var olderRequestGeneration: Long = 0L,
    )

    // 消息存储：chatId → 按时间倒序的消息列表（最新在前）
    private val messagesMap = mutableMapOf<String, MutableList<Message>>()
    private val messagesFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val messageHistoryLock = Any()
    private val messageHistoryOwner = Any()
    private var messageHistoryGlobalGeneration = 0L
    private var messageHistoryRequestGeneration = 0L
    private val messageHistoryStates = mutableMapOf<String, MessageHistoryState>()
    private val cacheUseGate = CacheUseGate()

    // 其他实体存储
    private val usersFlow = MutableStateFlow<List<User>>(emptyList())
    private val contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    private val contactLock = Any()
    private var contactProjectionGeneration = 0L
    private var lastFullContactSnapshotGeneration = 0L
    private val contactMutationGenerations = mutableMapOf<String, Long>()
    private val chatsFlow = MutableStateFlow<List<Chat>>(emptyList())
    private val membersMap = mutableMapOf<String, MutableList<Member>>()
    private val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private val conversationLock = Any()
    private var conversationProjectionGeneration = 0L
    private var lastAppliedConversationSnapshotGeneration = 0L
    private val conversationMutationGenerations = mutableMapOf<String, Long>()
    private val syncCursors = mutableMapOf<String, Long>()
    private val botMessageLog = FakeBotMessageLog()
    private val outgoingStore = FakeOutgoingMessageStore(
        lock = messagesMap,
        upsertProjection = ::upsertFakeMessage,
        updateProjectionStatus = ::updateFakeMessageStatus,
        completeProjection = ::completeFakeMessage,
        markAuthoritativeProjectionSent = ::markFakeAuthoritativeMessageSent,
        successReceiptLimit = successReceiptLimit,
    )
    private data class DraftObservation(val draft: String?)
    private data class DraftOverride(
        val draft: String?,
        val generation: Long,
        val mirrored: Boolean,
        val observedAuthority: DraftObservation? = null,
    )
    private data class ConversationMergePlan(
        val conversation: Conversation,
        val draftOverride: DraftOverride?,
        val clearDraftOverride: Boolean,
    )
    private val draftOverrides = mutableMapOf<String, DraftOverride>()
    private val draftGenerationHighWatermarks = mutableMapOf<String, Long>()

    // 记录 onChatInactive 调用（测试断言用）
    val inactiveChats = mutableListOf<String>()

    private fun messagesFlow(chatId: String): MutableStateFlow<List<Message>> = synchronized(messagesMap) {
        messagesFlows.getOrPut(chatId) { MutableStateFlow(messagesMap[chatId]?.toList() ?: emptyList()) }
    }

    /** Caller holds messagesMap. */
    private fun syncFlow(chatId: String) {
        messagesFlow(chatId).value = messagesMap[chatId]?.toList() ?: emptyList()
    }

    // ── 消息 ──
    override fun getMessages(chatId: String, limit: Int): List<Message> = cacheUseGate.use {
        synchronized(messagesMap) { fakeInitialMessages(messagesMap[chatId] ?: emptyList(), limit) }
    }

    override fun observeMessages(chatId: String): Flow<List<Message>> = cacheUseGate.use { messagesFlow(chatId) }

    override fun insertMessage(message: Message) = cacheUseGate.use {
        upsertFakeInboundMessage(messagesMap, outgoingStore, message, ::syncFlow)
    }

    override fun enqueueOutgoingMessage(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = cacheUseGate.use {
        enqueueFakeOutgoingMessage(messagesMap, outgoingStore, message, now, requestFingerprint)
    }

    override fun getOutgoingMessage(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = cacheUseGate.use {
        outgoingStore.get(chatId, clientMsgId, requestFingerprint)
    }

    override fun recoverOutgoingMessages(now: Long): List<OutgoingMessage> = cacheUseGate.use {
        synchronized(messagesMap) {
            failFakeOrphanedMessages(messagesMap, outgoingStore.projectionKeys(), ::syncFlow)
            reconcileFakeAuthoritativeOutgoing(messagesMap, outgoingStore, now)
            outgoingStore.recover(now)
        }
    }

    override fun peekNextOutgoingMessage(): OutgoingMessage? = cacheUseGate.use { outgoingStore.peek() }

    override fun claimNextOutgoingMessage(now: Long): OutgoingMessage? =
        cacheUseGate.use { outgoingStore.claim(now) }

    override fun markOutgoingMessageRetry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
    ) = cacheUseGate.use { outgoingStore.retry(localOrdinal, error, nextAttemptAt, now) }

    override fun markOutgoingMessageTerminalFailed(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int?,
    ) = cacheUseGate.use { outgoingStore.fail(localOrdinal, error, now, terminalCode) }

    override fun completeOutgoingMessage(localOrdinal: Long, ack: MessageAckPayload, now: Long) =
        cacheUseGate.use { outgoingStore.complete(localOrdinal, ack, now) }

    override fun cancelOutgoingMessages(reason: String, now: Long) =
        cacheUseGate.use { outgoingStore.cancel(reason, now) }

    override fun beginMessageHistoryLease(
        chatId: String,
        resetResidentWindow: Boolean,
    ): MessageHistoryLease = cacheUseGate.use {
        synchronized(messageHistoryLock) {
            val state = messageHistoryStates.getOrPut(chatId, ::MessageHistoryState)
            val requestGeneration = nextMessageHistoryRequestGeneration()
            if (resetResidentWindow) {
                state.historyChainGeneration = nextGeneration(state.historyChainGeneration)
                state.pendingNewestChainGeneration = state.historyChainGeneration
                state.newestRequestGeneration = requestGeneration
            } else {
                state.olderRequestGeneration = requestGeneration
            }
            MessageHistoryLease(
                chatId = chatId,
                owner = messageHistoryOwner,
                globalGeneration = messageHistoryGlobalGeneration,
                chatLifecycleGeneration = state.lifecycleGeneration,
                requestGeneration = requestGeneration,
                historyChainGeneration = if (resetResidentWindow) {
                    state.pendingNewestChainGeneration
                } else {
                    state.committedHistoryChainGeneration
                },
                resetResidentWindow = resetResidentWindow,
            )
        }
    }

    override fun applyMessageHistoryPage(
        lease: MessageHistoryLease,
        messages: List<Message>,
    ): Boolean = cacheUseGate.runIfOpen {
        synchronized(messageHistoryLock) history@{
        val state = currentMessageHistoryState(lease) ?: return@history false
        applyFakeHistoryProjection(
            messagesMap,
            outgoingStore,
            lease.chatId,
            messages,
            lease.resetResidentWindow,
            ::syncFlow,
        )

        if (lease.resetResidentWindow) {
            state.committedHistoryChainGeneration = lease.historyChainGeneration
            state.pendingNewestChainGeneration = 0L
            state.newestRequestGeneration = 0L
            state.olderRequestGeneration = 0L
        } else {
            state.olderRequestGeneration = 0L
        }
        true
        }
    }

    override fun abandonMessageHistoryLease(lease: MessageHistoryLease): Boolean =
        cacheUseGate.runIfOpen {
            synchronized(messageHistoryLock) history@{
            val state = messageHistoryStateForLeaseLifecycle(lease) ?: return@history false
            if (lease.resetResidentWindow) {
                if (state.newestRequestGeneration != lease.requestGeneration ||
                    state.pendingNewestChainGeneration != lease.historyChainGeneration
                ) {
                    return@history false
                }
                state.newestRequestGeneration = 0L
                state.pendingNewestChainGeneration = 0L
            } else {
                if (state.olderRequestGeneration != lease.requestGeneration ||
                    state.committedHistoryChainGeneration != lease.historyChainGeneration
                ) {
                    return@history false
                }
                state.olderRequestGeneration = 0L
            }
            true
            }
        }

    override fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) = cacheUseGate.use {
        synchronized(messagesMap) messages@{
            val list = messagesMap[chatId] ?: return@messages
            val idx = list.indexOfFirst { it.clientMsgId == clientMsgId }
            if (idx >= 0 && list[idx].serverSeq == 0L) {
                list[idx] = list[idx].copy(serverSeq = serverSeq, sendStatus = Message.SEND_STATUS_SENT)
                syncFlow(chatId)
            }
        }
    }

    override fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) = cacheUseGate.use {
        synchronized(messagesMap) messages@{
            val list = messagesMap[chatId] ?: return@messages
            val idx = list.indexOfFirst { it.clientMsgId == clientMsgId }
            if (idx >= 0 && list[idx].serverSeq == 0L) {
                list[idx] = list[idx].copy(sendStatus = sendStatus)
                syncFlow(chatId)
            }
        }
    }

    /** Caller already owns cache admission; helpers avoid recursively expressing public operations. */
    private fun upsertFakeMessage(message: Message) = synchronized(messagesMap) {
        val list = messagesMap.getOrPut(message.chatId) { mutableListOf() }
        val index = list.indexOfFirst { it.clientMsgId == message.clientMsgId }
        if (index >= 0) {
            if (list[index].serverSeq > 0L && message.serverSeq == 0L) return@synchronized
            list[index] = message
        } else {
            list.add(message)
        }
        list.sortWith(fakeMessageOrder)
        syncFlow(message.chatId)
    }

    private fun updateFakeMessageStatus(message: Message, sendStatus: Int) = synchronized(messagesMap) {
        val list = messagesMap[message.chatId] ?: return@synchronized
        val index = list.indexOfFirst { it.clientMsgId == message.clientMsgId }
        if (index >= 0 && list[index].serverSeq == 0L) {
            list[index] = list[index].copy(sendStatus = sendStatus)
            syncFlow(message.chatId)
        }
    }

    private fun completeFakeMessage(message: Message, serverSeq: Long) = synchronized(messagesMap) {
        val list = messagesMap[message.chatId] ?: return@synchronized
        val index = list.indexOfFirst { it.clientMsgId == message.clientMsgId }
        if (index >= 0 && list[index].serverSeq == 0L) {
            list[index] = list[index].copy(
                serverSeq = serverSeq,
                sendStatus = Message.SEND_STATUS_SENT,
            )
            syncFlow(message.chatId)
        }
    }

    private fun markFakeAuthoritativeMessageSent(message: Message) = synchronized(messagesMap) {
        val list = messagesMap[message.chatId] ?: return@synchronized
        val index = list.indexOfFirst { it.clientMsgId == message.clientMsgId }
        if (index >= 0 && list[index].serverSeq > 0L) {
            list[index] = list[index].copy(sendStatus = Message.SEND_STATUS_SENT)
            syncFlow(message.chatId)
        }
    }

    override fun pager(chatId: String, windowSize: Int): MessagePager = cacheUseGate.use {
        SimpleMessagePager(chatId, this, cacheUseGate, windowSize)
    }

    override fun onChatInactive(chatId: String) = cacheUseGate.use {
        inactiveChats += chatId
    }

    // ── 用户 ──
    override fun getUser(uid: String): User? = cacheUseGate.use { usersFlow.value.find { it.uid == uid } }
    override fun upsertUser(user: User) = cacheUseGate.use {
        val list = usersFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.uid == user.uid }
        if (idx >= 0) list[idx] = user else list.add(user)
        usersFlow.value = list
    }
    // ── 联系人 ──

    override fun getContacts(): List<Contact> = cacheUseGate.use { contactsFlow.value }
    override fun observeContacts(): Flow<List<Contact>> = cacheUseGate.use { contactsFlow }
    override fun upsertContact(contact: Contact) = cacheUseGate.use {
        synchronized(contactLock) {
            contactsFlow.value = mergeContact(contactsFlow.value, contact)
            markContactMutated(contact.friendUid)
        }
    }
    override fun deleteContact(friendUid: String) = cacheUseGate.use {
        synchronized(contactLock) {
            contactsFlow.value = contactsFlow.value.filter { it.friendUid != friendUid }
            markContactMutated(friendUid)
        }
    }
    override fun contactProjectionGeneration(): Long = cacheUseGate.use {
        synchronized(contactLock) { contactProjectionGeneration }
    }
    override fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean = cacheUseGate.use {
        require(expectedGeneration >= 0L) { "expectedGeneration 不能为负数" }
        val snapshot = contacts.associateBy(Contact::friendUid).values.toList()
        synchronized(contactLock) {
            if (contactProjectionGeneration == expectedGeneration) {
                contactsFlow.value = snapshot
                contactProjectionGeneration += 1L
                lastFullContactSnapshotGeneration = contactProjectionGeneration
                contactMutationGenerations.clear()
                true
            } else {
                val mergeable = if (expectedGeneration < lastFullContactSnapshotGeneration) {
                    emptyList()
                } else {
                    snapshot.filter { contact ->
                        (contactMutationGenerations[contact.friendUid] ?: 0L) <= expectedGeneration
                    }
                }
                if (mergeable.isNotEmpty()) {
                    var merged = contactsFlow.value
                    mergeable.forEach { merged = mergeContact(merged, it) }
                    contactsFlow.value = merged
                    contactProjectionGeneration += 1L
                    val mergedGeneration = contactProjectionGeneration
                    mergeable.forEach { contactMutationGenerations[it.friendUid] = mergedGeneration }
                }
                false
            }
        }
    }

    private fun mergeContact(current: List<Contact>, contact: Contact): List<Contact> {
        val list = current.toMutableList()
        val index = list.indexOfFirst { it.friendUid == contact.friendUid }
        if (index >= 0) list[index] = contact else list.add(contact)
        return list
    }

    private fun markContactMutated(friendUid: String) {
        contactProjectionGeneration += 1L
        contactMutationGenerations[friendUid] = contactProjectionGeneration
    }

    // ── 聊天 ──

    override fun getChat(chatId: String): Chat? = cacheUseGate.use { chatsFlow.value.find { it.chatId == chatId } }
    override fun upsertChat(chat: Chat) = cacheUseGate.use {
        synchronized(conversationLock) {
            val list = chatsFlow.value.toMutableList()
            val idx = list.indexOfFirst { it.chatId == chat.chatId }
            if (idx >= 0) list[idx] = chat else list.add(chat)
            chatsFlow.value = list
            markConversationMutated(chat.chatId)
        }
    }
    override fun deleteChat(chatId: String) = cacheUseGate.use {
        synchronized(messageHistoryLock) {
            synchronized(conversationLock) {
                synchronized(messagesMap) {
                    synchronized(botMessageLog) {
                        chatsFlow.value = chatsFlow.value.filter { it.chatId != chatId }
                        conversationsFlow.value = conversationsFlow.value.filter { it.chatId != chatId }
                        draftOverrides.remove(chatId)
                        membersMap.remove(chatId)
                        messagesMap.remove(chatId)
                        messagesFlows[chatId]?.value = emptyList()
                        botMessageLog.deleteChat(chatId)
                        outgoingStore.deleteChat(chatId)
                        markConversationMutated(chatId)
                        // Retain the resident messagesFlows entry and the draft generation high-watermark
                        // so replay collectors and stale-ACK fencing match LocalCacheImpl semantics.
                    }
                }
            }
            invalidateMessageHistoryForChat(chatId)
        }
    }

    // ── 成员 ──

    override fun getMembers(chatId: String): List<Member> = cacheUseGate.use {
        membersMap[chatId] ?: emptyList()
    }
    override fun observeMembers(chatId: String): Flow<List<Member>> = cacheUseGate.use {
        MutableStateFlow(membersMap[chatId] ?: emptyList())
    }
    override fun upsertMember(member: Member) {
        cacheUseGate.use {
            val list = membersMap.getOrPut(member.chatId) { mutableListOf() }
            val idx = list.indexOfFirst { it.uid == member.uid }
            if (idx >= 0) list[idx] = member else list.add(member)
        }
    }
    override fun removeMember(chatId: String, uid: String) {
        cacheUseGate.use {
            membersMap[chatId]?.removeAll { it.uid == uid }
        }
    }

    // ── 会话 ──

    override fun getConversations(): List<Conversation> = cacheUseGate.use {
        synchronized(conversationLock) { conversationsFlow.value }
    }
    override fun observeConversations(): Flow<List<Conversation>> = cacheUseGate.use { conversationsFlow }
    override fun upsertConversation(conv: Conversation) = cacheUseGate.use {
        synchronized(conversationLock) {
            val plan = prepareConversationMerge(
                local = conversationsFlow.value.firstOrNull { it.chatId == conv.chatId },
                remote = conv,
                draftOverride = draftOverrides[conv.chatId],
            )
            if (plan.clearDraftOverride) {
                draftOverrides.remove(conv.chatId)
            } else if (plan.draftOverride != null) {
                draftOverrides[conv.chatId] = plan.draftOverride
            }
            conversationsFlow.value = replaceSorted(conversationsFlow.value, plan.conversation)
            markConversationMutated(conv.chatId)
        }
    }

    override fun beginConversationSnapshot(): Long = cacheUseGate.use {
        synchronized(conversationLock) { nextConversationGeneration() }
    }

    override fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean = cacheUseGate.use {
        require(snapshotGeneration > 0L) { "snapshotGeneration must be positive" }
        val snapshot = conversations.associateBy(Conversation::chatId).values.toList()
        synchronized(conversationLock) {
            if (snapshotGeneration <= lastAppliedConversationSnapshotGeneration) {
                return@synchronized false
            }

            var projectedConversations = conversationsFlow.value
            val projectedOverrides = draftOverrides.toMutableMap()
            var hadConflict = conversationMutationGenerations.values.any { generation ->
                generation > snapshotGeneration
            }
            snapshot.forEach { remote ->
                if (wasConversationMutatedAfter(remote.chatId, snapshotGeneration)) {
                    hadConflict = true
                } else {
                    val plan = prepareConversationMerge(
                        local = projectedConversations.firstOrNull { it.chatId == remote.chatId },
                        remote = remote,
                        draftOverride = projectedOverrides[remote.chatId],
                    )
                    projectedConversations = replaceSorted(projectedConversations, plan.conversation)
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
            }
            val removableIds = absentIds.filterTo(mutableSetOf()) { chatId ->
                val safeToRemove = !wasConversationMutatedAfter(chatId, snapshotGeneration)
                if (!safeToRemove) hadConflict = true
                safeToRemove
            }

            projectedConversations = projectedConversations.filterNot { it.chatId in removableIds }
            removableIds.forEach { projectedOverrides.remove(it) }
            draftOverrides.clear()
            draftOverrides.putAll(projectedOverrides)
            conversationsFlow.value = sortConversations(projectedConversations)
            conversationProjectionGeneration = maxOf(
                conversationProjectionGeneration,
                snapshotGeneration,
            )
            lastAppliedConversationSnapshotGeneration = snapshotGeneration
            conversationMutationGenerations.entries.removeAll { (_, generation) ->
                generation <= snapshotGeneration
            }
            !hadConflict
        }
    }

    override fun setConversationDraft(chatId: String, draft: String?): Long = cacheUseGate.use {
        synchronized(conversationLock) {
            val generation = (draftGenerationHighWatermarks[chatId] ?: 0L) + 1L
            draftGenerationHighWatermarks[chatId] = generation
            draftOverrides[chatId] = DraftOverride(draft, generation, mirrored = false)
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId == chatId) it.copy(draft = draft) else it
            }
            markConversationMutated(chatId)
            generation
        }
    }
    override fun getPendingConversationDrafts(): List<PendingConversationDraft> = cacheUseGate.use {
        synchronized(conversationLock) {
            draftOverrides.mapNotNull { (chatId, override) ->
                if (!override.mirrored) PendingConversationDraft(chatId, override.draft, override.generation) else null
            }
        }
    }
    override fun markConversationDraftMirrored(chatId: String, generation: Long) = cacheUseGate.use {
        synchronized(conversationLock) conversation@{
            val current = draftOverrides[chatId] ?: return@conversation
            if (current.generation == generation && !current.mirrored) {
                if (current.observedAuthority?.draft == current.draft && current.observedAuthority != null) {
                    draftOverrides.remove(chatId)
                } else {
                    draftOverrides[chatId] = current.copy(mirrored = true)
                }
            }
        }
    }
    override fun deleteConversation(chatId: String) = cacheUseGate.use {
        synchronized(conversationLock) {
            draftOverrides.remove(chatId)
            conversationsFlow.value = conversationsFlow.value.filter { it.chatId != chatId }
            markConversationMutated(chatId)
        }
    }
    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) = cacheUseGate.use {
        synchronized(conversationLock) conversation@{
            val existing = conversationsFlow.value.firstOrNull { it.chatId == chatId } ?: return@conversation
            val mergedPeerReadSeq = maxOf(existing.peerReadSeq, peerReadSeq)
            if (mergedPeerReadSeq == existing.peerReadSeq) return@conversation
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId == chatId) it.copy(peerReadSeq = mergedPeerReadSeq) else it
            }
            markConversationMutated(chatId)
        }
    }
    override fun getSyncCursor(key: String): Long = cacheUseGate.use {
        synchronized(syncCursors) { syncCursors[key] ?: 0L }
    }
    override fun advanceSyncCursor(key: String, eventId: Long): Long = cacheUseGate.use {
        require(eventId > 0L) { "eventId must be positive" }
        synchronized(syncCursors) {
            maxOf(syncCursors[key] ?: 0L, eventId).also { syncCursors[key] = it }
        }
    }
    override fun resetServerProjection() = cacheUseGate.use {
        synchronized(messageHistoryLock) {
            synchronized(contactLock) {
                synchronized(conversationLock) {
                    synchronized(messagesMap) {
                        synchronized(syncCursors) {
                            synchronized(botMessageLog) {
                                usersFlow.value = emptyList()
                                contactsFlow.value = emptyList()
                                chatsFlow.value = emptyList()
                                membersMap.clear()
                                conversationsFlow.value = emptyList()
                                messagesMap.clear()
                                messagesFlows.values.forEach { it.value = emptyList() }
                                syncCursors.clear()
                                botMessageLog.reset()
                                draftOverrides.clear()
                                outgoingStore.projectionAfterReset().forEach { restored ->
                                    val list = messagesMap.getOrPut(restored.chatId) { mutableListOf() }
                                    list += restored
                                    list.sortWith(fakeMessageOrder)
                                    messagesFlows[restored.chatId]?.value = list.toList()
                                }

                                check(contactProjectionGeneration < Long.MAX_VALUE)
                                contactProjectionGeneration += 1L
                                lastFullContactSnapshotGeneration = contactProjectionGeneration
                                contactMutationGenerations.clear()

                                val resetGeneration = nextConversationGeneration()
                                lastAppliedConversationSnapshotGeneration = resetGeneration
                                conversationMutationGenerations.clear()
                                // Retain draft high-watermarks so a stale pre-reset ACK cannot match
                                // the next edit's generation.
                            }
                        }
                    }
                }
            }
            messageHistoryGlobalGeneration = nextGeneration(messageHistoryGlobalGeneration)
            messageHistoryStates.clear()
        }
    }

    override fun close() {
        cacheUseGate.close {
            synchronized(messageHistoryLock) {
                messageHistoryGlobalGeneration = nextGeneration(messageHistoryGlobalGeneration)
                messageHistoryStates.clear()
            }
        }
    }
    override fun enqueueBotMessage(eventId: Long, message: Message) =
        cacheUseGate.use { botMessageLog.enqueue(eventId, message) }

    override fun peekBotMessage(): PendingBotMessage? = cacheUseGate.use { botMessageLog.peek() }

    override fun ackBotMessage(eventId: Long, now: Long) = cacheUseGate.use { botMessageLog.ack(eventId) }
    override fun listBotMessageDeliveries(
        afterEventId: Long,
        chatId: String?,
        limit: Int,
    ): List<PendingBotMessage> = cacheUseGate.use { botMessageLog.list(afterEventId, chatId, limit) }

    override fun maxBotMessageEventId(): Long = cacheUseGate.use { botMessageLog.maxEventId() }
    override fun updateMessageInMemory(
        chatId: String,
        clientMsgId: String,
        transform: (Message) -> Message,
    ) = cacheUseGate.use {
        // 测试桩：无窗口概念，直接忽略（进度动画不影响测试语义）
    }

    override fun markConversationRead(chatId: String, readSeq: Long) = cacheUseGate.use {
        synchronized(conversationLock) {
            var changed = false
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId != chatId) return@map it
                val mergedReadSeq = maxOf(it.readSeq, readSeq)
                val mergedUnread = (it.lastSeq - mergedReadSeq)
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
                changed = changed || mergedReadSeq != it.readSeq || mergedUnread != it.unreadCount
                it.copy(unreadCount = mergedUnread, readSeq = mergedReadSeq)
            }
            if (changed) markConversationMutated(chatId)
        }
    }

    private fun prepareConversationMerge(
        local: Conversation?,
        remote: Conversation,
        draftOverride: DraftOverride?,
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
        val merged = if (local == null) incoming else mergeConversation(local, incoming, effectiveOverride)
        return ConversationMergePlan(merged, effectiveOverride, clearOverride)
    }

    private fun mergeConversation(
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

    private fun replaceSorted(current: List<Conversation>, conversation: Conversation): List<Conversation> {
        val result = current.toMutableList()
        val index = result.indexOfFirst { it.chatId == conversation.chatId }
        if (index >= 0) result[index] = conversation else result.add(conversation)
        return sortConversations(result)
    }

    private fun sortConversations(conversations: List<Conversation>): List<Conversation> =
        conversations.sortedWith(
            compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.lastMsgTimestamp ?: 0L },
        )

    private fun markConversationMutated(chatId: String) {
        conversationMutationGenerations[chatId] = nextConversationGeneration()
    }

    private fun nextConversationGeneration(): Long {
        check(conversationProjectionGeneration < Long.MAX_VALUE) {
            "conversation projection generation exhausted"
        }
        conversationProjectionGeneration += 1L
        return conversationProjectionGeneration
    }

    private fun wasConversationMutatedAfter(chatId: String, generation: Long): Boolean =
        (conversationMutationGenerations[chatId] ?: 0L) > generation

    /** Caller holds messageHistoryLock. */
    private fun currentMessageHistoryState(lease: MessageHistoryLease): MessageHistoryState? {
        val state = messageHistoryStateForLeaseLifecycle(lease) ?: return null
        val currentRequest: Long
        val currentChain: Long
        if (lease.resetResidentWindow) {
            currentRequest = state.newestRequestGeneration
            currentChain = state.pendingNewestChainGeneration
        } else {
            if (lease.historyChainGeneration == 0L) return null
            currentRequest = state.olderRequestGeneration
            currentChain = state.committedHistoryChainGeneration
        }
        return state.takeIf {
            currentRequest == lease.requestGeneration && currentChain == lease.historyChainGeneration
        }
    }

    /** Caller holds messageHistoryLock. */
    private fun messageHistoryStateForLeaseLifecycle(lease: MessageHistoryLease): MessageHistoryState? {
        if (lease.owner !== messageHistoryOwner ||
            lease.globalGeneration != messageHistoryGlobalGeneration
        ) {
            return null
        }
        val state = messageHistoryStates[lease.chatId] ?: return null
        if (lease.chatLifecycleGeneration != state.lifecycleGeneration) {
            return null
        }
        return state
    }

    /** Caller holds messageHistoryLock. */
    private fun invalidateMessageHistoryForChat(chatId: String) {
        val state = messageHistoryStates.getOrPut(chatId, ::MessageHistoryState)
        state.lifecycleGeneration = nextGeneration(state.lifecycleGeneration)
        state.historyChainGeneration = nextGeneration(state.historyChainGeneration)
        state.committedHistoryChainGeneration = 0L
        state.pendingNewestChainGeneration = 0L
        state.newestRequestGeneration = 0L
        state.olderRequestGeneration = 0L
    }

    /** Caller holds messageHistoryLock. */
    private fun nextMessageHistoryRequestGeneration(): Long {
        messageHistoryRequestGeneration = nextGeneration(messageHistoryRequestGeneration)
        return messageHistoryRequestGeneration
    }

    private fun nextGeneration(current: Long): Long {
        check(current < Long.MAX_VALUE) { "message history generation exhausted" }
        return current + 1L
    }

}
