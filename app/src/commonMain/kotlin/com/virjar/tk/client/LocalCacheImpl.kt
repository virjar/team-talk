package com.virjar.tk.client

import app.cash.sqldelight.db.SqlDriver
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.body.MessageBodyRegistry
import com.virjar.tk.model.*
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtoCodec
import io.netty.buffer.Unpooled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 SQLDelight 的 LocalCache 实现。
 *
 * 内存治理策略（Phase C）：
 * - 用户/联系人/聊天/成员/会话：初始化时全量加载（数据量天然有限）
 * - 消息：按需懒加载，聊天级 LRU（[LocalCache.MAX_ACTIVE_CHATS]），
 *   单聊窗口限制（[LocalCache.DEFAULT_MESSAGE_WINDOW]）
 * - [onChatInactive] 释放窗口，DB 持久化不变
 */
class LocalCacheImpl(driver: SqlDriver) : LocalCache {
    private val db = AppDatabase(driver)
    private val queries = db.appDatabaseQueries

    // 内存中的 StateFlow（非消息数据，全量加载）
    // 所有 StateFlow 的读-改-写复合操作必须在 stateLock 下进行——
    // MutableStateFlow.value 的 set 虽然线程安全，但 "value = value.filter{}" 这类
    // 读改写期间会被其他线程(EventProcessor 在 IO)的写插入，导致 last-write-wins 丢更新。
    private val stateLock = Any()
    private val contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    private val chatsFlow = MutableStateFlow<List<Chat>>(emptyList())
    private val membersFlow = MutableStateFlow<Map<String, List<Member>>>(emptyMap())
    private val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private val usersFlow = MutableStateFlow<List<User>>(emptyList())

    // ── 消息窗口（LRU 管理） ──
    // 每个 active chat 对应一个 MessageWindow，持有最近 N 条消息的内存副本
    private val chatWindows = ConcurrentHashMap<String, MessageWindow>()

    // LRU 跟踪：access-order LinkedHashMap，记录最后访问时间戳
    // synchronized(chatLock) 保护 chatWindows 和 chatLru 的复合操作
    private val chatLru = LinkedHashMap<String, Long>(LocalCache.MAX_ACTIVE_CHATS, 0.75f, true)
    private val chatLock = Any()

    init {
        loadFromDb()
    }

    private fun loadFromDb() {
        usersFlow.value = queries.selectAllUsers().executeAsList().map { it.toModel() }
        contactsFlow.value = queries.selectAllContacts().executeAsList().map { it.toModel() }
        chatsFlow.value = queries.selectAllChats().executeAsList().map { it.toModel() }
        conversationsFlow.value = queries.selectAllConversations().executeAsList().map { it.toModel() }

        val memberMap = mutableMapOf<String, List<Member>>()
        for (chat in chatsFlow.value) {
            val members = queries.selectMembersByChat(chat.chatId).executeAsList().map { it.toModel() }
            if (members.isNotEmpty()) memberMap[chat.chatId] = members
        }
        membersFlow.value = memberMap
    }

    // ── 用户 ──
    override fun getUser(uid: String): User? = usersFlow.value.find { it.uid == uid }
    override fun upsertUser(user: User) {
        queries.upsertUser(user.uid, user.username, user.name, user.avatar, user.phone, user.sex.toLong(), user.role.toLong(), user.status.toLong())
        updateFlow(usersFlow) { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.uid == user.uid }
            if (idx >= 0) list[idx] = user else list.add(user)
            list
        }
    }

    // ── 联系人 ──
    override fun getContacts(): List<Contact> {
        val users = usersFlow.value.associateBy { it.uid }
        return contactsFlow.value.map { contact ->
            val friendUser = users[contact.friendUid]
            if (friendUser != null && contact.user != friendUser) contact.copy(user = friendUser) else contact
        }
    }
    override fun observeContacts(): Flow<List<Contact>> = contactsFlow
    override fun upsertContact(contact: Contact) {
        queries.upsertContact(contact.uid, contact.friendUid, contact.remark, contact.status.toLong())
        contact.user?.let { user ->
            queries.upsertUser(user.uid, user.username, user.name, user.avatar, user.phone, user.sex.toLong(), user.role.toLong(), user.status.toLong())
            updateFlow(usersFlow) { current ->
                val list = current.toMutableList()
                val idx = list.indexOfFirst { it.uid == user.uid }
                if (idx >= 0) list[idx] = user else list.add(user)
                list
            }
        }
        updateFlow(contactsFlow) { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.friendUid == contact.friendUid }
            if (idx >= 0) list[idx] = contact else list.add(contact)
            list
        }
    }
    override fun deleteContact(friendUid: String) {
        queries.deleteContact(friendUid)
        updateFlow(contactsFlow) { it.filter { c -> c.friendUid != friendUid } }
    }

    // ── 聊天 ──
    override fun getChat(chatId: String): Chat? = chatsFlow.value.find { it.chatId == chatId }
    override fun upsertChat(chat: Chat) {
        queries.upsertChat(chat.chatId, chat.chatType.toLong(), chat.name, chat.avatar, chat.creator, chat.memberCount.toLong(), chat.maxSeq, chat.notice, if (chat.mutedAll) 1L else 0L)
        updateFlow(chatsFlow) { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.chatId == chat.chatId }
            if (idx >= 0) list[idx] = chat else list.add(chat)
            list
        }
    }
    override fun deleteChat(chatId: String) {
        queries.deleteChat(chatId)
        chatsFlow.value = chatsFlow.value.filter { it.chatId != chatId }
        // 同步释放该聊天的消息窗口
        onChatInactive(chatId)
    }

    // ── 成员 ──
    override fun getMembers(chatId: String): List<Member> = membersFlow.value[chatId] ?: emptyList()
    override fun observeMembers(chatId: String): Flow<List<Member>> = membersFlow.map { it[chatId] ?: emptyList() }
    override fun upsertMember(member: Member) {
        queries.upsertMember(member.chatId, member.uid, member.role.toLong(), member.nickname, member.joinedAt)
        synchronized(stateLock) {
            val current = membersFlow.value.toMutableMap()
            val list = (current[member.chatId] ?: emptyList()).toMutableList()
            val idx = list.indexOfFirst { it.uid == member.uid }
            if (idx >= 0) list[idx] = member else list.add(member)
            current[member.chatId] = list
            membersFlow.value = current
        }
    }
    override fun removeMember(chatId: String, uid: String) {
        queries.removeMember(chatId, uid)
        synchronized(stateLock) {
            val current = membersFlow.value.toMutableMap()
            current[chatId] = (current[chatId] ?: emptyList()).filter { it.uid != uid }
            membersFlow.value = current
        }
    }

    // ── 消息（LRU 窗口 + 持久化） ──

    override fun getMessages(chatId: String, limit: Int): List<Message> =
        getOrCreateWindow(chatId).snapshot(limit)

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        getOrCreateWindow(chatId).messages

    override fun insertMessage(message: Message) {
        val bodyBytes = message.body?.let { ProtoCodec.encode(it) }
        queries.insertMessage(message.chatId, message.clientMsgId, message.serverSeq, message.senderUid, message.messageType.toLong(), message.timestamp, message.flags.toLong(), bodyBytes, message.sendStatus.toLong())
        // 只更新已驻留的窗口；未驻留的 chat 下次 observe 时从 DB 加载
        chatWindows[message.chatId]?.upsert(message)
    }

    override fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) {
        queries.updateMessageSeqStatus(serverSeq, chatId, clientMsgId)
        chatWindows[chatId]?.updateMessage(clientMsgId, serverSeq = serverSeq, sendStatus = Message.SEND_STATUS_SENT)
    }

    override fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) {
        queries.updateMessageSendStatus(sendStatus.toLong(), chatId, clientMsgId)
        chatWindows[chatId]?.updateMessage(clientMsgId, sendStatus = sendStatus)
    }

    // ── Phase C：内存治理 API ──

    override fun pager(chatId: String, windowSize: Int): MessagePager = getOrCreateWindow(chatId, windowSize)

    override fun onChatInactive(chatId: String) {
        synchronized(chatLock) {
            chatWindows.remove(chatId)
            chatLru.remove(chatId)
        }
    }

    /**
     * 获取或创建某聊天的消息窗口。
     * 触发 LRU 更新；超过 [LocalCache.MAX_ACTIVE_CHATS] 时 evict 最旧窗口。
     */
    private fun getOrCreateWindow(chatId: String, windowSize: Int = LocalCache.DEFAULT_MESSAGE_WINDOW): MessageWindow {
        synchronized(chatLock) {
            chatLru[chatId] = System.currentTimeMillis()  // access-order 更新
            val existing = chatWindows[chatId]
            if (existing != null) {
                evictIfOverCapacity()
                return existing
            }
            val window = MessageWindow(chatId, queries, windowSize) { it.toModel() }
            chatWindows[chatId] = window
            evictIfOverCapacity()
            return window
        }
    }

    /** LRU 淘汰：超出 [LocalCache.MAX_ACTIVE_CHATS] 时移除最旧窗口（仅内存，DB 不动）。 */
    private fun evictIfOverCapacity() {
        while (chatLru.size > LocalCache.MAX_ACTIVE_CHATS) {
            val oldestChatId = chatLru.keys.firstOrNull() ?: break
            chatLru.remove(oldestChatId)
            chatWindows.remove(oldestChatId)
        }
    }

    // ── 会话 ──
    override fun getConversations(): List<Conversation> = conversationsFlow.value
    override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow
    override fun upsertConversation(conv: Conversation) {
        queries.upsertConversation(conv.chatId, conv.chatType.toLong(), conv.chatName, conv.chatAvatar, conv.lastSeq, conv.readSeq, conv.unreadCount.toLong(), if (conv.isPinned) 1L else 0L, if (conv.isMuted) 1L else 0L, conv.draft, conv.lastMsgTimestamp ?: 0L)
        updateFlow(conversationsFlow) { mergeSorted(it, conv) }
    }

    /**
     * 本地与服务端 Conversation 合并策略（解决状态覆盖类 bug）。
     *
     * 规则：
     * - unreadCount: 本地 readSeq ≥ 服务端 readSeq 且本地已清零 → 不被服务端旧值覆盖
     * - draft: 本地有草稿时不被服务端覆盖（草稿是纯客户端状态，服务端只是镜像）
     * - readSeq: 取较大值（已读位置只前进不后退）
     */
    private fun mergeConversation(local: Conversation, remote: Conversation): Conversation {
        val mergedReadSeq = maxOf(local.readSeq, remote.readSeq)
        // 已读状态保护：本地已标记已读，服务端通知滞后
        val mergedUnread = if (local.readSeq >= remote.readSeq && local.unreadCount == 0) 0 else remote.unreadCount
        // 草稿保护：本地非空草稿优先
        val mergedDraft = local.draft ?: remote.draft
        return remote.copy(
            readSeq = mergedReadSeq,
            unreadCount = mergedUnread,
            draft = mergedDraft,
        )
    }

    override fun deleteConversation(chatId: String) {
        queries.deleteConversation(chatId)
        updateFlow(conversationsFlow) { it.filter { c -> c.chatId != chatId } }
    }

    override fun markConversationRead(chatId: String, readSeq: Long) {
        queries.markConversationRead(readSeq, chatId)
        updateFlow(conversationsFlow) { current ->
            current.map { if (it.chatId == chatId) it.copy(unreadCount = 0, readSeq = readSeq) else it }
        }
    }

    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) {
        updateFlow(conversationsFlow) { current ->
            current.map { if (it.chatId == chatId) it.copy(peerReadSeq = peerReadSeq) else it }
        }
    }

    override fun toggleConversationPin(chatId: String, pinned: Boolean): Conversation? {
        return synchronized(stateLock) {
            val existing = conversationsFlow.value.find { it.chatId == chatId } ?: return@synchronized null
            val updated = existing.copy(isPinned = pinned)
            // 直接在锁内更新 DB + flow（不递归 updateFlow 的锁）
            queries.upsertConversation(updated.chatId, updated.chatType.toLong(), updated.chatName, updated.chatAvatar, updated.lastSeq, updated.readSeq, updated.unreadCount.toLong(), if (updated.isPinned) 1L else 0L, if (updated.isMuted) 1L else 0L, updated.draft, updated.lastMsgTimestamp ?: 0L)
            conversationsFlow.value = mergeSorted(conversationsFlow.value, updated)
            updated
        }
    }

    /** 在已排序列表中 upsert 一条会话（保持 置顶+时间 排序）。调用方持 stateLock。 */
    private fun mergeSorted(current: List<Conversation>, conv: Conversation): List<Conversation> {
        val list = current.toMutableList()
        val idx = list.indexOfFirst { it.chatId == conv.chatId }
        val merged = if (idx >= 0) mergeConversation(list[idx], conv) else conv
        if (idx >= 0) list[idx] = merged else list.add(merged)
        list.sortWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.lastMsgTimestamp ?: 0L })
        return list
    }

    // ── helpers ──
    /**
     * 加锁的 StateFlow 读-改-写。所有非消息数据的列表更新必须走此方法，
     * 避免多线程(EventProcessor IO / UI Main)并发 upsert 时丢更新。
     */
    private fun <T> updateFlow(flow: MutableStateFlow<List<T>>, update: (List<T>) -> List<T>) {
        synchronized(stateLock) {
            flow.value = update(flow.value)
        }
    }
}

// ── SQLDelight generated row -> domain model mapping ─

private fun com.virjar.tk.database.User.toModel() = User(
    uid = uid, username = username, name = name,
    avatar = avatar, phone = phone,
    sex = sex?.toInt() ?: 0, role = role?.toInt() ?: 0, status = status?.toInt() ?: 1,
)

private fun com.virjar.tk.database.Contact.toModel() = Contact(
    uid = uid, friendUid = friend_uid, remark = remark, status = status?.toInt() ?: 1,
)

private fun com.virjar.tk.database.Chat.toModel() = Chat(
    chatId = chat_id, chatType = chat_type.toInt(), name = name,
    avatar = avatar, creator = creator,
    memberCount = member_count?.toInt() ?: 0, maxSeq = max_seq ?: 0L,
    notice = notice, mutedAll = muted_all != 0L,
)

private fun com.virjar.tk.database.Member.toModel() = Member(
    chatId = chat_id, uid = uid, role = role?.toInt() ?: 0,
    nickname = nickname, joinedAt = joined_at ?: 0L,
)

private fun com.virjar.tk.database.Message.toModel(): Message {
    val bodyBytes = body
    val body = if (bodyBytes != null) {
        try {
            val msgType = MessageType.fromCode(message_type.toInt()) ?: MessageType.TEXT
            val byteBuf = Unpooled.wrappedBuffer(bodyBytes)
            val buf = PacketBuffer(byteBuf)
            MessageBodyRegistry.decode(msgType, buf)
        } catch (e: Exception) { com.virjar.tk.util.AppLog.fault("LocalCache", "Failed to decode message body chatId=$chat_id msgId=$client_msg_id", e); null }
    } else null
    return Message(
        chatId = chat_id, clientMsgId = client_msg_id, serverSeq = server_seq ?: 0L,
        senderUid = sender_uid, messageType = message_type.toInt(),
        timestamp = timestamp, flags = flags?.toInt() ?: 0, body = body,
        sendStatus = send_status?.toInt() ?: 0,
    )
}

private fun com.virjar.tk.database.Conversation.toModel() = Conversation(
    chatId = chat_id, chatType = chat_type.toInt(), chatName = chat_name,
    chatAvatar = chat_avatar, lastSeq = last_seq ?: 0L, readSeq = read_seq ?: 0L,
    unreadCount = unread_count?.toInt() ?: 0, isPinned = is_pinned == 1L,
    isMuted = is_muted == 1L, draft = draft, lastMsgTimestamp = last_msg_timestamp,
)
