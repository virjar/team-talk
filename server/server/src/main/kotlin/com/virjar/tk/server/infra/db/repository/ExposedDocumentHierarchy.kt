package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentHierarchyConflictException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.StatementType

internal data class DocumentAncestorQueryRow(
    val nodeId: String,
    val spaceId: String,
    val parentId: String?,
    val depth: Int,
    val cycle: Boolean,
)

internal data class DocumentMoveHierarchySnapshot(
    val targetAncestorIds: List<String>,
    val maxDescendantDepth: Int,
    val subtreeNodeCount: Long,
)

internal data class DocumentMoveHierarchyQueryRow(
    val ancestor: Boolean,
    val nodeId: String,
    val parentId: String?,
    val depth: Int,
    val cycle: Boolean,
    val nodeCount: Long = 1,
)

internal data class DocumentPathQueryRow(
    val node: DocumentNode,
    val depth: Int,
    val cycle: Boolean,
)

/** 仅用于校验和投影文档树的 PostgreSQL 查询。 */
internal class ExposedDocumentHierarchy {
    /**
     * 用一条深度有界的递归语句解析一个活跃的同空间路径。
     * 结果保持 O(depth)，而打开一个深层文档不再为每个
     * 祖先各执行一条 SELECT。缺失/不活跃/跨空间链接、环以及未终止的深度 128 路径
     * 都会在 [DocumentHierarchyTraversal] 中 fail closed。
     */
    fun resolveAncestorIds(
        transaction: Transaction,
        spaceId: String,
        parentId: String?,
    ): List<String> {
        if (parentId == null) return emptyList()
        val identifierColumnType = VarCharColumnType(36)
        val rows = transaction.execRawSql(
            stmt = DOCUMENT_ANCESTOR_PATH_SQL,
            args = listOf(
                identifierColumnType to spaceId,
                identifierColumnType to parentId,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { resultSet ->
            val result = mutableListOf<DocumentAncestorQueryRow>()
            while (resultSet.next()) {
                result += DocumentAncestorQueryRow(
                    nodeId = resultSet.getString("node_id"),
                    spaceId = resultSet.getString("space_id"),
                    parentId = resultSet.getString("parent_id"),
                    depth = resultSet.getInt("depth"),
                    cycle = resultSet.getBoolean("cycle"),
                )
            }
            result
        } ?: error("Document ancestor path query returned no result set")
        return DocumentHierarchyTraversal.resolveAncestorIds(spaceId, parentId, rows)
    }

    /** 用一条递归语句解析完整的活跃根到目标摘要路径。 */
    fun resolvePathSpine(
        transaction: Transaction,
        spaceId: String,
        targetNodeId: String,
    ): DocumentPathSpine {
        val identifierColumnType = VarCharColumnType(36)
        val rows = transaction.execRawSql(
            stmt = DOCUMENT_PATH_SPINE_SQL,
            args = listOf(
                identifierColumnType to spaceId,
                identifierColumnType to targetNodeId,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { resultSet ->
            val result = mutableListOf<DocumentPathQueryRow>()
            while (resultSet.next()) {
                result += DocumentPathQueryRow(
                    node = DocumentNode(
                        nodeId = resultSet.getString("node_id"),
                        spaceId = resultSet.getString("space_id"),
                        parentId = resultSet.getString("parent_id"),
                        hasChildren = resultSet.getBoolean("has_children"),
                        name = resultSet.getString("name"),
                        excerpt = resultSet.getString("excerpt"),
                        revision = resultSet.getLong("revision"),
                        createdBy = resultSet.getString("created_by"),
                        createdAt = resultSet.getLong("created_at"),
                        updatedBy = resultSet.getString("updated_by"),
                        updatedAt = resultSet.getLong("updated_at"),
                    ),
                    depth = resultSet.getInt("depth"),
                    cycle = resultSet.getBoolean("cycle"),
                )
            }
            result
        } ?: error("Document path spine query returned no result set")
        return DocumentHierarchyTraversal.resolvePathSpine(spaceId, targetNodeId, rows)
    }

    /**
     * 在一条递归 PostgreSQL 语句中捕获目标路径与深度有界的可达子树。
     * 调用方持有 DocumentSpaces 行锁，因此两个分支描述一个稳定的
     * 层级。PostgreSQL 把整个子树归约为其最大深度、环标志与节点
     * 计数，因此子树的宽度不会增加 JDBC 结果集。
     */
    fun moveSnapshot(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        rootNodeId: String,
        targetParentId: String?,
    ): DocumentMoveHierarchySnapshot {
        val identifierColumnType = VarCharColumnType(36)
        val rows = transaction.requireExposedTransaction().execRawSql(
            stmt = DOCUMENT_MOVE_HIERARCHY_SQL,
            args = listOf(
                identifierColumnType to spaceId,
                identifierColumnType to rootNodeId,
                identifierColumnType to targetParentId,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { resultSet ->
            val result = mutableListOf<DocumentMoveHierarchyQueryRow>()
            while (resultSet.next()) {
                val branch = resultSet.getInt("branch")
                require(branch == MOVE_BRANCH_ANCESTOR || branch == MOVE_BRANCH_SUBTREE) {
                    "文档树查询结果非法"
                }
                result += DocumentMoveHierarchyQueryRow(
                    ancestor = branch == MOVE_BRANCH_ANCESTOR,
                    nodeId = resultSet.getString("node_id"),
                    parentId = resultSet.getString("parent_id"),
                    depth = resultSet.getInt("depth"),
                    cycle = resultSet.getBoolean("cycle"),
                    nodeCount = resultSet.getLong("node_count"),
                )
            }
            result
        } ?: error("Document move hierarchy query returned no result set")
        return DocumentHierarchyTraversal.moveSnapshot(rootNodeId, targetParentId, rows)
    }

    fun parentsWithActiveChildren(spaceId: String, candidateNodeIds: List<String>): Set<String> {
        if (candidateNodeIds.isEmpty()) return emptySet()
        val parentsWithChildren = hashSetOf<String>()
        candidateNodeIds.chunked(HIERARCHY_QUERY_BATCH_SIZE).forEach { nodeIdBatch ->
            val nullableNodeIds: List<String?> = nodeIdBatch
            DocumentNodes.select(DocumentNodes.parentId).where {
                (DocumentNodes.spaceId eq spaceId) and
                    (DocumentNodes.parentId inList nullableNodeIds) and
                    (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
            }.forEach { row ->
                row[DocumentNodes.parentId]?.let(parentsWithChildren::add)
            }
        }
        return parentsWithChildren
    }

    fun hasActiveChildren(spaceId: String, nodeId: String): Boolean = DocumentNodes
        .select(DocumentNodes.nodeId)
        .where {
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.parentId eq nodeId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }
        .limit(1)
        .any()

    private companion object {
        const val HIERARCHY_QUERY_BATCH_SIZE = 500
    }
}

/** 纯遍历规则与 SQL 分开保存，使环、深度与排序语义保持本地化。 */
internal object DocumentHierarchyTraversal {
    fun resolveAncestorIds(
        spaceId: String,
        parentId: String?,
        rows: List<DocumentAncestorQueryRow>,
    ): List<String> {
        if (parentId == null) {
            require(rows.isEmpty()) { "文档树查询结果非法" }
            return emptyList()
        }
        if (rows.isEmpty()) throw DocumentNotFoundException("文档节点不存在")
        require(rows.none(DocumentAncestorQueryRow::cycle)) { "文档树存在循环" }
        require(rows.size <= Document.MAX_ANCESTOR_DEPTH) { "文档层级超过限制" }
        require(rows.first().nodeId == parentId) { "文档树查询结果非法" }
        require(rows.map(DocumentAncestorQueryRow::nodeId).toSet().size == rows.size) {
            "文档树存在循环"
        }
        rows.forEachIndexed { index, row ->
            require(row.spaceId == spaceId) { "文档祖先不属于当前空间" }
            require(row.depth == index + 1) { "文档树查询结果非法" }
            val expectedParentId = rows.getOrNull(index + 1)?.nodeId
            require(row.parentId == expectedParentId) { "文档祖先链不完整" }
        }
        require(rows.last().parentId == null) { "文档祖先链不完整" }
        return rows.asReversed().map(DocumentAncestorQueryRow::nodeId)
    }

    fun moveSnapshot(
        rootNodeId: String,
        targetParentId: String?,
        rows: List<DocumentMoveHierarchyQueryRow>,
    ): DocumentMoveHierarchySnapshot {
        val ancestorRows = rows.filter(DocumentMoveHierarchyQueryRow::ancestor)
        val subtreeRows = rows.filterNot(DocumentMoveHierarchyQueryRow::ancestor)

        val targetAncestorIds = if (targetParentId == null) {
            require(ancestorRows.isEmpty()) { "文档树查询结果非法" }
            emptyList()
        } else {
            if (ancestorRows.isEmpty()) throw DocumentHierarchyConflictException()
            require(ancestorRows.none(DocumentMoveHierarchyQueryRow::cycle)) { "文档树存在循环" }
            val leafToRoot = ancestorRows.sortedBy(DocumentMoveHierarchyQueryRow::depth)
            require(leafToRoot.first().nodeId == targetParentId) { "文档树查询结果非法" }
            require(leafToRoot.map(DocumentMoveHierarchyQueryRow::nodeId).toSet().size == leafToRoot.size) {
                "文档树存在循环"
            }
            leafToRoot.forEachIndexed { index, row ->
                require(row.depth == index + 1) { "文档树查询结果非法" }
                require(row.parentId == leafToRoot.getOrNull(index + 1)?.nodeId) {
                    "文档祖先链不完整"
                }
            }
            require(leafToRoot.size <= Document.MAX_ANCESTOR_DEPTH) { "文档层级超过限制" }
            require(leafToRoot.last().parentId == null) { "文档祖先链不完整" }
            leafToRoot.asReversed().map(DocumentMoveHierarchyQueryRow::nodeId)
        }

        val subtree = subtreeRows.singleOrNull() ?: throw IllegalArgumentException("文档树查询结果非法")
        require(subtree.nodeId == rootNodeId) { "文档树查询结果非法" }
        require(subtree.nodeCount > 0) { "文档节点不存在" }
        require(!subtree.cycle) { "文档树存在循环" }
        require(subtree.depth in 0..Document.MAX_ANCESTOR_DEPTH) { "文档层级超过限制" }

        return DocumentMoveHierarchySnapshot(
            targetAncestorIds = targetAncestorIds,
            maxDescendantDepth = subtree.depth,
            subtreeNodeCount = subtree.nodeCount,
        )
    }

    fun resolvePathSpine(
        spaceId: String,
        targetNodeId: String,
        rows: List<DocumentPathQueryRow>,
    ): DocumentPathSpine {
        if (rows.isEmpty()) throw DocumentNotFoundException("文档节点不存在")
        check(rows.none(DocumentPathQueryRow::cycle)) { "文档树存在循环" }
        check(rows.size <= DocumentPathSpine.MAX_NODES) { "文档层级超过限制" }
        check(rows.first().node.nodeId == targetNodeId) { "文档树查询结果非法" }
        check(rows.map { it.node.nodeId }.toSet().size == rows.size) { "文档树存在循环" }
        rows.forEachIndexed { index, row ->
            check(row.node.spaceId == spaceId) { "文档节点不属于当前空间" }
            check(row.depth == index + 1) { "文档树查询结果非法" }
            check(row.node.parentId == rows.getOrNull(index + 1)?.node?.nodeId) {
                "文档祖先链不完整"
            }
        }
        check(rows.last().node.parentId == null) { "文档祖先链不完整" }
        return DocumentPathSpine(rows.asReversed().map(DocumentPathQueryRow::node))
    }
}

private const val MOVE_BRANCH_ANCESTOR = 0
private const val MOVE_BRANCH_SUBTREE = 1

/** 一条深度有界的语句，只返回直接父节点到根的路径。 */
internal val DOCUMENT_ANCESTOR_PATH_SQL = """
    WITH RECURSIVE
    ancestor_params(space_id, parent_id) AS (
        VALUES (?::varchar, ?::varchar)
    ),
    ancestor_walk(space_id, node_id, parent_id, depth, path, cycle) AS (
        SELECT node.space_id,
               node.node_id,
               node.parent_id,
               1,
               ARRAY[node.node_id]::varchar[],
               FALSE
        FROM ancestor_params params
        JOIN document_nodes node
          ON node.node_id = params.parent_id
         AND node.space_id = params.space_id
         AND node.status = $DOCUMENT_STATUS_ACTIVE
        UNION ALL
        SELECT parent.space_id,
               parent.node_id,
               parent.parent_id,
               walk.depth + 1,
               array_append(walk.path, parent.node_id),
               parent.node_id = ANY(walk.path)
        FROM ancestor_walk walk
        JOIN ancestor_params params ON TRUE
        JOIN document_nodes parent
          ON parent.node_id = walk.parent_id
         AND parent.space_id = params.space_id
         AND parent.status = $DOCUMENT_STATUS_ACTIVE
        WHERE NOT walk.cycle
          AND walk.depth <= ${Document.MAX_ANCESTOR_DEPTH}
    )
    SELECT space_id,
           node_id,
           parent_id,
           depth,
           cycle
    FROM ancestor_walk
    ORDER BY depth ASC
""".trimIndent()

/** 一条有界的活跃同空间查询，返回目标及每个祖先的摘要。 */
internal val DOCUMENT_PATH_SPINE_SQL = """
    WITH RECURSIVE
    path_params(space_id, target_node_id) AS (
        VALUES (?::varchar, ?::varchar)
    ),
    path_walk(
        space_id, node_id, parent_id, name, excerpt, revision,
        created_by, created_at, updated_by, updated_at, depth, path, cycle
    ) AS (
        SELECT node.space_id,
               node.node_id,
               node.parent_id,
               node.name,
               node.excerpt,
               node.revision,
               node.created_by,
               node.created_at,
               node.updated_by,
               node.updated_at,
               1,
               ARRAY[node.node_id]::varchar[],
               FALSE
        FROM path_params params
        JOIN document_nodes node
          ON node.node_id = params.target_node_id
         AND node.space_id = params.space_id
         AND node.status = $DOCUMENT_STATUS_ACTIVE
        UNION ALL
        SELECT parent.space_id,
               parent.node_id,
               parent.parent_id,
               parent.name,
               parent.excerpt,
               parent.revision,
               parent.created_by,
               parent.created_at,
               parent.updated_by,
               parent.updated_at,
               walk.depth + 1,
               array_append(walk.path, parent.node_id),
               parent.node_id = ANY(walk.path)
        FROM path_walk walk
        JOIN path_params params ON TRUE
        JOIN document_nodes parent
          ON parent.node_id = walk.parent_id
         AND parent.space_id = params.space_id
         AND parent.status = $DOCUMENT_STATUS_ACTIVE
        WHERE NOT walk.cycle
          AND walk.depth <= ${Document.MAX_ANCESTOR_DEPTH}
    )
    SELECT walk.space_id,
           walk.node_id,
           walk.parent_id,
           walk.name,
           walk.excerpt,
           walk.revision,
           walk.created_by,
           walk.created_at,
           walk.updated_by,
           walk.updated_at,
           walk.depth,
           walk.cycle,
           EXISTS (
               SELECT 1
               FROM document_nodes child
               WHERE child.space_id = walk.space_id
                 AND child.parent_id = walk.node_id
                 AND child.status = $DOCUMENT_STATUS_ACTIVE
           ) AS has_children
    FROM path_walk walk
    ORDER BY walk.depth ASC
""".trimIndent()

/** 一条带两个递归分支与子树常量大小聚合的语句。 */
internal val DOCUMENT_MOVE_HIERARCHY_SQL = """
    WITH RECURSIVE
    move_params(space_id, root_node_id, target_parent_id) AS (
        VALUES (?::varchar, ?::varchar, ?::varchar)
    ),
    ancestor_walk(node_id, parent_id, depth, path, cycle) AS (
        SELECT node.node_id,
               node.parent_id,
               1,
               ARRAY[node.node_id]::varchar[],
               FALSE
        FROM move_params params
        JOIN document_nodes node
          ON node.node_id = params.target_parent_id
         AND node.space_id = params.space_id
         AND node.status = $DOCUMENT_STATUS_ACTIVE
        UNION ALL
        SELECT parent.node_id,
               parent.parent_id,
               walk.depth + 1,
               array_append(walk.path, parent.node_id),
               parent.node_id = ANY(walk.path)
        FROM ancestor_walk walk
        JOIN move_params params ON TRUE
        JOIN document_nodes parent
          ON parent.node_id = walk.parent_id
         AND parent.space_id = params.space_id
         AND parent.status = $DOCUMENT_STATUS_ACTIVE
        WHERE NOT walk.cycle
          AND walk.depth <= ${Document.MAX_ANCESTOR_DEPTH}
    ),
    subtree_walk(node_id, depth, path, cycle) AS (
        SELECT node.node_id,
               0,
               ARRAY[node.node_id]::varchar[],
               FALSE
        FROM move_params params
        JOIN document_nodes node
          ON node.node_id = params.root_node_id
         AND node.space_id = params.space_id
         AND node.status = $DOCUMENT_STATUS_ACTIVE
        UNION ALL
        SELECT child.node_id,
               walk.depth + 1,
               array_append(walk.path, child.node_id),
               child.node_id = ANY(walk.path)
        FROM subtree_walk walk
        JOIN move_params params ON TRUE
        JOIN document_nodes child
          ON child.parent_id = walk.node_id
         AND child.space_id = params.space_id
         AND child.status = $DOCUMENT_STATUS_ACTIVE
        WHERE NOT walk.cycle
          AND walk.depth <= ${Document.MAX_ANCESTOR_DEPTH}
    )
    SELECT $MOVE_BRANCH_ANCESTOR AS branch,
           node_id,
           parent_id,
           depth,
           cycle,
           1::bigint AS node_count
    FROM ancestor_walk
    UNION ALL
    SELECT $MOVE_BRANCH_SUBTREE AS branch,
           params.root_node_id AS node_id,
           NULL::varchar AS parent_id,
           COALESCE(MAX(subtree.depth), -1)::integer AS depth,
           COALESCE(BOOL_OR(subtree.cycle), FALSE) AS cycle,
           COUNT(subtree.node_id) AS node_count
    FROM move_params params
    LEFT JOIN subtree_walk subtree ON TRUE
    GROUP BY params.root_node_id
""".trimIndent()
