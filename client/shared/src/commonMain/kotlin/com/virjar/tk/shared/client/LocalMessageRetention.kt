package com.virjar.tk.shared.client

/** 可重新拉取的权威消息投影的按 chat 逻辑存储预算。 */
internal const val MAX_RETAINED_AUTHORITATIVE_MESSAGES_PER_CHAT = 2_048
internal const val MAX_RETAINED_AUTHORITATIVE_MESSAGE_BYTES_PER_CHAT = 64L * 1_024L * 1_024L

private const val MAX_RETENTION_CATCH_UP_CHATS = 8
private const val MAX_RETENTION_DELETE_BATCH = 256

internal data class LocalMessageRetentionLimits(
    val retainedCount: Int = MAX_RETAINED_AUTHORITATIVE_MESSAGES_PER_CHAT,
    val retainedBytes: Long = MAX_RETAINED_AUTHORITATIVE_MESSAGE_BYTES_PER_CHAT,
    val catchUpChats: Int = MAX_RETENTION_CATCH_UP_CHATS,
    val deleteBatchSize: Int = MAX_RETENTION_DELETE_BATCH,
) {
    init {
        require(retainedCount in 1..MAX_RETAINED_AUTHORITATIVE_MESSAGES_PER_CHAT)
        require(retainedBytes in 1L..MAX_RETAINED_AUTHORITATIVE_MESSAGE_BYTES_PER_CHAT)
        require(catchUpChats in 1..MAX_RETENTION_CATCH_UP_CHATS)
        require(deleteBatchSize in 1..MAX_RETENTION_DELETE_BATCH)
    }
}

internal val DEFAULT_LOCAL_MESSAGE_RETENTION_LIMITS = LocalMessageRetentionLimits()
