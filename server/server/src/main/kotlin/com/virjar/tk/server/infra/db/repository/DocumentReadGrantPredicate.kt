package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

/** Shared SQL projection of the current document read policy. */
internal fun relevantDocumentGrantCondition(
    actorUid: String,
    actorAccess: DocumentActorOrganizationAccess,
): Op<Boolean> {
    var principalMatches: Op<Boolean> =
        (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
            (DocumentSpaceGrants.principalId eq actorUid)
    if (actorAccess.directUnitIds.isNotEmpty()) {
        principalMatches = principalMatches or (
            (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                (DocumentSpaceGrants.includeDescendants eq false) and
                (DocumentSpaceGrants.principalId inList actorAccess.directUnitIds)
            )
    }
    if (actorAccess.unitAndAncestorIds.isNotEmpty()) {
        principalMatches = principalMatches or (
            (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                (DocumentSpaceGrants.includeDescendants eq true) and
                (DocumentSpaceGrants.principalId inList actorAccess.unitAndAncestorIds)
            )
    }
    return (DocumentSpaceGrants.role greaterEq DocumentSpace.ROLE_VIEWER) and
        (DocumentSpaceGrants.role lessEq DocumentSpace.ROLE_ADMIN) and principalMatches
}

