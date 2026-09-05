package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import kotlinx.serialization.Serializable

/**
 * 一个权威会话快照页的有界请求。
 *
 * 游标对客户端有意保持 opaque。只有服务端创建或解释它；
 * 线格式模型只是防止不可信对端把它当作无界 String 分配。
 */
@Serializable
data class ConversationPageRequest(
    val cursor: String? = null,
) : IProto {
    init {
        ConversationPagePolicy.requireOpaqueCursor(cursor)
    }

    override fun writeTo(buf: PacketBuffer) {
        ConversationPagePolicy.requireOpaqueCursor(cursor)
        buf.writeString(cursor)
    }

    companion object : IProtoReader<ConversationPageRequest> {
        override fun readFrom(buf: PacketBuffer): ConversationPageRequest = ConversationPageRequest(
            cursor = buf.readString(ConversationPagePolicy.MAX_CURSOR_BYTES),
        )
    }
}

/** 定长 keyset 页；[nextCursor] 仅在快照耗尽后为 null。 */
@Serializable
data class ConversationPage(
    val items: List<Conversation>,
    val nextCursor: String?,
) : IProto {
    init {
        if (items.size > MAX_PAGE_SIZE) {
            throw ProtocolEncodingException(
                "Conversation page cannot contain more than $MAX_PAGE_SIZE items",
            )
        }
        if (items.mapTo(hashSetOf(), Conversation::chatId).size != items.size) {
            throw ProtocolEncodingException("Conversation page contains duplicate chatId values")
        }
        try {
            ConversationPagePolicy.requireOpaqueCursor(nextCursor)
        } catch (invalidCursor: IllegalArgumentException) {
            throw ProtocolEncodingException(invalidCursor.message ?: "Conversation cursor is invalid")
        }
        if (nextCursor != null && items.size != MAX_PAGE_SIZE) {
            throw ProtocolEncodingException(
                "A non-terminal Conversation page must contain exactly $MAX_PAGE_SIZE items",
            )
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        val before = buf.readableBytes()
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
        val encodedBytes = buf.readableBytes() - before
        if (encodedBytes > MAX_ENCODED_BYTES) {
            throw ProtocolEncodingException(
                "Conversation page size $encodedBytes exceeds inner budget $MAX_ENCODED_BYTES",
            )
        }
    }

    companion object : IProtoReader<ConversationPage> {
        /** 十六份最大尺寸的 Markdown 草稿仍能安全地低于 16 MiB 帧上限。 */
        const val MAX_PAGE_SIZE = 16

        /** 域内响应预算；传输层的 16 MiB 上限仍是外层护栏。 */
        const val MAX_ENCODED_BYTES = 8 * 1024 * 1024

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES) {
                "Conversation page budget must remain stricter than the RPC envelope budget"
            }
        }

        override fun readFrom(buf: PacketBuffer): ConversationPage {
            val before = buf.readableBytes()
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 16,
                fieldName = "conversation page",
            )
            val page = ConversationPage(
                items = List(count) { Conversation.readFrom(buf) },
                nextCursor = buf.readString(ConversationPagePolicy.MAX_CURSOR_BYTES),
            )
            val encodedBytes = before - buf.readableBytes()
            if (encodedBytes > MAX_ENCODED_BYTES) {
                throw ProtocolCorruptionException(
                    "Conversation page size $encodedBytes exceeds inner budget $MAX_ENCODED_BYTES",
                )
            }
            return page
        }
    }
}

/** 页信封的共享线格式边界；游标内容仍属服务端私有。 */
object ConversationPagePolicy {
    const val MAX_CURSOR_BYTES = 128

    fun requireOpaqueCursor(cursor: String?) {
        if (cursor == null) return
        require(cursor.isNotEmpty() && cursor.length <= MAX_CURSOR_BYTES) {
            "Conversation cursor has an invalid length"
        }
        require(cursor.all(::isBase64UrlCharacter)) {
            "Conversation cursor is not canonical base64url"
        }
    }

    private fun isBase64UrlCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
}
