package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.body.MessageBodyPolicy

/**
 * 群头像的完整协议对象（内测反馈 T053）：设置请求、批量查询结果与变更通知共用同一形态。
 *
 * attachment 为 null 表示该群当前没有头像；对象内含 chatId，因此单对象即可表达
 * "哪个群、当前头像是什么"。附件本体经既有认证附件端点按"当前群头像对成员可见"
 * 的 ACL 下载，本对象只携带权威元数据描述符。
 */
@SinceProtocol(3)
data class GroupAvatar(
    val chatId: String,
    val attachment: Attachment?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeBoolean(attachment != null)
        attachment?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
    }

    companion object : IProtoReader<GroupAvatar> {
        private const val MAX_CHAT_ID_LENGTH = 36

        override fun readFrom(buf: PacketBuffer): GroupAvatar {
            val chatId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MAX_CHAT_ID_LENGTH),
                "group avatar chatId",
            )
            val attachment = if (buf.readBoolean("group avatar presence")) {
                UserAvatarPolicy.readFrom(buf, "group avatar")
            } else {
                null
            }
            return GroupAvatar(chatId, attachment)
        }
    }
}
