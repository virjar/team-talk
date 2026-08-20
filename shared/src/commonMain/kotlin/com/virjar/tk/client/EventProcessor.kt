package com.virjar.tk.client

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
import kotlinx.coroutines.flow.*


/**
 * 事件处理器。收集 NotifyPayload 并写入本地缓存。
 * 收集协程运行在 ImClient 的 EventLoop scope 上，
 * DB 操作切换到 Dispatchers.IO 避免阻塞 EventLoop。
 */
class EventProcessor(
    private val imClient: ImClient,
    private val localCache: LocalCache,
    /**
     * 会话/群变更时的刷新回调。收到 CHAT_CREATED 通知时触发，
     * 用于从服务端拉取最新会话列表——否则被拉入群的一方本地 Conversation 表
     * 不会更新，群会话不出现在会话列表（与建群发起方的 listConversations 修复保持一致）。
     */
    private val onConversationsDirty: (suspend () -> Unit)? = null,
    /** 联系人关系变更回调（好友申请/接受/删除），用于刷新红点等。 */
    var onContactChanged: (() -> Unit)? = null,
) {
    private val logger = TkLoggerFactory.get("EventProcessor")
    private var listenJob: Job? = null

    private val _lastEventId = MutableStateFlow(0L)
    val lastEventId: StateFlow<Long> = _lastEventId.asStateFlow()

    /** typing 事件：(chatId, senderUid) */
    private val _typingEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val typingEvents: SharedFlow<Pair<String, String>> = _typingEvents.asSharedFlow()

    /** 入站消息事件流（无头客户端/AI bot 的消息入口，UI 客户端走 LocalCache 观察也可）。 */
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
    @Volatile
    private var started = false

    fun start() {
        started = true
        ensureListening()
        if (watcherJob?.isActive == true) return
        watcherJob = lifecycleScope.launch {
            imClient.state.collect { state ->
                if (state == ConnectionState.CONNECTED && listenJob?.isActive != true) {
                    logger.trace("Connection restored, restarting event listener")
                    ensureListening()
                }
            }
        }
    }

    private fun ensureListening() {
        val scope = imClient.coroutineScope ?: run {
            logger.fault("Cannot listen: ImClient not connected")
            return
        }
        if (!started || listenJob?.isActive == true) return
        listenJob = scope.launch {
            try {
                imClient.packets.collect { proto ->
                    if (proto is NotifyPayload) {
                        withContext(Dispatchers.IO) { processNotify(proto) }
                    }
                }
            } catch (e: CancellationException) {
                // 正常的协作式取消（断连/重连时 SupervisorJob 被 cancel），不是 crash
                throw e
            } catch (e: Exception) {
                // 根监听循环：记好日志后兜住，不让单次错误搞垮整个监听
                logger.fault("EventProcessor listen loop crashed, events lost until reconnect", e)
            }
        }
    }

    fun stop() {
        started = false
        watcherJob?.cancel()
        listenJob?.cancel()
    }

    private suspend fun processNotify(notify: NotifyPayload) {
        try {
            val notifyType = NotifyType.fromCode(notify.notifyType)
            val payload = notify.payload
            // payload 为空的事件（如部分 PRESENCE）直接视为已处理
            if (payload != null) {
                handleNotifyPayload(notifyType, payload)
            }
            // 处理成功才推进游标：失败时不推进，下次重连/上线时服务端按
            // lastEventId 补发会重新拿到该事件，天然重试。
            // 不会死循环：消息类事件有独立的 seq 兜底（进聊天页按 seq 拉历史）。
            //
            // eventId=0 是非持久事件（PRESENCE 直写 / SUBSCRIBE 历史回放），
            // 不参与游标——推进它会把游标砸回 0，重连时 lastEventId>0 不成立，
            // 离线补发被整体跳过（历史 bug：好友上线广播即可触发）。
            if (notify.eventId > 0) {
                _lastEventId.value = notify.eventId
            }
        } catch (e: Exception) {
            // 处理失败：游标不推进，记录错误。下次补发自会重试该事件。
            // 若为永久性错误（如协议不兼容），事件会在 7 天 TTL 后自然过期，
            // 此时游标虽暂时落后，但新事件的补发会持续触发，TTL 后即可推进。
            logger.fault("Failed to process notify eventId=${notify.eventId} type=${notify.notifyType}", e)
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
    internal suspend fun handleNotifyPayload(notifyType: NotifyType, payload: ByteArray) {
        when (notifyType) {
            NotifyType.CONTACT_APPLY -> {
                // 好友申请不是好友关系。只缓存申请人的资料并通知上层刷新
                // 待处理申请；在 CONTACT_ACCEPTED 到达前绝不能写入 Contact。
                val apply = decodePayload<ContactApply>(notifyType, payload)
                apply.fromUser?.let(localCache::upsertUser)
                onContactChanged?.invoke()
                _contactEvents.emit(Unit)
            }

            NotifyType.CONTACT_ACCEPTED -> {
                // 契约：ACCEPTED 发各自视角的完整 Contact 快照。
                val contact = decodePayload<Contact>(notifyType, payload)
                localCache.upsertContact(contact)
                onContactChanged?.invoke()
                _contactEvents.emit(Unit)
            }

            NotifyType.CONTACT_DELETED -> {
                // DELETED 的 payload 只用 friendUid 定位 tombstone。Contact.status 默认为 1，
                // 因此绝不能与 ACCEPTED 共用 upsert 路径，否则删除/拉黑后会重新出现。
                val contact = decodePayload<Contact>(notifyType, payload)
                localCache.deleteContact(contact.friendUid)
                onContactChanged?.invoke()
                _contactEvents.emit(Unit)
            }

            NotifyType.CHAT_CREATED,
            NotifyType.CHAT_UPDATED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                localCache.upsertChat(chat)
                // 新会话（如被拉入群）需要刷新本地会话列表，
                // 否则 Conversation 表无对应记录，群会话不显示。
                if (notifyType == NotifyType.CHAT_CREATED) {
                    onConversationsDirty?.invoke()
                }
                _chatEvents.emit(notifyType to chat)
            }

            NotifyType.CHAT_DELETED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                localCache.deleteConversation(chat.chatId)
                localCache.deleteChat(chat.chatId)
                _chatEvents.emit(notifyType to chat)
            }

            NotifyType.MEMBER_ADDED,
            NotifyType.MEMBER_REMOVED,
            NotifyType.MEMBER_MUTED,
            NotifyType.MEMBER_UNMUTED,
            NotifyType.MEMBER_ROLE_CHANGED -> {
                val chat = decodePayload<Chat>(notifyType, payload)
                localCache.upsertChat(chat)
                _chatEvents.emit(notifyType to chat)
            }

            NotifyType.MESSAGE_RECV -> {
                val message = decodePayload<Message>(notifyType, payload)
                localCache.insertMessage(message)
                _messageEvents.emit(message)
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
                _presenceEvents.emit(presence)
            }

            NotifyType.GENERIC -> {
                // 通用扩展入口（协议演进策略 §9）：未注册扩展静默忽略（前向兼容），
                // 游标照常推进。分发机制（GenericDispatcher）待首个扩展需求落地时实现。
                logger.trace("GENERIC notify ignored (no extension registered)")
            }
            NotifyType.TYPING -> {
                val msg = decodePayload<Message>(notifyType, payload)
                _typingEvents.emit(msg.chatId to msg.senderUid)
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
}
