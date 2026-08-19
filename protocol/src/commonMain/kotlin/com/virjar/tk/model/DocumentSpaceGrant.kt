package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 空间授权事实。部门授权可选择覆盖其全部下级部门。 */
@Serializable
data class DocumentSpaceGrant(
    val spaceId: String,
    val principalType: Int,
    val principalId: String,
    val role: Int,
    val includeDescendants: Boolean = false,
    val displayName: String? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(spaceId)
        buf.writeVarInt(principalType)
        buf.writeString(principalId)
        buf.writeVarInt(role)
        buf.writeByte(if (includeDescendants) 1 else 0)
        buf.writeString(displayName)
    }

    companion object : IProtoReader<DocumentSpaceGrant> {
        const val PRINCIPAL_USER = 1
        const val PRINCIPAL_ORGANIZATION_UNIT = 2

        override fun readFrom(buf: PacketBuffer): DocumentSpaceGrant = DocumentSpaceGrant(
            spaceId = buf.readString()!!,
            principalType = buf.readVarInt(),
            principalId = buf.readString()!!,
            role = buf.readVarInt(),
            includeDescendants = buf.readByte() != 0,
            displayName = buf.readString(),
        )
    }
}
