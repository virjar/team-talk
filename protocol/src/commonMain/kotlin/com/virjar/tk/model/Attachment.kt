package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.body.MessageBodyPolicy
import kotlinx.serialization.Serializable

/**
 * TeamTalk 文件存储中的公开附件描述符。
 *
 * [path] 是附件的唯一身份，固定为 FileStore 相对路径；其余字段来自服务端
 * FileMetadata，是消息展示与下载策略使用的权威快照。消息协议不再传递 URL，
 * 客户端只在真正访问文件时用当前会话的 ServerConfig 解析 [path]。
 */
@Serializable
data class Attachment(
    val path: String,
    val name: String,
    val contentType: String,
    val size: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(path)
        buf.writeString(name)
        buf.writeString(contentType)
        buf.writeVarLong(size)
    }

    companion object : IProtoReader<Attachment> {
        override fun readFrom(buf: PacketBuffer) = Attachment(
            path = buf.readString(MessageBodyPolicy.utf8WireLimit(AttachmentPolicy.MAX_REFERENCE_LENGTH))!!,
            name = buf.readString(MessageBodyPolicy.utf8WireLimit(AttachmentPolicy.MAX_NAME_LENGTH))!!,
            contentType = buf.readString(
                MessageBodyPolicy.utf8WireLimit(AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH),
            )!!,
            size = buf.readVarLong(),
        )
    }
}
