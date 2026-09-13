package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.UserAvatarPolicy

/**
 * 群头像变更通知（内测反馈 T053）。attachment 为 null 表示头像被清除。
 * 附件本体经既有认证附件端点按"当前群头像对成员可见"的 ACL 下载；
 * 本 payload 只携带权威元数据描述符。
 */
@SinceProtocol(3)
data class GroupAvatarSyncPayload(
    val chatId: String,
    val attachment: Attachment?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeBoolean(attachment != null)
        attachment?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
    }

    companion object : IProtoReader<GroupAvatarSyncPayload> {
        override fun readFrom(buf: PacketBuffer): GroupAvatarSyncPayload {
            val chatId = buf.readRequiredString()
            val attachment = if (buf.readBoolean("group avatar presence")) {
                UserAvatarPolicy.readFrom(buf, "group avatar")
            } else {
                null
            }
            return GroupAvatarSyncPayload(chatId, attachment)
        }
    }
}
