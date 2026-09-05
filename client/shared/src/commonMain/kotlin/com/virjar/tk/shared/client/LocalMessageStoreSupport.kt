package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message

internal fun validateOutgoingRequestFingerprint(requestFingerprint: ByteArray?) {
    require(
        requestFingerprint == null ||
            requestFingerprint.size in 1..MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES
    ) {
        "requestFingerprint must contain 1..$MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES bytes"
    }
}

internal fun boundedOutgoingError(error: String): String =
    error.take(MAX_OUTGOING_LAST_ERROR_CHARACTERS)

internal const val OUTGOING_RECOVERY_PROJECTION_PAGE_SIZE = 32
internal const val MAX_PENDING_OPTIMISTIC_EDITS = 16

internal data class MessageProjectionKey(
    val chatId: String,
    val clientMsgId: String,
)

internal data class PendingOptimisticMessageEdit(
    val lease: LocalOptimisticMessageEditLease,
    val key: MessageProjectionKey,
    val window: MessageWindow,
    val previous: Message,
    val optimistic: Message,
    var published: Boolean = false,
    var superseded: Boolean = false,
)

internal class LocalOptimisticMessageEditLease(
    val owner: Any,
    val tokenId: Long,
) : OptimisticMessageEditLease

/** 调用方持有缓存状态锁与外层 SQL 事务。 */
internal fun nextOutgoingCompletionTime(queries: AppDatabaseQueries, now: Long): Long {
    val previous = queries.selectMaxOutgoingCompletedAt().executeAsOne().max_completed_at
    return if (previous == null || now > previous) {
        now
    } else {
        check(previous < Long.MAX_VALUE) { "outgoing completion clock exhausted" }
        previous + 1L
    }
}
