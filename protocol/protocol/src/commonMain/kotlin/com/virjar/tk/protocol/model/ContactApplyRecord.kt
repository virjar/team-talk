package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 当前用户视角的好友申请记录。
 *
 * 与 [ContactApply] 分离：前者是处理动作的定向投影，本模型是包含方向和历史状态的查询投影。
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
        buf.writeBoolean(peerUser != null)
        peerUser?.writeTo(buf)
    }

    companion object : IProtoReader<ContactApplyRecord> {
        const val DIRECTION_INCOMING = 1
        const val DIRECTION_OUTGOING = 2

        const val STATUS_PENDING = 0
        const val STATUS_ACCEPTED = 1
        const val STATUS_REJECTED = 2

        override fun readFrom(buf: PacketBuffer): ContactApplyRecord = ContactApplyRecord(
            id = buf.readVarLong(),
            fromUid = buf.readRequiredString(),
            toUid = buf.readRequiredString(),
            direction = buf.readVarInt(),
            token = buf.readString(),
            remark = buf.readString(),
            status = buf.readVarInt(),
            createdAt = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
            peerUser = if (buf.readBoolean("contact apply peer user presence")) User.readFrom(buf) else null,
        )
    }
}

/** 可选结果包装器，因为 RPC payload 要求 IProto 有显式的存在字节。 */
@Serializable
data class ContactApplyLookup(
    val record: ContactApplyRecord? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeBoolean(record != null)
        record?.writeTo(buf)
    }

    companion object : IProtoReader<ContactApplyLookup> {
        override fun readFrom(buf: PacketBuffer): ContactApplyLookup = ContactApplyLookup(
            record = if (buf.readBoolean("contact apply lookup presence")) ContactApplyRecord.readFrom(buf) else null,
        )
    }
}
