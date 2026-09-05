package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.attachment.DocumentAttachmentAccess
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.model.UserRole

/** 从同一个文档/ACL PostgreSQL 快照解析附件下载权限。 */
class DocumentEmbeddedAssetAccessService(
    private val repository: DocumentRepository,
    private val unitOfWork: PgUnitOfWork,
) : DocumentAttachmentAccess {
    override suspend fun canRead(uid: String, path: String): Boolean = unitOfWork.read {
        val actor = repository.findUser(transaction, uid)
        if (actor == null || actor.role != UserRole.HUMAN || actor.status != USER_STATUS_ACTIVE) {
            return@read false
        }
        val spaceIds = repository.findActiveEmbeddedAssetSpaceIds(
            transaction = transaction,
            path = path,
            limit = MAX_POLICY_ROOTS_PER_PATH + 1,
        )
        // 一个已上传对象不应跨越数千个独立的安全根。拒绝这种不可能的投影，
        // 而不是截断它并产生依赖顺序的 ACL。
        if (spaceIds.size > MAX_POLICY_ROOTS_PER_PATH) return@read false
        spaceIds.any { spaceId ->
            val snapshot = repository.readAccessSnapshot(transaction, uid, spaceId)
            val candidate = snapshot.candidates.singleOrNull() ?: return@any false
            DocumentAuthorizationPolicy.resolve(
                actorUid = uid,
                space = candidate.space,
                grants = candidate.grants,
                directUnitIds = snapshot.directUnitIds,
                unitAndAncestorIds = snapshot.unitAndAncestorIds,
                required = DocumentCapability.READ,
            ).allowed
        }
    }

    private companion object {
        const val USER_STATUS_ACTIVE = 1
        const val MAX_POLICY_ROOTS_PER_PATH = 4_096
    }
}
