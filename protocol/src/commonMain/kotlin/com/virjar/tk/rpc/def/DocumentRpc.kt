package com.virjar.tk.rpc.def

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import com.virjar.tk.rpc.RpcService

/** 协作文档 RPC。新方法只能追加，不能重排。 */
@RpcService("document")
interface DocumentRpc {
    suspend fun list(scopeType: Int, scopeId: String): List<DocumentSummary>
    suspend fun get(scopeType: Int, scopeId: String, documentId: String): Document
    suspend fun create(scopeType: Int, scopeId: String, title: String, markdown: String): Document
    suspend fun update(
        scopeType: Int,
        scopeId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Document
    suspend fun listRevisions(scopeType: Int, scopeId: String, documentId: String): List<DocumentRevisionSummary>
    suspend fun getRevision(scopeType: Int, scopeId: String, documentId: String, revision: Long): DocumentRevision
    suspend fun delete(scopeType: Int, scopeId: String, documentId: String, expectedRevision: Long)
}
