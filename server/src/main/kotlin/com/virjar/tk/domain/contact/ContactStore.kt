package com.virjar.tk.domain.contact

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import java.util.concurrent.ConcurrentHashMap

/**
 * 联系人领域热缓存。
 *
 * 缓存好友 UID 集合（用于快速 isFriend 判断）。
 * 好友申请和黑名单为低频操作，直接委托 Repository。
 */
class ContactStore(private val repo: ContactRepository) {
    private val friendUids = ConcurrentHashMap<String, MutableSet<String>>()
    private val friendLoaded = ConcurrentHashMap<String, Boolean>()

    // ── 好友读操作（缓存优先） ──

    fun isFriend(uid: String, friendUid: String): Boolean {
        ensureFriendsLoaded(uid)
        return friendUids[uid]?.contains(friendUid) == true
    }

    fun getFriendUids(uid: String): Set<String> {
        ensureFriendsLoaded(uid)
        return friendUids[uid]?.toSet() ?: emptySet()
    }

    // ── 好友写操作 ──

    /** 内存添加双向好友（不写 DB）。用于 acceptApply 后更新缓存 */
    fun addFriendPair(uid1: String, uid2: String) {
        friendUids.getOrPut(uid1) { ConcurrentHashMap.newKeySet() }.add(uid2)
        friendUids.getOrPut(uid2) { ConcurrentHashMap.newKeySet() }.add(uid1)
    }

    fun removeFriendPair(uid1: String, uid2: String) {
        friendUids[uid1]?.remove(uid2)
        friendUids[uid2]?.remove(uid1)
    }

    // ── 委托 Repository 的操作 ──

    fun listFriends(uid: String): List<Contact> = repo.listFriends(uid)

    fun addFriend(uid: String, friendUid: String, remark: String? = null) {
        repo.addFriend(uid, friendUid, remark)
        addFriendPair(uid, friendUid)
    }

    fun removeFriend(uid: String, friendUid: String) {
        repo.removeFriend(uid, friendUid)
        removeFriendPair(uid, friendUid)
    }

    fun setRemark(uid: String, friendUid: String, remark: String?) =
        repo.setRemark(uid, friendUid, remark)

    fun blacklist(uid: String, targetUid: String) {
        repo.blacklist(uid, targetUid)
        friendUids[uid]?.remove(targetUid)
    }

    fun removeFromBlacklist(uid: String, targetUid: String) =
        repo.removeFromBlacklist(uid, targetUid)

    fun listBlacklist(uid: String): List<Contact> = repo.listBlacklist(uid)

    // ── 好友申请（纯 DB，不缓存） ──

    fun createApply(fromUid: String, toUid: String, remark: String?): ContactApply =
        repo.createApply(fromUid, toUid, remark)

    fun acceptApply(token: String): ContactApply? {
        val apply = repo.acceptApply(token) ?: return null
        addFriendPair(apply.fromUid, apply.toUid)
        return apply
    }

    fun rejectApply(token: String): ContactApply? = repo.rejectApply(token)

    fun listPendingApplies(uid: String): List<ContactApply> = repo.listPendingApplies(uid)

    // ── 内部方法 ──

    private fun ensureFriendsLoaded(uid: String) {
        if (friendLoaded[uid] == true) return
        loadFriends(uid)
    }

    private fun loadFriends(uid: String) {
        if (friendLoaded[uid] == true) return
        val friends = repo.listFriends(uid)
        if (friends.isNotEmpty()) {
            friendUids.getOrPut(uid) { ConcurrentHashMap.newKeySet() }
                .addAll(friends.map { it.friendUid })
        }
        friendLoaded[uid] = true
    }
}
