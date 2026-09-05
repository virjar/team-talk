package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.LocalDocumentProjectionLimits
import com.virjar.tk.shared.client.LocalDocumentProjectionPolicy
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DOCUMENT_NODE_SIBLING_ORDER

internal fun mergeFakeSpacePage(
    cached: List<DocumentSpace>,
    page: List<DocumentSpace>,
    isFirstPage: Boolean,
): List<DocumentSpace> {
    val incoming = page.associateByTo(linkedMapOf(), DocumentSpace::spaceId)
    val naturalOrder = if (isFirstPage) {
        page + cached.filterNot { it.spaceId in incoming }
    } else {
        cached.map { incoming[it.spaceId] ?: it } + page.filterNot { remote ->
            cached.any { it.spaceId == remote.spaceId }
        }
    }
    if (naturalOrder.size <= LocalDocumentProjectionLimits.MAX_SPACES) return naturalOrder
    val retainedOlderIds = naturalOrder.asSequence()
        .filterNot { it.spaceId in incoming }
        .take(LocalDocumentProjectionLimits.MAX_SPACES - page.size)
        .mapTo(hashSetOf(), DocumentSpace::spaceId)
    return naturalOrder.filter { it.spaceId in incoming || it.spaceId in retainedOlderIds }
}

internal fun fakeBranchKey(spaceId: String, parentId: String?): BranchKey = BranchKey(
    LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId"),
    parentId?.let { LocalDocumentProjectionPolicy.requireKey(it, "parentId") },
)

internal fun fakeDocumentKey(spaceId: String, documentId: String): DocumentKey = DocumentKey(
    LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId"),
    LocalDocumentProjectionPolicy.requireKey(documentId, "documentId"),
)

internal data class BranchKey(val spaceId: String, val parentId: String?) {
    val snapshotKey = fakeCompositeKey(spaceId, parentId)
}

internal data class DocumentKey(val spaceId: String, val documentId: String) {
    val snapshotKey = fakeCompositeKey(spaceId, documentId)
}

internal data class SpaceSnapshotBoundary(
    val lease: ProjectionSnapshotLease,
    val initialSpaceIds: Set<String>,
    val seenInitialSpaceIds: MutableSet<String> = linkedSetOf(),
    var lastSpaceId: String? = null,
    var appliedPageCount: Int = 0,
)

internal fun Document.toFakeProjectionNode(hasChildren: Boolean): DocumentNode = DocumentNode(
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

internal fun Document?.fakeAncestorIds(): List<String> = this?.ancestorIds.orEmpty()

/** 内容树算法与测试替身的租约及快照编排拆分开来。 */
internal fun FakeDocumentProjectionStore.purgeBranch(branch: BranchKey) {
    branchOrder.remove(branch).orEmpty().forEach { nodeId ->
        val key = DocumentKey(branch.spaceId, nodeId)
        if (nodesById[key]?.parentId == branch.parentId) nodesById.remove(key)
    }
    branchMarkers.remove(branch)
}

internal fun FakeDocumentProjectionStore.purgeBody(key: DocumentKey) {
    bodies.remove(key)
    bodyLru.remove(key)
}

internal fun FakeDocumentProjectionStore.touchBody(key: DocumentKey) {
    bodyLru.remove(key)
    bodyLru += key
}

internal fun FakeDocumentProjectionStore.pruneBodies() {
    var bytes = bodies.values.sumOf { it.markdown.encodeToByteArray().size.toLong() }
    while (
        bodies.size > LocalDocumentProjectionLimits.MAX_BODIES ||
        bytes > LocalDocumentProjectionLimits.MAX_BODY_BYTES
    ) {
        val oldest = bodyLru.removeAt(0)
        bytes -= checkNotNull(bodies.remove(oldest)).markdown.encodeToByteArray().size.toLong()
    }
}

internal fun FakeDocumentProjectionStore.updateHomeSpaceName(spaceId: String, name: String) {
    home.keys.toList().forEach { collection ->
        home[collection] = home[collection].orEmpty().map { item ->
            if (item.spaceId == spaceId) item.copy(spaceName = name) else item
        }
    }
}

internal fun FakeDocumentProjectionStore.applyDocumentLocked(document: Document): Boolean {
    val key = fakeDocumentKey(document.spaceId, document.documentId)
    val existingBody = bodies[key]
    val existingNode = nodesById[key]
    val highestRevision = maxOf(existingBody?.revision ?: 0L, existingNode?.revision ?: 0L)
    if (document.revision < highestRevision) return false
    if (existingBody != null && document.revision == existingBody.revision) {
        check(existingBody == document) {
            "same document body revision has conflicting content or ancestry"
        }
    }
    val hasChildren = existingNode?.hasChildren ?: branchOrder[
        fakeBranchKey(document.spaceId, document.documentId)
    ].orEmpty().isNotEmpty()
    val node = document.toFakeProjectionNode(hasChildren)
    if (existingNode != null && document.revision == existingNode.revision) {
        check(existingNode.copy(hasChildren = node.hasChildren) == node) {
            "same document revision has conflicting node content"
        }
    }
    bodies[key] = document
    touchBody(key)
    convergeNode(node)
    updateHomeDocument(node)
    existingNode?.parentId?.takeIf { it != document.parentId }?.let { oldParentId ->
        updateParentHasChildren(fakeBranchKey(document.spaceId, oldParentId))
    }
    updateParentHasChildren(fakeBranchKey(document.spaceId, document.parentId))
    pruneBodies()
    return true
}

internal fun FakeDocumentProjectionStore.convergeNode(node: DocumentNode) {
    val key = DocumentKey(node.spaceId, node.nodeId)
    val previous = nodesById[key]
    val previousBranch = previous?.let { BranchKey(node.spaceId, it.parentId) }
    val target = BranchKey(node.spaceId, node.parentId)
    val targetCached = target in branchMarkers
    val wasInTarget = previousBranch == target
    val targetCount = branchOrder[target].orEmpty().size
    val canRetainTarget = wasInTarget || (
        targetCached &&
            targetCount < LocalDocumentProjectionLimits.MAX_BRANCH_NODES &&
            (previous != null || nodesById.size < LocalDocumentProjectionLimits.MAX_NODES)
        )
    if (!wasInTarget) {
        previousBranch?.let { branch ->
            branchOrder[branch] = branchOrder[branch].orEmpty() - node.nodeId
        }
    }
    nodesById.remove(key)
    if (!canRetainTarget) {
        if (targetCached && !wasInTarget) purgeBranch(target)
        return
    }
    branchOrder[target] = if (wasInTarget) {
        branchOrder[target].orEmpty()
    } else {
        branchOrder[target].orEmpty() + node.nodeId
    }
    nodesById[key] = node
    sortBranchBySiblingOrder(target)
}

internal fun FakeDocumentProjectionStore.sortBranchBySiblingOrder(branch: BranchKey) {
    branchOrder[branch] = branchOrder[branch].orEmpty().sortedWith { leftId, rightId ->
        DOCUMENT_NODE_SIBLING_ORDER.compare(
            nodesById.getValue(DocumentKey(branch.spaceId, leftId)),
            nodesById.getValue(DocumentKey(branch.spaceId, rightId)),
        )
    }
}

internal fun FakeDocumentProjectionStore.updateHomeDocument(node: DocumentNode) {
    home.keys.toList().forEach { collection ->
        home[collection] = home[collection].orEmpty().map { item ->
            if (item.spaceId == node.spaceId && item.documentId == node.nodeId) {
                item.copy(title = node.name, excerpt = node.excerpt, updatedAt = node.updatedAt)
            } else {
                item
            }
        }
    }
}

internal fun FakeDocumentProjectionStore.updateParentHasChildren(branch: BranchKey) {
    val parentId = branch.parentId ?: return
    if (branch !in branchMarkers) return
    val parentKey = DocumentKey(branch.spaceId, parentId)
    nodesById[parentKey]?.let { parent ->
        nodesById[parentKey] = parent.copy(hasChildren = branchOrder[branch].orEmpty().isNotEmpty())
    }
}

internal fun FakeDocumentProjectionStore.fenceDocumentRelationships(
    key: DocumentKey,
    vararg parentIds: String?,
) {
    parentIds.distinct().forEach { parentId ->
        val branch = BranchKey(key.spaceId, parentId)
        branchSnapshots.invalidate(branch.snapshotKey)
    }
    resetPathSpineSnapshots()
    homeSnapshots.reset()
    pruneTrackedSnapshotLeases()
}

private fun fakeCompositeKey(first: String, second: String?): String =
    "${first.length}:$first:${second?.length ?: -1}:${second.orEmpty()}"
