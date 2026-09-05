package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentCreateResult
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentCustodyTransferResult
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveCommandResult
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionPage
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.protocol.model.DocumentSpacePageRequest
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.DocumentSpaceGrantPage
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 企业文档空间 RPC：空间、授权、文档树、修订和首页索引。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("document")
interface DocumentRpc {
    @RpcMethod(1)
    suspend fun listSpaces(request: DocumentSpacePageRequest): DocumentSpacePage
    @RpcMethod(2)
    suspend fun createSpace(spaceId: String, name: String, description: String?): DocumentSpaceCreateResult
    @RpcMethod(3)
    suspend fun updateSpace(spaceId: String, name: String, description: String?): DocumentSpace
    /** [operationId] 是一个 canonical UUID，该归档命令的每次重试都复用同一值。 */
    @RpcMethod(4)
    suspend fun archiveSpace(spaceId: String, operationId: String)
    @RpcMethod(5)
    suspend fun listGrants(spaceId: String): DocumentSpaceGrantPage
    @RpcMethod(6)
    /** [operationId]/[issuedAt] 一起冻结，直到该 ACL 意图被确认。 */
    suspend fun upsertGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentPolicyMutationResult
    @RpcMethod(7)
    /** [operationId]/[issuedAt] 一起冻结，直到该 ACL 意图被确认。 */
    suspend fun removeGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentPolicyMutationResult
    @RpcMethod(8)
    suspend fun listNodes(spaceId: String, parentId: String?): List<DocumentNode>
    @RpcMethod(9)
    suspend fun createDocument(
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        content: DocumentContent,
    ): DocumentCreateResult
    @RpcMethod(10)
    suspend fun getDocument(spaceId: String, documentId: String): Document
    @RpcMethod(11)
    /** 仅保存内容。已有节点名只能通过可靠的 [moveNode] 修改。 */
    suspend fun updateDocument(
        spaceId: String,
        documentId: String,
        content: DocumentContent,
        expectedRevision: Long,
    ): Document
    @RpcMethod(12)
    /** [operationId]/[issuedAt] 在本次移动或重命名的每次重试之间保持冻结。 */
    suspend fun moveNode(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentMoveCommandResult
    /** [operationId] 跨重试保持稳定；[expectedRevision] 仅在首次成功时校验。 */
    @RpcMethod(13)
    suspend fun deleteNode(
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    )
    @RpcMethod(14)
    suspend fun listRevisions(
        spaceId: String,
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ): DocumentRevisionPage
    @RpcMethod(15)
    suspend fun getRevision(spaceId: String, documentId: String, revision: Long): DocumentRevision

    @RpcMethod(16)
    suspend fun listRecentDocuments(limit: Int): List<DocumentHomeItem>

    @RpcMethod(17)
    suspend fun listRecentlyCreatedDocuments(limit: Int): List<DocumentHomeItem>

    /**
     * 业务所有权转移与不可变的创建溯源相互分离。
     *
     * [operationId] 是一个 canonical UUID，每次重试都复用。[expectedCustodyRevision]
     * 防止过期的离职流程或管理员计划覆盖较新的交接。
     */
    @RpcMethod(18)
    suspend fun transferSpaceCustody(
        spaceId: String,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        expectedCustodyRevision: Long,
        operationId: String,
    ): DocumentCustodyTransferResult

    /** 在一个有界响应中返回完整的活跃根到目标路径。 */
    @RpcMethod(19)
    suspend fun getNodePathSpine(spaceId: String, nodeId: String): DocumentPathSpine
}
