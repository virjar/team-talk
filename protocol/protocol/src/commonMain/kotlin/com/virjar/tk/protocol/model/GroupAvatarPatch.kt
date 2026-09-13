package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol

/**
 * 群头像设置请求体（内测反馈 T053）：attachment 为 null 表示清除当前头像。
 * wire 布局自带 present 位，因此 RPC 签名保持非空 IProto 参数。
 */
@SinceProtocol(3)
data class GroupAvatarPatch(
    val attachment: Attachment?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeBoolean(attachment != null)
        attachment?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
    }

    companion object : IProtoReader<GroupAvatarPatch> {
        override fun readFrom(buf: PacketBuffer): GroupAvatarPatch {
            val attachment = if (buf.readBoolean("group avatar patch presence")) {
                UserAvatarPolicy.readFrom(buf, "group avatar patch")
            } else {
                null
            }
            return GroupAvatarPatch(attachment)
        }
    }
}
