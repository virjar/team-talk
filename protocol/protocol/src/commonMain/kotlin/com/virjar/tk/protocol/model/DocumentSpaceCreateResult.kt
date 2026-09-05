package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 文档空间创建命令的不可变确认。
 *
 * [space] 只有在创建者仍是空间管理人时才是权威的当前投影。
 * 当托管权转移或归档使该投影不可用后重放同一条精确创建命令时，它缺省；
 * 但命令仍然被认定已提交。
 */
@Serializable
data class DocumentSpaceCreateResult(
    val spaceId: String,
    val space: DocumentSpace?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(spaceId)
        buf.writeBoolean(space != null)
        space?.writeTo(buf)
    }

    companion object : IProtoReader<DocumentSpaceCreateResult> {
        override fun readFrom(buf: PacketBuffer): DocumentSpaceCreateResult = DocumentSpaceCreateResult(
            spaceId = buf.readRequiredString(),
            space = if (buf.readBoolean("document-space create projection presence")) {
                DocumentSpace.readFrom(buf)
            } else {
                null
            },
        )
    }
}
