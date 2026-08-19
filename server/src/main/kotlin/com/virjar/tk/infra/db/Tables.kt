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
    val peerReadSeq = long("peer_read_seq").default(0)
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

/** 单组织目录节点。groupChatId 非空时，该群的成员由组织领域维护。 */
object OrganizationUnits : Table("organization_units") {
    val unitId = varchar("unit_id", 36)
    val parentId = varchar("parent_id", 36).nullable().index()
    val name = varchar("name", 120)
    val leaderUid = varchar("leader_uid", 36).nullable().index()
    val sortOrder = integer("sort_order").default(0)
    val groupChatId = varchar("group_chat_id", 36).nullable().uniqueIndex()
    val status = integer("status").default(1)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(unitId)
}

/** 用户可以属于多个部门，但同一用户最多有一个 primary 归属（Repository 写入时收敛）。 */
object OrganizationMemberships : LongIdTable("organization_memberships") {
    val unitId = varchar("unit_id", 36).index()
    val uid = varchar("uid", 36).index()
    val title = varchar("title", 120).nullable()
    val primary = bool("is_primary").default(false)
    val joinedAt = long("joined_at")
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("idx_org_member_unit_uid", unitId, uid)
    }
}

/** 通知机器人应用。只保存高熵 webhook token 的 SHA-256，不保存可恢复密钥。 */
object AutomationBots : Table("automation_bots") {
    val botId = varchar("bot_id", 36)
    val userUid = varchar("user_uid", 36).uniqueIndex()
    val name = varchar("name", 100)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val status = integer("status").default(1)
    val lastUsedAt = long("last_used_at").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(botId)
}

object AutomationBotGrants : LongIdTable("automation_bot_grants") {
    val botId = varchar("bot_id", 36).index()
    val chatId = varchar("chat_id", 36).index()
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_bot_grant_bot_chat", botId, chatId)
    }
}

/** 群共享文件的逻辑目录树；文件内容本身仍由 FileStore 保存。 */
object GroupFileEntries : Table("group_file_entries") {
    val entryId = varchar("entry_id", 36)
    val chatId = varchar("chat_id", 36).index()
    val parentId = varchar("parent_id", 36).nullable().index()
    /** null 父级统一写为空串，用于根目录唯一约束。 */
    val parentKey = varchar("parent_key", 36).default("")
    val kind = integer("kind")
    val name = varchar("name", 180)
    /** 活跃条目为规范化名称；删除后追加 entryId，允许用户再次使用原名称。 */
    val nameKey = varchar("name_key", 260)
    val attachmentPath = varchar("attachment_path", 500).nullable().index()
    val attachmentName = varchar("attachment_name", 500).nullable()
    val attachmentContentType = varchar("attachment_content_type", 200).nullable()
    val attachmentSize = long("attachment_size").nullable()
    val revision = long("revision").default(1)
    val contentVersion = long("content_version").default(0)
    val status = integer("status").default(1)
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(entryId)

    init {
        uniqueIndex("idx_group_file_sibling_name", chatId, parentKey, nameKey)
    }
}

/** 文件内容版本不可变，只追加；所有版本都参与群空间配额。 */
object GroupFileVersions : LongIdTable("group_file_versions") {
    val entryId = varchar("entry_id", 36).index()
    val version = long("version")
    val attachmentPath = varchar("attachment_path", 500).index()
    val attachmentName = varchar("attachment_name", 500)
    val attachmentContentType = varchar("attachment_content_type", 200)
    val attachmentSize = long("attachment_size")
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_group_file_entry_version", entryId, version)
    }
}

/** 共享文件的最小审计轨迹，不记录文件内容。 */
object GroupFileAudits : LongIdTable("group_file_audits") {
    val chatId = varchar("chat_id", 36).index()
    val entryId = varchar("entry_id", 36).nullable().index()
    val actorUid = varchar("actor_uid", 36).index()
    val action = varchar("action", 32)
    val detail = varchar("detail", 500).nullable()
    val createdAt = long("created_at").index()
}

/** 文档当前快照；正文历史在 DocumentRevisions 中只追加保存。 */
object Documents : Table("documents") {
    val documentId = varchar("document_id", 36)
    val scopeType = integer("scope_type")
    val scopeId = varchar("scope_id", 36).index()
    val title = varchar("title", 180)
    val markdown = text("markdown")
    val revision = long("revision").default(1)
    val status = integer("status").default(1)
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at").index()

    override val primaryKey = PrimaryKey(documentId)

    init {
        index("idx_document_scope", false, scopeType, scopeId)
    }
}

/** 完整、不可变的文档修订快照。 */
object DocumentRevisions : LongIdTable("document_revisions") {
    val documentId = varchar("document_id", 36).index()
    val revision = long("revision")
    val title = varchar("title", 180)
    val markdown = text("markdown")
    val editedBy = varchar("edited_by", 36)
    val editedAt = long("edited_at")

    init {
        uniqueIndex("idx_document_revision", documentId, revision)
    }
}
