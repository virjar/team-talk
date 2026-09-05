package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.ProtoCodec

/**
 * 持久化文档投影的有界干净正文通道。
 *
 * [LocalDocumentProjectionStore] 仍然是锁与事务 owner。因此该组件的每个方法都在持有该 owner 的
 * 状态锁时被调用；如此声明的方法还要求 owner 的 SQL 事务。正文行、有序祖先与 LRU 元数据刻意
 * 共享此存储边界，而跨投影提交在 owner 中保持原子。
 */
internal class LocalDocumentBodyProjectionStore(
    private val queries: AppDatabaseQueries,
) {
    /** 调用方持有文档投影状态锁。 */
    fun getAndTouchLocked(spaceId: String, documentId: String): Document? {
        val document = loadLocked(spaceId, documentId) ?: return null
        queries.touchDocumentBody(nextOrdinalLocked(), spaceId, documentId)
        return document
    }

    /**
     * 调用方持有文档投影状态锁；与 [getAndTouchLocked] 不同，这不改变 LRU。
     */
    fun loadLocked(spaceId: String, documentId: String): Document? {
        val row = queries.selectDocumentBody(spaceId, documentId).executeAsOneOrNull() ?: return null
        return Document(
            documentId = row.document_id,
            spaceId = row.space_id,
            parentId = row.parent_id,
            title = row.title,
            markdown = row.markdown,
            revision = row.revision,
            createdBy = row.created_by,
            createdAt = row.created_at,
            updatedBy = row.updated_by,
            updatedAt = row.updated_at,
            ancestorIds = queries.selectDocumentBodyAncestors(spaceId, documentId).executeAsList(),
            assets = MarkdownAssetPolicy.canonicalize(
                row.markdown,
                ProtoCodec.decodeList(EmbeddedAsset, row.asset_manifest),
            ),
        )
    }

    /** 调用方持有文档投影状态锁与其 SQL 事务。 */
    fun persistLocked(document: Document, bodyBytes: Long) {
        queries.deleteDocumentBodyAncestors(document.spaceId, document.documentId)
        queries.upsertDocumentBody(
            document.spaceId,
            document.documentId,
            document.parentId,
            document.title,
            document.markdown,
            ProtoCodec.encodeList(document.assets),
            document.revision,
            document.createdBy,
            document.createdAt,
            document.updatedBy,
            document.updatedAt,
            bodyBytes,
            nextOrdinalLocked(),
        )
        document.ancestorIds.forEachIndexed { index, ancestorId ->
            queries.insertDocumentBodyAncestor(
                document.spaceId,
                document.documentId,
                index.toLong(),
                ancestorId,
            )
        }
    }

    /** 调用方持有文档投影状态锁。 */
    fun descendantIdsLocked(spaceId: String, ancestorId: String): List<String> =
        queries.selectDocumentBodyIdsByAncestor(spaceId, ancestorId).executeAsList()

    /** 调用方持有文档投影状态锁与其 SQL 事务。 */
    fun deleteLocked(spaceId: String, documentId: String) {
        queries.deleteDocumentBodyAncestors(spaceId, documentId)
        queries.deleteDocumentBody(spaceId, documentId)
    }

    /** 调用方持有文档投影状态锁与其 SQL 事务。 */
    fun deleteSpaceLocked(spaceId: String) {
        queries.deleteDocumentBodyAncestorsBySpace(spaceId)
        queries.deleteDocumentBodiesBySpace(spaceId)
    }

    /** 调用方持有文档投影状态锁与其 SQL 事务。 */
    fun deleteUnknownSpacesLocked() {
        queries.deleteDocumentBodyAncestorsForUnknownSpaces()
        queries.deleteDocumentBodiesForUnknownSpaces()
    }

    /** 调用方持有文档投影状态锁与其 SQL 事务。 */
    fun pruneLocked() {
        var count = queries.countDocumentBodies().executeAsOne()
        var bytes = queries.sumDocumentBodyBytes().executeAsOne()
        while (
            count > LocalDocumentProjectionLimits.MAX_BODIES.toLong() ||
            bytes > LocalDocumentProjectionLimits.MAX_BODY_BYTES
        ) {
            val oldest = checkNotNull(queries.selectOldestDocumentBodyKey().executeAsOneOrNull()) {
                "document body capacity metadata is inconsistent"
            }
            deleteLocked(oldest.space_id, oldest.document_id)
            count -= 1L
            bytes -= oldest.body_bytes
        }
    }

    /** 调用方持有文档投影状态锁。 */
    private fun nextOrdinalLocked(): Long {
        val current = queries.selectMaxDocumentBodyCacheOrdinal().executeAsOne()
        check(current < Long.MAX_VALUE) { "document body cache ordinal exhausted" }
        return current + 1L
    }
}
