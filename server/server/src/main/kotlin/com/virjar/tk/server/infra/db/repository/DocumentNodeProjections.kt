package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.infra.db.DocumentNodes
import org.jetbrains.exposed.sql.Expression

/**
 * 显式的文档节点投影。树导航绝不能获取可能高达 1 MiB 的
 * Markdown 正文；只有对单个具体文档的操作可以使用内容投影。
 */
internal val DOCUMENT_NODE_SUMMARY_PROJECTION: List<Expression<*>> = listOf(
    DocumentNodes.nodeId,
    DocumentNodes.spaceId,
    DocumentNodes.parentId,
    DocumentNodes.name,
    DocumentNodes.excerpt,
    DocumentNodes.revision,
    DocumentNodes.createdBy,
    DocumentNodes.createdAt,
    DocumentNodes.updatedBy,
    DocumentNodes.updatedAt,
)

/** 历史读取的活跃身份/空间探测；刻意排除内容与路径字段。 */
internal val DOCUMENT_NODE_IDENTITY_PROJECTION: List<Expression<*>> = listOf(
    DocumentNodes.nodeId,
    DocumentNodes.spaceId,
)

internal val DOCUMENT_NODE_CONTENT_PROJECTION: List<Expression<*>> = listOf(
    DocumentNodes.nodeId,
    DocumentNodes.spaceId,
    DocumentNodes.parentId,
    DocumentNodes.name,
    DocumentNodes.markdown,
    DocumentNodes.revision,
    DocumentNodes.createdBy,
    DocumentNodes.createdAt,
    DocumentNodes.updatedBy,
    DocumentNodes.updatedAt,
)

internal val DOCUMENT_NODE_DELETE_PROJECTION: List<Expression<*>> = listOf(
    DocumentNodes.nodeId,
    DocumentNodes.spaceId,
    DocumentNodes.revision,
)
