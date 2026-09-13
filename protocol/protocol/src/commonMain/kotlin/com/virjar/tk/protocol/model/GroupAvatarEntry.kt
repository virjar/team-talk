package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.body.MessageBodyPolicy

/** 群当前头像查询结果的单个条目（内测反馈 T053）；attachment 为 null 表示该群未设置头像。 */
@SinceProtocol(3)
data class GroupAvatarEntry(
    val chatId: String,
    val attachment: Attachment?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeBoolean(attachment != null)
        attachment?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
    }

    companion object : IProtoReader<GroupAvatarEntry> {
        private const val MAX_CHAT_ID_LENGTH = 36

        override fun readFrom(buf: PacketBuffer): GroupAvatarEntry {
            val chatId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MAX_CHAT_ID_LENGTH),
                "group avatar entry chatId",
            )
            val attachment = if (buf.readBoolean("group avatar entry presence")) {
                UserAvatarPolicy.readFrom(buf, "group avatar entry")
            } else {
                null
            }
            return GroupAvatarEntry(chatId, attachment)
        }
    }
}
