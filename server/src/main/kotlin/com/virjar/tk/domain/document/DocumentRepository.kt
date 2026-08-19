package com.virjar.tk.domain.document

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant

/** 文档空间持久化端口。正文修订只追加，节点变更使用 revision 乐观锁。 */
interface DocumentRepository {
    fun listSpaces(): List<DocumentSpace>
    fun findSpace(spaceId: String): DocumentSpace?
    fun createSpace(space: DocumentSpace): DocumentSpace
    fun updateSpace(spaceId: String, name: String, description: String?, updatedAt: Long): DocumentSpace
    fun archiveSpace(spaceId: String, updatedAt: Long)

    fun listGrants(spaceId: String): List<DocumentSpaceGrant>
    fun upsertGrant(grant: DocumentSpaceGrant)
    fun removeGrant(spaceId: String, principalType: Int, principalId: String)

    fun listNodes(spaceId: String, parentId: String?): List<DocumentNode>
    fun findNode(nodeId: String): DocumentNode?
    fun findDocument(documentId: String): Document?
    fun createFolder(node: DocumentNode): DocumentNode
    fun createDocument(document: Document, initialRevision: DocumentRevision): Document
    fun updateDocument(
        documentId: String,
        expectedRevision: Long,
        title: String,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
    ): Document
    fun moveNode(
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentNode
    fun deleteNode(nodeId: String, expectedRevision: Long, actorUid: String, updatedAt: Long)

    fun listRevisions(documentId: String): List<DocumentRevisionSummary>
    fun findRevision(documentId: String, revision: Long): DocumentRevision?
}
