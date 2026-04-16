package com.virjar.tk.db

import org.jetbrains.exposed.dao.id.LongIdTable

object Users : LongIdTable("users") {
    val uid = varchar("uid", 32).uniqueIndex()
    val username = varchar("username", 64).nullable().uniqueIndex()
    val name = varchar("name", 64)
    val phone = varchar("phone", 20).nullable()
    val zone = varchar("zone", 10).default("86")
    val passwordHash = varchar("password_hash", 128)
    val avatar = varchar("avatar", 256).default("")
    val sex = integer("sex").default(0)
    val shortNo = varchar("short_no", 20).nullable().uniqueIndex()
    val status = integer("status").default(1)
    val role = integer("role").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object Devices : LongIdTable("devices") {
    val uid = varchar("uid", 32)
    val deviceId = varchar("device_id", 64)
    val deviceName = varchar("device_name", 128).default("")
    val deviceModel = varchar("device_model", 128).default("")
    val deviceFlag = integer("device_flag").default(0)
    val lastLogin = long("last_login").nullable()
    val token = varchar("token", 128).nullable()
    init { uniqueIndex("uid_device_idx", uid, deviceId) }
}

object Tokens : LongIdTable("tokens") {
    val uid = varchar("uid", 32).index()
    val refreshToken = varchar("refresh_token", 128).uniqueIndex()
    val deviceFlag = integer("device_flag").default(0)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
}

object Channels : LongIdTable("channels") {
    val channelId = varchar("channel_id", 64).uniqueIndex()
    val channelType = integer("channel_type")  // 1=personal, 2=group, 3=system
    val name = varchar("name", 128).default("")
    val avatar = varchar("avatar", 256).default("")
    val creator = varchar("creator", 32).nullable()
    val notice = text("notice").default("")
    val status = integer("status").default(1)
    val maxSeq = long("max_seq").default(0)
    val mutedAll = bool("muted_all").default(false)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object ChannelMemberMutes : LongIdTable("channel_member_mutes") {
    val channelId = varchar("channel_id", 64)
    val uid = varchar("uid", 32)
    val operatorUid = varchar("operator_uid", 32)
    val expiresAt = long("expires_at") // 过期时间戳(ms), 0=永久
    val createdAt = long("created_at")
    init { uniqueIndex("channel_mute_idx", channelId, uid) }
}

object ChannelMembers : LongIdTable("channel_members") {
    val channelId = varchar("channel_id", 64).index()
    val channelType = integer("channel_type")
    val uid = varchar("uid", 32).index()
    val role = integer("role").default(0)  // 0=member, 1=admin, 2=owner
    val nickname = varchar("nickname", 64).default("")
    val status = integer("status").default(1)
    val joinedAt = long("joined_at")
    init { uniqueIndex("channel_member_idx", channelId, uid) }
}

object Conversations : LongIdTable("conversations") {
    val uid = varchar("uid", 32).index()
    val channelId = varchar("channel_id", 64)
    val channelType = integer("channel_type")
    val lastMsgSeq = long("last_msg_seq").default(0)
    val unreadCount = integer("unread_count").default(0)
    val readSeq = long("read_seq").default(0)
    val isMuted = bool("is_muted").default(false)
    val isPinned = bool("is_pinned").default(false)
    val draft = text("draft").default("")
    val version = long("version").default(0)
    val updatedAt = long("updated_at")
    init { uniqueIndex("user_channel_idx", uid, channelId) }
}

object Friends : LongIdTable("friends") {
    val uid = varchar("uid", 32).index()
    val friendUid = varchar("friend_uid", 32).index()
    val remark = varchar("remark", 64).default("")
    val status = integer("status").default(1)  // 1=normal, 2=blocked
    val version = long("version").default(0)
    val createdAt = long("created_at")
    init { uniqueIndex("friend_idx", uid, friendUid) }
}

object FriendApplies : LongIdTable("friend_applies") {
    val fromUid = varchar("from_uid", 32).index()
    val toUid = varchar("to_uid", 32).index()
    val token = varchar("token", 64).uniqueIndex()
    val remark = varchar("remark", 256).default("")
    val status = integer("status").default(0)  // 0=pending, 1=accepted, 2=rejected
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object GroupInviteLinks : LongIdTable("group_invite_links") {
    val token = varchar("token", 8).uniqueIndex()
    val channelId = varchar("channel_id", 64).index()
    val creatorUid = varchar("creator_uid", 32)
    val name = varchar("name", 64).nullable()
    val maxUses = integer("max_uses").nullable()
    val useCount = integer("use_count").default(0)
    val expiresAt = long("expires_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    val createdAt = long("created_at")
}
