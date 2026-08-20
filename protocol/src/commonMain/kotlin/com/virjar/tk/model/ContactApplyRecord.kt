package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 当前用户视角的好友申请记录。
 *
 * 这是独立于旧 [ContactApply] 的追加契约，避免给 V8 已发布的固定字段模型追加字段。
 * [token] 只会在“收到且待处理”时返回；发出申请及已处理记录始终为 null。
 */
@Serializable
data class ContactApplyRecord(
    val id: Long,
    val fromUid: String,
    val toUid: String,
    val direction: Int,
    val token: String? = null,
    val remark: String? = null,
    val status: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val peerUser: User? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(id)
        buf.writeString(fromUid)
        buf.writeString(toUid)
        buf.writeVarInt(direction)
        buf.writeString(token)
        buf.writeString(remark)
        buf.writeVarInt(status)
        buf.writeVarLong(createdAt)
        buf.writeVarLong(updatedAt)
        buf.writeByte(if (peerUser != null) 1 else 0)
        peerUser?.writeTo(buf)
    }

    companion object : IProtoReader<ContactApplyRecord> {
        const val DIRECTION_INCOMING = 1
        const val DIRECTION_OUTGOING = 2

        const val STATUS_PENDING = 0
        const val STATUS_ACCEPTED = 1
        const val STATUS_REJECTED = 2
        /** 旧客户端重复创建的同方向 pending 已被最新一条取代。 */
        const val STATUS_SUPERSEDED = 3

        override fun readFrom(buf: PacketBuffer): ContactApplyRecord = ContactApplyRecord(
            id = buf.readVarLong(),
            fromUid = buf.readString()!!,
            toUid = buf.readString()!!,
            direction = buf.readVarInt(),
            token = buf.readString(),
            remark = buf.readString(),
            status = buf.readVarInt(),
            createdAt = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
            peerUser = if (buf.readPresenceFlag("contact apply peer user")) User.readFrom(buf) else null,
        )
    }
}

/** Optional-result wrapper because RPC payloads require an explicit presence byte for IProto. */
@Serializable
data class ContactApplyLookup(
    val record: ContactApplyRecord? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeByte(if (record != null) 1 else 0)
        record?.writeTo(buf)
    }

    companion object : IProtoReader<ContactApplyLookup> {
        override fun readFrom(buf: PacketBuffer): ContactApplyLookup = ContactApplyLookup(
            record = if (buf.readPresenceFlag("contact apply lookup")) ContactApplyRecord.readFrom(buf) else null,
        )
    }
}
