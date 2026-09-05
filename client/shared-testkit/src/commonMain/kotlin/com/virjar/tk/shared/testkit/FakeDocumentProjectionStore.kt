package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.DocumentHomeCollection
import com.virjar.tk.shared.client.KeyedProjectionSnapshotGate
import com.virjar.tk.shared.client.LocalDocumentProjectionLimits
import com.virjar.tk.shared.client.LocalDocumentProjectionPolicy
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DOCUMENT_NODE_SIBLING_ORDER

/** SQL 文档投影的确定性内存版对应实现。 */
internal class FakeDocumentProjectionStore {
    private val lock = Any()
    private val spaceSnapshots = KeyedProjectionSnapshotGate("fake document spaces snapshot")
    private val spaceMutationSnapshots = KeyedProjectionSnapshotGate("fake document space mutation commit")
    internal val homeSnapshots = KeyedProjectionSnapshotGate("fake document home snapshot")
    internal val branchSnapshots = KeyedProjectionSnapshotGate("fake document branch snapshot")
    internal val pathSpineSnapshots = KeyedProjectionSnapshotGate("fake document path spine snapshot")
    internal val bodySnapshots = KeyedProjectionSnapshotGate("fake document body snapshot")
    internal val bodyMutationSnapshots = KeyedProjectionSnapshotGate("fake document body mutation commit")

    private var spaceSnapshotBoundary: SpaceSnapshotBoundary? = null
    private val spaceMutationSnapshotLeases = mutableMapOf<String, ProjectionSnapshotLease>()
    private val homeSnapshotLeases = mutableMapOf<DocumentHomeCollection, ProjectionSnapshotLease>()
    private val branchSnapshotLeases = mutableMapOf<BranchKey, ProjectionSnapshotLease>()
    private val pathSpineSnapshotLeases = mutableMapOf<DocumentKey, ProjectionSnapshotLease>()
    private val bodySnapshotLeases = mutableMapOf<DocumentKey, ProjectionSnapshotLease>()
    private val bodyMutationSnapshotLeases = mutableMapOf<DocumentKey, ProjectionSnapshotLease>()

    private var spaces = emptyList<DocumentSpace>()
    private var spaceSnapshotCached = false
    internal val home = mutableMapOf<DocumentHomeCollection, List<DocumentHomeItem>>()
    private val homeSnapshotsCached = mutableSetOf<DocumentHomeCollection>()
    internal val branchMarkers = linkedSetOf<BranchKey>()
    internal val branchOrder = mutableMapOf<BranchKey, List<String>>()
    internal val nodesById = mutableMapOf<DocumentKey, DocumentNode>()
    internal val bodies = mutableMapOf<DocumentKey, Document>()
    internal val bodyLru = mutableListOf<DocumentKey>()

    fun getSpaces(): List<DocumentSpace> = synchronized(lock) { spaces }

    fun isSpaceSnapshotCached(): Boolean = synchronized(lock) { spaceSnapshotCached }

    fun beginSpaceSnapshot(): ProjectionSnapshotLease = synchronized(lock) {
        val initialSpaceIds = knownSpaceIds()
        require(initialSpaceIds.size <= LocalDocumentProjectionLimits.MAX_SPACE_PROJECTION_IDENTITIES) {
            "document space projection exceeded its bounded identity set"
        }
        SpaceSnapshotBoundary(
            lease = spaceSnapshots.begin(ALL_SPACES_KEY),
            initialSpaceIds = initialSpaceIds,
        ).also { spaceSnapshotBoundary = it }.lease
    }

    fun applySpaceSnapshot(
        lease: ProjectionSnapshotLease,
        candidate: List<DocumentSpace>,
    ): Boolean = synchronized(lock) {
        val snapshot = LocalDocumentProjectionPolicy.normalizeSpaces(candidate)
        consumeSpaceSnapshotBoundary(lease) ?: return@synchronized false
        resetDependentProjectionGates()
        val visible = snapshot.mapTo(hashSetOf(), DocumentSpace::spaceId)
        spaces = snapshot
        spaceSnapshotCached = true
        home.keys.toList().forEach { collection ->
            home[collection] = home[collection].orEmpty().filter { it.spaceId in visible }
        }
        snapshot.forEach { space -> updateHomeSpaceName(space.spaceId, space.name) }
        branchMarkers.filterTo(mutableListOf()) { it.spaceId !in visible }.forEach(::purgeBranch)
        branchOrder.keys.filterTo(mutableListOf()) { it.spaceId !in visible }.forEach(::purgeBranch)
        nodesById.keys.removeAll { it.spaceId !in visible }
        bodies.keys.filter { it.spaceId !in visible }.forEach(::purgeBody)
        true
    }

    fun applySpacePage(
        lease: ProjectionSnapshotLease,
        candidate: List<DocumentSpace>,
        isFirstPage: Boolean,
    ): Boolean = synchronized(lock) {
        val page = LocalDocumentProjectionPolicy.normalizeSpaces(candidate)
        consumeSpaceSnapshotBoundary(lease) ?: return@synchronized false
        spaces = mergeFakeSpacePage(spaces, page, isFirstPage)
        spaceSnapshotCached = true
        page.forEach { updateHomeSpaceName(it.spaceId, it.name) }
        true
    }

    fun applySpaceRefreshPage(
        lease: ProjectionSnapshotLease,
        candidate: List<DocumentSpace>,
        isFirstPage: Boolean,
        isTerminal: Boolean,
    ): Boolean = synchronized(lock) {
        val page = LocalDocumentProjectionPolicy.normalizeSpaces(candidate)
        val boundary = currentSpaceSnapshotBoundary(lease) ?: return@synchronized false
        require(isFirstPage == (boundary.appliedPageCount == 0)) {
            "document space refresh page escaped its cursor cycle"
        }
        val pageIds = page.mapTo(linkedSetOf(), DocumentSpace::spaceId)
        require(page.zipWithNext().all { (first, second) -> first.spaceId < second.spaceId }) {
            "document space refresh page is not strictly ordered"
        }
        require(page.firstOrNull()?.spaceId?.let { first ->
            boundary.lastSpaceId?.let { previous -> first > previous } ?: true
        } != false) {
            "document space refresh page overlapped its predecessor"
        }
        val seenInitialAfterPage = LinkedHashSet<String>(boundary.seenInitialSpaceIds).apply {
            pageIds.filterTo(this, boundary.initialSpaceIds::contains)
        }
        val omitted = if (isTerminal) boundary.initialSpaceIds - seenInitialAfterPage else emptySet()
        spaces = mergeFakeSpacePage(spaces, page, isFirstPage)
            .filterNot { it.spaceId in omitted }
        page.forEach { updateHomeSpaceName(it.spaceId, it.name) }
        omitted.forEach(::deleteSpaceProjection)
        if (isTerminal) spaceSnapshotCached = true

        boundary.seenInitialSpaceIds.clear()
        boundary.seenInitialSpaceIds.addAll(seenInitialAfterPage)
        page.lastOrNull()?.spaceId?.let { boundary.lastSpaceId = it }
        boundary.appliedPageCount += 1
        if (isTerminal) {
            check(spaceSnapshots.consumeIfCurrent(lease, ALL_SPACES_KEY))
            spaceSnapshotBoundary = null
        }
        true
    }

    fun beginSpaceMutationSnapshot(spaceId: String): ProjectionSnapshotLease = synchronized(lock) {
        val normalized = LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId")
        spaceMutationSnapshots.begin(normalized).also { spaceMutationSnapshotLeases[normalized] = it }
    }

    fun applySpaceMutation(
        projectionLease: ProjectionSnapshotLease,
        candidate: DocumentSpace,
    ): Boolean = synchronized(lock) {
        val space = LocalDocumentProjectionPolicy.normalizeSpaces(listOf(candidate)).single()
        if (!spaceMutationSnapshots.consumeIfCurrent(projectionLease, space.spaceId)) {
            return@synchronized false
        }
        if (spaceMutationSnapshotLeases[space.spaceId] === projectionLease) {
            spaceMutationSnapshotLeases.remove(space.spaceId)
        }
        spaceSnapshots.invalidate(ALL_SPACES_KEY)
        spaceSnapshotBoundary = null
        homeSnapshots.reset()
        homeSnapshotLeases.clear()
        val existing = spaces.any { it.spaceId == space.spaceId }
        spaces = if (existing) {
            spaces.map { cached -> if (cached.spaceId == space.spaceId) space else cached }
        } else {
            (listOf(space) + spaces).take(LocalDocumentProjectionLimits.MAX_SPACES)
        }
        updateHomeSpaceName(space.spaceId, space.name)
        true
    }

    fun getHome(collection: DocumentHomeCollection): List<DocumentHomeItem> =
        synchronized(lock) { home[collection].orEmpty() }

    fun isHomeSnapshotCached(collection: DocumentHomeCollection): Boolean =
        synchronized(lock) { collection in homeSnapshotsCached }

    fun beginHomeSnapshot(collection: DocumentHomeCollection): ProjectionSnapshotLease = synchronized(lock) {
        homeSnapshots.begin(homeSnapshotKey(collection)).also { homeSnapshotLeases[collection] = it }
    }

    fun applyHomeSnapshot(
        lease: ProjectionSnapshotLease,
        collection: DocumentHomeCollection,
        candidate: List<DocumentHomeItem>,
    ): Boolean = synchronized(lock) {
        val snapshot = LocalDocumentProjectionPolicy.normalizeHome(candidate)
        if (!homeSnapshots.consumeIfCurrent(lease, homeSnapshotKey(collection))) {
            return@synchronized false
        }
        if (homeSnapshotLeases[collection] === lease) homeSnapshotLeases.remove(collection)
        home[collection] = snapshot
        homeSnapshotsCached += collection
        true
    }

    fun isBranchCached(spaceId: String, parentId: String?): Boolean = synchronized(lock) {
        fakeBranchKey(spaceId, parentId) in branchMarkers
    }

    fun getNodes(spaceId: String, parentId: String?): List<DocumentNode> = synchronized(lock) {
        val branch = fakeBranchKey(spaceId, parentId)
        if (branch !in branchMarkers) return@synchronized emptyList()
        branchOrder[branch].orEmpty().mapNotNull { nodeId ->
            nodesById[DocumentKey(branch.spaceId, nodeId)]
        }
    }

    fun beginBranchSnapshot(spaceId: String, parentId: String?): ProjectionSnapshotLease = synchronized(lock) {
        val branch = fakeBranchKey(spaceId, parentId)
        branchSnapshots.begin(branch.snapshotKey).also { branchSnapshotLeases[branch] = it }
    }

    fun applyBranchSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        parentId: String?,
        candidate: List<DocumentNode>,
    ): Boolean = synchronized(lock) {
        val branch = fakeBranchKey(spaceId, parentId)
        val snapshot = LocalDocumentProjectionPolicy.normalizeNodes(spaceId, parentId, candidate)
        if (!branchSnapshots.consumeIfCurrent(lease, branch.snapshotKey)) return@synchronized false
        if (branchSnapshotLeases[branch] === lease) branchSnapshotLeases.remove(branch)
        require(branch in branchMarkers || branchMarkers.size < LocalDocumentProjectionLimits.MAX_BRANCHES)
        val resolved = snapshot.mapNotNull { incoming ->
            val existing = nodesById[DocumentKey(branch.spaceId, incoming.nodeId)]
                ?: return@mapNotNull incoming
            when {
                incoming.revision > existing.revision -> incoming
                incoming.revision < existing.revision -> existing.takeIf { it.parentId == parentId }
                else -> {
                    check(existing.copy(hasChildren = incoming.hasChildren) == incoming) {
                        "same document node revision has conflicting parent or content"
                    }
                    incoming
                }
            }
        }.sortedWith(DOCUMENT_NODE_SIBLING_ORDER)
        val oldKeys = branchOrder[branch].orEmpty().mapTo(hashSetOf()) {
            DocumentKey(branch.spaceId, it)
        }
        val projectedKeys = (nodesById.keys - oldKeys) + resolved.map {
            DocumentKey(branch.spaceId, it.nodeId)
        }
        require(projectedKeys.size <= LocalDocumentProjectionLimits.MAX_NODES)

        branchOrder[branch].orEmpty().forEach { oldId ->
            val key = DocumentKey(branch.spaceId, oldId)
            if (nodesById[key]?.parentId == parentId) nodesById.remove(key)
        }
        resolved.forEach { node ->
            val key = DocumentKey(branch.spaceId, node.nodeId)
            nodesById[key]?.let { previous ->
                val previousBranch = BranchKey(branch.spaceId, previous.parentId)
                branchOrder[previousBranch] = branchOrder[previousBranch].orEmpty() - node.nodeId
            }
            nodesById[key] = node
        }
        branchMarkers += branch
        branchOrder[branch] = resolved.map(DocumentNode::nodeId)
        resetPathSpineSnapshots()
        updateParentHasChildren(branch)
        true
    }

    fun getPathSpine(spaceId: String, nodeId: String): DocumentPathSpine? = synchronized(lock) {
        val identity = fakeDocumentKey(spaceId, nodeId)
        val targetToRoot = ArrayList<DocumentNode>(DocumentPathSpine.MAX_NODES)
        val visited = hashSetOf<String>()
        var currentNodeId = identity.documentId
        repeat(DocumentPathSpine.MAX_NODES) {
            val node = nodesById[DocumentKey(identity.spaceId, currentNodeId)] ?: return@synchronized null
            if (!visited.add(node.nodeId)) return@synchronized null
            targetToRoot += node
            val parentId = node.parentId
            if (parentId == null) {
                return@synchronized LocalDocumentProjectionPolicy.normalizePathSpine(
                    identity.spaceId,
                    identity.documentId,
                    DocumentPathSpine(targetToRoot.asReversed()),
                )
            }
            currentNodeId = parentId
        }
        null
    }

    fun beginPathSpineSnapshot(spaceId: String, nodeId: String): ProjectionSnapshotLease = synchronized(lock) {
        val identity = fakeDocumentKey(spaceId, nodeId)
        pathSpineSnapshots.begin(identity.snapshotKey).also { pathSpineSnapshotLeases[identity] = it }
    }

    fun applyPathSpineSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        nodeId: String,
        candidate: DocumentPathSpine,
    ): Boolean = synchronized(lock) {
        val identity = fakeDocumentKey(spaceId, nodeId)
        val spine = LocalDocumentProjectionPolicy.normalizePathSpine(spaceId, nodeId, candidate)
        if (!pathSpineSnapshots.consumeIfCurrent(lease, identity.snapshotKey)) return@synchronized false
        if (pathSpineSnapshotLeases[identity] === lease) pathSpineSnapshotLeases.remove(identity)

        spine.nodes.forEach { incoming ->
            val existing = nodesById[DocumentKey(incoming.spaceId, incoming.nodeId)] ?: return@forEach
            if (incoming.revision < existing.revision) return@synchronized false
            if (incoming.revision == existing.revision) {
                check(existing.copy(hasChildren = incoming.hasChildren) == incoming) {
                    "same document path revision has conflicting node content"
                }
            }
        }
        val addedNodeCount = spine.nodes.count { node ->
            DocumentKey(node.spaceId, node.nodeId) !in nodesById
        }
        require(nodesById.size + addedNodeCount <= LocalDocumentProjectionLimits.MAX_NODES) {
            "document node projection exceeds the local capacity"
        }

        val branchLanesToFence = linkedSetOf<BranchKey>()
        val branchMarkersToInvalidate = linkedSetOf<BranchKey>()
        var relationshipChanged = false
        spine.nodes.forEach { incoming ->
            val key = DocumentKey(incoming.spaceId, incoming.nodeId)
            val existing = nodesById[key]
            val targetBranch = fakeBranchKey(incoming.spaceId, incoming.parentId)
            branchLanesToFence += targetBranch
            if (existing == null) {
                branchMarkersToInvalidate += targetBranch
                relationshipChanged = true
            } else if (existing.parentId != incoming.parentId) {
                val oldBranch = fakeBranchKey(incoming.spaceId, existing.parentId)
                branchMarkersToInvalidate += oldBranch
                branchMarkersToInvalidate += targetBranch
                branchLanesToFence += oldBranch
                relationshipChanged = true
            }
            val childBranch = fakeBranchKey(incoming.spaceId, incoming.nodeId)
            branchLanesToFence += childBranch
            if (childBranch in branchMarkers &&
                branchOrder[childBranch].orEmpty().isNotEmpty() != incoming.hasChildren
            ) {
                branchMarkersToInvalidate += childBranch
                relationshipChanged = true
            }
        }

        resetPathSpineSnapshots()
        branchLanesToFence.forEach { branchSnapshots.invalidate(it.snapshotKey) }
        branchSnapshotLeases.entries.removeAll { (branch, lease) ->
            branch in branchLanesToFence && !branchSnapshots.isCurrent(lease)
        }
        if (relationshipChanged) {
            bodySnapshots.reset()
            bodySnapshotLeases.clear()
        }
        branchMarkers.removeAll(branchMarkersToInvalidate)
        spine.nodes.forEach { incoming ->
            val key = DocumentKey(incoming.spaceId, incoming.nodeId)
            val existing = nodesById[key]
            existing?.let { previous ->
                if (previous.parentId != incoming.parentId) {
                    val previousBranch = fakeBranchKey(incoming.spaceId, previous.parentId)
                    branchOrder[previousBranch] = branchOrder[previousBranch].orEmpty() - incoming.nodeId
                }
            }
            val targetBranch = fakeBranchKey(incoming.spaceId, incoming.parentId)
            if (incoming.nodeId !in branchOrder[targetBranch].orEmpty()) {
                branchOrder[targetBranch] = branchOrder[targetBranch].orEmpty() + incoming.nodeId
            }
            nodesById[key] = incoming
            sortBranchBySiblingOrder(targetBranch)
        }
        true
    }

    fun getBody(spaceId: String, documentId: String): Document? = synchronized(lock) {
        val key = fakeDocumentKey(spaceId, documentId)
        bodies[key]?.also { touchBody(key) }
    }

    fun beginBodySnapshot(spaceId: String, documentId: String): ProjectionSnapshotLease = synchronized(lock) {
        val key = fakeDocumentKey(spaceId, documentId)
        bodySnapshots.begin(key.snapshotKey).also { bodySnapshotLeases[key] = it }
    }

    fun beginBodyMutationSnapshot(spaceId: String, documentId: String): ProjectionSnapshotLease =
        synchronized(lock) {
            val key = fakeDocumentKey(spaceId, documentId)
            bodyMutationSnapshots.begin(key.snapshotKey).also { bodyMutationSnapshotLeases[key] = it }
        }

    fun applyBodySnapshot(lease: ProjectionSnapshotLease, document: Document): Boolean = synchronized(lock) {
        val key = fakeDocumentKey(document.spaceId, document.documentId)
        LocalDocumentProjectionPolicy.validateBody(document)
        if (!bodySnapshots.consumeIfCurrent(lease, key.snapshotKey)) return@synchronized false
        if (bodySnapshotLeases[key] === lease) bodySnapshotLeases.remove(key)
        val oldParentId = nodesById[key]?.parentId ?: bodies[key]?.parentId
        fenceDocumentRelationships(key, oldParentId, document.parentId)
        applyDocumentLocked(document)
    }

    fun applyBodyMutation(
        projectionLease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = synchronized(lock) {
        val key = fakeDocumentKey(document.spaceId, document.documentId)
        LocalDocumentProjectionPolicy.validateBody(document)
        if (!bodyMutationSnapshots.consumeIfCurrent(projectionLease, key.snapshotKey)) {
            return@synchronized false
        }
        if (bodyMutationSnapshotLeases[key] === projectionLease) {
            bodyMutationSnapshotLeases.remove(key)
        }
        bodySnapshots.invalidate(key.snapshotKey)
        val oldParentId = nodesById[key]?.parentId ?: bodies[key]?.parentId
        fenceDocumentRelationships(key, oldParentId, document.parentId)
        applyDocumentLocked(document)
    }

    fun applyMove(
        projectionLease: ProjectionSnapshotLease,
        result: DocumentMoveResult,
    ): Boolean = synchronized(lock) {
        LocalDocumentProjectionPolicy.validateMove(result)
        val node = result.node
        val key = fakeDocumentKey(node.spaceId, node.nodeId)
        if (!bodyMutationSnapshots.consumeIfCurrent(projectionLease, key.snapshotKey)) {
            return@synchronized false
        }
        if (bodyMutationSnapshotLeases[key] === projectionLease) {
            bodyMutationSnapshotLeases.remove(key)
        }
        val existingNode = nodesById[key]
        val existingBody = bodies[key]
        val highestRevision = maxOf(existingNode?.revision ?: 0L, existingBody?.revision ?: 0L)
        if (node.revision < highestRevision) return@synchronized false
        if (existingNode != null && node.revision == existingNode.revision) {
            check(existingNode.copy(hasChildren = node.hasChildren) == node) {
                "same document move revision has conflicting node content"
            }
        }
        if (existingBody != null && node.revision == existingBody.revision) {
            check(
                existingBody.parentId == node.parentId &&
                    existingBody.title == node.name &&
                    existingBody.createdBy == node.createdBy &&
                    existingBody.createdAt == node.createdAt &&
                    existingBody.updatedBy == node.updatedBy &&
                    existingBody.updatedAt == node.updatedAt &&
                    existingBody.ancestorIds == result.ancestorIds,
            ) { "same document move revision has conflicting body identity or ancestry" }
        }

        val oldParentId = existingNode?.parentId ?: existingBody?.parentId
        fenceDocumentRelationships(key, oldParentId, node.parentId)
        bodySnapshots.reset()
        bodySnapshotLeases.clear()
        val descendantKeys = bodies.keys.filter { descendantKey ->
            descendantKey.spaceId == node.spaceId && node.nodeId in bodies[descendantKey].fakeAncestorIds()
        }
        descendantKeys.forEach { descendantKey ->
            bodyMutationSnapshots.invalidate(descendantKey.snapshotKey)
            bodyMutationSnapshotLeases.remove(descendantKey)
            purgeBody(descendantKey)
        }
        existingBody?.takeIf { node.revision >= it.revision }?.let { body ->
            bodies[key] = body.copy(
                parentId = node.parentId,
                title = node.name,
                revision = node.revision,
                updatedBy = node.updatedBy,
                updatedAt = node.updatedAt,
                ancestorIds = result.ancestorIds,
            )
            touchBody(key)
        }
        convergeNode(node)
        updateHomeDocument(node)
        updateParentHasChildren(fakeBranchKey(node.spaceId, oldParentId))
        if (oldParentId != node.parentId) {
            updateParentHasChildren(fakeBranchKey(node.spaceId, node.parentId))
        }
        pruneBodies()
        true
    }

    fun purgeSpace(spaceId: String) = synchronized(lock) {
        val normalized = LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId")
        resetProjectionGates()
        deleteSpaceProjection(normalized)
    }

    private fun deleteSpaceProjection(spaceId: String) {
        spaces = spaces.filterNot { it.spaceId == spaceId }
        home.keys.toList().forEach { collection ->
            home[collection] = home[collection].orEmpty().filterNot { it.spaceId == spaceId }
        }
        branchMarkers.filterTo(mutableListOf()) { it.spaceId == spaceId }.forEach(::purgeBranch)
        branchOrder.keys.filterTo(mutableListOf()) { it.spaceId == spaceId }.forEach(::purgeBranch)
        nodesById.keys.removeAll { it.spaceId == spaceId }
        bodies.keys.filter { it.spaceId == spaceId }.forEach(::purgeBody)
    }

    fun purgeDocument(spaceId: String, documentId: String) = synchronized(lock) {
        purgeDocumentLocked(fakeDocumentKey(spaceId, documentId))
    }

    private fun purgeDocumentLocked(key: DocumentKey) {
        val affectedIds = linkedSetOf(key.documentId)
        bodies.forEach { (candidateKey, body) ->
            if (candidateKey.spaceId == key.spaceId && key.documentId in body.ancestorIds) {
                affectedIds += candidateKey.documentId
            }
        }
        val pendingIds = affectedIds.toMutableList()
        var pendingIndex = 0
        while (pendingIndex < pendingIds.size) {
            val parent = pendingIds[pendingIndex++]
            branchOrder[BranchKey(key.spaceId, parent)].orEmpty().forEach { childId ->
                if (affectedIds.add(childId)) pendingIds += childId
            }
        }
        affectedIds.forEach { documentId ->
            val affectedKey = DocumentKey(key.spaceId, documentId)
            bodyMutationSnapshots.invalidate(affectedKey.snapshotKey)
            bodyMutationSnapshotLeases.remove(affectedKey)
        }
        val parentsToRefresh = affectedIds.mapNotNullTo(linkedSetOf<String>()) { documentId ->
            nodesById[DocumentKey(key.spaceId, documentId)]?.parentId
        }
        homeSnapshots.reset()
        branchSnapshots.reset()
        resetPathSpineSnapshots()
        bodySnapshots.reset()
        homeSnapshotLeases.clear()
        branchSnapshotLeases.clear()
        bodySnapshotLeases.clear()
        home.keys.toList().forEach { collection ->
            home[collection] = home[collection].orEmpty().filterNot {
                it.spaceId == key.spaceId && it.documentId in affectedIds
            }
        }
        affectedIds.forEach { documentId ->
            nodesById.remove(DocumentKey(key.spaceId, documentId))
        }
        branchOrder.keys.toList().forEach { branch ->
            if (branch.spaceId == key.spaceId) {
                branchOrder[branch] = branchOrder[branch].orEmpty().filterNot(affectedIds::contains)
            }
        }
        affectedIds.forEach { documentId ->
            purgeBody(DocumentKey(key.spaceId, documentId))
            purgeBranch(fakeBranchKey(key.spaceId, documentId))
        }
        parentsToRefresh.forEach { parentId ->
            updateParentHasChildren(fakeBranchKey(key.spaceId, parentId))
        }
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = synchronized(lock) {
        val abandoned = spaceSnapshots.abandon(lease) ||
            spaceMutationSnapshots.abandon(lease) ||
            homeSnapshots.abandon(lease) ||
            branchSnapshots.abandon(lease) ||
            pathSpineSnapshots.abandon(lease) ||
            bodySnapshots.abandon(lease) ||
            bodyMutationSnapshots.abandon(lease)
        if (abandoned) removeTrackedLease(lease)
        abandoned
    }

    fun activeSnapshotCountForTest(): Int = synchronized(lock) {
        (if (spaceSnapshotBoundary == null) 0 else 1) +
            spaceMutationSnapshotLeases.size +
            homeSnapshotLeases.size +
            branchSnapshotLeases.size +
            pathSpineSnapshotLeases.size +
            bodySnapshotLeases.size +
            bodyMutationSnapshotLeases.size
    }

    fun resetProjection() = synchronized(lock) {
        spaces = emptyList()
        spaceSnapshotCached = false
        home.clear()
        homeSnapshotsCached.clear()
        branchMarkers.clear()
        branchOrder.clear()
        nodesById.clear()
        bodies.clear()
        bodyLru.clear()
        resetProjectionGates()
    }

    fun close() = resetProjection()

    private fun resetProjectionGates() {
        spaceSnapshots.reset()
        resetDependentProjectionGates()
        spaceSnapshotBoundary = null
    }

    private fun resetDependentProjectionGates() {
        spaceMutationSnapshots.reset()
        homeSnapshots.reset()
        branchSnapshots.reset()
        resetPathSpineSnapshots()
        bodySnapshots.reset()
        bodyMutationSnapshots.reset()
        spaceMutationSnapshotLeases.clear()
        homeSnapshotLeases.clear()
        branchSnapshotLeases.clear()
        bodySnapshotLeases.clear()
        bodyMutationSnapshotLeases.clear()
    }

    private fun currentSpaceSnapshotBoundary(lease: ProjectionSnapshotLease): SpaceSnapshotBoundary? {
        if (!spaceSnapshots.isCurrent(lease)) return null
        return spaceSnapshotBoundary.takeIf { it?.lease === lease }
    }

    private fun consumeSpaceSnapshotBoundary(lease: ProjectionSnapshotLease): SpaceSnapshotBoundary? {
        val boundary = currentSpaceSnapshotBoundary(lease) ?: return null
        check(spaceSnapshots.consumeIfCurrent(lease, ALL_SPACES_KEY))
        spaceSnapshotBoundary = null
        return boundary
    }

    private fun knownSpaceIds(): Set<String> = buildSet {
        spaces.mapTo(this, DocumentSpace::spaceId)
        home.values.asSequence().flatten().mapTo(this, DocumentHomeItem::spaceId)
        branchMarkers.mapTo(this, BranchKey::spaceId)
        nodesById.keys.mapTo(this, DocumentKey::spaceId)
        bodies.keys.mapTo(this, DocumentKey::spaceId)
    }

    private fun removeTrackedLease(lease: ProjectionSnapshotLease) {
        if (spaceSnapshotBoundary?.lease === lease) spaceSnapshotBoundary = null
        spaceMutationSnapshotLeases.entries.removeAll { (_, current) -> current === lease }
        homeSnapshotLeases.entries.removeAll { (_, current) -> current === lease }
        branchSnapshotLeases.entries.removeAll { (_, current) -> current === lease }
        pathSpineSnapshotLeases.entries.removeAll { (_, current) -> current === lease }
        bodySnapshotLeases.entries.removeAll { (_, current) -> current === lease }
        bodyMutationSnapshotLeases.entries.removeAll { (_, current) -> current === lease }
    }

    internal fun pruneTrackedSnapshotLeases() {
        homeSnapshotLeases.entries.removeAll { (_, lease) -> !homeSnapshots.isCurrent(lease) }
        branchSnapshotLeases.entries.removeAll { (_, lease) -> !branchSnapshots.isCurrent(lease) }
        pathSpineSnapshotLeases.entries.removeAll { (_, lease) -> !pathSpineSnapshots.isCurrent(lease) }
        bodySnapshotLeases.entries.removeAll { (_, lease) -> !bodySnapshots.isCurrent(lease) }
        bodyMutationSnapshotLeases.entries.removeAll { (_, lease) -> !bodyMutationSnapshots.isCurrent(lease) }
        spaceMutationSnapshotLeases.entries.removeAll { (_, lease) -> !spaceMutationSnapshots.isCurrent(lease) }
        if (spaceSnapshotBoundary?.lease?.let(spaceSnapshots::isCurrent) != true) {
            spaceSnapshotBoundary = null
        }
    }

    private fun homeSnapshotKey(collection: DocumentHomeCollection): String = "home:${collection.name}"

    internal fun resetPathSpineSnapshots() {
        pathSpineSnapshots.reset()
        pathSpineSnapshotLeases.clear()
    }

    private companion object {
        const val ALL_SPACES_KEY = "all-spaces"
    }
}
