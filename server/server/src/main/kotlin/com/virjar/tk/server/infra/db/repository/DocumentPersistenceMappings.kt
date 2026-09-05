package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentHomeRecord
import com.virjar.tk.server.domain.document.DocumentRevisionConflictException
import com.virjar.tk.server.infra.db.DocumentContentRevisions
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import org.jetbrains.exposed.sql.ResultRow

internal const val DOCUMENT_STATUS_DELETED = 0
internal const val DOCUMENT_STATUS_ACTIVE = 1

internal fun throwDocumentRevisionConflict(): Nothing = throw DocumentRevisionConflictException()

internal fun ResultRow.toDocumentSpace() = DocumentSpace(
    spaceId = this[DocumentSpaces.spaceId],
    name = this[DocumentSpaces.name],
    description = this[DocumentSpaces.description],
    myRole = DocumentSpace.ROLE_NONE,
    createdBy = this[DocumentSpaces.createdBy],
    createdAt = this[DocumentSpaces.createdAt],
    updatedAt = this[DocumentSpaces.updatedAt],
    ownerPrincipalType = this[DocumentSpaces.ownerPrincipalType],
    ownerPrincipalId = this[DocumentSpaces.ownerPrincipalId],
    stewardUid = this[DocumentSpaces.stewardUid],
    custodyRevision = this[DocumentSpaces.custodyRevision],
    policyRevision = this[DocumentSpaces.policyRevision],
)

internal fun ResultRow.toDocumentSpaceGrant() = DocumentSpaceGrant(
    spaceId = this[DocumentSpaceGrants.spaceId],
    principalType = this[DocumentSpaceGrants.principalType],
    principalId = this[DocumentSpaceGrants.principalId],
    role = this[DocumentSpaceGrants.role],
    includeDescendants = this[DocumentSpaceGrants.includeDescendants],
)

internal fun ResultRow.toDocumentNode(hasChildren: Boolean) = DocumentNode(
    nodeId = this[DocumentNodes.nodeId],
    spaceId = this[DocumentNodes.spaceId],
    parentId = this[DocumentNodes.parentId],
    hasChildren = hasChildren,
    name = this[DocumentNodes.name],
    excerpt = this[DocumentNodes.excerpt],
    revision = this[DocumentNodes.revision],
    createdBy = this[DocumentNodes.createdBy],
    createdAt = this[DocumentNodes.createdAt],
    updatedBy = this[DocumentNodes.updatedBy],
    updatedAt = this[DocumentNodes.updatedAt],
)

internal fun ResultRow.toDocument(
    ancestorIds: List<String> = emptyList(),
    assets: List<EmbeddedAsset> = emptyList(),
) = Document(
    documentId = this[DocumentNodes.nodeId],
    spaceId = this[DocumentNodes.spaceId],
    parentId = this[DocumentNodes.parentId],
    title = this[DocumentNodes.name],
    markdown = this[DocumentNodes.markdown],
    revision = this[DocumentNodes.revision],
    createdBy = this[DocumentNodes.createdBy],
    createdAt = this[DocumentNodes.createdAt],
    updatedBy = this[DocumentNodes.updatedBy],
    updatedAt = this[DocumentNodes.updatedAt],
    ancestorIds = ancestorIds,
    assets = assets,
)

internal fun Document.toNode() = DocumentNode(
    nodeId = documentId,
    spaceId = spaceId,
    parentId = parentId,
    hasChildren = false,
    name = title,
    excerpt = DocumentPolicy.markdownExcerpt(markdown),
    revision = revision,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
)

internal fun ResultRow.toDocumentRevision(assets: List<EmbeddedAsset> = emptyList()) = DocumentRevision(
    documentId = this[DocumentContentRevisions.documentId],
    revision = this[DocumentContentRevisions.revision],
    title = this[DocumentContentRevisions.title],
    markdown = this[DocumentContentRevisions.markdown],
    editedBy = this[DocumentContentRevisions.editedBy],
    editedAt = this[DocumentContentRevisions.editedAt],
    assets = assets,
)

internal fun ResultRow.toDocumentRevisionSummary() = DocumentRevisionSummary(
    documentId = this[DocumentContentRevisions.documentId],
    revision = this[DocumentContentRevisions.revision],
    title = this[DocumentContentRevisions.title],
    contentLength = this[DocumentContentRevisions.contentLength],
    editedBy = this[DocumentContentRevisions.editedBy],
    editedAt = this[DocumentContentRevisions.editedAt],
)

internal fun ResultRow.toDocumentHomeRecord(accessedAt: Long) = DocumentHomeRecord(
    documentId = this[DocumentNodes.nodeId],
    spaceId = this[DocumentNodes.spaceId],
    spaceName = this[DocumentSpaces.name],
    title = this[DocumentNodes.name],
    excerpt = this[DocumentNodes.excerpt],
    createdBy = this[DocumentNodes.createdBy],
    creatorName = this[Users.name],
    createdAt = this[DocumentNodes.createdAt],
    updatedAt = this[DocumentNodes.updatedAt],
    accessedAt = accessedAt,
)
