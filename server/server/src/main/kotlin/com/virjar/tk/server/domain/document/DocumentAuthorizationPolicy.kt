package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant

/** 类型化的文档动作；wire 角色整数从不直接充当授权阈值。 */
internal enum class DocumentCapability {
    READ,
    EDIT_CONTENT,
    MANAGE_SPACE,
    MANAGE_POLICY,
    ARCHIVE_SPACE,
    TRANSFER_CUSTODY,
}

internal enum class DocumentRole(val wireValue: Int) {
    VIEWER(DocumentSpace.ROLE_VIEWER),
    EDITOR(DocumentSpace.ROLE_EDITOR),
    ADMIN(DocumentSpace.ROLE_ADMIN),
    OWNER(DocumentSpace.ROLE_OWNER),
    ;

    companion object {
        /** OWNER 是结构性的责任人权威，绝不能由一条授权行物化出来。 */
        fun fromGrantWire(value: Int): DocumentRole? = entries.singleOrNull {
            it != OWNER && it.wireValue == value
        }
    }

    fun allows(capability: DocumentCapability): Boolean = when (this) {
        VIEWER -> capability == DocumentCapability.READ
        EDITOR -> capability == DocumentCapability.READ ||
            capability == DocumentCapability.EDIT_CONTENT
        ADMIN -> capability == DocumentCapability.READ ||
            capability == DocumentCapability.EDIT_CONTENT ||
            capability == DocumentCapability.MANAGE_SPACE ||
            capability == DocumentCapability.MANAGE_POLICY
        OWNER -> true
    }
}

internal data class DocumentAuthorizationResult(
    val allowed: Boolean,
    val effectiveRole: Int,
)

/**
 * 把实时的所有者、授权与组织事实解析为文档能力。
 *
 * 业务所有权被刻意设计成不是一种访问授权。只有显式指派的责任人获得 OWNER；拥有组织的
 * 成员仍然需要一条普通的组织授权。
 */
internal object DocumentAuthorizationPolicy {
    fun resolve(
        actorUid: String,
        space: DocumentSpace,
        grants: List<DocumentSpaceGrant>,
        directUnitIds: Set<String>,
        unitAndAncestorIds: Set<String>,
        required: DocumentCapability,
    ): DocumentAuthorizationResult {
        val roles = resolvedRoles(
            actorUid = actorUid,
            space = space,
            grants = grants,
            directUnitIds = directUnitIds,
            unitAndAncestorIds = unitAndAncestorIds,
        )
        return DocumentAuthorizationResult(
            allowed = roles.any { it.allows(required) },
            effectiveRole = roles.maxOfOrNull(DocumentRole::wireValue) ?: DocumentSpace.ROLE_NONE,
        )
    }

    private fun resolvedRoles(
        actorUid: String,
        space: DocumentSpace,
        grants: List<DocumentSpaceGrant>,
        directUnitIds: Set<String>,
        unitAndAncestorIds: Set<String>,
    ): List<DocumentRole> = buildList {
        if (space.stewardUid == actorUid) {
            add(DocumentRole.OWNER)
        }
        grants.forEach { grant ->
            if (grant.spaceId != space.spaceId) return@forEach
            val role = DocumentRole.fromGrantWire(grant.role) ?: return@forEach
            when (grant.principalType) {
                DocumentSpaceGrant.PRINCIPAL_USER -> {
                    if (grant.principalId != actorUid) return@forEach
                }
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> {
                    val matches = if (grant.includeDescendants) {
                        grant.principalId in unitAndAncestorIds
                    } else {
                        grant.principalId in directUnitIds
                    }
                    if (!matches) return@forEach
                }
                else -> return@forEach
            }
            add(role)
        }
    }
}
