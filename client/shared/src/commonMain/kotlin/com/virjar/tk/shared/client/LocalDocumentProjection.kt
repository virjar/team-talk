package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.DOCUMENT_NODE_SIBLING_ORDER
import com.virjar.tk.protocol.ProtoCodec

/** 服务暴露的两个有界文档首页投影。 */
enum class DocumentHomeCollection(internal val storageCode: Long) {
    RECENT(1),
    RECENTLY_CREATED(2),
}

/** 硬性本地边界；畸形或意外过宽的远程快照在发布之前失败。 */
object LocalDocumentProjectionLimits {
    const val MAX_SPACES = 1_024
    /**
     * 一次完整投影刷新可能跨越比常驻 space 窗口更多的行，但其省略证明必须保持内存有界。
     * 超过该上限会在终态页可以撤销任何东西之前就拒绝刷新。
     */
    const val MAX_SPACE_PROJECTION_IDENTITIES = 16_384
    const val MAX_NODES = 50_000
    const val MAX_BRANCH_NODES = 512
    const val MAX_BRANCHES = 20_000
    const val MAX_BODIES = 512
    const val MAX_BODY_BYTES = 64L * 1_024L * 1_024L
    const val MAX_HOME_ITEMS = 50
}

/** 共享校验让 SQL 实现与测试 fake 保持在同一个失败关闭边界上。 */
object LocalDocumentProjectionPolicy {
    const val MAX_KEY_LENGTH = 128

    fun normalizeSpaces(spaces: List<DocumentSpace>): List<DocumentSpace> {
        require(spaces.size <= LocalDocumentProjectionLimits.MAX_SPACES) {
            "document space projection exceeds the local capacity"
        }
        spaces.forEach(::validateSpace)
        require(spaces.mapTo(hashSetOf(), DocumentSpace::spaceId).size == spaces.size) {
            "document space snapshot contains duplicate spaceId"
        }
        return spaces.toList()
    }

    fun normalizeHome(items: List<DocumentHomeItem>): List<DocumentHomeItem> {
        require(items.size <= LocalDocumentProjectionLimits.MAX_HOME_ITEMS) {
            "document home snapshot exceeds the local capacity"
        }
        items.forEach(::validateHomeItem)
        require(items.mapTo(hashSetOf()) { it.spaceId to it.documentId }.size == items.size) {
            "document home snapshot contains duplicate document identity"
        }
        return items.toList()
    }

    fun normalizeNodes(
        spaceId: String,
        parentId: String?,
        nodes: List<DocumentNode>,
    ): List<DocumentNode> {
        val expectedSpaceId = requireKey(spaceId, "spaceId")
        val expectedParentId = parentId?.let { requireKey(it, "parentId") }
        require(nodes.size <= LocalDocumentProjectionLimits.MAX_BRANCH_NODES) {
            "document branch snapshot exceeds the local branch capacity"
        }
        nodes.forEach { node ->
            validateNode(node)
            require(node.spaceId == expectedSpaceId && node.parentId == expectedParentId) {
                "document branch snapshot contains another branch"
            }
        }
        require(nodes.mapTo(hashSetOf(), DocumentNode::nodeId).size == nodes.size) {
            "document branch snapshot contains duplicate nodeId"
        }
        return nodes.sortedWith(DOCUMENT_NODE_SIBLING_ORDER)
    }

    fun normalizePathSpine(
        spaceId: String,
        nodeId: String,
        spine: DocumentPathSpine,
    ): DocumentPathSpine {
        val expectedSpaceId = requireKey(spaceId, "spaceId")
        val expectedNodeId = requireKey(nodeId, "nodeId")
        require(spine.spaceId == expectedSpaceId && spine.targetNodeId == expectedNodeId) {
            "document path spine escaped its requested identity"
        }
        spine.nodes.forEach(::validateNode)
        require(spine.nodes.dropLast(1).all(DocumentNode::hasChildren)) {
            "document path spine contains a parent without children"
        }
        return DocumentPathSpine(spine.nodes.toList())
    }

    fun validateMove(result: DocumentMoveResult) {
        validateNode(result.node)
        require(result.ancestorIds.size <= Document.MAX_ANCESTOR_DEPTH) {
            "document move ancestor chain exceeds the protocol capacity"
        }
        result.ancestorIds.forEach { requireKey(it, "ancestorId") }
        require(result.ancestorIds.toSet().size == result.ancestorIds.size) {
            "document move ancestor chain contains a cycle"
        }
        require(result.node.nodeId !in result.ancestorIds) {
            "document move ancestor chain contains the document itself"
        }
        require(
            (result.node.parentId == null && result.ancestorIds.isEmpty()) ||
                (result.node.parentId != null && result.ancestorIds.lastOrNull() == result.node.parentId),
        ) { "document move ancestor chain does not end at parentId" }
    }

    fun validateBody(document: Document): Long {
        validateNode(
            DocumentNode(
                nodeId = document.documentId,
                spaceId = document.spaceId,
                parentId = document.parentId,
                hasChildren = false,
                name = document.title,
                revision = document.revision,
                createdBy = document.createdBy,
                createdAt = document.createdAt,
                updatedBy = document.updatedBy,
                updatedAt = document.updatedAt,
            ),
        )
        DocumentPolicy.validateMarkdownEnvelope(document.markdown)
        require(document.ancestorIds.size <= Document.MAX_ANCESTOR_DEPTH) {
            "document ancestor chain exceeds the protocol capacity"
        }
        document.ancestorIds.forEach { requireKey(it, "ancestorId") }
        require(document.ancestorIds.toSet().size == document.ancestorIds.size) {
            "document ancestor chain contains a cycle"
        }
        require(document.documentId !in document.ancestorIds) {
            "document ancestor chain contains the document itself"
        }
        require(
            (document.parentId == null && document.ancestorIds.isEmpty()) ||
                (document.parentId != null && document.ancestorIds.lastOrNull() == document.parentId),
        ) { "document ancestor chain does not end at parentId" }
        MarkdownAssetPolicy.requireCanonical(document.markdown, document.assets)
        val manifestBytes = ProtoCodec.encodeList(document.assets).size.toLong()
        return (document.markdown.encodeToByteArray().size.toLong() + manifestBytes).also { bodyBytes ->
            require(bodyBytes <= LocalDocumentProjectionLimits.MAX_BODY_BYTES) {
                "document body exceeds the local byte capacity"
            }
        }
    }

    fun requireKey(value: String, label: String): String {
        require(value.isNotBlank() && value.length <= MAX_KEY_LENGTH) {
            "$label must contain 1..$MAX_KEY_LENGTH non-blank characters"
        }
        return value
    }

    private fun validateSpace(space: DocumentSpace) {
        requireKey(space.spaceId, "spaceId")
        require(space.name.isNotBlank()) { "document space name must not be blank" }
        require(space.myRole in DocumentSpace.ROLE_VIEWER..DocumentSpace.ROLE_OWNER) {
            "document space role is invalid"
        }
        requireKey(space.createdBy, "document space creator")
        require(
            space.ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                space.ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "document space owner principal type is invalid" }
        requireKey(space.ownerPrincipalId, "document space owner principal")
        requireKey(space.stewardUid, "document space steward")
        require(space.custodyRevision > 0L) { "document space custody revision must be positive" }
        require(space.policyRevision > 0L) { "document space policy revision must be positive" }
        if (space.ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            require(space.ownerPrincipalId == space.stewardUid) {
                "a user-owned document space must be stewarded by its owner"
            }
        }
        require(space.createdAt >= 0L && space.updatedAt >= 0L) {
            "document space timestamps must not be negative"
        }
    }

    private fun validateHomeItem(item: DocumentHomeItem) {
        requireKey(item.spaceId, "spaceId")
        requireKey(item.documentId, "documentId")
        require(item.spaceName.isNotBlank()) { "document home space name must not be blank" }
        require(item.title.isNotBlank()) { "document home title must not be blank" }
        require(item.createdBy.isNotBlank()) { "document home creator must not be blank" }
        require(item.createdAt >= 0L && item.updatedAt >= 0L && item.accessedAt >= 0L) {
            "document home timestamps must not be negative"
        }
    }

    private fun validateNode(node: DocumentNode) {
        requireKey(node.spaceId, "spaceId")
        requireKey(node.nodeId, "nodeId")
        node.parentId?.let { parentId ->
            requireKey(parentId, "parentId")
            require(parentId != node.nodeId) { "document node cannot parent itself" }
        }
        require(node.name.isNotBlank()) { "document node name must not be blank" }
        require(node.revision > 0L) { "document node revision must be positive" }
        require(node.createdBy.isNotBlank() && node.updatedBy.isNotBlank()) {
            "document node actor must not be blank"
        }
        require(node.createdAt >= 0L && node.updatedAt >= 0L) {
            "document node timestamps must not be negative"
        }
    }
}

/**
 * 持久、有服务器支撑的企业文档读取投影。
 *
 * 一个 branch 由 `(spaceId, parentId)` 标识，并有独立的持久标记，因此权威空 branch 可与从未
 * 拉取过的 branch 区分。[DocumentNode] 总是一个文档：[DocumentNode.hasChildren] 只宣告惰性后代，
 * 而绝不把节点变成仅文件夹类型。干净的 Markdown 正文被独立缓存，并保留其有序祖先链。
 *
 * 仓库是唯一的远程请求 owner。投影租约只表达普通请求顺序：对同一投影通道，更旧的响应不能覆盖
 * 更新的响应。这里绝不推断或重放权限；服务为每个 RPC 授权，当前的 403/404 会原子移除受影响的
 * 干净投影。
 */
interface LocalDocumentProjection {
    /** 区分权威空结果与从未拉取过 spaces 的缓存。 */
    fun isDocumentSpaceSnapshotCached(): Boolean

    /** 按最后权威顺序的完整缓存 space 投影。 */
    fun getDocumentSpaces(): List<DocumentSpace>

    /** 为当前该账号可见的完整 space 集合开始一次请求。 */
    fun beginDocumentSpaceSnapshot(): ProjectionSnapshotLease

    /** 原子替换完整的可见 space 投影。 */
    fun applyDocumentSpaceSnapshot(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
    ): Boolean

    /**
     * 合并一个有界服务器页，而不把省略当作撤销。页实体赢得身份冲突，并且在本地边界已满时保留在
     * 更旧缓存行之前。
     */
    fun applyDocumentSpacePage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
    ): Boolean

    /**
     * 应用单次完整可见 space 扫描的一页。同一个 [lease] 对每个非终态页保持有效，并且只被
     * [isTerminal] 消费。省略只有在终态提交时才成为删除证据。
     */
    fun applyDocumentSpaceRefreshPage(
        lease: ProjectionSnapshotLease,
        spaces: List<DocumentSpace>,
        isFirstPage: Boolean,
        isTerminal: Boolean,
    ): Boolean

    /** 一次已提交的变更使未完成的目录扫描失效，因此该扫描必须重启。 */
    fun beginDocumentSpaceMutationSnapshot(spaceId: String): ProjectionSnapshotLease

    /** 在普通变更请求通道中提交一次成功的 create/update 响应。 */
    fun applyDocumentSpaceMutation(
        projectionLease: ProjectionSnapshotLease,
        space: DocumentSpace,
    ): Boolean

    /** 返回最后一个完整 home 集合，未拉取过时为空列表。 */
    fun getDocumentHome(collection: DocumentHomeCollection): List<DocumentHomeItem>

    /** 每个 home 通道有独立的持久空/非空快照标记。 */
    fun isDocumentHomeSnapshotCached(collection: DocumentHomeCollection): Boolean

    fun beginDocumentHomeSnapshot(collection: DocumentHomeCollection): ProjectionSnapshotLease

    fun applyDocumentHomeSnapshot(
        lease: ProjectionSnapshotLease,
        collection: DocumentHomeCollection,
        items: List<DocumentHomeItem>,
    ): Boolean

    /** 即使拉取到的 branch 权威子列表为空也为 true。 */
    fun isDocumentBranchCached(spaceId: String, parentId: String?): Boolean

    /**
     * 恰好一个完整 branch 的有序直接子节点。当 branch 没有权威标记时返回空，即使个别路径脊柱
     * 节点已经本地存储。
     */
    fun getDocumentNodes(spaceId: String, parentId: String?): List<DocumentNode>

    fun beginDocumentBranchSnapshot(
        spaceId: String,
        parentId: String?,
    ): ProjectionSnapshotLease

    fun applyDocumentBranchSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        parentId: String?,
        nodes: List<DocumentNode>,
    ): Boolean

    /** 完整的缓存根到目标路径；链中任何节点缺失时为 null。 */
    fun getDocumentPathSpine(spaceId: String, nodeId: String): DocumentPathSpine?

    fun beginDocumentPathSpineSnapshot(
        spaceId: String,
        nodeId: String,
    ): ProjectionSnapshotLease

    /**
     * 持久化有界路径节点，而不声称任何包含它的 branch 是完整的。之后的完整 branch 快照仍然是
     * 安装其标记的唯一操作。
     */
    fun applyDocumentPathSpineSnapshot(
        lease: ProjectionSnapshotLease,
        spaceId: String,
        nodeId: String,
        spine: DocumentPathSpine,
    ): Boolean

    /** 最后一个干净的服务器正文。本地编辑器草稿保持为独立的本地事实。 */
    fun getDocumentBody(spaceId: String, documentId: String): Document?

    fun beginDocumentBodySnapshot(spaceId: String, documentId: String): ProjectionSnapshotLease

    /** 为远程完成的正文/移动变更开始响应后提交通道。 */
    fun beginDocumentBodyMutationSnapshot(
        spaceId: String,
        documentId: String,
    ): ProjectionSnapshotLease

    fun applyDocumentBodySnapshot(
        lease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean

    /** 在变更请求通道中发布一次成功的 create/update 正文响应。 */
    fun applyDocumentBodyMutation(
        projectionLease: ProjectionSnapshotLease,
        document: Document,
    ): Boolean

    /** 原子重定位/重命名一个节点，并修补或失效受影响的缓存正文路径。 */
    fun applyDocumentMove(
        projectionLease: ProjectionSnapshotLease,
        result: DocumentMoveResult,
    ): Boolean

    /** 在当前终态服务器结果之后移除一个干净 space 投影。 */
    fun purgeDocumentSpace(spaceId: String)

    /** 移除一个干净节点投影，同时保留其 branch 已拉取的状态。 */
    fun purgeDocument(spaceId: String, documentId: String)
}
