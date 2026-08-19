package com.virjar.tk.domain.document

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant

/** 文档空间持久化端口。正文修订只追加，节点变更使用 revision 乐观锁。 */
interface DocumentRepository {
    fun findSpace(spaceId: String): DocumentSpace?
    fun listSpaceAccessCandidates(
        actorUid: String,
        directUnitIds: Set<String>,
        unitAndAncestorIds: Set<String>,
    ): List<DocumentSpaceAccessCandidate>
    fun createSpace(space: DocumentSpace): DocumentSpace
    fun updateSpace(spaceId: String, name: String, description: String?, updatedAt: Long): DocumentSpace
    fun archiveSpace(spaceId: String, updatedAt: Long)

    fun listGrants(spaceId: String): List<DocumentSpaceGrant>
    fun upsertGrant(grant: DocumentSpaceGrant)
    fun removeGrant(spaceId: String, principalType: Int, principalId: String)

    fun listNodes(spaceId: String, parentId: String?): List<DocumentNode>
    fun findNode(nodeId: String): DocumentNode?
    fun findDocument(documentId: String): Document?
    fun createFolder(node: DocumentNode): DocumentNode
    fun createDocument(document: Document, initialRevision: DocumentRevision): Document
    fun updateDocument(
        documentId: String,
        expectedRevision: Long,
        title: String,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
    ): Document
    fun moveNode(
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentNode
    fun deleteNode(nodeId: String, expectedRevision: Long, actorUid: String, updatedAt: Long)

    fun listRevisions(documentId: String): List<DocumentRevisionSummary>
    fun findRevision(documentId: String, revision: Long): DocumentRevision?

    fun touchRecentDocument(actorUid: String, documentId: String, accessedAt: Long)
    fun listRecentDocuments(
        actorUid: String,
        accessibleSpaceIds: Set<String>,
        limit: Int,
    ): List<DocumentHomeRecord>
    fun listRecentlyCreatedDocuments(
        accessibleSpaceIds: Set<String>,
        limit: Int,
    ): List<DocumentHomeRecord>
}

/** SQL 侧预筛后的空间及相关授权；领域层仍须执行最终 effectiveRole 判定。 */
data class DocumentSpaceAccessCandidate(
    val space: DocumentSpace,
    val grants: List<DocumentSpaceGrant>,
)

/** SQL 首页投影；已包含空间名、持久化摘要和创建人显示名，不携带完整 Markdown。 */
data class DocumentHomeRecord(
    val documentId: String,
    val spaceId: String,
    val spaceName: String,
    val title: String,
    val excerpt: String,
    val createdBy: String,
    val creatorName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val accessedAt: Long,
)
