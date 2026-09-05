package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 一次文档空间托管权命令的不可变确认。 */
@Serializable
data class DocumentCustodyTransferResult(
    val spaceId: String,
    val ownerPrincipalType: Int,
    val ownerPrincipalId: String,
    val stewardUid: String,
    val custodyRevision: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(spaceId)
        buf.writeVarInt(ownerPrincipalType)
        buf.writeString(ownerPrincipalId)
        buf.writeString(stewardUid)
        buf.writeVarLong(custodyRevision)
    }

    companion object : IProtoReader<DocumentCustodyTransferResult> {
        override fun readFrom(buf: PacketBuffer): DocumentCustodyTransferResult = DocumentCustodyTransferResult(
            spaceId = buf.readRequiredString(),
            ownerPrincipalType = buf.readVarInt(),
            ownerPrincipalId = buf.readRequiredString(),
            stewardUid = buf.readRequiredString(),
            custodyRevision = buf.readVarLong(),
        )
    }
}
