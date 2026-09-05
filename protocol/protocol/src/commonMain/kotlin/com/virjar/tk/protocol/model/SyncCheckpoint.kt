package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import kotlinx.serialization.Serializable

/**
 * 一次连接内 checkpoint 引导的头部。
 *
 * [baseEventId] 是本检查点覆盖的权威尾部游标。在原子地安装全部分区之后，
 * 客户端从严格位于该游标之后的事件继续。每个后续页面都绑定到 [checkpointId]；
 * 如果该标识被拒绝或发生变化，客户端必须丢弃所有已收集页面。
 */
@Serializable
data class SyncCheckpointHeader(
    val datasetId: String,
    val checkpointId: String,
    val baseEventId: Long,
    val currentUser: User,
) : IProto {
    init {
        SyncDatasetIdPolicy.requireValid(datasetId)
        SyncCheckpointPolicy.requireCheckpointId(checkpointId)
        require(baseEventId >= 0L) { "checkpoint baseEventId must be non-negative" }
    }

    override fun writeTo(buf: PacketBuffer) {
        SyncDatasetIdPolicy.requireValid(datasetId)
        SyncCheckpointPolicy.requireCheckpointId(checkpointId)
        require(baseEventId >= 0L) { "checkpoint baseEventId must be non-negative" }
        buf.writeString(datasetId)
        buf.writeString(checkpointId)
        buf.writeVarLong(baseEventId)
        currentUser.writeTo(buf)
    }

    companion object : IProtoReader<SyncCheckpointHeader> {
        override fun readFrom(buf: PacketBuffer): SyncCheckpointHeader = SyncCheckpointHeader(
            datasetId = SyncDatasetIdPolicy.readRequired(buf, "checkpoint.datasetId"),
            checkpointId = SyncCheckpointPolicy.readCheckpointId(buf),
            baseEventId = buf.readVarLong(),
            currentUser = User.readFrom(buf),
        )
    }
}

/**
 * 每个类型化 checkpoint 分区共享的 keyset 请求。
 *
 * 为 null 的 [cursor] 表示开始一个分区。非 null 游标是上一页返回的排他、服务端拥有的值；
 * 客户端逐字节保留它，绝不自行合成或检查。
 */
@Serializable
data class SyncCheckpointPageRequest(
    val checkpointId: String,
    val cursor: String? = null,
) : IProto {
    init {
        SyncCheckpointPolicy.requireCheckpointId(checkpointId)
        SyncCheckpointPolicy.requireOpaqueCursor(cursor)
    }

    override fun writeTo(buf: PacketBuffer) {
        SyncCheckpointPolicy.requireCheckpointId(checkpointId)
        SyncCheckpointPolicy.requireOpaqueCursor(cursor)
        buf.writeString(checkpointId)
        buf.writeString(cursor)
    }

    companion object : IProtoReader<SyncCheckpointPageRequest> {
        override fun readFrom(buf: PacketBuffer): SyncCheckpointPageRequest = SyncCheckpointPageRequest(
            checkpointId = SyncCheckpointPolicy.readCheckpointId(buf),
            cursor = SyncCheckpointPolicy.readOpaqueCursor(buf),
        )
    }
}

/** 来自检查点完整联系人投影的一个稳定 `friendUid` keyset 页。 */
@Serializable
data class SyncCheckpointContactPage(
    val items: List<Contact>,
    val nextCursor: String?,
) : IProto {
    init {
        validateCheckpointPage(
            kind = "Checkpoint contact",
            items = items,
            nextCursor = nextCursor,
            keyOf = Contact::friendUid,
            fail = ::encodingFailure,
        )
    }

    override fun writeTo(buf: PacketBuffer) {
        validateCheckpointPage(
            kind = "Checkpoint contact",
            items = items,
            nextCursor = nextCursor,
            keyOf = Contact::friendUid,
            fail = ::encodingFailure,
        )
        val before = buf.readableBytes()
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
        requireEncodedBudget("Checkpoint contact", buf.readableBytes() - before)
    }

    companion object : IProtoReader<SyncCheckpointContactPage> {
        const val MAX_PAGE_SIZE = SyncCheckpointPolicy.MAX_PAGE_SIZE
        const val MAX_ENCODED_BYTES = SyncCheckpointPolicy.MAX_PAGE_ENCODED_BYTES

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES)
        }

        override fun readFrom(buf: PacketBuffer): SyncCheckpointContactPage {
            val before = buf.readableBytes()
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 7,
                fieldName = "checkpoint contact page",
            )
            val items = List(count) { Contact.readFrom(buf) }
            val nextCursor = SyncCheckpointPolicy.readOpaqueCursor(buf)
            val page = decodePage("Checkpoint contact") {
                SyncCheckpointContactPage(items, nextCursor)
            }
            verifyDecodedBudget("Checkpoint contact", before - buf.readableBytes())
            return page
        }
    }
}

/** 来自检查点完整可访问会话投影的一个稳定 `chatId` keyset 页。 */
@Serializable
data class SyncCheckpointChatPage(
    val items: List<Chat>,
    val nextCursor: String?,
) : IProto {
    init {
        validateCheckpointPage(
            kind = "Checkpoint chat",
            items = items,
            nextCursor = nextCursor,
            keyOf = Chat::chatId,
            fail = ::encodingFailure,
        )
    }

    override fun writeTo(buf: PacketBuffer) {
        validateCheckpointPage(
            kind = "Checkpoint chat",
            items = items,
            nextCursor = nextCursor,
            keyOf = Chat::chatId,
            fail = ::encodingFailure,
        )
        val before = buf.readableBytes()
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
        requireEncodedBudget("Checkpoint chat", buf.readableBytes() - before)
    }

    companion object : IProtoReader<SyncCheckpointChatPage> {
        const val MAX_PAGE_SIZE = SyncCheckpointPolicy.MAX_PAGE_SIZE
        const val MAX_ENCODED_BYTES = SyncCheckpointPolicy.MAX_PAGE_ENCODED_BYTES

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES)
        }

        override fun readFrom(buf: PacketBuffer): SyncCheckpointChatPage {
            val before = buf.readableBytes()
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 10,
                fieldName = "checkpoint chat page",
            )
            val items = List(count) { Chat.readFrom(buf) }
            val nextCursor = SyncCheckpointPolicy.readOpaqueCursor(buf)
            val page = decodePage("Checkpoint chat") {
                SyncCheckpointChatPage(items, nextCursor)
            }
            verifyDecodedBudget("Checkpoint chat", before - buf.readableBytes())
            return page
        }
    }
}

/**
 * 所有 checkpoint 页面共享的线格式边界；游标含义仍属服务端私有。
 *
 * 128 字节的游标上限有意与 [ConversationPagePolicy] 保持一致：会话 checkpoint 页
 * 复用了已有的编码会话游标，它比原始的 36 字符联系人或会话标识更长。
 */
object SyncCheckpointPolicy {
    const val MAX_CHECKPOINT_ID_BYTES = 36
    const val MAX_CURSOR_BYTES = 128
    const val MAX_PAGE_SIZE = 256
    const val MAX_PAGE_ENCODED_BYTES = 2 * 1024 * 1024

    private val CANONICAL_UUID = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    )

    fun requireCheckpointId(value: String) {
        require(value.length == MAX_CHECKPOINT_ID_BYTES && CANONICAL_UUID.matches(value)) {
            "checkpointId must be a canonical UUID"
        }
    }

    fun requireOpaqueCursor(cursor: String?) {
        if (cursor == null) return
        require(cursor.isNotEmpty() && cursor.length <= MAX_CURSOR_BYTES) {
            "Checkpoint cursor has an invalid length"
        }
        require(cursor.all(::isBase64UrlCharacter)) {
            "Checkpoint cursor is not canonical base64url"
        }
    }

    internal fun readCheckpointId(buf: PacketBuffer): String {
        val value = buf.readRequiredString(MAX_CHECKPOINT_ID_BYTES, "checkpoint.checkpointId")
        try {
            requireCheckpointId(value)
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "checkpointId is invalid")
        }
        return value
    }

    internal fun readOpaqueCursor(buf: PacketBuffer): String? {
        val value = buf.readString(MAX_CURSOR_BYTES)
        try {
            requireOpaqueCursor(value)
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "checkpoint cursor is invalid")
        }
        return value
    }

    private fun isBase64UrlCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
}

private fun <T, K> validateCheckpointPage(
    kind: String,
    items: List<T>,
    nextCursor: String?,
    keyOf: (T) -> K,
    fail: (String) -> Nothing,
) {
    if (items.size > SyncCheckpointPolicy.MAX_PAGE_SIZE) {
        fail("$kind page cannot contain more than ${SyncCheckpointPolicy.MAX_PAGE_SIZE} items")
    }
    if (items.mapTo(hashSetOf(), keyOf).size != items.size) {
        fail("$kind page contains duplicate identities")
    }
    try {
        SyncCheckpointPolicy.requireOpaqueCursor(nextCursor)
    } catch (invalid: IllegalArgumentException) {
        fail(invalid.message ?: "$kind cursor is invalid")
    }
    if (nextCursor != null && items.size != SyncCheckpointPolicy.MAX_PAGE_SIZE) {
        fail("A non-terminal $kind page must contain exactly ${SyncCheckpointPolicy.MAX_PAGE_SIZE} items")
    }
}

private fun encodingFailure(message: String): Nothing = throw ProtocolEncodingException(message)

private inline fun <T> decodePage(kind: String, build: () -> T): T = try {
    build()
} catch (corrupt: ProtocolCorruptionException) {
    throw corrupt
} catch (invalid: ProtocolEncodingException) {
    throw ProtocolCorruptionException(invalid.message ?: "$kind page is invalid")
} catch (invalid: IllegalArgumentException) {
    throw ProtocolCorruptionException(invalid.message ?: "$kind page is invalid")
}

private fun requireEncodedBudget(kind: String, encodedBytes: Int) {
    if (encodedBytes > SyncCheckpointPolicy.MAX_PAGE_ENCODED_BYTES) {
        throw ProtocolEncodingException(
            "$kind page size $encodedBytes exceeds inner budget " +
                SyncCheckpointPolicy.MAX_PAGE_ENCODED_BYTES,
        )
    }
}

private fun verifyDecodedBudget(kind: String, encodedBytes: Int) {
    if (encodedBytes > SyncCheckpointPolicy.MAX_PAGE_ENCODED_BYTES) {
        throw ProtocolCorruptionException(
            "$kind page size $encodedBytes exceeds inner budget " +
                SyncCheckpointPolicy.MAX_PAGE_ENCODED_BYTES,
        )
    }
}
