package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.MAX_TERMINAL_OUTGOING_RECEIPTS
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.shared.client.KeyedProjectionSnapshotGate
import com.virjar.tk.shared.client.MessageHistoryLease
import com.virjar.tk.shared.client.MessageHistoryLeaseGate
import com.virjar.tk.shared.client.MessagePager
import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.OutgoingFailureCode
import com.virjar.tk.shared.client.OutgoingQueueSnapshot
import com.virjar.tk.shared.client.OptimisticMessageEditLease
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.shared.client.PendingContactDecision
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.shared.client.PendingGroupCreationCommand
import com.virjar.tk.shared.client.PendingGroupFileCommand
import com.virjar.tk.shared.client.PendingGroupBotCredentialCommand
import com.virjar.tk.shared.client.PendingInviteLinkCreation
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.shared.client.ServerProjectionCheckpoint
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Fake [LocalCache]，纯内存实现。
 *
 * 消息方法重点实现（支持 Repository/ViewModel 测试）：
 * - [insertMessage] / [updateMessage] / [deleteMessage] 操作内存列表
 * - [getMessages] 短读内存列表
 * - [pager] 返回 [SimpleMessagePager]
 *
 * 其他实体（用户/联系人/聊天/成员/组织目录/会话）用 MutableStateFlow 模拟，可手动设置。
 */
const val FAKE_SYNC_DATASET_ID = "00000000-0000-4000-8000-000000000001"

class FakeLocalCache(
    terminalReceiptLimit: Int = MAX_TERMINAL_OUTGOING_RECEIPTS,
    initialDatasetId: String? = FAKE_SYNC_DATASET_ID,
) : LocalCache {
    // 消息存储：chatId → 按时间倒序的消息列表（最新在前）
    private val messagesMap = mutableMapOf<String, MutableList<Message>>()
    private val messagesFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val messageHistoryLock = Any()
    private val messageHistoryGate = MessageHistoryLeaseGate("fake message history")
    private val cacheUseGate = FakeCacheUseGate()
    private val pagerLock = Any()
    private val activePagers = linkedSetOf<SimpleMessagePager>()
    private val optimisticMessageEdits = FakeOptimisticMessageEditStore(messagesMap, ::syncFlow)

    // 其他实体存储
    private val people = FakePeopleProjectionStore()
    private val chatsFlow = MutableStateFlow<List<Chat>>(emptyList())
    private val chatSnapshots = KeyedProjectionSnapshotGate("fake chat snapshot")
    private val chatSnapshotLeases = mutableMapOf<String, ProjectionSnapshotLease>()
    private val organization = FakeOrganizationProjectionStore(cacheUseGate)
    private val documents = FakeDocumentProjectionStore()
    private val conversationLock = Any()
    private val conversationProjection = FakeConversationProjectionStore(conversationLock)
    private val syncState = FakeSyncStateStore(initialDatasetId)
    private val botMessageLog = FakeBotMessageLog()
    private val reliableCommands = FakeReliableCommandStore(cacheUseGate)
    private val outgoingStore = FakeOutgoingMessageStore(
        lock = messagesMap,
        upsertProjection = { upsertFakeMessageProjection(messagesMap, it, ::syncFlow) },
        updateProjectionStatus = { message, status ->
            updateFakeMessageProjectionStatus(messagesMap, message, status, ::syncFlow)
        },
        completeProjection = { message, serverSeq ->
            completeFakeMessageProjection(messagesMap, message, serverSeq, ::syncFlow)
        },
        markAuthoritativeProjectionSent = {
            markFakeAuthoritativeMessageSent(messagesMap, it, ::syncFlow)
        },
        terminalReceiptLimit = terminalReceiptLimit,
    )
    private val outgoing = FakeOutgoingCacheSupport(
        cacheUseGate = cacheUseGate,
        messagesMap = messagesMap,
        outgoingStore = outgoingStore,
        optimisticMessageEdits = optimisticMessageEdits,
        onChatChanged = ::syncFlow,
    )
    // 记录精确 pager lease 的首次关闭（测试断言用）
    val inactiveChats = mutableListOf<String>()
    val userPointReadCountForTest: Int get() = people.userPointReadCountForTest
    val userProjectionAcquisitionCountForTest: Int get() = people.userProjectionAcquisitionCountForTest
    var pagerCloseOverlapCountForTest: Int = 0
        private set

    private fun messagesFlow(chatId: String): MutableStateFlow<List<Message>> = synchronized(messagesMap) {
        messagesFlows.getOrPut(chatId) { MutableStateFlow(messagesMap[chatId]?.toList() ?: emptyList()) }
    }

    /** 调用方需持有 messagesMap 锁。 */
    private fun syncFlow(chatId: String) {
        messagesFlow(chatId).value = messagesMap[chatId]?.toList() ?: emptyList()
    }

    override fun getPendingGroupCreation(): PendingGroupCreationCommand? = reliableCommands.getGroupCreation()
    override fun replacePendingGroupCreation(command: PendingGroupCreationCommand) = reliableCommands.replaceGroupCreation(command)
    override fun clearPendingGroupCreation(operationId: String) = reliableCommands.clearGroupCreation(operationId)
    override fun preparePendingContactDecision(candidate: PendingContactDecision) = reliableCommands.prepareContactDecision(candidate)
    override fun getPendingContactDecisions() = reliableCommands.getContactDecisions()
    override fun clearPendingContactDecision(operationId: String) = reliableCommands.clearContactDecision(operationId)
    override fun preparePendingInviteLinkCreation(candidate: PendingInviteLinkCreation) = reliableCommands.prepareInviteLinkCreation(candidate)
    override fun getPendingInviteLinkCreations() = reliableCommands.getInviteLinkCreations()
    override fun clearPendingInviteLinkCreation(operationId: String) = reliableCommands.clearInviteLinkCreation(operationId)
    override fun getPendingGroupBotCredentialCommand() = reliableCommands.getGroupBotCredentialCommand()
    override fun preparePendingGroupBotCredentialCommand(command: PendingGroupBotCredentialCommand) = reliableCommands.prepareGroupBotCredentialCommand(command)
    override fun clearPendingGroupBotCredentialCommand(operationId: String) = reliableCommands.clearGroupBotCredentialCommand(operationId)
    override fun preparePendingGroupFileCommand(candidate: PendingGroupFileCommand) = reliableCommands.prepareGroupFileCommand(candidate)
    override fun getPendingGroupFileCommands() = reliableCommands.getGroupFileCommands()
    override fun clearPendingGroupFileCommand(commandId: String) = reliableCommands.clearGroupFileCommand(commandId)
    override fun preparePendingDocumentMoveCommand(candidate: PendingDocumentMoveCommand) = reliableCommands.prepareDocumentMoveCommand(candidate)
    override fun getPendingDocumentMoveCommands() = reliableCommands.getDocumentMoveCommands()
    override fun clearPendingDocumentMoveCommand(operationId: String) = reliableCommands.clearDocumentMoveCommand(operationId)

    // ── 消息 ──
    override fun getMessages(chatId: String, limit: Int): List<Message> = cacheUseGate.use {
        require(chatId.isNotBlank()) { "chatId must not be blank" }
        require(limit in 1..LocalCache.MAX_MESSAGE_READ_LIMIT) {
            "limit must be between 1 and ${LocalCache.MAX_MESSAGE_READ_LIMIT}"
        }
        synchronized(messagesMap) { fakeInitialMessages(messagesMap[chatId] ?: emptyList(), limit) }
    }
    override fun findMessage(chatId: String, clientMsgId: String): Message? = synchronized(messagesMap) { messagesMap[chatId]?.firstOrNull { it.clientMsgId == clientMsgId } }

    internal fun messageFlowForPager(chatId: String): Flow<List<Message>> = messagesFlow(chatId)

    override fun insertMessage(message: Message) = cacheUseGate.use {
        require(message.chatId.isNotBlank()) { "message chatId must not be blank" }
        require(message.clientMsgId.isNotBlank()) { "message clientMsgId must not be blank" }
        synchronized(messageHistoryLock) {
            synchronized(messagesMap) {
                if (message.serverSeq > 0L) {
                    messageHistoryGate.recordAuthoritativeMutation(
                        message.chatId,
                        message.clientMsgId,
                        retainIfAbsentFromNewestPage = true,
                    )
                }
                optimisticMessageEdits.supersede(message.chatId, message.clientMsgId)
                upsertFakeInboundMessage(messagesMap, outgoingStore, message, ::syncFlow)
            }
        }
    }

    override fun reserveOptimisticMessageEdit(message: Message): OptimisticMessageEditLease? =
        cacheUseGate.use { optimisticMessageEdits.reserve(message) }

    override fun publishOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean =
        cacheUseGate.use { optimisticMessageEdits.publish(lease) }

    override fun commitOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean =
        cacheUseGate.use { optimisticMessageEdits.commit(lease) }

    override fun rollbackOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean =
        cacheUseGate.use { optimisticMessageEdits.rollback(lease) }

    override fun enqueueOutgoingMessage(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = outgoing.enqueue(message, now, requestFingerprint)

    override fun getOutgoingMessage(chatId: String, clientMsgId: String, requestFingerprint: ByteArray?) =
        outgoing.get(chatId, clientMsgId, requestFingerprint)

    override fun findOutgoingFailureCode(chatId: String, clientMsgId: String): OutgoingFailureCode? = outgoing.findFailureCode(chatId, clientMsgId)

    override fun outgoingQueueSnapshot(now: Long): OutgoingQueueSnapshot = outgoing.snapshot(now)
    override fun discardTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
    ): Boolean = outgoing.discard(ownerUid, chatId, clientMsgId)
    override fun replaceTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = outgoing.replace(
        ownerUid, chatId, clientMsgId, replacement, now, requestFingerprint,
    )
    override fun recoverOutgoingMessages(now: Long): List<OutgoingMessage> = outgoing.recoverMessages(now)
    override fun recoverOutgoingState(now: Long) = outgoing.recoverState(now)
    override fun peekNextOutgoingMessage(): OutgoingMessage? = outgoing.peek()
    override fun claimNextOutgoingMessage(now: Long): OutgoingMessage? = outgoing.claim(now)
    override fun markOutgoingMessageRetry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
        failureCode: OutgoingFailureCode,
    ) = outgoing.retry(localOrdinal, error, nextAttemptAt, now, failureCode)
    override fun markOutgoingMessageTerminalFailed(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int?,
        failureCode: OutgoingFailureCode,
    ) = outgoing.fail(localOrdinal, error, now, terminalCode, failureCode)
    override fun completeOutgoingMessage(localOrdinal: Long, ack: MessageAckPayload, now: Long) =
        outgoing.complete(localOrdinal, ack, now)
    override fun cancelOutgoingMessages(reason: String, now: Long) = outgoing.cancel(reason, now)

    override fun beginMessageHistoryLease(
        chatId: String,
        resetResidentWindow: Boolean,
    ): MessageHistoryLease = cacheUseGate.use {
        synchronized(messageHistoryLock) {
            messageHistoryGate.begin(chatId, resetResidentWindow)
        }
    }

    override fun applyMessageHistoryPage(
        lease: MessageHistoryLease,
        messages: List<Message>,
    ): Boolean = cacheUseGate.runIfOpen {
        synchronized(messageHistoryLock) history@{
            messageHistoryGate.consumeIfCurrent(lease) { chatId, resetResidentWindow, protectedIds, liveIds ->
                require(messages.size <= Message.MAX_QUERY_PAGE_SIZE) {
                    "history page cannot contain more than ${Message.MAX_QUERY_PAGE_SIZE} messages"
                }
                requireFakeHistoryPageShape(messages, chatId)
                val protectedInPage = messages.asSequence()
                    .map(Message::clientMsgId)
                    .filter { it in protectedIds }
                    .toSet()
                messages.forEach { message ->
                    if (message.clientMsgId !in protectedInPage) {
                        messageHistoryGate.recordAuthoritativeMutation(
                            message.chatId,
                            message.clientMsgId,
                            excluding = lease,
                        )
                        optimisticMessageEdits.supersede(message.chatId, message.clientMsgId)
                    }
                }
                applyFakeHistoryProjection(
                    messagesMap,
                    outgoingStore,
                    chatId,
                    messages,
                    resetResidentWindow,
                    protectedInPage,
                    liveIds,
                    ::syncFlow,
                )
            }
        }
    }

    override fun abandonMessageHistoryLease(lease: MessageHistoryLease): Boolean =
        cacheUseGate.runIfOpen {
            synchronized(messageHistoryLock) { messageHistoryGate.abandon(lease) }
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

    private val groupFileProjection = FakeGroupFileProjection()

    override fun applyGroupFileUpsert(entry: com.virjar.tk.protocol.model.GroupFileEntry) =
        groupFileProjection.applyUpsert(entry)

    override fun applyGroupFileDelete(chatId: String, entryId: String, tombstoneRevision: Long, updatedBy: String, updatedAt: Long) =
        groupFileProjection.applyDelete(chatId, entryId, tombstoneRevision)

    override fun replaceGroupFileDirectory(chatId: String, parentId: String?, entries: List<com.virjar.tk.protocol.model.GroupFileEntry>) =
        groupFileProjection.replaceDirectory(chatId, parentId, entries)

    override fun activeGroupFileEntries(chatId: String, parentId: String?) =
        groupFileProjection.activeEntries(chatId, parentId)

    override fun observeGroupFileEntries(chatId: String, parentId: String?) =
        groupFileProjection.observe(chatId, parentId)

    override fun purgeGroupFileProjection(chatId: String) = groupFileProjection.purge(chatId)

    // ── 表情回应 ──
    private val reactionProjection = FakeMessageReactionProjection()

    override fun applyMessageReactionDelta(payload: com.virjar.tk.protocol.MessageReactionEventPayload) {
        cacheUseGate.use { reactionProjection.applyDelta(payload) }
    }

    override fun beginMessageReactionSnapshot(chatId: String): ProjectionSnapshotLease =
        cacheUseGate.use { reactionProjection.beginSnapshot(chatId) }

    override fun applyMessageReactionSnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        summaries: List<MessageReactionSummary>,
    ): Boolean = cacheUseGate.runIfOpen {
        reactionProjection.applySnapshot(lease, chatId, fromSeq, toSeq, summaries)
    }

    override fun clearMessageReactions(chatId: String, serverSeq: Long) {
        cacheUseGate.use { reactionProjection.clearMessage(chatId, serverSeq) }
    }

    override fun observeMessageReactions(chatId: String): Flow<Map<Long, List<MessageReactionGroup>>> =
        cacheUseGate.use { reactionProjection.observe(chatId) }

    fun fakeReactionRows(chatId: String): Set<Triple<Long, String, String>> =
        reactionProjection.rows(chatId)

    override fun pager(chatId: String, windowSize: Int): MessagePager = cacheUseGate.use {
        lateinit var pager: SimpleMessagePager
        pager = SimpleMessagePager(chatId, this, cacheUseGate, windowSize) { closed ->
            cacheUseGate.runIfOpen {
                synchronized(pagerLock) {
                    if (activePagers.remove(closed)) {
                        inactiveChats += chatId
                        if (closed.closeOverlappedMessageCollector) {
                            pagerCloseOverlapCountForTest++
                        }
                    }
                }
                true
            }
        }
        synchronized(pagerLock) { activePagers += pager }
        pager
    }

    // ── 用户 ──
    override fun getUser(uid: String) = cacheUseGate.use { people.getUser(uid) }
    override fun observeUser(uid: String) = cacheUseGate.use { people.observeUser(uid) }
    override fun upsertUser(user: User) = cacheUseGate.use { people.upsertUser(user) }
    override fun beginUserSnapshot(uid: String) = cacheUseGate.use { people.beginUserSnapshot(uid) }

    override fun applyUserSnapshot(
        lease: ProjectionSnapshotLease,
        user: User,
    ) = cacheUseGate.use { people.applyUserSnapshot(lease, user) }

    // ── 联系人 ──
    override fun getContacts() = cacheUseGate.use { people.getContacts() }
    override fun observeContacts() = cacheUseGate.use { people.observeContacts() }
    override fun upsertContact(contact: Contact) = cacheUseGate.use { people.upsertContact(contact) }
    override fun deleteContact(friendUid: String) = cacheUseGate.use { people.deleteContact(friendUid) }
    override fun contactProjectionGeneration() = cacheUseGate.use { people.contactProjectionGeneration() }
    override fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>) =
        cacheUseGate.use { people.applyContactSnapshot(expectedGeneration, contacts) }

    // ── 聊天 ──
    override fun getChat(chatId: String): Chat? = cacheUseGate.use { chatsFlow.value.find { it.chatId == chatId } }

    override fun observeChat(chatId: String): Flow<Chat?> = cacheUseGate.use {
        chatsFlow.map { chats -> chats.firstOrNull { it.chatId == chatId } }.distinctUntilChanged()
    }

    override fun upsertChat(chat: Chat) = cacheUseGate.use {
        synchronized(conversationLock) {
            chatSnapshots.invalidate(chat.chatId)
            chatSnapshotLeases.remove(chat.chatId)
            upsertChatLocked(chat)
        }
    }

    override fun beginChatSnapshot(chatId: String): ProjectionSnapshotLease =
        cacheUseGate.use {
            synchronized(conversationLock) {
                chatSnapshots.begin(chatId).also { chatSnapshotLeases[chatId] = it }
            }
        }

    override fun applyChatSnapshot(
        lease: ProjectionSnapshotLease,
        chat: Chat,
    ): Boolean = cacheUseGate.use {
        synchronized(conversationLock) {
            if (chatSnapshotLeases[chat.chatId] !== lease) return@synchronized false
            if (!chatSnapshots.consumeIfCurrent(lease, chat.chatId)) return@synchronized false
            chatSnapshotLeases.remove(chat.chatId)
            upsertChatLocked(chat)
            true
        }
    }

    private fun upsertChatLocked(chat: Chat) {
        val list = chatsFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.chatId == chat.chatId }
        if (idx >= 0) list[idx] = chat else list.add(chat)
        chatsFlow.value = list
        conversationProjection.markMutatedLocked(chat.chatId)
    }

    override fun deleteChat(chatId: String) = cacheUseGate.use {
        synchronized(messageHistoryLock) {
            synchronized(conversationLock) {
                synchronized(messagesMap) {
                    synchronized(botMessageLog) {
                        chatSnapshots.invalidate(chatId)
                        chatSnapshotLeases.remove(chatId)
                        chatsFlow.value = chatsFlow.value.filter { it.chatId != chatId }
                        conversationProjection.deleteForChatTombstoneLocked(chatId)
                        people.removeChat(chatId)
                        messagesMap.remove(chatId)
                        optimisticMessageEdits.supersedeChat(chatId)
                        messagesFlows[chatId]?.value = emptyList()
                        botMessageLog.deleteChat(chatId)
                        outgoingStore.deleteChat(chatId)
                        reactionProjection.deleteChat(chatId)
                        // 保留常驻的 messagesFlows 条目与草稿代次高水位，
                        // 使重放收集器与过期 ACK 门禁的语义和 LocalCacheImpl 保持一致。
                    }
                }
            }
            messageHistoryGate.invalidate(chatId)
        }
    }

    // ── 成员 ──
    override fun getMembers(chatId: String) = cacheUseGate.use { people.getMembers(chatId) }
    override fun observeMembers(chatId: String) = cacheUseGate.use { people.observeMembers(chatId) }
    override fun upsertMember(member: Member) = cacheUseGate.use { people.upsertMember(member) }
    override fun removeMember(chatId: String, uid: String) = cacheUseGate.use { people.removeMember(chatId, uid) }
    override fun beginMemberSnapshot(chatId: String) = cacheUseGate.use { people.beginMemberSnapshot(chatId) }

    override fun applyMemberSnapshot(
        lease: ProjectionSnapshotLease,
        members: List<Member>,
    ) = cacheUseGate.use { people.applyMemberSnapshot(lease, members) }

    // ── 组织目录 ──
    override fun getOrganizationUnitProjection() = withOrganization { getUnitProjection() }
    override fun observeOrganizationUnitProjection() = withOrganization { observeUnitProjection() }
    override fun advanceOrganizationRequiredRevision(revision: Long) = withOrganization { advanceRequiredRevision(revision) }
    override fun upsertOrganizationUnit(unit: OrganizationUnit) = withOrganization { upsertUnit(unit) }
    override fun deleteOrganizationUnit(unitId: String) = withOrganization { deleteUnit(unitId) }
    override fun beginOrganizationUnitSnapshot() = withOrganization { beginUnitSnapshot() }
    override fun applyOrganizationUnitSnapshot(
        lease: ProjectionSnapshotLease, units: List<OrganizationUnit>, revision: Long,
    ) = withOrganization { applyUnitSnapshot(lease, units, revision) }
    override fun getOrganizationMemberProjection(unitId: String) = withOrganization { getMemberProjection(unitId) }
    override fun observeOrganizationMemberProjection(unitId: String) = withOrganization { observeMemberProjection(unitId) }
    override fun getOrganizationMembersForUnits(unitIds: Set<String>) = withOrganization { getMembersForUnits(unitIds) }
    override fun upsertOrganizationMember(member: OrganizationMember) = withOrganization { upsertMember(member) }
    override fun removeOrganizationMember(unitId: String, uid: String) = withOrganization { removeMember(unitId, uid) }
    override fun beginOrganizationMemberSnapshot(unitId: String) = withOrganization { beginMemberSnapshot(unitId) }
    override fun applyOrganizationMemberSnapshot(
        lease: ProjectionSnapshotLease, members: List<OrganizationMember>, revision: Long,
    ) = withOrganization { applyMemberSnapshot(lease, members, revision) }
    private fun <T> withOrganization(action: FakeOrganizationProjectionStore.() -> T): T =
        cacheUseGate.use { organization.action() }
    override fun abandonProjectionSnapshot(lease: ProjectionSnapshotLease): Boolean =
        cacheUseGate.runIfOpen {
            synchronized(conversationLock) {
                val chatAbandoned = chatSnapshots.abandon(lease)
                if (chatAbandoned) removeCurrentFakeLease(chatSnapshotLeases, lease)
                chatAbandoned || people.abandonSnapshot(lease) {
                    organization.abandonSnapshot(lease)
                } || documents.abandonSnapshot(lease) || reactionProjection.abandonSnapshot(lease)
            }
        }

    override fun getDocumentSpaces(): List<DocumentSpace> =
        cacheUseGate.use { documents.getSpaces() }

    override fun isDocumentSpaceSnapshotCached(): Boolean =
        cacheUseGate.use { documents.isSpaceSnapshotCached() }

    override fun beginDocumentSpaceSnapshot(): ProjectionSnapshotLease =
        cacheUseGate.use { documents.beginSpaceSnapshot() }

    override fun applyDocumentSpaceSnapshot(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
    ): Boolean = cacheUseGate.runIfOpen { documents.applySpaceSnapshot(lease, spaces) }

    override fun applyDocumentSpacePage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
    ): Boolean = cacheUseGate.runIfOpen { documents.applySpacePage(lease, spaces, isFirstPage) }

    override fun applyDocumentSpaceRefreshPage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
        isTerminal: Boolean,
    ): Boolean = cacheUseGate.runIfOpen {
        documents.applySpaceRefreshPage(lease, spaces, isFirstPage, isTerminal)
    }

    override fun beginDocumentSpaceMutationSnapshot(spaceId: String): ProjectionSnapshotLease =
        cacheUseGate.use { documents.beginSpaceMutationSnapshot(spaceId) }

    override fun applyDocumentSpaceMutation(
        projectionLease: ProjectionSnapshotLease,
        space: DocumentSpace,
    ): Boolean = cacheUseGate.runIfOpen {
        documents.applySpaceMutation(projectionLease, space)
    }

    override fun getDocumentHome(collection: com.virjar.tk.shared.client.DocumentHomeCollection): List<DocumentHomeItem> =
        cacheUseGate.use { documents.getHome(collection) }

    override fun isDocumentHomeSnapshotCached(
        collection: com.virjar.tk.shared.client.DocumentHomeCollection,
    ): Boolean = cacheUseGate.use { documents.isHomeSnapshotCached(collection) }

    override fun beginDocumentHomeSnapshot(
        collection: com.virjar.tk.shared.client.DocumentHomeCollection,
    ): ProjectionSnapshotLease = cacheUseGate.use { documents.beginHomeSnapshot(collection) }

    override fun applyDocumentHomeSnapshot(
        lease: ProjectionSnapshotLease,
        collection: com.virjar.tk.shared.client.DocumentHomeCollection,
        items: List<DocumentHomeItem>,
    ): Boolean = cacheUseGate.runIfOpen { documents.applyHomeSnapshot(lease, collection, items) }

    override fun isDocumentBranchCached(spaceId: String, parentId: String?): Boolean =
        cacheUseGate.use { documents.isBranchCached(spaceId, parentId) }

    override fun getDocumentNodes(spaceId: String, parentId: String?): List<DocumentNode> =
        cacheUseGate.use { documents.getNodes(spaceId, parentId) }

    override fun beginDocumentBranchSnapshot(
        spaceId: String,
        parentId: String?,
    ): ProjectionSnapshotLease = cacheUseGate.use { documents.beginBranchSnapshot(spaceId, parentId) }

    override fun applyDocumentBranchSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        parentId: String?,
        nodes: List<DocumentNode>,
    ): Boolean = cacheUseGate.runIfOpen {
        documents.applyBranchSnapshot(lease, spaceId, parentId, nodes)
    }

    override fun getDocumentPathSpine(spaceId: String, nodeId: String): DocumentPathSpine? =
        cacheUseGate.use { documents.getPathSpine(spaceId, nodeId) }

    override fun beginDocumentPathSpineSnapshot(
        spaceId: String,
        nodeId: String,
    ): ProjectionSnapshotLease = cacheUseGate.use {
        documents.beginPathSpineSnapshot(spaceId, nodeId)
    }

    override fun applyDocumentPathSpineSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        nodeId: String,
        spine: DocumentPathSpine,
    ): Boolean = cacheUseGate.runIfOpen {
        documents.applyPathSpineSnapshot(lease, spaceId, nodeId, spine)
    }

    override fun getDocumentBody(spaceId: String, documentId: String): Document? =
        cacheUseGate.use { documents.getBody(spaceId, documentId) }

    override fun beginDocumentBodySnapshot(
        spaceId: String,
        documentId: String,
    ): ProjectionSnapshotLease = cacheUseGate.use { documents.beginBodySnapshot(spaceId, documentId) }

    override fun beginDocumentBodyMutationSnapshot(
        spaceId: String,
        documentId: String,
    ): ProjectionSnapshotLease = cacheUseGate.use {
        documents.beginBodyMutationSnapshot(spaceId, documentId)
    }

    override fun applyDocumentBodySnapshot(
        lease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = cacheUseGate.runIfOpen { documents.applyBodySnapshot(lease, document) }

    override fun applyDocumentBodyMutation(
        projectionLease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = cacheUseGate.runIfOpen {
        documents.applyBodyMutation(projectionLease, document)
    }

    override fun applyDocumentMove(
        projectionLease: ProjectionSnapshotLease,
        result: com.virjar.tk.protocol.model.DocumentMoveResult,
    ): Boolean = cacheUseGate.runIfOpen {
        documents.applyMove(projectionLease, result)
    }

    override fun purgeDocumentSpace(spaceId: String) = cacheUseGate.use { documents.purgeSpace(spaceId) }

    override fun purgeDocument(spaceId: String, documentId: String) =
        cacheUseGate.use { documents.purgeDocument(spaceId, documentId) }

    fun activeProjectionSnapshotCountForTest(): Int = synchronized(conversationLock) {
        chatSnapshotLeases.size + people.activeSnapshotCount {
            organization.activeSnapshotCountForTest()
        } + documents.activeSnapshotCountForTest()
    }

    fun residentOrganizationMemberProjectionCountForTest(): Int =
        organization.residentMemberProjectionCountForTest()

    // ── 会话 ──
    override fun getConversations() = cacheUseGate.use { conversationProjection.get() }
    override fun observeConversations() = cacheUseGate.use { conversationProjection.observe() }
    override fun upsertConversation(conv: Conversation) =
        cacheUseGate.use { conversationProjection.upsert(conv) }
    override fun beginConversationSnapshot() = cacheUseGate.use { conversationProjection.beginSnapshot() }
    override fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ) = cacheUseGate.use { conversationProjection.applySnapshot(snapshotGeneration, conversations) }
    override fun setConversationDraft(chatId: String, draft: String?) =
        cacheUseGate.use { conversationProjection.setDraft(chatId, draft) }
    override fun getPendingConversationDrafts() =
        cacheUseGate.use { conversationProjection.pendingDrafts() }
    override fun getPendingConversationDraft(chatId: String) =
        cacheUseGate.use { conversationProjection.pendingDraft(chatId) }
    override fun markConversationDraftMirrored(chatId: String, generation: Long) =
        cacheUseGate.use { conversationProjection.markDraftMirrored(chatId, generation) }
    override fun deleteConversation(chatId: String) =
        cacheUseGate.use { conversationProjection.delete(chatId) }
    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) =
        cacheUseGate.use { conversationProjection.updatePeerReadSeq(chatId, peerReadSeq) }
    override fun getSyncState() = cacheUseGate.use { syncState.get() }
    override fun bindSyncDataset(datasetId: String) = cacheUseGate.use { syncState.bind(datasetId) }
    override fun advanceSyncCursor(expectedDatasetId: String, eventId: Long) =
        cacheUseGate.use { syncState.advance(expectedDatasetId, eventId) }

    override fun applyServerProjectionCheckpoint(
        expectedDatasetId: String,
        expectedCursor: Long,
        checkpoint: ServerProjectionCheckpoint,
    ) = cacheUseGate.use {
        require(checkpoint.datasetId == expectedDatasetId) {
            "checkpoint belongs to a different server dataset"
        }
        synchronized(messageHistoryLock) {
            val appliedState = people.withProjectionCheckpoint(
                currentUser = checkpoint.currentUser,
                contacts = checkpoint.contacts,
            ) { applyPeopleProjection ->
                synchronized(conversationLock) {
                    synchronized(messagesMap) {
                        syncState.applyCheckpoint(
                            expectedDatasetId = expectedDatasetId,
                            expectedCursor = expectedCursor,
                            baseEventId = checkpoint.baseEventId,
                        ) {
                            chatSnapshots.reset()
                            chatSnapshotLeases.clear()
                            chatsFlow.value = checkpoint.chats
                            applyPeopleProjection()
                            optimisticMessageEdits.supersedeAll()
                            val outgoingProjection = outgoing.projectionAfterReset()
                            messagesMap.clear()
                            messagesFlows.values.forEach { it.value = emptyList() }
                            outgoingProjection.forEach { restored ->
                                val list = messagesMap.getOrPut(restored.chatId) { mutableListOf() }
                                list += restored
                                list.sortWith(fakeMessageOrder)
                                messagesFlows[restored.chatId]?.value = list.toList()
                            }
                            conversationProjection.applyServerCheckpointLocked(checkpoint.conversations)
                            reactionProjection.reset()
                        }
                    }
                }
            }
            messageHistoryGate.reset()
            appliedState
        }
    }

    override fun resetServerProjection(datasetId: String) = cacheUseGate.use {
        synchronized(messageHistoryLock) {
            people.withProjectionReset { resetPeopleProjection ->
                synchronized(conversationLock) {
                    synchronized(messagesMap) {
                        syncState.resetProjection(datasetId) {
                            synchronized(botMessageLog) {
                                chatSnapshots.reset()
                                chatSnapshotLeases.clear()
                                chatsFlow.value = emptyList()
                                resetPeopleProjection()
                                organization.resetServerProjection()
                                documents.resetProjection()
                                optimisticMessageEdits.supersedeAll()
                                val outgoingProjection = outgoing.projectionAfterReset()
                                messagesMap.clear()
                                messagesFlows.values.forEach { it.value = emptyList() }
                                botMessageLog.reset()
                                outgoingProjection.forEach { restored ->
                                    val list = messagesMap.getOrPut(restored.chatId) { mutableListOf() }
                                    list += restored
                                    list.sortWith(fakeMessageOrder)
                                    messagesFlows[restored.chatId]?.value = list.toList()
                                }
                                conversationProjection.resetServerProjectionLocked()
                                reactionProjection.reset()
                                // 草稿/已读可靠发件箱属于本地可靠事实。保留它们（以及草稿
                                // 高水位），使重放能够安全地重新叠加它们。
                            }
                        }
                    }
                }
            }
            messageHistoryGate.reset()
        }
    }

    override fun close() {
        cacheUseGate.close {
            synchronized(messageHistoryLock) {
                messageHistoryGate.reset()
                synchronized(conversationLock) {
                    chatSnapshots.reset()
                    chatSnapshotLeases.clear()
                }
                people.close()
                organization.close()
                documents.close()
                optimisticMessageEdits.close()
                reactionProjection.reset()
                synchronized(pagerLock) {
                    activePagers.forEach { pager -> pager.retireFromCache() }
                    activePagers.clear()
                }
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
        synchronized(messagesMap) {
            val list = messagesMap[chatId] ?: return@synchronized
            val index = list.indexOfFirst { it.clientMsgId == clientMsgId }
            if (index < 0) return@synchronized
            val replacement = transform(list[index])
            optimisticMessageEdits.supersede(chatId, clientMsgId)
            list[index] = replacement
            list.sortWith(fakeMessageOrder)
            syncFlow(chatId)
        }
    }

    override fun enqueueConversationRead(chatId: String, readSeq: Long): Long = cacheUseGate.use {
        conversationProjection.enqueueRead(chatId, readSeq)
    }

    override fun getPendingConversationReads() = cacheUseGate.use {
        conversationProjection.pendingReads()
    }

    override fun getPendingConversationRead(chatId: String) = cacheUseGate.use {
        conversationProjection.pendingRead(chatId)
    }

    override fun markConversationReadMirrored(chatId: String, readSeq: Long) = cacheUseGate.use {
        conversationProjection.markReadMirrored(chatId, readSeq)
    }

}
