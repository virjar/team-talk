package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 文档讨论独立于正文修订；所有方法重新核对空间及文档的当前读取权限。 */
@SinceProtocol(2)
@RpcService("documentComment")
interface DocumentCommentRpc {
    @RpcMethod(1)
    suspend fun list(spaceId: String, documentId: String, beforeSequence: Long, limit: Int): DocumentCommentPage

    /** 客户端生成 canonical UUID；同一次创建的重试保持 commentId、正文和回复目标不变。 */
    @RpcMethod(2)
    suspend fun create(spaceId: String, documentId: String, commentId: String, replyToId: String?, body: String): DocumentComment

    /** 仅作者编辑，按评论自身 revision CAS；精确的相邻修订重试不再次更新。 */
    @RpcMethod(3)
    suspend fun update(spaceId: String, documentId: String, commentId: String, body: String, expectedRevision: Long): DocumentComment

    /** 作者或空间管理员删除，保留稳定身份及回复上下文。 */
    @RpcMethod(4)
    suspend fun delete(spaceId: String, documentId: String, commentId: String, expectedRevision: Long): DocumentComment
}
