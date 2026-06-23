package com.virjar.tk.protocol

/**
 * 二进制序列化接口。
 * 所有传输模型和 payload 都实现此接口，手写编解码。
 */
interface IProto {
    fun writeTo(buf: PacketBuffer)
}

/**
 * 可从二进制反序列化的接口。
 */
interface IProtoReader<T> {
    fun readFrom(buf: PacketBuffer): T
}

/**
 * 已读同步通知 payload。
 * 客户端 B 收到 READ_SYNC 后：peerUid 已读到 peerReadSeq。
 */
data class ReadSyncPayload(
    val peerUid: String,
    val chatId: String,
    val peerReadSeq: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(peerUid)
        buf.writeString(chatId)
        buf.writeVarLong(peerReadSeq)
    }

    companion object : IProtoReader<ReadSyncPayload> {
        override fun readFrom(buf: PacketBuffer): ReadSyncPayload {
            val peerUid = buf.readString() ?: ""
            val chatId = buf.readString() ?: ""
            val peerReadSeq = buf.readVarLong()
            return ReadSyncPayload(peerUid, chatId, peerReadSeq)
        }
    }
}
