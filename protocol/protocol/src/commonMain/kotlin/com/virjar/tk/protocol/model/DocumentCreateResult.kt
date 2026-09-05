package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 一条稳定文档创建命令的不可变确认。
 *
 * [document] 仅在首次成功创建时出现。精确重试仍可证明命令在托管权转移、归档或删除
 * 使当前文档投影不安全后依然已提交；该回执保留 [documentId] 而让 [document] 缺省。
 */
@Serializable
data class DocumentCreateResult(
    val documentId: String,
    val document: Document?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(documentId)
        buf.writeBoolean(document != null)
        document?.writeTo(buf)
    }

    companion object : IProtoReader<DocumentCreateResult> {
        override fun readFrom(buf: PacketBuffer): DocumentCreateResult = DocumentCreateResult(
            documentId = buf.readRequiredString(),
            document = if (buf.readBoolean("document create projection presence")) {
                Document.readFrom(buf)
            } else {
                null
            },
        )
    }
}
