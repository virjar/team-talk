package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.DocumentCustodyTransferResult
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole

/**
 * 文档空间授权与事务准入边界。
 *
 * 每次读取都在一个可重复读 PostgreSQL 快照内合并组织成员关系、授权与受保护载荷。
 * 每次普通写入先锁定操作者/目标 User，再锁定空间聚合与 ACL，之后解析同一策略。因此
 * 领域调用方不可能意外地从某一个数据库瞬间授权、却返回另一个瞬间的数据。
 */
internal class DocumentAccessControl(
    private val repository: DocumentRepository,
    private val unitOfWork: PgUnitOfWork,
) {
    suspend fun resolveAccessibleSpacePage(
        actorUid: String,
        after: DocumentSpacePageAnchor?,
        pageSize: Int,
    ): ResolvedDocumentSpacePage = unitOfWork.read {
        requireActiveHumanActor(repository.findUser(transaction, actorUid))
        val page = repository.readAccessibleSpacePage(transaction, actorUid, after, pageSize)
        val actorAccess = page.snapshot.actorAccess()
        val spaces = page.snapshot.candidates.map { candidate ->
            val authorization = authorize(
                actorUid,
                candidate.space,
                candidate.grants,
                actorAccess,
                DocumentCapability.READ,
            )
            check(authorization.allowed && authorization.effectiveRole >= DocumentSpace.ROLE_VIEWER) {
                "SQL document-space access candidate failed domain authorization"
            }
            candidate.space.copy(myRole = authorization.effectiveRole)
        }
        if (page.snapshotChanged) {
            check(spaces.isEmpty() && page.nextAnchor == null) {
                "Changed Document directory snapshot must be empty and terminal"
            }
        }
        ResolvedDocumentSpacePage(
            items = spaces,
            nextAnchor = page.nextAnchor,
            snapshotVersion = page.snapshotVersion,
            snapshotChanged = page.snapshotChanged,
        )
    }

    suspend fun <T> readAuthorized(
        actorUid: String,
        spaceId: String,
        required: DocumentCapability,
        block: PgReadScope.(space: DocumentSpace, effectiveRole: Int) -> T,
    ): T = unitOfWork.read {
        requireActiveHumanActor(repository.findUser(transaction, actorUid))
        val snapshot = repository.readAccessSnapshot(transaction, actorUid, spaceId)
        val candidate = snapshot.candidates.singleOrNull()
            ?: throw DocumentNotFoundException("文档空间不存在")
        val authorization = authorize(actorUid, candidate.space, candidate.grants, snapshot.actorAccess(), required)
        if (!authorization.allowed) throw DocumentAccessDeniedException("没有文档空间权限")
        block(candidate.space.copy(myRole = authorization.effectiveRole), authorization.effectiveRole)
    }

    /** 对 SQL 限定的文档首页/类搜索结果候选的最终类型化策略闸门。 */
    fun resolveReadableHomeRecords(
        actorUid: String,
        actor: User?,
        snapshot: DocumentHomeAccessSnapshot,
    ): List<DocumentHomeRecord> {
        requireActiveHumanActor(actor)
        val actorAccess = ActorAccess(snapshot.directUnitIds, snapshot.unitAndAncestorIds)
        return snapshot.candidates.map { candidate ->
            val authorization = authorize(
                actorUid,
                candidate.space,
                candidate.grants,
                actorAccess,
                DocumentCapability.READ,
            )
            check(authorization.allowed) {
                "SQL document-home access candidate failed domain authorization"
            }
            candidate.record
        }
    }

    suspend fun <T> writeAuthorized(
        actorUid: String,
        spaceId: String,
        required: DocumentCapability,
        requiredOrganizationUnitIds: Set<String> = emptySet(),
        requiredUserIds: Set<String> = emptySet(),
        block: PgWriteScope.(space: DocumentSpace, effectiveRole: Int) -> T,
    ): T = unitOfWork.write {
        authorizeWrite(
            actorUid = actorUid,
            spaceId = spaceId,
            required = required,
            requiredOrganizationUnitIds = requiredOrganizationUnitIds,
            requiredUserIds = requiredUserIds,
            block = block,
        )
    }

    /**
     * 可靠的文档创建准入。
     *
     * 在检查不可变的创建回执之前，先锁定操作者 User 与任意状态的空间行。因此一次精确的
     * 已完成重试会在当前 ACL/状态评估之前成功；每条新的或冲突的命令仍然通过实时 EDIT
     * 闸门。
     */
    suspend fun <T> createDocumentAuthorizedOrCompleted(
        actorUid: String,
        spaceId: String,
        alreadyCompleted: PgWriteScope.() -> T?,
        block: PgWriteScope.(space: DocumentSpace, effectiveRole: Int) -> T,
    ): T = unitOfWork.write {
        repository.lockDocumentCreateCommandFence(transaction, actorUid, spaceId)
        alreadyCompleted()?.let { return@write it }
        authorizeWrite(
            actorUid = actorUid,
            spaceId = spaceId,
            required = DocumentCapability.EDIT_CONTENT,
            requiredUserIds = setOf(actorUid),
            block = block,
        )
    }

    /** 可靠的移动/重命名准入，在当前 ACL 与修订检查之前先查回执。 */
    suspend fun <T> moveNodeAuthorizedOrCompleted(
        actorUid: String,
        spaceId: String,
        alreadyCompleted: PgWriteScope.() -> T?,
        block: PgWriteScope.(space: DocumentSpace, effectiveRole: Int) -> T,
    ): T = unitOfWork.write {
        repository.lockNodeMoveCommandFence(transaction, actorUid, spaceId)
        alreadyCompleted()?.let { return@write it }
        authorizeWrite(
            actorUid = actorUid,
            spaceId = spaceId,
            required = DocumentCapability.EDIT_CONTENT,
            block = block,
        )
    }

    /**
     * 在决定是否仍然需要当前授权与活跃聚合之前，把一次精确的破坏性命令重试与其首次
     * 尝试串行化。
     *
     * 完成标记属于该破坏性命令，因此在未先获取聚合锁的情况下检查它是
     * 检查时/使用时（time-of-check/time-of-use）竞争。精确的已完成重试从这个同一写入
     * 事务返回；其他所有请求继续通过正常的实时 ACL 闸门。
     */
    suspend fun writeAuthorizedOrCompleted(
        actorUid: String,
        spaceId: String,
        required: DocumentCapability,
        alreadyCompleted: PgWriteScope.() -> Boolean,
        block: PgWriteScope.(space: DocumentSpace, effectiveRole: Int) -> Unit,
    ) {
        unitOfWork.write {
            repository.lockDestructiveCommandSpace(transaction, actorUid, spaceId)
            if (alreadyCompleted()) return@write
            authorizeWrite(
                actorUid = actorUid,
                spaceId = spaceId,
                required = required,
                block = block,
            )
        }
    }

    /**
     * 可靠的归属交接（custody transfer）准入。
     *
     * 在任何其他命令行之前先获取全局组织/归属围栏。随后在评估当前空间授权之前检查匹配
     * 的不可变回执，因此即使在原责任人已经失去 OWNER、或空间后来被归档之后，精确重试
     * 仍能成功。每条新命令继续通过实时 TRANSFER_CUSTODY 能力闸门。
     */
    suspend fun transferCustodyAuthorized(
        actorUid: String,
        spaceId: String,
        requiredOrganizationUnitIds: Set<String>,
        requiredUserIds: Set<String>,
        replay: PgWriteScope.() -> DocumentCustodyTransferResult?,
        block: PgWriteScope.(space: DocumentSpace) -> DocumentCustodyTransferResult,
    ): DocumentCustodyTransferResult = unitOfWork.write {
        repository.lockCustodyTransferFence(transaction)
        replay()?.let { return@write it }
        val authority = repository.lockWriteAuthority(
            transaction,
            actorUid,
            spaceId,
            requiredOrganizationUnitIds,
            requiredUserIds,
        )
        requireActiveHumanActor(authority.actor)
        val access = ActorAccess(authority.directUnitIds, authority.unitAndAncestorIds)
        val authorization = authorize(
            actorUid,
            authority.space,
            authority.grants,
            access,
            DocumentCapability.TRANSFER_CUSTODY,
        )
        if (!authorization.allowed) throw DocumentAccessDeniedException("没有文档空间权限")
        require(authority.missingRequiredUserIds.isEmpty()) { "用户不存在" }
        require(authority.missingRequiredOrganizationUnitIds.isEmpty()) { "组织节点不存在" }
        block(authority.space)
    }

    private fun <T> PgWriteScope.authorizeWrite(
        actorUid: String,
        spaceId: String,
        required: DocumentCapability,
        requiredOrganizationUnitIds: Set<String> = emptySet(),
        requiredUserIds: Set<String> = emptySet(),
        block: PgWriteScope.(space: DocumentSpace, effectiveRole: Int) -> T,
    ): T {
        // 适配器先获取操作者/目标 User 锁，再是活跃空间与 ACL。因此用户封禁、归档与授权
        // 撤回要么先于本命令（命令被拒绝），要么后于命令的已提交结果。
        val authority = repository.lockWriteAuthority(
            transaction,
            actorUid,
            spaceId,
            requiredOrganizationUnitIds,
            requiredUserIds,
        )
        requireActiveHumanActor(authority.actor)
        val authorization = authorize(
            actorUid = actorUid,
            space = authority.space,
            grants = authority.grants,
            access = ActorAccess(authority.directUnitIds, authority.unitAndAncestorIds),
            required = required,
        )
        if (!authorization.allowed) throw DocumentAccessDeniedException("没有文档空间权限")
        require(authority.missingRequiredUserIds.isEmpty()) { "用户不存在" }
        require(authority.missingRequiredOrganizationUnitIds.isEmpty()) { "组织节点不存在" }
        return block(authority.space, authorization.effectiveRole)
    }

    private fun requireActiveHumanActor(actor: User?) {
        if (actor == null || actor.role != UserRole.HUMAN || actor.status != USER_STATUS_ACTIVE) {
            throw DocumentAccessDeniedException("没有文档空间权限")
        }
    }

    private fun DocumentReadAccessSnapshot.actorAccess() =
        ActorAccess(directUnitIds, unitAndAncestorIds)

    private fun authorize(
        actorUid: String,
        space: DocumentSpace,
        grants: List<DocumentSpaceGrant>,
        access: ActorAccess,
        required: DocumentCapability,
    ): DocumentAuthorizationResult = DocumentAuthorizationPolicy.resolve(
        actorUid = actorUid,
        space = space,
        grants = grants,
        directUnitIds = access.directUnitIds,
        unitAndAncestorIds = access.unitAndAncestorIds,
        required = required,
    )

    private data class ActorAccess(
        val directUnitIds: Set<String>,
        val unitAndAncestorIds: Set<String>,
    )

    private companion object {
        const val USER_STATUS_ACTIVE = 1
    }
}

internal data class ResolvedDocumentSpacePage(
    val items: List<DocumentSpace>,
    val nextAnchor: DocumentSpacePageAnchor?,
    val snapshotVersion: com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion,
    val snapshotChanged: Boolean,
)
