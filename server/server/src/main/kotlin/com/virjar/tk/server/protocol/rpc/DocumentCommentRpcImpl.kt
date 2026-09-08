package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.rpc.gen.DocumentCommentRpcStub
import com.virjar.tk.server.domain.document.DocumentCommentService

class DocumentCommentRpcImpl(uid: String, private val service: DocumentCommentService) : DocumentCommentRpcStub(uid) {
    override suspend fun list(spaceId: String, documentId: String, beforeSequence: Long, limit: Int) = service.list(uid, spaceId, documentId, beforeSequence, limit)
    override suspend fun create(spaceId: String, documentId: String, commentId: String, replyToId: String?, body: String) = service.create(uid, spaceId, documentId, commentId, replyToId, body)
    override suspend fun update(spaceId: String, documentId: String, commentId: String, body: String, expectedRevision: Long) = service.update(uid, spaceId, documentId, commentId, body, expectedRevision)
    override suspend fun delete(spaceId: String, documentId: String, commentId: String, expectedRevision: Long) = service.delete(uid, spaceId, documentId, commentId, expectedRevision)
}
