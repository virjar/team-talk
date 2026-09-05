package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload

/**
 * 会话拥有的消息投影、持久发送可靠发件箱、历史隔断与常驻 LRU 窗口。
 *
 * 所有持久写者都持有缓存的共享 [stateLock]。[LocalMessageWindowRegistry] 拥有额外的常驻锁，
 * 保持固定的 `CacheUseGate -> stateLock -> resident registry -> SQL -> window` 顺序。
 */
internal class LocalMessageStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val outboxLimits: LocalOutboxLimits,
    private val retentionLimits: LocalMessageRetentionLimits,
    refreshReactionsAfterPrune: (chatId: String) -> Unit = {},
) {
    private val historyLeases = MessageHistoryLeaseGate()
    private val projectionPersistence = LocalMessageProjectionPersistence(queries)
    private val retention =
        LocalAuthoritativeMessageRetention(queries, retentionLimits, refreshReactionsAfterPrune)
    private val windowRegistry = LocalMessageWindowRegistry(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        historyLeases = historyLeases,
        pruneIdleWindowTail = ::pruneAuthoritativeMessageHistoryForIdleWindowLocked,
    )
    private val optimisticEditOwner = Any()
    private var nextOptimisticEditToken = 0L
    private val optimisticEdits = linkedMapOf<Long, PendingOptimisticMessageEdit>()
    private val optimisticEditByMessage = mutableMapOf<MessageProjectionKey, Long>()
    private val outgoingRecovery = LocalOutgoingRecoveryStore(
        queries = queries,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        admitActive = ::admitActiveOutgoingMessageLocked,
        persistMessage = projectionPersistence::persist,
        supersedeOptimisticEdit = ::supersedeOptimisticEditLocked,
        upsertResident = { message -> windowRegistry.residentWindow(message.chatId)?.upsert(message) },
        deleteResident = { chatId, clientMsgId ->
            windowRegistry.residentWindow(chatId)?.deleteMessage(clientMsgId)
        },
        replaceResident = { chatId, clientMsgId, replacement ->
            windowRegistry.residentWindow(chatId)?.replaceMessage(clientMsgId, replacement)
        },
    )

    /** JVM 并发测试在 SQL 快照之后、常驻发布之前暂停。 */
    internal var windowSnapshotLoadedHookForTest: (() -> Unit)?
        get() = windowRegistry.snapshotLoadedHookForTest
        set(value) {
            windowRegistry.snapshotLoadedHookForTest = value
        }
    /** JVM 测试在其常驻覆盖层存在之后、RPC 准入之前使一次发布失败。 */
    internal var optimisticEditPublishedHookForTest: (() -> Unit)? = null

    init {
        // 构造是 GUI 与无头会话共享的唯一通用发布前时点。此时尚不存在任何历史或常驻窗口租约；
        // 保持其工作量有限。
        synchronized(stateLock) {
            retention.catchUp()
        }
    }

    /** 同步 SQL 快照是读取，不是隐藏的常驻窗口获取。 */
    fun getMessages(chatId: String, limit: Int): List<Message> = cacheUseGate.use {
        require(chatId.isNotBlank()) { "chatId must not be blank" }
        require(limit in 1..LocalCache.MAX_MESSAGE_READ_LIMIT) {
            "limit must be between 1 and ${LocalCache.MAX_MESSAGE_READ_LIMIT}"
        }
        synchronized(stateLock) {
            loadBoundedInitialMessages(queries, chatId, limit) { row -> row.toLocalModel() }
        }
    }

    fun findMessage(chatId: String, clientMsgId: String): Message? = cacheUseGate.use {
        require(chatId.isNotBlank() && clientMsgId.isNotBlank()) { "message identity must not be blank" }
        synchronized(stateLock) {
            queries.selectMessageById(chatId, clientMsgId).executeAsOneOrNull()?.toLocalModel()
        }
    }

    fun insertMessage(message: Message) {
        cacheUseGate.use {
            val projection = message.asAuthoritativeProjection()
            require(projection.chatId.isNotBlank()) { "message chatId must not be blank" }
            require(projection.clientMsgId.isNotBlank()) { "message clientMsgId must not be blank" }
            synchronized(stateLock) {
                queries.transaction {
                    projectionPersistence.persist(projection)
                    promoteOutgoingFromAuthoritativeProjection(projection, System.currentTimeMillis())
                }
                if (projection.serverSeq > 0L) {
                    historyLeases.recordLiveAuthoritativeMutation(projection.chatId, projection.clientMsgId)
                }
                supersedeOptimisticEditLocked(projection.chatId, projection.clientMsgId)
                // 非常驻 chat 在其窗口下次打开时加载该持久事实。
                windowRegistry.residentWindow(projection.chatId)?.upsert(projection)
                pruneAuthoritativeMessageHistoryIfIdleLocked(projection.chatId)
            }
        }
    }

    fun reserveOptimisticMessageEdit(message: Message): OptimisticMessageEditLease? = cacheUseGate.use {
        require(message.chatId.isNotBlank()) { "optimistic edit chatId must not be blank" }
        require(message.clientMsgId.isNotBlank()) { "optimistic edit clientMsgId must not be blank" }
        require(message.serverSeq > 0L) { "only confirmed messages can be edited" }
        synchronized(stateLock) {
            val key = MessageProjectionKey(message.chatId, message.clientMsgId)
            if (key in optimisticEditByMessage) return@synchronized null
            val window = windowRegistry.residentWindow(message.chatId) ?: return@synchronized null
            val previous = window.currentMessage(message.clientMsgId) ?: return@synchronized null
            if (
                previous.serverSeq != message.serverSeq ||
                previous.senderUid != message.senderUid ||
                previous.timestamp != message.timestamp ||
                previous.flags and Message.FLAG_REVOKED != 0
            ) {
                return@synchronized null
            }
            // 调用方只贡献可编辑内容。稳定身份、创建元数据、状态与每个既有 flag 仍归当前投影所有。
            val optimistic = previous.copy(
                messageType = message.messageType,
                body = message.body,
                flags = previous.flags or Message.FLAG_EDITED,
                uploadProgress = 0f,
            ).asAuthoritativeProjection()
            check(optimisticEdits.size < MAX_PENDING_OPTIMISTIC_EDITS) {
                "Too many concurrent optimistic message edits"
            }
            check(nextOptimisticEditToken < Long.MAX_VALUE) {
                "optimistic message edit token exhausted"
            }
            val lease = LocalOptimisticMessageEditLease(
                owner = optimisticEditOwner,
                tokenId = ++nextOptimisticEditToken,
            )
            optimisticEdits[lease.tokenId] = PendingOptimisticMessageEdit(
                lease = lease,
                key = key,
                window = window,
                previous = previous,
                optimistic = optimistic,
            )
            check(optimisticEditByMessage.put(key, lease.tokenId) == null) {
                "duplicate optimistic message edit reservation"
            }
            lease
        }
    }

    fun publishOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val pending = currentOptimisticEditLocked(lease) ?: return@synchronized false
            if (pending.published || pending.superseded) return@synchronized false
            pending.published = true
            val published = try {
                pending.window.replaceMessageIfCurrent(pending.previous, pending.optimistic).also { changed ->
                    if (changed) optimisticEditPublishedHookForTest?.invoke()
                }
            } catch (failure: Throwable) {
                // 保留调用方拥有的精确租约。常驻 setter 可能已在之后失败的 cursor/SQLite 刷新之前
                // 发布；生命周期回滚必须仍能在 RPC 尝试之前恢复该覆盖层。
                throw failure
            }
            if (!published) pending.superseded = true
            published && currentOptimisticEditLocked(lease) === pending && !pending.superseded
        }
    }

    fun commitOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val pending = currentOptimisticEditLocked(lease) ?: return@synchronized false
            removeOptimisticEditLocked(pending)
            true
        }
    }

    fun rollbackOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val pending = currentOptimisticEditLocked(lease) ?: return@synchronized false
            removeOptimisticEditLocked(pending)
            if (pending.published && !pending.superseded) {
                // 精确替换返回 false 意味着一个未预料的更新窗口投影获胜。
                // 保留该值仍然比强行恢复旧快照更安全。
                pending.window.replaceMessageIfCurrent(pending.optimistic, pending.previous)
            }
            true
        }
    }

    fun enqueueOutgoingMessage(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = outgoingRecovery.enqueue(message, now, requestFingerprint)

    fun getOutgoingMessage(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = outgoingRecovery.get(chatId, clientMsgId, requestFingerprint)

    fun findOutgoingFailureCode(
        chatId: String,
        clientMsgId: String,
    ): OutgoingFailureCode? = outgoingRecovery.findFailureCode(chatId, clientMsgId)

    /** 仅元数据聚合：该查询从不选择或 decode 规范载荷。 */
    fun outgoingQueueSnapshot(now: Long): OutgoingQueueSnapshot = outgoingRecovery.snapshot(now)

    fun discardTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
    ): Boolean = outgoingRecovery.discard(ownerUid, chatId, clientMsgId)

    fun replaceTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = outgoingRecovery.replace(
        ownerUid,
        chatId,
        clientMsgId,
        replacement,
        now,
        requestFingerprint,
    )

    /** Worker 启动路径：修复持久状态，而不物化或 decode SUCCESS 行。 */
    fun recoverOutgoingState(now: Long) = cacheUseGate.use {
        synchronized(stateLock) {
            recoverOutgoingStateLocked(now)
        }
    }

    /** 显式诊断 API；先应用恢复，再 decode 有界回执集合。 */
    fun recoverOutgoingMessages(now: Long): List<OutgoingMessage> = cacheUseGate.use {
        synchronized(stateLock) {
            recoverOutgoingStateLocked(now)
            queries.selectAllOutgoingMessages().executeAsList().map { it.toLocalModel() }
        }
    }

    fun peekNextOutgoingMessage(): OutgoingMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            queries.selectNextActiveOutgoingMessage().executeAsOneOrNull()?.toLocalModel()
        }
    }

    fun claimNextOutgoingMessage(now: Long): OutgoingMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            var claimed: com.virjar.tk.shared.database.Outgoing_message? = null
            queries.transaction {
                val head = queries.selectNextActiveOutgoingMessage().executeAsOneOrNull()
                if (head != null && head.next_attempt_at <= now) {
                    queries.markOutgoingMessageInFlight(now, head.local_ordinal)
                    claimed = queries.selectOutgoingMessageByOrdinal(head.local_ordinal).executeAsOneOrNull()
                    claimed?.let { row ->
                        queries.updateMessageSendStatus(
                            Message.SEND_STATUS_SENDING.toLong(),
                            row.chat_id,
                            row.client_msg_id,
                        )
                    }
                }
            }
            claimed?.also { row ->
                updateResidentOptimisticMessage(
                    chatId = row.chat_id,
                    clientMsgId = row.client_msg_id,
                    sendStatus = Message.SEND_STATUS_SENDING,
                )
            }?.toLocalModel()
        }
    }

    fun markOutgoingMessageRetry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
        failureCode: OutgoingFailureCode,
    ) {
        cacheUseGate.use {
            synchronized(stateLock) {
                var changed: com.virjar.tk.shared.database.Outgoing_message? = null
                queries.transaction {
                    val row = queries.selectOutgoingMessageByOrdinal(localOrdinal).executeAsOneOrNull()
                    if (row == null || row.state != OutgoingMessageState.IN_FLIGHT.code) return@transaction
                    queries.markOutgoingMessageRetry(
                        failureCode.storageCode,
                        boundedOutgoingError(error),
                        nextAttemptAt,
                        now,
                        localOrdinal,
                    )
                    queries.updateMessageSendStatus(
                        Message.SEND_STATUS_QUEUED.toLong(),
                        row.chat_id,
                        row.client_msg_id,
                    )
                    changed = row
                }
                changed?.let { row ->
                    updateResidentOptimisticMessage(
                        chatId = row.chat_id,
                        clientMsgId = row.client_msg_id,
                        sendStatus = Message.SEND_STATUS_QUEUED,
                    )
                }
            }
        }
    }

    fun markOutgoingMessageTerminalFailed(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int?,
        failureCode: OutgoingFailureCode,
    ) {
        cacheUseGate.use {
            synchronized(stateLock) {
                var changed: com.virjar.tk.shared.database.Outgoing_message? = null
                queries.transaction {
                    val row = queries.selectOutgoingMessageByOrdinal(localOrdinal).executeAsOneOrNull()
                    if (row == null || row.state != OutgoingMessageState.IN_FLIGHT.code) return@transaction
                    queries.markOutgoingMessageTerminalFailed(
                        failureCode.storageCode,
                        boundedOutgoingError(error),
                        terminalCode?.toLong(),
                        now,
                        nextOutgoingCompletionTime(queries, now),
                        localOrdinal,
                    )
                    queries.updateMessageTerminalFailure(
                        failureCode.storageCode,
                        row.chat_id,
                        row.client_msg_id,
                    )
                    pruneTerminalOutgoingReceiptsLocked()
                    changed = row
                }
                changed?.let { row ->
                    updateResidentOptimisticMessage(
                        chatId = row.chat_id,
                        clientMsgId = row.client_msg_id,
                        sendStatus = Message.SEND_STATUS_FAILED,
                    )
                }
            }
        }
    }

    fun completeOutgoingMessage(localOrdinal: Long, ack: MessageAckPayload, now: Long) {
        cacheUseGate.use {
            require(ack.code == 0) { "Only successful ACKs complete an outgoing message" }
            require(ack.serverSeq > 0L) { "Successful ACK must carry a positive serverSeq" }
            synchronized(stateLock) {
                var completed: com.virjar.tk.shared.database.Outgoing_message? = null
                queries.transaction {
                    val row = queries.selectOutgoingMessageByOrdinal(localOrdinal).executeAsOneOrNull()
                    if (row == null || row.state != OutgoingMessageState.IN_FLIGHT.code) return@transaction
                    require(ack.chatId == row.chat_id && ack.clientMsgId == row.client_msg_id) {
                        "ACK belongs to another outgoing message"
                    }
                    queries.updateMessageSeqStatus(ack.serverSeq, row.chat_id, row.client_msg_id)
                    queries.markOutgoingMessageSucceeded(
                        ack.serverSeq,
                        now,
                        nextOutgoingCompletionTime(queries, now),
                        localOrdinal,
                    )
                    pruneTerminalOutgoingReceiptsLocked()
                    completed = row
                }
                completed?.let { row ->
                    // 防止更旧的在途最新页移除这条已确认的常驻行。
                    historyLeases.recordLiveAuthoritativeMutation(row.chat_id, row.client_msg_id)
                    updateResidentOptimisticMessage(
                        chatId = row.chat_id,
                        clientMsgId = row.client_msg_id,
                        serverSeq = ack.serverSeq,
                        sendStatus = Message.SEND_STATUS_SENT,
                    )
                    pruneAuthoritativeMessageHistoryIfIdleLocked(row.chat_id)
                }
            }
        }
    }

    fun cancelOutgoingMessages(reason: String, now: Long) = cacheUseGate.use {
        synchronized(stateLock) {
            var active = emptyList<com.virjar.tk.shared.database.Outgoing_message>()
            queries.transaction {
                active = queries.selectActiveOutgoingMessages().executeAsList()
                if (active.isNotEmpty()) {
                    queries.cancelActiveOutgoingMessages(
                        OutgoingFailureCode.SESSION_RETIRED.storageCode,
                        boundedOutgoingError(reason),
                        now,
                        nextOutgoingCompletionTime(queries, now),
                    )
                }
                active.forEach { row ->
                    queries.updateMessageTerminalFailure(
                        OutgoingFailureCode.SESSION_RETIRED.storageCode,
                        row.chat_id,
                        row.client_msg_id,
                    )
                }
                pruneTerminalOutgoingReceiptsLocked()
            }
            active.forEach { row ->
                updateResidentOptimisticMessage(
                    chatId = row.chat_id,
                    clientMsgId = row.client_msg_id,
                    sendStatus = Message.SEND_STATUS_FAILED,
                )
            }
        }
    }

    fun beginMessageHistoryLease(
        chatId: String,
        resetResidentWindow: Boolean,
    ): MessageHistoryLease = cacheUseGate.use {
        synchronized(stateLock) {
            val lease = historyLeases.begin(chatId, resetResidentWindow)
            if (windowRegistry.isResident(chatId)) {
                historyLeases.retain(chatId)
            } else {
                // 非常驻预取只拥有其在途状态，绝不拥有持久锚点。
                historyLeases.release(chatId)
            }
            lease
        }
    }

    fun applyMessageHistoryPage(
        lease: MessageHistoryLease,
        messages: List<Message>,
    ): Boolean = cacheUseGate.runIfOpen {
        synchronized(stateLock) {
            val applied = historyLeases.consumeIfCurrent(lease) { chatId, resetResidentWindow, protectedIds, liveIds ->
                require(messages.size <= Message.MAX_QUERY_PAGE_SIZE) {
                    "history page cannot contain more than ${Message.MAX_QUERY_PAGE_SIZE} messages"
                }
                val page = messages.map(Message::asAuthoritativeProjection)
                val clientMsgIds = HashSet<String>(page.size)
                val serverSeqs = HashSet<Long>(page.size)
                page.forEach { message ->
                    require(message.chatId == chatId) {
                        "history page contains another chat: ${message.chatId}"
                    }
                    require(message.clientMsgId.isNotBlank()) {
                        "history page contains a blank clientMsgId"
                    }
                    require(message.serverSeq > 0L) {
                        "history page contains a non-positive serverSeq"
                    }
                    require(clientMsgIds.add(message.clientMsgId)) {
                        "history page contains duplicate clientMsgId"
                    }
                    require(serverSeqs.add(message.serverSeq)) {
                        "history page contains duplicate serverSeq"
                    }
                }
                windowRegistry.withResidentWindow(chatId) { residentWindow ->
                    val protectedInPage = page.asSequence()
                        .map(Message::clientMsgId)
                        .filter { it in protectedIds }
                        .toSet()
                    val preservedDurableMessages = mutableListOf<Message>()
                    queries.transaction {
                        page.forEach { message ->
                            if (message.clientMsgId !in protectedInPage) {
                                projectionPersistence.persist(message)
                                promoteOutgoingFromAuthoritativeProjection(message, System.currentTimeMillis())
                            }
                        }
                        protectedInPage.forEach { clientMsgId ->
                            val current = checkNotNull(
                                queries.selectMessageById(chatId, clientMsgId).executeAsOneOrNull(),
                            ) {
                                "history mutation fence has no durable projection for $clientMsgId"
                            }
                            preservedDurableMessages += current.toLocalModel().asAuthoritativeProjection()
                        }
                    }
                    page.forEach { message ->
                        if (message.clientMsgId !in protectedInPage) {
                            historyLeases.recordAuthoritativeMutation(
                                message.chatId,
                                message.clientMsgId,
                                excluding = lease,
                            )
                            supersedeOptimisticEditLocked(message.chatId, message.clientMsgId)
                        }
                    }
                    // 页来源而非数值相邻性，使其 sequence 空洞成为权威。
                    residentWindow?.applyHistoryPage(
                        page = page,
                        resetResidentWindow = resetResidentWindow,
                        preserveClientMsgIds = protectedInPage,
                        retainResidentClientMsgIds = liveIds,
                        preservedDurableMessages = preservedDurableMessages,
                    )
                }
            }
            if (applied) pruneAuthoritativeMessageHistoryIfIdleLocked(lease.chatId)
            applied
        }
    }

    fun abandonMessageHistoryLease(lease: MessageHistoryLease): Boolean =
        cacheUseGate.runIfOpen {
            synchronized(stateLock) {
                val abandoned = historyLeases.abandon(lease)
                if (abandoned) pruneAuthoritativeMessageHistoryIfIdleLocked(lease.chatId)
                abandoned
            }
        }

    fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) {
        cacheUseGate.use {
            synchronized(stateLock) {
                queries.updateMessageSeqStatus(serverSeq, chatId, clientMsgId)
                updateResidentOptimisticMessage(
                    chatId = chatId,
                    clientMsgId = clientMsgId,
                    serverSeq = serverSeq,
                    sendStatus = Message.SEND_STATUS_SENT,
                )
                pruneAuthoritativeMessageHistoryIfIdleLocked(chatId)
            }
        }
    }

    fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) {
        cacheUseGate.use {
            synchronized(stateLock) {
                queries.updateMessageSendStatus(sendStatus.toLong(), chatId, clientMsgId)
                updateResidentOptimisticMessage(chatId, clientMsgId, sendStatus = sendStatus)
            }
        }
    }

    fun updateMessageInMemory(
        chatId: String,
        clientMsgId: String,
        transform: (Message) -> Message,
    ) {
        cacheUseGate.use {
            synchronized(stateLock) {
                windowRegistry.residentWindow(chatId)?.updateMessage(
                    clientMsgId = clientMsgId,
                    transform = transform,
                    beforePublish = { supersedeOptimisticEditLocked(chatId, clientMsgId) },
                )
            }
        }
    }

    fun pager(chatId: String, windowSize: Int): MessagePager = cacheUseGate.use {
        windowRegistry.acquire(chatId, windowSize)
    }

    /** 调用方持有 [stateLock]；SQL 删除由 [LocalCacheImpl] 拥有。 */
    fun invalidateChatHistoryLocked(chatId: String) {
        historyLeases.invalidate(chatId)
    }

    /** 调用方持有 [stateLock]；保持在墓碑发布顺序中的最后。 */
    fun resetResidentChatLocked(chatId: String) {
        supersedeOptimisticEditsForChatLocked(chatId)
        // 保持现有 Flow 附着，并立即发布墓碑。
        windowRegistry.resetChat(chatId)
    }

    /** 调用方持有 [stateLock] 与 reset 事务。 */
    fun snapshotResidentWindowsForResetLocked(): List<MessageWindowResetSnapshot> =
        windowRegistry.snapshotForReset()

    /** 调用方持有 [stateLock]；SQL reset 由 [LocalCacheImpl] 拥有。 */
    fun resetResidentWindowsLocked(snapshot: List<MessageWindowResetSnapshot>) {
        supersedeAllOptimisticEditsLocked()
        // 不替换常驻窗口：现有收集者必须先看到空，再看到重放状态。
        windowRegistry.resetAll(snapshot)
    }

    /** 调用方在缓存退役期间持有 [stateLock]。 */
    fun invalidateAllHistoryLocked() {
        historyLeases.reset()
    }

    /** 调用方持有 [stateLock]；缓存关闭终态退役每个被捕获的 pager 租约。 */
    fun closeResidentWindowsLocked() {
        historyLeases.reset()
        optimisticEdits.clear()
        optimisticEditByMessage.clear()
        windowRegistry.closeAll()
    }

    internal fun residentWindowCountsForTest(): MessageWindowResidentCounts = cacheUseGate.use {
        synchronized(stateLock) {
            windowRegistry.counts()
        }
    }

    /**
     * 在普通写入上摊薄保留，而不改变在途历史链或游标仍指向持久尾部的常驻窗口。
     */
    private fun pruneAuthoritativeMessageHistoryIfIdleLocked(chatId: String) {
        if (historyLeases.hasCurrentRequest(chatId)) return
        if (windowRegistry.isResident(chatId)) {
            windowRegistry.sweepIdleWindowForRetention(chatId)
        } else {
            retention.prune(chatId)
        }
    }

    /** 在其最后一个 pager 租约消失之后，带着常驻注册锁被调用。 */
    private fun pruneAuthoritativeMessageHistoryForIdleWindowLocked(chatId: String): Boolean {
        if (historyLeases.hasCurrentRequest(chatId)) return false
        return retention.prune(chatId)
    }

    /** 调用方持有 [stateLock]。同值变更仍按来源取代。 */
    private fun supersedeOptimisticEditLocked(chatId: String, clientMsgId: String) {
        val tokenId = optimisticEditByMessage[MessageProjectionKey(chatId, clientMsgId)] ?: return
        optimisticEdits[tokenId]?.superseded = true
    }

    /** 调用方持有 [stateLock]。 */
    private fun supersedeOptimisticEditsForChatLocked(chatId: String) {
        optimisticEdits.values.forEach { pending ->
            if (pending.key.chatId == chatId) pending.superseded = true
        }
    }

    /** 调用方持有 [stateLock]。 */
    private fun supersedeAllOptimisticEditsLocked() {
        optimisticEdits.values.forEach { it.superseded = true }
    }

    /** 调用方持有 [stateLock]。 */
    private fun currentOptimisticEditLocked(
        lease: OptimisticMessageEditLease,
    ): PendingOptimisticMessageEdit? {
        val localLease = lease as? LocalOptimisticMessageEditLease ?: return null
        if (localLease.owner !== optimisticEditOwner) return null
        return optimisticEdits[localLease.tokenId]?.takeIf { it.lease === localLease }
    }

    /** 调用方持有 [stateLock]。 */
    private fun removeOptimisticEditLocked(pending: PendingOptimisticMessageEdit) {
        optimisticEdits.remove(pending.lease.tokenId, pending)
        optimisticEditByMessage.remove(pending.key, pending.lease.tokenId)
    }

    /** 调用方持有 [stateLock] 与外层事务。 */
    private fun promoteOutgoingFromAuthoritativeProjection(message: Message, now: Long) {
        if (message.serverSeq <= 0L) return
        queries.markAuthoritativeMessageSent(message.chatId, message.clientMsgId)
        val row = queries.selectOutgoingMessageById(message.chatId, message.clientMsgId)
            .executeAsOneOrNull() ?: return
        if (row.state == OutgoingMessageState.SUCCESS.code) return
        if (row.sender_uid != message.senderUid) return
        val completedAt = nextOutgoingCompletionTime(queries, now)
        queries.promoteOutgoingMessageSucceededFromProjection(
            message.serverSeq,
            now,
            completedAt,
            row.local_ordinal,
        )
        pruneTerminalOutgoingReceiptsLocked()
    }

    /** 调用方持有 [stateLock]。 */
    private fun recoverOutgoingStateLocked(now: Long) {
        val repairedProjection = mutableListOf<Message>()
        queries.transaction {
            queries.failOrphanedLocalMessages()
            queries.recoverInFlightOutgoingMessages(
                failureCode = OutgoingFailureCode.PROCESS_INTERRUPTED.storageCode,
                diagnostic = "interrupted before durable response",
                updatedAt = now,
            )

            // 发送者身份与不可变载荷一起持久化，因此该对账是一个集合操作，绝不可能提升
            // 来自另一个发送者的同 id 消息。
            queries.markAuthoritativeMessagesSentForOutgoing()
            if (queries.countPromotableOutgoingMessages().executeAsOne() > 0L) {
                queries.promoteOutgoingMessagesFromAuthoritativeProjection(
                    updatedAt = now,
                    completedAt = nextOutgoingCompletionTime(queries, now),
                )
            }
            queries.reconcileOutgoingProjectionStatuses()

            // 现有的乐观投影已由上面的集合更新修复。只有真正缺失的非成功投影需要
            // protobuf 解码并重新插入。
            while (true) {
                val missing = queries.selectOutgoingMessagesMissingProjection(
                    OUTGOING_RECOVERY_PROJECTION_PAGE_SIZE.toLong(),
                ).executeAsList()
                if (missing.isEmpty()) break
                missing.forEach { row ->
                    val projection = row.toProjectionMessage().also(
                        projectionPersistence::persistMissingOutgoing,
                    )
                    if (row.state == OutgoingMessageState.TERMINAL_FAILED.code) {
                        queries.updateMessageTerminalFailure(
                            requireNotNull(row.failure_code) {
                                "Terminal outgoing receipt has no stable failure code"
                            },
                            row.chat_id,
                            row.client_msg_id,
                        )
                    }
                    if (windowRegistry.isResident(row.chat_id)) repairedProjection += projection
                }
            }
            pruneTerminalOutgoingReceiptsLocked()
        }
        repairedProjection.forEach { message ->
            windowRegistry.residentWindow(message.chatId)?.upsert(message)
        }
    }

    /** 调用方持有 [stateLock]，对变更而言还持有外层事务。 */
    private fun admitActiveOutgoingMessageLocked(payloadBytes: Long, fingerprintBytes: Long) {
        val activeCount = queries.countActiveOutgoingMessages().executeAsOne()
        if (activeCount >= outboxLimits.activeOutgoingCount.toLong()) {
            localOutboxCapacityExceeded(
                LocalOutboxKind.OUTGOING_MESSAGE,
                LocalOutboxCapacityDimension.ENTRY_COUNT,
                outboxLimits.activeOutgoingCount.toLong(),
            )
        }
        val activeBytes = queries.sumActiveOutgoingStorageBytes().executeAsOne()
        val requestedBytes = payloadBytes + fingerprintBytes
        if (requestedBytes > outboxLimits.activeOutgoingBytes - activeBytes) {
            localOutboxCapacityExceeded(
                LocalOutboxKind.OUTGOING_MESSAGE,
                LocalOutboxCapacityDimension.STORED_BYTES,
                outboxLimits.activeOutgoingBytes,
            )
        }
    }

    /** 调用方持有 [stateLock] 与外层事务。只保留最新前缀。 */
    private fun pruneTerminalOutgoingReceiptsLocked() {
        var retainedBytes = 0L
        var retaining = true
        queries.selectTerminalOutgoingStorageNewestFirst().executeAsList().forEachIndexed { index, row ->
            val rowBytes = checkNotNull(row.stored_bytes) {
                "terminal outgoing storage size is unexpectedly null"
            }
            val fitsCount = index < outboxLimits.terminalOutgoingCount
            val fitsBytes = rowBytes <= outboxLimits.terminalOutgoingBytes - retainedBytes
            if (retaining && fitsCount && fitsBytes) {
                retainedBytes += rowBytes
            } else {
                retaining = false
                queries.deleteOutgoingMessage(row.local_ordinal)
            }
        }
    }

    /** 可靠发件箱回调绝不改变服务器拥有的同 id 常驻行。 */
    private fun updateResidentOptimisticMessage(
        chatId: String,
        clientMsgId: String,
        serverSeq: Long? = null,
        sendStatus: Int? = null,
    ) {
        windowRegistry.residentWindow(chatId)?.updateMessage(clientMsgId, transform = {
            if (this.serverSeq > 0L) this else copy(
                serverSeq = serverSeq ?: this.serverSeq,
                sendStatus = sendStatus ?: this.sendStatus,
            )
        })
    }

}
