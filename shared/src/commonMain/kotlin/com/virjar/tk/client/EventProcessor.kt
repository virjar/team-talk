package com.virjar.tk.client

import com.virjar.tk.body.GenericPayload
import com.virjar.tk.model.*
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.NotifyContracts
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.log.TkLoggerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
     * Optional reliable session-owned sink for durable inbound messages (used by ImBot inbox).
     * It receives the authoritative event id, runs after the local insert and before cursor commit,
     * and persists with INSERT OR IGNORE. Failure aborts the batch so replay can retry; UI-facing
     * [messageEvents] remains a non-blocking refresh stream.
     */
    private val durableMessageSink: (suspend (Long, Message) -> Unit)? = null,
) {
    private val logger = TkLoggerFactory.get("EventProcessor")
    private var listenJob: Job? = null

    private val _lastEventId = MutableStateFlow(localCache.getSyncCursor(SYNC_CURSOR_KEY))
    val lastEventId: StateFlow<Long> = _lastEventId.asStateFlow()

    /** typing 事件：(chatId, senderUid) */
    private val _typingEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val typingEvents: SharedFlow<Pair<String, String>> = _typingEvents.asSharedFlow()

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

    /** 好友在线状态事件（上下线广播，服务端直写不持久化）。 */
    private val _presenceEvents = MutableSharedFlow<PresencePayload>(extraBufferCapacity = 32)
    val presenceEvents: SharedFlow<PresencePayload> = _presenceEvents.asSharedFlow()

    /**
     * 自治重连 watcher：断线时监听协程随连接 scope 消亡；
     * 重连成功（新 scope 就绪）时自动重启。与 RpcClient 同模式。
     * （历史 bug：监听只启动一次，断线重连后 NOTIFY 全部静默丢失。）
     */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, t ->
        logger.fault("EventProcessor lifecycle watcher crashed", t)
    })
    private var watcherJob: Job? = null
    private var conversationRefreshJob: Job? = null
    private val conversationsDirty = MutableStateFlow(false)
    private val conversationRefreshSignals = Channel<Unit>(Channel.CONFLATED)
    /** Serializes live delivery, replay pages, and destructive projection reset across reconnects. */
    private val projectionMutex = Mutex()
    private val syncOwner = Any()
    @Volatile
    private var started = false
    @Volatile
    private var stopped = false

    fun start() {
        check(!stopped) { "EventProcessor is session-owned and cannot restart after stop" }
        if (started) return
        started = true
        ensureConversationRefreshWorker()
        ensureListening()
        imClient.installEventSync(
            owner = syncOwner,
            cursor = { lastEventId.value },
            processBatch = { events, reportProgress ->
                withContext(Dispatchers.IO) { processBatch(events, reportProgress) }
            },
            reset = {
                withContext(Dispatchers.IO) { resetServerProjection() }
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
     * Every ready edge performs one full reconciliation even when the in-memory dirty flag is
     * false. A process may die after committing CHAT_CREATED's cursor but before persisting any
     * auxiliary signal; unconditional reconciliation closes that crash window.
     */
    internal fun requireConversationReconciliation() {
        conversationsDirty.value = true
        conversationRefreshSignals.trySend(Unit)
    }

    /**
     * Consume one coalesced refresh only beyond the authentication boundary.
     *
     * Failure deliberately restores dirty without immediately signalling again: retry happens on
     * the next CHAT_CREATED or AUTHENTICATED edge, so an unavailable RPC cannot create a hot loop.
     * The durable event cursor has already committed and is never rolled back by this hint refresh.
     */
    internal suspend fun refreshDirtyConversations(
        authenticated: Boolean = imClient.state.value == ConnectionState.AUTHENTICATED,
    ): Boolean {
        val refresh = onConversationsDirty ?: return true
        if (!authenticated || !conversationsDirty.value) return false
        conversationsDirty.value = false
        return try {
            refresh()
            true
        } catch (cancelled: CancellationException) {
            conversationsDirty.value = true
            throw cancelled
        } catch (failure: Throwable) {
            conversationsDirty.value = true
            logger.fault("Conversation refresh failed; keeping dirty for a later ready edge", failure)
            false
        }
    }

    internal val hasDirtyConversations: Boolean
        get() = conversationsDirty.value

    private fun ensureListening() {
        if (!started || listenJob?.isActive == true) return
        // This subscription belongs to the authenticated session rather than one TCP attempt. It is
        // visible before installEventSync can send SYNC_REQUEST and survives every reconnect, so the
        // READY -> first live NOTIFY boundary has no replay=0 subscription gap.
        listenJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            imClient.routedPackets.collect { packet ->
                val proto = packet.payload as? NotifyPayload ?: return@collect
                // A packet already queued by a retired TCP attempt is never allowed to mutate the
                // replacement attempt. Durable replay will deliver it again when required.
                if (packet.connectionGeneration != imClient.currentConnectionGeneration) {
                    return@collect
                }
                try {
                    withContext(Dispatchers.IO) { processNotify(proto) }
                } catch (cancelled: CancellationException) {
                    // Session stop owns this cancellation; TCP reconnects do not replace listener.
                    throw cancelled
                } catch (failure: Exception) {
                    // Keep the session-owned collector alive for the replacement connection. The
                    // generation lease prevents a late failure from closing that replacement.
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

    fun stop() {
        if (stopped) return
        stopped = true
        started = false
        imClient.removeEventSync(syncOwner)
        listenJob?.cancel()
        watcherJob?.cancel()
        conversationRefreshJob?.cancel()
        conversationRefreshSignals.close()
        lifecycleScope.cancel()
    }

    /**
     * Project one server page in event-id order. Every successful item advances the durable
     * cursor independently; the first failure escapes immediately and later items are untouched.
     */
    internal suspend fun processBatch(
        events: List<NotifyPayload>,
        reportProgress: (Long) -> Unit = {},
    ): Long {
        require(events.isNotEmpty()) { "sync batch must not be empty" }
        return projectionMutex.withLock {
            events.forEach { processNotifyLocked(it, reportProgress) }
            val expectedCursor = events.last().eventId
            check(lastEventId.value >= expectedCursor) {
                "sync page was not durably projected through cursor=$expectedCursor"
            }
            // A retired live delivery may already have persisted farther. The server page itself
            // is nevertheless complete through expectedCursor, so acknowledge precisely its end.
            expectedCursor
        }
    }

    /** Destructive recovery requested by an authenticated server-side cursor rejection. */
    internal suspend fun resetServerProjection(): Long = projectionMutex.withLock {
        localCache.resetServerProjection()
        _lastEventId.value = localCache.getSyncCursor(SYNC_CURSOR_KEY)
        check(_lastEventId.value == 0L) { "projection reset did not clear sync cursor" }
        conversationsDirty.value = false
        _lastEventId.value
    }

    internal suspend fun processNotify(notify: NotifyPayload) =
        projectionMutex.withLock { processNotifyLocked(notify) }

    private suspend fun processNotifyLocked(
        notify: NotifyPayload,
        reportProgress: (Long) -> Unit = {},
    ) {
        // 重连/最终激活竞态可能产生 at-least-once 重复。已经持久化完成的事件不再
        // 重放上层 SharedFlow 副作用；服务端保证同一用户的持久事件按 ID 交付。
        if (notify.eventId > 0L && notify.eventId <= _lastEventId.value) {
            reportProgress(notify.eventId)
            return
        }
        val notifyType = NotifyType.fromCode(notify.notifyType)
        val payload = notify.payload
        // payload 为空的事件（如部分 PRESENCE）直接视为已处理。
        if (payload != null) {
            handleNotifyPayload(notifyType, payload, notify.eventId)
        }
        // 只有完整投影成功后才单调落盘。异常故意向监听/批次循环传播：调用方必须
        // 立即停止后续事件并关闭连接，重连从最后一个已持久化 cursor 继续。
        if (notify.eventId > 0L) {
            _lastEventId.value = localCache.advanceSyncCursor(SYNC_CURSOR_KEY, notify.eventId)
            // A large page can legitimately take time. Surface each durable commit so the
            // controller's synchronization watchdog measures no-progress rather than wall time.
            reportProgress(notify.eventId)
        }
        if (notifyType == NotifyType.CHAT_CREATED) {
            // Signal strictly after the authoritative projection and durable cursor commit. The
            // worker is conflated/non-blocking and will additionally enforce AUTHENTICATED.
            conversationRefreshSignals.trySend(Unit)
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
                // 好友申请不是好友关系。只缓存申请人的资料并通知上层刷新
                // 待处理申请；在 CONTACT_ACCEPTED 到达前绝不能写入 Contact。
                val apply = decodePayload<ContactApply>(notifyType, payload)
                apply.fromUser?.let(localCache::upsertUser)
                _contactEvents.tryEmit(Unit)
            }

            NotifyType.CONTACT_ACCEPTED -> {
                // 契约：ACCEPTED 发各自视角的完整 Contact 快照。
                val contact = decodePayload<Contact>(notifyType, payload)
                localCache.upsertContact(contact)
                _contactEvents.tryEmit(Unit)
            }

            NotifyType.CONTACT_DELETED -> {
                // DELETED 的 payload 只用 friendUid 定位 tombstone。Contact.status 默认为 1，
                // 因此绝不能与 ACCEPTED 共用 upsert 路径，否则删除/拉黑后会重新出现。
                val contact = decodePayload<Contact>(notifyType, payload)
                localCache.deleteContact(contact.friendUid)
                _contactEvents.tryEmit(Unit)
            }

            NotifyType.CHAT_CREATED,
            NotifyType.CHAT_UPDATED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                localCache.upsertChat(chat)
                // 新会话（如被拉入群）需要刷新 Conversation 全量投影，但
                // SYNCHRONIZING 阶段严禁 RPC。先提交 Chat + cursor，READY 后合并刷新。
                if (notifyType == NotifyType.CHAT_CREATED) {
                    markConversationsDirty()
                }
                _chatEvents.tryEmit(notifyType to chat)
            }

            NotifyType.CHAT_DELETED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                localCache.deleteChat(chat.chatId)
                _chatEvents.tryEmit(notifyType to chat)
            }

            NotifyType.MEMBER_ADDED,
            NotifyType.MEMBER_REMOVED,
            NotifyType.MEMBER_MUTED,
            NotifyType.MEMBER_UNMUTED,
            NotifyType.MEMBER_ROLE_CHANGED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                localCache.upsertChat(chat)
                _chatEvents.tryEmit(notifyType to chat)
            }

            NotifyType.MESSAGE_RECV -> {
                val message = decodePayload<Message>(notifyType, payload)
                localCache.insertMessage(message)
                // The disk inbox insert and cursor are separate idempotent statements: a crash
                // between them replays this event, and INSERT OR IGNORE deduplicates by eventId.
                durableMessageSink?.let { sink ->
                    require(eventId > 0L) { "durable message inbox requires a positive eventId" }
                    sink(eventId, message)
                }
                _messageEvents.tryEmit(message)
            }

            NotifyType.CONVERSATION_UPDATED -> {
                val conv = decodePayload<Conversation>(notifyType, payload)
                localCache.upsertConversation(conv)
            }

            NotifyType.CONVERSATION_DELETED -> {
                val conv = decodePayload<Conversation>(notifyType, payload)
                localCache.deleteConversation(conv.chatId)
            }

            NotifyType.PRESENCE -> {
                // 在线状态广播（服务端直写不持久化）；无头端消费 presenceEvents
                val presence = decodePayload<PresencePayload>(notifyType, payload)
                _presenceEvents.tryEmit(presence)
            }

            NotifyType.GENERIC -> {
                // 先严格消费通用信封，避免 malformed payload 被当作已处理事件；只要信封合法，
                // 未知 extensionType 就是前向兼容输入，opaque data 不解释、不记录，外层照常提交游标。
                val generic = decodePayload<GenericPayload>(notifyType, payload)
                // 首个真实通知扩展落地时在这个会话所有的 EventProcessor 边界注入 handler；
                // 禁止建立会跨登录会话泄漏状态的全局可变 GenericDispatcher。
                logger.trace("GENERIC notify ignored (extensionType=${generic.extensionType}, no session handler)")
            }
            NotifyType.TYPING -> {
                val msg = decodePayload<Message>(notifyType, payload)
                _typingEvents.tryEmit(msg.chatId to msg.senderUid)
            }
            NotifyType.READ_SYNC -> {
                val sync = decodePayload<ReadSyncPayload>(notifyType, payload)
                // 对方已读到 sync.peerReadSeq，更新该会话中对方已读状态
                localCache.updatePeerReadSeq(sync.chatId, sync.peerReadSeq)
            }

            NotifyType.USER_UPDATED -> {
                val user = decodePayload<User>(notifyType, payload)
                localCache.upsertUser(user)
            }


        }
    }

    companion object {
        const val SYNC_CURSOR_KEY = "durable_events"
    }
}
