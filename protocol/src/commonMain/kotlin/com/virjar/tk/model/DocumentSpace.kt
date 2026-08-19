package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 企业文档资产的一级权限边界。不同空间可独立授权用户和组织部门。 */
@Serializable
data class DocumentSpace(
    val spaceId: String,
    val name: String,
    val description: String? = null,
    /** 当前调用者在空间内的有效角色，由服务端动态投影，不作为持久化事实。 */
    val myRole: Int,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(spaceId)
        buf.writeString(name)
        buf.writeString(description)
        buf.writeVarInt(myRole)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<DocumentSpace> {
        const val ROLE_NONE = 0
        const val ROLE_VIEWER = 1
        const val ROLE_EDITOR = 2
        const val ROLE_ADMIN = 3
        const val ROLE_OWNER = 4

        override fun readFrom(buf: PacketBuffer): DocumentSpace = DocumentSpace(
            spaceId = buf.readString()!!,
            name = buf.readString()!!,
            description = buf.readString(),
            myRole = buf.readVarInt(),
            createdBy = buf.readString()!!,
            createdAt = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
        )
    }
}
