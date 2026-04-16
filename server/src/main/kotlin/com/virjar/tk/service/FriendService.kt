package com.virjar.tk.service

import com.virjar.tk.api.BusinessException
import com.virjar.tk.db.FriendApplyRow
import com.virjar.tk.db.FriendRow
import com.virjar.tk.dto.*
import com.virjar.tk.protocol.ContactErrorCode
import com.virjar.tk.store.ContactStore
import com.virjar.tk.store.UserStore
import io.ktor.http.*
import org.slf4j.LoggerFactory

class FriendService(
    private val contactStore: ContactStore,
    private val userStore: UserStore,
) {
    private val logger = LoggerFactory.getLogger(FriendService::class.java)

    suspend fun apply(fromUid: String, req: ApplyFriendRequest): FriendApplyDto {
        if (fromUid == req.toUid) throw BusinessException(ContactErrorCode.APPLY_SELF, "cannot add yourself as friend")
        if (contactStore.isFriend(fromUid, req.toUid)) throw BusinessException(ContactErrorCode.ALREADY_FRIENDS, "already friends", HttpStatusCode.Conflict)
        val apply = contactStore.createApply(fromUid, req.toUid, req.remark)
        logger.info("Friend apply: {} -> {}", fromUid, req.toUid)
        return apply.toResponse()
    }

    suspend fun accept(token: String): FriendApplyDto {
        val apply = contactStore.acceptApply(token) ?: throw BusinessException(ContactErrorCode.APPLY_NOT_FOUND, "invalid or expired apply token")
        logger.info("Friend accepted: {} <-> {}", apply.fromUid, apply.toUid)
        return apply.toResponse()
    }

    suspend fun reject(token: String) {
        if (!contactStore.rejectApply(token)) throw BusinessException(ContactErrorCode.APPLY_NOT_FOUND, "invalid apply token")
    }

    suspend fun getFriends(uid: String, version: Long = 0): List<FriendDto> {
        return contactStore.getFriends(uid, version).map { it.toResponse() }
    }

    suspend fun updateRemark(uid: String, friendUid: String, remark: String) {
        contactStore.updateRemark(uid, friendUid, remark)
    }

    suspend fun deleteFriend(uid: String, friendUid: String) {
        contactStore.removeFriend(uid, friendUid)
        contactStore.removeFriend(friendUid, uid)
        logger.info("Friend removed: {} <-> {}", uid, friendUid)
    }

    suspend fun getBlacklist(uid: String): List<BlacklistDto> {
        return contactStore.getBlacklist(uid).map { row ->
            val user = userStore.findByUid(row.friendUid)
            BlacklistDto(
                uid = row.friendUid,
                name = user?.name ?: "",
                username = user?.username ?: "",
            )
        }
    }

    suspend fun addBlacklist(uid: String, targetUid: String) {
        contactStore.blockUser(uid, targetUid)
        logger.info("User {} blacklisted {}", uid, targetUid)
    }

    suspend fun removeBlacklist(uid: String, targetUid: String) {
        contactStore.unblockUser(uid, targetUid)
        logger.info("User {} unblacklisted {}", uid, targetUid)
    }

    suspend fun getApplies(uid: String, page: Int = 1): List<FriendApplyDto> {
        return contactStore.getApplies(uid, page).map { it.toResponse() }
    }

    private fun FriendRow.toResponse(): FriendDto {
        val friendUser = userStore.findByUid(friendUid)
        return FriendDto(
            uid = uid,
            friendUid = friendUid,
            friendName = friendUser?.name ?: "",
            friendUsername = friendUser?.username ?: "",
            remark = remark,
            status = status,
            version = version,
        )
    }

    private fun FriendApplyRow.toResponse(): FriendApplyDto {
        val fromUser = userStore.findByUid(fromUid)
        return FriendApplyDto(
            fromUid = fromUid,
            fromName = fromUser?.name ?: "",
            toUid = toUid,
            token = token,
            remark = remark,
            status = status,
            createdAt = createdAt,
        )
    }
}
