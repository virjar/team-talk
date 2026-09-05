package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.DocumentSpace

/** 仓库拥有的一条游标链，用于重建可见空间工作集。 */
internal data class DocumentSpaceSnapshotBoundary(
    val lease: ProjectionSnapshotLease,
    /** 只有扫描开始时存在的身份才可能被其终态省略证明移除。 */
    val initialSpaceIds: Set<String>,
    val seenInitialSpaceIds: MutableSet<String> = linkedSetOf(),
    var lastSpaceId: String? = null,
    var appliedPageCount: Int = 0,
)

internal fun normalizedBranch(spaceId: String, parentId: String?): DocumentBranchIdentity =
    DocumentBranchIdentity(
        LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId"),
        parentId?.let { LocalDocumentProjectionPolicy.requireKey(it, "parentId") },
    )

internal fun normalizedDocumentIdentity(spaceId: String, documentId: String): DocumentIdentity =
    DocumentIdentity(
        LocalDocumentProjectionPolicy.requireKey(spaceId, "spaceId"),
        LocalDocumentProjectionPolicy.requireKey(documentId, "documentId"),
    )

internal data class DocumentBranchIdentity(
    val spaceId: String,
    val parentId: String?,
) {
    val parentKey: String = parentId.orEmpty()
    val snapshotKey: String = compositeDocumentSnapshotKey(spaceId, parentId)
}

internal data class DocumentIdentity(
    val spaceId: String,
    val documentId: String,
) {
    val snapshotKey: String = compositeDocumentSnapshotKey(spaceId, documentId)
}

internal data class ResolvedBranchNode(
    val node: com.virjar.tk.protocol.model.DocumentNode,
    val addsAfterBranchDelete: Boolean,
)

internal fun compositeDocumentSnapshotKey(first: String, second: String?): String =
    "${first.length}:$first:${second?.length ?: -1}:${second.orEmpty()}"

/**
 * 把一个服务器页合并进有界本地索引。返回的服务器行整体替换缓存行；客户端绝不会组合来自不同
 * 读取的 role、custody 或 policy 字段。
 */
internal fun mergeSpacePage(
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
