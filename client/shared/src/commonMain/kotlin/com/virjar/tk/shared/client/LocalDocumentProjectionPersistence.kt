package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentSpace

/**
 * 文档投影的 SQL 行映射与有界工作集持久化。
 *
 * 调用方拥有共享缓存状态锁。把同步留在该类之外使加锁顺序显式，同时把投影的存储表示集中在这里。
 */
internal class LocalDocumentProjectionPersistence(
    private val queries: AppDatabaseQueries,
    private val spacesProjectionCode: Long,
) {
    fun persistSpaceLocked(space: DocumentSpace, position: Int) {
        queries.upsertDocumentSpace(
            space.spaceId,
            position.toLong(),
            space.name,
            space.description,
            space.myRole.toLong(),
            space.createdBy,
            space.createdAt,
            space.updatedAt,
            space.ownerPrincipalType.toLong(),
            space.ownerPrincipalId,
            space.stewardUid,
            space.custodyRevision,
            space.policyRevision,
        )
    }

    fun persistHomeLocked(
        collection: DocumentHomeCollection,
        position: Int,
        item: DocumentHomeItem,
    ) {
        queries.insertDocumentHomeItem(
            collection.storageCode,
            position.toLong(),
            item.documentId,
            item.spaceId,
            item.spaceName,
            item.title,
            item.excerpt,
            item.createdBy,
            item.creatorName,
            item.createdAt,
            item.updatedAt,
            item.accessedAt,
        )
    }

    fun persistNodeLocked(node: DocumentNode, parentKey: String) {
        queries.upsertDocumentNode(
            node.spaceId,
            node.nodeId,
            parentKey,
            if (node.hasChildren) 1L else 0L,
            node.name,
            node.excerpt,
            node.revision,
            node.createdBy,
            node.createdAt,
            node.updatedBy,
            node.updatedAt,
        )
    }

    fun loadSpacesLocked(): List<DocumentSpace> =
        queries.selectAllDocumentSpaces().executeAsList().map { row ->
            DocumentSpace(
                spaceId = row.space_id,
                name = row.name,
                description = row.description,
                myRole = row.my_role.toInt(),
                createdBy = row.created_by,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
                ownerPrincipalType = row.owner_principal_type.toInt(),
                ownerPrincipalId = row.owner_principal_id,
                stewardUid = row.steward_uid,
                custodyRevision = row.custody_revision,
                policyRevision = row.policy_revision,
            )
        }

    fun loadKnownSpaceIdsLocked(): Set<String> =
        queries.selectDocumentProjectionSpaceIds().executeAsList().toCollection(linkedSetOf())

    /** 省略与有界索引驱逐绝不意味着撤销。 */
    fun replaceSpaceWorkingSetLocked(
        replacement: List<DocumentSpace>,
        markCached: Boolean,
        refreshedRows: List<DocumentSpace> = emptyList(),
    ) {
        // 容量驱逐只修剪有界 space 索引。它不是 ACL 墓碑，绝不能丢弃打开的标签页可能仍在使用
        // 的独立有界 bodies/branches。
        queries.transaction {
            queries.deleteAllDocumentSpaces()
            replacement.forEachIndexed { index, space -> persistSpaceLocked(space, index) }
            if (markCached) queries.markDocumentProjectionCached(spacesProjectionCode)
            refreshedRows.forEach { space ->
                queries.updateDocumentHomeSpaceName(space.name, space.spaceId)
            }
        }
    }
}
