package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 群文件的不可变内容版本；历史版本只追加、不原地覆盖。 */
@Serializable
data class GroupFileVersion(
    val entryId: String,
    val version: Long,
    val attachment: Attachment,
    val createdBy: String,
    val createdAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(entryId)
        buf.writeVarLong(version)
        attachment.writeTo(buf)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
    }

    companion object : IProtoReader<GroupFileVersion> {
        override fun readFrom(buf: PacketBuffer): GroupFileVersion = GroupFileVersion(
            entryId = buf.readRequiredString(),
            version = buf.readVarLong(),
            attachment = Attachment.readFrom(buf),
            createdBy = buf.readRequiredString(),
            createdAt = buf.readVarLong(),
        )
    }
}
