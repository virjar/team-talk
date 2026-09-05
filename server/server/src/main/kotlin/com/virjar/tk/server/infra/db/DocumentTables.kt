package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

/** 企业文档空间；权限和目录树都以 spaceId 为根。 */
object DocumentSpaces : Table("document_spaces") {
    val spaceId = varchar("space_id", 36)
    /** actor 与规范化创建载荷的不可变哈希，用于重试安全的资源 ID。 */
    val creationFingerprint = varchar("creation_fingerprint", 64)
    val name = varchar("name", 120)
    val description = varchar("description", 500).nullable()
    val status = integer("status").default(1)
    /** 与软删除原子地设置；空间处于活跃状态时为 null。 */
    val archiveCommandId = varchar("archive_command_id", 36).nullable()
    /** 与 [archiveCommandId] 配对的 actor；创建溯源无法识别后来的责任人。 */
    val archiveActorUid = varchar("archive_actor_uid", 36).nullable()
    val createdBy = varchar("created_by", 36)
    /** 可变的业务归属；创建溯源保留在 [createdBy]。 */
    val ownerPrincipalType = integer("owner_principal_type")
    val ownerPrincipalId = varchar("owner_principal_id", 36)
    /** 唯一获得隐式 owner 角色的身份。 */
    val stewardUid = varchar("steward_uid", 36)
    val custodyRevision = long("custody_revision").default(1)
    /** 显式授权变更的乐观锁版本。 */
    val policyRevision = long("policy_revision").default(1)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").index()

    override val primaryKey = PrimaryKey(spaceId)

    init {
        index("idx_document_space_owner_active", false, ownerPrincipalType, ownerPrincipalId, status)
        index("idx_document_space_steward_active", false, stewardUid, status)
        check("ck_document_space_owner_principal_type") {
            (ownerPrincipalType eq 1) or (ownerPrincipalType eq 2)
        }
        check("ck_document_space_user_owner_is_steward") {
            (ownerPrincipalType neq 1) or (ownerPrincipalId eq stewardUid)
        }
        check("ck_document_space_custody_revision_positive") { custodyRevision greater 0L }
        check("ck_document_space_policy_revision_positive") { policyRevision greater 0L }
    }
}

/** 不可变的资产交接命令回执同时充当文档空间的归属审计轨迹。 */
object DocumentSpaceCustodyTransfers : Table("document_space_custody_transfers") {
    val operationId = varchar("operation_id", 36)
    val spaceId = varchar("space_id", 36).index()
    val actorUid = varchar("actor_uid", 36).index()
    val fingerprint = varchar("fingerprint", 64)
    val fromPrincipalType = integer("from_principal_type")
    val fromPrincipalId = varchar("from_principal_id", 36)
    val fromStewardUid = varchar("from_steward_uid", 36)
    val fromRevision = long("from_revision")
    val toPrincipalType = integer("to_principal_type")
    val toPrincipalId = varchar("to_principal_id", 36)
    val toStewardUid = varchar("to_steward_uid", 36)
    val resultingRevision = long("resulting_revision")
    val createdAt = long("created_at").index()

    override val primaryKey = PrimaryKey(operationId)

    init {
        check("ck_document_custody_from_principal_type") {
            (fromPrincipalType eq 1) or (fromPrincipalType eq 2)
        }
        check("ck_document_custody_to_principal_type") {
            (toPrincipalType eq 1) or (toPrincipalType eq 2)
        }
        check("ck_document_custody_revisions") {
            (fromRevision greater 0L) and (resultingRevision greaterEq fromRevision)
        }
    }
}

/**
 * 重试安全的文档 ACL 变更的不可变、actor 作用域回执。
 *
 * actor User 行序列化一个 `(actor_uid, operation_id)` 身份的创建。回执
 * 在后续策略变化后仍然保留，这样旧的 retry 可以确认其原始副作用，
 * 而不会对更新的 ACL 再次执行它。
 */
object DocumentSpacePolicyCommands : Table("document_space_policy_commands") {
    /** 代理收集器键；actor/operation 仍是业务主身份。 */
    val retentionId = long("retention_id").autoIncrement().uniqueIndex()
    val actorUid = varchar("actor_uid", 36)
    val operationId = varchar("operation_id", 36)
    val spaceId = varchar("space_id", 36).index()
    val mutationType = integer("mutation_type")
    val fingerprint = varchar("fingerprint", 64)
    val fromPolicyRevision = long("from_policy_revision")
    val resultingPolicyRevision = long("resulting_policy_revision")
    /** 客户端冻结的签发时间控制有限的 ACK 丢失重放寿命。 */
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val createdAt = long("created_at").index()

    override val primaryKey = PrimaryKey(actorUid, operationId)

    init {
        index("idx_document_policy_commands_actor_expiry", false, actorUid, expiresAt)
        check("ck_document_policy_command_mutation_type") {
            (mutationType eq 1) or (mutationType eq 2)
        }
        check("ck_document_policy_command_revisions") {
            (fromPolicyRevision greater 0L) and
                (resultingPolicyRevision greaterEq fromPolicyRevision)
        }
        check("ck_document_policy_command_lifetime") {
            (issuedAt greaterEq 0L) and (expiresAt greaterEq issuedAt) and (createdAt greaterEq 0L)
        }
    }
}

/** 重试安全的文档移动与重命名命令的有限 actor 作用域回执。 */
object DocumentNodeMoveCommands : Table("document_node_move_commands") {
    val retentionId = long("retention_id").autoIncrement().uniqueIndex()
    val actorUid = varchar("actor_uid", 36)
    val operationId = varchar("operation_id", 36)
    val spaceId = varchar("space_id", 36).index()
    val nodeId = varchar("node_id", 36).index()
    val fingerprint = varchar("fingerprint", 64)
    val fromRevision = long("from_revision")
    val resultingRevision = long("resulting_revision")
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val createdAt = long("created_at").index()

    override val primaryKey = PrimaryKey(actorUid, operationId)

    init {
        index("idx_document_node_move_actor_expiry", false, actorUid, expiresAt)
        index("idx_document_node_move_expiry", false, expiresAt, retentionId)
        check("ck_document_node_move_revisions") {
            (fromRevision greater 0L) and (resultingRevision greaterEq fromRevision)
        }
        check("ck_document_node_move_lifetime") {
            (issuedAt greaterEq 0L) and (expiresAt greaterEq issuedAt) and (createdAt greaterEq 0L)
        }
    }
}

/** 用户或组织部门对文档空间的显式授权；可变归属和责任人保存在 [DocumentSpaces]。 */
object DocumentSpaceGrants : LongIdTable("document_space_grants") {
    val spaceId = varchar("space_id", 36).index()
    val principalType = integer("principal_type")
    val principalId = varchar("principal_id", 36).index()
    val role = integer("role")
    val includeDescendants = bool("include_descendants").default(false)
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("idx_document_space_principal", spaceId, principalType, principalId)
        check("ck_document_space_grant_principal_type") {
            (principalType eq 1) or (principalType eq 2)
        }
        check("ck_document_space_grant_role") { (role greaterEq 1) and (role lessEq 3) }
    }
}

/** 文档树与当前 Markdown 快照；每个节点既有正文，也可以拥有子文档。 */
object DocumentNodes : Table("document_nodes") {
    val nodeId = varchar("node_id", 36)
    /** actor、位置与初始内容的不可变哈希，用于重试安全的资源 ID。 */
    val creationFingerprint = varchar("creation_fingerprint", 64)
    val spaceId = varchar("space_id", 36).index()
    val parentId = varchar("parent_id", 36).nullable().index()
    val name = varchar("name", 180)
    /** 列表与首页投影使用的有界摘要，避免读取最大 1MB 的 Markdown 正文。 */
    val excerpt = varchar("excerpt", 500).default("")
    val markdown = text("markdown")
    val revision = long("revision").default(1)
    val status = integer("status").default(1)
    /** 与软删除原子地设置；节点处于活跃状态时为 null。 */
    val deleteCommandId = varchar("delete_command_id", 36).nullable()
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at").index()

    override val primaryKey = PrimaryKey(nodeId)

    init {
        index("idx_document_node_parent", false, spaceId, parentId, status, createdAt, nodeId)
        index("idx_document_node_created", false, spaceId, status, createdAt)
    }
}

/** 完整、不可变的文档修订快照。 */
object DocumentContentRevisions : LongIdTable("document_content_revisions") {
    val documentId = varchar("document_id", 36).index()
    val revision = long("revision")
    val title = varchar("title", 180)
    val markdown = text("markdown")
    /** 已持久化的元数据投影；修订列表查询绝不能通过读取 Markdown 来推导该值。 */
    val contentLength = integer("content_length")
    val editedBy = varchar("edited_by", 36)
    val editedAt = long("edited_at")

    init {
        uniqueIndex("idx_document_content_revision", documentId, revision)
    }
}

/**
 * 活跃文档中一个作用域内嵌资产的修订区间。
 *
 * 从当前 Markdown 中移除资产只会关闭其打开的区间。不可变行
 * 被保留，使每个历史内容修订都能继续解析出确切的清单，并让
 * 主对象及其可选缩略图都保持存活。之后重新插入会以相同的
 * 资产身份和不可变元数据再打开一个区间。
 */
object DocumentEmbeddedAssets : Table("document_embedded_assets") {
    val documentId = varchar("document_id", 36).index()
    val assetId = varchar("asset_id", 36)
    val attachmentPath = varchar("attachment_path", 4096).index()
    val attachmentName = varchar("attachment_name", 512)
    val attachmentContentType = varchar("attachment_content_type", 255)
    val attachmentSize = long("attachment_size")
    val thumbnailPath = varchar("thumbnail_path", 4096).nullable().index()
    val thumbnailName = varchar("thumbnail_name", 512).nullable()
    val thumbnailContentType = varchar("thumbnail_content_type", 255).nullable()
    val thumbnailSize = long("thumbnail_size").nullable()
    val width = integer("width")
    val height = integer("height")
    /** 该区间覆盖的每个修订的规范清单顺序。 */
    val position = integer("position")
    val firstRevision = long("first_revision")
    val lastRevision = long("last_revision").nullable()

    override val primaryKey = PrimaryKey(documentId, assetId, firstRevision)

    init {
        index("idx_document_embedded_asset_revision", false, documentId, firstRevision, lastRevision)
        check("ck_document_embedded_asset_size") { attachmentSize greaterEq 0L }
        check("ck_document_embedded_asset_dimensions") { (width greaterEq 0) and (height greaterEq 0) }
        check("ck_document_embedded_asset_position") { position greaterEq 0 }
        check("ck_document_embedded_asset_first_revision") { firstRevision greater 0L }
        check("ck_document_embedded_asset_revision_interval") {
            lastRevision.isNull() or (lastRevision greaterEq firstRevision)
        }
        check("ck_document_embedded_asset_thumbnail_shape") {
            (
                thumbnailPath.isNull() and thumbnailName.isNull() and
                    thumbnailContentType.isNull() and thumbnailSize.isNull()
                ) or (
                thumbnailPath.isNotNull() and thumbnailName.isNotNull() and
                    thumbnailContentType.isNotNull() and thumbnailSize.isNotNull()
                )
        }
        check("ck_document_embedded_asset_thumbnail_size") {
            thumbnailSize.isNull() or (thumbnailSize greaterEq 0L)
        }
    }
}

/** 每个用户有界的最近文档工作集；一篇文档只保留该用户最后一次访问时间。 */
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
