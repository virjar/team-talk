package com.virjar.tk.protocol.model

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
        buf.writeBoolean(includeDescendants)
        buf.writeString(displayName)
    }

    companion object : IProtoReader<DocumentSpaceGrant> {
        const val PRINCIPAL_USER = 1
        const val PRINCIPAL_ORGANIZATION_UNIT = 2
        /** 一个空间维护一个有界的 ACL；更大范围必须使用组织部门授权。 */
        const val MAX_GRANTS_PER_SPACE = 1_000
        const val MAX_ID_LENGTH = 36
        const val MAX_DISPLAY_NAME_LENGTH = 100

        /** 有界 ACL 页与 SDK 目标检查共用的 fail-closed 校验。 */
        fun validationError(grant: DocumentSpaceGrant): String? = when {
            grant.spaceId.isBlank() || grant.spaceId.length > MAX_ID_LENGTH ->
                "Document grant space id is invalid"
            grant.principalType != PRINCIPAL_USER &&
                grant.principalType != PRINCIPAL_ORGANIZATION_UNIT ->
                "Document grant principal type is invalid"
            grant.principalId.isBlank() || grant.principalId.length > MAX_ID_LENGTH ->
                "Document grant principal id is invalid"
            grant.role !in DocumentSpace.ROLE_VIEWER..DocumentSpace.ROLE_ADMIN ->
                "Document grant role is invalid"
            grant.principalType == PRINCIPAL_USER && grant.includeDescendants ->
                "User document grant cannot include descendants"
            grant.displayName != null &&
                (grant.displayName.isBlank() || grant.displayName.length > MAX_DISPLAY_NAME_LENGTH) ->
                "Document grant display name is invalid"
            else -> null
        }

        override fun readFrom(buf: PacketBuffer): DocumentSpaceGrant = DocumentSpaceGrant(
            spaceId = buf.readRequiredString(),
            principalType = buf.readVarInt(),
            principalId = buf.readRequiredString(),
            role = buf.readVarInt(),
            includeDescendants = buf.readBoolean("document grant include descendants"),
            displayName = buf.readString(),
        )
    }
}
