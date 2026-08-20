package com.virjar.tk.rpc.def

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentHomeItem
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.rpc.RpcService
import com.virjar.tk.rpc.RpcMethod

/** 企业文档空间 RPC：空间、授权、目录、修订和首页索引。 */
@RpcService("document")
interface DocumentRpc {
    @RpcMethod(1)
    suspend fun listSpaces(): List<DocumentSpace>
    @RpcMethod(2)
    suspend fun createSpace(name: String, description: String?): DocumentSpace
    @RpcMethod(3)
    suspend fun updateSpace(spaceId: String, name: String, description: String?): DocumentSpace
    @RpcMethod(4)
    suspend fun archiveSpace(spaceId: String)
    @RpcMethod(5)
    suspend fun listGrants(spaceId: String): List<DocumentSpaceGrant>
    @RpcMethod(6)
    suspend fun upsertGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): DocumentSpaceGrant
    @RpcMethod(7)
    suspend fun removeGrant(spaceId: String, principalType: Int, principalId: String)
    @RpcMethod(8)
    suspend fun listNodes(spaceId: String, parentId: String?): List<DocumentNode>
    @RpcMethod(9)
    suspend fun createFolder(spaceId: String, parentId: String?, name: String): DocumentNode
    @RpcMethod(10)
    suspend fun createDocument(spaceId: String, parentId: String?, title: String, markdown: String): Document
    @RpcMethod(11)
    suspend fun getDocument(spaceId: String, documentId: String): Document
    @RpcMethod(12)
    suspend fun updateDocument(
        spaceId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Document
    @RpcMethod(13)
    suspend fun moveNode(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
    ): DocumentNode
    @RpcMethod(14)
    suspend fun deleteNode(spaceId: String, nodeId: String, expectedRevision: Long)
    @RpcMethod(15)
    suspend fun listRevisions(spaceId: String, documentId: String): List<DocumentRevisionSummary>
    @RpcMethod(16)
    suspend fun getRevision(spaceId: String, documentId: String, revision: Long): DocumentRevision

    @RpcMethod(17)
    suspend fun listRecentDocuments(limit: Int): List<DocumentHomeItem>

    @RpcMethod(18)
    suspend fun listRecentlyCreatedDocuments(limit: Int): List<DocumentHomeItem>
}
