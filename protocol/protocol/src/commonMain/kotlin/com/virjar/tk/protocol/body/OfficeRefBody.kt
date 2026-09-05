package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 类型化办公对象引用（CONTENT-08）：消息以引用指向 Document 或群共享文件。
 *
 * MessageBody 只保存引用（[spaceId]+[targetId]）与服务端在发送时重建的安全预览
 * （[title]/[subtitle] 快照），不承载权威内容；打开时客户端必须经当前权限重新校验
 * （DocumentRpc.getDocument / GroupFileRpc.getEntry），删除、归档或撤权后安全降级。
 * 转发只复制引用与冻结预览，不复制权威对象或扩大权限。
 */
data class OfficeRefBody(
    /** 1=Document（spaceId=空间 id，targetId=documentId）；2=群共享文件（spaceId=群 chatId，targetId=entryId）。 */
    val refType: Int,
    val spaceId: String,
    val targetId: String,
    val title: String,
    val subtitle: String = "",
) : MessageBody {
    init {
        require(refType == REF_TYPE_DOCUMENT || refType == REF_TYPE_GROUP_FILE) { "office ref 类型非法" }
        require(spaceId.isNotBlank()) { "office ref spaceId 非法" }
        require(targetId.isNotBlank()) { "office ref targetId 非法" }
        require(title.isNotBlank()) { "office ref title 非法" }
    }

    val isDocument: Boolean get() = refType == REF_TYPE_DOCUMENT

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(refType)
        buf.writeString(spaceId)
        buf.writeString(targetId)
        buf.writeString(title)
        buf.writeString(subtitle)
    }

    companion object : IProtoReader<OfficeRefBody> {
        const val REF_TYPE_DOCUMENT = 1
        const val REF_TYPE_GROUP_FILE = 2

        override fun readFrom(buf: PacketBuffer): OfficeRefBody {
            val body = OfficeRefBody(
                refType = buf.readVarInt(),
                spaceId = buf.readRequiredString(
                    com.virjar.tk.protocol.body.MessageBodyPolicy.utf8WireLimit(
                        com.virjar.tk.protocol.body.MessageBodyPolicy.MAX_CHAT_ID_LENGTH,
                    ),
                ),
                targetId = buf.readRequiredString(
                    com.virjar.tk.protocol.body.MessageBodyPolicy.utf8WireLimit(
                        com.virjar.tk.protocol.body.MessageBodyPolicy.MAX_IDENTIFIER_LENGTH,
                    ),
                ),
                title = buf.readRequiredString(
                    com.virjar.tk.protocol.body.MessageBodyPolicy.utf8WireLimit(
                        com.virjar.tk.protocol.body.MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH,
                    ),
                ),
                subtitle = buf.readString(
                    com.virjar.tk.protocol.body.MessageBodyPolicy.utf8WireLimit(
                        com.virjar.tk.protocol.body.MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH,
                    ),
                ) ?: "",
            )
            return body
        }
    }
}
