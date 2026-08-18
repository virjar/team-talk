package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 用户在组织节点中的直接归属；递归查询时 [unitId] 仍保留其实际所属节点。 */
@Serializable
data class OrganizationMember(
    val unitId: String,
    val uid: String,
    val title: String? = null,
    val primary: Boolean = false,
    val joinedAt: Long = 0,
    val user: User? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(unitId)
        buf.writeString(uid)
        buf.writeString(title)
        buf.writeByte(if (primary) 1 else 0)
        buf.writeVarLong(joinedAt)
        buf.writeByte(if (user != null) 1 else 0)
        user?.writeTo(buf)
    }

    companion object : IProtoReader<OrganizationMember> {
        override fun readFrom(buf: PacketBuffer): OrganizationMember = OrganizationMember(
            unitId = buf.readString()!!,
            uid = buf.readString()!!,
            title = buf.readString(),
            primary = buf.readByte() != 0,
            joinedAt = buf.readVarLong(),
            user = if (buf.readByte() != 0) User.readFrom(buf) else null,
        )
    }
}
