package com.virjar.tk.domain.document

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary

/** 文档持久化端口。修订快照只追加；更新和删除必须使用 revision 乐观锁。 */
interface DocumentRepository {
    fun list(scopeType: Int, scopeId: String): List<DocumentSummary>
    fun find(documentId: String): Document?
    fun create(document: Document, initialRevision: DocumentRevision): Document
    fun update(
        documentId: String,
        expectedRevision: Long,
        title: String,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
    ): Document
    fun delete(documentId: String, expectedRevision: Long, actorUid: String, updatedAt: Long)
    fun listRevisions(documentId: String): List<DocumentRevisionSummary>
    fun findRevision(documentId: String, revision: Long): DocumentRevision?
}
