package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf
import kotlinx.serialization.json.*

// ============================================================
// 操作消息 Body（PacketType 27-34，双向统一）
// ============================================================

/**
 * REPLY(27) 回复消息
 */
class ReplyBody(
    val replyToMessageId: String,
    val replyToSenderUid: String,
    val replyToSenderName: String?,
    val replyToPacketType: Byte,
    val text: String,
    val mentionUids: List<String>,
) : MessageBody {
    override val packetType = PacketType.REPLY

    constructor(buf: ByteBuf) : this(
        replyToMessageId = IProto.readString(buf)!!,
        replyToSenderUid = IProto.readString(buf)!!,
        replyToSenderName = IProto.readString(buf),
        replyToPacketType = buf.readByte(),
        text = IProto.readString(buf)!!,
        mentionUids = IProto.readStringList(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, replyToMessageId)
        IProto.writeString(buf, replyToSenderUid)
        IProto.writeString(buf, replyToSenderName)
        buf.writeByte(replyToPacketType.toInt())
        IProto.writeString(buf, text)
        IProto.writeStringList(buf, mentionUids)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("replyToMessageId", replyToMessageId)
        put("replyToSenderUid", replyToSenderUid)
        if (replyToSenderName != null) put("replyToSenderName", replyToSenderName)
        put("replyToPacketType", replyToPacketType.toInt())
        put("text", text)
        put("mentionUids", JsonArray(mentionUids.map { JsonPrimitive(it) }))
    }

    companion object : MessageBodyCreator<ReplyBody> {
        override fun create(buf: ByteBuf) = ReplyBody(buf)

        fun fromJson(json: JsonObject): ReplyBody {
            return ReplyBody(
                replyToMessageId = json["replyToMessageId"]?.jsonPrimitive?.content ?: "",
                replyToSenderUid = json["replyToSenderUid"]?.jsonPrimitive?.content ?: "",
                replyToSenderName = json["replyToSenderName"]?.jsonPrimitive?.contentOrNull,
                replyToPacketType = json["replyToPacketType"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
                text = json["text"]?.jsonPrimitive?.content ?: "",
                mentionUids = json["mentionUids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }
    }
}

/**
 * FORWARD(28) 转发消息
 * forwardPayload 存储 body JSON 字符串（与整体存储模型一致）。
 */
class ForwardBody(
    val forwardFromChannelId: String?,
    val forwardFromMessageId: String,
    val forwardFromSenderUid: String?,
    val forwardFromSenderName: String?,
    val forwardPacketType: Byte,
    val forwardPayload: String?,
) : MessageBody {
    override val packetType = PacketType.FORWARD

    constructor(buf: ByteBuf) : this(
        forwardFromChannelId = IProto.readString(buf),
        forwardFromMessageId = IProto.readString(buf)!!,
        forwardFromSenderUid = IProto.readString(buf),
        forwardFromSenderName = IProto.readString(buf),
        forwardPacketType = buf.readByte(),
        forwardPayload = IProto.readString(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, forwardFromChannelId)
        IProto.writeString(buf, forwardFromMessageId)
        IProto.writeString(buf, forwardFromSenderUid)
        IProto.writeString(buf, forwardFromSenderName)
        buf.writeByte(forwardPacketType.toInt())
        IProto.writeString(buf, forwardPayload)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        if (forwardFromChannelId != null) put("forwardFromChannelId", forwardFromChannelId)
        put("forwardFromMessageId", forwardFromMessageId)
        if (forwardFromSenderUid != null) put("forwardFromSenderUid", forwardFromSenderUid)
        if (forwardFromSenderName != null) put("forwardFromSenderName", forwardFromSenderName)
        put("forwardPacketType", forwardPacketType.toInt())
        if (forwardPayload != null) put("forwardPayload", forwardPayload)
    }

    companion object : MessageBodyCreator<ForwardBody> {
        override fun create(buf: ByteBuf) = ForwardBody(buf)

        fun fromJson(json: JsonObject): ForwardBody {
            return ForwardBody(
                forwardFromChannelId = json["forwardFromChannelId"]?.jsonPrimitive?.contentOrNull,
                forwardFromMessageId = json["forwardFromMessageId"]?.jsonPrimitive?.content ?: "",
                forwardFromSenderUid = json["forwardFromSenderUid"]?.jsonPrimitive?.contentOrNull,
                forwardFromSenderName = json["forwardFromSenderName"]?.jsonPrimitive?.contentOrNull,
                forwardPacketType = json["forwardPacketType"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
                forwardPayload = json["forwardPayload"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/**
 * MERGE_FORWARD(29) 合并转发消息
 * MergeForwardMessage.content 存储 body JSON 字符串。
 */
class MergeForwardBody(
    val title: String?,
    val messages: List<MergeForwardMessage>,
    val users: List<MergeForwardUser>,
) : MessageBody {
    override val packetType = PacketType.MERGE_FORWARD

    constructor(buf: ByteBuf) : this(
        title = IProto.readString(buf),
        messages = (0 until buf.readShort().toInt()).map {
            MergeForwardMessage(
                messageId = IProto.readString(buf),
                fromUid = IProto.readString(buf),
                timestamp = IProto.readVarInt(buf),
                packetType = buf.readByte(),
                content = IProto.readString(buf),
            )
        },
        users = (0 until buf.readShort().toInt()).map {
            MergeForwardUser(
                uid = IProto.readString(buf)!!,
                name = IProto.readString(buf)!!,
                avatar = IProto.readString(buf),
            )
        },
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, title)
        buf.writeShort(messages.size)
        messages.forEach { msg ->
            IProto.writeString(buf, msg.messageId)
            IProto.writeString(buf, msg.fromUid)
            IProto.writeVarInt(buf, msg.timestamp)
            buf.writeByte(msg.packetType.toInt())
            IProto.writeString(buf, msg.content)
        }
        buf.writeShort(users.size)
        users.forEach { user ->
            IProto.writeString(buf, user.uid)
            IProto.writeString(buf, user.name)
            IProto.writeString(buf, user.avatar)
        }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        if (title != null) put("title", title)
        put("messages", JsonArray(messages.map { msg -> buildJsonObject {
            if (msg.messageId != null) put("messageId", msg.messageId)
            if (msg.fromUid != null) put("fromUid", msg.fromUid)
            put("timestamp", msg.timestamp)
            put("packetType", msg.packetType.toInt())
            if (msg.content != null) put("content", msg.content)
        }}))
        put("users", JsonArray(users.map { user -> buildJsonObject {
            put("uid", user.uid)
            put("name", user.name)
            if (user.avatar != null) put("avatar", user.avatar)
        }}))
    }

    companion object : MessageBodyCreator<MergeForwardBody> {
        override fun create(buf: ByteBuf) = MergeForwardBody(buf)

        fun fromJson(json: JsonObject): MergeForwardBody {
            return MergeForwardBody(
                title = json["title"]?.jsonPrimitive?.contentOrNull,
                messages = json["messages"]?.jsonArray?.map { msg ->
                    val obj = msg.jsonObject
                    MergeForwardMessage(
                        messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull,
                        fromUid = obj["fromUid"]?.jsonPrimitive?.contentOrNull,
                        timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                        packetType = obj["packetType"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
                        content = obj["content"]?.jsonPrimitive?.contentOrNull,
                    )
                } ?: emptyList(),
                users = json["users"]?.jsonArray?.map { user ->
                    val obj = user.jsonObject
                    MergeForwardUser(
                        uid = obj["uid"]?.jsonPrimitive?.content ?: "",
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        avatar = obj["avatar"]?.jsonPrimitive?.contentOrNull,
                    )
                } ?: emptyList(),
            )
        }
    }
}

data class MergeForwardMessage(
    val messageId: String?,
    val fromUid: String?,
    val timestamp: Long,
    val packetType: Byte,
    val content: String?,
)

data class MergeForwardUser(
    val uid: String,
    val name: String,
    val avatar: String?,
)

/**
 * REVOKE(30) 撤回消息
 */
class RevokeBody(
    val targetMessageId: String,
) : MessageBody {
    override val packetType = PacketType.REVOKE

    constructor(buf: ByteBuf) : this(
        targetMessageId = IProto.readString(buf)!!,
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, targetMessageId)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("targetMessageId", targetMessageId)
    }

    companion object : MessageBodyCreator<RevokeBody> {
        override fun create(buf: ByteBuf) = RevokeBody(buf)

        fun fromJson(json: JsonObject): RevokeBody {
            return RevokeBody(
                targetMessageId = json["targetMessageId"]?.jsonPrimitive?.content ?: "",
            )
        }
    }
}

/**
 * EDIT(31) 编辑消息
 */
class EditBody(
    val targetMessageId: String,
    val newContent: String,
    val editedAt: Long,
) : MessageBody {
    override val packetType = PacketType.EDIT

    constructor(buf: ByteBuf) : this(
        targetMessageId = IProto.readString(buf)!!,
        newContent = IProto.readString(buf)!!,
        editedAt = IProto.readVarInt(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, targetMessageId)
        IProto.writeString(buf, newContent)
        IProto.writeVarInt(buf, editedAt)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("targetMessageId", targetMessageId)
        put("newContent", newContent)
        put("editedAt", editedAt)
    }

    companion object : MessageBodyCreator<EditBody> {
        override fun create(buf: ByteBuf) = EditBody(buf)

        fun fromJson(json: JsonObject): EditBody {
            return EditBody(
                targetMessageId = json["targetMessageId"]?.jsonPrimitive?.content ?: "",
                newContent = json["newContent"]?.jsonPrimitive?.content ?: "",
                editedAt = json["editedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }
}

/**
 * TYPING(32) 输入状态
 */
class TypingBody(
    val action: Byte,     // 0=text, 1=voice, 2=image, 3=file
) : MessageBody {
    override val packetType = PacketType.TYPING

    constructor(buf: ByteBuf) : this(
        action = buf.readByte(),
    )

    override fun writeTo(buf: ByteBuf) {
        buf.writeByte(action.toInt())
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("action", action.toInt())
    }

    companion object : MessageBodyCreator<TypingBody> {
        override fun create(buf: ByteBuf) = TypingBody(buf)

        fun fromJson(json: JsonObject): TypingBody {
            return TypingBody(
                action = json["action"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
            )
        }
    }
}

/**
 * REACTION(34) 表情回应
 */
class ReactionBody(
    val targetMessageId: String,
    val emoji: String,
    val remove: Boolean,
) : MessageBody {
    override val packetType = PacketType.REACTION

    constructor(buf: ByteBuf) : this(
        targetMessageId = IProto.readString(buf)!!,
        emoji = IProto.readString(buf)!!,
        remove = buf.readBoolean(),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, targetMessageId)
        IProto.writeString(buf, emoji)
        buf.writeBoolean(remove)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("targetMessageId", targetMessageId)
        put("emoji", emoji)
        put("remove", remove)
    }

    companion object : MessageBodyCreator<ReactionBody> {
        override fun create(buf: ByteBuf) = ReactionBody(buf)

        fun fromJson(json: JsonObject): ReactionBody {
            return ReactionBody(
                targetMessageId = json["targetMessageId"]?.jsonPrimitive?.content ?: "",
                emoji = json["emoji"]?.jsonPrimitive?.content ?: "",
                remove = json["remove"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}
