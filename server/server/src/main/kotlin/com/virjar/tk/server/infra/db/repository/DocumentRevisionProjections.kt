package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.infra.db.DocumentContentRevisions
import org.jetbrains.exposed.sql.Expression

/** 修订列表行刻意排除可能很大的不可变 Markdown 快照。 */
internal val DOCUMENT_REVISION_SUMMARY_PROJECTION: List<Expression<*>> = listOf(
    DocumentContentRevisions.documentId,
    DocumentContentRevisions.revision,
    DocumentContentRevisions.title,
    DocumentContentRevisions.contentLength,
    DocumentContentRevisions.editedBy,
    DocumentContentRevisions.editedAt,
)
