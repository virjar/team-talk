package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.infra.db.DocumentEmbeddedAssets
import com.virjar.tk.protocol.model.EmbeddedAsset
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update

internal fun insertInitialAssetManifest(
    documentId: String,
    revision: Long,
    assets: List<EmbeddedAsset>,
) {
    require(revision == 1L) { "文档初始内嵌资产版本必须为 1" }
    assets.forEachIndexed { position, asset ->
        insertAssetInterval(documentId, asset, position, revision)
    }
}

/**
 * 只关闭描述符/顺序发生变化的区间，并打开它们的替代区间。已关闭的
 * 区间保持不可变，使历史修订能继续保留并解析字节。
 */
internal fun replaceAssetManifest(
    documentId: String,
    currentRevision: Long,
    nextRevision: Long,
    currentAssets: List<EmbeddedAsset>,
    nextAssets: List<EmbeddedAsset>,
) {
    require(nextRevision == currentRevision + 1) { "文档内嵌资产版本区间非法" }
    val known = loadKnownDocumentAssets(
        documentId,
        nextAssets.mapTo(linkedSetOf(), EmbeddedAsset::assetId),
    ).associateBy(EmbeddedAsset::assetId)
    nextAssets.forEach { asset ->
        known[asset.assetId]?.let { previous ->
            require(previous == asset) {
                "同一文档的内嵌资产标识不能改绑到其他文件: ${asset.assetId}"
            }
        }
    }

    val currentPositions = currentAssets.withIndex().associate { it.value.assetId to it }
    val nextPositions = nextAssets.withIndex().associate { it.value.assetId to it }
    val unchangedAssetIds = currentPositions.keys.intersect(nextPositions.keys).filterTo(linkedSetOf()) { assetId ->
        val current = currentPositions.getValue(assetId)
        val next = nextPositions.getValue(assetId)
        current.index == next.index && current.value == next.value
    }

    currentAssets.asSequence()
        .filterNot { it.assetId in unchangedAssetIds }
        .forEach { asset ->
            val closed = DocumentEmbeddedAssets.update({
                (DocumentEmbeddedAssets.documentId eq documentId) and
                    (DocumentEmbeddedAssets.assetId eq asset.assetId) and
                    DocumentEmbeddedAssets.lastRevision.isNull()
            }) {
                it[lastRevision] = currentRevision
            }
            require(closed == 1) { "文档内嵌资产活动区间损坏: ${asset.assetId}" }
        }
    nextAssets.forEachIndexed { position, asset ->
        if (asset.assetId !in unchangedAssetIds) {
            insertAssetInterval(documentId, asset, position, nextRevision)
        }
    }
}

private fun insertAssetInterval(
    documentId: String,
    asset: EmbeddedAsset,
    position: Int,
    firstRevision: Long,
) {
    DocumentEmbeddedAssets.insert {
        it[DocumentEmbeddedAssets.documentId] = documentId
        it[assetId] = asset.assetId
        it[attachmentPath] = asset.attachment.path
        it[attachmentName] = asset.attachment.name
        it[attachmentContentType] = asset.attachment.contentType
        it[attachmentSize] = asset.attachment.size
        it[thumbnailPath] = asset.thumbnail?.path
        it[thumbnailName] = asset.thumbnail?.name
        it[thumbnailContentType] = asset.thumbnail?.contentType
        it[thumbnailSize] = asset.thumbnail?.size
        it[width] = asset.width
        it[height] = asset.height
        it[DocumentEmbeddedAssets.position] = position
        it[DocumentEmbeddedAssets.firstRevision] = firstRevision
        it[lastRevision] = null
    }
}
