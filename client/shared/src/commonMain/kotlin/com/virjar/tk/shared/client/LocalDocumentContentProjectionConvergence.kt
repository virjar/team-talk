package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy

/** 树/正文收敛算法；拥有存储在每次调用时持有其状态锁。 */
internal class LocalDocumentContentProjectionConvergence(
    private val queries: AppDatabaseQueries,
    private val persistence: LocalDocumentProjectionPersistence,
    private val bodies: LocalDocumentBodyProjectionStore,
    private val home: LocalDocumentHomeProjectionStore,
    private val branchSnapshots: KeyedProjectionSnapshotGate,
    private val pathSpineSnapshots: KeyedProjectionSnapshotGate,
) {
    fun applyDocumentLocked(document: Document, bodyBytes: Long): Boolean {
        val identity = normalizedDocumentIdentity(document.spaceId, document.documentId)
        val existingBody = bodies.loadLocked(identity.spaceId, identity.documentId)
        val existingRow = queries.selectDocumentNodeById(document.spaceId, document.documentId)
            .executeAsOneOrNull()
        val existingNode = existingRow?.toLocalDocumentNode()
        val highestRevision = maxOf(existingBody?.revision ?: 0L, existingNode?.revision ?: 0L)
        if (document.revision < highestRevision) return false
        if (existingBody != null && document.revision == existingBody.revision) {
            check(existingBody == document) {
                "same document body revision has conflicting content or ancestry"
            }
        }
        val hasChildren = existingNode?.hasChildren ?: (
            queries.countDocumentNodesByBranch(document.spaceId, document.documentId).executeAsOne() > 0L
            )
        val node = document.toProjectionNode(hasChildren)
        if (existingNode != null && document.revision == existingNode.revision) {
            check(existingNode.copy(hasChildren = node.hasChildren) == node) {
                "same document revision has conflicting node content"
            }
        }
        queries.transaction {
            bodies.persistLocked(document, bodyBytes)
            convergeNodeLocked(node, existingRow?.parent_key)
            updateHomeDocumentLocked(node)
            existingNode?.parentId?.takeIf { it != document.parentId }?.let { oldParentId ->
                updateParentHasChildrenLocked(normalizedBranch(document.spaceId, oldParentId))
            }
            updateParentHasChildrenLocked(normalizedBranch(document.spaceId, document.parentId))
            bodies.pruneLocked()
        }
        return true
    }

    fun convergeNodeLocked(node: DocumentNode, previousParentKey: String?) {
        val target = normalizedBranch(node.spaceId, node.parentId)
        val targetCached = queries.isDocumentBranchCached(target.spaceId, target.parentKey)
            .executeAsOne() > 0L
        val wasInTarget = previousParentKey == target.parentKey
        val targetCount = queries.countDocumentNodesByBranch(target.spaceId, target.parentKey).executeAsOne()
        val globalCount = queries.countDocumentNodes().executeAsOne()
        // 同父行可能是一条部分路径脊柱的一条边。刷新其正文必须就地更新该边，
        // 而不声称周围的 branch 是完整的。
        val canRetainTarget = wasInTarget || (
            targetCached &&
                targetCount < LocalDocumentProjectionLimits.MAX_BRANCH_NODES.toLong() &&
                (previousParentKey != null || globalCount < LocalDocumentProjectionLimits.MAX_NODES.toLong())
            )
        queries.deleteDocumentNode(node.spaceId, node.nodeId)
        if (!canRetainTarget) {
            if (targetCached && !wasInTarget) {
                queries.deleteDocumentNodesByBranch(target.spaceId, target.parentKey)
                queries.deleteDocumentBranch(target.spaceId, target.parentKey)
            }
            return
        }
        persistence.persistNodeLocked(node, target.parentKey)
    }

    fun updateHomeDocumentLocked(node: DocumentNode) {
        queries.updateDocumentHomeDocument(
            node.name, node.excerpt, node.updatedAt, node.spaceId, node.nodeId,
        )
    }

    fun updateParentHasChildrenLocked(branch: DocumentBranchIdentity) {
        val parentId = branch.parentId ?: return
        if (queries.isDocumentBranchCached(branch.spaceId, branch.parentKey).executeAsOne() == 0L) return
        val hasChildren = queries.countDocumentNodesByBranch(branch.spaceId, branch.parentKey)
            .executeAsOne() > 0L
        queries.updateDocumentNodeHasChildren(if (hasChildren) 1L else 0L, branch.spaceId, parentId)
    }

    fun fenceDocumentRelationshipsLocked(
        identity: DocumentIdentity,
        vararg parentIds: String?,
    ) {
        parentIds.distinct().forEach { parentId ->
            val key = normalizedBranch(identity.spaceId, parentId).snapshotKey
            branchSnapshots.invalidate(key)
        }
        pathSpineSnapshots.reset()
        home.reset()
    }

    fun resolveBranchNodesLocked(
        branch: DocumentBranchIdentity,
        snapshot: List<DocumentNode>,
    ): List<ResolvedBranchNode> = snapshot.mapNotNull { incoming ->
        val existing = queries.selectDocumentNodeById(incoming.spaceId, incoming.nodeId)
            .executeAsOneOrNull()
            ?.toLocalDocumentNode()
            ?: return@mapNotNull ResolvedBranchNode(incoming, addsAfterBranchDelete = true)
        val existingInTargetBranch = existing.parentId == branch.parentId
        when {
            incoming.revision > existing.revision ->
                ResolvedBranchNode(incoming, addsAfterBranchDelete = existingInTargetBranch)
            incoming.revision < existing.revision -> existing.takeIf { existingInTargetBranch }?.let {
                ResolvedBranchNode(it, addsAfterBranchDelete = true)
            }
            else -> {
                check(existing.copy(hasChildren = incoming.hasChildren) == incoming) {
                    "same document node revision has conflicting parent or content"
                }
                ResolvedBranchNode(incoming, addsAfterBranchDelete = existingInTargetBranch)
            }
        }
    }
}

internal fun parentIdFromDocumentStorage(parentKey: String): String? = parentKey.ifEmpty { null }

internal fun com.virjar.tk.shared.database.Document_node.toLocalDocumentNode(): DocumentNode = DocumentNode(
    nodeId = node_id,
    spaceId = space_id,
    parentId = parentIdFromDocumentStorage(parent_key),
    hasChildren = has_children != 0L,
    name = name,
    excerpt = excerpt,
    revision = revision,
    createdBy = created_by,
    createdAt = created_at,
    updatedBy = updated_by,
    updatedAt = updated_at,
)

private fun Document.toProjectionNode(hasChildren: Boolean): DocumentNode = DocumentNode(
    nodeId = documentId,
    spaceId = spaceId,
    parentId = parentId,
    hasChildren = hasChildren,
    name = title,
    excerpt = DocumentPolicy.markdownExcerpt(markdown),
    revision = revision,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
)
