package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.model.Message

/** 扫描权威消息归档的默认启动页边界。 */
const val DEFAULT_MESSAGE_ARCHIVE_PAGE_SIZE: Int = 256
const val DEFAULT_MESSAGE_ARCHIVE_PAGE_BYTES: Long = 32L * 1024 * 1024
const val MAX_MESSAGE_ARCHIVE_PAGE_SIZE: Int = 1_024

/**
 * [MessageArchiveReader] 返回的不透明续页。游标标识最后一条已返回的记录，
 * 因此后续页严格从这个不可变消息身份之后开始。
 */
data class MessageArchiveCursor(
    val chatId: String,
    val serverSeq: Long,
) : Comparable<MessageArchiveCursor> {
    private val orderBytes = chatId.encodeToByteArray(throwOnInvalidSequence = true)

    init {
        require(chatId.isNotBlank()) { "Message archive cursor chatId must not be blank" }
        require(serverSeq > 0L) { "Message archive cursor sequence must be positive" }
    }

    /** 匹配 MessageStore 的带长度前缀 UTF-8 聊天键，后跟正的 big-endian seq。 */
    override fun compareTo(other: MessageArchiveCursor): Int {
        orderBytes.size.compareTo(other.orderBytes.size).takeIf { it != 0 }?.let { return it }
        for (index in orderBytes.indices) {
            val compared = (orderBytes[index].toInt() and 0xFF)
                .compareTo(other.orderBytes[index].toInt() and 0xFF)
            if (compared != 0) return compared
        }
        return serverSeq.compareTo(other.serverSeq)
    }
}

/** 最新的权威消息值，连同其单调的搜索投影修订。 */
data class MessageArchiveEntry(
    val message: Message,
    val revision: Long,
) {
    init {
        require(message.chatId.isNotBlank() && message.serverSeq > 0L) {
            "Message archive entry requires a durable message identity"
        }
        require(revision > 0L) { "Message archive revision must be positive" }
    }

    val cursor: MessageArchiveCursor
        get() = MessageArchiveCursor(message.chatId, message.serverSeq)
}

/**
 * 一个资源受限的页。非空的 [nextCursor] 表示还有更多记录，且总是等于最后返回的条目；
 * 这使进度可以被独立验证，并防止空页循环。
 */
data class MessageArchivePage(
    val entries: List<MessageArchiveEntry>,
    val nextCursor: MessageArchiveCursor?,
    val encodedBytes: Long,
) {
    init {
        require(encodedBytes >= 0L) { "Message archive page byte count must not be negative" }
        require(entries.isNotEmpty() || nextCursor == null) {
            "An empty message archive page cannot have a continuation"
        }
        require(nextCursor == null || entries.last().cursor == nextCursor) {
            "Message archive continuation must identify the last returned entry"
        }
    }
}

/**
 * 当前消息值的稳定只读扫描。生产只在启动拥有 MessageStore 且 TCP/HTTP 写入者尚未开启时
 * 调用，因此游标观察的是一个固定的归档。
 */
fun interface MessageArchiveReader {
    fun readArchivePage(
        after: MessageArchiveCursor?,
        limit: Int,
        maxEncodedBytes: Long,
    ): MessageArchivePage
}
