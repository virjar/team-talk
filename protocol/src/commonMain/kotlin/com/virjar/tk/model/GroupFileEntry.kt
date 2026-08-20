package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 群文件空间中的稳定逻辑条目。
 *
 * 文件内容按版本存储，[attachment] 仅指向当前版本；目录没有附件。
 * [revision] 是乐观锁；[contentVersion] 只随文件内容版本递增，目录恒为 0。
 */
@Serializable
data class GroupFileEntry(
    val entryId: String,
    val chatId: String,
    val parentId: String? = null,
    val kind: Int,
    val name: String,
    val attachment: Attachment? = null,
    val revision: Long = 1,
    val contentVersion: Long = 0,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(entryId)
        buf.writeString(chatId)
        buf.writeString(parentId)
        buf.writeVarInt(kind)
        buf.writeString(name)
        buf.writeBoolean(attachment != null)
        attachment?.writeTo(buf)
        buf.writeVarLong(revision)
        buf.writeVarLong(contentVersion)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<GroupFileEntry> {
        const val KIND_FOLDER = 1
        const val KIND_FILE = 2

        override fun readFrom(buf: PacketBuffer): GroupFileEntry = GroupFileEntry(
            entryId = buf.readRequiredString(),
            chatId = buf.readRequiredString(),
            parentId = buf.readString(),
            kind = buf.readVarInt(),
            name = buf.readRequiredString(),
            attachment = if (buf.readBoolean("group file attachment presence")) Attachment.readFrom(buf) else null,
            revision = buf.readVarLong(),
            contentVersion = buf.readVarLong(),
            createdBy = buf.readRequiredString(),
            createdAt = buf.readVarLong(),
            updatedBy = buf.readRequiredString(),
            updatedAt = buf.readVarLong(),
        )
    }
}
