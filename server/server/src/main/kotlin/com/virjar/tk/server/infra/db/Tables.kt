package com.virjar.tk.server.infra.db

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.UserAvatarPolicy
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

/** 当前持久化布局与数据集身份；普通升级保留该行。 */
object SchemaMetadata : Table("schema_metadata") {
    val id = integer("id")
    val epoch = integer("epoch")
    /** 每次空数据库初始化都重新生成；绝不从端点或 epoch 推断。 */
    val datasetId = varchar(
        "dataset_id",
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.MAX_LENGTH,
    )
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

internal const val USERS_UID_UNIQUE_INDEX = "users_uid_unique"
internal const val USERS_USERNAME_UNIQUE_INDEX = "users_username_unique"
internal const val USERS_PHONE_UNIQUE_INDEX = "users_phone_unique"

object Users : LongIdTable("users") {
    val uid = varchar("uid", 36).uniqueIndex(USERS_UID_UNIQUE_INDEX)
    val username = varchar("username", 50).uniqueIndex(USERS_USERNAME_UNIQUE_INDEX)
    val name = varchar("name", 100)
    val phone = varchar("phone", 20).nullable().uniqueIndex(USERS_PHONE_UNIQUE_INDEX)
    val zone = varchar("zone", 10).default("+86")
    val passwordHash = varchar("password_hash", 100)
    val avatarPath = varchar("avatar_path", AttachmentPolicy.MAX_REFERENCE_LENGTH).nullable()
        .index("idx_users_avatar_path")
    val avatarName = varchar("avatar_name", AttachmentPolicy.MAX_NAME_LENGTH).nullable()
    val avatarContentType = varchar("avatar_content_type", AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH).nullable()
    val avatarSize = long("avatar_size").nullable()
    val sex = integer("sex").default(0)
    val shortNo = varchar("short_no", 20).nullable().uniqueIndex()
    val status = integer("status").default(1)
    val role = integer("role").default(0)
    /** 外部可见 User 字段的单调版本；仅凭据变更不会推进它。 */
    val revision = long("revision").default(1)
    /** 每当该用户的所有凭据都必须永久失效时递增。 */
    val credentialEpoch = long("credential_epoch").default(1)
    /**
     * 该用户最后全局分配的设备凭据 epoch。
     *
     * Device 行有容量上限，被吊销的行可能被回收。把单调
     * 分配器放在不可驱逐的用户聚合上，可以防止先前被驱逐的 deviceId
     * 以低于进程内凭据 fence 的 epoch 重新出现。
     */
    val deviceCredentialSequence = long("device_credential_sequence").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        check("ck_users_status") { (status greaterEq 1) and (status lessEq 2) }
        check("ck_users_avatar_descriptor_complete") {
            (
                avatarPath.isNull() and avatarName.isNull() and
                    avatarContentType.isNull() and avatarSize.isNull()
                ) or
                (
                    avatarPath.isNotNull() and avatarName.isNotNull() and
                        avatarContentType.isNotNull() and avatarSize.isNotNull()
                    )
        }
        check("ck_users_avatar_size") {
            avatarSize.isNull() or ((avatarSize greaterEq 0L) and (avatarSize lessEq UserAvatarPolicy.MAX_BYTES))
        }
        check("ck_users_avatar_content_type") {
            avatarContentType.isNull() or (avatarContentType inList UserAvatarPolicy.allowedContentTypes.sorted())
        }
        check("ck_users_credential_epoch_positive") { credentialEpoch greater 0L }
        check("ck_users_revision_positive") { revision greater 0L }
        check("ck_users_device_credential_sequence_non_negative") { deviceCredentialSequence greaterEq 0L }
    }
}

object Devices : LongIdTable("devices") {
    val uid = varchar("uid", 36).index()
    val deviceId = varchar("device_id", 100)
    val deviceName = varchar("device_name", 200).nullable()
    val deviceModel = varchar("device_model", 200).nullable()
    val deviceFlag = integer("device_flag").default(0)
    val status = integer("status").default(1)
    /** 每次凭据签发、刷新和吊销都会推进此值。 */
    val credentialEpoch = long("credential_epoch").default(1)
    val lastLogin = long("last_login").default(0)
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_device_uid_id", uid, deviceId)
        check("ck_devices_status") { (status greaterEq 1) and (status lessEq 2) }
        check("ck_devices_credential_epoch_positive") { credentialEpoch greater 0L }
    }
}

/** 仅哈希的访问与刷新凭据。原始 bearer 秘密绝不进入持久存储。 */
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
    /** 私聊的规范排序 uid 对；群聊为 null。 */
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
    /** 最后一条新消息的服务端时间；置顶、草稿、已读和消息编辑不改变会话排序。 */
    val lastMsgTimestamp = long("last_msg_timestamp").nullable()
    val readSeq = long("read_seq").default(0)
    val peerReadSeq = long("peer_read_seq").default(0)
    val isMuted = bool("is_muted").default(false)
    val isPinned = bool("is_pinned").default(false)
    /** 用户拥有的列表可见性；活跃成员资格保持该持久容量槽被预留。 */
    val isHidden = bool("is_hidden").default(false)
    // Markdown 源码草稿必须与可发送正文使用同一容量契约；VARCHAR(500) 会静默截断
    // 代码块、表格等高频办公内容。项目尚未发布，测试实例可直接重建该列/数据。
    val draft = text("draft").nullable()
    val version = long("version").default(0)
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("idx_conv_uid_chat", uid, chatId)
    }
}

/**
 * 有界 Conversation 快照契约的 O(1) 每用户聚合台账。
 *
 * 每个生产 Conversation 写入器在锁定或变更 Conversation 行之前都会锁定此行。
 * 每个预发布数据 epoch 都从空数据库开始，因此缺失的行绝不会
 * 从未锁定的聚合回填：它只会在第一个投影被预留时以零创建。
 */
object ConversationUsages : Table("conversation_usages") {
    // 刻意不做外键：托管组织对账先获取 Organization 和 Chat
    // 再获取此行，而身份命令先获取 User。此处的隐式外键
    // key-share 锁会重新引入被禁止的 Chat -> User 边。
    val uid = varchar("uid", 36)
    val conversationCount = integer("conversation_count").default(0)
    val draftCharacters = long("draft_characters").default(0L)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(uid)

    init {
        check("ck_conversation_usage_count") {
            (conversationCount greaterEq 0) and
                (conversationCount lessEq ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER)
        }
        check("ck_conversation_usage_draft_characters") {
            (draftCharacters greaterEq 0L) and
                (draftCharacters lessEq ConversationCapacityPolicy.MAX_TOTAL_DRAFT_CHARACTERS_PER_USER)
        }
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
    val fromUid = varchar("from_uid", 36)
    val toUid = varchar("to_uid", 36)
    /** 仅在请求处于待处理状态时存在处理能力。 */
    val token = varchar("token", 36).nullable().uniqueIndex()
    val remark = varchar("remark", 200).nullable()
    val status = integer("status").default(0)  // 0=pending, 1=accepted, 2=rejected
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        index("idx_friend_applies_from_id", false, fromUid, id)
        index("idx_friend_applies_to_id", false, toUid, id)
        index(
            "idx_friend_applies_pending_sender",
            false,
            fromUid,
            filterCondition = { status eq 0 },
        )
        index(
            "idx_friend_applies_pending_recipient",
            false,
            toUid,
            filterCondition = { status eq 0 },
        )
        uniqueIndex(
            "uq_friend_applies_pending_direction",
            fromUid,
            toUid,
            filterCondition = { status eq 0 },
        )
        check("ck_friend_applies_status") { status inList listOf(0, 1, 2) }
        check("ck_friend_applies_token_lifecycle") {
            ((status eq 0) and token.isNotNull()) or ((status neq 0) and token.isNull())
        }
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

/** 每用户持久事件序号分配器。一行只会在所有领域写入完成后才被锁定。 */
object SyncStreams : Table("sync_streams") {
    val uid = varchar("uid", 36).references(Users.uid)
    val lastSeq = long("last_seq").default(0)
    /**
     * 已被压缩掉的最大事件序号。持久重放游标只在
     * `[compactedThrough, lastSeq]` 内有效；把下限保留在流行上，使校验
     * 与分页读取只依赖一个 PostgreSQL 快照，而不是从缺失的行推断保留策略。
     */
    val compactedThrough = long("compacted_through").default(0)

    override val primaryKey = PrimaryKey(uid)

    init {
        check("ck_sync_streams_last_seq_non_negative") { lastSeq greaterEq 0L }
        check("ck_sync_streams_compacted_through_non_negative") { compactedThrough greaterEq 0L }
        check("ck_sync_streams_compacted_through_lte_last_seq") { compactedThrough lessEq lastSeq }
    }
}

/**
 * 持久用户事件日志。[streamSeq] 以现有 wire `eventId` 对外暴露，并且
 * 只在一个已鉴权 uid 内连续；两个用户可能合法地拥有相同的数字事件 ID。
 */
object SyncEvents : Table("sync_events") {
    val uid = varchar("uid", 36).references(SyncStreams.uid)
    val streamSeq = long("stream_seq")
    val eventType = integer("event_type")
    val payload = binary("payload")
    val createdAt = long("created_at")
    /** 在线投递是优化手段；重放始终读取行，而不考虑此标记。 */
    val dispatchedAt = long("dispatched_at").nullable()
    val dispatchAttempts = integer("dispatch_attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").default(0)
    val lastDispatchError = text("last_dispatch_error").nullable()

    override val primaryKey = PrimaryKey(uid, streamSeq)

    init {
        // 保留候选选择和管理员滚动窗口计数器按创建时间查找；
        // 每用户主键仍是连续前缀删除的权威。
        index("idx_sync_events_created_at", false, createdAt)
        // 每用户投递始终按流顺序读取未分发头部。下面的重试索引
        // 以 next_attempt_at 开头，无法高效服务该访问路径。
        index(
            "idx_sync_events_pending_uid_seq",
            false,
            uid,
            streamSeq,
            filterCondition = { dispatchedAt.isNull() },
        )
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
 * 权威操作位于外部存储的投影的幂等回执。
 * 稳定的投影键标识一条消息；revision 标识 CREATE/EDIT/REVOKE。
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

/**
 * 一条消息的服务器权威每用户反应行。聚合
 * （emoji → 反应者 uid）始终由这张表推导；客户端从不自行合并计数。
 * 成员离开后行仍作为历史事实保留；消息撤回会删除它们。
 */
object MessageReactions : Table("message_reactions") {
    val chatId = varchar("chat_id", 160).index()
    val serverSeq = long("server_seq")
    val emoji = varchar("emoji", 64)
    val uid = varchar("uid", 36)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(chatId, serverSeq, emoji, uid)

    init {
        index("idx_message_reactions_chat_seq", false, chatId, serverSeq)
        check("ck_message_reactions_server_seq_positive") { serverSeq greater 0L }
    }
}

/** 每次组织事实变更的全局单调版本。 */
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

/**
 * 每次可能改变 Document 目录投影的变更的全局单调版本。
 *
 * 此单例与各空间的 custody/策略版本刻意分开：一个按版本界定的
 * `listSpaces` 游标还必须能检测到其他空间中的创建、元数据更新与归档。
 * 写入器只有在获得其所需的所有业务聚合行之后才获取此行。
 */
object DocumentDirectoryState : Table("document_directory_state") {
    val id = integer("id")
    val revision = long("revision").default(0)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        check("ck_document_directory_state_singleton") { id eq 1 }
        check("ck_document_directory_state_revision_non_negative") { revision greaterEq 0L }
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
 * 组织拥有聊天的持久期望/实际状态。
 *
 * 当部门禁用它自己的聊天或被归档时，行绝不会被删除：负向的期望
 * 状态就是防止更旧的正向对账重新复活访问的 fence。
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
    /** 稳定的客户端命令回执；作用域为拥有该条目的 Chat。 */
    val creationCommandId = varchar("creation_command_id", 36)
    /** actor、位置、规范化名称、类型与初始 Attachment 的不可变哈希。 */
    val creationFingerprint = varchar("creation_fingerprint", 64)
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
    /** 条目活跃期间所有不可变版本的总和；删除以 O(1) 释放它。 */
    val activeVersionBytes = long("active_version_bytes").default(0)
    val status = integer("status").default(1)
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(entryId)

    init {
        uniqueIndex("idx_group_file_sibling_name", chatId, parentKey, nameKey)
        uniqueIndex("idx_group_file_creation_command", chatId, creationCommandId)
        check("ck_group_file_entry_active_version_bytes_non_negative") { activeVersionBytes greaterEq 0L }
    }
}

/** 文件内容版本不可变，只追加；所有版本都参与群空间配额。 */
object GroupFileVersions : LongIdTable("group_file_versions") {
    val entryId = varchar("entry_id", 36).index()
    val version = long("version")
    /** 稳定的客户端命令回执。初始版本复用其条目创建命令。 */
    val commandId = varchar("command_id", 36)
    val commandFingerprint = varchar("command_fingerprint", 64)
    val attachmentPath = varchar("attachment_path", 500).index()
    val attachmentName = varchar("attachment_name", 500)
    val attachmentContentType = varchar("attachment_content_type", 200)
    val attachmentSize = long("attachment_size")
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")

    init {
        uniqueIndex("idx_group_file_entry_version", entryId, version)
        uniqueIndex("idx_group_file_version_command", commandId)
        check("ck_group_file_version_attachment_size_non_negative") { attachmentSize greaterEq 0L }
    }
}

/** 由拥有 Chat 行序列化的 O(1) 活跃用量台账。不支持启动回填。 */
object GroupFileChatUsages : Table("group_file_chat_usages") {
    val chatId = varchar("chat_id", 36)
    val activeEntries = long("active_entries")
    val activeVersionBytes = long("active_version_bytes")

    override val primaryKey = PrimaryKey(chatId)

    init {
        check("ck_group_file_usage_active_entries_non_negative") { activeEntries greaterEq 0L }
        check("ck_group_file_usage_active_version_bytes_non_negative") { activeVersionBytes greaterEq 0L }
    }
}

/** 重试安全的群文件变更的全局唯一、不可变回执。 */
object GroupFileCommands : Table("group_file_commands") {
    val commandId = varchar("command_id", 36)
    val chatId = varchar("chat_id", 36).index()
    val entryId = varchar("entry_id", 36).index()
    val actorUid = varchar("actor_uid", 36)
    val kind = integer("kind")
    val fingerprint = varchar("fingerprint", 64)
    val resultVersion = long("result_version").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(commandId)
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

/** 一个账户安装的最新已鉴权运行时事实。手机号被刻意排除。 */
object ClientTelemetryDevices : Table("client_telemetry_devices") {
    val uid = varchar("uid", 36)
    val deviceId = varchar("device_id", 100)
    val platform = varchar("platform", 32)
    val osName = varchar("os_name", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val osVersion = varchar("os_version", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val architecture = varchar("architecture", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val deviceModel = varchar("device_model", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val appVersion = varchar("app_version", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val buildNumber = varchar("build_number", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val gitCommit = varchar("git_commit", ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS)
    val buildIdentity = varchar("build_identity", ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS)
    val buildTime = varchar("build_time", ClientTelemetryLimits.MAX_BUILD_TIME_CHARS)
    val protocolVersion = integer("protocol_version")
    val distribution = varchar("distribution", ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS)
    val firstSeenAt = long("first_seen_at")
    val lastSeenAt = long("last_seen_at").index()
    val runtimeObservedAt = long("runtime_observed_at")
    val lastEventAt = long("last_event_at").nullable()

    override val primaryKey = PrimaryKey(uid, deviceId)

    init {
        index("idx_client_telemetry_device_runtime", false, platform, appVersion, gitCommit)
        check("ck_client_telemetry_device_protocol_version") {
            protocolVersion greaterEq 0
        }
    }
}

/** uid 级或精确设备目标的当前采集状态。空设备键表示 uid 级。 */
object ClientTelemetryPolicies : LongIdTable("client_telemetry_policies") {
    val targetUid = varchar("target_uid", 36).index()
    val targetDeviceKey = varchar("target_device_key", 100).default("")
    val mode = integer("mode")
    val revision = long("revision")
    val reason = varchar("reason", 500).nullable()
    val expiresAt = long("expires_at").nullable().index()
    val updatedAt = long("updated_at")
    val updatedBy = varchar("updated_by", 100)

    init {
        uniqueIndex("idx_client_telemetry_policy_target", targetUid, targetDeviceKey)
        check("ck_client_telemetry_policy_mode") { (mode greaterEq 0) and (mode lessEq 1) }
        check("ck_client_telemetry_policy_revision") { revision greaterEq 0L }
    }
}

/** 仅追加的管理与过期审计；手机号选择器在此边界之前已被解析。 */
object ClientTelemetryPolicyAudits : LongIdTable("client_telemetry_policy_audits") {
    val policyId = long("policy_id").index()
    val targetUid = varchar("target_uid", 36).index()
    val targetDeviceKey = varchar("target_device_key", 100).default("")
    val action = varchar("action", 24)
    val mode = integer("mode")
    val revision = long("revision")
    val reason = varchar("reason", 500).nullable()
    val expiresAt = long("expires_at").nullable()
    val actor = varchar("actor", 100)
    val createdAt = long("created_at").index()
}

/** 每次管理员遥测搜索、关联与策略变更的安全审计。 */
object ClientTelemetryAdminAudits : LongIdTable("client_telemetry_admin_audits") {
    val actor = varchar("actor", 100).index()
    val action = varchar("action", 40).index()
    val target = varchar("target", 180)
    val result = varchar("result", 24)
    val createdAt = long("created_at").index()

    init {
        index("idx_client_telemetry_admin_audit_action_time", false, action, createdAt)
    }
}
