package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

/**
 * 一个不可变的管理员 Document 资产交接批次。
 *
 * [adminPrincipal] 是附着在已鉴权管理员 token 上的用户名，绝不是伪造的
 * 普通 uid。零条目的批次仍是持久、可重放的审计事实，并仍可记录
 * 对源用户显式授权的移除。
 */
object DocumentCustodyBatchTransfers : Table("document_custody_batch_transfers") {
    val operationId = varchar("operation_id", 36)
    val adminPrincipal = varchar("admin_principal", 100).index()
    val sourceUid = varchar("source_uid", 36).index()
    val requestFingerprint = varchar("request_fingerprint", 64)
    val planFingerprint = varchar("plan_fingerprint", 64)
    val targetOwnerPrincipalType = integer("target_owner_principal_type")
    val targetOwnerPrincipalId = varchar("target_owner_principal_id", 36)
    val targetStewardUid = varchar("target_steward_uid", 36).index()
    val itemCount = integer("item_count")
    val revokedGrantCount = integer("revoked_grant_count")
    val createdAt = long("created_at").index()

    override val primaryKey = PrimaryKey(operationId)

    init {
        check("ck_document_custody_batch_target_type") {
            (targetOwnerPrincipalType eq 1) or (targetOwnerPrincipalType eq 2)
        }
        check("ck_document_custody_batch_item_count") { itemCount greaterEq 0 }
        check("ck_document_custody_batch_revoked_grants") { revokedGrantCount greaterEq 0 }
    }
}

/** 管理员批次回执中每个空间的不可变旧/新责任版本。 */
object DocumentCustodyBatchTransferItems : Table("document_custody_batch_transfer_items") {
    val operationId = varchar("operation_id", 36)
    val spaceId = varchar("space_id", 36).index()
    val fromOwnerPrincipalType = integer("from_owner_principal_type")
    val fromOwnerPrincipalId = varchar("from_owner_principal_id", 36)
    val fromStewardUid = varchar("from_steward_uid", 36)
    val fromCustodyRevision = long("from_custody_revision")
    val toOwnerPrincipalType = integer("to_owner_principal_type")
    val toOwnerPrincipalId = varchar("to_owner_principal_id", 36)
    val toStewardUid = varchar("to_steward_uid", 36)
    val resultingCustodyRevision = long("resulting_custody_revision")

    override val primaryKey = PrimaryKey(operationId, spaceId)

    init {
        check("ck_document_custody_batch_item_from_type") {
            (fromOwnerPrincipalType eq 1) or (fromOwnerPrincipalType eq 2)
        }
        check("ck_document_custody_batch_item_to_type") {
            (toOwnerPrincipalType eq 1) or (toOwnerPrincipalType eq 2)
        }
        check("ck_document_custody_batch_item_revisions") {
            (fromCustodyRevision greater 0L) and
                (resultingCustodyRevision eq fromCustodyRevision + 1L)
        }
    }
}
