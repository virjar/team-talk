package com.virjar.tk.protocol.model

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
    /** 可转移的资产归属主体；[createdBy] 只保留不可变审计语义。 */
    val ownerPrincipalType: Int = DocumentSpaceGrant.PRINCIPAL_USER,
    val ownerPrincipalId: String = createdBy,
    /** 当前承担空间管理责任的用户；组织持有本身不会向全部部门成员授予权限。 */
    val stewardUid: String = createdBy,
    /** 资产交接的乐观锁版本，与文档节点 revision 相互独立。 */
    val custodyRevision: Long = 1,
    /** 显式 ACL 的乐观锁版本；只有真实 grant 变化才单调推进。 */
    val policyRevision: Long = 1,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(spaceId)
        buf.writeString(name)
        buf.writeString(description)
        buf.writeVarInt(myRole)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeVarLong(updatedAt)
        buf.writeVarInt(ownerPrincipalType)
        buf.writeString(ownerPrincipalId)
        buf.writeString(stewardUid)
        buf.writeVarLong(custodyRevision)
        buf.writeVarLong(policyRevision)
    }

    companion object : IProtoReader<DocumentSpace> {
        const val ROLE_NONE = 0
        const val ROLE_VIEWER = 1
        const val ROLE_EDITOR = 2
        const val ROLE_ADMIN = 3
        const val ROLE_OWNER = 4

        override fun readFrom(buf: PacketBuffer): DocumentSpace = DocumentSpace(
            spaceId = buf.readRequiredString(),
            name = buf.readRequiredString(),
            description = buf.readString(),
            myRole = buf.readVarInt(),
            createdBy = buf.readRequiredString(),
            createdAt = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
            ownerPrincipalType = buf.readVarInt(),
            ownerPrincipalId = buf.readRequiredString(),
            stewardUid = buf.readRequiredString(),
            custodyRevision = buf.readVarLong(),
            policyRevision = buf.readVarLong(),
        )
    }
}
