package com.virjar.tk.client

import app.cash.sqldelight.db.SqlDriver
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.body.MessageBodyRegistry
import com.virjar.tk.model.*
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.MessageAckPayload
import io.netty.buffer.Unpooled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

private const val DRAFT_MIRROR_PENDING = 0L
private const val DRAFT_MIRROR_ACKED = 1L
internal const val DEFAULT_OUTGOING_SUCCESS_RECEIPTS = 512

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

private data class MessageHistoryState(
    var lifecycleGeneration: Long = 0L,
    /** Monotonic allocator; pending chains are never reused after failure/cancellation. */
    var historyChainGeneration: Long = 0L,
    var committedHistoryChainGeneration: Long = 0L,
    var pendingNewestChainGeneration: Long = 0L,
    var newestRequestGeneration: Long = 0L,
    var olderRequestGeneration: Long = 0L,
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
class LocalCacheImpl(
    private val driver: SqlDriver,
    private val successReceiptLimit: Int = DEFAULT_OUTGOING_SUCCESS_RECEIPTS,
) : LocalCache {
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
    private val cacheUseGate = CacheUseGate()

    /** In-memory request fences are sufficient: no RPC can survive a process restart. */
    private val messageHistoryOwner = Any()
    private var messageHistoryGlobalGeneration = 0L
    private var messageHistoryRequestGeneration = 0L
    private val messageHistoryStates = mutableMapOf<String, MessageHistoryState>()
    // ── 消息窗口（LRU 管理） ──
    // 每个 active chat 对应一个 MessageWindow，持有最近 N 条消息的内存副本
    private val chatWindows = ConcurrentHashMap<String, MessageWindow>()

    // LRU 跟踪：access-order LinkedHashMap，记录最后访问时间戳
    // synchronized(chatLock) 保护 chatWindows 和 chatLru 的复合操作
    private val chatLru = LinkedHashMap<String, Long>(LocalCache.MAX_ACTIVE_CHATS, 0.75f, true)
    private val chatLock = Any()
    /** JVM concurrency tests pause exactly after the initial SQL snapshot and before publication. */
    internal var windowSnapshotLoadedHookForTest: (() -> Unit)? = null

    init {
        require(successReceiptLimit > 0) { "successReceiptLimit must be positive" }
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
    override fun getUser(uid: String): User? = cacheUseGate.use {
        usersFlow.value.find { it.uid == uid }
    }
    override fun upsertUser(user: User) = cacheUseGate.use {
        synchronized(stateLock) {
            persistUser(user)
            usersFlow.value = mergeUser(usersFlow.value, user)
        }
    }

    // ── 联系人 ──
    override fun getContacts(): List<Contact> = cacheUseGate.use {
        synchronized(stateLock) { projectContacts(contactsFlow.value, usersFlow.value) }
    }

    override fun observeContacts(): Flow<List<Contact>> = cacheUseGate.use {
        combine(contactsFlow, usersFlow) { contacts, users -> projectContacts(contacts, users) }
    }

    private fun projectContacts(contacts: List<Contact>, users: List<User>): List<Contact> {
        val usersByUid = users.associateBy(User::uid)
        return contacts.map { contact ->
            val friendUser = usersByUid[contact.friendUid]
            if (friendUser != null && contact.user != friendUser) contact.copy(user = friendUser) else contact
        }
    }

    override fun upsertContact(contact: Contact) = cacheUseGate.use {
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
    override fun deleteContact(friendUid: String) = cacheUseGate.use {
        synchronized(stateLock) {
            queries.deleteContact(friendUid)
            contactsFlow.value = contactsFlow.value.filter { it.friendUid != friendUid }
            // 即使本地当前没有该行也必须记 tombstone：在途旧 RPC 可能仍携带它。
            markContactMutated(friendUid)
        }
    }

    override fun contactProjectionGeneration(): Long = cacheUseGate.use {
        synchronized(stateLock) { contactProjectionGeneration }
    }

    override fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean = cacheUseGate.use {
        require(expectedGeneration >= 0L) { "expectedGeneration 不能为负数" }
        val snapshot = normalizeContacts(contacts)
        synchronized(stateLock) {
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
    override fun getChat(chatId: String): Chat? = cacheUseGate.use {
        chatsFlow.value.find { it.chatId == chatId }
    }
    override fun upsertChat(chat: Chat) = cacheUseGate.use {
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
        cacheUseGate.use {
            synchronized(stateLock) {
                queries.transaction {
                    queries.deleteConversationDraftOutbox(chatId)
                    queries.deleteBotMessagesByChat(chatId)
                    queries.deleteOutgoingMessagesByChat(chatId)
                    queries.deleteMessagesByChat(chatId)
                    queries.deleteMembersByChat(chatId)
                    queries.deleteConversation(chatId)
                    queries.deleteChat(chatId)
                }
                invalidateMessageHistoryForChat(chatId)
                localDraftOverrides.remove(chatId)
                chatsFlow.value = chatsFlow.value.filter { it.chatId != chatId }
                conversationsFlow.value = conversationsFlow.value.filter { it.chatId != chatId }
                membersFlow.value = membersFlow.value - chatId
                // Even an already-applied tombstone must advance the in-process fence so an older
                // listConversations response cannot resurrect the deleted chat projection.
                markConversationMutated(chatId)

                // Keep the resident window object registered. Existing collectors must observe an
                // immediate empty projection and later replay inserts through this same Flow.
                synchronized(chatLock) {
                    chatWindows[chatId]?.resetServerProjection()
                }
                // Keep draftGenerationHighWatermarks as a stale-ACK fence, just like a full reset.
            }
        }
    }
    // ── 成员 ──
    override fun getMembers(chatId: String): List<Member> = cacheUseGate.use {
        membersFlow.value[chatId] ?: emptyList()
    }
    override fun observeMembers(chatId: String): Flow<List<Member>> = cacheUseGate.use {
        membersFlow.map { it[chatId] ?: emptyList() }
    }
    override fun upsertMember(member: Member) = cacheUseGate.use {
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
    override fun removeMember(chatId: String, uid: String) = cacheUseGate.use {
        queries.removeMember(chatId, uid)
        synchronized(stateLock) {
            val current = membersFlow.value.toMutableMap()
            current[chatId] = (current[chatId] ?: emptyList()).filter { it.uid != uid }
            membersFlow.value = current
        }
    }

    // ── 消息（LRU 窗口 + 持久化） ──

    override fun getMessages(chatId: String, limit: Int): List<Message> = cacheUseGate.use {
        getOrCreateWindow(chatId).snapshot(limit)
    }

    override fun observeMessages(chatId: String): Flow<List<Message>> = cacheUseGate.use {
        getOrCreateWindow(chatId).messages
    }

    override fun insertMessage(message: Message) {
        cacheUseGate.use {
            val projection = message.asAuthoritativeProjection()
            synchronized(stateLock) {
                queries.transaction {
                    persistMessage(projection)
                    promoteOutgoingFromAuthoritativeProjection(projection, System.currentTimeMillis())
                }
            }
            // 只更新已驻留的窗口；未驻留的 chat 下次 observe 时从 DB 加载
            chatWindows[projection.chatId]?.upsert(projection)
        }
    }

    override fun enqueueOutgoingMessage(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage = cacheUseGate.use {
        val canonical = canonicalizeOutboundMessage(message)
        require(canonical.serverSeq == 0L) { "Only unacknowledged messages can enter the outbox" }
        require(requestFingerprint == null || requestFingerprint.isNotEmpty()) {
            "requestFingerprint must not be empty"
        }
        val payload = ProtoCodec.encode(canonical)
        synchronized(stateLock) {
            lateinit var persisted: com.virjar.tk.database.Outgoing_message
            var projection: Message? = null
            queries.transaction {
                val existing = queries.selectOutgoingMessageById(
                    canonical.chatId,
                    canonical.clientMsgId,
                ).executeAsOneOrNull()
                if (existing == null) {
                    val authoritative = queries.selectMessageById(
                        canonical.chatId,
                        canonical.clientMsgId,
                    ).executeAsOneOrNull()
                    if ((authoritative?.server_seq ?: 0L) > 0L) {
                        throw OutgoingMessageConflictException(
                            "clientMsgId already belongs to an authoritative server message",
                        )
                    }
                    queries.enqueueOutgoingMessage(
                        canonical.clientMsgId,
                        canonical.chatId,
                        payload,
                        requestFingerprint,
                        now,
                        now,
                    )
                } else {
                    existing.requireSameOutgoingRequest(payload, requestFingerprint)
                }
                persisted = queries.selectOutgoingMessageById(
                    canonical.chatId,
                    canonical.clientMsgId,
                ).executeAsOne()
                val existingProjection = queries.selectMessageById(
                    canonical.chatId,
                    canonical.clientMsgId,
                ).executeAsOneOrNull()
                if (
                    persisted.state != OutgoingMessageState.SUCCESS.code &&
                    (existingProjection?.server_seq ?: 0L) == 0L
                ) {
                    projection = persisted.toProjectionMessage().also(::persistMessage)
                }
            }
            projection?.let { chatWindows[canonical.chatId]?.upsert(it) }
            persisted.toModel()
        }
    }

    override fun getOutgoingMessage(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray?,
    ): OutgoingMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            queries.selectOutgoingMessageById(chatId, clientMsgId).executeAsOneOrNull()?.also { row ->
                if (requestFingerprint != null) row.requireRequestFingerprint(requestFingerprint)
            }?.toModel()
        }
    }

    override fun recoverOutgoingMessages(now: Long): List<OutgoingMessage> = cacheUseGate.use {
        synchronized(stateLock) {
            lateinit var recovered: List<com.virjar.tk.database.Outgoing_message>
            var orphaned = emptyList<com.virjar.tk.database.Message>()
            val repairedProjection = mutableListOf<Message>()
            val reconciledAuthority = mutableListOf<Message>()
            queries.transaction {
                orphaned = queries.selectOrphanedLocalMessages().executeAsList()
                queries.failOrphanedLocalMessages()
                queries.recoverInFlightOutgoingMessages(now)
                recovered = queries.selectAllOutgoingMessages().executeAsList()
                recovered.forEach { row ->
                    val authority = queries.selectMessageById(row.chat_id, row.client_msg_id).executeAsOneOrNull()
                    if (authority != null && (authority.server_seq ?: 0L) > 0L) {
                        val projection = authority.toModel().asAuthoritativeProjection()
                        promoteOutgoingFromAuthoritativeProjection(projection, now)
                        reconciledAuthority += projection
                    }
                }
                queries.pruneOutgoingSuccessReceipts(successReceiptLimit.toLong())
                recovered = queries.selectAllOutgoingMessages().executeAsList()
                recovered.filter { it.state != OutgoingMessageState.SUCCESS.code }.forEach { row ->
                    val existing = queries.selectMessageById(row.chat_id, row.client_msg_id).executeAsOneOrNull()
                    if ((existing?.server_seq ?: 0L) == 0L) {
                        repairedProjection += row.toProjectionMessage().also(::persistMessage)
                    }
                }
            }
            orphaned.forEach { row ->
                updateResidentOptimisticMessage(
                    chatId = row.chat_id,
                    clientMsgId = row.client_msg_id,
                    sendStatus = Message.SEND_STATUS_FAILED,
                )
            }
            reconciledAuthority.forEach { message -> chatWindows[message.chatId]?.upsert(message) }
            repairedProjection.forEach { message -> chatWindows[message.chatId]?.upsert(message) }
            recovered.map { it.toModel() }
        }
    }

    /** Caller holds [stateLock] and an enclosing transaction. */
    private fun promoteOutgoingFromAuthoritativeProjection(message: Message, now: Long) {
        if (message.serverSeq <= 0L) return
        // Server projections are authoritative regardless of whether a matching local receipt
        // remains. This also repairs rows written by an interrupted intermediate epoch-3 build.
        queries.markAuthoritativeMessageSent(message.chatId, message.clientMsgId)
        val row = queries.selectOutgoingMessageById(message.chatId, message.clientMsgId)
            .executeAsOneOrNull() ?: return
        if (row.state == OutgoingMessageState.SUCCESS.code) return
        val original = ProtoCodec.decode(Message, row.payload)
        if (original.senderUid != message.senderUid) return
        val completedAt = nextOutgoingCompletionTime(now)
        queries.promoteOutgoingMessageSucceededFromProjection(
            message.serverSeq,
            now,
            completedAt,
            row.local_ordinal,
        )
        queries.pruneOutgoingSuccessReceipts(successReceiptLimit.toLong())
    }

    /** Caller holds [stateLock] and a transaction. Durable MAX survives restart and clock rollback. */
    private fun nextOutgoingCompletionTime(now: Long): Long {
        val previous = queries.selectMaxOutgoingCompletedAt().executeAsOne().max_completed_at
        return if (previous == null || now > previous) {
            now
        } else {
            check(previous < Long.MAX_VALUE) { "outgoing completion clock exhausted" }
            previous + 1L
        }
    }

    override fun peekNextOutgoingMessage(): OutgoingMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            queries.selectNextActiveOutgoingMessage().executeAsOneOrNull()?.toModel()
        }
    }

    override fun claimNextOutgoingMessage(now: Long): OutgoingMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            var claimed: com.virjar.tk.database.Outgoing_message? = null
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
            }?.toModel()
        }
    }

    override fun markOutgoingMessageRetry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
    ) {
        cacheUseGate.use {
            synchronized(stateLock) {
                var changed: com.virjar.tk.database.Outgoing_message? = null
                queries.transaction {
                    val row = queries.selectOutgoingMessageByOrdinal(localOrdinal).executeAsOneOrNull()
                    if (row == null || row.state != OutgoingMessageState.IN_FLIGHT.code) return@transaction
                    queries.markOutgoingMessageRetry(error, nextAttemptAt, now, localOrdinal)
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

    override fun markOutgoingMessageTerminalFailed(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int?,
    ) {
        cacheUseGate.use {
            synchronized(stateLock) {
                var changed: com.virjar.tk.database.Outgoing_message? = null
                queries.transaction {
                    val row = queries.selectOutgoingMessageByOrdinal(localOrdinal).executeAsOneOrNull()
                    if (row == null || row.state != OutgoingMessageState.IN_FLIGHT.code) return@transaction
                    queries.markOutgoingMessageTerminalFailed(
                        error,
                        terminalCode?.toLong(),
                        now,
                        nextOutgoingCompletionTime(now),
                        localOrdinal,
                    )
                    queries.updateMessageSendStatus(
                        Message.SEND_STATUS_FAILED.toLong(),
                        row.chat_id,
                        row.client_msg_id,
                    )
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

    override fun completeOutgoingMessage(localOrdinal: Long, ack: MessageAckPayload, now: Long) {
        cacheUseGate.use {
            require(ack.code == 0) { "Only successful ACKs complete an outgoing message" }
            require(ack.serverSeq > 0L) { "Successful ACK must carry a positive serverSeq" }
            synchronized(stateLock) {
                var completed: com.virjar.tk.database.Outgoing_message? = null
                queries.transaction {
                    val row = queries.selectOutgoingMessageByOrdinal(localOrdinal).executeAsOneOrNull()
                    if (row == null || row.state != OutgoingMessageState.IN_FLIGHT.code) return@transaction
                    require(ack.clientMsgId == row.client_msg_id) { "ACK belongs to another outgoing message" }
                    queries.updateMessageSeqStatus(ack.serverSeq, row.chat_id, row.client_msg_id)
                    queries.markOutgoingMessageSucceeded(
                        ack.serverSeq,
                        now,
                        nextOutgoingCompletionTime(now),
                        localOrdinal,
                    )
                    queries.pruneOutgoingSuccessReceipts(successReceiptLimit.toLong())
                    completed = row
                }
                completed?.let { row ->
                    updateResidentOptimisticMessage(
                        chatId = row.chat_id,
                        clientMsgId = row.client_msg_id,
                        serverSeq = ack.serverSeq,
                        sendStatus = Message.SEND_STATUS_SENT,
                    )
                }
            }
        }
    }

    override fun cancelOutgoingMessages(reason: String, now: Long) = cacheUseGate.use {
        synchronized(stateLock) {
            var active = emptyList<com.virjar.tk.database.Outgoing_message>()
            queries.transaction {
                active = queries.selectAllOutgoingMessages().executeAsList().filter {
                    it.state == OutgoingMessageState.PENDING.code ||
                        it.state == OutgoingMessageState.IN_FLIGHT.code ||
                        it.state == OutgoingMessageState.RETRY_WAIT.code
                }
                if (active.isNotEmpty()) {
                    queries.cancelActiveOutgoingMessages(reason, now, nextOutgoingCompletionTime(now))
                }
                active.forEach { row ->
                    queries.updateMessageSendStatus(
                        Message.SEND_STATUS_FAILED.toLong(),
                        row.chat_id,
                        row.client_msg_id,
                    )
                }
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

    override fun beginMessageHistoryLease(
        chatId: String,
        resetResidentWindow: Boolean,
    ): MessageHistoryLease = cacheUseGate.use {
        synchronized(stateLock) {
            val state = messageHistoryStates.getOrPut(chatId, ::MessageHistoryState)
            val requestGeneration = nextMessageHistoryRequestGeneration()
            if (resetResidentWindow) {
                state.historyChainGeneration = nextGeneration(
                    state.historyChainGeneration,
                    "message history chain generation",
                )
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
        synchronized(stateLock) state@{
            val state = currentMessageHistoryState(lease) ?: return@state false
            val page = messages.map(Message::asAuthoritativeProjection)

            // Fixed order: CacheUseGate -> stateLock -> chatLock -> SQLite -> window lock.
            synchronized(chatLock) {
                queries.transaction {
                    page.forEach { message ->
                        require(message.chatId == lease.chatId) {
                            "history page contains another chat: ${message.chatId}"
                        }
                        persistMessage(message)
                        promoteOutgoingFromAuthoritativeProjection(message, System.currentTimeMillis())
                    }
                }
                // Page provenance, not numeric adjacency, tells the window that gaps are authoritative.
                chatWindows[lease.chatId]?.applyHistoryPage(page, lease.resetResidentWindow)
            }

            if (lease.resetResidentWindow) {
                state.committedHistoryChainGeneration = lease.historyChainGeneration
                state.pendingNewestChainGeneration = 0L
                state.newestRequestGeneration = 0L
                // Any older request was bound to the previous committed anchor. If it did not win the
                // stateLock before this commit it must not append across the reset.
                state.olderRequestGeneration = 0L
            } else {
                state.olderRequestGeneration = 0L
            }
            true
        }
    }

    override fun abandonMessageHistoryLease(lease: MessageHistoryLease): Boolean =
        cacheUseGate.runIfOpen {
            synchronized(stateLock) state@{
                val state = messageHistoryStateForLeaseLifecycle(lease) ?: return@state false
                if (lease.resetResidentWindow) {
                    if (state.newestRequestGeneration != lease.requestGeneration ||
                        state.pendingNewestChainGeneration != lease.historyChainGeneration
                    ) {
                        return@state false
                    }
                    state.newestRequestGeneration = 0L
                    state.pendingNewestChainGeneration = 0L
                } else {
                    if (state.olderRequestGeneration != lease.requestGeneration ||
                        state.committedHistoryChainGeneration != lease.historyChainGeneration
                    ) {
                        return@state false
                    }
                    state.olderRequestGeneration = 0L
                }
                true
            }
        }

    private fun persistMessage(message: Message) {
        if (message.serverSeq == 0L) {
            val existing = queries.selectMessageById(message.chatId, message.clientMsgId).executeAsOneOrNull()
            if ((existing?.server_seq ?: 0L) > 0L) {
                throw OutgoingMessageConflictException(
                    "local message cannot replace an authoritative server projection",
                )
            }
        }
        val bodyBytes = message.body?.let { ProtoCodec.encode(it) }
        queries.insertMessage(message.chatId, message.clientMsgId, message.serverSeq, message.senderUid, message.messageType.toLong(), message.timestamp, message.flags.toLong(), bodyBytes, message.sendStatus.toLong())
    }

    override fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) {
        cacheUseGate.use {
            synchronized(stateLock) {
                queries.updateMessageSeqStatus(serverSeq, chatId, clientMsgId)
                updateResidentOptimisticMessage(
                    chatId = chatId,
                    clientMsgId = clientMsgId,
                    serverSeq = serverSeq,
                    sendStatus = Message.SEND_STATUS_SENT,
                )
            }
        }
    }

    override fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) {
        cacheUseGate.use {
            synchronized(stateLock) {
                queries.updateMessageSendStatus(sendStatus.toLong(), chatId, clientMsgId)
                updateResidentOptimisticMessage(chatId, clientMsgId, sendStatus = sendStatus)
            }
        }
    }

    override fun updateMessageInMemory(
        chatId: String,
        clientMsgId: String,
        transform: (Message) -> Message,
    ) {
        cacheUseGate.use {
            chatWindows[chatId]?.updateMessage(clientMsgId, transform = transform)
        }
    }

    /** Outbox callbacks must never mutate a server-owned same-id row in a resident window. */
    private fun updateResidentOptimisticMessage(
        chatId: String,
        clientMsgId: String,
        serverSeq: Long? = null,
        sendStatus: Int? = null,
    ) {
        chatWindows[chatId]?.updateMessage(clientMsgId, transform = {
            if (this.serverSeq > 0L) this else copy(
                serverSeq = serverSeq ?: this.serverSeq,
                sendStatus = sendStatus ?: this.sendStatus,
            )
        })
    }

    // ── Phase C：内存治理 API ──

    override fun pager(chatId: String, windowSize: Int): MessagePager = cacheUseGate.use {
        getOrCreateWindow(chatId, windowSize)
    }

    override fun onChatInactive(chatId: String) {
        cacheUseGate.use {
            synchronized(chatLock) {
                chatWindows.remove(chatId)
                chatLru.remove(chatId)
            }
        }
    }

    /**
     * 获取或创建某聊天的消息窗口。
     * 触发 LRU 更新；超过 [LocalCache.MAX_ACTIVE_CHATS] 时 evict 最旧窗口。
     */
    private fun getOrCreateWindow(chatId: String, windowSize: Int = LocalCache.DEFAULT_MESSAGE_WINDOW): MessageWindow {
        // Fixed order: stateLock -> chatLock -> SQLite snapshot -> map publication. Every durable
        // message writer takes stateLock first, so a row is either in this snapshot or its writer
        // observes the registered window and publishes the same fact after commit.
        synchronized(stateLock) {
            synchronized(chatLock) {
                chatLru[chatId] = System.currentTimeMillis()  // access-order 更新
                val existing = chatWindows[chatId]
                if (existing != null) {
                    evictIfOverCapacity()
                    return existing
                }
                val window = MessageWindow(chatId, queries, cacheUseGate, windowSize) { it.toModel() }
                windowSnapshotLoadedHookForTest?.invoke()
                chatWindows[chatId] = window
                evictIfOverCapacity()
                return window
            }
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
    override fun getConversations(): List<Conversation> = cacheUseGate.use {
        conversationsFlow.value
    }
    override fun observeConversations(): Flow<List<Conversation>> = cacheUseGate.use {
        conversationsFlow
    }
    override fun upsertConversation(conv: Conversation) = cacheUseGate.use {
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

    override fun beginConversationSnapshot(): Long = cacheUseGate.use {
        synchronized(stateLock) { nextConversationGeneration() }
    }

    override fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean = cacheUseGate.use {
        require(snapshotGeneration > 0L) { "snapshotGeneration must be positive" }
        val snapshot = conversations.associateBy(Conversation::chatId).values.toList()
        synchronized(stateLock) {
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

    override fun deleteConversation(chatId: String) = cacheUseGate.use {
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

    override fun markConversationRead(chatId: String, readSeq: Long) = cacheUseGate.use {
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

    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) = cacheUseGate.use {
        synchronized(stateLock) state@{
            val existing = conversationsFlow.value.firstOrNull { it.chatId == chatId } ?: return@state
            val mergedPeerReadSeq = maxOf(existing.peerReadSeq, peerReadSeq)
            if (mergedPeerReadSeq == existing.peerReadSeq) return@state
            queries.updatePeerReadSeq(mergedPeerReadSeq, chatId)
            conversationsFlow.value = conversationsFlow.value.map {
                if (it.chatId == chatId) it.copy(peerReadSeq = mergedPeerReadSeq) else it
            }
            markConversationMutated(chatId)
        }
    }

    override fun getSyncCursor(key: String): Long = cacheUseGate.use {
        synchronized(stateLock) { queries.getSyncCursor(key).executeAsOneOrNull() ?: 0L }
    }

    override fun advanceSyncCursor(key: String, eventId: Long): Long = cacheUseGate.use {
        require(eventId > 0L) { "eventId must be positive" }
        synchronized(stateLock) {
            var persisted = 0L
            queries.transaction {
                queries.ensureSyncCursor(key)
                queries.advanceSyncCursor(value = eventId, key = key)
                persisted = queries.getSyncCursor(key).executeAsOne()
            }
            persisted
        }
    }

    override fun resetServerProjection() = cacheUseGate.use {
        synchronized(stateLock) {
            var outgoingProjection = emptyList<Message>()
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
                outgoingProjection = queries.selectAllOutgoingMessages().executeAsList()
                    .filter { it.state != OutgoingMessageState.SUCCESS.code }
                    .map { row -> row.toProjectionMessage().also(::persistMessage) }
            }

            messageHistoryGlobalGeneration = nextGeneration(
                messageHistoryGlobalGeneration,
                "message history global generation",
            )
            messageHistoryStates.clear()

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
                outgoingProjection.forEach { message -> chatWindows[message.chatId]?.upsert(message) }
            }
            // Keep draftGenerationHighWatermarks as stale-ACK fences. The outbox/overrides are
            // gone, while the next local edit must not reuse a generation still in flight.
        }
    }

    override fun enqueueBotMessage(eventId: Long, message: Message) {
        cacheUseGate.use {
            require(eventId > 0L) { "eventId must be positive" }
            require(message.serverSeq > 0L) { "durable bot messages require a positive serverSeq" }
            synchronized(stateLock) {
                queries.enqueueBotMessage(
                    eventId,
                    message.chatId,
                    message.serverSeq,
                    ProtoCodec.encode(message),
                    System.currentTimeMillis(),
                )
            }
        }
    }

    override fun peekBotMessage(): PendingBotMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            queries.peekBotMessage().executeAsOneOrNull()?.let { row ->
                PendingBotMessage(
                    eventId = row.event_id,
                    message = ProtoCodec.decode(Message, row.payload),
                )
            }
        }
    }

    override fun ackBotMessage(eventId: Long, now: Long) {
        cacheUseGate.use {
            require(eventId > 0L) { "eventId must be positive" }
            synchronized(stateLock) {
                queries.ackBotMessage(now, eventId)
            }
        }
    }

    override fun listBotMessageDeliveries(
        afterEventId: Long,
        chatId: String?,
        limit: Int,
    ): List<PendingBotMessage> = cacheUseGate.use {
        require(afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(limit > 0) { "limit must be positive" }
        synchronized(stateLock) {
            queries.selectBotMessageDeliveries(afterEventId, chatId, limit.toLong())
                .executeAsList()
                .map { row ->
                    PendingBotMessage(row.event_id, ProtoCodec.decode(Message, row.payload))
                }
        }
    }

    override fun maxBotMessageEventId(): Long = cacheUseGate.use {
        synchronized(stateLock) {
            queries.selectMaxBotMessageEventId().executeAsOne()
        }
    }

    override fun setConversationDraft(chatId: String, draft: String?): Long = cacheUseGate.use {
        synchronized(stateLock) {
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

    override fun getPendingConversationDrafts(): List<PendingConversationDraft> = cacheUseGate.use {
        synchronized(stateLock) {
            localDraftOverrides.mapNotNull { (chatId, override) ->
                if (override.state == DRAFT_MIRROR_PENDING) {
                    PendingConversationDraft(chatId, override.draft, override.generation)
                } else {
                    null
                }
            }
        }
    }

    override fun markConversationDraftMirrored(chatId: String, generation: Long) = cacheUseGate.use {
        synchronized(stateLock) state@{
            val current = localDraftOverrides[chatId] ?: return@state
            if (current.generation != generation || current.state != DRAFT_MIRROR_PENDING) return@state
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
        cacheUseGate.close {
            synchronized(stateLock) {
                messageHistoryGlobalGeneration = nextGeneration(
                    messageHistoryGlobalGeneration,
                    "message history global generation",
                )
                messageHistoryStates.clear()
            }
            driver.close()
        }
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

    /** Caller holds stateLock. */
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

    /** Caller holds stateLock. */
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

    /** Caller holds stateLock. */
    private fun invalidateMessageHistoryForChat(chatId: String) {
        val state = messageHistoryStates.getOrPut(chatId, ::MessageHistoryState)
        state.lifecycleGeneration = nextGeneration(
            state.lifecycleGeneration,
            "message history chat lifecycle generation",
        )
        state.historyChainGeneration = nextGeneration(
            state.historyChainGeneration,
            "message history chain generation",
        )
        state.committedHistoryChainGeneration = 0L
        state.pendingNewestChainGeneration = 0L
        state.newestRequestGeneration = 0L
        state.olderRequestGeneration = 0L
    }

    /** Caller holds stateLock. */
    private fun nextMessageHistoryRequestGeneration(): Long {
        messageHistoryRequestGeneration = nextGeneration(
            messageHistoryRequestGeneration,
            "message history request generation",
        )
        return messageHistoryRequestGeneration
    }

    private fun nextGeneration(current: Long, label: String): Long {
        check(current < Long.MAX_VALUE) { "$label exhausted" }
        return current + 1L
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

/** A positive server sequence makes the server projection the sole display authority. */
private fun Message.asAuthoritativeProjection(): Message =
    if (serverSeq > 0L && sendStatus != Message.SEND_STATUS_SENT) {
        copy(sendStatus = Message.SEND_STATUS_SENT)
    } else {
        this
    }

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
            // This mapper can be reached by headless sessions that deliberately do not own the
            // process-global AppLog slot. Keep diagnostics platform-local instead of attributing
            // account A's corrupt row to whichever graphical account currently owns AppLog.
            com.virjar.tk.util.platformLog(
                "fault",
                "LocalCache",
                failure.message ?: "Corrupt cached message body",
                failure,
            )
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

private fun com.virjar.tk.database.Outgoing_message.toModel() = OutgoingMessage(
    localOrdinal = local_ordinal,
    message = ProtoCodec.decode(Message, payload),
    state = OutgoingMessageState.fromCode(state),
    attemptCount = attempt_count,
    lastError = last_error,
    nextAttemptAt = next_attempt_at,
    createdAt = created_at,
    updatedAt = updated_at,
    serverSeq = server_seq,
    terminalCode = terminal_code?.toInt(),
    completedAt = completed_at,
)

private fun com.virjar.tk.database.Outgoing_message.projectionSendStatus(): Int = when (
    OutgoingMessageState.fromCode(state)
) {
    OutgoingMessageState.IN_FLIGHT -> Message.SEND_STATUS_SENDING
    OutgoingMessageState.TERMINAL_FAILED -> Message.SEND_STATUS_FAILED
    OutgoingMessageState.SUCCESS -> Message.SEND_STATUS_SENT
    OutgoingMessageState.PENDING,
    OutgoingMessageState.RETRY_WAIT -> Message.SEND_STATUS_QUEUED
}

private fun com.virjar.tk.database.Outgoing_message.toProjectionMessage(): Message =
    ProtoCodec.decode(Message, payload).copy(
        serverSeq = if (state == OutgoingMessageState.SUCCESS.code) {
            requireNotNull(server_seq) { "SUCCESS outgoing receipt has no serverSeq" }
        } else {
            0L
        },
        sendStatus = projectionSendStatus(),
    )

private fun com.virjar.tk.database.Outgoing_message.requireSameOutgoingRequest(
    candidatePayload: ByteArray,
    candidateFingerprint: ByteArray?,
) {
    if (request_fingerprint != null || candidateFingerprint != null) {
        if (
            request_fingerprint == null || candidateFingerprint == null ||
            !request_fingerprint.contentEquals(candidateFingerprint)
        ) {
            throw OutgoingMessageConflictException(
                "clientMsgId already names a different durable outgoing request",
            )
        }
        // The fingerprint identifies a higher-level stable request. Keep the first immutable wire
        // payload (including its original timestamp/remote attachment) as the canonical fact.
        return
    }
    if (!payload.contentEquals(candidatePayload)) {
        throw OutgoingMessageConflictException(
            "clientMsgId already names a different immutable outgoing payload",
        )
    }
}

private fun com.virjar.tk.database.Outgoing_message.requireRequestFingerprint(expected: ByteArray) {
    if (request_fingerprint == null || !request_fingerprint.contentEquals(expected)) {
        throw OutgoingMessageConflictException(
            "clientMsgId already names a different durable outgoing request",
        )
    }
}

private fun com.virjar.tk.database.Conversation.toModel() = Conversation(
    chatId = chat_id, chatType = chat_type.toInt(), chatName = chat_name,
    chatAvatar = chat_avatar, lastSeq = last_seq ?: 0L, readSeq = read_seq ?: 0L,
    peerReadSeq = peer_read_seq ?: 0L,
    unreadCount = unread_count?.toInt() ?: 0, isPinned = is_pinned == 1L,
    isMuted = is_muted == 1L, draft = draft, lastMsgTimestamp = last_msg_timestamp,
)
