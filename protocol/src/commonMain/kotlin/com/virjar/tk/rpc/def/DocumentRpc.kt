package com.virjar.tk.rpc.def

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.rpc.RpcService

/** 企业文档空间 RPC。协议版本 5 起，文档不再以群聊作为根作用域。 */
@RpcService("document")
interface DocumentRpc {
    suspend fun listSpaces(): List<DocumentSpace>
    suspend fun createSpace(name: String, description: String?): DocumentSpace
    suspend fun updateSpace(spaceId: String, name: String, description: String?): DocumentSpace
    suspend fun archiveSpace(spaceId: String)
    suspend fun listGrants(spaceId: String): List<DocumentSpaceGrant>
    suspend fun upsertGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): DocumentSpaceGrant
    suspend fun removeGrant(spaceId: String, principalType: Int, principalId: String)
    suspend fun listNodes(spaceId: String, parentId: String?): List<DocumentNode>
    suspend fun createFolder(spaceId: String, parentId: String?, name: String): DocumentNode
    suspend fun createDocument(spaceId: String, parentId: String?, title: String, markdown: String): Document
    suspend fun getDocument(spaceId: String, documentId: String): Document
    suspend fun updateDocument(
        spaceId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Document
    suspend fun moveNode(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
    ): DocumentNode
    suspend fun deleteNode(spaceId: String, nodeId: String, expectedRevision: Long)
    suspend fun listRevisions(spaceId: String, documentId: String): List<DocumentRevisionSummary>
    suspend fun getRevision(spaceId: String, documentId: String, revision: Long): DocumentRevision
}
