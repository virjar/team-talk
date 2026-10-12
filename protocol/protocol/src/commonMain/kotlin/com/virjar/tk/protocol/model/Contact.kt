package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

@Serializable
data class Contact(
    val uid: String,
    val friendUid: String,
    val remark: String? = null,
    val status: Int = STATUS_NORMAL,
    val user: User? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(friendUid)
        buf.writeString(remark)
        buf.writeVarInt(status)
        buf.writeBoolean(user != null)
        user?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Contact> {
        /** 好友关系正常；与 Friends 表 status 列共用同一值空间。 */
        const val STATUS_NORMAL = 1

        /** 任一方拉黑后关系进入该状态，服务端创建私聊/发消息时权威拒绝。 */
        const val STATUS_BLOCKED = 2

        override fun readFrom(buf: PacketBuffer): Contact = Contact(
            uid = buf.readRequiredString(),
            friendUid = buf.readRequiredString(),
            remark = buf.readString(),
            status = buf.readVarInt(),
            user = if (buf.readBoolean("contact user presence")) User.readFrom(buf) else null,
        )
    }
}
