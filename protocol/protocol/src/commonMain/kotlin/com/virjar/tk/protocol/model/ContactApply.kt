package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

@Serializable
data class ContactApply(
    val id: Long,
    val fromUid: String,
    val toUid: String,
    val token: String? = null,
    val remark: String? = null,
    /** wire 仍为 Int code（与 [ContactApplyStatus] 共用值空间）；逻辑代码用 [statusValue]。 */
    val status: Int = 0,
    val createdAt: Long = 0,
    val fromUser: User? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(id)
        buf.writeString(fromUid)
        buf.writeString(toUid)
        buf.writeString(token)
        buf.writeString(remark)
        buf.writeVarInt(status)
        buf.writeVarLong(createdAt)
        buf.writeBoolean(fromUser != null)
        fromUser?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<ContactApply> {
        override fun readFrom(buf: PacketBuffer): ContactApply = ContactApply(
            id = buf.readVarLong(),
            fromUid = buf.readRequiredString(),
            toUid = buf.readRequiredString(),
            token = buf.readString(),
            remark = buf.readString(),
            status = buf.readVarInt(),
            createdAt = buf.readVarLong(),
            fromUser = if (buf.readBoolean("contact apply user presence")) User.readFrom(buf) else null,
        )
    }
}

/** 处理动作定向投影的状态视图；与 [ContactApplyRecord.statusValue] 同一值空间。 */
val ContactApply.statusValue: ContactApplyStatus?
    get() = ContactApplyStatus.fromCode(status)
