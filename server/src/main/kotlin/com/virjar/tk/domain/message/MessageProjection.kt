package com.virjar.tk.domain.message

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Message
import java.util.concurrent.atomic.AtomicReference

enum class MessageOperationType(val code: Int) {
    CREATE(1),
    EDIT(2),
    REVOKE(3),
    ;

    companion object {
        fun fromCode(code: Int): MessageOperationType = values().firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown message operation code: $code")
    }
}

/**
 * Maximum recipient set captured while the chat lifecycle gate protects message acceptance.
 * Recovery intersects it with current active membership: later joiners never receive an old
 * operation, while a member removed before recovery never receives stale events after removal.
 */
data class MessageProjectionTarget(
    val chatType: Int,
    val recipientUids: List<String>,
) {
    init {
        require(chatType in 1..2) { "Message projection chat type must be personal or group" }
        require(recipientUids.isNotEmpty()) { "Message projection must capture at least one recipient" }
        require(recipientUids.all { it.isNotBlank() }) { "Message projection recipient uid must not be blank" }
    }

    fun canonical(): MessageProjectionTarget = copy(recipientUids = recipientUids.distinct().sorted())
}

/** Immutable, versioned operation stored in the RocksDB outbox. */
data class MessageProjectionOperation(
    val projectionKey: String,
    val operation: MessageOperationType,
    val revision: Long,
    val message: Message,
    val target: MessageProjectionTarget,
) {
    init {
        require(revision > 0L) { "Message projection revision must be positive" }
        require(message.chatId.isNotBlank() && message.serverSeq > 0L) {
            "Message projection requires a durable message identity"
        }
        require(target == target.canonical()) { "Message projection recipients must be canonical" }
        require(projectionKey == stableKey(message.chatId, message.serverSeq)) {
            "Message projection key does not match its immutable operation identity"
        }
    }

    companion object {
        /** Length-prefixing keeps arbitrary chat ids unambiguous without a process-random hash. */
        fun stableKey(chatId: String, serverSeq: Long): String =
            "message/v1/${chatId.length}:$chatId/$serverSeq"
    }
}

data class MessageProjectionRecipient(
    val uid: String,
    /** Null when EDIT/REVOKE does not target the conversation's current last message. */
    val conversation: Conversation?,
)

data class MessageProjectionApplyResult(
    val applied: Boolean,
    val recipients: List<MessageProjectionRecipient>,
)

/** PostgreSQL projection port; implementations must use the supplied outer UoW transaction. */
fun interface MessageProjectionRepository {
    fun apply(
        transaction: PgTransactionContext,
        operation: MessageProjectionOperation,
        lastMessagePreview: String?,
    ): MessageProjectionApplyResult
}

data class MessageProjectionFailure(
    val projectionKey: String,
    val detail: String,
)

/** Process readiness state. Durable operations remain in RocksDB; this is only the live gate. */
class MessageProjectionReadiness {
    private data class State(
        val generation: Long,
        val failure: MessageProjectionFailure?,
    )

    private val state = AtomicReference(State(generation = 0L, failure = null))

    fun block(projectionKey: String, error: Throwable) {
        state.updateAndGet { current ->
            State(
                generation = current.generation + 1L,
                failure = MessageProjectionFailure(
                    projectionKey = projectionKey,
                    detail = error.message ?: error::class.simpleName ?: "projection failed",
                ),
            )
        }
    }

    /**
     * Capture the failure generation before a global drain. A concurrent poison operation bumps
     * the generation, so a stale empty scan cannot accidentally clear its readiness failure.
     */
    fun generation(): Long = state.get().generation

    /** Called only after the global Rocks outbox has been observed empty twice. */
    fun markReadyIfUnchanged(expectedGeneration: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.generation != expectedGeneration) return false
            if (current.failure == null) return true
            if (state.compareAndSet(current, current.copy(failure = null))) return true
        }
    }

    fun currentFailure(): MessageProjectionFailure? = state.get().failure
}

enum class MessageProjectionStage {
    AFTER_LUCENE_BEFORE_POSTGRES,
    AFTER_POSTGRES_BEFORE_ROCKS_ACK,
}

/** Deterministic crash seam used only by isolation tests. */
fun interface MessageProjectionHooks {
    suspend fun hit(stage: MessageProjectionStage, operation: MessageProjectionOperation)

    object None : MessageProjectionHooks {
        override suspend fun hit(stage: MessageProjectionStage, operation: MessageProjectionOperation) = Unit
    }
}
