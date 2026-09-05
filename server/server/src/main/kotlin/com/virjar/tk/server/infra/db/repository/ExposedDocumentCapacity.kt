package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and

/**
 * 用于准入文档容量写入的小型索引探测。
 *
 * 此协作者不拥有锁。调用方必须先持有空间创建的拥有者 Users 行，
 * 或节点创建/移动的活跃 DocumentSpaces 行。有界的 COUNT 子查询
 * 随后观察一个已序列化的聚合快照，并在达到硬上限时立即停止。
 */
internal object ExposedDocumentCapacity {
    fun requireOwnerSpaceSlot(
        transaction: PgWriteTransactionContext,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
    ) {
        transaction.requireExposedTransaction()
        val activeCount = DocumentSpaces
            .select(DocumentSpaces.spaceId)
            .where {
                (DocumentSpaces.ownerPrincipalType eq ownerPrincipalType) and
                    (DocumentSpaces.ownerPrincipalId eq ownerPrincipalId) and
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
            }
            .limit(DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER)
            .count()
        DocumentCapacityPolicy.requireSpaceSlot(activeCount)
    }

    /** 调用方持有 [stewardUid] 对应的 [Users.uid]，序列化每个新的责任人名额。 */
    fun requireStewardshipSlot(
        transaction: PgWriteTransactionContext,
        stewardUid: String,
    ) {
        transaction.requireExposedTransaction()
        val activeCount = DocumentSpaces
            .select(DocumentSpaces.spaceId)
            .where {
                (DocumentSpaces.stewardUid eq stewardUid) and
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
            }
            .limit(DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER)
            .count()
        DocumentCapacityPolicy.requireStewardshipSlot(activeCount)
    }

    /** 调用方持有被授权用户的 Users 行，即跨空间分配 fence。 */
    fun requireDirectUserGrantSlot(
        transaction: PgWriteTransactionContext,
        uid: String,
    ) {
        transaction.requireExposedTransaction()
        val currentCount = DocumentSpaceGrants
            .select(DocumentSpaceGrants.id)
            .where {
                (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                    (DocumentSpaceGrants.principalId eq uid)
            }
            .limit(DocumentCapacityPolicy.MAX_DIRECT_DOCUMENT_GRANTS_PER_USER)
            .count()
        DocumentCapacityPolicy.requireDirectUserGrantSlot(currentCount)
    }

    fun requireSpaceDocumentSlot(transaction: PgWriteTransactionContext, spaceId: String) {
        transaction.requireExposedTransaction()
        val activeCount = DocumentNodes
            .select(DocumentNodes.nodeId)
            .where {
                (DocumentNodes.spaceId eq spaceId) and
                    (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
            }
            .limit(DocumentCapacityPolicy.MAX_ACTIVE_DOCUMENTS_PER_SPACE)
            .count()
        DocumentCapacityPolicy.requireDocumentSlot(activeCount)
    }

    fun requireParentChildSlot(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        parentId: String?,
    ) {
        transaction.requireExposedTransaction()
        val activeCount = DocumentNodes
            .select(DocumentNodes.nodeId)
            .where {
                val parentMatches = if (parentId == null) {
                    DocumentNodes.parentId.isNull()
                } else {
                    DocumentNodes.parentId eq parentId
                }
                (DocumentNodes.spaceId eq spaceId) and
                    parentMatches and
                    (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
            }
            .limit(DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT)
            .count()
        DocumentCapacityPolicy.requireChildSlot(activeCount)
    }
}
