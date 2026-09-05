package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal data class ServerCheckpointUserPlan(
    val canonicalUsers: List<User>,
    val consumedTransientUids: Set<String>,
)

/**
 * 会话拥有的规范化实体投影。
 *
 * 联系人是当前产品要求的唯一显式常驻列表。User 与 Chat 是按 key 的 SQL 投影；其 StateFlow 只在
 * 该 key 被收集期间存在。成员按 chat 使用相同策略，并且只对选定 chat 联接 User 摘要。每次 SQL
 * 变更与常驻发布共享 [stateLock]，保留快照/墓碑/reset 隔断。
 */
internal class LocalEntityProjectionStore(
    queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val markConversationMutatedLocked: (String) -> Unit,
    private val persistExternalUserProjectionLocked: (User) -> Unit = {},
    private val publishExternalUserProjectionLocked: (User) -> Unit = {},
) {
    private val dao = LocalEntityProjectionDao(queries)
    private val userResidents = LinkedHashMap<String, KeyedEntityResident<User?>>()
    private val chatResidents = LinkedHashMap<String, KeyedEntityResident<Chat?>>()
    private val memberResidents = LinkedHashMap<String, MemberProjectionResident>()
    private val memberResidentChatsByUid = LinkedHashMap<String, LinkedHashSet<String>>()
    private val userSnapshots = KeyedProjectionSnapshotGate("user snapshot")
    private val chatSnapshots = KeyedProjectionSnapshotGate("chat snapshot")
    private val memberSnapshots = KeyedProjectionSnapshotGate("member snapshot")
    /** 为刚好在其首个关系之前到达的临时 USER_UPDATED 提供的 best-effort 桥。 */
    private val recentTransientUsers = LinkedHashMap<String, User>()
    private val contacts = LocalContactProjectionStore(
        dao = dao,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
        mergeUserLocked = ::mergeAuthoritativeUserLocked,
        publishUserMergeLocked = ::publishAuthoritativeUserMergeLocked,
        refreshEmbeddedUsersLocked = { uids ->
            refreshVisibleEmbeddedUsersLocked(uids, includeContacts = false)
        },
    )

    fun getUser(uid: String): User? = cacheUseGate.use {
        synchronized(stateLock) { dao.getUser(uid) }
    }

    fun observeUser(uid: String): Flow<User?> = cacheUseGate.use {
        flow {
            val resident = acquireUserResident(uid)
            try {
                emitAll(resident.flow.observe())
            } finally {
                releaseUserResident(uid, resident)
            }
        }
    }

    fun upsertUser(user: User) {
        cacheUseGate.use {
            synchronized(stateLock) { upsertUserLocked(user) }
        }
    }

    fun upsertTransientUserIfRelevant(user: User): Boolean = cacheUseGate.runIfOpen {
        synchronized(stateLock) {
            val hasActiveProjection =
                userSnapshots.hasCurrentRequest(user.uid) || userResidents.containsKey(user.uid)
            if (!hasActiveProjection && !dao.isUserLocallyRelevant(user.uid)) {
                rememberRecentTransientUserLocked(user)
                return@synchronized false
            }
            upsertUserLocked(user)
            true
        }
    }

    fun beginUserSnapshot(uid: String): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) {
            // 一旦精确 profile 请求存在，其 uid 就不再是 best-effort 未知项。
            // 在签发租约之前先安装任何更早的临时项，这样有界覆盖层驱逐就不会让最终的 RPC
            // 响应回退它。
            materializeRecentTransientUsersForRelationsLocked(setOf(uid))
            userSnapshots.begin(uid)
        }
    }

    fun applyUserSnapshot(lease: ProjectionSnapshotLease, user: User): Boolean = cacheUseGate.runIfOpen {
        require(user.uid == lease.key) { "user snapshot identity mismatch" }
        synchronized(stateLock) {
            if (!userSnapshots.consumeIfCurrent(lease, user.uid)) return@synchronized false
            var merge: UserProjectionMerge? = null
            dao.transaction { merge = mergeAuthoritativeUserLocked(user) }
            val applied = requireNotNull(merge)
            publishAuthoritativeUserMergeLocked(applied)
            applied.canonical == user
        }
    }

    /** 调用方持有 [stateLock]。 */
    private fun upsertUserLocked(user: User): Boolean {
        var merge: UserProjectionMerge? = null
        dao.transaction { merge = mergeAuthoritativeUserLocked(user) }
        val applied = requireNotNull(merge)
        publishAuthoritativeUserMergeLocked(applied)
        return applied.canonical == user
    }

    /** 调用方持有 [stateLock] 与外层 SQL 事务。 */
    internal fun mergeAuthoritativeUserLocked(user: User): UserProjectionMerge {
        val incoming = mergeWithRecentTransientLocked(user)
        val merge = mergeUserProjection(dao.getUser(user.uid), incoming)
        if (!merge.advanced) return merge
        userSnapshots.invalidate(user.uid)
        dao.persistUser(merge.canonical)
        persistExternalUserProjectionLocked(merge.canonical)
        return merge
    }

    /** 调用方持有 [stateLock]；关系行已经安装。 */
    internal fun materializeRecentTransientUsersForRelationsLocked(uids: Set<String>) {
        if (uids.isEmpty()) return
        val merges = mutableListOf<UserProjectionMerge>()
        dao.transaction {
            uids.forEach { uid ->
                recentTransientUsers[uid]?.let { transient ->
                    merges += mergeAuthoritativeUserLocked(transient)
                }
            }
        }
        merges.forEach(::publishAuthoritativeUserMergeLocked)
    }

    /** 调用方持有 [stateLock]；对应的 SQL 事务已提交。 */
    internal fun publishAuthoritativeUserMergeLocked(merge: UserProjectionMerge) {
        if (!merge.advanced) return
        publishExternalUserProjectionLocked(merge.canonical)
        publishUsersToResidentsLocked(mapOf(merge.canonical.uid to merge.canonical))
    }

    fun getContacts(): List<Contact> = contacts.get()
    fun observeContacts(): Flow<List<Contact>> = contacts.observe()
    fun upsertContact(contact: Contact) = contacts.upsert(contact)
    fun deleteContact(friendUid: String) = contacts.delete(friendUid)
    fun contactProjectionGeneration(): Long = contacts.generation()
    fun applyContactSnapshot(expectedGeneration: Long, contactSnapshot: List<Contact>): Boolean =
        contacts.applySnapshot(expectedGeneration, contactSnapshot)

    fun getChat(chatId: String): Chat? = cacheUseGate.use {
        synchronized(stateLock) { dao.getChat(chatId) }
    }

    fun observeChat(chatId: String): Flow<Chat?> = cacheUseGate.use {
        flow {
            val resident = acquireChatResident(chatId)
            try {
                emitAll(resident.flow.observe())
            } finally {
                releaseChatResident(chatId, resident)
            }
        }
    }

    fun upsertChat(chat: Chat) = cacheUseGate.use {
        synchronized(stateLock) {
            chatSnapshots.invalidate(chat.chatId)
            persistAndPublishChatLocked(chat)
        }
    }

    fun beginChatSnapshot(chatId: String): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) { chatSnapshots.begin(chatId) }
    }

    fun applyChatSnapshot(lease: ProjectionSnapshotLease, chat: Chat): Boolean = cacheUseGate.runIfOpen {
        require(chat.chatId == lease.key) { "chat snapshot identity mismatch" }
        synchronized(stateLock) {
            if (!chatSnapshots.consumeIfCurrent(lease, chat.chatId)) return@synchronized false
            persistAndPublishChatLocked(chat)
            true
        }
    }

    /** 调用方持有 [stateLock]。 */
    private fun persistAndPublishChatLocked(chat: Chat) {
        dao.persistChat(chat)
        chatResidents[chat.chatId]?.flow?.value = chat
        // CHAT_CREATED 初始只持久化 Chat；同样隔断过期的 conversation 列表。
        markConversationMutatedLocked(chat.chatId)
    }

    fun getMembers(chatId: String): List<Member> = cacheUseGate.use {
        synchronized(stateLock) { memberResidents[chatId]?.flow?.value ?: dao.loadMembers(chatId) }
    }

    fun observeMembers(chatId: String): Flow<List<Member>> = cacheUseGate.use {
        flow {
            val resident = acquireMemberResident(chatId)
            try {
                emitAll(resident.flow.observe())
            } finally {
                releaseMemberResident(chatId, resident)
            }
        }
    }

    fun upsertMember(member: Member) {
        cacheUseGate.use {
            validateMember(member)
            synchronized(stateLock) {
                memberSnapshots.invalidate(member.chatId)
                var userMerge: UserProjectionMerge? = null
                dao.transaction {
                    dao.persistMember(member)
                    userMerge = member.user?.let(::mergeAuthoritativeUserLocked)
                }
                userMerge?.let(::publishAuthoritativeUserMergeLocked)
                member.user?.let { embedded ->
                    refreshVisibleEmbeddedUsersLocked(
                        setOf(embedded.uid),
                        excludedMemberChatId = member.chatId,
                    )
                }
                memberResidents[member.chatId]?.let { resident ->
                    val persisted = requireNotNull(dao.getMember(member.chatId, member.uid)) {
                        "persisted member disappeared before resident publication"
                    }
                    val wasAbsent = resident.membersByUid.put(member.uid, persisted) == null
                    if (wasAbsent) registerMemberResidentUidLocked(member.uid, member.chatId)
                    resident.flow.value = resident.membersByUid.values.toList()
                }
            }
        }
    }

    fun removeMember(chatId: String, uid: String) {
        cacheUseGate.use {
            synchronized(stateLock) {
                memberSnapshots.invalidate(chatId)
                dao.removeMember(chatId, uid)
                memberResidents[chatId]?.let { resident ->
                    if (resident.membersByUid.remove(uid) != null) {
                        unregisterMemberResidentUidLocked(uid, chatId)
                        resident.flow.value = resident.membersByUid.values.toList()
                    }
                }
            }
        }
    }

    fun beginMemberSnapshot(chatId: String): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) { memberSnapshots.begin(chatId) }
    }

    fun applyMemberSnapshot(
        lease: ProjectionSnapshotLease,
        members: List<Member>,
    ): Boolean = cacheUseGate.runIfOpen {
        val snapshot = normalizeMembers(lease.key, members)
        synchronized(stateLock) {
            if (!memberSnapshots.consumeIfCurrent(lease, lease.key)) return@synchronized false
            val userMerges = mutableListOf<UserProjectionMerge>()
            dao.transaction {
                dao.deleteMembers(lease.key)
                snapshot.forEach(dao::persistMember)
                snapshot.mapNotNull(Member::user).distinctBy(User::uid)
                    .mapTo(userMerges, ::mergeAuthoritativeUserLocked)
            }
            userMerges.forEach(::publishAuthoritativeUserMergeLocked)
            refreshVisibleEmbeddedUsersLocked(
                snapshot.mapNotNull(Member::user).mapTo(linkedSetOf(), User::uid),
                excludedMemberChatId = lease.key,
            )
            memberResidents[lease.key]?.let { resident ->
                replaceMemberResidentLocked(lease.key, resident, dao.loadMembers(lease.key))
            }
            true
        }
    }

    fun abandonProjectionSnapshot(lease: ProjectionSnapshotLease): Boolean =
        cacheUseGate.runIfOpen {
            synchronized(stateLock) {
                userSnapshots.abandon(lease) ||
                    chatSnapshots.abandon(lease) ||
                    memberSnapshots.abandon(lease)
            }
        }

    /** 调用方持有 [stateLock]；SQL 删除由 [LocalCacheImpl] 拥有。 */
    fun invalidateChatAndRemoveChatLocked(chatId: String) {
        chatSnapshots.invalidate(chatId)
        memberSnapshots.invalidate(chatId)
        chatResidents[chatId]?.flow?.value = null
    }

    /** 调用方持有 [stateLock]；保持分开以保留墓碑发布顺序。 */
    fun removeChatMembersLocked(chatId: String) {
        memberResidents[chatId]?.let { resident -> replaceMemberResidentLocked(chatId, resident, emptyList()) }
    }

    /** 调用方持有 [stateLock]。规划期间不变更任何常驻或临时状态。 */
    fun prepareServerCheckpointLocked(
        checkpoint: ServerProjectionCheckpoint,
        projectedConversations: Collection<com.virjar.tk.protocol.model.Conversation>,
    ): ServerCheckpointUserPlan {
        val canonicalUsers = LinkedHashMap<String, User>()
        val consumedTransientUids = linkedSetOf<String>()

        fun recentTransient(uid: String): User? = recentTransientUsers[uid]?.also {
            consumedTransientUids += uid
        }

        fun mergeRelevantUser(incoming: User) {
            val transient = recentTransient(incoming.uid)
            val candidate = transient?.let { firstAccepted ->
                mergeUserProjection(firstAccepted, incoming).canonical
            } ?: incoming
            val stored = canonicalUsers[incoming.uid] ?: dao.getUser(incoming.uid)
            canonicalUsers[incoming.uid] = mergeUserProjection(stored, candidate).canonical
        }

        checkpointUsers(checkpoint).forEach(::mergeRelevantUser)
        dao.loadOrganizationMembersForCheckpoint().forEach { member ->
            val embedded = member.user
            if (embedded != null) {
                mergeRelevantUser(embedded)
            } else {
                val transient = recentTransient(member.uid)
                val stored = canonicalUsers[member.uid] ?: dao.getUser(member.uid)
                val candidate = when {
                    stored == null -> transient
                    transient == null -> stored
                    else -> mergeUserProjection(stored, transient).canonical
                }
                if (candidate != null) canonicalUsers[member.uid] = candidate
            }
        }
        projectedConversations.forEach { conversation ->
            val peerUid = conversation.peerUid ?: return@forEach
            val peerRevision = conversation.peerRevision ?: return@forEach
            val transient = recentTransient(peerUid)
            val stored = canonicalUsers[peerUid] ?: dao.getUser(peerUid)
            val candidate = when {
                stored == null -> transient
                transient == null -> stored
                else -> mergeUserProjection(stored, transient).canonical
            }
            if (candidate != null && candidate.revision >= peerRevision) {
                canonicalUsers[peerUid] = candidate
            }
        }
        return ServerCheckpointUserPlan(
            canonicalUsers = canonicalUsers.values.toList(),
            consumedTransientUids = consumedTransientUids,
        )
    }

    /** 调用方持有 [stateLock] 与外层检查点 SQL 事务。 */
    fun persistServerCheckpointLocked(
        checkpoint: ServerProjectionCheckpoint,
        plan: ServerCheckpointUserPlan,
    ) {
        dao.deleteAllMembers()
        dao.deleteAllContacts()
        dao.deleteAllChats()
        dao.deleteAllUsers()
        plan.canonicalUsers.forEach { canonical ->
            dao.persistUser(canonical)
            persistExternalUserProjectionLocked(canonical)
        }
        checkpoint.contacts.forEach(dao::persistContact)
        checkpoint.chats.forEach(dao::persistChat)
    }

    /** 调用方持有 [stateLock]；检查点事务已提交。 */
    fun publishServerCheckpointLocked(
        checkpoint: ServerProjectionCheckpoint,
        plan: ServerCheckpointUserPlan,
    ) {
        plan.consumedTransientUids.forEach(recentTransientUsers::remove)
        plan.canonicalUsers.forEach(publishExternalUserProjectionLocked)
        resetSnapshotGatesLocked()
        contacts.reloadCheckpointProjectionLocked()
        userResidents.forEach { (uid, resident) -> resident.flow.value = dao.getUser(uid) }
        chatResidents.forEach { (chatId, resident) -> resident.flow.value = dao.getChat(chatId) }
        memberResidents.forEach { (chatId, resident) ->
            replaceMemberResidentLocked(chatId, resident, emptyList())
        }
    }

    private fun checkpointUsers(checkpoint: ServerProjectionCheckpoint): List<User> =
        buildList {
            add(checkpoint.currentUser)
            addAll(checkpoint.contacts.mapNotNull(Contact::user))
        }

    /** 调用方持有 [stateLock]。 */
    fun resetSnapshotGatesLocked() {
        userSnapshots.reset()
        chatSnapshots.reset()
        memberSnapshots.reset()
    }

    /** 调用方持有 [stateLock]；SQL reset 由 [LocalCacheImpl] 拥有。 */
    fun clearProjectionLocked() {
        recentTransientUsers.clear()
        contacts.clearProjectionLocked()
        userResidents.values.forEach { it.flow.value = null }
        chatResidents.values.forEach { it.flow.value = null }
        memberResidents.forEach { (chatId, resident) ->
            replaceMemberResidentLocked(chatId, resident, emptyList())
        }
    }

    /** 调用方持有 [stateLock]；缓存关闭永久退役每个收集者拥有的常驻项。 */
    fun closeResidentsLocked() {
        recentTransientUsers.clear()
        contacts.closeResidentLocked()
        userResidents.values.forEach {
            it.flow.value = null
            it.flow.retire()
        }
        chatResidents.values.forEach {
            it.flow.value = null
            it.flow.retire()
        }
        memberResidents.values.forEach {
            it.flow.value = emptyList()
            it.flow.retire()
        }
        userResidents.clear()
        chatResidents.clear()
        memberResidents.clear()
        memberResidentChatsByUid.clear()
    }

    internal fun residentCountsForTest(): EntityProjectionResidentCounts = cacheUseGate.use {
        synchronized(stateLock) {
            EntityProjectionResidentCounts(
                contacts = contacts.residentCountLocked(),
                users = userResidents.size,
                userObservers = userResidents.values.sumOf { it.observers },
                chats = chatResidents.size,
                chatObservers = chatResidents.values.sumOf { it.observers },
                memberChats = memberResidents.size,
                memberObservers = memberResidents.values.sumOf { it.observers },
            )
        }
    }

    internal fun recentTransientUserCountForTest(): Int = cacheUseGate.use {
        synchronized(stateLock) { recentTransientUsers.size }
    }

    /** 调用方持有 [stateLock]。先到者在相等修订号冲突中获胜。 */
    private fun rememberRecentTransientUserLocked(user: User) {
        val existing = recentTransientUsers.remove(user.uid)
        val canonical = when {
            existing == null -> user
            user.revision > existing.revision -> user
            else -> existing
        }
        if (existing == null && recentTransientUsers.size >= MAX_RECENT_TRANSIENT_USERS) {
            recentTransientUsers.keys.firstOrNull()?.let(recentTransientUsers::remove)
        }
        recentTransientUsers[user.uid] = canonical
    }

    /**
     * 调用方持有 [stateLock]。临时项先到，因此它在相等修订号冲突中获胜。把有界覆盖层保留到普通
     * LRU 驱逐：调用方在 SQL 事务内部调用它，提交前消费它会在回滚时丢失更新的事实。
     */
    private fun mergeWithRecentTransientLocked(authoritative: User): User {
        val transient = recentTransientUsers[authoritative.uid] ?: return authoritative
        return mergeUserProjection(transient, authoritative).canonical
    }

    private fun acquireUserResident(uid: String): KeyedEntityResident<User?> = cacheUseGate.use {
        synchronized(stateLock) {
            // 被收集的精确 user 投影是当前产品关系。在暴露初始常驻值之前先提升更早的临时项。
            materializeRecentTransientUsersForRelationsLocked(setOf(uid))
            userResidents.getOrPut(uid) { KeyedEntityResident(dao.getUser(uid)) }
                .also { it.observers += 1 }
        }
    }

    private fun releaseUserResident(uid: String, resident: KeyedEntityResident<User?>) {
        synchronized(stateLock) {
            check(resident.observers > 0) { "user observer count underflow" }
            resident.observers -= 1
            if (resident.observers == 0 && userResidents[uid] === resident) {
                userResidents.remove(uid)
            }
        }
    }

    private fun acquireChatResident(chatId: String): KeyedEntityResident<Chat?> = cacheUseGate.use {
        synchronized(stateLock) {
            chatResidents.getOrPut(chatId) { KeyedEntityResident(dao.getChat(chatId)) }
                .also { it.observers += 1 }
        }
    }

    private fun releaseChatResident(chatId: String, resident: KeyedEntityResident<Chat?>) {
        synchronized(stateLock) {
            check(resident.observers > 0) { "chat observer count underflow" }
            resident.observers -= 1
            if (resident.observers == 0 && chatResidents[chatId] === resident) {
                chatResidents.remove(chatId)
            }
        }
    }

    private fun acquireMemberResident(chatId: String): MemberProjectionResident = cacheUseGate.use {
        synchronized(stateLock) {
            memberResidents.getOrPut(chatId) {
                MemberProjectionResident(dao.loadMembers(chatId)).also { resident ->
                    resident.membersByUid.keys.forEach { uid -> registerMemberResidentUidLocked(uid, chatId) }
                }
            }.also { it.observers += 1 }
        }
    }

    private fun releaseMemberResident(chatId: String, resident: MemberProjectionResident) {
        synchronized(stateLock) {
            check(resident.observers > 0) { "member observer count underflow" }
            resident.observers -= 1
            if (resident.observers == 0 && memberResidents[chatId] === resident) {
                resident.membersByUid.keys.forEach { uid -> unregisterMemberResidentUidLocked(uid, chatId) }
                memberResidents.remove(chatId)
            }
        }
    }

    private fun refreshVisibleEmbeddedUsersLocked(
        candidateUids: Set<String>,
        includeContacts: Boolean = true,
        excludedMemberChatId: String? = null,
    ) {
        val visibleUsers = LinkedHashMap<String, User>()
        candidateUids.forEach { uid ->
            val visibleMemberChat = memberResidentChatsByUid[uid]?.any { it != excludedMemberChatId } == true
            if (userResidents.containsKey(uid) ||
                (includeContacts && contacts.containsFriendLocked(uid)) || visibleMemberChat
            ) {
                dao.getUser(uid)?.let { visibleUsers[uid] = it }
            }
        }
        publishUsersToResidentsLocked(visibleUsers, includeContacts, excludedMemberChatId)
    }

    private fun publishUsersToResidentsLocked(
        users: Map<String, User>,
        includeContacts: Boolean = true,
        excludedMemberChatId: String? = null,
    ) {
        val changedMemberChats = linkedSetOf<String>()
        users.forEach { (uid, user) ->
            userResidents[uid]?.flow?.value = user
            memberResidentChatsByUid[uid]?.forEach memberChat@{ chatId ->
                if (chatId == excludedMemberChatId) return@memberChat
                val resident = memberResidents[chatId] ?: return@memberChat
                val member = resident.membersByUid[uid] ?: return@memberChat
                if (member.user != user) {
                    resident.membersByUid[uid] = member.copy(user = user)
                    changedMemberChats += chatId
                }
            }
        }
        if (includeContacts) contacts.publishUsersLocked(users)
        changedMemberChats.forEach { chatId ->
            memberResidents[chatId]?.let { it.flow.value = it.membersByUid.values.toList() }
        }
    }

    private fun replaceMemberResidentLocked(
        chatId: String,
        resident: MemberProjectionResident,
        members: List<Member>,
    ) {
        resident.membersByUid.keys.forEach { uid -> unregisterMemberResidentUidLocked(uid, chatId) }
        resident.membersByUid.clear()
        members.forEach { member ->
            resident.membersByUid[member.uid] = member
            registerMemberResidentUidLocked(member.uid, chatId)
        }
        resident.flow.value = resident.membersByUid.values.toList()
    }

    private fun registerMemberResidentUidLocked(uid: String, chatId: String) {
        memberResidentChatsByUid.getOrPut(uid) { linkedSetOf() }.add(chatId)
    }

    private fun unregisterMemberResidentUidLocked(uid: String, chatId: String) {
        memberResidentChatsByUid[uid]?.let { chatIds ->
            chatIds.remove(chatId)
            if (chatIds.isEmpty()) memberResidentChatsByUid.remove(uid)
        }
    }

    private fun normalizeMembers(chatId: String, members: List<Member>): List<Member> =
        LinkedHashMap<String, Member>().apply {
            members.forEach { member ->
                require(member.chatId == chatId) { "member snapshot identity mismatch" }
                validateMember(member)
                put(member.uid, member)
            }
        }.values.toList()

    private fun validateMember(member: Member) {
        member.user?.let { user ->
            require(user.uid == member.uid) { "member embedded user identity mismatch" }
        }
    }

    private companion object {
        const val MAX_RECENT_TRANSIENT_USERS = 256
    }
}
