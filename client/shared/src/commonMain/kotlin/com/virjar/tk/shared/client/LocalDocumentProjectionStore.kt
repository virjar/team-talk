package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace

/** 一个 [LocalCacheImpl] 拥有的、SQL 支撑的干净企业文档投影。 */
internal class LocalDocumentProjectionStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    private val spaceSnapshots = KeyedProjectionSnapshotGate("document spaces snapshot")
    private val spaceMutationSnapshots = KeyedProjectionSnapshotGate("document space mutation commit")
    private val branchSnapshots = KeyedProjectionSnapshotGate("document branch snapshot")
    private val pathSpineSnapshots = KeyedProjectionSnapshotGate("document path spine snapshot")
    private val bodySnapshots = KeyedProjectionSnapshotGate("document body snapshot")
    private val bodyMutationSnapshots = KeyedProjectionSnapshotGate("document body mutation commit")
    private val bodies = LocalDocumentBodyProjectionStore(queries)
    private val persistence = LocalDocumentProjectionPersistence(queries, SPACES_PROJECTION_CODE)
    private val home = LocalDocumentHomeProjectionStore(
        queries = queries,
        persistence = persistence,
        cacheUseGate = cacheUseGate,
        stateLock = stateLock,
    )
    private val content = LocalDocumentContentProjectionConvergence(
        queries, persistence, bodies, home, branchSnapshots, pathSpineSnapshots,
    )
    private var spaceSnapshotBoundary: DocumentSpaceSnapshotBoundary? = null

    fun isSpaceSnapshotCached(): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            queries.isDocumentProjectionCached(SPACES_PROJECTION_CODE).executeAsOne() > 0L
        }
    }

    fun getSpaces(): List<DocumentSpace> = cacheUseGate.use {
        synchronized(stateLock) {
            persistence.loadSpacesLocked()
        }
    }

    fun beginSpaceSnapshot(): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) {
            beginSpaceSnapshotBoundaryLocked().also { spaceSnapshotBoundary = it }.lease
        }
    }

    fun applySpaceSnapshot(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
    ): Boolean = cacheUseGate.runIfOpen {
        val snapshot = LocalDocumentProjectionPolicy.normalizeSpaces(spaces)
        synchronized(stateLock) {
            consumeSpaceSnapshotBoundaryLocked(lease) ?: return@synchronized false
            resetDependentProjectionGatesLocked()
            queries.transaction {
                queries.deleteAllDocumentSpaces()
                snapshot.forEachIndexed { index, space -> persistence.persistSpaceLocked(space, index) }
                queries.markDocumentProjectionCached(SPACES_PROJECTION_CODE)
                bodies.deleteUnknownSpacesLocked()
                queries.deleteDocumentHomeForUnknownSpaces()
                queries.deleteDocumentNodesForUnknownSpaces()
                queries.deleteDocumentBranchesForUnknownSpaces()
                snapshot.forEach { space ->
                    queries.updateDocumentHomeSpaceName(space.name, space.spaceId)
                }
            }
            true
        }
    }

    fun applySpacePage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
    ): Boolean = cacheUseGate.runIfOpen {
        val page = LocalDocumentProjectionPolicy.normalizeSpaces(spaces)
        synchronized(stateLock) {
            consumeSpaceSnapshotBoundaryLocked(lease) ?: return@synchronized false
            val current = persistence.loadSpacesLocked()
            val merged = mergeSpacePage(current, page, isFirstPage)
            val mergedById = merged.associateBy(DocumentSpace::spaceId)
            persistence.replaceSpaceWorkingSetLocked(
                replacement = merged,
                markCached = true,
                refreshedRows = page.mapNotNull { remote -> mergedById[remote.spaceId] },
            )
            true
        }
    }

    fun applySpaceRefreshPage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
        isTerminal: Boolean,
    ): Boolean = cacheUseGate.runIfOpen {
        val page = LocalDocumentProjectionPolicy.normalizeSpaces(spaces)
        synchronized(stateLock) {
            val boundary = currentSpaceSnapshotBoundaryLocked(lease) ?: return@synchronized false
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

            val current = persistence.loadSpacesLocked()
            val omitted = if (isTerminal) boundary.initialSpaceIds - seenInitialAfterPage else emptySet()
            val merged = mergeSpacePage(current, page, isFirstPage)
                .filterNot { it.spaceId in omitted }
            val mergedById = merged.associateBy(DocumentSpace::spaceId)
            queries.transaction {
                persistence.replaceSpaceWorkingSetLocked(
                    replacement = merged,
                    markCached = isTerminal,
                    refreshedRows = page.mapNotNull { remote -> mergedById[remote.spaceId] },
                )
                omitted.forEach(::deleteSpaceProjectionRowsLocked)
            }

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
    }

    fun applySpaceMutation(
        projectionLease: ProjectionSnapshotLease,
        space: DocumentSpace,
    ): Boolean = cacheUseGate.runIfOpen {
        val validated = LocalDocumentProjectionPolicy.normalizeSpaces(listOf(space)).single()
        synchronized(stateLock) {
            if (!spaceMutationSnapshots.consumeIfCurrent(projectionLease, validated.spaceId)) {
                return@synchronized false
            }
            spaceSnapshots.invalidate(ALL_SPACES_KEY)
            pruneSpaceSnapshotBoundaryLocked()
            home.reset()
            val current = persistence.loadSpacesLocked()
            val existing = current.firstOrNull { it.spaceId == validated.spaceId }
            val merged = if (existing != null) {
                current.map { cached -> if (cached.spaceId == validated.spaceId) validated else cached }
            } else {
                (listOf(validated) + current).take(LocalDocumentProjectionLimits.MAX_SPACES)
            }
            persistence.replaceSpaceWorkingSetLocked(
                replacement = merged,
                markCached = false,
                refreshedRows = listOf(validated),
            )
            true
        }
    }

    fun beginSpaceMutationSnapshot(spaceId: String): ProjectionSnapshotLease = cacheUseGate.use {
        val normalizedSpaceId = LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId")
        synchronized(stateLock) { spaceMutationSnapshots.begin(normalizedSpaceId) }
    }

    fun getHome(collection: DocumentHomeCollection): List<DocumentHomeItem> = home.get(collection)

    fun isHomeSnapshotCached(collection: DocumentHomeCollection): Boolean = home.isCached(collection)

    fun beginHomeSnapshot(collection: DocumentHomeCollection): ProjectionSnapshotLease =
        home.begin(collection)

    fun applyHomeSnapshot(
        lease: ProjectionSnapshotLease,
        collection: DocumentHomeCollection,
        items: List<DocumentHomeItem>,
    ): Boolean = home.apply(lease, collection, items)

    fun isBranchCached(spaceId: String, parentId: String?): Boolean = cacheUseGate.use {
        val branch = normalizedBranch(spaceId, parentId)
        synchronized(stateLock) {
            queries.isDocumentBranchCached(branch.spaceId, branch.parentKey).executeAsOne() > 0L
        }
    }

    fun getNodes(spaceId: String, parentId: String?): List<DocumentNode> = cacheUseGate.use {
        val branch = normalizedBranch(spaceId, parentId)
        synchronized(stateLock) {
            if (queries.isDocumentBranchCached(branch.spaceId, branch.parentKey).executeAsOne() == 0L) {
                return@synchronized emptyList()
            }
            queries.selectDocumentNodes(branch.spaceId, branch.parentKey).executeAsList()
                .map { it.toLocalDocumentNode() }
        }
    }

    fun beginBranchSnapshot(spaceId: String, parentId: String?): ProjectionSnapshotLease =
        cacheUseGate.use {
            val branch = normalizedBranch(spaceId, parentId)
            synchronized(stateLock) { branchSnapshots.begin(branch.snapshotKey) }
        }

    fun applyBranchSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        parentId: String?,
        nodes: List<DocumentNode>,
    ): Boolean = cacheUseGate.runIfOpen {
        val branch = normalizedBranch(spaceId, parentId)
        val snapshot = LocalDocumentProjectionPolicy.normalizeNodes(branch.spaceId, branch.parentId, nodes)
        synchronized(stateLock) {
            if (!branchSnapshots.consumeIfCurrent(lease, branch.snapshotKey)) return@synchronized false
            val resolved = content.resolveBranchNodesLocked(branch, snapshot)
            val markerExists = queries.isDocumentBranchCached(branch.spaceId, branch.parentKey)
                .executeAsOne() > 0L
            val branchCount = queries.countDocumentBranches().executeAsOne()
            require(markerExists || branchCount < LocalDocumentProjectionLimits.MAX_BRANCHES.toLong()) {
                "document branch projection exceeds the local capacity"
            }
            val oldBranchCount = queries.countDocumentNodesByBranch(branch.spaceId, branch.parentKey)
                .executeAsOne()
            val projectedNodeCount = queries.countDocumentNodes().executeAsOne() - oldBranchCount +
                resolved.count { it.addsAfterBranchDelete }
            require(projectedNodeCount <= LocalDocumentProjectionLimits.MAX_NODES.toLong()) {
                "document node projection exceeds the local capacity"
            }
            pathSpineSnapshots.reset()
            queries.transaction {
                queries.deleteDocumentNodesByBranch(branch.spaceId, branch.parentKey)
                queries.upsertDocumentBranch(branch.spaceId, branch.parentKey)
                resolved.forEach { resolvedNode ->
                    persistence.persistNodeLocked(resolvedNode.node, branch.parentKey)
                }
                content.updateParentHasChildrenLocked(branch)
            }
            true
        }
    }

    fun getPathSpine(spaceId: String, nodeId: String): DocumentPathSpine? = cacheUseGate.use {
        val identity = normalizedDocumentIdentity(spaceId, nodeId)
        synchronized(stateLock) {
            loadPathSpineLocked(identity)
        }
    }

    fun beginPathSpineSnapshot(spaceId: String, nodeId: String): ProjectionSnapshotLease =
        cacheUseGate.use {
            val identity = normalizedDocumentIdentity(spaceId, nodeId)
            synchronized(stateLock) { pathSpineSnapshots.begin(identity.snapshotKey) }
        }

    fun applyPathSpineSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        nodeId: String,
        spine: DocumentPathSpine,
    ): Boolean = cacheUseGate.runIfOpen {
        val identity = normalizedDocumentIdentity(spaceId, nodeId)
        val snapshot = LocalDocumentProjectionPolicy.normalizePathSpine(spaceId, nodeId, spine)
        synchronized(stateLock) {
            if (!pathSpineSnapshots.consumeIfCurrent(lease, identity.snapshotKey)) {
                return@synchronized false
            }

            val existingRowsByNodeId = snapshot.nodes.associate { node ->
                node.nodeId to queries.selectDocumentNodeById(node.spaceId, node.nodeId).executeAsOneOrNull()
            }
            snapshot.nodes.forEach { incoming ->
                val row = existingRowsByNodeId.getValue(incoming.nodeId)
                val existing = row?.toLocalDocumentNode() ?: return@forEach
                if (incoming.revision < existing.revision) return@synchronized false
                if (incoming.revision == existing.revision) {
                    check(existing.copy(hasChildren = incoming.hasChildren) == incoming) {
                        "same document path revision has conflicting node content"
                    }
                }
            }
            val addedNodeCount = existingRowsByNodeId.values.count { it == null }
            require(
                queries.countDocumentNodes().executeAsOne() + addedNodeCount <=
                    LocalDocumentProjectionLimits.MAX_NODES.toLong(),
            ) { "document node projection exceeds the local capacity" }

            val branchLanesToFence = linkedSetOf<DocumentBranchIdentity>()
            val branchMarkersToInvalidate = linkedSetOf<DocumentBranchIdentity>()
            var relationshipChanged = false
            snapshot.nodes.forEach { incoming ->
                val row = existingRowsByNodeId.getValue(incoming.nodeId)
                val targetBranch = normalizedBranch(incoming.spaceId, incoming.parentId)
                branchLanesToFence += targetBranch
                if (row == null) {
                    branchMarkersToInvalidate += targetBranch
                    relationshipChanged = true
                } else if (row.parent_key != targetBranch.parentKey) {
                    val oldBranch = normalizedBranch(
                        incoming.spaceId,
                        parentIdFromDocumentStorage(row.parent_key),
                    )
                    branchMarkersToInvalidate += oldBranch
                    branchMarkersToInvalidate += targetBranch
                    branchLanesToFence += oldBranch
                    relationshipChanged = true
                }

                val childBranch = normalizedBranch(incoming.spaceId, incoming.nodeId)
                branchLanesToFence += childBranch
                val childMarkerExists = queries.isDocumentBranchCached(
                    childBranch.spaceId,
                    childBranch.parentKey,
                ).executeAsOne() > 0L
                if (childMarkerExists) {
                    val cachedHasChildren = queries.countDocumentNodesByBranch(
                        childBranch.spaceId,
                        childBranch.parentKey,
                    ).executeAsOne() > 0L
                    if (cachedHasChildren != incoming.hasChildren) {
                        branchMarkersToInvalidate += childBranch
                        relationshipChanged = true
                    }
                }
            }

            pathSpineSnapshots.reset()
            branchLanesToFence.forEach { branchSnapshots.invalidate(it.snapshotKey) }
            if (relationshipChanged) bodySnapshots.reset()
            queries.transaction {
                branchMarkersToInvalidate.forEach { branch ->
                    queries.deleteDocumentBranch(branch.spaceId, branch.parentKey)
                }
                snapshot.nodes.forEach { incoming ->
                    val parentKey = normalizedBranch(incoming.spaceId, incoming.parentId).parentKey
                    persistence.persistNodeLocked(incoming, parentKey)
                }
                // 仅 UPDATE：刷新已接受目标中已经有界的 Home 行，而不从部分路径投影准入
                // 祖先或目标条目。
                content.updateHomeDocumentLocked(snapshot.nodes.last())
            }
            true
        }
    }

    fun getBody(spaceId: String, documentId: String): Document? = cacheUseGate.use {
        val identity = normalizedDocumentIdentity(spaceId, documentId)
        synchronized(stateLock) {
            bodies.getAndTouchLocked(identity.spaceId, identity.documentId)
        }
    }

    fun beginBodySnapshot(spaceId: String, documentId: String): ProjectionSnapshotLease =
        cacheUseGate.use {
            val identity = normalizedDocumentIdentity(spaceId, documentId)
            synchronized(stateLock) { bodySnapshots.begin(identity.snapshotKey) }
        }

    fun beginBodyMutationSnapshot(spaceId: String, documentId: String): ProjectionSnapshotLease =
        cacheUseGate.use {
            val identity = normalizedDocumentIdentity(spaceId, documentId)
            synchronized(stateLock) { bodyMutationSnapshots.begin(identity.snapshotKey) }
        }

    fun applyBodySnapshot(
        lease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = cacheUseGate.runIfOpen {
        val identity = normalizedDocumentIdentity(document.spaceId, document.documentId)
        val bodyBytes = LocalDocumentProjectionPolicy.validateBody(document)
        synchronized(stateLock) {
            if (!bodySnapshots.consumeIfCurrent(lease, identity.snapshotKey)) return@synchronized false
            val existingNode = queries.selectDocumentNodeById(document.spaceId, document.documentId)
                .executeAsOneOrNull()
            val oldParentId = if (existingNode != null) {
                parentIdFromDocumentStorage(existingNode.parent_key)
            } else {
                bodies.loadLocked(identity.spaceId, identity.documentId)?.parentId
            }
            content.fenceDocumentRelationshipsLocked(identity, oldParentId, document.parentId)
            content.applyDocumentLocked(document, bodyBytes)
        }
    }

    fun applyBodyMutation(
        projectionLease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean = cacheUseGate.runIfOpen {
        val identity = normalizedDocumentIdentity(document.spaceId, document.documentId)
        val bodyBytes = LocalDocumentProjectionPolicy.validateBody(document)
        synchronized(stateLock) {
            if (!bodyMutationSnapshots.consumeIfCurrent(projectionLease, identity.snapshotKey)) {
                return@synchronized false
            }
            bodySnapshots.invalidate(identity.snapshotKey)
            val existingNode = queries.selectDocumentNodeById(document.spaceId, document.documentId)
                .executeAsOneOrNull()
            val oldParentId = if (existingNode != null) {
                parentIdFromDocumentStorage(existingNode.parent_key)
            } else {
                bodies.loadLocked(identity.spaceId, identity.documentId)?.parentId
            }
            content.fenceDocumentRelationshipsLocked(
                identity, oldParentId, document.parentId,
            )
            content.applyDocumentLocked(document, bodyBytes)
        }
    }

    fun applyMove(
        projectionLease: ProjectionSnapshotLease,
        result: DocumentMoveResult,
    ): Boolean = cacheUseGate.runIfOpen {
        LocalDocumentProjectionPolicy.validateMove(result)
        val node = result.node
        val identity = normalizedDocumentIdentity(node.spaceId, node.nodeId)
        synchronized(stateLock) {
            if (!bodyMutationSnapshots.consumeIfCurrent(projectionLease, identity.snapshotKey)) {
                return@synchronized false
            }
            val existingRow = queries.selectDocumentNodeById(node.spaceId, node.nodeId).executeAsOneOrNull()
            val existingNode = existingRow?.toLocalDocumentNode()
            val existingBody = bodies.loadLocked(identity.spaceId, identity.documentId)
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
            content.fenceDocumentRelationshipsLocked(
                identity, oldParentId, node.parentId,
            )
            // 路径尚未被缓存的在途后代正文还无法从本地祖先中识别。move 很少见；reset 这条
            // 有界通道，这样在层级提交之后没有任何请求能发布旧根到父链。
            bodySnapshots.reset()
            pathSpineSnapshots.reset()
            val descendantBodyIds = bodies.descendantIdsLocked(node.spaceId, node.nodeId)
            descendantBodyIds.forEach { descendantId ->
                val descendantKey = normalizedDocumentIdentity(node.spaceId, descendantId).snapshotKey
                bodyMutationSnapshots.invalidate(descendantKey)
            }
            val movedBody = existingBody?.takeIf { node.revision >= it.revision }?.copy(
                parentId = node.parentId,
                title = node.name,
                revision = node.revision,
                updatedBy = node.updatedBy,
                updatedAt = node.updatedAt,
                ancestorIds = result.ancestorIds,
            )
            val movedBodyBytes = movedBody?.let(LocalDocumentProjectionPolicy::validateBody)
            queries.transaction {
                descendantBodyIds.forEach { descendantId ->
                    bodies.deleteLocked(node.spaceId, descendantId)
                }
                movedBody?.let { body ->
                    bodies.persistLocked(body, checkNotNull(movedBodyBytes))
                }
                content.convergeNodeLocked(node, existingRow?.parent_key)
                content.updateHomeDocumentLocked(node)
                content.updateParentHasChildrenLocked(normalizedBranch(node.spaceId, oldParentId))
                if (oldParentId != node.parentId) {
                    content.updateParentHasChildrenLocked(normalizedBranch(node.spaceId, node.parentId))
                }
                bodies.pruneLocked()
            }
            true
        }
    }

    fun purgeSpace(spaceId: String) {
        cacheUseGate.use {
            val normalizedSpaceId = LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId")
            synchronized(stateLock) {
                purgeSpaceLocked(normalizedSpaceId)
            }
        }
    }

    fun purgeDocument(spaceId: String, documentId: String) {
        cacheUseGate.use {
            val identity = normalizedDocumentIdentity(spaceId, documentId)
            synchronized(stateLock) {
                purgeDocumentLocked(identity)
            }
        }
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = cacheUseGate.runIfOpen {
        synchronized(stateLock) {
            spaceSnapshots.abandon(lease).also { abandoned ->
                if (abandoned && spaceSnapshotBoundary?.lease === lease) spaceSnapshotBoundary = null
            } ||
                spaceMutationSnapshots.abandon(lease) ||
                home.abandon(lease) ||
                branchSnapshots.abandon(lease) ||
                pathSpineSnapshots.abandon(lease) ||
                bodySnapshots.abandon(lease) ||
                bodyMutationSnapshots.abandon(lease)
        }
    }

    /** 调用方持有 [stateLock]。 */
    fun resetSnapshotGatesLocked() {
        resetProjectionSnapshotGatesLocked()
    }

    /** 调用方持有 [stateLock]。 */
    private fun resetProjectionSnapshotGatesLocked() {
        spaceSnapshots.reset()
        resetDependentProjectionGatesLocked()
        spaceSnapshotBoundary = null
    }

    /** 调用方持有 [stateLock]。 */
    private fun resetDependentProjectionGatesLocked() {
        spaceMutationSnapshots.reset()
        home.reset()
        branchSnapshots.reset()
        pathSpineSnapshots.reset()
        bodySnapshots.reset()
        bodyMutationSnapshots.reset()
    }

    /** 调用方持有 [stateLock]。 */
    private fun purgeSpaceLocked(spaceId: String) {
        resetProjectionSnapshotGatesLocked()
        queries.transaction {
            deleteSpaceProjectionRowsLocked(spaceId)
        }
    }

    /** 调用方持有 [stateLock] 与一个 SQL 事务。 */
    private fun deleteSpaceProjectionRowsLocked(spaceId: String) {
        queries.deleteDocumentHomeBySpace(spaceId)
        bodies.deleteSpaceLocked(spaceId)
        queries.deleteDocumentNodesBySpace(spaceId)
        queries.deleteDocumentBranchesBySpace(spaceId)
        queries.deleteDocumentSpace(spaceId)
    }

    /** 调用方持有 [stateLock]。 */
    private fun purgeDocumentLocked(identity: DocumentIdentity) {
        val affectedIds = linkedSetOf(identity.documentId)
        affectedIds += bodies.descendantIdsLocked(identity.spaceId, identity.documentId)
        val pendingIds = affectedIds.toMutableList()
        var pendingIndex = 0
        while (pendingIndex < pendingIds.size) {
            val parentId = pendingIds[pendingIndex++]
            queries.selectDocumentNodes(identity.spaceId, parentId).executeAsList().forEach { child ->
                if (affectedIds.add(child.node_id)) pendingIds += child.node_id
            }
        }
        affectedIds.forEach { documentId ->
            val snapshotKey = normalizedDocumentIdentity(identity.spaceId, documentId).snapshotKey
            bodyMutationSnapshots.invalidate(snapshotKey)
        }
        val parentsToRefresh = affectedIds.mapNotNullTo(linkedSetOf<String>()) { documentId ->
            queries.selectDocumentNodeById(identity.spaceId, documentId).executeAsOneOrNull()
                ?.parent_key
                ?.let(::parentIdFromDocumentStorage)
        }
        // 该节点可能在不同的 branch 读取在途时移动过。reset 所有读取通道，而不是猜测哪个父级
        // 拥有该响应。任何缓存的后代路径也不再是干净的服务器事实：父级缺失/被删除可能意味着其
        // 子节点在本客户端观察到终态响应之前就已移动。
        home.reset()
        branchSnapshots.reset()
        pathSpineSnapshots.reset()
        bodySnapshots.reset()
        queries.transaction {
            affectedIds.forEach { documentId ->
                queries.deleteDocumentHomeItem(identity.spaceId, documentId)
                bodies.deleteLocked(identity.spaceId, documentId)
                queries.deleteDocumentNode(identity.spaceId, documentId)
                queries.deleteDocumentNodesByBranch(identity.spaceId, documentId)
                queries.deleteDocumentBranch(identity.spaceId, documentId)
            }
            parentsToRefresh.forEach { parentId ->
                content.updateParentHasChildrenLocked(normalizedBranch(identity.spaceId, parentId))
            }
        }
    }

    private fun beginSpaceSnapshotBoundaryLocked(): DocumentSpaceSnapshotBoundary =
        persistence.loadKnownSpaceIdsLocked().also { initialSpaceIds ->
            require(initialSpaceIds.size <= LocalDocumentProjectionLimits.MAX_SPACE_PROJECTION_IDENTITIES) {
                "document space projection exceeded its bounded identity set"
            }
        }.let { initialSpaceIds ->
            DocumentSpaceSnapshotBoundary(
                lease = spaceSnapshots.begin(ALL_SPACES_KEY),
                initialSpaceIds = initialSpaceIds,
            )
        }

    private fun currentSpaceSnapshotBoundaryLocked(
        lease: ProjectionSnapshotLease,
    ): DocumentSpaceSnapshotBoundary? {
        if (!spaceSnapshots.isCurrent(lease)) return null
        return spaceSnapshotBoundary.takeIf { it?.lease === lease }
    }

    private fun consumeSpaceSnapshotBoundaryLocked(lease: ProjectionSnapshotLease): DocumentSpaceSnapshotBoundary? {
        if (!spaceSnapshots.consumeIfCurrent(lease, ALL_SPACES_KEY)) return null
        return spaceSnapshotBoundary.takeIf { it?.lease === lease }.also { spaceSnapshotBoundary = null }
    }

    private fun pruneSpaceSnapshotBoundaryLocked() {
        if (spaceSnapshotBoundary?.lease?.let(spaceSnapshots::isCurrent) != true) spaceSnapshotBoundary = null
    }

    /** 调用方持有 [stateLock]。 */
    private fun loadPathSpineLocked(identity: DocumentIdentity): DocumentPathSpine? {
        val targetToRoot = ArrayList<DocumentNode>(DocumentPathSpine.MAX_NODES)
        val visited = hashSetOf<String>()
        var currentNodeId = identity.documentId
        repeat(DocumentPathSpine.MAX_NODES) {
            val row = queries.selectDocumentNodeById(identity.spaceId, currentNodeId)
                .executeAsOneOrNull()
                ?: return null
            val node = row.toLocalDocumentNode()
            if (!visited.add(node.nodeId)) return null
            targetToRoot += node
            val parentId = node.parentId
            if (parentId == null) {
                return LocalDocumentProjectionPolicy.normalizePathSpine(
                    identity.spaceId,
                    identity.documentId,
                    DocumentPathSpine(targetToRoot.asReversed()),
                )
            }
            currentNodeId = parentId
        }
        return null
    }

    private companion object {
        const val ALL_SPACES_KEY = "all-spaces"
        const val SPACES_PROJECTION_CODE = 1L
    }
}
