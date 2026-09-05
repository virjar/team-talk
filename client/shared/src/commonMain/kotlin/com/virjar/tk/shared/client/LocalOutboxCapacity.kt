package com.virjar.tk.shared.client

/** 标识准入门预算被耗尽的持久本地优先队列。 */
enum class LocalOutboxKind {
    OUTGOING_MESSAGE,
    CONVERSATION_DRAFT,
    CONVERSATION_READ,
    GROUP_FILE_COMMAND,
}

/** 标识本地可靠发件箱预算中独立强制的资源维度。 */
enum class LocalOutboxCapacityDimension {
    ENTRY_COUNT,
    STORED_BYTES,
    CHARACTER_COUNT,
}

/**
 * 在新本地可靠事实持久化之前抛出的、带类型的可恢复拒绝。
 *
 * 现有队列内容保持不变。特别地，活跃 outgoing 消息绝不会被驱逐来为新请求腾位；调用方可以在
 * 容量可用后重试完全相同的请求，并保留原始幂等语义。
 */
class LocalOutboxCapacityExceededException(
    val outbox: LocalOutboxKind,
    val dimension: LocalOutboxCapacityDimension,
    val limit: Long,
) : IllegalStateException(
    "Local $outbox outbox exceeds its $dimension limit of $limit",
)

const val MAX_ACTIVE_OUTGOING_MESSAGES = 1_024
const val MAX_ACTIVE_OUTGOING_STORED_BYTES = 64L * 1_024L * 1_024L
const val MAX_TERMINAL_OUTGOING_RECEIPTS = 512
const val MAX_TERMINAL_OUTGOING_STORED_BYTES = 32L * 1_024L * 1_024L
const val MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES = 64
const val MAX_OUTGOING_LAST_ERROR_CHARACTERS = 1_000
const val MAX_CONVERSATION_DRAFT_OUTBOX_ENTRIES = 1_000
const val MAX_CONVERSATION_READ_OUTBOX_ENTRIES = 1_000
const val MAX_CONVERSATION_DRAFT_CHARACTERS = 12_000_000L
const val MAX_CONVERSATION_DRAFT_UTF8_BYTES = 48L * 1_024L * 1_024L

internal data class LocalOutboxLimits(
    val activeOutgoingCount: Int = MAX_ACTIVE_OUTGOING_MESSAGES,
    val activeOutgoingBytes: Long = MAX_ACTIVE_OUTGOING_STORED_BYTES,
    val terminalOutgoingCount: Int = MAX_TERMINAL_OUTGOING_RECEIPTS,
    val terminalOutgoingBytes: Long = MAX_TERMINAL_OUTGOING_STORED_BYTES,
    val draftCount: Int = MAX_CONVERSATION_DRAFT_OUTBOX_ENTRIES,
    val readCount: Int = MAX_CONVERSATION_READ_OUTBOX_ENTRIES,
    val draftCharacters: Long = MAX_CONVERSATION_DRAFT_CHARACTERS,
    val draftUtf8Bytes: Long = MAX_CONVERSATION_DRAFT_UTF8_BYTES,
    val groupFileCommandCount: Int = MAX_PENDING_GROUP_FILE_COMMANDS,
    val groupFileCommandBytes: Long = MAX_PENDING_GROUP_FILE_COMMAND_STORED_BYTES,
) {
    init {
        // 这是内部测试接缝，而不是调用方放宽生产硬限制的途径。
        require(activeOutgoingCount in 1..MAX_ACTIVE_OUTGOING_MESSAGES)
        require(activeOutgoingBytes in 1L..MAX_ACTIVE_OUTGOING_STORED_BYTES)
        require(terminalOutgoingCount in 1..MAX_TERMINAL_OUTGOING_RECEIPTS)
        require(terminalOutgoingBytes in 1L..MAX_TERMINAL_OUTGOING_STORED_BYTES)
        require(draftCount in 1..MAX_CONVERSATION_DRAFT_OUTBOX_ENTRIES)
        require(readCount in 1..MAX_CONVERSATION_READ_OUTBOX_ENTRIES)
        require(draftCharacters in 0L..MAX_CONVERSATION_DRAFT_CHARACTERS)
        require(draftUtf8Bytes in 0L..MAX_CONVERSATION_DRAFT_UTF8_BYTES)
        require(groupFileCommandCount in 1..MAX_PENDING_GROUP_FILE_COMMANDS)
        require(groupFileCommandBytes in 1L..MAX_PENDING_GROUP_FILE_COMMAND_STORED_BYTES)
    }
}

internal val DEFAULT_LOCAL_OUTBOX_LIMITS = LocalOutboxLimits()

internal fun localOutboxCapacityExceeded(
    outbox: LocalOutboxKind,
    dimension: LocalOutboxCapacityDimension,
    limit: Long,
): Nothing = throw LocalOutboxCapacityExceededException(outbox, dimension, limit)
