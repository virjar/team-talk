package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.organization.OrganizationMemberPageAnchor
import com.virjar.tk.server.domain.organization.OrganizationMemberPageSlice
import com.virjar.tk.server.domain.organization.OrganizationUnitPageAnchor
import com.virjar.tk.server.domain.organization.OrganizationUnitPageSlice
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationMemberPage
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.OrganizationUnitPage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet

/**
 * 组织目录的有界读模型。
 *
 * 仓库外观拥有命令锁定与变更；此组件拥有快照
 * 校验、keyset 投影与原始递归查询。每个公共操作仍开启与
 * 原仓库实现相同的事务类型，而辅助函数要求活跃的
 * Exposed 事务，而不是打开隐藏的嵌套快照。
 */
internal class ExposedOrganizationReadProjection(
    private val database: Database,
) {
    fun listUnitPage(
        expectedRevision: Long?,
        after: OrganizationUnitPageAnchor?,
        pageSize: Int,
    ): OrganizationUnitPageSlice = transaction(
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        db = database,
    ) {
        require(pageSize in 1..OrganizationUnitPage.MAX_PAGE_SIZE) {
            "Organization unit page size is out of range"
        }
        val revision = currentOrganizationRevision()
        if (expectedRevision != null && revision != expectedRevision) {
            return@transaction OrganizationUnitPageSlice(
                revision = revision,
                items = emptyList(),
                nextAnchor = null,
                snapshotChanged = true,
            )
        }
        validateReadableHierarchy()
        val rows = OrganizationUnits.selectAll().where {
            val active = OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE
            after?.let { active and (OrganizationUnits.unitId greater it.unitId) } ?: active
        }.orderBy(OrganizationUnits.unitId, SortOrder.ASC)
            .limit(pageSize + 1)
            .toList()
        val hasMore = rows.size > pageSize
        val selectedRows = if (hasMore) rows.subList(0, pageSize) else rows
        val unitIds = selectedRows.mapTo(linkedSetOf()) { it[OrganizationUnits.unitId] }
        val counts = countDirectMembersInCurrentTransaction(unitIds)
        val items = selectedRows.map { row ->
            row.toOrganizationUnit().copy(
                directMemberCount = counts[row[OrganizationUnits.unitId]] ?: 0,
            )
        }
        OrganizationUnitPageSlice(
            revision = revision,
            items = items,
            nextAnchor = if (hasMore) OrganizationUnitPageAnchor(items.last().unitId) else null,
        )
    }

    fun listMemberPage(
        rootUnitId: String,
        recursive: Boolean,
        expectedRevision: Long?,
        after: OrganizationMemberPageAnchor?,
        pageSize: Int,
    ): OrganizationMemberPageSlice = transaction(
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        db = database,
    ) {
        require(pageSize in 1..OrganizationMemberPage.MAX_PAGE_SIZE) {
            "Organization member page size is out of range"
        }
        val revision = currentOrganizationRevision()
        if (expectedRevision != null && revision != expectedRevision) {
            return@transaction OrganizationMemberPageSlice(
                revision = revision,
                items = emptyList(),
                nextAnchor = null,
                snapshotChanged = true,
            )
        }
        validateReadableHierarchy()
        val result = queryMemberPage(rootUnitId, recursive, after, pageSize)
        require(result.rootExists) { "组织节点不存在: $rootUnitId" }
        check(!result.duplicateDetected) { "组织架构存在循环" }
        require(!result.depthExceeded) { OrganizationCapacityPolicy.TREE_DEPTH_REASON }
        val hasMore = result.members.size > pageSize
        val items = if (hasMore) result.members.subList(0, pageSize) else result.members
        OrganizationMemberPageSlice(
            revision = revision,
            items = items,
            nextAnchor = if (hasMore) {
                items.last().let { OrganizationMemberPageAnchor(it.unitId, it.uid) }
            } else {
                null
            },
        )
    }

    fun listUnits(): List<OrganizationUnit> = transaction(database) {
        validateReadableHierarchy()
        val rows = OrganizationUnits.selectAll()
            .where { OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE }
            .orderBy(OrganizationUnits.sortOrder to SortOrder.ASC, OrganizationUnits.name to SortOrder.ASC)
            .limit(OrganizationCapacityPolicy.MAX_ACTIVE_UNITS + 1)
            .toList()
        require(rows.size <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS) {
            OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
        }
        rows.map(ResultRow::toOrganizationUnit)
    }

    fun findUnit(unitId: String): OrganizationUnit? = transaction(database) {
        OrganizationUnits.selectAll().where {
            (OrganizationUnits.unitId eq unitId) and
                (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
        }.singleOrNull()?.toOrganizationUnit()
    }

    fun listMembers(unitIds: Set<String>): List<OrganizationMember> {
        if (unitIds.isEmpty()) return emptyList()
        return transaction(database) {
            OrganizationMemberships.selectAll()
                .where { OrganizationMemberships.unitId inList unitIds }
                .orderBy(OrganizationMemberships.joinedAt to SortOrder.ASC)
                .map(ResultRow::toOrganizationMember)
        }
    }

    fun countDirectMembers(unitIds: Set<String>): Map<String, Int> {
        if (unitIds.isEmpty()) return emptyMap()
        return transaction(database) {
            countDirectMembersInCurrentTransaction(unitIds)
        }
    }

    fun listMemberships(uid: String): List<OrganizationMember> = transaction(database) {
        OrganizationMemberships.selectAll().where { OrganizationMemberships.uid eq uid }
            .map(ResultRow::toOrganizationMember)
    }

    private fun currentOrganizationRevision(): Long = OrganizationState.selectAll()
        .where { OrganizationState.id eq STATE_ID }
        .single()[OrganizationState.revision]

    private fun countDirectMembersInCurrentTransaction(unitIds: Set<String>): Map<String, Int> {
        if (unitIds.isEmpty()) return emptyMap()
        val memberCount = OrganizationMemberships.id.count()
        return OrganizationMemberships.select(OrganizationMemberships.unitId, memberCount)
            .where { OrganizationMemberships.unitId inList unitIds }
            .groupBy(OrganizationMemberships.unitId)
            .associate { it[OrganizationMemberships.unitId] to it[memberCount].toInt() }
    }

    /**
     * 在发布任何页面之前，先校验一个完整、有界的活跃快照。
     *
     * 游标是规范的，但刻意不携带任何鉴权秘密，因此对端提供的
     * 版本号不能证明较早的页面已被校验。
     */
    private fun validateReadableHierarchy() {
        val stats = TransactionManager.current().execRawSql(
            stmt = ORGANIZATION_HIERARCHY_VALIDATION_SQL,
            args = emptyList(),
            explicitStatementType = StatementType.SELECT,
        ) { resultSet: ResultSet ->
            check(resultSet.next()) { "Organization hierarchy validation returned no row" }
            OrganizationHierarchyStats(
                activeCount = resultSet.getLong("active_count"),
                rootCount = resultSet.getLong("root_count"),
                reachedCount = resultSet.getLong("reached_count"),
                distinctReachedCount = resultSet.getLong("distinct_reached_count"),
                maxDepth = resultSet.getInt("max_depth"),
            ).also {
                check(!resultSet.next()) { "Organization hierarchy validation returned multiple rows" }
            }
        } ?: error("Organization hierarchy validation returned no result set")
        require(stats.activeCount <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS.toLong()) {
            OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
        }
        val expectedRoots = if (stats.activeCount == 0L) 0L else 1L
        check(stats.rootCount == expectedRoots) {
            "非空组织架构必须恰好包含一个根节点"
        }
        check(
            stats.reachedCount == stats.activeCount &&
                stats.distinctReachedCount == stats.reachedCount,
        ) { "组织架构存在循环或断开的父节点引用" }
        require(stats.maxDepth <= OrganizationCapacityPolicy.MAX_TREE_DEPTH) {
            OrganizationCapacityPolicy.TREE_DEPTH_REASON
        }
    }

    private fun queryMemberPage(
        rootUnitId: String,
        recursive: Boolean,
        after: OrganizationMemberPageAnchor?,
        pageSize: Int,
    ): RawOrganizationMemberPage {
        val afterPredicate = if (after == null) {
            ""
        } else {
            "AND (membership.unit_id, membership.uid) > (?::varchar, ?::varchar)"
        }
        val recursiveBranch = if (recursive) ORGANIZATION_MEMBER_RECURSIVE_BRANCH else ""
        val sql = ORGANIZATION_MEMBER_PAGE_SQL
            .replace("/* recursive-branch */", recursiveBranch)
            .replace("/* after-predicate */", afterPredicate)
        val args = buildList {
            add(OrganizationUnits.unitId.columnType to rootUnitId)
            if (after != null) {
                add(OrganizationMemberships.unitId.columnType to after.unitId)
                add(OrganizationMemberships.uid.columnType to after.uid)
            }
            add(OrganizationUnits.sortOrder.columnType to pageSize + 1)
        }
        return TransactionManager.current().execRawSql(
            stmt = sql,
            args = args,
            explicitStatementType = StatementType.SELECT,
        ) { resultSet: ResultSet ->
            var rootExists = false
            var duplicateDetected = false
            var depthExceeded = false
            val members = mutableListOf<OrganizationMember>()
            while (resultSet.next()) {
                rootExists = resultSet.getBoolean("root_exists")
                duplicateDetected = resultSet.getBoolean("duplicate_detected")
                depthExceeded = resultSet.getBoolean("depth_exceeded")
                val uid = resultSet.getString("uid")
                if (uid != null) {
                    members += OrganizationMember(
                        unitId = resultSet.getString("unit_id"),
                        uid = uid,
                        title = resultSet.getString("title"),
                        primary = resultSet.getBoolean("is_primary"),
                        joinedAt = resultSet.getLong("joined_at"),
                    )
                }
            }
            RawOrganizationMemberPage(rootExists, duplicateDetected, depthExceeded, members)
        } ?: error("Organization member page query returned no result set")
    }

    private companion object {
        const val STATE_ID = 1
    }
}

private data class RawOrganizationMemberPage(
    val rootExists: Boolean,
    val duplicateDetected: Boolean,
    val depthExceeded: Boolean,
    val members: List<OrganizationMember>,
)

private data class OrganizationHierarchyStats(
    val activeCount: Long,
    val rootCount: Long,
    val reachedCount: Long,
    val distinctReachedCount: Long,
    val maxDepth: Int,
)

internal val ORGANIZATION_MEMBER_RECURSIVE_BRANCH = """
    UNION ALL
    SELECT child.unit_id,
           walk.depth + 1
    FROM unit_walk walk
    JOIN organization_units child
      ON child.parent_id = walk.unit_id
     AND child.status = 1
    WHERE walk.depth < ${OrganizationCapacityPolicy.MAX_TREE_DEPTH}
""".trimIndent()

/**
 * 一条有界的 SQL 语句同时计算子树完整性与一个关系 keyset 页面。
 *
 * 状态行左连接到页面，使空/未知/成环的树仍可被观察，
 * 且不会与有效的空成员列表混淆。只插入编译期 SQL 片段；
 * 对端提供的每个标识符都是绑定的 JDBC 值。
 */
internal val ORGANIZATION_MEMBER_PAGE_SQL = """
    WITH RECURSIVE unit_walk(unit_id, depth) AS (
        SELECT unit.unit_id,
               1
        FROM organization_units unit
        WHERE unit.unit_id = ?::varchar
          AND unit.status = 1
        /* recursive-branch */
    ),
    scope_status AS (
        SELECT COUNT(*) > 0 AS root_exists,
               COUNT(*) <> COUNT(DISTINCT unit_id) AS duplicate_detected,
               EXISTS (
                   SELECT 1
                   FROM unit_walk frontier
                   JOIN organization_units child
                     ON child.parent_id = frontier.unit_id
                    AND child.status = 1
                   WHERE frontier.depth = ${OrganizationCapacityPolicy.MAX_TREE_DEPTH}
               ) AS depth_exceeded
        FROM unit_walk
    ),
    relation_page AS (
        SELECT membership.unit_id,
               membership.uid,
               membership.title,
               membership.is_primary,
               membership.joined_at
        FROM organization_memberships membership
        JOIN unit_walk walk ON walk.unit_id = membership.unit_id
        JOIN scope_status status
          ON NOT status.duplicate_detected
         AND NOT status.depth_exceeded
        WHERE TRUE
          /* after-predicate */
        ORDER BY membership.unit_id ASC, membership.uid ASC
        LIMIT ?::integer
    )
    SELECT status.root_exists,
           status.duplicate_detected,
           status.depth_exceeded,
           page.unit_id,
           page.uid,
           page.title,
           page.is_primary,
           page.joined_at
    FROM scope_status status
    LEFT JOIN relation_page page ON TRUE
    ORDER BY page.unit_id ASC NULLS LAST, page.uid ASC NULLS LAST
""".trimIndent()

/**
 * 自根向下的校验恰好访问每个有效节点一次，并在公共深度
 * 边界之外多停一层，使溢出可被观察。活跃 CTE 最多读取 MAX+1 行：多余的一行是
 * fail-closed 的溢出信号，绝不是被截断的目录结果。
 */
internal val ORGANIZATION_HIERARCHY_VALIDATION_SQL = """
    WITH RECURSIVE
    active_probe AS MATERIALIZED (
        SELECT unit_id
        FROM organization_units
        WHERE status = 1
        ORDER BY unit_id ASC
        LIMIT ${OrganizationCapacityPolicy.MAX_ACTIVE_UNITS + 1}
    ),
    root_probe AS MATERIALIZED (
        SELECT unit_id
        FROM organization_units
        WHERE status = 1
          AND parent_id IS NULL
        ORDER BY unit_id ASC
        LIMIT 2
    ),
    tree(unit_id, depth) AS (
        SELECT root.unit_id, 1
        FROM root_probe root
        WHERE (SELECT COUNT(*) FROM active_probe) <= ${OrganizationCapacityPolicy.MAX_ACTIVE_UNITS}
        UNION ALL
        SELECT child.unit_id, tree.depth + 1
        FROM tree
        JOIN organization_units child
          ON child.parent_id = tree.unit_id
         AND child.status = 1
        WHERE tree.depth <= ${OrganizationCapacityPolicy.MAX_TREE_DEPTH}
    )
    SELECT (SELECT COUNT(*) FROM active_probe) AS active_count,
           (SELECT COUNT(*) FROM root_probe) AS root_count,
           COUNT(*) AS reached_count,
           COUNT(DISTINCT unit_id) AS distinct_reached_count,
           COALESCE(MAX(depth), 0) AS max_depth
    FROM tree
""".trimIndent()

internal fun ResultRow.toOrganizationUnit() = OrganizationUnit(
    unitId = this[OrganizationUnits.unitId],
    parentId = this[OrganizationUnits.parentId],
    name = this[OrganizationUnits.name],
    leaderUid = this[OrganizationUnits.leaderUid],
    sortOrder = this[OrganizationUnits.sortOrder],
    groupChatId = this[OrganizationUnits.groupChatId],
    status = this[OrganizationUnits.status],
)

private fun ResultRow.toOrganizationMember() = OrganizationMember(
    unitId = this[OrganizationMemberships.unitId],
    uid = this[OrganizationMemberships.uid],
    title = this[OrganizationMemberships.title],
    primary = this[OrganizationMemberships.primary],
    joinedAt = this[OrganizationMemberships.joinedAt],
)
