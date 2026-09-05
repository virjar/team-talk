package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.shared.database.SelectContactsWithUsers
import com.virjar.tk.shared.database.SelectMemberWithUser
import com.virjar.tk.shared.database.SelectMembersWithUsersByChat
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.User

internal data class UserProjectionMerge(
    val canonical: User,
    val advanced: Boolean,
)

/** 相等修订号只意味着幂等；内容冲突的相等修订号保留已存储行。 */
internal fun mergeUserProjection(stored: User?, incoming: User): UserProjectionMerge = when {
    stored == null -> UserProjectionMerge(incoming, advanced = true)
    incoming.revision > stored.revision -> UserProjectionMerge(incoming, advanced = true)
    else -> UserProjectionMerge(stored, advanced = false)
}

/**
 * 规范化 user/contact/chat/member 投影的 SQL 边界。
 *
 * 按 key 的实体按主键读取。这里刻意暴露的唯一列表投影是活跃联系人目录与恰好一个 chat 的成员；
 * 两者都在同一条 SQL 查询中组装其 User 摘要。
 */
internal class LocalEntityProjectionDao(
    private val queries: AppDatabaseQueries,
) {
    fun getUser(uid: String): User? =
        queries.selectUserByUid(uid).executeAsOneOrNull()?.toLocalModel()

    fun isUserLocallyRelevant(uid: String): Boolean =
        queries.isUserLocallyRelevant(uid).executeAsOne() != 0L

    fun getChat(chatId: String): Chat? =
        queries.selectChatById(chatId).executeAsOneOrNull()?.toLocalModel()

    fun loadContacts(): List<Contact> =
        queries.selectContactsWithUsers().executeAsList().map { it.toModel() }

    fun loadMembers(chatId: String): List<Member> =
        queries.selectMembersWithUsersByChat(chatId).executeAsList()
            .map { it.toModel() }

    fun loadOrganizationMembersForCheckpoint(): List<OrganizationMember> =
        queries.selectAllOrganizationMembersForCheckpoint().executeAsList().map { it.toLocalModel() }

    fun getMember(chatId: String, uid: String): Member? =
        queries.selectMemberWithUser(chatId, uid).executeAsOneOrNull()?.toModel()

    fun persistUser(user: User) {
        val avatar = user.avatar
        queries.upsertUser(
            user.uid,
            user.username,
            user.name,
            avatar?.path,
            avatar?.name,
            avatar?.contentType,
            avatar?.size,
            user.phone,
            user.sex.toLong(),
            user.role.toLong(),
            user.status.toLong(),
            user.revision,
        )
    }

    fun persistContact(contact: Contact) {
        queries.upsertContact(contact.uid, contact.friendUid, contact.remark, contact.status.toLong())
    }

    fun deleteContact(friendUid: String) = queries.deleteContact(friendUid)
    fun deleteAllContacts() = queries.deleteAllContacts()

    fun deleteAllUsers() = queries.deleteAllUsers()

    fun persistChat(chat: Chat) {
        queries.upsertChat(
            chat.chatId,
            chat.chatType.toLong(),
            chat.name,
            chat.avatar,
            chat.creator,
            chat.memberCount.toLong(),
            chat.maxSeq,
            chat.notice,
            if (chat.mutedAll) 1L else 0L,
        )
    }

    fun deleteAllChats() = queries.deleteAllChats()

    fun persistMember(member: Member) {
        queries.upsertMember(
            member.chatId,
            member.uid,
            member.role.toLong(),
            member.nickname,
            member.joinedAt,
        )
    }

    fun removeMember(chatId: String, uid: String) = queries.removeMember(chatId, uid)
    fun deleteMembers(chatId: String) = queries.deleteMembersByChat(chatId)
    fun deleteAllMembers() = queries.deleteAllMembers()

    fun transaction(block: () -> Unit) = queries.transaction { block() }

    private fun SelectContactsWithUsers.toModel() = Contact(
        uid = contact_owner_uid,
        friendUid = contact_friend_uid,
        remark = contact_remark,
        status = contact_status?.toInt() ?: 1,
        user = joinedUser(
            user_uid,
            user_username,
            user_name,
            user_avatar_path,
            user_avatar_name,
            user_avatar_content_type,
            user_avatar_size,
            user_phone,
            user_sex,
            user_role,
            user_status,
            user_revision,
        ),
    )

    private fun SelectMembersWithUsersByChat.toModel(): Member = joinedMember(
        member_chat_id,
        member_uid,
        member_role,
        member_nickname,
        member_joined_at,
        user_uid,
        user_username,
        user_name,
        user_avatar_path,
        user_avatar_name,
        user_avatar_content_type,
        user_avatar_size,
        user_phone,
        user_sex,
        user_role,
        user_status,
        user_revision,
    )

    private fun SelectMemberWithUser.toModel(): Member = joinedMember(
        member_chat_id,
        member_uid,
        member_role,
        member_nickname,
        member_joined_at,
        user_uid,
        user_username,
        user_name,
        user_avatar_path,
        user_avatar_name,
        user_avatar_content_type,
        user_avatar_size,
        user_phone,
        user_sex,
        user_role,
        user_status,
        user_revision,
    )

    private fun joinedMember(
        chatId: String,
        uid: String,
        role: Long?,
        nickname: String?,
        joinedAt: Long?,
        userUid: String?,
        username: String?,
        name: String?,
        avatarPath: String?,
        avatarName: String?,
        avatarContentType: String?,
        avatarSize: Long?,
        phone: String?,
        sex: Long?,
        userRole: Long?,
        userStatus: Long?,
        userRevision: Long?,
    ) = Member(
        chatId = chatId,
        uid = uid,
        role = role?.toInt() ?: 0,
        nickname = nickname,
        joinedAt = joinedAt ?: 0L,
        user = joinedUser(
            userUid,
            username,
            name,
            avatarPath,
            avatarName,
            avatarContentType,
            avatarSize,
            phone,
            sex,
            userRole,
            userStatus,
            userRevision,
        ),
    )

    private fun joinedUser(
        uid: String?,
        username: String?,
        name: String?,
        avatarPath: String?,
        avatarName: String?,
        avatarContentType: String?,
        avatarSize: Long?,
        phone: String?,
        sex: Long?,
        role: Long?,
        status: Long?,
        revision: Long?,
    ): User? {
        uid ?: return null
        return User(
            uid = uid,
            username = requireNotNull(username) { "Cached user $uid has no username" },
            name = requireNotNull(name) { "Cached user $uid has no name" },
            avatar = storedAttachment(
                avatarPath,
                avatarName,
                avatarContentType,
                avatarSize,
                "Cached user $uid avatar",
            ),
            phone = phone,
            sex = sex?.toInt() ?: 0,
            role = role?.toInt() ?: 0,
            status = status?.toInt() ?: 1,
            revision = requireNotNull(revision) { "Cached user $uid has no revision" },
        )
    }
}
