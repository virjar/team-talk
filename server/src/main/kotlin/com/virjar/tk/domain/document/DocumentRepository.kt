package com.virjar.tk.domain.document

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.User

/** 文档空间持久化端口。正文修订只追加，节点变更使用 revision 乐观锁。 */
interface DocumentRepository {
    fun findSpace(spaceId: String): DocumentSpace?
    fun listSpaceAccessCandidates(
        actorUid: String,
        directUnitIds: Set<String>,
        unitAndAncestorIds: Set<String>,
    ): List<DocumentSpaceAccessCandidate>
    /**
     * Lock the active space aggregate and resolve the actor's ACL from the same PostgreSQL
     * transaction. Implementations must serialize this snapshot with grant revocation, space
     * archival and every node/revision write rooted at the space.
     */
    fun lockWriteAuthority(
        transaction: PgTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredOrganizationUnitIds: Set<String> = emptySet(),
        requiredUserIds: Set<String> = emptySet(),
    ): DocumentWriteAuthority

    /** Resolve grant principals inside the command transaction after authority pre-locks them. */
    fun findUser(transaction: PgTransactionContext, uid: String): User?
    fun findActiveOrganizationUnitName(transaction: PgTransactionContext, unitId: String): String?

    fun createSpace(transaction: PgTransactionContext, space: DocumentSpace): DocumentSpace
    fun updateSpace(
        transaction: PgTransactionContext,
        spaceId: String,
        name: String,
        description: String?,
        updatedAt: Long,
    ): DocumentSpace
    fun archiveSpace(transaction: PgTransactionContext, spaceId: String, updatedAt: Long)

    fun listGrants(spaceId: String): List<DocumentSpaceGrant>
    fun upsertGrant(transaction: PgTransactionContext, grant: DocumentSpaceGrant)
    fun removeGrant(
        transaction: PgTransactionContext,
        spaceId: String,
        principalType: Int,
        principalId: String,
    )

    fun listNodes(spaceId: String, parentId: String?): List<DocumentNode>
    fun findNode(nodeId: String): DocumentNode?
    fun findDocument(documentId: String): Document?
    fun listNodes(transaction: PgTransactionContext, spaceId: String, parentId: String?): List<DocumentNode>
    fun findNode(transaction: PgTransactionContext, nodeId: String): DocumentNode?
    fun findDocument(transaction: PgTransactionContext, documentId: String): Document?
    fun createFolder(transaction: PgTransactionContext, node: DocumentNode): DocumentNode
    fun createDocument(
        transaction: PgTransactionContext,
        document: Document,
        initialRevision: DocumentRevision,
    ): Document
    fun updateDocument(
        transaction: PgTransactionContext,
        spaceId: String,
        documentId: String,
        expectedRevision: Long,
        title: String,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
    ): Document
    fun moveNode(
        transaction: PgTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentNode
    fun deleteNode(
        transaction: PgTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        actorUid: String,
        updatedAt: Long,
    )

    fun listRevisions(documentId: String): List<DocumentRevisionSummary>
    fun findRevision(documentId: String, revision: Long): DocumentRevision?

    fun touchRecentDocument(
        transaction: PgTransactionContext,
        actorUid: String,
        documentId: String,
        accessedAt: Long,
    )
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

/**
 * Authorization facts captured only after the active space row is locked.
 *
 * Direct membership rows and every active organization row used to resolve an inherited grant are
 * locked by the adapter before this value is returned. The domain remains the owner of effective
 * role semantics while PostgreSQL owns the linearization boundary.
 */
data class DocumentWriteAuthority(
    val space: DocumentSpace,
    val grants: List<DocumentSpaceGrant>,
    val directUnitIds: Set<String>,
    val unitAndAncestorIds: Set<String>,
)

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
