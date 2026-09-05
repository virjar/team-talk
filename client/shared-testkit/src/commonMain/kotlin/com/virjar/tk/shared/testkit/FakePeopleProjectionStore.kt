package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.KeyedProjectionSnapshotGate
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 用户、联系人与聊天成员投影共享内嵌 User 的装配与快照门禁。 */
internal class FakePeopleProjectionStore {
    private val userMemberLock = Any()
    private val usersFlow = MutableStateFlow<List<User>>(emptyList())
    private val userSnapshots = KeyedProjectionSnapshotGate("fake user snapshot")
    private val userSnapshotLeases = mutableMapOf<String, ProjectionSnapshotLease>()
    private val contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    private val contactLock = Any()
    private var contactProjectionGeneration = 0L
    private var lastFullContactSnapshotGeneration = 0L
    private val contactMutationGenerations = mutableMapOf<String, Long>()
    private val membersFlow = MutableStateFlow<Map<String, List<Member>>>(emptyMap())
    private val memberSnapshots = KeyedProjectionSnapshotGate("fake member snapshot")
    private val memberSnapshotLeases = mutableMapOf<String, ProjectionSnapshotLease>()

    var userPointReadCountForTest: Int = 0
        private set
    var userProjectionAcquisitionCountForTest: Int = 0
        private set

    fun getUser(uid: String): User? {
        userPointReadCountForTest++
        return usersFlow.value.find { it.uid == uid }
    }

    fun observeUser(uid: String): Flow<User?> {
        userProjectionAcquisitionCountForTest++
        return usersFlow.map { users -> users.firstOrNull { it.uid == uid } }.distinctUntilChanged()
    }

    fun upsertUser(user: User) = synchronized(userMemberLock) {
        userSnapshots.invalidate(user.uid)
        userSnapshotLeases.remove(user.uid)
        upsertUserLocked(user)
    }

    fun beginUserSnapshot(uid: String): ProjectionSnapshotLease = synchronized(userMemberLock) {
        userSnapshots.begin(uid).also { userSnapshotLeases[uid] = it }
    }

    fun applyUserSnapshot(lease: ProjectionSnapshotLease, user: User): Boolean =
        synchronized(userMemberLock) {
            if (userSnapshotLeases[user.uid] !== lease) return@synchronized false
            if (!userSnapshots.consumeIfCurrent(lease, user.uid)) return@synchronized false
            userSnapshotLeases.remove(user.uid)
            upsertUserLocked(user)
            true
        }

    fun getContacts(): List<Contact> = projectFakeContacts(contactsFlow.value, usersFlow.value)

    fun observeContacts(): Flow<List<Contact>> =
        combine(contactsFlow, usersFlow, ::projectFakeContacts).distinctUntilChanged()

    fun upsertContact(contact: Contact) = synchronized(contactLock) {
        synchronized(userMemberLock) {
            contact.user?.let { user ->
                userSnapshots.invalidate(user.uid)
                userSnapshotLeases.remove(user.uid)
                upsertUserLocked(user)
            }
        }
        contactsFlow.value = mergeFakeContact(contactsFlow.value, contact)
        markContactMutated(contact.friendUid)
    }

    fun deleteContact(friendUid: String) = synchronized(contactLock) {
        contactsFlow.value = contactsFlow.value.filter { it.friendUid != friendUid }
        markContactMutated(friendUid)
    }

    fun contactProjectionGeneration(): Long = synchronized(contactLock) { contactProjectionGeneration }

    fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean {
        require(expectedGeneration >= 0L) { "expectedGeneration 不能为负数" }
        val snapshot = contacts.associateBy(Contact::friendUid).values.toList()
        return synchronized(contactLock) {
            if (contactProjectionGeneration == expectedGeneration) {
                mergeUsers(snapshot.mapNotNull(Contact::user))
                contactsFlow.value = snapshot
                contactProjectionGeneration += 1L
                lastFullContactSnapshotGeneration = contactProjectionGeneration
                contactMutationGenerations.clear()
                true
            } else {
                val mergeable = if (expectedGeneration < lastFullContactSnapshotGeneration) {
                    emptyList()
                } else {
                    snapshot.filter { contact ->
                        (contactMutationGenerations[contact.friendUid] ?: 0L) <= expectedGeneration
                    }
                }
                if (mergeable.isNotEmpty()) {
                    mergeUsers(mergeable.mapNotNull(Contact::user))
                    var merged = contactsFlow.value
                    mergeable.forEach { merged = mergeFakeContact(merged, it) }
                    contactsFlow.value = merged
                    contactProjectionGeneration += 1L
                    val mergedGeneration = contactProjectionGeneration
                    mergeable.forEach { contactMutationGenerations[it.friendUid] = mergedGeneration }
                }
                false
            }
        }
    }

    fun getMembers(chatId: String): List<Member> = synchronized(userMemberLock) {
        projectFakeMembers(membersFlow.value[chatId].orEmpty(), usersFlow.value)
    }

    fun observeMembers(chatId: String): Flow<List<Member>> = combine(membersFlow, usersFlow) { members, users ->
        projectFakeMembers(members[chatId].orEmpty(), users)
    }.distinctUntilChanged()

    fun upsertMember(member: Member) = synchronized(userMemberLock) {
        memberSnapshots.invalidate(member.chatId)
        memberSnapshotLeases.remove(member.chatId)
        mergeUsersLocked(member.user?.let { listOf(it) }.orEmpty())
        val current = membersFlow.value.toMutableMap()
        val list = current[member.chatId].orEmpty().toMutableList()
        val index = list.indexOfFirst { it.uid == member.uid }
        if (index >= 0) list[index] = member else list.add(member)
        current[member.chatId] = list
        membersFlow.value = current
    }

    fun removeMember(chatId: String, uid: String) = synchronized(userMemberLock) {
        memberSnapshots.invalidate(chatId)
        memberSnapshotLeases.remove(chatId)
        val current = membersFlow.value.toMutableMap()
        current[chatId] = current[chatId].orEmpty().filter { it.uid != uid }
        membersFlow.value = current
    }

    fun beginMemberSnapshot(chatId: String): ProjectionSnapshotLease = synchronized(userMemberLock) {
        memberSnapshots.begin(chatId).also { memberSnapshotLeases[chatId] = it }
    }

    fun applyMemberSnapshot(lease: ProjectionSnapshotLease, members: List<Member>): Boolean =
        synchronized(userMemberLock) {
            val chatId = memberSnapshotLeases.entries
                .firstOrNull { (_, currentLease) -> currentLease === lease }
                ?.key ?: return@synchronized false
            require(members.all { it.chatId == chatId }) { "member snapshot identity mismatch" }
            val snapshot = members.associateBy(Member::uid).values.toList()
            if (!memberSnapshots.consumeIfCurrent(lease, chatId)) return@synchronized false
            memberSnapshotLeases.remove(chatId)
            mergeUsersLocked(snapshot.mapNotNull(Member::user))
            membersFlow.value = membersFlow.value.toMutableMap().apply { put(chatId, snapshot) }
            true
        }

    fun removeChat(chatId: String) = synchronized(userMemberLock) {
        memberSnapshots.invalidate(chatId)
        memberSnapshotLeases.remove(chatId)
        membersFlow.value = membersFlow.value - chatId
    }

    /** 保持外层重置的加锁顺序，同时将所有人员投影事实保持私有。 */
    fun <T> withProjectionReset(block: (resetPeopleProjection: () -> Unit) -> T): T =
        synchronized(contactLock) {
            var reset = false
            val result = block {
                check(!reset) { "People projection reset was already applied" }
                reset = true
                contactsFlow.value = emptyList()
                synchronized(userMemberLock) {
                    userSnapshots.reset()
                    memberSnapshots.reset()
                    userSnapshotLeases.clear()
                    memberSnapshotLeases.clear()
                    usersFlow.value = emptyList()
                    membersFlow.value = emptyMap()
                }
                check(contactProjectionGeneration < Long.MAX_VALUE)
                contactProjectionGeneration += 1L
                lastFullContactSnapshotGeneration = contactProjectionGeneration
                contactMutationGenerations.clear()
            }
            check(reset) { "People projection reset was not applied" }
            result
        }

    /** 保持外层检查点的加锁顺序，同时原子性地仅替换同步拥有的行。 */
    fun <T> withProjectionCheckpoint(
        currentUser: User,
        contacts: List<Contact>,
        block: (applyPeopleProjection: () -> Unit) -> T,
    ): T = synchronized(contactLock) {
        var applied = false
        val result = block {
            check(!applied) { "People checkpoint projection was already applied" }
            applied = true
            synchronized(userMemberLock) {
                userSnapshots.reset()
                memberSnapshots.reset()
                userSnapshotLeases.clear()
                memberSnapshotLeases.clear()
                val storedUsers = usersFlow.value.associateBy(User::uid)
                val users = LinkedHashMap<String, User>()
                fun mergeCheckpointUser(incoming: User) {
                    val stored = users[incoming.uid] ?: storedUsers[incoming.uid]
                    users[incoming.uid] = if (
                        stored == null || incoming.revision > stored.revision
                    ) {
                        incoming
                    } else {
                        stored
                    }
                }
                mergeCheckpointUser(currentUser)
                contacts.mapNotNull(Contact::user).forEach(::mergeCheckpointUser)
                usersFlow.value = users.values.toList()
                membersFlow.value = emptyMap()
            }
            contactsFlow.value = contacts
            check(contactProjectionGeneration < Long.MAX_VALUE)
            contactProjectionGeneration += 1L
            lastFullContactSnapshotGeneration = contactProjectionGeneration
            contactMutationGenerations.clear()
        }
        check(applied) { "People checkpoint projection was not applied" }
        result
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease, fallback: () -> Boolean): Boolean =
        synchronized(userMemberLock) {
            val userAbandoned = userSnapshots.abandon(lease)
            if (userAbandoned) removeCurrentFakeLease(userSnapshotLeases, lease)
            val memberAbandoned = !userAbandoned && memberSnapshots.abandon(lease)
            if (memberAbandoned) removeCurrentFakeLease(memberSnapshotLeases, lease)
            userAbandoned || memberAbandoned || fallback()
        }

    fun activeSnapshotCount(fallback: () -> Int): Int = synchronized(userMemberLock) {
        userSnapshotLeases.size + memberSnapshotLeases.size + fallback()
    }

    fun close() = synchronized(userMemberLock) {
        userSnapshots.reset()
        memberSnapshots.reset()
        userSnapshotLeases.clear()
        memberSnapshotLeases.clear()
    }

    private fun upsertUserLocked(user: User) {
        val list = usersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.uid == user.uid }
        if (index < 0) {
            list.add(user)
        } else if (user.revision > list[index].revision) {
            list[index] = user
        }
        usersFlow.value = list
    }

    private fun markContactMutated(friendUid: String) {
        contactProjectionGeneration += 1L
        contactMutationGenerations[friendUid] = contactProjectionGeneration
    }

    private fun mergeUsers(candidates: List<User>) = synchronized(userMemberLock) {
        mergeUsersLocked(candidates)
    }

    /** 调用方持有 [userMemberLock] 锁。 */
    private fun mergeUsersLocked(candidates: List<User>) =
        candidates.distinctBy(User::uid).forEach(::upsertUserLocked)
}
