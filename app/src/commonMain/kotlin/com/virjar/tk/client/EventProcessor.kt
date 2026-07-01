package com.virjar.tk.client

import com.virjar.tk.model.*
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
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

    fun start() {
        val scope = imClient.coroutineScope ?: run {
            logger.fault("Cannot start: ImClient not connected")
            return
        }
        // 幂等：重复 start 先取消旧监听协程，避免泄漏（CLAUDE.md: 销毁操作幂等）
        listenJob?.cancel()
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
            // 不会死循环：消息类事件有独立的 seq 兜底（进聊天页按 seq 拉历史），
            // 且服务端 sync_events 有 7 天 TTL，过期后补发自然跳过。
            _lastEventId.value = notify.eventId
        } catch (e: Exception) {
            // 处理失败：游标不推进，记录错误。下次补发自会重试该事件。
            // 若为永久性错误（如协议不兼容），事件会在 7 天 TTL 后自然过期，
            // 此时游标虽暂时落后，但新事件的补发会持续触发，TTL 后即可推进。
            logger.fault("Failed to process notify eventId=${notify.eventId} type=${notify.notifyType}", e)
        }
    }

    private suspend fun handleNotifyPayload(notifyType: NotifyType, payload: ByteArray) {
        when (notifyType) {
            NotifyType.CONTACT_APPLY,
            NotifyType.CONTACT_ACCEPTED,
            NotifyType.CONTACT_DELETED -> {
                // 服务端发送 ContactApply（含 fromUid/toUid），转换为 Contact
                val apply = ProtoCodec.decode(ContactApply, payload)
                val contact = Contact(
                    uid = apply.toUid, friendUid = apply.fromUid,
                    remark = apply.remark, status = 1, user = apply.fromUser,
                )
                localCache.upsertContact(contact)
                onContactChanged?.invoke()
            }

            NotifyType.CHAT_CREATED,
            NotifyType.CHAT_UPDATED,
            NotifyType.CHAT_DELETED -> {
                val chat = ProtoCodec.decode(Chat, payload)
                localCache.upsertChat(chat)
                // 新会话（如被拉入群）需要刷新本地会话列表，
                // 否则 Conversation 表无对应记录，群会话不显示。
                if (notifyType == NotifyType.CHAT_CREATED) {
                    onConversationsDirty?.invoke()
                }
            }

            NotifyType.MEMBER_ADDED,
            NotifyType.MEMBER_REMOVED,
            NotifyType.MEMBER_MUTED,
            NotifyType.MEMBER_UNMUTED,
            NotifyType.MEMBER_ROLE_CHANGED -> {
                val chat = ProtoCodec.decode(Chat, payload)
                localCache.upsertChat(chat)
            }

            NotifyType.MESSAGE_RECV -> {
                val message = ProtoCodec.decode(Message, payload)
                localCache.insertMessage(message)
            }

            NotifyType.CONVERSATION_UPDATED -> {
                val conv = ProtoCodec.decode(Conversation, payload)
                localCache.upsertConversation(conv)
            }

            NotifyType.CONVERSATION_DELETED -> {
                val conv = ProtoCodec.decode(Conversation, payload)
                localCache.deleteConversation(conv.chatId)
            }

            NotifyType.PRESENCE -> {
                // PresencePayload: uid(string) + status(byte) + lastSeenAt(varLong)
                // User 模型暂无在线状态字段，仅记录日志
                val buf = com.virjar.tk.protocol.PacketBuffer(io.netty.buffer.Unpooled.wrappedBuffer(payload))
                val uid = buf.readString()
                val status = buf.readByte()
                val lastSeenAt = buf.readVarLong()
                logger.trace("PRESENCE: uid=$uid status=$status lastSeenAt=$lastSeenAt")
            }
            NotifyType.TYPING -> {
                val msg = ProtoCodec.decode(Message, payload)
                _typingEvents.emit(msg.chatId to msg.senderUid)
            }
            NotifyType.READ_SYNC -> {
                val sync = ProtoCodec.decode(com.virjar.tk.protocol.ReadSyncPayload, payload)
                // 对方已读到 sync.peerReadSeq，更新该会话中对方已读状态
                localCache.updatePeerReadSeq(sync.chatId, sync.peerReadSeq)
            }

            NotifyType.USER_UPDATED -> {
                val user = ProtoCodec.decode(User, payload)
                localCache.upsertUser(user)
            }

            NotifyType.GENERIC -> {
                // 通用扩展推送：解析 GenericPayload，按 extensionType 分发
                val generic = ProtoCodec.decode(com.virjar.tk.protocol.payload.GenericPayload, payload)
                GenericDispatcher.dispatchNotify(generic.extensionType, generic.data)
            }
        }
    }
}
