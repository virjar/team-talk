package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.protocol.model.DocumentMoveResult

sealed interface DocumentMoveCommandSubmission {
    val command: PendingDocumentMoveCommand

    data class Acknowledged(
        override val command: PendingDocumentMoveCommand,
        /** 为 null 表示命令已提交，但没有可发布的当前本地投影。 */
        val projection: DocumentMoveResult?,
    ) : DocumentMoveCommandSubmission

    data class Pending(
        override val command: PendingDocumentMoveCommand,
    ) : DocumentMoveCommandSubmission
}

enum class DocumentMoveCommandCompletionStatus {
    ACKNOWLEDGED,
    REJECTED,
}

/** 有界的恢复后提示；持久命令本身已包含受影响的双方分支。 */
data class DocumentMoveCommandCompletion(
    val command: PendingDocumentMoveCommand,
    val status: DocumentMoveCommandCompletionStatus,
    val projection: DocumentMoveResult? = null,
)
