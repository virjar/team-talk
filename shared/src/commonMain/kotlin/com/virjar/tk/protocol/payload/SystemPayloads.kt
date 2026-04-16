package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf

// ============================================================
// 系统消息 Payload（PacketType 90-98，仅 Server → Client）
// ============================================================

/**
 * CHANNEL_CREATED(90) 频道创建
 */
data class ChannelCreatedPayload(
    val channelId: String,
    val channelType: ChannelType,
    val channelName: String,
    val creatorUid: String,
) : IProto {
    override val packetType = PacketType.CHANNEL_CREATED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        buf.writeByte(channelType.code)
        IProto.writeString(buf, channelName)
        IProto.writeString(buf, creatorUid)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        channelType = ChannelType.fromCode(buf.readByte().toInt()),
        channelName = IProto.readString(buf)!!,
        creatorUid = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<ChannelCreatedPayload> {
        override fun create(buf: ByteBuf) = ChannelCreatedPayload(buf)
    }
}

/**
 * CHANNEL_UPDATED(91) 频道更新
 */
data class ChannelUpdatedPayload(
    val channelId: String,
    val channelType: ChannelType,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val operatorUid: String,
) : IProto {
    override val packetType = PacketType.CHANNEL_UPDATED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        buf.writeByte(channelType.code)
        IProto.writeString(buf, field)
        IProto.writeString(buf, oldValue)
        IProto.writeString(buf, newValue)
        IProto.writeString(buf, operatorUid)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        channelType = ChannelType.fromCode(buf.readByte().toInt()),
        field = IProto.readString(buf)!!,
        oldValue = IProto.readString(buf),
        newValue = IProto.readString(buf),
        operatorUid = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<ChannelUpdatedPayload> {
        override fun create(buf: ByteBuf) = ChannelUpdatedPayload(buf)
    }
}

/**
 * CHANNEL_DELETED(92) 频道删除
 */
data class ChannelDeletedPayload(
    val channelId: String,
    val channelType: ChannelType,
    val operatorUid: String,
) : IProto {
    override val packetType = PacketType.CHANNEL_DELETED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        buf.writeByte(channelType.code)
        IProto.writeString(buf, operatorUid)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        channelType = ChannelType.fromCode(buf.readByte().toInt()),
        operatorUid = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<ChannelDeletedPayload> {
        override fun create(buf: ByteBuf) = ChannelDeletedPayload(buf)
    }
}

/**
 * MEMBER_ADDED(93) 成员加入
 */
data class MemberAddedPayload(
    val channelId: String,
    val channelType: ChannelType,
    val memberUid: String,
    val memberName: String?,
    val inviterUid: String,
) : IProto {
    override val packetType = PacketType.MEMBER_ADDED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        buf.writeByte(channelType.code)
        IProto.writeString(buf, memberUid)
        IProto.writeString(buf, memberName)
        IProto.writeString(buf, inviterUid)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        channelType = ChannelType.fromCode(buf.readByte().toInt()),
        memberUid = IProto.readString(buf)!!,
        memberName = IProto.readString(buf),
        inviterUid = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<MemberAddedPayload> {
        override fun create(buf: ByteBuf) = MemberAddedPayload(buf)
    }
}

/**
 * MEMBER_REMOVED(94) 成员移除
 */
data class MemberRemovedPayload(
    val channelId: String,
    val channelType: ChannelType,
    val memberUid: String,
    val memberName: String?,
    val operatorUid: String,
    val reason: Byte, // 0=主动退出, 1=被踢出
) : IProto {
    override val packetType = PacketType.MEMBER_REMOVED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        buf.writeByte(channelType.code)
        IProto.writeString(buf, memberUid)
        IProto.writeString(buf, memberName)
        IProto.writeString(buf, operatorUid)
        buf.writeByte(reason.toInt())
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        channelType = ChannelType.fromCode(buf.readByte().toInt()),
        memberUid = IProto.readString(buf)!!,
        memberName = IProto.readString(buf),
        operatorUid = IProto.readString(buf)!!,
        reason = buf.readByte(),
    )

    companion object : IProtoCreator<MemberRemovedPayload> {
        override fun create(buf: ByteBuf) = MemberRemovedPayload(buf)
    }
}

/**
 * MEMBER_MUTED(95) 成员禁言
 */
data class MemberMutedPayload(
    val channelId: String,
    val memberUid: String,
    val operatorUid: String,
    val duration: Long, // 禁言时长（秒），0=永久
) : IProto {
    override val packetType = PacketType.MEMBER_MUTED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, memberUid)
        IProto.writeString(buf, operatorUid)
        IProto.writeVarInt(buf, duration)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        memberUid = IProto.readString(buf)!!,
        operatorUid = IProto.readString(buf)!!,
        duration = IProto.readVarInt(buf),
    )

    companion object : IProtoCreator<MemberMutedPayload> {
        override fun create(buf: ByteBuf) = MemberMutedPayload(buf)
    }
}

/**
 * MEMBER_UNMUTED(96) 解除禁言
 */
data class MemberUnmutedPayload(
    val channelId: String,
    val memberUid: String,
    val operatorUid: String,
) : IProto {
    override val packetType = PacketType.MEMBER_UNMUTED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, memberUid)
        IProto.writeString(buf, operatorUid)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        memberUid = IProto.readString(buf)!!,
        operatorUid = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<MemberUnmutedPayload> {
        override fun create(buf: ByteBuf) = MemberUnmutedPayload(buf)
    }
}

/**
 * MEMBER_ROLE_CHANGED(97) 成员角色变更
 */
data class MemberRoleChangedPayload(
    val channelId: String,
    val memberUid: String,
    val operatorUid: String,
    val oldRole: Byte, // 0=普通成员, 1=管理员, 2=群主
    val newRole: Byte,
) : IProto {
    override val packetType = PacketType.MEMBER_ROLE_CHANGED
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, memberUid)
        IProto.writeString(buf, operatorUid)
        buf.writeByte(oldRole.toInt())
        buf.writeByte(newRole.toInt())
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        memberUid = IProto.readString(buf)!!,
        operatorUid = IProto.readString(buf)!!,
        oldRole = buf.readByte(),
        newRole = buf.readByte(),
    )

    companion object : IProtoCreator<MemberRoleChangedPayload> {
        override fun create(buf: ByteBuf) = MemberRoleChangedPayload(buf)
    }
}

/**
 * CHANNEL_ANNOUNCEMENT(98) 群公告
 */
data class ChannelAnnouncementPayload(
    val channelId: String,
    val content: String,
    val operatorUid: String,
) : IProto {
    override val packetType = PacketType.CHANNEL_ANNOUNCEMENT
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, content)
        IProto.writeString(buf, operatorUid)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        content = IProto.readString(buf)!!,
        operatorUid = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<ChannelAnnouncementPayload> {
        override fun create(buf: ByteBuf) = ChannelAnnouncementPayload(buf)
    }
}
