package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import com.virjar.tk.outcome
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.rpc.gen.DocumentRpcProxy

/** 协作文档 SDK。正文按需读取，列表与历史默认只传输摘要。 */
class DocumentRepository(rpcClient: RpcInvoker) {
    private val rpc = DocumentRpcProxy(rpcClient)

    suspend fun list(scopeType: Int, scopeId: String): Outcome<List<DocumentSummary>> =
        outcome { rpc.list(scopeType, scopeId) }

    suspend fun get(scopeType: Int, scopeId: String, documentId: String): Outcome<Document> =
        outcome { rpc.get(scopeType, scopeId, documentId) }

    suspend fun create(scopeType: Int, scopeId: String, title: String, markdown: String): Outcome<Document> =
        outcome { rpc.create(scopeType, scopeId, title, markdown) }

    suspend fun update(
        scopeType: Int,
        scopeId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Outcome<Document> = outcome {
        rpc.update(scopeType, scopeId, documentId, title, markdown, expectedRevision)
    }

    suspend fun listRevisions(
        scopeType: Int,
        scopeId: String,
        documentId: String,
    ): Outcome<List<DocumentRevisionSummary>> = outcome { rpc.listRevisions(scopeType, scopeId, documentId) }

    suspend fun getRevision(
        scopeType: Int,
        scopeId: String,
        documentId: String,
        revision: Long,
    ): Outcome<DocumentRevision> = outcome { rpc.getRevision(scopeType, scopeId, documentId, revision) }

    suspend fun delete(
        scopeType: Int,
        scopeId: String,
        documentId: String,
        expectedRevision: Long,
    ): Outcome<Unit> = outcome { rpc.delete(scopeType, scopeId, documentId, expectedRevision) }
}
