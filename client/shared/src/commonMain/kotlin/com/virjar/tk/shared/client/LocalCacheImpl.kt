package com.virjar.tk.shared.client

import app.cash.sqldelight.db.SqlDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.flow.Flow

/** 原子转移 driver ownership：构造失败绝不能泄漏已打开的句柄。 */
internal fun createLocalCacheWithOwnedDriver(
    driver: SqlDriver,
    terminalReceiptLimit: Int = MAX_TERMINAL_OUTGOING_RECEIPTS,
): LocalCacheImpl {
    try {
        return LocalCacheImpl(driver, terminalReceiptLimit)
    } catch (constructionFailure: Throwable) {
        closeOwnedDriverAfterFailure(driver, constructionFailure)
    }
}

/** 在释放 ownership 已转移的 driver 时保留主因/致命因的排序。 */
internal fun closeOwnedDriverAfterFailure(driver: SqlDriver, primaryFailure: Throwable): Nothing {
    var terminalFailure = primaryFailure
    try {
        driver.close()
    } catch (closeFailure: Throwable) {
        terminalFailure = mergeSessionLifecycleFailures(terminalFailure, closeFailure)
    }
    throw terminalFailure
}

/**
 * SQLDelight 支撑的 LocalCache 组合根。
 *
 * 该门面拥有 driver、一个准入门禁与一把状态锁。其协作者按各自实际的一致性规则划分实体投影、
 * 会话、消息与持久投递，同时共享这两个同步边界。跨投影墓碑与 reset 保留在这里，因为它们刻意
 * 在单个 SQL 事务中跨越每个存储。
 */
class LocalCacheImpl internal constructor(
    private val driver: SqlDriver,
    private val outboxLimits: LocalOutboxLimits,
    private val messageRetentionLimits: LocalMessageRetentionLimits =
        DEFAULT_LOCAL_MESSAGE_RETENTION_LIMITS,
) : LocalCache {
    constructor(
        driver: SqlDriver,
        terminalReceiptLimit: Int = MAX_TERMINAL_OUTGOING_RECEIPTS,
    ) : this(
        driver = driver,
        outboxLimits = DEFAULT_LOCAL_OUTBOX_LIMITS.copy(terminalOutgoingCount = terminalReceiptLimit),
    )

    private val database = AppDatabase(driver)
    private val queries = database.appDatabaseQueries
    private val stateLock = Any()
    private val cacheUseGate = CacheUseGate()

    private val entities: LocalEntityProjectionStore
    private val conversations = LocalConversationProjectionStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        outboxLimits = outboxLimits,
        materializeTransientPeerUsersLocked = { peerUids ->
            entities.materializeRecentTransientUsersForRelationsLocked(peerUids)
        },
    )
    private val organization = LocalOrganizationProjectionStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        mergeUserLocked = { user -> entities.mergeAuthoritativeUserLocked(user) },
        publishUserMergeLocked = { merge -> entities.publishAuthoritativeUserMergeLocked(merge) },
    )
    init {
        entities = LocalEntityProjectionStore(
            queries = queries,
            cacheUseGate = cacheUseGate,
            stateLock = stateLock,
            markConversationMutatedLocked = conversations::markConversationMutatedLocked,
            persistExternalUserProjectionLocked = organization::persistUserLocked,
            publishExternalUserProjectionLocked = organization::publishUserLocked,
        )
    }
    private val documents = LocalDocumentProjectionStore(queries, cacheUseGate, stateLock)
    private val reactions = LocalMessageReactionStore(queries, cacheUseGate, stateLock)
    internal val groupFileEntries = LocalGroupFileEntryStore(queries, cacheUseGate, stateLock)
    private val messages = LocalMessageStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        outboxLimits = outboxLimits,
        retentionLimits = messageRetentionLimits,
        refreshReactionsAfterPrune = reactions::refreshResidentAfterPrune,
    )
    private val messageProjectionReset = LocalMessageProjectionResetStore(queries)
    private val deliveryLog = LocalDeliveryLogStore(queries, cacheUseGate, stateLock)
    private val groupCreationCommands = LocalGroupCreationCommandStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
    )
    private val reliableSocialCommands = LocalReliableSocialCommandStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
    )
    private val groupBotCredentialCommands = LocalGroupBotCredentialCommandStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
    )
    private val groupFileCommands = LocalGroupFileCommandStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        limits = outboxLimits,
    )
    private val documentMoveCommands = LocalDocumentMoveCommandStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
    )

    override fun getPendingGroupCreation(): PendingGroupCreationCommand? =
        groupCreationCommands.get()

    override fun replacePendingGroupCreation(command: PendingGroupCreationCommand) =
        groupCreationCommands.replace(command)

    override fun clearPendingGroupCreation(operationId: String): Boolean =
        groupCreationCommands.clearIfOperation(operationId)

    override fun preparePendingContactDecision(
        candidate: PendingContactDecision,
    ): PendingContactDecision = reliableSocialCommands.prepareContact(candidate)

    override fun getPendingContactDecisions(): List<PendingContactDecision> =
        reliableSocialCommands.contacts()

    override fun clearPendingContactDecision(operationId: String): Boolean =
        reliableSocialCommands.clearContact(operationId)

    override fun preparePendingInviteLinkCreation(
        candidate: PendingInviteLinkCreation,
    ): PendingInviteLinkCreation = reliableSocialCommands.prepareInvite(candidate)

    override fun getPendingInviteLinkCreations(): List<PendingInviteLinkCreation> =
        reliableSocialCommands.invites()

    override fun clearPendingInviteLinkCreation(operationId: String): Boolean =
        reliableSocialCommands.clearInvite(operationId)

    override fun getPendingGroupBotCredentialCommand(): PendingGroupBotCredentialCommand? =
        groupBotCredentialCommands.get()

    override fun preparePendingGroupBotCredentialCommand(
        command: PendingGroupBotCredentialCommand,
    ): PendingGroupBotCredentialCommand = groupBotCredentialCommands.prepare(command)

    override fun clearPendingGroupBotCredentialCommand(operationId: String): Boolean =
        groupBotCredentialCommands.clearIfOperation(operationId)

    override fun preparePendingGroupFileCommand(
        candidate: PendingGroupFileCommand,
    ): PendingGroupFileCommand = groupFileCommands.prepare(candidate)

    override fun getPendingGroupFileCommands(): List<PendingGroupFileCommand> =
        groupFileCommands.snapshot()

    override fun clearPendingGroupFileCommand(commandId: String): Boolean =
        groupFileCommands.clear(commandId)

    override fun preparePendingDocumentMoveCommand(
        candidate: PendingDocumentMoveCommand,
    ): PendingDocumentMoveCommand = documentMoveCommands.prepare(candidate)

    override fun getPendingDocumentMoveCommands(): List<PendingDocumentMoveCommand> =
        documentMoveCommands.snapshot()

    override fun clearPendingDocumentMoveCommand(operationId: String): Boolean =
        documentMoveCommands.clear(operationId)

    /** 现有 JVM 并发接缝；行为与可见性保持不变。 */
    internal var windowSnapshotLoadedHookForTest: (() -> Unit)?
        get() = messages.windowSnapshotLoadedHookForTest
        set(value) {
            messages.windowSnapshotLoadedHookForTest = value
        }

    override fun getUser(uid: String): User? = entities.getUser(uid)
    override fun observeUser(uid: String): Flow<User?> = entities.observeUser(uid)
    override fun upsertUser(user: User) = entities.upsertUser(user)
    override fun upsertTransientUserIfRelevant(user: User): Boolean =
        entities.upsertTransientUserIfRelevant(user)
    override fun beginUserSnapshot(uid: String): ProjectionSnapshotLease = entities.beginUserSnapshot(uid)
    override fun applyUserSnapshot(lease: ProjectionSnapshotLease, user: User): Boolean =
        entities.applyUserSnapshot(lease, user)

    override fun getContacts(): List<Contact> = entities.getContacts()
    override fun observeContacts(): Flow<List<Contact>> = entities.observeContacts()
    override fun upsertContact(contact: Contact) = entities.upsertContact(contact)
    override fun deleteContact(friendUid: String) = entities.deleteContact(friendUid)
    override fun contactProjectionGeneration(): Long = entities.contactProjectionGeneration()
    override fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean =
        entities.applyContactSnapshot(expectedGeneration, contacts)

    override fun getChat(chatId: String): Chat? = entities.getChat(chatId)
    override fun observeChat(chatId: String): Flow<Chat?> = entities.observeChat(chatId)
    override fun upsertChat(chat: Chat) = entities.upsertChat(chat)
    override fun beginChatSnapshot(chatId: String): ProjectionSnapshotLease =
        entities.beginChatSnapshot(chatId)

    override fun applyChatSnapshot(lease: ProjectionSnapshotLease, chat: Chat): Boolean =
        entities.applyChatSnapshot(lease, chat)

    override fun deleteChat(chatId: String) {
        cacheUseGate.use {
            synchronized(stateLock) {
                queries.transaction {
                    queries.deleteConversationDraftOutbox(chatId)
                    queries.deleteConversationReadOutbox(chatId)
                    queries.deleteBotMessagesByChat(chatId)
                    queries.deleteOutgoingMessagesByChat(chatId)
                    queries.deleteMessagesByChat(chatId)
                    queries.deleteAllMessageReactionsForChat(chatId)
                    queries.deleteMembersByChat(chatId)
                    queries.deleteConversation(chatId)
                    queries.deleteChat(chatId)
                }
                messages.invalidateChatHistoryLocked(chatId)
                entities.invalidateChatAndRemoveChatLocked(chatId)
                conversations.removeChatProjectionLocked(chatId)
                entities.removeChatMembersLocked(chatId)
                // 即使重放的墓碑也会隔断一个更旧的 listConversations 响应。
                conversations.markConversationMutatedLocked(chatId)
                messages.resetResidentChatLocked(chatId)
                reactions.refreshResidentAfterPrune(chatId)
            }
        }
    }

    override fun getMembers(chatId: String): List<Member> = entities.getMembers(chatId)
    override fun observeMembers(chatId: String): Flow<List<Member>> = entities.observeMembers(chatId)
    override fun upsertMember(member: Member) = entities.upsertMember(member)
    override fun removeMember(chatId: String, uid: String) = entities.removeMember(chatId, uid)
    override fun beginMemberSnapshot(chatId: String): ProjectionSnapshotLease =
        entities.beginMemberSnapshot(chatId)

    override fun applyMemberSnapshot(
        lease: ProjectionSnapshotLease,
        members: List<Member>,
    ): Boolean = entities.applyMemberSnapshot(lease, members)

    override fun getOrganizationUnitProjection(): OrganizationUnitProjection =
        organization.getUnitProjection()

    override fun observeOrganizationUnitProjection(): Flow<OrganizationUnitProjection> =
        organization.observeUnitProjection()

    override fun advanceOrganizationRequiredRevision(revision: Long): Long =
        organization.advanceRequiredRevision(revision)
    override fun upsertOrganizationUnit(unit: OrganizationUnit) = organization.upsertUnit(unit)
    override fun deleteOrganizationUnit(unitId: String) = organization.deleteUnit(unitId)
    override fun beginOrganizationUnitSnapshot(): ProjectionSnapshotLease =
        organization.beginUnitSnapshot()

    override fun applyOrganizationUnitSnapshot(
        lease: ProjectionSnapshotLease,
        units: List<OrganizationUnit>,
        revision: Long,
    ): Boolean = organization.applyUnitSnapshot(lease, units, revision)

    override fun getOrganizationMemberProjection(unitId: String): OrganizationMemberProjection =
        organization.getMemberProjection(unitId)

    override fun observeOrganizationMemberProjection(
        unitId: String,
    ): Flow<OrganizationMemberProjection> = organization.observeMemberProjection(unitId)

    override fun getOrganizationMembersForUnits(unitIds: Set<String>): List<OrganizationMember> =
        organization.getMembersForUnits(unitIds)

    override fun upsertOrganizationMember(member: OrganizationMember) = organization.upsertMember(member)
    override fun removeOrganizationMember(unitId: String, uid: String) =
        organization.removeMember(unitId, uid)

    override fun beginOrganizationMemberSnapshot(unitId: String): ProjectionSnapshotLease =
        organization.beginMemberSnapshot(unitId)

    override fun applyOrganizationMemberSnapshot(
        lease: ProjectionSnapshotLease,
        members: List<OrganizationMember>,
        revision: Long,
    ): Boolean = organization.applyMemberSnapshot(lease, members, revision)

    override fun abandonProjectionSnapshot(lease: ProjectionSnapshotLease): Boolean =
        entities.abandonProjectionSnapshot(lease) ||
            organization.abandonSnapshot(lease) ||
            documents.abandonSnapshot(lease) ||
            reactions.abandonSnapshot(lease)

    override fun getDocumentSpaces(): List<DocumentSpace> = documents.getSpaces()

    override fun isDocumentSpaceSnapshotCached(): Boolean = documents.isSpaceSnapshotCached()

    override fun beginDocumentSpaceSnapshot(): ProjectionSnapshotLease =
        documents.beginSpaceSnapshot()

    override fun applyDocumentSpaceSnapshot(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
    ): Boolean = documents.applySpaceSnapshot(lease, spaces)

    override fun applyDocumentSpacePage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
    ): Boolean = documents.applySpacePage(lease, spaces, isFirstPage)

    override fun applyDocumentSpaceRefreshPage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
        isTerminal: Boolean,
    ): Boolean = documents.applySpaceRefreshPage(lease, spaces, isFirstPage, isTerminal)

    override fun beginDocumentSpaceMutationSnapshot(spaceId: String): ProjectionSnapshotLease =
        documents.beginSpaceMutationSnapshot(spaceId)

    override fun applyDocumentSpaceMutation(
        projectionLease: ProjectionSnapshotLease,
        space: DocumentSpace,
    ): Boolean = documents.applySpaceMutation(projectionLease, space)

    override fun getDocumentHome(collection: DocumentHomeCollection): List<DocumentHomeItem> =
        documents.getHome(collection)

    override fun isDocumentHomeSnapshotCached(collection: DocumentHomeCollection): Boolean =
        documents.isHomeSnapshotCached(collection)

    override fun beginDocumentHomeSnapshot(collection: DocumentHomeCollection): ProjectionSnapshotLease =
        documents.beginHomeSnapshot(collection)

    override fun applyDocumentHomeSnapshot(
        lease: ProjectionSnapshotLease,
        collection: DocumentHomeCollection,
        items: List<DocumentHomeItem>,
    ): Boolean = documents.applyHomeSnapshot(lease, collection, items)

    override fun isDocumentBranchCached(spaceId: String, parentId: String?): Boolean =
        documents.isBranchCached(spaceId, parentId)

    override fun getDocumentNodes(spaceId: String, parentId: String?): List<DocumentNode> =
        documents.getNodes(spaceId, parentId)

    override fun beginDocumentBranchSnapshot(
        spaceId: String,
        parentId: String?,
    ): ProjectionSnapshotLease = documents.beginBranchSnapshot(spaceId, parentId)

    override fun applyDocumentBranchSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        parentId: String?,
        nodes: List<DocumentNode>,
    ): Boolean = documents.applyBranchSnapshot(lease, spaceId, parentId, nodes)

    override fun getDocumentPathSpine(spaceId: String, nodeId: String): DocumentPathSpine? =
        documents.getPathSpine(spaceId, nodeId)

    override fun beginDocumentPathSpineSnapshot(
        spaceId: String,
        nodeId: String,
    ): ProjectionSnapshotLease = documents.beginPathSpineSnapshot(spaceId, nodeId)

    override fun applyDocumentPathSpineSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        nodeId: String,
        spine: DocumentPathSpine,
    ): Boolean = documents.applyPathSpineSnapshot(lease, spaceId, nodeId, spine)

    override fun getDocumentBody(spaceId: String, documentId: String): Document? =
        documents.getBody(spaceId, documentId)

    override fun beginDocumentBodySnapshot(
        spaceId: String,
        documentId: String,
    ): ProjectionSnapshotLease = documents.beginBodySnapshot(spaceId, documentId)

    override fun beginDocumentBodyMutationSnapshot(
        spaceId: String,
        documentId: String,
    ): ProjectionSnapshotLease = documents.beginBodyMutationSnapshot(spaceId, documentId)

    override fun applyDocumentBodySnapshot(
        lease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = documents.applyBodySnapshot(lease, document)

    override fun applyDocumentBodyMutation(
        projectionLease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = documents.applyBodyMutation(projectionLease, document)

    override fun applyDocumentMove(
        projectionLease: ProjectionSnapshotLease,
        result: com.virjar.tk.protocol.model.DocumentMoveResult,
    ): Boolean = documents.applyMove(projectionLease, result)

    override fun purgeDocumentSpace(spaceId: String) = documents.purgeSpace(spaceId)

    override fun purgeDocument(spaceId: String, documentId: String) =
        documents.purgeDocument(spaceId, documentId)

    internal fun residentOrganizationMemberProjectionCountForTest(): Int =
        organization.residentMemberProjectionCountForTest()

    internal fun residentEntityProjectionCountsForTest(): EntityProjectionResidentCounts =
        entities.residentCountsForTest()

    internal fun recentTransientUserCountForTest(): Int =
        entities.recentTransientUserCountForTest()

    override fun getMessages(chatId: String, limit: Int): List<Message> =
        messages.getMessages(chatId, limit)

    override fun findMessage(chatId: String, clientMsgId: String): Message? =
        messages.findMessage(chatId, clientMsgId)

    override fun insertMessage(message: Message) {
        messages.insertMessage(message)
        if (message.serverSeq > 0L && message.flags and Message.FLAG_REVOKED != 0) {
            // 撤回消息不再展示回应；服务端在同一投影事务里删除了权威行。
            reactions.clearMessageReactions(message.chatId, message.serverSeq)
        }
    }

    override fun reserveOptimisticMessageEdit(message: Message): OptimisticMessageEditLease? =
        messages.reserveOptimisticMessageEdit(message)

    override fun publishOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean =
        messages.publishOptimisticMessageEdit(lease)

    override fun commitOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean =
        messages.commitOptimisticMessageEdit(lease)

    override fun rollbackOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean =
        messages.rollbackOptimisticMessageEdit(lease)

    override fun enqueueOutgoingMessage(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = messages.enqueueOutgoingMessage(message, now, requestFingerprint)

    override fun getOutgoingMessage(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = messages.getOutgoingMessage(chatId, clientMsgId, requestFingerprint)

    override fun findOutgoingFailureCode(
        chatId: String,
        clientMsgId: String,
    ): OutgoingFailureCode? = messages.findOutgoingFailureCode(chatId, clientMsgId)

    override fun outgoingQueueSnapshot(now: Long): OutgoingQueueSnapshot =
        messages.outgoingQueueSnapshot(now)

    override fun discardTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
    ): Boolean = messages.discardTerminalFailure(ownerUid, chatId, clientMsgId)

    override fun replaceTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = messages.replaceTerminalFailure(
        ownerUid,
        chatId,
        clientMsgId,
        replacement,
        now,
        requestFingerprint,
    )

    override fun recoverOutgoingMessages(now: Long): List<OutgoingMessage> =
        messages.recoverOutgoingMessages(now)

    override fun recoverOutgoingState(now: Long) = messages.recoverOutgoingState(now)

    override fun peekNextOutgoingMessage(): OutgoingMessage? = messages.peekNextOutgoingMessage()
    override fun claimNextOutgoingMessage(now: Long): OutgoingMessage? =
        messages.claimNextOutgoingMessage(now)

    override fun markOutgoingMessageRetry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
        failureCode: OutgoingFailureCode,
    ) = messages.markOutgoingMessageRetry(localOrdinal, error, nextAttemptAt, now, failureCode)

    override fun markOutgoingMessageTerminalFailed(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int?,
        failureCode: OutgoingFailureCode,
    ) = messages.markOutgoingMessageTerminalFailed(
        localOrdinal,
        error,
        now,
        terminalCode,
        failureCode,
    )

    override fun completeOutgoingMessage(localOrdinal: Long, ack: MessageAckPayload, now: Long) =
        messages.completeOutgoingMessage(localOrdinal, ack, now)

    override fun cancelOutgoingMessages(reason: String, now: Long) =
        messages.cancelOutgoingMessages(reason, now)

    override fun beginMessageHistoryLease(
        chatId: String,
        resetResidentWindow: Boolean,
    ): MessageHistoryLease = messages.beginMessageHistoryLease(chatId, resetResidentWindow)

    override fun applyMessageHistoryPage(
        lease: MessageHistoryLease,
        messages: List<Message>,
    ): Boolean = this.messages.applyMessageHistoryPage(lease, messages)

    override fun abandonMessageHistoryLease(lease: MessageHistoryLease): Boolean =
        messages.abandonMessageHistoryLease(lease)

    override fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) =
        messages.updateMessage(chatId, clientMsgId, serverSeq)

    override fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) =
        messages.updateMessageStatus(chatId, clientMsgId, sendStatus)

    override fun updateMessageInMemory(
        chatId: String,
        clientMsgId: String,
        transform: (Message) -> Message,
    ) = messages.updateMessageInMemory(chatId, clientMsgId, transform)

    override fun pager(chatId: String, windowSize: Int): MessagePager = messages.pager(chatId, windowSize)

    // ── 群共享文件投影 ──
    override fun applyGroupFileUpsert(entry: com.virjar.tk.protocol.model.GroupFileEntry) =
        groupFileEntries.applyUpsert(entry)

    override fun applyGroupFileDelete(
        chatId: String,
        entryId: String,
        tombstoneRevision: Long,
        updatedBy: String,
        updatedAt: Long,
    ) = groupFileEntries.applyDelete(chatId, entryId, tombstoneRevision, updatedBy, updatedAt)

    override fun replaceGroupFileDirectory(
        chatId: String,
        parentId: String?,
        entries: List<com.virjar.tk.protocol.model.GroupFileEntry>,
    ) = groupFileEntries.replaceDirectory(chatId, parentId, entries)

    override fun activeGroupFileEntries(chatId: String, parentId: String?) =
        groupFileEntries.activeEntries(chatId, parentId)

    override fun observeGroupFileEntries(chatId: String, parentId: String?): Flow<List<com.virjar.tk.protocol.model.GroupFileEntry>> =
        groupFileEntries.observe(chatId, parentId)

    override fun purgeGroupFileProjection(chatId: String) =
        groupFileEntries.purgeChat(chatId)

    // ── 表情回应 ──
    override fun applyMessageReactionDelta(payload: MessageReactionEventPayload) =
        reactions.applyReactionDelta(payload)

    override fun beginMessageReactionSnapshot(chatId: String): ProjectionSnapshotLease =
        reactions.beginSnapshot(chatId)

    override fun applyMessageReactionSnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        summaries: List<MessageReactionSummary>,
    ): Boolean = reactions.applySnapshot(lease, chatId, fromSeq, toSeq, summaries)

    override fun clearMessageReactions(chatId: String, serverSeq: Long) {
        reactions.clearMessageReactions(chatId, serverSeq)
    }

    override fun observeMessageReactions(chatId: String): Flow<Map<Long, List<MessageReactionGroup>>> =
        reactions.observeMessageReactions(chatId)

    internal fun residentMessageWindowCountsForTest(): MessageWindowResidentCounts =
        messages.residentWindowCountsForTest()

    internal var optimisticEditPublishedHookForTest: (() -> Unit)?
        get() = messages.optimisticEditPublishedHookForTest
        set(value) {
            messages.optimisticEditPublishedHookForTest = value
        }

    override fun getConversations(): List<Conversation> = conversations.getConversations()
    override fun observeConversations(): Flow<List<Conversation>> = conversations.observeConversations()
    override fun upsertConversation(conv: Conversation) = conversations.upsertConversation(conv)
    override fun deleteConversation(chatId: String) = conversations.deleteConversation(chatId)
    override fun beginConversationSnapshot(): Long = conversations.beginConversationSnapshot()
    override fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean = this.conversations.applyConversationSnapshot(snapshotGeneration, conversations)

    override fun enqueueConversationRead(chatId: String, readSeq: Long): Long =
        conversations.enqueueConversationRead(chatId, readSeq)

    override fun getPendingConversationReads(): List<PendingConversationRead> =
        conversations.getPendingConversationReads()

    override fun getPendingConversationRead(chatId: String): PendingConversationRead? =
        conversations.getPendingConversationRead(chatId)

    override fun markConversationReadMirrored(chatId: String, readSeq: Long) =
        conversations.markConversationReadMirrored(chatId, readSeq)

    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) =
        conversations.updatePeerReadSeq(chatId, peerReadSeq)

    override fun getSyncState(): ServerProjectionSyncState? = deliveryLog.getSyncState()
    override fun bindSyncDataset(datasetId: String): ServerProjectionSyncState =
        deliveryLog.bindSyncDataset(datasetId)
    override fun advanceSyncCursor(
        expectedDatasetId: String,
        eventId: Long,
    ): ServerProjectionSyncState = deliveryLog.advanceSyncCursor(expectedDatasetId, eventId)

    override fun applyServerProjectionCheckpoint(
        expectedDatasetId: String,
        expectedCursor: Long,
        checkpoint: ServerProjectionCheckpoint,
    ): ServerProjectionSyncState = cacheUseGate.use {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(expectedDatasetId)
        require(expectedCursor >= 0L) { "expectedCursor must be non-negative" }
        require(checkpoint.datasetId == expectedDatasetId) {
            "checkpoint belongs to a different server dataset"
        }
        synchronized(stateLock) {
            val conversationPlan = conversations.prepareServerCheckpointLocked(checkpoint.conversations)
            val userPlan = entities.prepareServerCheckpointLocked(
                checkpoint = checkpoint,
                projectedConversations = conversationPlan.conversations.values,
            )
            lateinit var residentMessages: List<MessageWindowResetSnapshot>
            lateinit var appliedState: ServerProjectionSyncState
            queries.transaction {
                val before = checkNotNull(selectSyncStateForCheckpointLocked()) {
                    "sync state is not bound"
                }
                check(before.datasetId == expectedDatasetId && before.cursor == expectedCursor) {
                    "checkpoint no longer matches the current sync authority"
                }

                queries.deleteServerProjectionMessagesPreservingOrphanFailures()
                queries.deleteAllMessageReactions()
                entities.persistServerCheckpointLocked(checkpoint, userPlan)
                conversations.persistServerCheckpointLocked(conversationPlan)
                messageProjectionReset.rebuildOutgoingProjection()
                queries.applySyncCheckpointCursor(
                    baseEventId = checkpoint.baseEventId,
                    expectedDatasetId = expectedDatasetId,
                    expectedCursor = expectedCursor,
                )
                appliedState = checkNotNull(selectSyncStateForCheckpointLocked()) {
                    "sync state disappeared while applying checkpoint"
                }
                check(
                    appliedState.datasetId == expectedDatasetId &&
                        appliedState.cursor == checkpoint.baseEventId
                ) { "checkpoint cursor was not applied" }
                residentMessages = messages.snapshotResidentWindowsForResetLocked()
            }

            messages.invalidateAllHistoryLocked()
            reactions.publishServerProjectionResetLocked()
            entities.publishServerCheckpointLocked(checkpoint, userPlan)
            conversations.publishServerCheckpointLocked(conversationPlan)
            messages.resetResidentWindowsLocked(residentMessages)
            appliedState
        }
    }

    override fun resetServerProjection(datasetId: String) = cacheUseGate.use {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
        synchronized(stateLock) {
            lateinit var residentMessages: List<MessageWindowResetSnapshot>
            queries.transaction {
                queries.deleteAllBotMessages()
                queries.resetBotInboxMetadata()
                queries.resetSyncState(datasetId)
                queries.deleteServerProjectionMessagesPreservingOrphanFailures()
                queries.deleteAllMessageReactions()
                queries.deleteAllMembers()
                queries.deleteAllOrganizationMembers()
                queries.deleteAllOrganizationMemberSnapshots()
                queries.deleteAllOrganizationUnits()
                queries.deleteOrganizationProjectionState()
                queries.deleteAllDocumentBodyAncestors()
                queries.deleteAllDocumentBodies()
                queries.deleteAllDocumentHome()
                queries.deleteAllDocumentNodes()
                queries.deleteAllDocumentBranches()
                queries.deleteAllDocumentProjectionMarkers()
                queries.deleteAllDocumentSpaces()
                queries.deleteAllConversations()
                queries.deleteAllContacts()
                queries.deleteAllChats()
                queries.deleteAllUsers()
                messageProjectionReset.rebuildOutgoingProjection()
                residentMessages = messages.snapshotResidentWindowsForResetLocked()
            }

            messages.invalidateAllHistoryLocked()
            entities.resetSnapshotGatesLocked()
            entities.clearProjectionLocked()
            organization.resetSnapshotGatesLocked()
            organization.clearProjectionLocked()
            documents.resetSnapshotGatesLocked()
            conversations.clearServerProjectionLocked()
            reactions.publishServerProjectionResetLocked()
            groupFileEntries.clearAllLocked()
            messages.resetResidentWindowsLocked(residentMessages)
        }
    }

    override fun enqueueBotMessage(eventId: Long, message: Message) =
        deliveryLog.enqueueBotMessage(eventId, message)

    override fun peekBotMessage(): PendingBotMessage? = deliveryLog.peekBotMessage()
    override fun ackBotMessage(eventId: Long, now: Long) = deliveryLog.ackBotMessage(eventId, now)
    override fun listBotMessageDeliveries(
        afterEventId: Long,
        chatId: String?,
        limit: Int,
    ): List<PendingBotMessage> = deliveryLog.listBotMessageDeliveries(afterEventId, chatId, limit)

    override fun maxBotMessageEventId(): Long = deliveryLog.maxBotMessageEventId()
    override fun setConversationDraft(chatId: String, draft: String?): Long =
        conversations.setConversationDraft(chatId, draft)

    override fun getPendingConversationDrafts(): List<PendingConversationDraft> =
        conversations.getPendingConversationDrafts()

    override fun getPendingConversationDraft(chatId: String): PendingConversationDraft? =
        conversations.getPendingConversationDraft(chatId)

    override fun markConversationDraftMirrored(chatId: String, generation: Long) =
        conversations.markConversationDraftMirrored(chatId, generation)

    override fun close() {
        cacheUseGate.close {
            synchronized(stateLock) {
                messages.closeResidentWindowsLocked()
                entities.resetSnapshotGatesLocked()
                entities.closeResidentsLocked()
                organization.resetSnapshotGatesLocked()
                organization.closeResidentsLocked()
                documents.resetSnapshotGatesLocked()
                conversations.closeResidentLocked()
                reactions.closeResidentsLocked()
                groupFileEntries.closeObserversLocked()
            }
            closeLocalCacheSqliteDriver(driver)
        }
    }

    /** 调用方持有 [stateLock]。 */
    private fun selectSyncStateForCheckpointLocked(): ServerProjectionSyncState? =
        queries.selectSyncState().executeAsOneOrNull()?.let { row ->
            ServerProjectionSyncState(row.dataset_id, row.cursor)
        }
}
