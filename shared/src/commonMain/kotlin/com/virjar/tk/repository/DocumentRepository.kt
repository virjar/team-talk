package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.outcome
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.rpc.gen.DocumentRpcProxy

/** 企业文档空间 SDK。目录和历史只传摘要，Markdown 正文按需读取。 */
class DocumentRepository(rpcClient: RpcInvoker) {
    private val rpc = DocumentRpcProxy(rpcClient)

    suspend fun listSpaces(): Outcome<List<DocumentSpace>> = outcome { rpc.listSpaces() }
    suspend fun createSpace(name: String, description: String?): Outcome<DocumentSpace> =
        outcome { rpc.createSpace(name, description) }
    suspend fun updateSpace(spaceId: String, name: String, description: String?): Outcome<DocumentSpace> =
        outcome { rpc.updateSpace(spaceId, name, description) }
    suspend fun archiveSpace(spaceId: String): Outcome<Unit> = outcome { rpc.archiveSpace(spaceId) }

    suspend fun listGrants(spaceId: String): Outcome<List<DocumentSpaceGrant>> = outcome { rpc.listGrants(spaceId) }
    suspend fun upsertGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): Outcome<DocumentSpaceGrant> = outcome {
        rpc.upsertGrant(spaceId, principalType, principalId, role, includeDescendants)
    }
    suspend fun removeGrant(spaceId: String, principalType: Int, principalId: String): Outcome<Unit> =
        outcome { rpc.removeGrant(spaceId, principalType, principalId) }

    suspend fun listNodes(spaceId: String, parentId: String?): Outcome<List<DocumentNode>> =
        outcome { rpc.listNodes(spaceId, parentId) }
    suspend fun createFolder(spaceId: String, parentId: String?, name: String): Outcome<DocumentNode> =
        outcome { rpc.createFolder(spaceId, parentId, name) }
    suspend fun createDocument(spaceId: String, parentId: String?, title: String, markdown: String): Outcome<Document> =
        outcome { rpc.createDocument(spaceId, parentId, title, markdown) }
    suspend fun getDocument(spaceId: String, documentId: String): Outcome<Document> =
        outcome { rpc.getDocument(spaceId, documentId) }
    suspend fun updateDocument(
        spaceId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Outcome<Document> = outcome { rpc.updateDocument(spaceId, documentId, title, markdown, expectedRevision) }
    suspend fun moveNode(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
    ): Outcome<DocumentNode> = outcome { rpc.moveNode(spaceId, nodeId, parentId, name, expectedRevision) }
    suspend fun deleteNode(spaceId: String, nodeId: String, expectedRevision: Long): Outcome<Unit> =
        outcome { rpc.deleteNode(spaceId, nodeId, expectedRevision) }

    suspend fun listRevisions(spaceId: String, documentId: String): Outcome<List<DocumentRevisionSummary>> =
        outcome { rpc.listRevisions(spaceId, documentId) }
    suspend fun getRevision(spaceId: String, documentId: String, revision: Long): Outcome<DocumentRevision> =
        outcome { rpc.getRevision(spaceId, documentId, revision) }
}
