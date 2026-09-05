package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.command.canonicalOperationId
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.UserRole
import java.util.UUID

/** 显式 Document ACL 变更的、可靠的、按操作者限定作用域的命令边界。 */
internal class DocumentPolicyMutationService(
    private val repository: DocumentRepository,
    private val unitOfWork: PgUnitOfWork,
    private val wallClockMillis: () -> Long,
) {
    suspend fun upsertGrant(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentPolicyMutationResult {
        require(role in DocumentSpace.ROLE_VIEWER..DocumentSpace.ROLE_ADMIN) { "空间角色非法" }
        return mutateGrantPolicy(
            actorUid = actorUid,
            spaceId = spaceId,
            principalType = principalType,
            principalId = principalId,
            role = role,
            includeDescendants = includeDescendants,
            expectedPolicyRevision = expectedPolicyRevision,
            operationId = operationId,
            issuedAt = issuedAt,
            kind = DocumentPolicyMutationKind.UPSERT,
        )
    }

    suspend fun removeGrant(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentPolicyMutationResult = mutateGrantPolicy(
        actorUid = actorUid,
        spaceId = spaceId,
        principalType = principalType,
        principalId = principalId,
        role = null,
        includeDescendants = false,
        expectedPolicyRevision = expectedPolicyRevision,
        operationId = operationId,
        issuedAt = issuedAt,
        kind = DocumentPolicyMutationKind.REMOVE,
    )

    private suspend fun mutateGrantPolicy(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int?,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
        kind: DocumentPolicyMutationKind,
    ): DocumentPolicyMutationResult {
        require(
            principalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "授权对象类型非法" }
        require(expectedPolicyRevision > 0L) { "文档空间权限版本非法" }
        val validatedSpaceId = validateResourceId(spaceId, "文档空间标识")
        val validatedPrincipalId = validatePrincipalId(principalId, "授权对象标识")
        val validatedOperationId = canonicalOperationId(operationId, "文档权限")
        val canonicalIncludeDescendants =
            principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT && includeDescendants
        val fingerprint = reliableCommandFingerprint(
            "document-space-policy-v2",
            actorUid,
            validatedSpaceId,
            kind.name,
            principalType.toString(),
            validatedPrincipalId,
            role?.toString(),
            canonicalIncludeDescendants.toString(),
            expectedPolicyRevision.toString(),
            issuedAt.toString(),
        )
        val addressedUserIds = if (principalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            setOf(validatedPrincipalId)
        } else {
            emptySet()
        }
        val requiredOrganizationUnitIds = if (
            kind == DocumentPolicyMutationKind.UPSERT &&
            principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT
        ) {
            setOf(validatedPrincipalId)
        } else {
            emptySet()
        }

        return unitOfWork.write {
            // 阶段 1：稳定 identity 命中收据时直接重放原结果，不再执行任何变更。
            replayPolicyMutationReceipt(
                transaction = transaction,
                actorUid = actorUid,
                spaceId = validatedSpaceId,
                operationId = validatedOperationId,
                fingerprint = fingerprint,
                issuedAt = issuedAt,
                addressedUserIds = addressedUserIds,
            )?.let { return@write it }

            // 阶段 2：准入——收据容量、操作者/空间活性、写权威与 MANAGE_POLICY 裁决、
            // 版本冲突与责任人守卫全部通过后才允许规划变更。
            val authority = admitPolicyMutation(
                transaction = transaction,
                actorUid = actorUid,
                spaceId = validatedSpaceId,
                kind = kind,
                principalType = principalType,
                principalId = validatedPrincipalId,
                expectedPolicyRevision = expectedPolicyRevision,
                addressedUserIds = addressedUserIds,
                requiredOrganizationUnitIds = requiredOrganizationUnitIds,
                issuedAt = issuedAt,
            )

            // 阶段 3：规划——canonical grant、幂等比对、结果版本与结果授权列表。
            val plan = planGrantMutation(
                transaction = transaction,
                authority = authority,
                kind = kind,
                principalType = principalType,
                principalId = validatedPrincipalId,
                role = role,
                includeDescendants = canonicalIncludeDescendants,
                expectedPolicyRevision = expectedPolicyRevision,
                spaceId = validatedSpaceId,
            )

            // 阶段 4：提交并在结果空间上重算操作者可见角色。
            commitPlannedPolicyMutation(
                transaction = transaction,
                actorUid = actorUid,
                operationId = validatedOperationId,
                spaceId = validatedSpaceId,
                fingerprint = fingerprint,
                kind = kind,
                principalType = principalType,
                principalId = validatedPrincipalId,
                issuedAt = issuedAt,
                plan = plan,
                authority = authority,
                expectedPolicyRevision = expectedPolicyRevision,
            )
        }
    }

    /**
     * 稳定 operationId 命中既有收据时的重放路径：校验 identity 一致与 issuedAt 仍在窗内，
     * 然后按当前空间/操作者活性返回原变更结果（或其降级角色）。无收据返回 null。
     */
    private fun replayPolicyMutationReceipt(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
        fingerprint: String,
        issuedAt: Long,
        addressedUserIds: Set<String>,
    ): DocumentPolicyMutationResult? {
        val receipt = repository.findPolicyMutationReceipt(transaction, actorUid, operationId)
            ?: return null
        if (receipt.spaceId != spaceId || receipt.fingerprint != fingerprint) {
            throw ReliableCommandConflictException("文档权限操作标识已用于不同请求")
        }
        // 保留的身份在当前准入与容量之前被检查。其有限生命周期仍然适用，因此
        // 回收永远不会把一次旧的重试变成新的变更。
        ReliableCommandPolicy.requireActiveIssuedAt(issuedAt, wallClockMillis(), "文档权限操作")
        val fence = repository.lockPolicyMutationFence(
            transaction = transaction,
            actorUid = actorUid,
            spaceId = spaceId,
            requiredUserIds = addressedUserIds,
        )
        val currentSpace = fence.space
        if (!fence.actorIsActiveHuman || !fence.spaceIsActive || currentSpace == null) {
            return DocumentPolicyMutationResult(
                spaceId = receipt.spaceId,
                policyRevision = currentSpace?.policyRevision ?: receipt.resultingPolicyRevision,
                effectiveRole = DocumentSpace.ROLE_NONE,
            )
        }
        val currentAuthority = repository.lockWriteAuthority(
            transaction = transaction,
            actorUid = actorUid,
            spaceId = spaceId,
        )
        return DocumentPolicyMutationResult(
            spaceId = spaceId,
            policyRevision = currentAuthority.space.policyRevision,
            effectiveRole = resolveEffectiveDocumentRole(actorUid, currentAuthority),
        )
    }

    /** 变更准入：收据容量、操作者/空间活性、写权威、MANAGE_POLICY、版本冲突与责任人守卫。 */
    private fun admitPolicyMutation(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        kind: DocumentPolicyMutationKind,
        principalType: Int,
        principalId: String,
        expectedPolicyRevision: Long,
        addressedUserIds: Set<String>,
        requiredOrganizationUnitIds: Set<String>,
        issuedAt: Long,
    ): DocumentWriteAuthority {
        val fence = repository.lockPolicyMutationFence(
            transaction = transaction,
            actorUid = actorUid,
            spaceId = spaceId,
            requiredUserIds = addressedUserIds,
        )
        val admittedAt = wallClockMillis()
        ReliableCommandPolicy.requireActiveIssuedAt(issuedAt, admittedAt, "文档权限操作")
        repository.pruneExpiredPolicyMutationReceiptsAndRequireCapacity(
            transaction,
            actorUid,
            admittedAt,
        )
        if (!fence.actorIsActiveHuman) {
            throw DocumentAccessDeniedException("没有文档空间权限")
        }
        if (!fence.spaceIsActive || fence.space == null) {
            throw DocumentNotFoundException("文档空间不存在")
        }
        val authority = repository.lockWriteAuthority(
            transaction = transaction,
            actorUid = actorUid,
            spaceId = spaceId,
            requiredOrganizationUnitIds = requiredOrganizationUnitIds,
            requiredUserIds = addressedUserIds,
        )
        val actor = authority.actor
        if (actor?.role != UserRole.HUMAN || actor.status != USER_STATUS_ACTIVE) {
            throw DocumentAccessDeniedException("没有文档空间权限")
        }
        val authorization = DocumentAuthorizationPolicy.resolve(
            actorUid = actorUid,
            space = authority.space,
            grants = authority.grants,
            directUnitIds = authority.directUnitIds,
            unitAndAncestorIds = authority.unitAndAncestorIds,
            required = DocumentCapability.MANAGE_POLICY,
        )
        if (!authorization.allowed) throw DocumentAccessDeniedException("没有文档空间权限")
        if (kind == DocumentPolicyMutationKind.UPSERT) {
            require(authority.missingRequiredUserIds.isEmpty()) { "用户不存在" }
            require(authority.missingRequiredOrganizationUnitIds.isEmpty()) { "组织节点不存在" }
        }
        if (authority.space.policyRevision != expectedPolicyRevision) {
            throw ReliableCommandConflictException("文档空间权限已被其他操作更新")
        }
        require(
            principalId != authority.space.stewardUid ||
                principalType != DocumentSpaceGrant.PRINCIPAL_USER,
        ) {
            if (kind == DocumentPolicyMutationKind.UPSERT) {
                "空间所有者不需要重复授权"
            } else {
                "不能移除空间责任人的隐式权限"
            }
        }
        return authority
    }

    /** 一次已准入变更的规划结果：canonical grant、是否实际变化、结果版本与结果授权列表。 */
    private data class PlannedGrantMutation(
        val canonicalGrant: DocumentSpaceGrant?,
        val changed: Boolean,
        val resultingPolicyRevision: Long,
        val resultingGrants: List<DocumentSpaceGrant>,
    )

    private fun planGrantMutation(
        transaction: PgWriteTransactionContext,
        authority: DocumentWriteAuthority,
        kind: DocumentPolicyMutationKind,
        principalType: Int,
        principalId: String,
        role: Int?,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        spaceId: String,
    ): PlannedGrantMutation {
        val canonicalGrant = if (kind == DocumentPolicyMutationKind.UPSERT) {
            when (principalType) {
                DocumentSpaceGrant.PRINCIPAL_USER -> {
                    val user = repository.findUser(transaction, principalId)
                        ?: throw IllegalArgumentException("用户不存在")
                    require(user.role == UserRole.HUMAN && user.status == USER_STATUS_ACTIVE) {
                        "只能向活动普通用户授予文档空间"
                    }
                }
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> requireNotNull(
                    repository.findActiveOrganizationUnitName(transaction, principalId),
                ) { "组织节点不存在" }
            }
            DocumentSpaceGrant(
                spaceId = spaceId,
                principalType = principalType,
                principalId = principalId,
                role = requireNotNull(role),
                includeDescendants = includeDescendants,
            )        } else {
            null
        }
        val matchingGrant = authority.grants.singleOrNull { grant ->
            grant.principalType == principalType && grant.principalId == principalId
        }
        val changed = when (kind) {
            DocumentPolicyMutationKind.UPSERT -> matchingGrant?.let { existing ->
                existing.role != canonicalGrant?.role ||
                    existing.includeDescendants != canonicalGrant.includeDescendants
            } ?: true
            DocumentPolicyMutationKind.REMOVE -> matchingGrant != null
        }
        val resultingPolicyRevision = if (changed) {
            if (expectedPolicyRevision == Long.MAX_VALUE) {
                throw ReliableCommandConflictException("文档空间权限版本已耗尽")
            }
            expectedPolicyRevision + 1L
        } else {
            expectedPolicyRevision
        }
        val resultingGrants = when (kind) {
            DocumentPolicyMutationKind.UPSERT -> authority.grants
                .filterNot { grant ->
                    grant.principalType == principalType && grant.principalId == principalId
                } + requireNotNull(canonicalGrant)
            DocumentPolicyMutationKind.REMOVE -> authority.grants.filterNot { grant ->
                grant.principalType == principalType && grant.principalId == principalId
            }
        }
        return PlannedGrantMutation(
            canonicalGrant = canonicalGrant,
            changed = changed,
            resultingPolicyRevision = resultingPolicyRevision,
            resultingGrants = resultingGrants,
        )
    }

    /** 提交收据与结果空间，并在结果授权列表上重算操作者的可见角色。 */
    private fun commitPlannedPolicyMutation(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        operationId: String,
        spaceId: String,
        fingerprint: String,
        kind: DocumentPolicyMutationKind,
        principalType: Int,
        principalId: String,
        issuedAt: Long,
        plan: PlannedGrantMutation,
        authority: DocumentWriteAuthority,
        expectedPolicyRevision: Long,
    ): DocumentPolicyMutationResult {
        val committedAt = wallClockMillis()
        ReliableCommandPolicy.requireActiveIssuedAt(issuedAt, committedAt, "文档权限操作")
        repository.commitPolicyMutation(
            transaction,
            DocumentPolicyMutationCommit(
                actorUid = actorUid,
                operationId = operationId,
                spaceId = spaceId,
                fingerprint = fingerprint,
                kind = kind,
                principalType = principalType,
                principalId = principalId,
                role = plan.canonicalGrant?.role,
                includeDescendants = plan.canonicalGrant?.includeDescendants ?: false,
                fromPolicyRevision = expectedPolicyRevision,
                resultingPolicyRevision = plan.resultingPolicyRevision,
                changed = plan.changed,
                issuedAt = issuedAt,
                createdAt = committedAt,
            ),
        )
        val resultingSpace = authority.space.copy(policyRevision = plan.resultingPolicyRevision)
        val resultingAuthorization = DocumentAuthorizationPolicy.resolve(
            actorUid = actorUid,
            space = resultingSpace,
            grants = plan.resultingGrants,
            directUnitIds = authority.directUnitIds,
            unitAndAncestorIds = authority.unitAndAncestorIds,
            required = DocumentCapability.READ,
        )
        return DocumentPolicyMutationResult(
            spaceId = spaceId,
            policyRevision = plan.resultingPolicyRevision,
            effectiveRole = resultingAuthorization.effectiveRole,
        )
    }

    private fun resolveEffectiveDocumentRole(
        actorUid: String,
        authority: DocumentWriteAuthority,
    ): Int = DocumentAuthorizationPolicy.resolve(
        actorUid = actorUid,
        space = authority.space,
        grants = authority.grants,
        directUnitIds = authority.directUnitIds,
        unitAndAncestorIds = authority.unitAndAncestorIds,
        required = DocumentCapability.READ,
    ).effectiveRole

    private fun validateResourceId(value: String, label: String): String {
        require(value.length == UUID_TEXT_LENGTH && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "$label 非法"
        }
        return value
    }

    private fun validatePrincipalId(value: String, label: String): String {
        require(value.isNotBlank() && value.length <= UUID_TEXT_LENGTH) { "$label 非法" }
        return value
    }

    private companion object {
        const val USER_STATUS_ACTIVE = 1
        const val UUID_TEXT_LENGTH = 36
    }
}
