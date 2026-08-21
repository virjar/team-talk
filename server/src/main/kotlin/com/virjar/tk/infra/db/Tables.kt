package com.virjar.tk.infra.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and

/** Single-row marker for the disposable pre-release database schema. */
object SchemaMetadata : Table("schema_metadata") {
    val id = integer("id")
    val epoch = integer("epoch")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

internal const val USERS_PHONE_UNIQUE_INDEX = "users_phone_unique"

object Users : LongIdTable("users") {
    val uid = varchar("uid", 36).uniqueIndex()
    val username = varchar("username", 50).uniqueIndex()
    val name = varchar("name", 100)
    val phone = varchar("phone", 20).nullable().uniqueIndex(USERS_PHONE_UNIQUE_INDEX)
    val zone = varchar("zone", 10).default("+86")
    val passwordHash = varchar("password_hash", 100)
    val avatar = varchar("avatar", 500).nullable()
    val sex = integer("sex").default(0)
    val shortNo = varchar("short_no", 20).nullable().uniqueIndex()
    val status = integer("status").default(1)
    val role = integer("role").default(0)
    /** Incremented whenever every credential for this user must become permanently invalid. */
    val credentialEpoch = long("credential_epoch").default(1)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        check("ck_users_status") { (status greaterEq 1) and (status lessEq 2) }
        check("ck_users_credential_epoch_positive") { credentialEpoch greater 0L }
    }
}

object Devices : LongIdTable("devices") {
    val uid = varchar("uid", 36).index()
    val deviceId = varchar("device_id", 100)
    val deviceName = varchar("device_name", 200).nullable()
    val deviceModel = varchar("device_model", 200).nullable()
    val deviceFlag = integer("device_flag").default(0)
    val status = integer("status").default(1)
    /** Every credential-pair rotation and revocation advances this value. */
    val credentialEpoch = long("credential_epoch").default(1)
    val lastLogin = long("last_login").default(0)
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_device_uid_id", uid, deviceId)
        check("ck_devices_status") { (status greaterEq 1) and (status lessEq 2) }
        check("ck_devices_credential_epoch_positive") { credentialEpoch greater 0L }
    }
}

/** Hash-only access and refresh credentials. Raw bearer secrets never enter durable storage. */
object Credentials : Table("credentials") {
    val tokenHash = varchar("token_hash", 64)
    val tokenType = integer("token_type")
    val uid = varchar("uid", 36).references(Users.uid)
    val deviceId = varchar("device_id", 100)
    val deviceFlag = integer("device_flag")
    val userCredentialEpoch = long("user_credential_epoch")
    val deviceCredentialEpoch = long("device_credential_epoch")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(tokenHash)

    init {
        index("idx_credentials_uid_device", false, uid, deviceId)
        index("idx_credentials_expires_at", false, expiresAt)
        check("ck_credentials_token_type") { (tokenType greaterEq 1) and (tokenType lessEq 2) }
        check("ck_credentials_user_epoch_positive") { userCredentialEpoch greater 0L }
        check("ck_credentials_device_epoch_positive") { deviceCredentialEpoch greater 0L }
    }
}

object Chats : LongIdTable("chats") {
    val chatId = varchar("chat_id", 36).uniqueIndex()
    val chatType = integer("chat_type")  // 1=personal, 2=group
    /** Canonical sorted uid pair for personal chats; null for group chats. */
    val personalKey = varchar("personal_key", 80).nullable().uniqueIndex()
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
        uniqueIndex(
            "uq_group_members_active_owner",
            chatId,
            filterCondition = { (status eq 1) and (role eq 2) },
        )
    }
}

object GroupMemberMutes : LongIdTable("group_member_mutes") {
    val chatId = varchar("chat_id", 36).index()
    val uid = varchar("uid", 36)
    val operatorUid = varchar("operator_uid", 36)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")

    init {
        uniqueIndex("uq_group_member_mute_chat_uid", chatId, uid)
    }
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
    // Markdown 源码草稿必须与可发送正文使用同一容量契约；VARCHAR(500) 会静默截断
    // 代码块、表格等高频办公内容。项目尚未发布，测试实例可直接重建该列/数据。
    val draft = text("draft").nullable()
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

    init {
        uniqueIndex(
            "uq_friend_applies_pending_direction",
            fromUid,
            toUid,
            filterCondition = { status eq 0 },
        )
    }
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

    init {
        check("ck_group_invite_links_max_uses_non_negative") { maxUses greaterEq 0 }
        check("ck_group_invite_links_expires_at_non_negative") { expiresAt greaterEq 0L }
    }
}

/** Per-user durable event sequence allocator. A row is locked only after all domain writes finish. */
object SyncStreams : Table("sync_streams") {
    val uid = varchar("uid", 36).references(Users.uid)
    val lastSeq = long("last_seq").default(0)

    override val primaryKey = PrimaryKey(uid)

    init {
        check("ck_sync_streams_last_seq_non_negative") { lastSeq greaterEq 0L }
    }
}

/**
 * Durable user event log. [streamSeq] is exposed as the existing wire `eventId` and is contiguous
 * only inside one authenticated uid; two users may legitimately have the same numeric event ID.
 */
object SyncEvents : Table("sync_events") {
    val uid = varchar("uid", 36).references(SyncStreams.uid)
    val streamSeq = long("stream_seq")
    val eventType = integer("event_type")
    val payload = binary("payload")
    val dedupeKey = varchar("dedupe_key", 192).nullable()
    val createdAt = long("created_at")
    /** Live delivery is an optimization; replay always reads rows regardless of this marker. */
    val dispatchedAt = long("dispatched_at").nullable()
    val dispatchAttempts = integer("dispatch_attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").default(0)
    val lastDispatchError = text("last_dispatch_error").nullable()

    override val primaryKey = PrimaryKey(uid, streamSeq)

    init {
        uniqueIndex("uq_sync_events_uid_dedupe", uid, dedupeKey)
        index(
            "idx_sync_events_dispatch",
            false,
            nextAttemptAt,
            uid,
            streamSeq,
            filterCondition = { dispatchedAt.isNull() },
        )
        check("ck_sync_events_stream_seq_positive") { streamSeq greater 0L }
        check("ck_sync_events_dispatch_attempts_non_negative") { dispatchAttempts greaterEq 0 }
        check("ck_sync_events_next_attempt_non_negative") { nextAttemptAt greaterEq 0L }
    }
}

/**
 * Idempotency receipt for a projection whose authoritative operation lives in an external store.
 * The stable projection key identifies one message; revision identifies CREATE/EDIT/REVOKE.
 */
object ExternalProjectionReceipts : Table("external_projection_receipts") {
    val projectionKey = varchar("projection_key", 512)
    val revision = long("revision")
    val operationType = integer("operation_type")
    val chatId = varchar("chat_id", 160)
    val serverSeq = long("server_seq")
    val payloadHash = binary("payload_hash")
    val appliedAt = long("applied_at")

    override val primaryKey = PrimaryKey(projectionKey, revision)

    init {
        check("ck_external_projection_revision_positive") { revision greater 0L }
        check("ck_external_projection_server_seq_positive") { serverSeq greater 0L }
    }
}

/** Global monotonic revision for every organization fact mutation. */
object OrganizationState : Table("organization_state") {
    val id = integer("id")
    val revision = long("revision").default(0)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        check("ck_organization_state_singleton") { id eq 1 }
        check("ck_organization_state_revision_non_negative") { revision greaterEq 0L }
    }
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

    init {
        check("ck_organization_units_sort_order_non_negative") { sortOrder greaterEq 0 }
    }
}

/** 用户可以属于多个部门，但同一用户最多有一个 primary 归属。 */
object OrganizationMemberships : LongIdTable("organization_memberships") {
    val unitId = varchar("unit_id", 36).index()
    val uid = varchar("uid", 36).index()
    val title = varchar("title", 120).nullable()
    val primary = bool("is_primary").default(false)
    val joinedAt = long("joined_at")
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("idx_org_member_unit_uid", unitId, uid)
        uniqueIndex(
            "uq_org_membership_primary_uid",
            uid,
            filterCondition = { primary eq true },
        )
    }
}

/**
 * Durable desired/applied state for organization-owned chats.
 *
 * Rows are never deleted when a unit disables its chat or is archived: the negative desired
 * state is the fence which prevents an older positive reconciliation from resurrecting access.
 */
object OrganizationManagedChatProjections : Table("organization_managed_chat_projections") {
    val unitId = varchar("unit_id", 36)
    val chatId = varchar("chat_id", 36).uniqueIndex()
    val desiredRevision = long("desired_revision")
    val appliedRevision = long("applied_revision").default(0)
    val desiredActive = bool("desired_active")
    val attemptCount = integer("attempt_count").default(0)
    val nextAttemptAt = long("next_attempt_at").default(0)
    val lastFailure = varchar("last_failure", 1000).nullable()
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(unitId)

    init {
        index(
            "idx_org_managed_chat_projection_pending",
            false,
            nextAttemptAt,
            desiredRevision,
            unitId,
        )
        check("ck_org_managed_chat_desired_revision_positive") { desiredRevision greater 0L }
        check("ck_org_managed_chat_applied_revision_non_negative") { appliedRevision greaterEq 0L }
        check("ck_org_managed_chat_applied_not_ahead") { appliedRevision lessEq desiredRevision }
        check("ck_org_managed_chat_attempts_non_negative") { attemptCount greaterEq 0 }
        check("ck_org_managed_chat_next_attempt_non_negative") { nextAttemptAt greaterEq 0L }
    }
}

/** 通知机器人应用。只保存高熵 webhook token 的 SHA-256，不保存可恢复密钥。 */
object AutomationBots : Table("automation_bots") {
    val botId = varchar("bot_id", 36)
    val userUid = varchar("user_uid", 36).uniqueIndex()
    val name = varchar("name", 100)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val status = integer("status").default(1)
    val managedChatId = varchar("managed_chat_id", 36).nullable().index()
    val createdByUid = varchar("created_by_uid", 36).nullable().index()
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

/** 企业文档空间；权限和目录树都以 spaceId 为根。 */
object DocumentSpaces : Table("document_spaces") {
    val spaceId = varchar("space_id", 36)
    val name = varchar("name", 120)
    val description = varchar("description", 500).nullable()
    val status = integer("status").default(1)
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").index()

    override val primaryKey = PrimaryKey(spaceId)

    init {
        index("idx_document_space_owner_active", false, createdBy, status)
    }
}

/** 用户或组织部门对文档空间的授权。空间所有者由 DocumentSpaces.createdBy 表示。 */
object DocumentSpaceGrants : LongIdTable("document_space_grants") {
    val spaceId = varchar("space_id", 36).index()
    val principalType = integer("principal_type")
    val principalId = varchar("principal_id", 36).index()
    val role = integer("role")
    val includeDescendants = bool("include_descendants").default(false)
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("idx_document_space_principal", spaceId, principalType, principalId)
    }
}

/** 空间目录树和文档当前快照；文件夹的 markdown 为 null。 */
object DocumentNodes : Table("document_nodes") {
    val nodeId = varchar("node_id", 36)
    val spaceId = varchar("space_id", 36).index()
    val parentId = varchar("parent_id", 36).nullable().index()
    val nodeType = integer("node_type")
    val name = varchar("name", 180)
    /** 列表与首页投影使用的有界摘要，避免读取最大 1MB 的 Markdown 正文。 */
    val excerpt = varchar("excerpt", 500).default("")
    val markdown = text("markdown").nullable()
    val revision = long("revision").default(1)
    val status = integer("status").default(1)
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at").index()

    override val primaryKey = PrimaryKey(nodeId)

    init {
        index("idx_document_node_parent", false, spaceId, parentId, status)
        index("idx_document_node_created", false, spaceId, nodeType, status, createdAt)
    }
}

/** 完整、不可变的文档修订快照。 */
object DocumentContentRevisions : LongIdTable("document_content_revisions") {
    val documentId = varchar("document_id", 36).index()
    val revision = long("revision")
    val title = varchar("title", 180)
    val markdown = text("markdown")
    val editedBy = varchar("edited_by", 36)
    val editedAt = long("edited_at")

    init {
        uniqueIndex("idx_document_content_revision", documentId, revision)
    }
}

/** 每个用户最近打开的文档；一篇文档只保留该用户最后一次访问时间。 */
object DocumentUserRecents : Table("document_user_recents") {
    val uid = varchar("uid", 36)
    val documentId = varchar("document_id", 36)
    val accessedAt = long("accessed_at")

    override val primaryKey = PrimaryKey(uid, documentId)

    init {
        index("idx_document_user_recents_order", false, uid, accessedAt)
        index("idx_document_user_recents_document", false, documentId)
    }
}
