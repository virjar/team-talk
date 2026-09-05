package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DOCUMENT_NODE_SIBLING_ORDER

/** 目录树当前可见的一行。子页面只在父页面展开后按需加载。 */
data class DocumentTreeRow(
    val node: DocumentNode,
    val depth: Int,
    /** 已加载的子列表优先于可能已经过期的服务端摘要。 */
    val hasChildren: Boolean,
)

/**
 * 按不可变快照身份记忆展平后的树。草稿/编辑器状态刻意不在 key 中，
 * 这样在文档中输入时不会再次遍历数百个目录节点。
 */
internal class DocumentTreeRowsProjection {
    private var cachedTreeChildren: Map<String?, List<DocumentNode>>? = null
    private var cachedExpandedNodeIds: Set<String>? = null
    private var cachedRows: List<DocumentTreeRow> = emptyList()

    fun rows(
        treeChildren: Map<String?, List<DocumentNode>>,
        expandedNodeIds: Set<String>,
    ): List<DocumentTreeRow> {
        if (treeChildren === cachedTreeChildren && expandedNodeIds === cachedExpandedNodeIds) {
            return cachedRows
        }
        cachedTreeChildren = treeChildren
        cachedExpandedNodeIds = expandedNodeIds
        return visibleDocumentTreeRows(treeChildren, expandedNodeIds).also { cachedRows = it }
    }
}

/**
 * 每个分支的最近响应围栏加上真实的 in-flight 计数。
 *
 * 使一个分支失效绝不抹除它的活动 token：最终的 `finally` 仍然排空计数，
 * 而 [isCurrent] 拒绝过期的响应。更新的请求只取代同一个
 * `(spaceId, parentId)` 分支；无关的分支可以独立完成。
 */
internal class DocumentBranchRequestGate {
    @ConsistentCopyVisibility
    internal data class Token internal constructor(
        internal val requestId: Long,
        internal val epoch: Long,
        internal val spaceId: String,
        internal val parentId: String?,
    )

    private data class BranchKey(val spaceId: String, val parentId: String?)

    private var nextRequestId = 0L
    private var epoch = 0L
    private var exhausted = false
    private val latestRequestByBranch = mutableMapOf<BranchKey, Long>()
    private val activeRequestIds = mutableSetOf<Long>()

    val inFlightCount: Int get() = activeRequestIds.size

    fun begin(spaceId: String, parentId: String?): Token {
        check(!exhausted) { "文档分支生命周期代次已耗尽" }
        check(nextRequestId < Long.MAX_VALUE) { "文档分支请求代次已耗尽" }
        val requestId = ++nextRequestId
        val key = BranchKey(spaceId, parentId)
        latestRequestByBranch[key] = requestId
        activeRequestIds += requestId
        return Token(requestId, epoch, spaceId, parentId)
    }

    fun isCurrent(token: Token): Boolean = !exhausted && token.epoch == epoch &&
        latestRequestByBranch[BranchKey(token.spaceId, token.parentId)] == token.requestId

    fun finish(token: Token) {
        activeRequestIds -= token.requestId
        val key = BranchKey(token.spaceId, token.parentId)
        if (latestRequestByBranch[key] == token.requestId) {
            latestRequestByBranch.remove(key)
        }
    }

    fun invalidate(spaceId: String, parentId: String?) {
        latestRequestByBranch.remove(BranchKey(spaceId, parentId))
    }

    fun invalidateSpace(spaceId: String) {
        latestRequestByBranch.keys.removeAll { it.spaceId == spaceId }
    }

    fun invalidateAll() {
        // 清除当前所有权是 fail-closed 边界，即使标量无法推进也是如此。
        latestRequestByBranch.clear()
        if (epoch == Long.MAX_VALUE) {
            exhausted = true
            error("文档分支生命周期代次已耗尽")
        }
        epoch += 1L
    }
}

/** 解析查询空间，而不在空间切换之后把旧的 branch ID 重新指向别处。 */
internal fun resolveDocumentBranchSpace(
    selectedSpaceId: String?,
    expectedSpaceId: String?,
): String? {
    val targetSpaceId = expectedSpaceId ?: selectedSpaceId ?: return null
    return targetSpaceId.takeIf { it == selectedSpaceId }
}

/**
 * 在保持全局节点身份规则的同时，替换一个权威的惰性加载分支。
 *
 * 在另一台设备上移动的页面可能已经缓存于它的旧 parent 之下。发布新分支必须在同一个
 * 不可变更新中，把每一个传入的 node ID 从所有其他分支驱逐出去；
 * 来自另一个 parent 的更低 revision 响应不能取代更新的缓存位置，
 * 而相同 revision 出现在不同 parent 下则 fail closed。展平时的 `visited` 抑制
 * 只是损坏/环防护，绝不是冲突解决。
 */
internal fun publishDocumentTreeBranch(
    treeChildren: Map<String?, List<DocumentNode>>,
    spaceId: String,
    parentId: String?,
    children: List<DocumentNode>,
): Map<String?, List<DocumentNode>> {
    require(children.all { node ->
        node.nodeId.isNotBlank() && node.spaceId == spaceId && node.parentId == parentId
    }) { "文档目录分支包含错误空间或父节点" }
    val incomingNodeIds = children.mapTo(linkedSetOf(), DocumentNode::nodeId)
    require(incomingNodeIds.size == children.size) { "文档目录分支包含重复节点" }

    data class CachedLocation(val branchParentId: String?, val node: DocumentNode)
    data class Resolution(val incomingWins: Boolean, val cachedWinner: CachedLocation? = null)

    val cachedLocationsByNodeId = mutableMapOf<String, MutableList<CachedLocation>>()
    treeChildren.forEach { (cachedParentId, cachedChildren) ->
        cachedChildren.forEach { cachedNode ->
            if (cachedNode.nodeId in incomingNodeIds) {
                check(cachedNode.spaceId == spaceId && cachedNode.parentId == cachedParentId) {
                    "文档目录缓存包含错误空间或父节点"
                }
                cachedLocationsByNodeId.getOrPut(cachedNode.nodeId) { mutableListOf() } +=
                    CachedLocation(cachedParentId, cachedNode)
            }
        }
    }
    val resolutions = children.associate { incoming ->
        val cachedLocations = cachedLocationsByNodeId[incoming.nodeId].orEmpty()
        val maxCachedRevision = cachedLocations.maxOfOrNull { it.node.revision }
        val resolution = when {
            maxCachedRevision == null || incoming.revision > maxCachedRevision -> Resolution(
                incomingWins = true,
            )
            incoming.revision == maxCachedRevision -> {
                val newestLocations = cachedLocations.filter {
                    it.node.revision == maxCachedRevision
                }
                check(newestLocations.all { it.branchParentId == parentId }) {
                    "同一文档 revision 出现在不同父分支"
                }
                Resolution(incomingWins = true)
            }
            else -> {
                val newestLocations = cachedLocations.filter {
                    it.node.revision == maxCachedRevision
                }
                check(newestLocations.map { it.branchParentId }.distinct().size == 1) {
                    "同一文档 revision 出现在不同父分支"
                }
                Resolution(incomingWins = false, cachedWinner = newestLocations.first())
            }
        }
        incoming.nodeId to resolution
    }

    val updated = linkedMapOf<String?, List<DocumentNode>>()
    treeChildren.forEach { (cachedParentId, cachedChildren) ->
        if (cachedParentId != parentId) {
            val retainedWinnerIds = mutableSetOf<String>()
            updated[cachedParentId] = cachedChildren.filter { cachedNode ->
                val resolution = resolutions[cachedNode.nodeId] ?: return@filter true
                val winner = resolution.cachedWinner
                !resolution.incomingWins && winner != null && winner.branchParentId == cachedParentId &&
                    winner.node == cachedNode && retainedWinnerIds.add(cachedNode.nodeId)
            }
        }
    }
    updated[parentId] = children.mapNotNull { incoming ->
        val resolution = resolutions.getValue(incoming.nodeId)
        when {
            resolution.incomingWins -> incoming
            else -> resolution.cachedWinner
                ?.takeIf { winner -> winner.branchParentId == parentId }
                ?.node
        }
    }
    // `hasChildren` 是从子成员关系推导的正向提示，而不是节点 revision 的一部分。
    // 因此更早获取的分支可能被缓存为空，即使之后的父分支刷新现在证明至少存在一个子节点。
    // 保留那份负缓存会同时压制披露能力和每一个未来的惰性重载。
    // 只忘记相互矛盾的空分支；已加载的非空分支仍然是更强的事实。
    children.forEach { incoming ->
        if (resolutions.getValue(incoming.nodeId).incomingWins && incoming.hasChildren &&
            treeChildren[incoming.nodeId]?.isEmpty() == true
        ) {
            updated.remove(incoming.nodeId)
        }
    }
    return updated
}

/** 已加载分支失效的展开节点必须回到折叠/重载状态。 */
internal fun reconcileExpandedDocumentTreeBranches(
    expandedNodeIds: Set<String>,
    previousTreeChildren: Map<String?, List<DocumentNode>>,
    publishedTreeChildren: Map<String?, List<DocumentNode>>,
): Set<String> {
    val invalidatedBranchIds = previousTreeChildren.keys
        .asSequence()
        .filterNotNull()
        .filterNot(publishedTreeChildren::containsKey)
        .toSet()
    return if (invalidatedBranchIds.isEmpty()) {
        expandedNodeIds
    } else {
        expandedNodeIds - invalidatedBranchIds
    }
}

/**
 * 一次已确认的删除已经是一个权威的本地事实。在最大努力式 I/O 之前，
 * 既要从每一个缓存的父分支驱逐行身份，也要驱逐被删节点自己的惰性子分支。
 */
internal fun removeDeletedDocumentTreeIdentity(
    treeChildren: Map<String?, List<DocumentNode>>,
    spaceId: String,
    documentId: String,
): Map<String?, List<DocumentNode>> {
    require(spaceId.isNotBlank() && documentId.isNotBlank()) { "待删除文档身份非法" }
    return treeChildren.mapValues { (_, children) ->
        children.filterNot { node ->
            if (node.nodeId != documentId) return@filterNot false
            check(node.spaceId == spaceId) { "待删除文档缓存属于错误空间" }
            true
        }
    } - documentId
}

/** 在任何旧/新 parent 刷新被允许挂起之前产生的不可变工作计划。 */
internal data class DocumentTreeRefreshPlan(
    val treeChildren: Map<String?, List<DocumentNode>>,
    /** 先刷新新 parent，随后是每一个不同的已知旧 parent。 */
    val parentIdsToRefresh: List<String?>,
)

/**
 * 在刷新一个权威文档身份之前，把它从每一个缓存分支中移除。
 *
 * [previousParentIds] 包含操作在其 RPC 之前捕获的位置。缓存位置也被包括在内，
 * 这样重复的过期快照就能在一次遍历中收敛。
 */
internal fun planDocumentTreeRefresh(
    treeChildren: Map<String?, List<DocumentNode>>,
    document: Document,
    previousParentIds: Set<String?> = emptySet(),
): DocumentTreeRefreshPlan {
    require(document.hasValidDocumentPath()) { "服务器返回了非法文档路径" }
    return planDocumentNodeTreeRefresh(
        treeChildren = treeChildren,
        spaceId = document.spaceId,
        nodeId = document.documentId,
        parentId = document.parentId,
        previousParentIds = previousParentIds,
    )
}

/** 用于不携带完整内容的权威 move 结果的、与路径无关的变体。 */
internal fun planDocumentNodeTreeRefresh(
    treeChildren: Map<String?, List<DocumentNode>>,
    spaceId: String,
    nodeId: String,
    parentId: String?,
    previousParentIds: Set<String?> = emptySet(),
): DocumentTreeRefreshPlan {
    require(spaceId.isNotBlank() && nodeId.isNotBlank()) { "服务器返回了非法文档身份" }
    val cachedParentIds = treeChildren
        .asSequence()
        .filter { (_, children) -> children.any { it.nodeId == nodeId } }
        .mapTo(linkedSetOf()) { it.key }
    val parentIdsToRefresh = buildList {
        add(parentId)
        previousParentIds.forEach { if (it !in this) add(it) }
        cachedParentIds.forEach { if (it !in this) add(it) }
    }
    val withoutDocument = treeChildren.mapValues { (_, children) ->
        children.filterNot { it.nodeId == nodeId }
    }
    return DocumentTreeRefreshPlan(withoutDocument, parentIdsToRefresh)
}

/** 路径发布和目标感知的目录揭示共享的结构校验。 */
internal fun Document.hasValidDocumentPath(): Boolean =
    documentId.isNotBlank() && spaceId.isNotBlank() &&
        ancestorIds.none(String::isBlank) && ancestorIds.distinct().size == ancestorIds.size &&
        documentId !in ancestorIds &&
        (if (parentId == null) ancestorIds.isEmpty() else ancestorIds.lastOrNull() == parentId)

internal sealed interface DocumentPathRevealResult {
    data object Revealed : DocumentPathRevealResult
    /** 更新的导航拥有状态；旧的延续绝不能发布任何东西。 */
    data object Superseded : DocumentPathRevealResult
    /** 当前的权威分支不再包含捕获的路径。 */
    data object Contradicted : DocumentPathRevealResult
}

/** 每一个必需分支加载完成之后的最终不可挂起校验。 */
internal fun loadedDocumentPathMatches(
    treeChildren: Map<String?, List<DocumentNode>>,
    stamp: DocumentPathStamp,
): Boolean {
    val locationsByNodeId = mutableMapOf<String, MutableList<Pair<String?, DocumentNode>>>()
    treeChildren.forEach { (cachedParentId, children) ->
        children.forEach { node ->
            locationsByNodeId.getOrPut(node.nodeId) { mutableListOf() } += cachedParentId to node
        }
    }
    fun uniqueNode(parentId: String?, nodeId: String): DocumentNode? {
        val (cachedParentId, node) = locationsByNodeId[nodeId]?.singleOrNull() ?: return null
        return node.takeIf {
            cachedParentId == parentId && it.spaceId == stamp.spaceId && it.parentId == parentId
        }
    }

    var parentId: String? = null
    stamp.ancestorIds.forEach { ancestorId ->
        val ancestor = uniqueNode(parentId, ancestorId) ?: return false
        parentId = ancestor.nodeId
    }
    if (parentId != stamp.parentId) return false
    return uniqueNode(parentId, stamp.documentId) != null
}

internal data class DocumentPathSpineTreeProjection(
    val treeChildren: Map<String?, List<DocumentNode>>,
    val partialBranchParentIds: Set<String?>,
)

/**
 * 合并一条 root 到目标的路径，而不声称任何单例边是完整分支。
 * 现有的兄弟节点存续，而更新的路径位置在别处驱逐同一个节点身份。
 */
internal fun mergeDocumentPathSpineIntoTree(
    treeChildren: Map<String?, List<DocumentNode>>,
    partialBranchParentIds: Set<String?>,
    spine: DocumentPathSpine,
): DocumentPathSpineTreeProjection {
    var merged = treeChildren
    val partial = partialBranchParentIds.filterTo(linkedSetOf(), treeChildren::containsKey)

    spine.nodes.forEach { incoming ->
        data class Location(val parentId: String?, val node: DocumentNode)

        val locations = merged.flatMap { (parentId, children) ->
            children.filter { it.nodeId == incoming.nodeId }.map { Location(parentId, it) }
        }
        locations.forEach { location ->
            check(location.node.spaceId == incoming.spaceId &&
                location.node.parentId == location.parentId
            ) { "文档目录缓存包含错误空间或父节点" }
        }
        val maxCachedRevision = locations.maxOfOrNull { it.node.revision }
        if (maxCachedRevision != null && maxCachedRevision > incoming.revision) {
            return@forEach
        }
        if (maxCachedRevision == incoming.revision) {
            val newest = locations.filter { it.node.revision == maxCachedRevision }
            check(newest.all { it.parentId == incoming.parentId }) {
                "同一文档 revision 出现在不同父分支"
            }
        }

        val targetExisted = locations.any { it.parentId == incoming.parentId }
        val targetIndex = merged[incoming.parentId].orEmpty()
            .indexOfFirst { it.nodeId == incoming.nodeId }
        val updated = linkedMapOf<String?, List<DocumentNode>>()
        merged.forEach { (parentId, children) ->
            updated[parentId] = children.filterNot { it.nodeId == incoming.nodeId }
        }
        val targetChildren = updated[incoming.parentId].orEmpty().toMutableList()
        if (targetIndex >= 0) targetChildren.add(targetIndex, incoming) else targetChildren += incoming
        updated[incoming.parentId] = targetChildren.sortedWith(DOCUMENT_NODE_SIBLING_ORDER)
        merged = updated

        if (!targetExisted) {
            partial += incoming.parentId
        }
        if (incoming.hasChildren && merged[incoming.nodeId]?.isEmpty() == true) {
            merged = merged - incoming.nodeId
            partial -= incoming.nodeId
        }
    }
    partial.retainAll(merged.keys)
    return DocumentPathSpineTreeProjection(merged, partial)
}

/**
 * 将已加载分支展平为可见行。页面是否拥有正文与是否拥有子页面互不排斥，因此不再按节点类型分支。
 */
internal fun visibleDocumentTreeRows(
    treeChildren: Map<String?, List<DocumentNode>>,
    expandedNodeIds: Set<String>,
): List<DocumentTreeRow> = buildList {
    // 分支发布已经保证每个 node ID 只有一个位置。这仍然是对意外注入的
    // 环/损坏测试快照的最后防线。
    val visited = mutableSetOf<String>()
    fun append(parentId: String?, depth: Int) {
        treeChildren[parentId].orEmpty().forEach { node ->
            if (!visited.add(node.nodeId)) return@forEach
            val loadedChildren = treeChildren[node.nodeId]
            add(
                DocumentTreeRow(
                    node = node,
                    depth = depth,
                    hasChildren = loadedChildren?.isNotEmpty() ?: node.hasChildren,
                )
            )
            if (node.nodeId in expandedNodeIds) append(node.nodeId, depth + 1)
        }
    }
    append(parentId = null, depth = 0)
}

/** 根据已加载节点恢复 root → node 路径，供新建子页面保存导航上下文。 */
internal fun nodeAncestorIds(
    nodeId: String?,
    treeChildren: Map<String?, List<DocumentNode>>,
): List<String> {
    if (nodeId == null) return emptyList()
    val nodesById = treeChildren.values.flatten().associateBy(DocumentNode::nodeId)
    val reversed = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var currentId: String? = nodeId
    while (currentId != null) {
        if (!visited.add(currentId)) return emptyList()
        val node = nodesById[currentId] ?: return emptyList()
        reversed += currentId
        currentId = node.parentId
    }
    return reversed.asReversed()
}

/** 只返回当前惰性加载树中已知的后代；服务器仍然是最终权威。 */
internal fun knownDocumentDescendantIds(
    nodeId: String,
    treeChildren: Map<String?, List<DocumentNode>>,
): Set<String> {
    val descendants = mutableSetOf<String>()
    val pending = ArrayDeque<String>()
    pending.addLast(nodeId)
    while (pending.isNotEmpty()) {
        val parentId = pending.removeLast()
        treeChildren[parentId].orEmpty().forEach { child ->
            if (child.nodeId != nodeId && descendants.add(child.nodeId)) {
                pending.addLast(child.nodeId)
            }
        }
    }
    return descendants
}

/** 当只有活动文档草稿变化时，避免再次遍历已加载的子树。 */
internal class DocumentKnownDescendantsProjection {
    private var cachedTreeChildren: Map<String?, List<DocumentNode>>? = null
    private var cachedNodeId: String? = null
    private var cachedDescendants: Set<String> = emptySet()

    fun descendants(
        nodeId: String?,
        treeChildren: Map<String?, List<DocumentNode>>,
    ): Set<String> {
        if (treeChildren === cachedTreeChildren && nodeId == cachedNodeId) return cachedDescendants
        cachedTreeChildren = treeChildren
        cachedNodeId = nodeId
        cachedDescendants = if (nodeId == null) {
            emptySet()
        } else {
            knownDocumentDescendantIds(nodeId, treeChildren)
        }
        return cachedDescendants
    }
}

internal data class DocumentCreationLocation(
    val parentId: String?,
    val ancestorIds: List<String>,
)

/** 在不咨询可变 UI 选择的情况下，解析一个显式的顶层或子页面目标。 */
internal fun documentCreationLocation(
    spaceId: String,
    parentId: String?,
    treeChildren: Map<String?, List<DocumentNode>>,
): DocumentCreationLocation? {
    if (parentId == null) return DocumentCreationLocation(parentId = null, ancestorIds = emptyList())
    val parent = treeChildren.values.flatten().firstOrNull { it.nodeId == parentId } ?: return null
    if (parent.spaceId != spaceId) return null
    val ancestorIds = nodeAncestorIds(parentId, treeChildren)
    if (ancestorIds.lastOrNull() != parentId) return null
    return DocumentCreationLocation(parentId = parentId, ancestorIds = ancestorIds)
}
