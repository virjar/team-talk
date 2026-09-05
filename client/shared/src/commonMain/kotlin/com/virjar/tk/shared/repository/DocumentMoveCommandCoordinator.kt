package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.DocumentRpcProxy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 持有持久 move/rename 的准入、传输、重试、退场与完成发布。 */
internal class DocumentMoveCommandCoordinator(
    private val rpc: DocumentRpcProxy,
    private val localCache: LocalCache,
    private val projectionConverger: DocumentMoveProjectionConverger,
    private val requestMutex: Mutex,
    private val newOperationId: () -> String,
    private val nowMillis: () -> Long,
    private val onPendingCommandCommitted: () -> Unit,
    private val onCommandCompleted: (DocumentMoveCommandCompletion) -> Unit,
) {
    fun pendingCommands(): List<PendingDocumentMoveCommand> =
        localCache.getPendingDocumentMoveCommands()

    suspend fun submit(
        spaceId: String,
        nodeId: String,
        oldParentId: String?,
        targetParentId: String?,
        name: String,
        expectedRevision: Long,
    ): Outcome<DocumentMoveCommandSubmission> = outcome {
        val pending = localCache.preparePendingDocumentMoveCommand(
            PendingDocumentMoveCommand.create(
                operationId = newOperationId(),
                spaceId = spaceId,
                nodeId = nodeId,
                oldParentId = oldParentId,
                targetParentId = targetParentId,
                name = name,
                expectedRevision = expectedRevision,
                issuedAt = nowMillis(),
            ),
        )
        var recoveryWoken = false
        fun wakeRecovery() {
            if (recoveryWoken) return
            recoveryWoken = true
            onPendingCommandCommitted()
        }
        try {
            requestMutex.withLock {
                wakeRecovery()
                try {
                    DocumentMoveCommandSubmission.Acknowledged(
                        pending,
                        send(pending),
                    )
                } catch (failure: Exception) {
                    if (failure.isRetryableReliableCommandFailure()) {
                        DocumentMoveCommandSubmission.Pending(pending)
                    } else {
                        throw failure
                    }
                }
            }
        } finally {
            // 准入先于这把互斥锁；等待期间被取消时仍必须唤醒恢复。
            wakeRecovery()
        }
    }

    suspend fun retryPending(): Outcome<Unit> = retryPendingMirrors(
        localCache.getPendingDocumentMoveCommands(),
    ) { pending ->
        outcome {
            requestMutex.withLock {
                if (localCache.getPendingDocumentMoveCommands().none {
                        it.operationId == pending.operationId
                    }
                ) return@withLock
                send(pending, publishCompletion = true)
            }
        }
    }

    private suspend fun send(
        pending: PendingDocumentMoveCommand,
        publishCompletion: Boolean = false,
    ): DocumentMoveResult? {
        val acknowledgement = try {
            rpc.moveNode(
                pending.spaceId,
                pending.nodeId,
                pending.targetParentId,
                pending.name,
                pending.expectedRevision,
                pending.operationId,
                pending.issuedAt,
            )
        } catch (failure: Exception) {
            projectionConverger.invalidateFailure(failure, pending)
            if (failure.isDefinitiveReliableCommandRejection()) {
                val cleared = localCache.clearPendingDocumentMoveCommand(pending.operationId)
                if (cleared && publishCompletion) {
                    onCommandCompleted(
                        DocumentMoveCommandCompletion(
                            pending,
                            DocumentMoveCommandCompletionStatus.REJECTED,
                        ),
                    )
                }
            }
            throw failure
        }
        check(acknowledgement.operationId == pending.operationId) {
            "moveNode acknowledgement escaped its requested operation"
        }
        val remote = acknowledgement.result
        if (remote != null) {
            check(
                remote.node.spaceId == pending.spaceId &&
                    remote.node.nodeId == pending.nodeId &&
                    remote.node.parentId == pending.targetParentId,
            ) { "moveNode response escaped its requested identity" }
        }
        val projection = projectionConverger.converge(pending, remote)
        val cleared = localCache.clearPendingDocumentMoveCommand(pending.operationId)
        if (cleared && publishCompletion) {
            onCommandCompleted(
                DocumentMoveCommandCompletion(
                    pending,
                    DocumentMoveCommandCompletionStatus.ACKNOWLEDGED,
                    projection,
                ),
            )
        }
        return projection
    }
}
