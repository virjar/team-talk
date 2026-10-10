package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 群已读状态单行（MSG-01/F5）：成员的已读水位投影。
 *
 * 语义是"阅读进度已到 readSeq"的近似（水位制），不是逐条回执；
 * UI 文案必须表达水位语义。可见性由服务端裁决（默认群主/管理员与消息发送者）。
 */
@com.virjar.tk.protocol.SinceProtocol(6)
data class ChatReadState(
    val uid: String,
    /** 服务端权威显示名快照；不再从客户端目录二次解析。 */
    val displayName: String,
    val readSeq: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(displayName)
        buf.writeVarLong(readSeq)
    }

    companion object : IProtoReader<ChatReadState> {
        override fun readFrom(buf: PacketBuffer): ChatReadState = ChatReadState(
            uid = buf.readRequiredString(MAX_ID_LENGTH, "readState uid"),
            displayName = buf.readString(MAX_ID_LENGTH) ?: "",
            readSeq = buf.readVarLong(),
        )

        const val MAX_ID_LENGTH = 256
    }
}
