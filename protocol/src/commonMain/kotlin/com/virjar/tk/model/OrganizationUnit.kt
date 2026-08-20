package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 单组织目录中的一个结构节点。
 *
 * [groupChatId] 非空表示该节点启用了由组织目录维护的部门群。部门群成员来自当前节点及其
 * 全部后代节点，不能由普通群成员手工修改。
 *
 * [directMemberCount] 是只读目录投影，表示直接归属于当前节点的用户数，不包含下级节点。
 * 该口径与客户端选中节点后展示的直属成员列表一致。
 */
@Serializable
data class OrganizationUnit(
    val unitId: String,
    val parentId: String? = null,
    val name: String,
    val leaderUid: String? = null,
    val sortOrder: Int = 0,
    val groupChatId: String? = null,
    val status: Int = STATUS_ACTIVE,
    val directMemberCount: Int = 0,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(unitId)
        buf.writeString(parentId)
        buf.writeString(name)
        buf.writeString(leaderUid)
        buf.writeVarInt(sortOrder)
        buf.writeString(groupChatId)
        buf.writeVarInt(status)
        buf.writeVarInt(directMemberCount)
    }

    companion object : IProtoReader<OrganizationUnit> {
        const val STATUS_ARCHIVED = 0
        const val STATUS_ACTIVE = 1

        override fun readFrom(buf: PacketBuffer): OrganizationUnit = OrganizationUnit(
            unitId = buf.readRequiredString(),
            parentId = buf.readString(),
            name = buf.readRequiredString(),
            leaderUid = buf.readString(),
            sortOrder = buf.readVarInt(),
            groupChatId = buf.readString(),
            status = buf.readVarInt(),
            directMemberCount = buf.readVarInt(),
        )
    }
}
