package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.rpc.RpcStatusException
import com.virjar.tk.protocol.rpc.gen.DocumentRpcProxy

/** 让一次已确认的 move/rename 在干净的本地文档投影中变得可见。 */
internal class DocumentMoveProjectionConverger(
    private val rpc: DocumentRpcProxy,
    private val localCache: LocalCache,
    private val projectionMaintenance: DocumentProjectionMaintenance,
) {
    suspend fun converge(
        pending: PendingDocumentMoveCommand,
        remote: DocumentMoveResult?,
    ): DocumentMoveResult? {
        val stagedRemote = remote?.let { move ->
            projectionMaintenance.runPostCommit(
                "Failed to stage a moved document projection",
                fallback = null,
            ) {
                val lease = localCache.beginDocumentBodyMutationSnapshot(
                    pending.spaceId,
                    pending.nodeId,
                )
                try {
                    move.takeIf { localCache.applyDocumentMove(lease, move) }
                } finally {
                    localCache.abandonProjectionSnapshot(lease)
                }
            }
        }
        if (stagedRemote != null) return stagedRemote

        val minimumRevision = remote?.node?.revision ?: pending.expectedRevision + 1L
        return cachedCurrentProjection(pending, minimumRevision)
            ?: convergeProjectionlessAcknowledgement(pending)
    }

    fun invalidateFailure(
        failure: Throwable,
        pending: PendingDocumentMoveCommand,
    ) {
        failure.documentRpcStatusOrNull()?.let { status ->
            invalidateProjection(status, pending)
        }
    }

    private fun cachedCurrentProjection(
        pending: PendingDocumentMoveCommand,
        minimumRevision: Long,
    ): DocumentMoveResult? {
        localCache.getDocumentBody(pending.spaceId, pending.nodeId)
            ?.takeIf { it.revision >= minimumRevision }
            ?.let { document ->
                val node = localCache.getDocumentNodes(document.spaceId, document.parentId)
                    .firstOrNull { it.nodeId == document.documentId }
                    ?: document.asMoveNode(hasChildren = false)
                return DocumentMoveResult(node, document.ancestorIds)
            }
        val spine = localCache.getDocumentPathSpine(pending.spaceId, pending.nodeId)
            ?.takeIf {
                it.nodes.lastOrNull()?.revision?.let { revision -> revision >= minimumRevision } == true
            }
            ?: return null
        return DocumentMoveResult(
            node = spine.nodes.last(),
            ancestorIds = spine.nodes.dropLast(1).map(DocumentNode::nodeId),
        )
    }

    /**
     * 精确回放可以在不重演其历史投影的情况下确认一条命令。保留
     * 持久命令，直到新的 body 或路径脊柱让已提交的结果可见。
     */
    private suspend fun convergeProjectionlessAcknowledgement(
        pending: PendingDocumentMoveCommand,
    ): DocumentMoveResult? = if (
        localCache.getDocumentBody(pending.spaceId, pending.nodeId) != null
    ) {
        convergeDocumentBody(pending)
    } else {
        convergePathSpine(pending)
    }

    private suspend fun convergeDocumentBody(
        pending: PendingDocumentMoveCommand,
    ): DocumentMoveResult? {
        val lease = localCache.beginDocumentBodyMutationSnapshot(pending.spaceId, pending.nodeId)
        return try {
            val document = loadOrPurge(pending) {
                rpc.getDocument(pending.spaceId, pending.nodeId)
            } ?: return null
            check(document.spaceId == pending.spaceId && document.documentId == pending.nodeId) {
                "document response escaped its requested identity"
            }
            check(localCache.applyDocumentBodyMutation(lease, document)) {
                "document response was fenced by a newer projection"
            }
            val converged = checkNotNull(
                localCache.getDocumentBody(pending.spaceId, pending.nodeId),
            ) { "document body was not retained by the local projection" }
            val node = localCache.getDocumentNodes(converged.spaceId, converged.parentId)
                .firstOrNull { it.nodeId == converged.documentId }
                ?: converged.asMoveNode(hasChildren = false)
            DocumentMoveResult(node, converged.ancestorIds)
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    private suspend fun convergePathSpine(
        pending: PendingDocumentMoveCommand,
    ): DocumentMoveResult? {
        val lease = localCache.beginDocumentPathSpineSnapshot(pending.spaceId, pending.nodeId)
        return try {
            val spine = loadOrPurge(pending) {
                rpc.getNodePathSpine(pending.spaceId, pending.nodeId)
            } ?: return null
            check(spine.spaceId == pending.spaceId && spine.targetNodeId == pending.nodeId) {
                "document path spine response escaped its requested identity"
            }
            check(
                localCache.applyDocumentPathSpineSnapshot(
                    lease,
                    pending.spaceId,
                    pending.nodeId,
                    spine,
                ),
            ) { "document path spine response was fenced by a newer projection" }
            val converged = checkNotNull(
                localCache.getDocumentPathSpine(pending.spaceId, pending.nodeId),
            ) { "document path spine was not retained by the local projection" }
            DocumentMoveResult(
                node = converged.nodes.last(),
                ancestorIds = converged.nodes.dropLast(1).map(DocumentNode::nodeId),
            )
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    private suspend fun <T> loadOrPurge(
        pending: PendingDocumentMoveCommand,
        load: suspend () -> T,
    ): T? = try {
        load()
    } catch (failure: Exception) {
        val status = failure.documentRpcStatusOrNull()
        if (status == 403 || status == 404) {
            invalidateProjection(status, pending)
            null
        } else {
            throw failure
        }
    }

    private fun invalidateProjection(
        status: Int,
        pending: PendingDocumentMoveCommand,
    ) {
        when (status) {
            403 -> projectionMaintenance.runPostCommit(
                "Failed to purge a forbidden document space",
            ) {
                localCache.purgeDocumentSpace(pending.spaceId)
            }
            404 -> projectionMaintenance.runPostCommit(
                "Failed to purge a missing document projection",
            ) {
                localCache.purgeDocument(pending.spaceId, pending.nodeId)
            }
        }
    }
}

private fun Throwable.documentRpcStatusOrNull(): Int? = when (this) {
    is RpcStatusException -> status
    is AppError.Business -> code
    else -> null
}

private fun Document.asMoveNode(hasChildren: Boolean): DocumentNode = DocumentNode(
    nodeId = documentId,
    spaceId = spaceId,
    parentId = parentId,
    hasChildren = hasChildren,
    name = title,
    excerpt = DocumentPolicy.markdownExcerpt(markdown),
    revision = revision,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
)
