package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.attachment.DocumentAttachmentReferences
import com.virjar.tk.server.infra.db.DocumentEmbeddedAssets
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaces
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/** 活跃文档聚合每个修订区间的保留投影。 */
class ExposedDocumentAttachmentReferences(
    private val database: Database,
) : DocumentAttachmentReferences {
    override fun getReferencedPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val requested = paths.sorted()
        return transaction(database) {
            documentAssetJoin().select(
                DocumentEmbeddedAssets.attachmentPath,
                DocumentEmbeddedAssets.thumbnailPath,
            ).where {
                (
                    (DocumentEmbeddedAssets.attachmentPath inList requested) or
                        (DocumentEmbeddedAssets.thumbnailPath inList requested)
                    ) and
                    (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
            }.fold(linkedSetOf()) { referenced, row ->
                row[DocumentEmbeddedAssets.attachmentPath]
                    .takeIf(paths::contains)
                    ?.let(referenced::add)
                row[DocumentEmbeddedAssets.thumbnailPath]
                    ?.takeIf(paths::contains)
                    ?.let(referenced::add)
                referenced
            }
        }
    }

    private fun documentAssetJoin() = DocumentEmbeddedAssets.join(
        otherTable = DocumentNodes,
        joinType = JoinType.INNER,
        onColumn = DocumentEmbeddedAssets.documentId,
        otherColumn = DocumentNodes.nodeId,
    ).join(
        otherTable = DocumentSpaces,
        joinType = JoinType.INNER,
        onColumn = DocumentNodes.spaceId,
        otherColumn = DocumentSpaces.spaceId,
    )
}
