package com.virjar.tk.server.domain.message

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
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
 * 在聊天生命周期闸门保护消息接收期间捕获的最大接收者集合。恢复时把它与当前活跃成员
 * 求交集：后来的加入者绝不会收到旧操作，而在恢复前被移除的成员绝不会在移除后收到
 * 过期事件。
 */
data class MessageProjectionTarget(
    val chatType: Int,
    val recipientUids: List<String>,
) {
    init {
        require(chatType in 1..3) { "Message projection chat type must be personal, group or saved" }
        require(recipientUids.isNotEmpty()) { "Message projection must capture at least one recipient" }
        require(recipientUids.all { it.isNotBlank() }) { "Message projection recipient uid must not be blank" }
    }

    fun canonical(): MessageProjectionTarget = copy(recipientUids = recipientUids.distinct().sorted())
}

/** 存储在 RocksDB 可靠发件箱中的不可变、带版本操作。 */
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
        /** 长度前缀让任意聊天 id 保持无歧义，而无需进程随机哈希。 */
        fun stableKey(chatId: String, serverSeq: Long): String =
            "message/v1/${chatId.length}:$chatId/$serverSeq"
    }
}

data class MessageProjectionRecipient(
    val uid: String,
    /** 当 EDIT/REVOKE 不针对会话当前最后一条消息时为 null。 */
    val conversation: Conversation?,
)

data class MessageProjectionApplyResult(
    val applied: Boolean,
    val recipients: List<MessageProjectionRecipient>,
)

/** PostgreSQL 投影端口；实现必须使用提供的外层工作单元事务。 */
fun interface MessageProjectionRepository {
    fun apply(
        transaction: PgWriteTransactionContext,
        operation: MessageProjectionOperation,
        lastMessagePreview: String?,
    ): MessageProjectionApplyResult
}

data class MessageProjectionFailure(
    val projectionKey: String,
    val detail: String,
)

/** 进程就绪状态。持久化操作仍在 RocksDB 中；这只是实时闸门。 */
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
     * 在全局排空之前捕获失败代号（generation）。并发的毒化操作会推进代号，
     * 因此一次过期的空扫描不会意外清掉其就绪失败。
     */
    fun generation(): Long = state.get().generation

    /** 仅在全局 Rocks 可靠发件箱被观察到两次为空之后调用。 */
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
    BEFORE_PROJECTION_LOCK,
    AFTER_PENDING_BEFORE_PROJECTION,
    AFTER_LUCENE_BEFORE_POSTGRES,
    AFTER_POSTGRES_BEFORE_OUTBOX_DELETE,
    AFTER_OUTBOX_DELETE_BEFORE_MESSAGE_RETURN,
}

/** 仅隔离测试使用的确定性崩溃接缝。 */
fun interface MessageProjectionHooks {
    suspend fun hit(stage: MessageProjectionStage, operation: MessageProjectionOperation)

    object None : MessageProjectionHooks {
        override suspend fun hit(stage: MessageProjectionStage, operation: MessageProjectionOperation) = Unit
    }
}
