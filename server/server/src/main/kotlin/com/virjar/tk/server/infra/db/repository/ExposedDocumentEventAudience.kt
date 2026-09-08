package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.statements.StatementType

/** 单个空间的集合式反向 ACL 查询，避免按收件人逐个查询组织路径。 */
internal object ExposedDocumentEventAudience {
    fun read(transaction: PgReadTransactionContext, spaceId: String): Set<String> =
        transaction.requireExposedReadTransaction().execRawSql(
            stmt = DOCUMENT_EVENT_AUDIENCE_SQL,
            args = listOf(VarCharColumnType(36) to spaceId),
            explicitStatementType = StatementType.SELECT,
        ) { rows ->
            buildSet { while (rows.next()) add(rows.getString("uid")) }
        } ?: error("Document event audience query returned no result set")
}

/** 与 actor ACL 相同：归档单位截断祖先路径，环路径仍保留直接所属部门的授权。 */
private val DOCUMENT_EVENT_AUDIENCE_SQL = """
    WITH RECURSIVE
    selected_space AS (
        SELECT space_id, steward_uid FROM document_spaces WHERE space_id = ?::varchar AND status = 1
    ),
    grants AS (
        SELECT grant_row.* FROM document_space_grants grant_row
        JOIN selected_space space ON space.space_id = grant_row.space_id
        WHERE grant_row.role BETWEEN ${DocumentSpace.ROLE_VIEWER} AND ${DocumentSpace.ROLE_ADMIN}
    ),
    direct_units AS (
        SELECT DISTINCT unit.unit_id, unit.parent_id
        FROM organization_units unit
        JOIN organization_memberships membership ON membership.unit_id = unit.unit_id
        WHERE unit.status = ${OrganizationUnit.STATUS_ACTIVE}
          AND EXISTS (SELECT 1 FROM grants WHERE principal_type = ${DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT})
    ),
    ancestor_walk(origin_id, unit_id, parent_id, path, cycle) AS (
        SELECT unit_id, unit_id, parent_id, ARRAY[unit_id]::varchar[], FALSE FROM direct_units
        UNION ALL
        SELECT walk.origin_id, parent.unit_id, parent.parent_id,
               array_append(walk.path, parent.unit_id), parent.unit_id = ANY(walk.path)
        FROM ancestor_walk walk
        JOIN organization_units parent ON parent.unit_id = walk.parent_id
          AND parent.status = ${OrganizationUnit.STATUS_ACTIVE}
        WHERE NOT walk.cycle
    ),
    path_status AS (
        SELECT origin_id, BOOL_OR(cycle) AS cycle_detected FROM ancestor_walk GROUP BY origin_id
    ),
    matching_units AS (
        SELECT direct.unit_id FROM direct_units direct
        JOIN grants grant_row ON grant_row.principal_id = direct.unit_id
        WHERE grant_row.principal_type = ${DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT}
        UNION
        SELECT walk.origin_id FROM ancestor_walk walk
        JOIN path_status state ON state.origin_id = walk.origin_id AND NOT state.cycle_detected
        JOIN grants grant_row ON grant_row.principal_id = walk.unit_id
        WHERE NOT walk.cycle AND grant_row.principal_type = ${DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT}
          AND grant_row.include_descendants
    ),
    candidates(uid) AS (
        SELECT steward_uid FROM selected_space
        UNION
        SELECT principal_id FROM grants WHERE principal_type = ${DocumentSpaceGrant.PRINCIPAL_USER}
        UNION
        SELECT membership.uid FROM organization_memberships membership
        JOIN matching_units unit ON unit.unit_id = membership.unit_id
    )
    SELECT users.uid FROM users JOIN candidates ON candidates.uid = users.uid
    WHERE users.status = 1 AND users.role = ${UserRole.HUMAN}
    ORDER BY users.uid
""".trimIndent()
