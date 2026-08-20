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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

private const val DRAFT_MIRROR_PENDING = 0L
private const val DRAFT_MIRROR_ACKED = 1L

private data class LocalDraftOverride(
    val draft: String?,
    val generation: Long,
    val state: Long,
    val observedAuthority: AuthoritativeDraftObservation? = null,
)

/** 包装类用于区分“还没观察到事件”与“已观察到权威 null”。 */
private data class AuthoritativeDraftObservation(val draft: String?)

private data class ConversationMergePlan(
    val conversation: Conversation,
    val draftOverride: LocalDraftOverride?,
    val clearDraftOverride: Boolean,
)

/**
 * 基于 SQLDelight 的 LocalCache 实现。
 *
 * 内存治理策略（Phase C）：
 * - 用户/联系人/聊天/成员/会话：初始化时全量加载（数据量天然有限）
 * - 消息：按需懒加载，聊天级 LRU（[LocalCache.MAX_ACTIVE_CHATS]），
 *   单聊窗口限制（[LocalCache.DEFAULT_MESSAGE_WINDOW]）
 * - [onChatInactive] 释放窗口，DB 持久化不变
 */
class LocalCacheImpl(private val driver: SqlDriver) : LocalCache {
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
    /** 联系人关系变化的进程内水位；进程重建后没有在途 RPC，因此无需落盘。 */
    private var contactProjectionGeneration = 0L
    /** 最近一次全量替换的代次，用于整体拒绝更早的并发快照。 */
    private var lastFullContactSnapshotGeneration = 0L
    /** 单联系人最近变化水位；删除也保留 tombstone，防止旧快照复活。 */
    private val contactMutationGenerations = mutableMapOf<String, Long>()
    /** 会话全量请求、实时投影和本地会话操作共享的进程内逻辑时钟。 */
    private var conversationProjectionGeneration = 0L
    /** 最近一次已开始应用的权威会话快照；用于整体拒绝乱序返回的旧请求。 */
    private var lastAppliedConversationSnapshotGeneration = 0L
    /** chat 级变化水位；删除也保留 tombstone，防止迟到快照复活。 */
    private val conversationMutationGenerations = mutableMapOf<String, Long>()
    /**
     * 持久化 outbox 的内存镜像。map 中“有 key + draft=null”表示明确清空；
     * 缺少 key 才表示本机没有待收敛操作，可接受跨设备草稿。
     */
    private val localDraftOverrides = mutableMapOf<String, LocalDraftOverride>()
    /** 已收敛后仍保留进程内高水位，避免迟到的旧 ACK 命中重新从 1 开始的新操作。 */
    private val draftGenerationHighWatermarks = mutableMapOf<String, Long>()
    private val closeLock = Any()
    private var closed = false

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
        localDraftOverrides.putAll(
            queries.selectAllConversationDraftOutbox().executeAsList().associate { row ->
                row.chat_id to LocalDraftOverride(
                    draft = row.draft,
                    generation = row.generation,
                    state = row.state,
                )
            },
        )
        localDraftOverrides.forEach { (chatId, override) ->
            draftGenerationHighWatermarks[chatId] = override.generation
        }

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
        synchronized(stateLock) {
            persistUser(user)
            usersFlow.value = mergeUser(usersFlow.value, user)
        }
    }

    // ── 联系人 ──
    override fun getContacts(): List<Contact> = synchronized(stateLock) {
        projectContacts(contactsFlow.value, usersFlow.value)
    }

    override fun observeContacts(): Flow<List<Contact>> = combine(contactsFlow, usersFlow) { contacts, users ->
        projectContacts(contacts, users)
    }

    private fun projectContacts(contacts: List<Contact>, users: List<User>): List<Contact> {
        val usersByUid = users.associateBy(User::uid)
        return contacts.map { contact ->
            val friendUser = usersByUid[contact.friendUid]
            if (friendUser != null && contact.user != friendUser) contact.copy(user = friendUser) else contact
        }
    }

    override fun upsertContact(contact: Contact) {
        synchronized(stateLock) {
            queries.transaction {
                persistContact(contact)
                contact.user?.let(::persistUser)
            }
            contact.user?.let { usersFlow.value = mergeUser(usersFlow.value, it) }
            contactsFlow.value = mergeContact(contactsFlow.value, contact)
            markContactMutated(contact.friendUid)
        }
    }
    override fun deleteContact(friendUid: String) {
        synchronized(stateLock) {
            queries.deleteContact(friendUid)
            contactsFlow.value = contactsFlow.value.filter { it.friendUid != friendUid }
            // 即使本地当前没有该行也必须记 tombstone：在途旧 RPC 可能仍携带它。
            markContactMutated(friendUid)
        }
    }

    override fun contactProjectionGeneration(): Long = synchronized(stateLock) {
        contactProjectionGeneration
    }

    override fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean {
        require(expectedGeneration >= 0L) { "expectedGeneration 不能为负数" }
        val snapshot = normalizeContacts(contacts)
        return synchronized(stateLock) {
            if (contactProjectionGeneration == expectedGeneration) {
                queries.transaction {
                    queries.deleteAllContacts()
                    snapshot.forEach { contact ->
                        persistContact(contact)
                        contact.user?.let(::persistUser)
                    }
                }
                usersFlow.value = mergeContactUsers(usersFlow.value, snapshot)
                contactsFlow.value = snapshot
                contactProjectionGeneration += 1L
                lastFullContactSnapshotGeneration = contactProjectionGeneration
                // lastFullContactSnapshotGeneration 已能拒绝所有更早请求；新事件从空 map 重新记录。
                contactMutationGenerations.clear()
                true
            } else {
                // 如果其他全量快照已经先收敛，该请求整体过期；否则按单联系人
                // 水位合并。这样既不会删掉刚 ACCEPTED 的人，也不会复活刚 DELETED 的人。
                val mergeable = if (expectedGeneration < lastFullContactSnapshotGeneration) {
                    emptyList()
                } else {
                    snapshot.filter { contact ->
                        (contactMutationGenerations[contact.friendUid] ?: 0L) <= expectedGeneration
                    }
                }
                if (mergeable.isNotEmpty()) {
                    queries.transaction {
                        mergeable.forEach { contact ->
                            persistContact(contact)
                            contact.user?.let(::persistUser)
                        }
                    }
                    usersFlow.value = mergeContactUsers(usersFlow.value, mergeable)
                    var mergedContacts = contactsFlow.value
                    mergeable.forEach { mergedContacts = mergeContact(mergedContacts, it) }
                    contactsFlow.value = mergedContacts
                    contactProjectionGeneration += 1L
                    val mergedGeneration = contactProjectionGeneration
                    mergeable.forEach { contactMutationGenerations[it.friendUid] = mergedGeneration }
                }
                false
            }
        }
    }

    private fun persistUser(user: User) {
        queries.upsertUser(
            user.uid,
            user.username,
            user.name,
            user.avatar,
            user.phone,
            user.sex.toLong(),
            user.role.toLong(),
            user.status.toLong(),
        )
    }

    private fun persistContact(contact: Contact) {
        queries.upsertContact(contact.uid, contact.friendUid, contact.remark, contact.status.toLong())
    }

    private fun mergeUser(current: List<User>, user: User): List<User> {
        val list = current.toMutableList()
        val index = list.indexOfFirst { it.uid == user.uid }
        if (index >= 0) list[index] = user else list.add(user)
        return list
    }

    private fun mergeContactUsers(current: List<User>, contacts: List<Contact>): List<User> {
        var merged = current
        contacts.mapNotNull(Contact::user).forEach { merged = mergeUser(merged, it) }
        return merged
    }

    private fun mergeContact(current: List<Contact>, contact: Contact): List<Contact> {
        val list = current.toMutableList()
        val index = list.indexOfFirst { it.friendUid == contact.friendUid }
        if (index >= 0) list[index] = contact else list.add(contact)
        return list
    }

    private fun normalizeContacts(contacts: List<Contact>): List<Contact> =
        contacts.associateBy(Contact::friendUid).values.toList()

    private fun markContactMutated(friendUid: String) {
        contactProjectionGeneration += 1L
        contactMutationGenerations[friendUid] = contactProjectionGeneration
    }

    // ── 聊天 ──
    override fun getChat(chatId: String): Chat? = chatsFlow.value.find { it.chatId == chatId }
    override fun upsertChat(chat: Chat) {
        synchronized(stateLock) {
            queries.upsertChat(chat.chatId, chat.chatType.toLong(), chat.name, chat.avatar, chat.creator, chat.memberCount.toLong(), chat.maxSeq, chat.notice, if (chat.mutedAll) 1L else 0L)
            val list = chatsFlow.value.toMutableList()
            val idx = list.indexOfFirst { it.chatId == chat.chatId }
            if (idx >= 0) list[idx] = chat else list.add(chat)
            chatsFlow.value = list
            // CHAT_CREATED 只先落 Chat，Conversation 由 READY 后的全量刷新补齐。
            // 因此 Chat 变化也必须保护同 chat 的旧会话不被在途快照误删。
            markConversationMutated(chat.chatId)
        }
    }
    override fun deleteChat(chatId: String) {
        synchronized(stateLock) {
            queries.deleteChat(chatId)
            chatsFlow.value = chatsFlow.value.filter { it.chatId != chatId }
            markConversationMutated(chatId)
        }
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

    override fun getRecentMessages(chatId: String?, afterSeq: Long, limit: Int): List<Message> {
        require(afterSeq >= 0L) { "afterSeq must be non-negative" }
        require(limit > 0) { "limit must be positive" }
        val rows = synchronized(stateLock) {
            if (chatId == null) {
                queries.selectRecentMessages(afterSeq, limit.toLong()).executeAsList()
            } else {
                queries.selectRecentMessagesByChat(chatId, afterSeq, limit.toLong()).executeAsList()
            }
        }
        return rows.asReversed().map { it.toModel() }
    }

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        getOrCreateWindow(chatId).messages

    override fun insertMessage(message: Message) {
        persistMessage(message)
        // 只更新已驻留的窗口；未驻留的 chat 下次 observe 时从 DB 加载
        chatWindows[message.chatId]?.upsert(message)
    }

    override fun insertMessagePage(
        chatId: String,
        messages: List<Message>,
        resetResidentWindow: Boolean,
    ) {
        messages.forEach { message ->
            require(message.chatId == chatId) { "history page contains another chat: ${message.chatId}" }
            persistMessage(message)
        }
        // Page provenance, not numeric adjacency, tells the window that gaps are authoritative.
        chatWindows[chatId]?.applyHistoryPage(messages, resetResidentWindow)
    }

    private fun persistMessage(message: Message) {
        val bodyBytes = message.body?.let { ProtoCodec.encode(it) }
        queries.insertMessage(message.chatId, message.clientMsgId, message.serverSeq, message.senderUid, message.messageType.toLong(), message.timestamp, message.flags.toLong(), bodyBytes, message.sendStatus.toLong())
    }

    override fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) {
        queries.updateMessageSeqStatus(serverSeq, chatId, clientMsgId)
        chatWindows[chatId]?.updateMessage(clientMsgId, serverSeq = serverSeq, sendStatus = Message.SEND_STATUS_SENT)
    }

    override fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) {
        queries.updateMessageSendStatus(sendStatus.toLong(), chatId, clientMsgId)
        chatWindows[chatId]?.updateMessage(clientMsgId, sendStatus = sendStatus)
    }

    override fun updateMessageInMemory(chatId: String, clientMsgId: String, transform: (Message) -> Message) {
        chatWindows[chatId]?.updateMessage(clientMsgId, transform = transform)
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
        synchronized(stateLock) {
            val plan = prepareConversationMerge(
                local = conversationsFlow.value.firstOrNull { it.chatId == conv.chatId },
                remote = conv,
                draftOverride = localDraftOverrides[conv.chatId],
            )
            queries.transaction {
                if (plan.clearDraftOverride) {
                    queries.deleteConversationDraftOutbox(conv.chatId)
                }
                // 持久化的也必须是合并后的草稿，否则进程重建会暂时
                // 读回迟到事件里的旧值。
                persistConversation(plan.conversation)
            }
            if (plan.clearDraftOverride) {
                localDraftOverrides.remove(conv.chatId)
            } else if (plan.draftOverride != null) {
                localDraftOverrides[conv.chatId] = plan.draftOverride
            }
            conversationsFlow.value = replaceSorted(conversationsFlow.value, plan.conversation)
            markConversationMutated(conv.chatId)
        }
    }

    override fun beginConversationSnapshot(): Long = synchronized(stateLock) {
        nextConversationGeneration()
    }

    override fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean {
        require(snapshotGeneration > 0L) { "snapshotGeneration must be positive" }
        val snapshot = conversations.associateBy(Conversation::chatId).values.toList()
        return synchronized(stateLock) {
            // 同一进程内两个全量请求可能乱序返回。更新的请求已经应用后，旧请求
            // 整体不能再触碰投影，即使期间没有 Notify。
            if (snapshotGeneration <= lastAppliedConversationSnapshotGeneration) {
                return@synchronized false
            }

            var projectedConversations = conversationsFlow.value
            val projectedOverrides = localDraftOverrides.toMutableMap()
            val mergePlans = mutableListOf<ConversationMergePlan>()
            // CHAT_CREATED 在下一次 list 前只有 Chat 投影，没有 Conversation 行；
            // 因此冲突判断不能只遍历 snapshot/current conversation 的交集。
            var hadConflict = conversationMutationGenerations.values.any { generation ->
                generation > snapshotGeneration
            }

            snapshot.forEach { remote ->
                if (wasConversationMutatedAfter(remote.chatId, snapshotGeneration)) {
                    // 新实时删除留下的 tombstone 会阻止旧快照复活；新实时 upsert
                    // 也不会被旧快照覆盖。
                    hadConflict = true
                } else {
                    val plan = prepareConversationMerge(
                        local = projectedConversations.firstOrNull { it.chatId == remote.chatId },
                        remote = remote,
                        draftOverride = projectedOverrides[remote.chatId],
                    )
                    mergePlans += plan
                    projectedConversations = replaceSorted(projectedConversations, plan.conversation)
                    if (plan.clearDraftOverride) {
                        projectedOverrides.remove(remote.chatId)
                    } else if (plan.draftOverride != null) {
                        projectedOverrides[remote.chatId] = plan.draftOverride
                    }
                }
            }

            val remoteIds = snapshot.mapTo(mutableSetOf(), Conversation::chatId)
            // outbox 可能因旧版本 bug 在 conversation 行已不存在时仍残留，所以清理
            // 候选必须同时覆盖当前投影与 override key。
            val absentIds = buildSet {
                projectedConversations.forEach { if (it.chatId !in remoteIds) add(it.chatId) }
                projectedOverrides.keys.forEach { if (it !in remoteIds) add(it) }
            }
            val removableIds = absentIds.filterTo(mutableSetOf()) { chatId ->
                val safeToRemove = !wasConversationMutatedAfter(chatId, snapshotGeneration)
                if (!safeToRemove) hadConflict = true
                safeToRemove
            }

            queries.transaction {
                mergePlans.forEach { plan ->
                    if (plan.clearDraftOverride) {
                        queries.deleteConversationDraftOutbox(plan.conversation.chatId)
                    }
                    persistConversation(plan.conversation)
                }
                removableIds.forEach { chatId ->
                    // 与 deleteConversation 保持同一持久化语义，避免被踢/解散后
                    // pending draft 在每次登录时永久重试。
                    queries.deleteConversationDraftOutbox(chatId)
                    queries.deleteConversation(chatId)
                }
            }

            removableIds.forEach { projectedOverrides.remove(it) }
            projectedConversations = projectedConversations.filterNot { it.chatId in removableIds }
            localDraftOverrides.clear()
            localDraftOverrides.putAll(projectedOverrides)
            conversationsFlow.value = sortConversations(projectedConversations)

            conversationProjectionGeneration = maxOf(
                conversationProjectionGeneration,
                snapshotGeneration,
            )
            lastAppliedConversationSnapshotGeneration = snapshotGeneration
            // 已被该快照覆盖的旧 mutation 不再需要；更新水位和删除 tombstone 均保留。
            conversationMutationGenerations.entries.removeAll { (_, generation) ->
                generation <= snapshotGeneration
            }
            !hadConflict
        }
    }

    private fun prepareConversationMerge(
        local: Conversation?,
        remote: Conversation,
        draftOverride: LocalDraftOverride?,
    ): ConversationMergePlan {
        // Conversation 事件可能比 setDraft RPC ACK 更早到达。只记录当前
        // generation 创建之后最后观察到的权威值，ACK 才有资格据此收敛 outbox。
        val observedOverride = draftOverride?.let { override ->
            if (override.state == DRAFT_MIRROR_PENDING) {
                override.copy(observedAuthority = AuthoritativeDraftObservation(remote.draft))
            } else {
                override
            }
        }
        // RPC 成功不等于本地已消费权威快照。在收到值匹配的
        // Conversation 前保留 override，防止更早的通知在应答后短暂复活草稿。
        val clearAcknowledgedOverride = observedOverride?.let { override ->
            override.state == DRAFT_MIRROR_ACKED && override.draft == remote.draft
        } == true
        val effectiveOverride = observedOverride.takeUnless { clearAcknowledgedOverride }
        val incoming = remote.copy(
            draft = if (effectiveOverride != null) effectiveOverride.draft else remote.draft,
        )
        val merged = if (local == null) incoming else {
            mergeConversation(local, incoming, effectiveOverride)
        }
        return ConversationMergePlan(
            conversation = merged,
            draftOverride = effectiveOverride,
            clearDraftOverride = clearAcknowledgedOverride,
        )
    }

    private fun persistConversation(conversation: Conversation) {
        queries.upsertConversation(
            conversation.chatId,
            conversation.chatType.toLong(),
            conversation.chatName,
            conversation.chatAvatar,
            conversation.lastSeq,
            conversation.readSeq,
            conversation.peerReadSeq,
            conversation.unreadCount.toLong(),
            if (conversation.isPinned) 1L else 0L,
            if (conversation.isMuted) 1L else 0L,
            conversation.draft,
            conversation.lastMsgTimestamp ?: 0L,
        )
    }

    /**
     * 本地与服务端 Conversation 合并策略。
     *
     * readSeq 服务端权威持久化（Commit 7f91d58 修复 markRead 不再 no-op + 会话行预创建），
     * unreadCount 与服务端使用同一 lastSeq - readSeq 模型；合并单调水位后重新计算，
     * 避免迟到事件覆盖更新摘要或复活/隐藏红点。
     *
     * 仍需本地合并的三项（纯客户端状态/水位线）：
     * - readSeq: 取 max（本地 markRead 可能比服务端通知先到，水位线只增不减）
     * - peerReadSeq: 取 max（同理）
     * - draft: 仅在 outbox 有明确本地操作时本地优先；否则接受远端（包括 null）
     */
    private fun mergeConversation(
        local: Conversation,
        remote: Conversation,
        draftOverride: LocalDraftOverride? = localDraftOverrides[remote.chatId],
    ): Conversation {
        val mergedReadSeq = maxOf(local.readSeq, remote.readSeq)
        val latestMessage = if (remote.lastSeq >= local.lastSeq) remote else local
        return remote.copy(
            lastMessage = latestMessage.lastMessage,
            lastMessageType = latestMessage.lastMessageType,
            lastMsgTimestamp = latestMessage.lastMsgTimestamp,
            lastSeq = latestMessage.lastSeq,
            readSeq = mergedReadSeq,
            // A CONVERSATION_UPDATED event produced before our markRead request can arrive late.
            // Once the local watermark already covers that event's latest message, accepting its
            // stale unread count would resurrect a badge that the user just cleared.
            unreadCount = (latestMessage.lastSeq - mergedReadSeq)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt(),
            peerReadSeq = maxOf(local.peerReadSeq, remote.peerReadSeq),
            draft = if (draftOverride != null) draftOverride.draft else remote.draft,
        )
    }

    override fun deleteConversation(chatId: String) {
        synchronized(stateLock) {
            queries.transaction {
                queries.deleteConversationDraftOutbox(chatId)
                queries.deleteConversation(chatId)
            }
            localDraftOverrides.remove(chatId)
            conversationsFlow.value = conversationsFlow.value.filter { it.chatId != chatId }
            // 即使本地没有行也记录 tombstone：在途旧快照可能仍携带该会话。
            markConversationMutated(chatId)
        }
    }

    override fun markConversationRead(chatId: String, readSeq: Long) {
        synchronized(stateLock) {
            queries.markConversationRead(readSeq, chatId)
            var changed = false
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId != chatId) return@map it
                val mergedReadSeq = maxOf(it.readSeq, readSeq)
                changed = changed || mergedReadSeq != it.readSeq || it.unreadCount !=
                    (it.lastSeq - mergedReadSeq).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                it.copy(
                    unreadCount = (it.lastSeq - mergedReadSeq)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt(),
                    readSeq = mergedReadSeq,
                )
            }
            if (changed) markConversationMutated(chatId)
        }
    }

    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) {
        synchronized(stateLock) {
            val existing = conversationsFlow.value.firstOrNull { it.chatId == chatId } ?: return
            val mergedPeerReadSeq = maxOf(existing.peerReadSeq, peerReadSeq)
            if (mergedPeerReadSeq == existing.peerReadSeq) return
            queries.updatePeerReadSeq(mergedPeerReadSeq, chatId)
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId == chatId) it.copy(peerReadSeq = mergedPeerReadSeq) else it
            }
            markConversationMutated(chatId)
        }
    }

    override fun getSyncCursor(key: String): Long = synchronized(stateLock) {
        queries.getSyncCursor(key).executeAsOneOrNull() ?: 0L
    }

    override fun advanceSyncCursor(key: String, eventId: Long): Long {
        require(eventId > 0L) { "eventId must be positive" }
        return synchronized(stateLock) {
            var persisted = 0L
            queries.transaction {
                queries.ensureSyncCursor(key)
                queries.advanceSyncCursor(value = eventId, key = key)
                persisted = queries.getSyncCursor(key).executeAsOne()
            }
            persisted
        }
    }

    override fun resetServerProjection() {
        synchronized(stateLock) {
            queries.transaction {
                queries.deleteAllBotMessages()
                queries.deleteAllConversationDraftOutbox()
                queries.deleteAllSyncCursors()
                queries.deleteAllMessages()
                queries.deleteAllMembers()
                queries.deleteAllConversations()
                queries.deleteAllContacts()
                queries.deleteAllChats()
                queries.deleteAllUsers()
            }

            usersFlow.value = emptyList()
            contactsFlow.value = emptyList()
            chatsFlow.value = emptyList()
            membersFlow.value = emptyMap()
            conversationsFlow.value = emptyList()
            localDraftOverrides.clear()

            check(contactProjectionGeneration < Long.MAX_VALUE) {
                "contact projection generation exhausted"
            }
            contactProjectionGeneration += 1L
            lastFullContactSnapshotGeneration = contactProjectionGeneration
            contactMutationGenerations.clear()

            val resetGeneration = nextConversationGeneration()
            lastAppliedConversationSnapshotGeneration = resetGeneration
            conversationMutationGenerations.clear()

            // Keep existing windows attached: open screens first observe empty, then receive the
            // same connection's replay inserts. Removing the map entries would strand collectors.
            synchronized(chatLock) {
                chatWindows.values.forEach(MessageWindow::resetServerProjection)
            }
            // Keep draftGenerationHighWatermarks as stale-ACK fences. The outbox/overrides are
            // gone, while the next local edit must not reuse a generation still in flight.
        }
    }

    override fun enqueueBotMessage(eventId: Long, message: Message) {
        require(eventId > 0L) { "eventId must be positive" }
        require(message.serverSeq > 0L) { "durable bot messages require a positive serverSeq" }
        synchronized(stateLock) {
            queries.enqueueBotMessage(
                eventId,
                message.chatId,
                message.serverSeq,
                ProtoCodec.encode(message),
            )
        }
    }

    override fun peekBotMessage(): PendingBotMessage? = synchronized(stateLock) {
        queries.peekBotMessage().executeAsOneOrNull()?.let { row ->
            PendingBotMessage(
                eventId = row.event_id,
                message = ProtoCodec.decode(Message, row.payload),
            )
        }
    }

    override fun deleteBotMessage(eventId: Long) {
        require(eventId > 0L) { "eventId must be positive" }
        synchronized(stateLock) {
            queries.deleteBotMessage(eventId)
        }
    }

    override fun setConversationDraft(chatId: String, draft: String?): Long {
        return synchronized(stateLock) {
            val generation = (draftGenerationHighWatermarks[chatId] ?: 0L) + 1L
            draftGenerationHighWatermarks[chatId] = generation
            val override = LocalDraftOverride(draft, generation, DRAFT_MIRROR_PENDING)
            queries.transaction {
                queries.upsertConversationDraftOutbox(chatId, draft, generation, DRAFT_MIRROR_PENDING)
                queries.setConversationDraft(draft, chatId)
            }
            localDraftOverrides[chatId] = override
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId == chatId) it.copy(draft = draft) else it
            }
            markConversationMutated(chatId)
            generation
        }
    }

    override fun getPendingConversationDrafts(): List<PendingConversationDraft> =
        synchronized(stateLock) {
            localDraftOverrides.mapNotNull { (chatId, override) ->
                if (override.state == DRAFT_MIRROR_PENDING) {
                    PendingConversationDraft(chatId, override.draft, override.generation)
                } else {
                    null
                }
            }
        }

    override fun markConversationDraftMirrored(chatId: String, generation: Long) {
        synchronized(stateLock) {
            val current = localDraftOverrides[chatId] ?: return
            if (current.generation != generation || current.state != DRAFT_MIRROR_PENDING) return
            val matchingAuthorityAlreadyObserved =
                current.observedAuthority?.draft == current.draft && current.observedAuthority != null
            queries.transaction {
                if (matchingAuthorityAlreadyObserved) {
                    // stateLock 下 generation 仍匹配，因此这里只可能删除本次 ACK
                    // 对应的行；新 generation 无法在事务中途插入被误清。
                    queries.deleteConversationDraftOutbox(chatId)
                } else {
                    queries.markConversationDraftOutboxAcked(chatId, generation)
                }
            }
            if (matchingAuthorityAlreadyObserved) {
                localDraftOverrides.remove(chatId)
            } else {
                localDraftOverrides[chatId] = current.copy(state = DRAFT_MIRROR_ACKED)
            }
        }
    }

    override fun close() {
        val shouldClose = synchronized(closeLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) driver.close()
    }

    /** 在已排序列表中替换一条已完成合并的会话。调用方持 stateLock。 */
    private fun replaceSorted(
        current: List<Conversation>,
        conv: Conversation,
    ): List<Conversation> {
        val list = current.toMutableList()
        val idx = list.indexOfFirst { it.chatId == conv.chatId }
        if (idx >= 0) list[idx] = conv else list.add(conv)
        return sortConversations(list)
    }

    private fun sortConversations(conversations: List<Conversation>): List<Conversation> =
        conversations.sortedWith(
            compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.lastMsgTimestamp ?: 0L },
        )

    /** 调用方必须持有 stateLock。 */
    private fun markConversationMutated(chatId: String) {
        conversationMutationGenerations[chatId] = nextConversationGeneration()
    }

    /** 调用方必须持有 stateLock。 */
    private fun nextConversationGeneration(): Long {
        check(conversationProjectionGeneration < Long.MAX_VALUE) {
            "conversation projection generation exhausted"
        }
        conversationProjectionGeneration += 1L
        return conversationProjectionGeneration
    }

    /** 调用方必须持有 stateLock。 */
    private fun wasConversationMutatedAfter(chatId: String, generation: Long): Boolean =
        (conversationMutationGenerations[chatId] ?: 0L) > generation

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
            val msgType = requireNotNull(MessageType.fromCode(message_type.toInt())) {
                "Unknown cached message type: $message_type"
            }
            val byteBuf = Unpooled.wrappedBuffer(bodyBytes)
            try {
                val buf = PacketBuffer(byteBuf)
                val decoded = requireNotNull(MessageBodyRegistry.decode(msgType, buf)) {
                    "Message type $msgType has no body reader"
                }
                require(buf.readableBytes() == 0) { "Cached message body has trailing bytes" }
                decoded
            } finally {
                byteBuf.release()
            }
        } catch (e: Exception) {
            val failure = IllegalStateException(
                "Corrupt cached message body chatId=$chat_id msgId=$client_msg_id type=$message_type",
                e,
            )
            com.virjar.tk.util.AppLog.fault("LocalCache", failure.message ?: "Corrupt cached message body", failure)
            throw failure
        }
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
    peerReadSeq = peer_read_seq ?: 0L,
    unreadCount = unread_count?.toInt() ?: 0, isPinned = is_pinned == 1L,
    isMuted = is_muted == 1L, draft = draft, lastMsgTimestamp = last_msg_timestamp,
)
