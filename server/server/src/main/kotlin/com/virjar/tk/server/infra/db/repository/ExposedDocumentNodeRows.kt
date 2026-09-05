package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.infra.db.DocumentNodes
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and

/** 文档读取与聚合写入共用的小型、投影显式的行网关。 */
internal class ExposedDocumentNodeRows {
    fun findActiveSummary(spaceId: String, nodeId: String): ResultRow? = DocumentNodes
        .select(DOCUMENT_NODE_SUMMARY_PROJECTION)
        .where {
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }
        .singleOrNull()

    fun requireActiveSummary(spaceId: String, nodeId: String): ResultRow =
        findActiveSummary(spaceId, nodeId) ?: throw DocumentNotFoundException("文档节点不存在")

    fun findActiveContent(spaceId: String, nodeId: String): ResultRow? = DocumentNodes
        .select(DOCUMENT_NODE_CONTENT_PROJECTION)
        .where {
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }
        .singleOrNull()

    fun requireActiveContent(spaceId: String, nodeId: String, forUpdate: Boolean = false): ResultRow {
        val query = DocumentNodes.select(DOCUMENT_NODE_CONTENT_PROJECTION).where {
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw DocumentNotFoundException("文档节点不存在")
    }

    fun requireActiveDeleteProjection(spaceId: String, nodeId: String, forUpdate: Boolean): ResultRow {
        val query = DocumentNodes.select(DOCUMENT_NODE_DELETE_PROJECTION).where {
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw DocumentNotFoundException("文档节点不存在")
    }
}
