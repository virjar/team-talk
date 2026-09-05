package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.protocol.model.OrganizationUnit
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.statements.StatementType

internal data class DocumentActorOrganizationAccess(
    val directUnitIds: Set<String>,
    val unitAndAncestorIds: Set<String>,
)

/** 文档 ACL 读取使用的 actor 作用域组织投影。 */
internal object ExposedDocumentActorAccess {
    fun read(
        transaction: PgReadTransactionContext,
        actorUid: String,
    ): DocumentActorOrganizationAccess {
        val uidColumnType = VarCharColumnType(36)
        val rows = transaction.requireExposedReadTransaction().execRawSql(
            stmt = DOCUMENT_ACTOR_ORGANIZATION_ACCESS_SQL,
            args = listOf(uidColumnType to actorUid),
            explicitStatementType = StatementType.SELECT,
        ) { resultSet ->
            val result = mutableListOf<DocumentActorOrganizationAccessRow>()
            while (resultSet.next()) {
                result += DocumentActorOrganizationAccessRow(
                    directMembership = resultSet.getBoolean("direct_membership"),
                    unitId = resultSet.getString("unit_id"),
                )
            }
            result
        } ?: error("Document actor organization access query returned no result set")

        // 集合按字典序返回，使重叠的成员路径无法让
        // 授权快照依赖于 PostgreSQL 执行计划或成员插入顺序。
        val directUnitIds = rows.asSequence()
            .filter(DocumentActorOrganizationAccessRow::directMembership)
            .map(DocumentActorOrganizationAccessRow::unitId)
            .distinct()
            .sorted()
            .toCollection(linkedSetOf())
        val unitAndAncestorIds = rows.asSequence()
            .map(DocumentActorOrganizationAccessRow::unitId)
            .distinct()
            .sorted()
            .toCollection(linkedSetOf())
        return DocumentActorOrganizationAccess(directUnitIds, unitAndAncestorIds)
    }
}

private data class DocumentActorOrganizationAccessRow(
    val directMembership: Boolean,
    val unitId: String,
)

/**
 * 一次参数化的 PostgreSQL 遍历，只以 actor 活跃的直接成员资格为根。
 *
 * 已归档的部门会终止该成员路径。历史活跃环保持直接
 * 成员资格的权威性，但丢弃来自该环状路径的所有继承授权，与
 * 领域 fail-closed 规则一致。uid 始终是绑定的 JDBC 值；只有编译期的活跃
 * 状态常量被内插到此语句中。
 */
internal val DOCUMENT_ACTOR_ORGANIZATION_ACCESS_SQL = """
    WITH RECURSIVE
    actor_direct(unit_id) AS (
        SELECT DISTINCT membership.unit_id
        FROM organization_memberships membership
        JOIN organization_units direct_unit
          ON direct_unit.unit_id = membership.unit_id
         AND direct_unit.status = ${OrganizationUnit.STATUS_ACTIVE}
        WHERE membership.uid = ?::varchar
    ),
    ancestor_walk(direct_unit_id, unit_id, parent_id, path, cycle) AS (
        SELECT direct.unit_id,
               unit.unit_id,
               unit.parent_id,
               ARRAY[unit.unit_id]::varchar[],
               FALSE
        FROM actor_direct direct
        JOIN organization_units unit
          ON unit.unit_id = direct.unit_id
         AND unit.status = ${OrganizationUnit.STATUS_ACTIVE}
        UNION ALL
        SELECT walk.direct_unit_id,
               parent.unit_id,
               parent.parent_id,
               array_append(walk.path, parent.unit_id),
               parent.unit_id = ANY(walk.path)
        FROM ancestor_walk walk
        JOIN organization_units parent
          ON parent.unit_id = walk.parent_id
         AND parent.status = ${OrganizationUnit.STATUS_ACTIVE}
        WHERE NOT walk.cycle
    ),
    path_status(direct_unit_id, cycle_detected) AS (
        SELECT direct_unit_id, BOOL_OR(cycle)
        FROM ancestor_walk
        GROUP BY direct_unit_id
    ),
    resolved_ancestors(unit_id) AS (
        SELECT DISTINCT walk.unit_id
        FROM ancestor_walk walk
        JOIN path_status status
          ON status.direct_unit_id = walk.direct_unit_id
        WHERE NOT status.cycle_detected
          AND NOT walk.cycle
    )
    SELECT TRUE AS direct_membership, direct.unit_id
    FROM actor_direct direct
    UNION ALL
    SELECT FALSE AS direct_membership, ancestor.unit_id
    FROM resolved_ancestors ancestor
    ORDER BY direct_membership DESC, unit_id ASC
""".trimIndent()
