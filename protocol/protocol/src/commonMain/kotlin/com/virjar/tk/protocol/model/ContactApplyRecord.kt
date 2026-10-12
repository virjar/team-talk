package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable
import com.virjar.tk.protocol.WireCoded

/** 好友申请方向；未知取值承载为 [ContactApplyDirection.Unknown]。 */
@Serializable(with = ContactApplyDirection.CodeSerializer::class)
sealed interface ContactApplyDirection : WireCoded {
    data object INCOMING : ContactApplyDirection {
        override val code = 1
    }
    data object OUTGOING : ContactApplyDirection {
        override val code = 2
    }

    data class Unknown(override val code: Int) : ContactApplyDirection

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<ContactApplyDirection>("ContactApplyDirection") {
        override fun fromCode(code: Int) = ContactApplyDirection.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): ContactApplyDirection = when (code) {
            INCOMING.code -> INCOMING
            OUTGOING.code -> OUTGOING
            else -> Unknown(code)
        }
    }
}

/**
 * 好友申请处理状态；与 friend_applies 表 status 列共用值空间。
 * 未知取值承载为 [ContactApplyStatus.Unknown]，按已终结处理。
 */
@Serializable(with = ContactApplyStatus.CodeSerializer::class)
sealed interface ContactApplyStatus : WireCoded {
    data object PENDING : ContactApplyStatus {
        override val code = 0
    }
    data object ACCEPTED : ContactApplyStatus {
        override val code = 1
    }
    data object REJECTED : ContactApplyStatus {
        override val code = 2
    }

    data class Unknown(override val code: Int) : ContactApplyStatus

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<ContactApplyStatus>("ContactApplyStatus") {
        override fun fromCode(code: Int) = ContactApplyStatus.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): ContactApplyStatus = when (code) {
            PENDING.code -> PENDING
            ACCEPTED.code -> ACCEPTED
            REJECTED.code -> REJECTED
            else -> Unknown(code)
        }
    }
}

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
    val direction: ContactApplyDirection,
    val token: String? = null,
    val remark: String? = null,
    val status: ContactApplyStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val peerUser: User? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(id)
        buf.writeString(fromUid)
        buf.writeString(toUid)
        buf.writeVarInt(direction.code)
        buf.writeString(token)
        buf.writeString(remark)
        buf.writeVarInt(status.code)
        buf.writeVarLong(createdAt)
        buf.writeVarLong(updatedAt)
        buf.writeBoolean(peerUser != null)
        peerUser?.writeTo(buf)
    }

    companion object : IProtoReader<ContactApplyRecord> {
        override fun readFrom(buf: PacketBuffer): ContactApplyRecord = ContactApplyRecord(
            id = buf.readVarLong(),
            fromUid = buf.readRequiredString(),
            toUid = buf.readRequiredString(),
            direction = ContactApplyDirection.fromCode(buf.readVarInt()),
            token = buf.readString(),
            remark = buf.readString(),
            status = ContactApplyStatus.fromCode(buf.readVarInt()),
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
