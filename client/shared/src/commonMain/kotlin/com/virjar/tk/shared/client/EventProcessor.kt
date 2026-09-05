package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.NotifyContracts
import com.virjar.tk.protocol.OrganizationChangedPayload
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.shared.log.TkLogger
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * 事件处理器。收集 NotifyPayload 并写入本地缓存。
 * 入站订阅属于整个认证会话并跨 TCP 重连存活；数据库投影切换到 Dispatchers.IO，
 * 认证/同步控制仍由 ImClient 的 EventLoop 串行化。
 */
class EventProcessor(
    private val imClient: ImClient,
    private val localCache: LocalCache,
    /**
     * 会话/群变更时的刷新回调。CHAT_CREATED 在持久事件投影阶段只标记 dirty，
     * 只有连接进入 AUTHENTICATED 后才能调用该 RPC。这保证 replay 的 cache + cursor
     * 提交不依赖业务 RPC，多个 CHAT_CREATED 也只合并为一次刷新。
     */
    private val onConversationsDirty: (suspend () -> Unit)? = null,
    /**
     * 面向持久入站消息的可选可靠会话 sink（供 ImBot inbox 使用）。
     * 它接收权威 event id，在本地插入之后、游标提交之前运行，并以 INSERT OR IGNORE 持久化。
     * 失败会中止该批以便重放可以重试；面向 UI 的 [messageEvents] 仍然是非阻塞刷新流。
     */
    private val durableMessageSink: ((Long, Message) -> Unit)? = null,
    /**
     * 可选的无头 inbox 线性化边界。sink 必须在持有其按会话 key 的投递门禁时同步运行墓碑；
     * 失败会中止游标推进，重放会重试。
     */
    private val durableChatTombstoneSink: ((chatId: String, tombstone: () -> Unit) -> Unit)? = null,
    /** Null 保留给裸协议/E2E 测试台；createSession 总是绑定一个 owner uid。 */
    private val ownerUid: String? = null,
    /** 在权威回显可能已完成一次丢失的 ACK 之后刷新会话聚合。 */
    private val onOutgoingProjectionMayHaveChanged: (() -> Unit)? = null,
    /** 生产检查点收集器；从不接收 RESET 的裸协议测试台可以省略它。 */
    private val checkpointLoader: ServerCheckpointLoader? = null,
) {
    @Volatile
    private var logger: TkLogger = PlatformOnlyTkLogger("EventProcessor")
    private var listenJob: Job? = null

    private val initialSyncState = checkNotNull(localCache.getSyncState()) {
        "LocalCache must bind its server dataset before EventProcessor starts"
    }
    private val _datasetId = MutableStateFlow(initialSyncState.datasetId)
    private val _lastEventId = MutableStateFlow(initialSyncState.cursor)
    val lastEventId: StateFlow<Long> = _lastEventId.asStateFlow()

    /** typing 事件：(chatId, senderUid) */
    private val _typingEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val typingEvents: SharedFlow<Pair<String, String>> = _typingEvents.asSharedFlow()

    private val _groupFileChanges = MutableSharedFlow<com.virjar.tk.protocol.GroupFileChangedPayload>(extraBufferCapacity = 64)

    /** 群文件行级变更事件（GROUP_FILE_CHANGED）；订阅者据此触发投影流收敛。 */
    val groupFileChanges: SharedFlow<com.virjar.tk.protocol.GroupFileChangedPayload> = _groupFileChanges.asSharedFlow()

    /**
     * 入站消息的非阻塞广播提示。UI 以 LocalCache 为权威；ImBot.nextMessage
     * 使用 [durableMessageSink] 的独立可靠 inbox，不依赖此 SharedFlow 的缓冲。
     */
    private val _messageEvents = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<Message> = _messageEvents.asSharedFlow()

    /** 联系人关系事件（好友申请/接受/删除后触发；详情查 LocalCache contacts）。 */
    private val _contactEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val contactEvents: SharedFlow<Unit> = _contactEvents.asSharedFlow()

    /** 群/成员变更事件（创建/更新/成员增删/禁言/角色变更后触发；详情查 LocalCache chats/members）。 */
    private val _chatEvents = MutableSharedFlow<Pair<NotifyType, Chat>>(extraBufferCapacity = 32)
    val chatEvents: SharedFlow<Pair<NotifyType, Chat>> = _chatEvents.asSharedFlow()

    /** 最近本地持久化的必需组织修订信号；行数据来自 LocalCache。 */
    private val _organizationEvents = MutableSharedFlow<Long>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val organizationEvents: SharedFlow<Long> = _organizationEvents.asSharedFlow()

    /** 好友在线状态事件（上下线广播，服务端直写不持久化）。 */
    private val _presenceEvents = MutableSharedFlow<PresencePayload>(extraBufferCapacity = 32)
    val presenceEvents: SharedFlow<PresencePayload> = _presenceEvents.asSharedFlow()
    private val publicationGate = SessionWorkGate("EventProcessor")
    private val publicationLease = publicationGate.lease()

    /** 在 [start] 之前绑定；生产会话使用固定 owner 或禁用的 no-op logger。 */
    internal fun bindLogger(sessionLogger: TkLogger) {
        check(!started) { "EventProcessor logger must bind before start" }
        logger = sessionLogger
    }

    /**
     * 会话级作用域：入站订阅、连接就绪观察与辅助刷新均跨 TCP 重连存活，由 [stop] 统一取消。
     * 连接就绪观察只触发会话列表对账，不负责重建入站订阅。
     */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, t ->
        publicationGate.runIfActive(publicationLease) {
            logger.fault("EventProcessor lifecycle watcher crashed", t)
        }
    })
    private var watcherJob: Job? = null
    private var conversationRefreshJob: Job? = null
    private val conversationsDirty = MutableStateFlow(false)
    private val conversationRefreshSignals = Channel<Unit>(Channel.CONFLATED)
    /** 跨重连串行化实时投递、重放页与破坏性投影 reset。 */
    private val projectionMutex = Mutex()
    private val syncOwner = Any()
    /** 在会话 quiesce 时同步退役；跨每一次实际的 SYNC_REQUEST 写入被持有。 */
    private var syncWireAdmission = SessionOutboundLease()
    @Volatile
    private var started = false
    @Volatile
    private var stopped = false

    /** 组合根钩子：checkpoint RPC 与 SYNC_REQUEST 必须作为同一个 owner 退役。 */
    internal fun bindSyncWireAdmission(admission: SessionOutboundLease) {
        check(!started && !stopped) { "Synchronization admission must bind before start" }
        syncWireAdmission = admission
    }

    fun start() {
        publicationGate.requireActive(publicationLease)
        check(!stopped) { "EventProcessor is session-owned and cannot restart after stop" }
        if (started) return
        started = true
        ensureConversationRefreshWorker()
        ensureListening()
        imClient.installEventSync(
            owner = syncOwner,
            expectedUid = ownerUid,
            wireAdmission = syncWireAdmission,
            datasetId = { _datasetId.value },
            cursor = { lastEventId.value },
            processBatch = { events, reportProgress ->
                withContext(Dispatchers.IO) { processBatch(events, reportProgress) }
            },
            applyCheckpoint = { datasetId, reportProgress ->
                withContext(Dispatchers.IO) {
                    applyServerProjectionCheckpoint(datasetId, reportProgress)
                }
            },
        )
        if (watcherJob?.isActive == true) return
        watcherJob = lifecycleScope.launch {
            imClient.state.collect { state ->
                if (state == ConnectionState.AUTHENTICATED) {
                    requireConversationReconciliation()
                }
            }
        }
    }

    private fun ensureConversationRefreshWorker() {
        if (onConversationsDirty == null || conversationRefreshJob?.isActive == true) return
        conversationRefreshJob = lifecycleScope.launch {
            for (ignored in conversationRefreshSignals) {
                refreshDirtyConversations()
            }
        }
    }

    private fun markConversationsDirty() {
        conversationsDirty.value = true
    }

    /**
     * 即使内存 dirty 标记为 false，每条 ready 边也执行一次完整对账。进程可能在提交 CHAT_CREATED
     * 的游标之后、持久化任何辅助信号之前死亡；无条件对账关闭了这个崩溃窗口。
     */
    internal fun requireConversationReconciliation() {
        publicationGate.runIfActive(publicationLease) {
            conversationsDirty.value = true
            conversationRefreshSignals.trySend(Unit)
        }
    }

    /**
     * 只在认证边界之外消费一次合并后的刷新。
     *
     * 失败刻意恢复 dirty 而不立即再次发信号：重试发生在下一条 CHAT_CREATED 或 AUTHENTICATED 边，
     * 因此不可用的 RPC 不会造成热循环。持久事件游标已经提交，绝不会被这次提示刷新回滚。
     */
    internal suspend fun refreshDirtyConversations(
        authenticated: Boolean = imClient.state.value == ConnectionState.AUTHENTICATED,
    ): Boolean {
        val refresh = onConversationsDirty ?: return true
        if (!authenticated) return false
        val shouldRefresh = publicationGate.use(publicationLease) {
            if (!conversationsDirty.value) false else {
                conversationsDirty.value = false
                true
            }
        }
        if (!shouldRefresh) return false
        return try {
            refresh()
            publicationGate.use(publicationLease) { true }
        } catch (cancelled: CancellationException) {
            publicationGate.runIfActive(publicationLease) { conversationsDirty.value = true }
            throw cancelled
        } catch (failure: Exception) {
            publicationGate.runIfActive(publicationLease) {
                conversationsDirty.value = true
                logger.fault("Conversation refresh failed; keeping dirty for a later ready edge", failure)
            }
            false
        }
    }

    internal val hasDirtyConversations: Boolean
        get() = conversationsDirty.value

    private fun ensureListening() {
        if (!started || listenJob?.isActive == true) return
        // 该订阅属于已认证会话，而非某一次 TCP 尝试。它在 installEventSync 能发送 SYNC_REQUEST
        // 之前就已可见，并且跨每次重连存活，因此 READY -> 首条实时 NOTIFY 边界没有 replay=0 的
        // 订阅空档。
        listenJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            imClient.routedPackets.collect { packet ->
                val proto = packet.payload as? NotifyPayload ?: return@collect
                // 已退役 TCP 尝试排队的包绝不允许变更替代尝试。需要时持久重放会再次投递它。
                if (packet.connectionGeneration != imClient.currentConnectionGeneration) {
                    return@collect
                }
                try {
                    withContext(Dispatchers.IO) { processNotify(proto) }
                } catch (cancelled: CancellationException) {
                    // 会话 stop 拥有这次取消；TCP 重连不会替换监听器。
                    throw cancelled
                } catch (failure: Exception) {
                    // 让会话拥有的收集器为替代连接保持存活。代际租约防止迟到的失败关闭该替代连接。
                    publicationGate.runIfActive(publicationLease) {
                        logger.fault(
                            "EventProcessor projection failed; reconnecting from durable cursor",
                            failure,
                        )
                        imClient.closeForEventResync(
                            owner = syncOwner,
                            connectionGeneration = packet.connectionGeneration,
                            reason = "Persistent event projection failed",
                            cause = failure,
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        // removeEventSync 在 EventLoop 上是刻意异步的。准入退役才是同步硬边界，并等待一次
        // 已被准入的实际线格式写入。
        syncWireAdmission.retire()
        var boundaryFailure: SessionWorkGateReentrantCloseException? = null
        val newlyClosed = try {
            publicationGate.close()
        } catch (failure: SessionWorkGateReentrantCloseException) {
            boundaryFailure = failure
            true
        }
        if (!newlyClosed) return
        stopped = true
        started = false
        // 取消是建议性的；上面的发布代际才是硬边界。
        // 即使某个协作者有故障关闭钩子，也继续释放后续 owner。
        val failures = releaseAllSessionResources(
            "event sync" to { imClient.removeEventSync(syncOwner) },
            "listener" to { listenJob?.cancel() },
            "connection watcher" to { watcherJob?.cancel() },
            "conversation refresh" to { conversationRefreshJob?.cancel() },
            "refresh signal" to { conversationRefreshSignals.close() },
            "processor scope" to { lifecycleScope.cancel() },
        )
        boundaryFailure?.let { throw it }
        if (failures.isNotEmpty()) throw SessionResourceCloseException("EventProcessor", failures.map { it.second })
    }

    /** 在 QUIESCED 发布之前，从 ClientSession 的生命周期线性化点调用。 */
    internal fun retireSyncWireAdmission() {
        syncWireAdmission.retire()
    }

    /**
     * 按 event-id 顺序投影一个服务器页。每个成功条目都独立推进持久游标；第一个失败立即逃逸，
     * 后续条目保持不动。
     */
    internal suspend fun processBatch(
        events: List<NotifyPayload>,
        reportProgress: (Long) -> Unit = {},
    ): Long {
        require(events.isNotEmpty()) { "sync batch must not be empty" }
        publicationGate.requireActive(publicationLease)
        return projectionMutex.withLock {
            events.forEach { processNotifyLocked(it, reportProgress) }
            val expectedCursor = events.last().eventId
            check(lastEventId.value >= expectedCursor) {
                "sync page was not durably projected through cursor=$expectedCursor"
            }
            // 一次已退役的实时投递可能已经持久化得更远。但服务器页本身仍然完整到 expectedCursor，
            // 因此精确确认它的终点。
            expectedCursor
        }
    }

    /** 由已认证的服务器端游标拒绝所请求的原子检查点恢复。 */
    internal suspend fun applyServerProjectionCheckpoint(
        datasetId: String,
        reportProgress: () -> Unit = {},
    ): Long = projectionMutex.withLock {
        val loader = checkNotNull(checkpointLoader) {
            "Server requested a checkpoint but this session has no checkpoint loader"
        }
        val before = checkNotNull(localCache.getSyncState()) {
            "checkpoint load started without sync authority"
        }
        check(before.datasetId == datasetId) { "checkpoint requested for another dataset" }
        val owner = checkNotNull(ownerUid) { "checkpoint session has no account owner" }
        val checkpoint = loader.load(datasetId, owner, reportProgress)
        publicationGate.use(publicationLease) {
            val applied = localCache.applyServerProjectionCheckpoint(
                expectedDatasetId = datasetId,
                expectedCursor = before.cursor,
                checkpoint = checkpoint,
            )
            _datasetId.value = applied.datasetId
            _lastEventId.value = applied.cursor
            conversationsDirty.value = false
            _lastEventId.value
        }
    }

    internal suspend fun processNotify(notify: NotifyPayload) =
        projectionMutex.withLock { processNotifyLocked(notify) }

    /**
     * 普通事件使用“幂等投影 → 可靠 sink → 游标提交”，不是覆盖这三步的 SQLite 总事务。
     * 投影成功而 sink 或游标失败时，旧游标让下次连接重放该事件；各投影与 sink 必须能吸收重复。
     * 检查点安装则由 [LocalCache.applyServerProjectionCheckpoint] 原子替换投影和游标。
     */
    private suspend fun processNotifyLocked(
        notify: NotifyPayload,
        reportProgress: (Long) -> Unit = {},
    ) {
        publicationGate.requireActive(publicationLease)
        // 重连/最终激活竞态可能产生 at-least-once 重复。已经持久化完成的事件不再
        // 重放上层 SharedFlow 副作用；服务端保证同一用户的持久事件按 ID 交付。
        if (notify.eventId > 0L && notify.eventId <= _lastEventId.value) {
            publicationGate.use(publicationLease) { reportProgress(notify.eventId) }
            return
        }
        val notifyType = NotifyType.fromCode(notify.notifyType)
        val payload = notify.payload
        if (notifyType == NotifyType.EVENT_CURSOR_ADVANCED) {
            require(payload == null) { "EVENT_CURSOR_ADVANCED must not carry a payload" }
        }
        if (payload == null) {
            require(notifyType in NotifyContracts.exempt) {
                "Notify $notifyType is missing its required payload"
            }
        } else {
            handleNotifyPayload(notifyType, payload, notify.eventId)
        }
        // 只有完整投影成功后才单调落盘。异常故意向监听/批次循环传播：调用方必须
        // 立即停止后续事件并关闭连接，重连从最后一个已持久化 cursor 继续。
        if (notify.eventId > 0L) {
            publicationGate.use(publicationLease) {
                val persisted = localCache.advanceSyncCursor(_datasetId.value, notify.eventId)
                check(persisted.datasetId == _datasetId.value) {
                    "sync cursor advanced under a replacement dataset"
                }
                _lastEventId.value = persisted.cursor
                // 大页耗时是合理的。浮出每一次持久提交，让控制器的同步看门狗度量的是无进展
                // 而非墙钟时间。
                reportProgress(notify.eventId)
            }
        }
        if (notifyType == NotifyType.CHAT_CREATED) {
            // 严格在权威投影与持久游标提交之后发信号。worker 是合并的/非阻塞的，并且还会
            // 额外强制 AUTHENTICATED。
            publicationGate.use(publicationLease) { conversationRefreshSignals.trySend(Unit) }
        }
    }

    /**
     * 契约 decode：reader 统一取自 [NotifyContracts]（唯一事实源），
     * 与服务端 emit 侧共享同一张表，类型错配在两侧任一改动时即暴露。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : IProto> decodePayload(type: NotifyType, payload: ByteArray): T {
        val reader = NotifyContracts.payloads[type]
            ?: throw IllegalStateException("No payload contract for $type")
        return ProtoCodec.decode(reader as IProtoReader<T>, payload)
    }

    /** 按 NOTIFY 类型分发处理。internal 供单测直调（绕过监听协程）。 */
    internal suspend fun handleNotifyPayload(
        notifyType: NotifyType,
        payload: ByteArray,
        eventId: Long = 0L,
    ) {
        when (notifyType) {
            NotifyType.CONTACT_APPLY -> {
                // 新申请和申请状态变化都不是好友关系。只缓存申请人的资料并通知上层
                // 权威刷新历史、资料状态和红点；在 CONTACT_ACCEPTED 到达前绝不能写入 Contact。
                val apply = decodePayload<ContactApply>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    apply.fromUser?.let(localCache::upsertUser)
                    _contactEvents.tryEmit(Unit)
                }
            }

            NotifyType.CONTACT_ACCEPTED -> {
                // 契约：ACCEPTED 发各自视角的完整 Contact 快照。
                val contact = decodePayload<Contact>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    localCache.upsertContact(contact)
                    _contactEvents.tryEmit(Unit)
                }
            }

            NotifyType.CONTACT_DELETED -> {
                // DELETED 的 payload 只用 friendUid 定位 tombstone。Contact.status 默认为 1，
                // 因此绝不能与 ACCEPTED 共用 upsert 路径，否则删除/拉黑后会重新出现。
                val contact = decodePayload<Contact>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    localCache.deleteContact(contact.friendUid)
                    _contactEvents.tryEmit(Unit)
                }
            }

            NotifyType.CHAT_CREATED,
            NotifyType.CHAT_UPDATED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    localCache.upsertChat(chat)
                    // 新会话（如被拉入群）需要刷新 Conversation 全量投影，但
                    // SYNCHRONIZING 阶段严禁 RPC。先提交 Chat + cursor，READY 后合并刷新。
                    if (notifyType == NotifyType.CHAT_CREATED) {
                        markConversationsDirty()
                    }
                    _chatEvents.tryEmit(notifyType to chat)
                }
            }

            NotifyType.CHAT_DELETED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    val tombstone = { localCache.deleteChat(chat.chatId) }
                    durableChatTombstoneSink?.invoke(chat.chatId, tombstone) ?: tombstone()
                    _chatEvents.tryEmit(notifyType to chat)
                }
            }

            NotifyType.MEMBER_ADDED,
            NotifyType.MEMBER_REMOVED,
            NotifyType.MEMBER_MUTED,
            NotifyType.MEMBER_UNMUTED,
            NotifyType.MEMBER_ROLE_CHANGED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    localCache.upsertChat(chat)
                    _chatEvents.tryEmit(notifyType to chat)
                }
            }

            NotifyType.MESSAGE_RECV -> {
                val message = decodePayload<Message>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    localCache.insertMessage(message)
                    if (ownerUid != null && message.senderUid == ownerUid) {
                        onOutgoingProjectionMayHaveChanged?.invoke()
                    }
                    // 会话扩展是刻意同步的，并保持在硬发布边界内部。stop() 会等待它结束；任何
                    // 任意挂起的回调都不能在会话退役之后恢复外部副作用。
                    durableMessageSink?.let { sink ->
                        require(eventId > 0L) { "durable message inbox requires a positive eventId" }
                        sink(eventId, message)
                    }
                    _messageEvents.tryEmit(message)
                }
            }

            NotifyType.CONVERSATION_UPDATED -> {
                val conv = decodePayload<Conversation>(notifyType, payload)
                publicationGate.use(publicationLease) { localCache.upsertConversation(conv) }
            }

            NotifyType.CONVERSATION_DELETED -> {
                val conv = decodePayload<Conversation>(notifyType, payload)
                publicationGate.use(publicationLease) { localCache.deleteConversation(conv.chatId) }
            }

            NotifyType.PRESENCE -> {
                // 在线状态广播（服务端直写不持久化）；无头端消费 presenceEvents
                val presence = decodePayload<PresencePayload>(notifyType, payload)
                publicationGate.use(publicationLease) { _presenceEvents.tryEmit(presence) }
            }

            NotifyType.EVENT_CURSOR_ADVANCED -> {
                // 只允许无 payload 的版本降级占位；游标由 processNotifyLocked 正常持久化。
                error("EVENT_CURSOR_ADVANCED must not carry a payload")
            }
            NotifyType.TYPING -> {
                val msg = decodePayload<Message>(notifyType, payload)
                publicationGate.use(publicationLease) { _typingEvents.tryEmit(msg.chatId to msg.senderUid) }
            }
            NotifyType.READ_SYNC -> {
                val sync = decodePayload<ReadSyncPayload>(notifyType, payload)
                // 对方已读到 sync.peerReadSeq，更新该会话中对方已读状态
                publicationGate.use(publicationLease) {
                    localCache.updatePeerReadSeq(sync.chatId, sync.peerReadSeq)
                }
            }
            NotifyType.GROUP_FILE_CHANGED -> {
                val change = decodePayload<com.virjar.tk.protocol.GroupFileChangedPayload>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    if (change.operation == com.virjar.tk.protocol.GroupFileChangedPayload.OPERATION_UPSERT) {
                        localCache.applyGroupFileUpsert(requireNotNull(change.entry))
                    } else {
                        localCache.applyGroupFileDelete(
                            chatId = change.chatId,
                            entryId = change.deletedEntryId,
                            tombstoneRevision = change.deletedRevision,
                            updatedBy = "",
                            updatedAt = 0L,
                        )
                    }
                    _groupFileChanges.tryEmit(change)
                }
            }
            NotifyType.MESSAGE_REACTION -> {
                val reaction = decodePayload<com.virjar.tk.protocol.MessageReactionEventPayload>(notifyType, payload)
                // 行级 delta 幂等收敛；聚合快照由 MessageRepository 的主动拉取提供权威计数。
                publicationGate.use(publicationLease) {
                    localCache.applyMessageReactionDelta(reaction)
                }
            }

            NotifyType.USER_UPDATED -> {
                val user = decodePayload<User>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    if (eventId > 0L) {
                        localCache.upsertUser(user)
                    } else {
                        localCache.upsertTransientUserIfRelevant(user)
                    }
                }
            }

            NotifyType.ORGANIZATION_CHANGED -> {
                val changed = decodePayload<OrganizationChangedPayload>(notifyType, payload)
                publicationGate.use(publicationLease) {
                    // 在发布提示之前先持久化失效。该处理器刻意绝不发起组织业务 RPC。
                    val required = localCache.advanceOrganizationRequiredRevision(changed.revision)
                    val published = _organizationEvents.replayCache.lastOrNull() ?: 0L
                    if (required > published) _organizationEvents.tryEmit(required)
                }
            }

        }
    }
}
