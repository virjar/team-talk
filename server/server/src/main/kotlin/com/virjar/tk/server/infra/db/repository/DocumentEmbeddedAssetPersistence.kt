package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.infra.db.DocumentEmbeddedAssets
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

internal data class PositionedEmbeddedAsset(
    val position: Int,
    val asset: EmbeddedAsset,
)

/** 由修订区间表保留的精确规范清单。 */
internal fun loadDocumentAssetsAtRevision(
    documentId: String,
    revision: Long,
): List<EmbeddedAsset> {
    val positioned = DocumentEmbeddedAssets.selectAll().where {
        (DocumentEmbeddedAssets.documentId eq documentId) and
            (DocumentEmbeddedAssets.firstRevision lessEq revision) and
            (
                DocumentEmbeddedAssets.lastRevision.isNull() or
                    (DocumentEmbeddedAssets.lastRevision greaterEq revision)
                )
    }.orderBy(
        DocumentEmbeddedAssets.position to SortOrder.ASC,
        DocumentEmbeddedAssets.assetId to SortOrder.ASC,
    ).map(ResultRow::toPositionedEmbeddedAsset)
    require(positioned.map(PositionedEmbeddedAsset::position) == positioned.indices.toList()) {
        "文档版本内嵌资产顺序损坏"
    }
    require(positioned.map { it.asset.assetId }.distinct().size == positioned.size) {
        "文档版本内嵌资产标识重复"
    }
    return positioned.map(PositionedEmbeddedAsset::asset)
}

/** 该文档曾用过的每个不可变资产身份，与活跃区间无关。 */
internal fun loadKnownDocumentAssets(
    documentId: String,
    assetIds: Set<String>,
): List<EmbeddedAsset> {
    if (assetIds.isEmpty()) return emptyList()
    val rows = DocumentEmbeddedAssets.selectAll().where {
        (DocumentEmbeddedAssets.documentId eq documentId) and
            (DocumentEmbeddedAssets.assetId inList assetIds.sorted())
    }.orderBy(
        DocumentEmbeddedAssets.assetId to SortOrder.ASC,
        DocumentEmbeddedAssets.firstRevision to SortOrder.ASC,
    ).map { it.toPositionedEmbeddedAsset().asset }
    return rows.groupBy(EmbeddedAsset::assetId).map { (assetId, versions) ->
        val first = versions.first()
        require(versions.all { it == first }) {
            "文档内嵌资产标识的元数据发生漂移: $assetId"
        }
        first
    }
}

internal fun ResultRow.toPositionedEmbeddedAsset(): PositionedEmbeddedAsset {
    val thumbnailPath = this[DocumentEmbeddedAssets.thumbnailPath]
    return PositionedEmbeddedAsset(
        position = this[DocumentEmbeddedAssets.position],
        asset = EmbeddedAsset(
            assetId = this[DocumentEmbeddedAssets.assetId],
            attachment = Attachment(
                path = this[DocumentEmbeddedAssets.attachmentPath],
                name = this[DocumentEmbeddedAssets.attachmentName],
                contentType = this[DocumentEmbeddedAssets.attachmentContentType],
                size = this[DocumentEmbeddedAssets.attachmentSize],
            ),
            thumbnail = thumbnailPath?.let { path ->
                Attachment(
                    path = path,
                    name = checkNotNull(this[DocumentEmbeddedAssets.thumbnailName]),
                    contentType = checkNotNull(this[DocumentEmbeddedAssets.thumbnailContentType]),
                    size = checkNotNull(this[DocumentEmbeddedAssets.thumbnailSize]),
                )
            },
            width = this[DocumentEmbeddedAssets.width],
            height = this[DocumentEmbeddedAssets.height],
        ),
    )
}
