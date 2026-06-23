package com.virjar.tk.infra.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table

object Users : LongIdTable("users") {
    val uid = varchar("uid", 36).uniqueIndex()
    val username = varchar("username", 50).uniqueIndex()
    val name = varchar("name", 100)
    val phone = varchar("phone", 20).nullable().uniqueIndex()
    val zone = varchar("zone", 10).default("+86")
    val passwordHash = varchar("password_hash", 100)
    val avatar = varchar("avatar", 500).nullable()
    val sex = integer("sex").default(0)
    val shortNo = varchar("short_no", 20).nullable().uniqueIndex()
    val status = integer("status").default(1)
    val role = integer("role").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object Devices : LongIdTable("devices") {
    val uid = varchar("uid", 36).index()
    val deviceId = varchar("device_id", 100)
    val deviceName = varchar("device_name", 200).nullable()
    val deviceModel = varchar("device_model", 200).nullable()
    val deviceFlag = integer("device_flag").default(0)
    val lastLogin = long("last_login").default(0)
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_device_uid_id", uid, deviceId)
    }
}

object Chats : LongIdTable("chats") {
    val chatId = varchar("chat_id", 36).uniqueIndex()
    val chatType = integer("chat_type")  // 1=personal, 2=group
    val maxSeq = long("max_seq").default(0)
    val status = integer("status").default(1)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object GroupChats : Table("group_chats") {
    val chatId = varchar("chat_id", 36).references(Chats.chatId)
    val name = varchar("name", 200).default("")
    val avatar = varchar("avatar", 500).nullable()
    val creator = varchar("creator", 36)
    val notice = varchar("notice", 500).default("")
    val mutedAll = bool("muted_all").default(false)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(chatId)
}

object GroupMembers : LongIdTable("group_members") {
    val chatId = varchar("chat_id", 36).index()
    val chatType = integer("chat_type").default(2)
    val uid = varchar("uid", 36).index()
    val role = integer("role").default(0)  // 0=member, 1=admin, 2=owner
    val nickname = varchar("nickname", 100).nullable()
    val status = integer("status").default(1)
    val joinedAt = long("joined_at")

    init {
        uniqueIndex("idx_member_chat_uid", chatId, uid)
    }
}

object GroupMemberMutes : LongIdTable("group_member_mutes") {
    val chatId = varchar("chat_id", 36).index()
    val uid = varchar("uid", 36)
    val operatorUid = varchar("operator_uid", 36)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
}

object Conversations : LongIdTable("conversations") {
    val uid = varchar("uid", 36).index()
    val chatId = varchar("chat_id", 36)
    val chatType = integer("chat_type")
    val lastMsgSeq = long("last_msg_seq").default(0)
    val lastMessage = varchar("last_message", 500).nullable()
    val lastMessageType = integer("last_message_type").default(0)
    val readSeq = long("read_seq").default(0)
    val isMuted = bool("is_muted").default(false)
    val isPinned = bool("is_pinned").default(false)
    val draft = varchar("draft", 500).nullable()
    val version = long("version").default(0)
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("idx_conv_uid_chat", uid, chatId)
    }
}

object Friends : LongIdTable("friends") {
    val uid = varchar("uid", 36).index()
    val friendUid = varchar("friend_uid", 36)
    val remark = varchar("remark", 100).nullable()
    val status = integer("status").default(1)  // 1=normal, 2=blocked
    val version = long("version").default(0)
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_friend_uid_friend", uid, friendUid)
    }
}

object FriendApplies : LongIdTable("friend_applies") {
    val fromUid = varchar("from_uid", 36).index()
    val toUid = varchar("to_uid", 36).index()
    val token = varchar("token", 36).uniqueIndex()
    val remark = varchar("remark", 200).nullable()
    val status = integer("status").default(0)  // 0=pending, 1=accepted, 2=rejected
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object GroupInviteLinks : LongIdTable("group_invite_links") {
    val token = varchar("token", 36).uniqueIndex()
    val chatId = varchar("chat_id", 36).index()
    val creatorUid = varchar("creator_uid", 36)
    val name = varchar("name", 200).default("")
    val maxUses = integer("max_uses").default(0)
    val useCount = integer("use_count").default(0)
    val expiresAt = long("expires_at").default(0)
    val revokedAt = long("revoked_at").default(0)
    val createdAt = long("created_at")
}

object SyncEvents : LongIdTable("sync_events") {
    val uid = varchar("uid", 36).index()
    val eventType = integer("event_type")
    val payload = binary("payload")
    val createdAt = long("created_at")
}
