package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.EmbeddedAsset

/**
 * 文档空间事务端口。所有读取都必须加入调用方的 PostgreSQL 快照；正文修订只追加，节点变更使用
 * 聚合 revision 乐观锁。
 */
interface DocumentRepository {
    fun findSpace(transaction: PgReadTransactionContext, spaceId: String): DocumentSpace?

    /**
     * 从单个可重复读 PostgreSQL 快照中捕获一次授权决策所使用的每个组织成员关系与授权
     * 事实。该快照包含那个活跃空间及其全部授权，即使操作者没有任何匹配的授权。
     */
    fun readAccessSnapshot(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
    ): DocumentReadAccessSnapshot

    /**
     * 返回一个 SQL 限定的、去重的访问相关候选页。
     *
     * 实现必须在稳定的排他键集（keyset）与 `limit + 1` 之前应用当前所有者/用户/组织 ACL
     * 谓词。用于解析 [DocumentSpace.myRole] 的授权事实随后仅为选中的页面加载。[after]
     * 无需仍然指向一个可访问或存在的行：它是一个不可变的排序边界，而不是授权凭证。
     */
    fun readAccessibleSpacePage(
        transaction: PgReadTransactionContext,
        actorUid: String,
        after: DocumentSpacePageAnchor?,
        pageSize: Int,
    ): DocumentSpaceAccessPage

    /**
     * 按 uid 顺序锁定操作者与必需的用户主体行，然后锁定活跃空间聚合及其 ACL，并从同一个
     * PostgreSQL 事务解析操作者的权威。实现必须把这个快照与用户封禁、授权撤回、空间归档
     * 以及每个以该空间为根的节点/修订写入串行化。缺失的目标行在 [DocumentWriteAuthority]
     * 中返回，而不是在领域校验操作者角色之前披露。
     */
    fun lockWriteAuthority(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredOrganizationUnitIds: Set<String> = emptySet(),
        requiredUserIds: Set<String> = emptySet(),
    ): DocumentWriteAuthority

    /**
     * 先锁定操作者 User 行，再锁定空间聚合，即使它已经被归档。
     *
     * 破坏性命令在检查其完成标记之前使用本围栏。因此精确重试在持有同一聚合锁时观察到
     * 首次提交，而不是让只读预检与之后的变更事务竞争。
     */
    fun lockDestructiveCommandSpace(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    )

    /**
     * 无论活跃/归档状态，都先锁定操作者 User 行，再锁定空间聚合。
     * 这是可重试文档创建命令的固定准入顺序。
     */
    fun lockDocumentCreateCommandFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    )

    /** 在检查移动/重命名命令回执之前，先锁定操作者，再锁定任意状态的空间。 */
    fun lockNodeMoveCommandFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    )

    /**
     * 在任何 User、Space 或 OrganizationUnit 行被锁定之前，先把归属所有权变更与组织变更
     * 串行化。这个全局围栏也让一个在途命令回执在评估当前空间授权之前对其精确重试可见。
     */
    fun lockCustodyTransferFence(transaction: PgWriteTransactionContext)

    /** 在权威预锁定主体之后，于命令事务内解析授权主体。 */
    fun findUser(transaction: PgReadTransactionContext, uid: String): User?
    fun findActiveOrganizationUnitName(transaction: PgReadTransactionContext, unitId: String): String?

    /**
     * 由所有者 User 行串行化的、可安全重试的创建。一个新 ID 必须原子地消耗一个
     * [DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER] 槽位；精确的已有创建回执在配额
     * 准入之前返回。
     */
    fun createSpace(
        transaction: PgWriteTransactionContext,
        space: DocumentSpace,
        creationFingerprint: String,
    ): DocumentSpaceCreateResult
    fun updateSpace(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        name: String,
        description: String?,
        updatedAt: Long,
    ): DocumentSpace
    fun isSpaceArchivedByCommand(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
    ): Boolean
    fun archiveSpace(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
        updatedAt: Long,
    )

    /** 一次归属交接操作 id 的不可变可靠命令回执。 */
    fun findCustodyTransferReceipt(
        transaction: PgReadTransactionContext,
        operationId: String,
    ): DocumentCustodyTransferReceipt?

    /**
     * 移动可变业务所有权，同时保留 [DocumentSpace.createdBy]。
     *
     * 调用方已经通过 [lockWriteAuthority] 持有目标主体行与活跃空间聚合锁。实现必须比对
     * [expectedCustodyRevision]，执行目标所有者的容量限制，并原子地追加不可变命令回执。
     */
    fun transferSpaceCustody(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        expectedCustodyRevision: Long,
        operationId: String,
        fingerprint: String,
        updatedAt: Long,
    ): DocumentSpace

    /**
     * 返回至多 [DocumentSpaceGrant.MAX_GRANTS_PER_SPACE] 条授权，并批量解析显示名。
     */
    fun listGrants(transaction: PgReadTransactionContext, spaceId: String): List<DocumentSpaceGrant>

    /**
     * 按字典序锁定每个被寻址的 User，然后锁定任意状态的空间聚合。
     *
     * 不可变回执仅在本围栏之后被检查。因此精确的 ACK 丢失重试可以在活跃用户/当前 ACL
     * 准入之前完成确认，而新命令继续通过普通的类型化授权快照。
     */
    fun lockPolicyMutationFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredUserIds: Set<String>,
    ): DocumentPolicyMutationFence

    fun findPolicyMutationReceipt(
        transaction: PgReadTransactionContext,
        actorUid: String,
        operationId: String,
    ): DocumentPolicyMutationReceipt?

    /**
     * 仅删除有限重试期限已经结束的身份，然后为新命令保留一个槽位。调用方持有操作者
     * User 行，它串行化这个按操作者限定作用域的保留窗口。精确重放必须在调用本方法之前
     * 检查。
     */
    fun pruneExpiredPolicyMutationReceiptsAndRequireCapacity(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        nowMillis: Long,
    )

    /** 原子地应用授权增量，推进其根修订，并追加其回执。 */
    fun commitPolicyMutation(
        transaction: PgWriteTransactionContext,
        command: DocumentPolicyMutationCommit,
    )

    fun listNodes(transaction: PgReadTransactionContext, spaceId: String, parentId: String?): List<DocumentNode>
    /** 仅在已经授权的空间边界内解析活跃节点。 */
    fun findNode(transaction: PgReadTransactionContext, spaceId: String, nodeId: String): DocumentNode?

    /** 在已授权空间内解析完整的活跃根到目标摘要路径。 */
    fun findPathSpine(
        transaction: PgReadTransactionContext,
        spaceId: String,
        nodeId: String,
    ): DocumentPathSpine

    /** 仅在已经授权的空间边界内解析活跃内容。 */
    fun findDocument(transaction: PgReadTransactionContext, spaceId: String, documentId: String): Document?
    /**
     * 仅在已经授权的空间边界内解析活跃文档身份。
     *
     * 这个投影绝不能读取当前 Markdown 快照或解析祖先路径。
     */
    fun findActiveDocumentIdentity(
        transaction: PgReadTransactionContext,
        spaceId: String,
        documentId: String,
    ): ActiveDocumentIdentity?

    /**
     * 其保留修订历史引用了 [path] 的活跃文档空间策略根。主对象与缩略图都参与；已删除
     * 节点与已归档空间不参与。适配器必须返回至多 [limit] 个稳定、互不相同的标识符。
     */
    fun findActiveEmbeddedAssetSpaceIds(
        transaction: PgReadTransactionContext,
        path: String,
        limit: Int,
    ): List<String>

    /** 此前已被准入进本文档修订历史的不可变资产身份。 */
    fun findKnownEmbeddedAssets(
        transaction: PgReadTransactionContext,
        documentId: String,
        assetIds: Set<String>,
    ): List<EmbeddedAsset>
    /**
     * 在锁定空间聚合之后创建，此前已执行活跃空间总量与直接子节点预算。精确的创建重试
     * 不消耗另一个槽位。
     */
    fun createDocument(
        transaction: PgWriteTransactionContext,
        document: Document,
        initialRevision: DocumentRevision,
        creationFingerprint: String,
    ): Document

    /** 活跃与软删除节点保留的精确创建回执。调用方持有创建围栏。 */
    fun hasExactDocumentCreateReceipt(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        documentId: String,
        creationFingerprint: String,
    ): Boolean
    /** 在聚合修订 CAS 之后保存正文；节点名只通过 [moveNode] 变化。 */
    fun updateDocument(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        documentId: String,
        expectedRevision: Long,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
        assets: List<EmbeddedAsset> = emptyList(),
    ): Document
    /**
     * 跨父移动消耗一个目标子节点槽位；同父重命名不消耗。当前聚合修订在空操作检测之前
     * 检查。纯父移动只推进节点聚合，而标题变化还追加一个不可变的内容修订。
     */
    fun moveNode(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentMoveResult

    fun findNodeMoveReceipt(
        transaction: PgReadTransactionContext,
        actorUid: String,
        operationId: String,
    ): DocumentNodeMoveReceipt?

    fun pruneExpiredNodeMoveReceiptsAndRequireCapacity(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        nowMillis: Long,
    )

    fun appendNodeMoveReceipt(
        transaction: PgWriteTransactionContext,
        receipt: DocumentNodeMoveReceipt,
        createdAt: Long,
    )
    fun isNodeDeletedByCommand(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    ): Boolean
    fun deleteNode(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
        actorUid: String,
        updatedAt: Long,
    )

    /** 返回至多 [limit] 条严格早于 [beforeRevision] 的新到旧摘要；为零时返回最新。 */
    fun listRevisions(
        transaction: PgReadTransactionContext,
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ): List<DocumentRevisionSummary>
    fun findRevision(
        transaction: PgReadTransactionContext,
        documentId: String,
        revision: Long,
    ): DocumentRevision?

    fun touchRecentDocument(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        documentId: String,
        accessedAt: Long,
    )
    fun listRecentDocuments(
        transaction: PgReadTransactionContext,
        actorUid: String,
        limit: Int,
    ): DocumentHomeAccessSnapshot
    fun listRecentlyCreatedDocuments(
        transaction: PgReadTransactionContext,
        actorUid: String,
        limit: Int,
    ): DocumentHomeAccessSnapshot
}

/** 为一次移动/重命名 ACK 丢失窗口保留的不可变结果身份。 */
data class DocumentNodeMoveReceipt(
    val actorUid: String,
    val operationId: String,
    val spaceId: String,
    val nodeId: String,
    val fingerprint: String,
    val fromRevision: Long,
    val resultingRevision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
)

/** 保留在不透明文档空间游标内的不可变数据库键。 */
data class DocumentSpacePageAnchor(
    val spaceId: String,
    val snapshotVersion: DocumentDirectorySnapshotVersion,
)

/** 一个有界的 ACL 快照，加上下一次 SQL 查询的排他键。 */
data class DocumentSpaceAccessPage(
    val snapshot: DocumentReadAccessSnapshot,
    val nextAnchor: DocumentSpacePageAnchor?,
    val snapshotVersion: DocumentDirectorySnapshotVersion,
    /** 某个续页的冻结权威输入不再与本事务的快照匹配。 */
    val snapshotChanged: Boolean = false,
)

/**
 * 仅在活跃空间行被锁定之后捕获的授权事实。
 *
 * 直接成员关系行与用于解析继承授权的每个活跃组织行，都在此值返回之前由适配器锁定。
 * 领域仍是有效角色语义的所有者，而 PostgreSQL 拥有线性化边界。
 */
data class DocumentWriteAuthority(
    /** 在空间之前锁定的操作者行；null 表示拒绝，且不披露目标事实。 */
    val actor: User?,
    val space: DocumentSpace,
    val grants: List<DocumentSpaceGrant>,
    val directUnitIds: Set<String>,
    val unitAndAncestorIds: Set<String>,
    /** 本次写入快照中缺失的必需行；仅在操作者通过 ACL 之后披露。 */
    val missingRequiredOrganizationUnitIds: Set<String>,
    val missingRequiredUserIds: Set<String>,
)

data class DocumentCustodyTransferReceipt(
    val operationId: String,
    val spaceId: String,
    val actorUid: String,
    val fingerprint: String,
    val ownerPrincipalType: Int,
    val ownerPrincipalId: String,
    val stewardUid: String,
    val custodyRevision: Long,
)

/** 任意状态的可靠 ACL 命令围栏；任何调用方都不得把它用作授权决策。 */
data class DocumentPolicyMutationFence(
    val actorIsActiveHuman: Boolean,
    val space: DocumentSpace?,
    val spaceIsActive: Boolean,
)

/** 一条按操作者限定作用域的 ACL 命令的不可变身份与已提交修订。 */
data class DocumentPolicyMutationReceipt(
    val actorUid: String,
    val operationId: String,
    val spaceId: String,
    val fingerprint: String,
    val fromPolicyRevision: Long,
    val resultingPolicyRevision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
)

enum class DocumentPolicyMutationKind(val databaseValue: Int) {
    UPSERT(1),
    REMOVE(2),
}

/** 仅在调用方通过当前 ACL 与 CAS 闸门之后写入的完全归一化变更事实。 */
data class DocumentPolicyMutationCommit(
    val actorUid: String,
    val operationId: String,
    val spaceId: String,
    val fingerprint: String,
    val kind: DocumentPolicyMutationKind,
    val principalType: Int,
    val principalId: String,
    val role: Int?,
    val includeDescendants: Boolean,
    val fromPolicyRevision: Long,
    val resultingPolicyRevision: Long,
    val changed: Boolean,
    val issuedAt: Long,
    val createdAt: Long,
)

/** SQL 侧预筛后的空间及相关授权；领域层仍须执行最终 effectiveRole 判定。 */
data class DocumentSpaceAccessCandidate(
    val space: DocumentSpace,
    val grants: List<DocumentSpaceGrant>,
)

/** 一个不可变授权视图；任何调用方都不得把其字段与另一次 DB 读取重新组合。 */
data class DocumentReadAccessSnapshot(
    val candidates: List<DocumentSpaceAccessCandidate>,
    val directUnitIds: Set<String>,
    val unitAndAncestorIds: Set<String>,
)

/** 一条 SQL 限定的首页结果，与领域授权所需的策略事实配对。 */
data class DocumentHomeAccessCandidate(
    val record: DocumentHomeRecord,
    val space: DocumentSpace,
    val grants: List<DocumentSpaceGrant>,
)

/**
 * 一个最近/新近创建结果页的不可变授权视图。SQL 只提供有界候选；类型化的文档策略仍是
 * 最终的可见性权威。
 */
data class DocumentHomeAccessSnapshot(
    val candidates: List<DocumentHomeAccessCandidate>,
    val directUnitIds: Set<String>,
    val unitAndAncestorIds: Set<String>,
)

/** 用于授权文档历史而不预加载内容的最小活跃节点投影。 */
data class ActiveDocumentIdentity(
    val documentId: String,
    val spaceId: String,
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
