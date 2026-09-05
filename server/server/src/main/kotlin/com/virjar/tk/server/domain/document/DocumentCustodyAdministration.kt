package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.command.canonicalOperationId
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.UserRole
import kotlinx.serialization.Serializable

/** 一次管理型 Document 归属批量操作（custody batch）的显式目的地。 */
data class DocumentCustodyTarget(
    val ownerPrincipalType: Int,
    val ownerPrincipalId: String,
    val stewardUid: String,
)

/** 一个当前需要从 [sourceUid] 恢复的活跃空间。 */
@Serializable
data class DocumentCustodyPlanEntry(
    val spaceId: String,
    val name: String,
    val ownerPrincipalType: Int,
    val ownerPrincipalId: String,
    val stewardUid: String,
    val custodyRevision: Long,
    /** 即使来源在该空间中没有直接授权，也捕获的 ACL CAS 坐标。 */
    val policyRevision: Long,
)

/** 一条直接 USER 授权根，其当前 ACL 修订已绑定进离职交接（offboarding）计划。 */
@Serializable
data class DocumentCustodyGrantPlanEntry(
    val spaceId: String,
    val policyRevision: Long,
)

/** 用作管理型交接 CAS 输入的不可变规划快照。 */
@Serializable
data class DocumentCustodyPlan(
    val sourceUid: String,
    val targetOwnerPrincipalType: Int,
    val targetOwnerPrincipalId: String,
    val targetStewardUid: String,
    val planFingerprint: String,
    val spaces: List<DocumentCustodyPlanEntry>,
    /** 执行所移除的每条直接 USER 授权，涵盖活跃与已归档空间。 */
    val directGrants: List<DocumentCustodyGrantPlanEntry>,
)

/** 已完成的行政批量操作所保留的、按空间区分的不可变审计事实。 */
@Serializable
data class DocumentCustodyBatchItem(
    val spaceId: String,
    val fromOwnerPrincipalType: Int,
    val fromOwnerPrincipalId: String,
    val fromStewardUid: String,
    val fromCustodyRevision: Long,
    val toOwnerPrincipalType: Int,
    val toOwnerPrincipalId: String,
    val toStewardUid: String,
    val resultingCustodyRevision: Long,
)

/** 可安全重试的管理型 Document 归属命令的持久化响应。 */
@Serializable
data class DocumentCustodyBatchReceipt(
    val operationId: String,
    val adminPrincipal: String,
    val sourceUid: String,
    val targetOwnerPrincipalType: Int,
    val targetOwnerPrincipalId: String,
    val targetStewardUid: String,
    val planFingerprint: String,
    val revokedGrantCount: Int,
    val createdAt: Long,
    val items: List<DocumentCustodyBatchItem>,
)

data class DocumentCustodyBatchCommand(
    val operationId: String,
    val adminPrincipal: String,
    val sourceUid: String,
    val target: DocumentCustodyTarget,
    val expectedPlanFingerprint: String,
    val requestFingerprint: String,
)

/** 行政恢复策略所需的最小账户事实。 */
data class DocumentCustodyUserFact(
    val uid: String,
    val role: Int,
    val status: Int,
)

/** 一次事务一致的来源盘点与目标校验快照。 */
data class DocumentCustodySnapshot(
    val source: DocumentCustodyUserFact?,
    val targetSteward: DocumentCustodyUserFact?,
    val targetOwnerUnitStatus: Int?,
    val spaces: List<DocumentCustodyPlanEntry>,
    val directGrants: List<DocumentCustodyGrantPlanEntry>,
)

interface DocumentCustodyAdministrationRepository {
    fun inspect(
        transaction: PgReadTransactionContext,
        sourceUid: String,
        target: DocumentCustodyTarget,
    ): DocumentCustodySnapshot

    /**
     * 按适配器文档化的固定锁定顺序执行一个完整批次。
     * 实现绝不能循环调用普通的单空间交接命令。
     */
    fun transfer(
        transaction: PgWriteTransactionContext,
        command: DocumentCustodyBatchCommand,
        now: Long,
    ): DocumentCustodyBatchReceipt
}

/** 计划在展示给管理员之后发生了变化；不允许提交任何部分交接。 */
class DocumentCustodyPlanConflictException : IllegalArgumentException(
    "文档资产交接计划已变化，请重新盘点",
)

/**
 * 仅针对 Document 资产的行政恢复。
 *
 * 账户封禁仍然是独立的紧急凭证围栏。本服务刻意要求：一个已认证的管理员、一个显式的
 * 目的地，以及一个独立的稳定操作 id。
 */
class DocumentCustodyAdministrationService(
    private val repository: DocumentCustodyAdministrationRepository,
    private val unitOfWork: PgUnitOfWork,
) {
    suspend fun plan(
        sourceUid: String,
        targetOwnerPrincipalType: Int,
        targetOwnerPrincipalId: String,
        targetStewardUid: String,
    ): DocumentCustodyPlan {
        val source = validatePrincipalId(sourceUid, "离职员工标识")
        val target = validateTarget(
            targetOwnerPrincipalType,
            targetOwnerPrincipalId,
            targetStewardUid,
            source,
        )
        return unitOfWork.read {
            val snapshot = repository.inspect(transaction, source, target)
            DocumentCustodyAdministrationPolicy.requireValid(snapshot, target)
            snapshot.toPlan(source, target)
        }
    }

    suspend fun transfer(
        adminPrincipal: String,
        sourceUid: String,
        operationId: String,
        expectedPlanFingerprint: String,
        targetOwnerPrincipalType: Int,
        targetOwnerPrincipalId: String,
        targetStewardUid: String,
    ): DocumentCustodyBatchReceipt {
        val actor = adminPrincipal.trim().also {
            require(it.isNotEmpty() && it.length <= MAX_ADMIN_PRINCIPAL_LENGTH) {
                "管理主体非法"
            }
        }
        val source = validatePrincipalId(sourceUid, "离职员工标识")
        val target = validateTarget(
            targetOwnerPrincipalType,
            targetOwnerPrincipalId,
            targetStewardUid,
            source,
        )
        val operation = canonicalOperationId(operationId, "文档资产批量交接")
        val planFingerprint = expectedPlanFingerprint.also(::requireSha256Fingerprint)
        val requestFingerprint = reliableCommandFingerprint(
            "document-custody-batch-v1",
            actor,
            source,
            target.ownerPrincipalType.toString(),
            target.ownerPrincipalId,
            target.stewardUid,
            planFingerprint,
        )
        return unitOfWork.write {
            repository.transfer(
                transaction,
                DocumentCustodyBatchCommand(
                    operationId = operation,
                    adminPrincipal = actor,
                    sourceUid = source,
                    target = target,
                    expectedPlanFingerprint = planFingerprint,
                    requestFingerprint = requestFingerprint,
                ),
                System.currentTimeMillis(),
            )
        }
    }

    private fun validateTarget(
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        sourceUid: String,
    ): DocumentCustodyTarget {
        require(
            ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "目标归属主体类型非法" }
        val owner = validatePrincipalId(ownerPrincipalId, "目标归属主体标识")
        val steward = validatePrincipalId(stewardUid, "目标责任人标识")
        require(steward != sourceUid) { "目标责任人不能仍是离职员工" }
        if (ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            require(owner == steward) { "个人持有空间必须由本人负责" }
        }
        return DocumentCustodyTarget(ownerPrincipalType, owner, steward)
    }

    private fun validatePrincipalId(value: String, label: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 36) { "$label 非法" }
    }

    private fun requireSha256Fingerprint(value: String) {
        require(value.length == SHA256_TEXT_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "文档资产交接计划指纹非法"
        }
    }

    private companion object {
        const val SHA256_TEXT_LENGTH = 64
        const val MAX_ADMIN_PRINCIPAL_LENGTH = 100
    }
}

internal object DocumentCustodyAdministrationPolicy {
    fun requireValid(snapshot: DocumentCustodySnapshot, target: DocumentCustodyTarget) {
        val source = snapshot.source ?: throw IllegalArgumentException("离职员工不存在")
        require(source.role == UserRole.HUMAN) { "Document 资产只能从普通用户交接" }

        val steward = snapshot.targetSteward ?: throw IllegalArgumentException("目标责任人不存在")
        require(steward.role == UserRole.HUMAN && steward.status == USER_STATUS_ACTIVE) {
            "目标责任人必须是活动普通用户"
        }
        if (target.ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) {
            require(snapshot.targetOwnerUnitStatus == OrganizationUnit.STATUS_ACTIVE) {
                "目标归属组织节点不存在或已归档"
            }
        }
        require(snapshot.spaces.size <= DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER) {
            "单次 Document 资产交接超过 ${DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER} 个空间的安全上限"
        }
        require(snapshot.directGrants.size <= DocumentCapacityPolicy.MAX_DIRECT_DOCUMENT_GRANTS_PER_USER) {
            "待撤销的直接 Document 授权超过 ${DocumentCapacityPolicy.MAX_DIRECT_DOCUMENT_GRANTS_PER_USER} 条安全上限"
        }
        require(snapshot.directGrants.distinctBy(DocumentCustodyGrantPlanEntry::spaceId).size == snapshot.directGrants.size) {
            "直接 Document 授权计划包含重复空间"
        }
        snapshot.spaces.forEach { space ->
            require(space.stewardUid == source.uid) {
                "文档资产责任人已变化，请重新盘点"
            }
            require(space.custodyRevision in 1 until Long.MAX_VALUE) {
                "文档资产归属版本非法"
            }
            require(space.policyRevision > 0L) { "文档空间权限版本非法" }
        }
        snapshot.directGrants.forEach { grant ->
            require(grant.policyRevision in 1 until Long.MAX_VALUE) {
                "待撤销直接 Document 授权版本非法"
            }
        }
    }

    fun fingerprint(
        sourceUid: String,
        target: DocumentCustodyTarget,
        spaces: List<DocumentCustodyPlanEntry>,
        directGrants: List<DocumentCustodyGrantPlanEntry>,
    ): String {
        val fields = ArrayList<String?>(7 + spaces.size * 6 + directGrants.size * 2)
        fields += "document-custody-plan-v2"
        fields += sourceUid
        fields += target.ownerPrincipalType.toString()
        fields += target.ownerPrincipalId
        fields += target.stewardUid
        fields += spaces.size.toString()
        spaces.sortedBy(DocumentCustodyPlanEntry::spaceId).forEach { space ->
            fields += space.spaceId
            fields += space.custodyRevision.toString()
            fields += space.ownerPrincipalType.toString()
            fields += space.ownerPrincipalId
            fields += space.stewardUid
            fields += space.policyRevision.toString()
        }
        val orderedDirectGrants = directGrants.sortedBy(DocumentCustodyGrantPlanEntry::spaceId)
        fields += orderedDirectGrants.size.toString()
        orderedDirectGrants.forEach { grant ->
            fields += grant.spaceId
            fields += grant.policyRevision.toString()
        }
        return reliableCommandFingerprint(*fields.toTypedArray())
    }

    private const val USER_STATUS_ACTIVE = 1
}

private fun DocumentCustodySnapshot.toPlan(
    sourceUid: String,
    target: DocumentCustodyTarget,
): DocumentCustodyPlan {
    val orderedSpaces = spaces.sortedBy(DocumentCustodyPlanEntry::spaceId)
    val orderedDirectGrants = directGrants.sortedBy(DocumentCustodyGrantPlanEntry::spaceId)
    return DocumentCustodyPlan(
        sourceUid = sourceUid,
        targetOwnerPrincipalType = target.ownerPrincipalType,
        targetOwnerPrincipalId = target.ownerPrincipalId,
        targetStewardUid = target.stewardUid,
        planFingerprint = DocumentCustodyAdministrationPolicy.fingerprint(
            sourceUid,
            target,
            orderedSpaces,
            orderedDirectGrants,
        ),
        spaces = orderedSpaces,
        directGrants = orderedDirectGrants,
    )
}
