package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 好友申请方向；wire 存 Int code，逻辑代码经 [ContactApplyRecord.directionValue] 比较。
 */
@com.virjar.tk.protocol.SinceProtocol(7)
enum class ContactApplyDirection(val code: Int) {
    INCOMING(1),
    OUTGOING(2);

    companion object {
        fun fromCode(code: Int): ContactApplyDirection? = entries.find { it.code == code }
    }
}

/**
 * 好友申请处理状态；与 friend_applies 表 status 列共用值空间。
 * 未知 code（未来版本）经 [ContactApplyRecord.statusValue] 映射为 null，按已终结处理。
 */
@com.virjar.tk.protocol.SinceProtocol(7)
enum class ContactApplyStatus(val code: Int) {
    PENDING(0),
    ACCEPTED(1),
    REJECTED(2);

    companion object {
        fun fromCode(code: Int): ContactApplyStatus? = entries.find { it.code == code }
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
    /** wire 仍为 Int code；逻辑代码使用 [directionValue] 枚举视图比较。 */
    val direction: Int,
    val token: String? = null,
    val remark: String? = null,
    /** wire 仍为 Int code；逻辑代码使用 [statusValue] 枚举视图比较。 */
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

/** 申请方向的类型化视图；null = 未知取值（未来版本）。 */
val ContactApplyRecord.directionValue: ContactApplyDirection?
    get() = ContactApplyDirection.fromCode(direction)

/** 申请状态的类型化视图；null = 未知取值，按已终结处理。 */
val ContactApplyRecord.statusValue: ContactApplyStatus?
    get() = ContactApplyStatus.fromCode(status)

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
