package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.flow.Flow

/**
 * 显式账号级联系人投影及其全量列表请求隔断。
 *
 * 联系人保持常驻，因为当前产品渲染完整目录。每个快照用一次 Contact/User 联接重建；该存储从不
 * 拥有全局 User 列表。
 */
internal class LocalContactProjectionStore(
    private val dao: LocalEntityProjectionDao,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val mergeUserLocked: (User) -> UserProjectionMerge,
    private val publishUserMergeLocked: (UserProjectionMerge) -> Unit,
    private val refreshEmbeddedUsersLocked: (Set<String>) -> Unit,
) {
    private val contactsByFriendUid = LinkedHashMap<String, Contact>()
    private val contactsFlow = RetirableProjectionState<List<Contact>>(emptyList())
    private var projectionGeneration = 0L
    private var lastFullSnapshotGeneration = 0L
    private val mutationGenerations = mutableMapOf<String, Long>()

    init {
        replaceContactsLocked(dao.loadContacts())
    }

    fun get(): List<Contact> = cacheUseGate.use {
        synchronized(stateLock) { contactsFlow.value }
    }

    fun observe(): Flow<List<Contact>> = cacheUseGate.use { contactsFlow.observe() }

    fun upsert(contact: Contact) = cacheUseGate.use {
        validateContact(contact)
        synchronized(stateLock) {
            var userMerge: UserProjectionMerge? = null
            dao.transaction {
                dao.persistContact(contact)
                userMerge = contact.user?.let(mergeUserLocked)
            }
            userMerge?.let(publishUserMergeLocked)
            if (contact.status == ACTIVE_CONTACT_STATUS) {
                contactsByFriendUid[contact.friendUid] = contact.copy(user = dao.getUser(contact.friendUid))
            } else {
                contactsByFriendUid.remove(contact.friendUid)
            }
            publishLocked()
            markMutatedLocked(contact.friendUid)
        }
    }

    fun delete(friendUid: String) = cacheUseGate.use {
        synchronized(stateLock) {
            dao.deleteContact(friendUid)
            if (contactsByFriendUid.remove(friendUid) != null) publishLocked()
            // 即使没有行也保留墓碑：一个更旧的 RPC 仍可能携带该关系。
            markMutatedLocked(friendUid)
        }
    }

    fun generation(): Long = cacheUseGate.use {
        synchronized(stateLock) { projectionGeneration }
    }

    fun applySnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean =
        cacheUseGate.runIfOpen {
            require(expectedGeneration >= 0L) { "expectedGeneration 不能为负数" }
            val snapshot = normalizeContacts(contacts)
            synchronized(stateLock) {
                if (projectionGeneration == expectedGeneration) {
                    persistSnapshotLocked(snapshot, replace = true)
                    projectionGeneration = nextGenerationLocked()
                    lastFullSnapshotGeneration = projectionGeneration
                    mutationGenerations.clear()
                    true
                } else {
                    val mergeable = if (expectedGeneration < lastFullSnapshotGeneration) {
                        emptyList()
                    } else {
                        snapshot.filter { contact ->
                            (mutationGenerations[contact.friendUid] ?: 0L) <= expectedGeneration
                        }
                    }
                    if (mergeable.isNotEmpty()) {
                        persistSnapshotLocked(mergeable, replace = false)
                        projectionGeneration = nextGenerationLocked()
                        val mergedGeneration = projectionGeneration
                        mergeable.forEach { mutationGenerations[it.friendUid] = mergedGeneration }
                    }
                    false
                }
            }
        }

    /** 调用方持有 [stateLock]。 */
    fun publishUsersLocked(users: Map<String, User>) {
        var changed = false
        users.forEach { (uid, user) ->
            contactsByFriendUid[uid]?.let { contact ->
                if (contact.user != user) {
                    contactsByFriendUid[uid] = contact.copy(user = user)
                    changed = true
                }
            }
        }
        if (changed) publishLocked()
    }

    /** 调用方持有 [stateLock]。 */
    fun containsFriendLocked(uid: String): Boolean = contactsByFriendUid.containsKey(uid)

    /** 调用方持有 [stateLock]；SQL reset 由 LocalCacheImpl 拥有。 */
    fun clearProjectionLocked() {
        contactsByFriendUid.clear()
        contactsFlow.value = emptyList()
        projectionGeneration = nextGenerationLocked()
        lastFullSnapshotGeneration = projectionGeneration
        mutationGenerations.clear()
    }

    /** 调用方持有 [stateLock]；检查点行已经提交。 */
    fun reloadCheckpointProjectionLocked() {
        replaceContactsLocked(dao.loadContacts())
        projectionGeneration = nextGenerationLocked()
        lastFullSnapshotGeneration = projectionGeneration
        mutationGenerations.clear()
    }

    /** 调用方持有 [stateLock]。 */
    fun closeResidentLocked() {
        contactsByFriendUid.clear()
        contactsFlow.value = emptyList()
        contactsFlow.retire()
    }

    /** 调用方持有 [stateLock]。 */
    fun residentCountLocked(): Int = contactsByFriendUid.size

    /** 调用方持有 [stateLock]。 */
    private fun persistSnapshotLocked(snapshot: List<Contact>, replace: Boolean) {
        val embeddedUids = snapshot.mapNotNull(Contact::user).mapTo(linkedSetOf(), User::uid)
        val userMerges = mutableListOf<UserProjectionMerge>()
        dao.transaction {
            if (replace) dao.deleteAllContacts()
            snapshot.forEach(dao::persistContact)
            snapshot.mapNotNull(Contact::user).distinctBy(User::uid)
                .mapTo(userMerges, mergeUserLocked)
        }
        userMerges.forEach(publishUserMergeLocked)
        refreshEmbeddedUsersLocked(embeddedUids)
        // 重载完整关系仍是一次联接查询，并且同样让过期合并发布与在其逐 key 隔断之后存活的行
        // 精确对齐。
        replaceContactsLocked(dao.loadContacts())
    }

    private fun replaceContactsLocked(contacts: List<Contact>) {
        contactsByFriendUid.clear()
        contacts.forEach { contact -> contactsByFriendUid[contact.friendUid] = contact }
        publishLocked()
    }

    private fun publishLocked() {
        contactsFlow.value = contactsByFriendUid.values.toList()
    }

    private fun normalizeContacts(contacts: List<Contact>): List<Contact> =
        LinkedHashMap<String, Contact>().apply {
            contacts.forEach { contact ->
                validateContact(contact)
                put(contact.friendUid, contact)
            }
        }.values.toList()

    private fun validateContact(contact: Contact) {
        contact.user?.let { user ->
            require(user.uid == contact.friendUid) { "contact embedded user identity mismatch" }
        }
    }

    private fun markMutatedLocked(friendUid: String) {
        projectionGeneration = nextGenerationLocked()
        mutationGenerations[friendUid] = projectionGeneration
    }

    private fun nextGenerationLocked(): Long {
        check(projectionGeneration < Long.MAX_VALUE) { "contact projection generation exhausted" }
        return projectionGeneration + 1L
    }

    private companion object {
        const val ACTIVE_CONTACT_STATUS = 1
    }
}
