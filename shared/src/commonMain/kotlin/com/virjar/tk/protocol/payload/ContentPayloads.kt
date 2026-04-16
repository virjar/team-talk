package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf
import kotlinx.serialization.json.*

// ============================================================
// 内容消息 Body（PacketType 20-28, 33-36，双向统一）
// ============================================================

/**
 * TEXT(20) 文本消息
 */
class TextBody(
    val text: String,
    val mentionUids: List<String>,
) : MessageBody {
    override val packetType = PacketType.TEXT

    constructor(buf: ByteBuf) : this(
        text = IProto.readString(buf)!!,
        mentionUids = IProto.readStringList(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, text)
        IProto.writeStringList(buf, mentionUids)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("text", text)
        put("mentionUids", JsonArray(mentionUids.map { JsonPrimitive(it) }))
    }

    companion object : MessageBodyCreator<TextBody> {
        override fun create(buf: ByteBuf) = TextBody(buf)

        fun fromJson(json: JsonObject): TextBody {
            return TextBody(
                text = json["text"]?.jsonPrimitive?.content ?: "",
                mentionUids = json["mentionUids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }
    }
}

/**
 * IMAGE(21) 图片消息
 */
class ImageBody(
    val url: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val thumbnailUrl: String?,
    val caption: String?,
) : MessageBody {
    override val packetType = PacketType.IMAGE

    constructor(buf: ByteBuf) : this(
        url = IProto.readString(buf)!!,
        width = IProto.readVarInt(buf).toInt(),
        height = IProto.readVarInt(buf).toInt(),
        size = IProto.readVarInt(buf),
        thumbnailUrl = IProto.readString(buf),
        caption = IProto.readString(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, url)
        IProto.writeVarInt(buf, width.toLong())
        IProto.writeVarInt(buf, height.toLong())
        IProto.writeVarInt(buf, size)
        IProto.writeString(buf, thumbnailUrl)
        IProto.writeString(buf, caption)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("width", width)
        put("height", height)
        put("size", size)
        if (thumbnailUrl != null) put("thumbnailUrl", thumbnailUrl)
        if (caption != null) put("caption", caption)
    }

    companion object : MessageBodyCreator<ImageBody> {
        override fun create(buf: ByteBuf) = ImageBody(buf)

        fun fromJson(json: JsonObject): ImageBody {
            return ImageBody(
                url = json["url"]?.jsonPrimitive?.content ?: "",
                width = json["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = json["height"]?.jsonPrimitive?.intOrNull ?: 0,
                size = json["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                thumbnailUrl = json["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
                caption = json["caption"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/**
 * VOICE(22) 语音消息
 */
class VoiceBody(
    val url: String,
    val duration: Int,
    val size: Long,
    val waveform: ByteArray?,
) : MessageBody {
    override val packetType = PacketType.VOICE

    constructor(buf: ByteBuf) : this(
        url = IProto.readString(buf)!!,
        duration = IProto.readVarInt(buf).toInt(),
        size = IProto.readVarInt(buf),
        waveform = IProto.readByteArray(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, url)
        IProto.writeVarInt(buf, duration.toLong())
        IProto.writeVarInt(buf, size)
        IProto.writeByteArray(buf, waveform)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("duration", duration)
        put("size", size)
        if (waveform != null) put("waveform", java.util.Base64.getEncoder().encodeToString(waveform))
    }

    companion object : MessageBodyCreator<VoiceBody> {
        override fun create(buf: ByteBuf) = VoiceBody(buf)

        fun fromJson(json: JsonObject): VoiceBody {
            return VoiceBody(
                url = json["url"]?.jsonPrimitive?.content ?: "",
                duration = json["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                size = json["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                waveform = json["waveform"]?.jsonPrimitive?.contentOrNull?.let { java.util.Base64.getDecoder().decode(it) },
            )
        }
    }
}

/**
 * VIDEO(23) 视频消息
 */
class VideoBody(
    val url: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val duration: Int,
    val coverUrl: String?,
) : MessageBody {
    override val packetType = PacketType.VIDEO

    constructor(buf: ByteBuf) : this(
        url = IProto.readString(buf)!!,
        width = IProto.readVarInt(buf).toInt(),
        height = IProto.readVarInt(buf).toInt(),
        size = IProto.readVarInt(buf),
        duration = IProto.readVarInt(buf).toInt(),
        coverUrl = IProto.readString(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, url)
        IProto.writeVarInt(buf, width.toLong())
        IProto.writeVarInt(buf, height.toLong())
        IProto.writeVarInt(buf, size)
        IProto.writeVarInt(buf, duration.toLong())
        IProto.writeString(buf, coverUrl)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("width", width)
        put("height", height)
        put("size", size)
        put("duration", duration)
        if (coverUrl != null) put("coverUrl", coverUrl)
    }

    companion object : MessageBodyCreator<VideoBody> {
        override fun create(buf: ByteBuf) = VideoBody(buf)

        fun fromJson(json: JsonObject): VideoBody {
            return VideoBody(
                url = json["url"]?.jsonPrimitive?.content ?: "",
                width = json["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = json["height"]?.jsonPrimitive?.intOrNull ?: 0,
                size = json["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                duration = json["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                coverUrl = json["coverUrl"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/**
 * FILE(24) 文件消息
 */
class FileBody(
    val url: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val thumbnailUrl: String?,
) : MessageBody {
    override val packetType = PacketType.FILE

    constructor(buf: ByteBuf) : this(
        url = IProto.readString(buf)!!,
        fileName = IProto.readString(buf)!!,
        fileSize = IProto.readVarInt(buf),
        mimeType = IProto.readString(buf),
        thumbnailUrl = IProto.readString(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, url)
        IProto.writeString(buf, fileName)
        IProto.writeVarInt(buf, fileSize)
        IProto.writeString(buf, mimeType)
        IProto.writeString(buf, thumbnailUrl)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("fileName", fileName)
        put("fileSize", fileSize)
        if (mimeType != null) put("mimeType", mimeType)
        if (thumbnailUrl != null) put("thumbnailUrl", thumbnailUrl)
    }

    companion object : MessageBodyCreator<FileBody> {
        override fun create(buf: ByteBuf) = FileBody(buf)

        fun fromJson(json: JsonObject): FileBody {
            return FileBody(
                url = json["url"]?.jsonPrimitive?.content ?: "",
                fileName = json["fileName"]?.jsonPrimitive?.content ?: "",
                fileSize = json["fileSize"]?.jsonPrimitive?.longOrNull ?: 0L,
                mimeType = json["mimeType"]?.jsonPrimitive?.contentOrNull,
                thumbnailUrl = json["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/**
 * LOCATION(25) 位置消息（占位）
 */
class LocationBody(
    val latitude: Double,
    val longitude: Double,
    val title: String?,
    val address: String?,
) : MessageBody {
    override val packetType = PacketType.LOCATION

    constructor(buf: ByteBuf) : this(
        latitude = buf.readDouble(),
        longitude = buf.readDouble(),
        title = IProto.readString(buf),
        address = IProto.readString(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        buf.writeDouble(latitude)
        buf.writeDouble(longitude)
        IProto.writeString(buf, title)
        IProto.writeString(buf, address)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("latitude", latitude)
        put("longitude", longitude)
        if (title != null) put("title", title)
        if (address != null) put("address", address)
    }

    companion object : MessageBodyCreator<LocationBody> {
        override fun create(buf: ByteBuf) = LocationBody(buf)

        fun fromJson(json: JsonObject): LocationBody {
            return LocationBody(
                latitude = json["latitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                longitude = json["longitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                title = json["title"]?.jsonPrimitive?.contentOrNull,
                address = json["address"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/**
 * CARD(26) 名片消息
 */
class CardBody(
    val uid: String,
    val name: String,
    val avatar: String?,
    val phone: String?,
) : MessageBody {
    override val packetType = PacketType.CARD

    constructor(buf: ByteBuf) : this(
        uid = IProto.readString(buf)!!,
        name = IProto.readString(buf)!!,
        avatar = IProto.readString(buf),
        phone = IProto.readString(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, uid)
        IProto.writeString(buf, name)
        IProto.writeString(buf, avatar)
        IProto.writeString(buf, phone)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("uid", uid)
        put("name", name)
        if (avatar != null) put("avatar", avatar)
        if (phone != null) put("phone", phone)
    }

    companion object : MessageBodyCreator<CardBody> {
        override fun create(buf: ByteBuf) = CardBody(buf)

        fun fromJson(json: JsonObject): CardBody {
            return CardBody(
                uid = json["uid"]?.jsonPrimitive?.content ?: "",
                name = json["name"]?.jsonPrimitive?.content ?: "",
                avatar = json["avatar"]?.jsonPrimitive?.contentOrNull,
                phone = json["phone"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/**
 * STICKER(33) 表情贴纸消息
 */
class StickerBody(
    val stickerId: String,
    val packId: String?,
    val url: String,
    val emoji: String?,
    val width: Int,
    val height: Int,
) : MessageBody {
    override val packetType = PacketType.STICKER

    constructor(buf: ByteBuf) : this(
        stickerId = IProto.readString(buf)!!,
        packId = IProto.readString(buf),
        url = IProto.readString(buf)!!,
        emoji = IProto.readString(buf),
        width = IProto.readVarInt(buf).toInt(),
        height = IProto.readVarInt(buf).toInt(),
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, stickerId)
        IProto.writeString(buf, packId)
        IProto.writeString(buf, url)
        IProto.writeString(buf, emoji)
        IProto.writeVarInt(buf, width.toLong())
        IProto.writeVarInt(buf, height.toLong())
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("stickerId", stickerId)
        if (packId != null) put("packId", packId)
        put("url", url)
        if (emoji != null) put("emoji", emoji)
        put("width", width)
        put("height", height)
    }

    companion object : MessageBodyCreator<StickerBody> {
        override fun create(buf: ByteBuf) = StickerBody(buf)

        fun fromJson(json: JsonObject): StickerBody {
            return StickerBody(
                stickerId = json["stickerId"]?.jsonPrimitive?.content ?: "",
                packId = json["packId"]?.jsonPrimitive?.contentOrNull,
                url = json["url"]?.jsonPrimitive?.content ?: "",
                emoji = json["emoji"]?.jsonPrimitive?.contentOrNull,
                width = json["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = json["height"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}

/**
 * INTERACTIVE(35) 交互式卡片消息（Bot）
 */
class InteractiveBody(
    val botId: String,
    val templateType: Byte,
    val title: String,
    val content: String?,
    val imageUrl: String?,
    val buttons: List<InteractiveButton>,
) : MessageBody {
    override val packetType = PacketType.INTERACTIVE

    constructor(buf: ByteBuf) : this(
        botId = IProto.readString(buf)!!,
        templateType = buf.readByte(),
        title = IProto.readString(buf)!!,
        content = IProto.readString(buf),
        imageUrl = IProto.readString(buf),
        buttons = (0 until buf.readShort().toInt()).map {
            InteractiveButton(
                label = IProto.readString(buf)!!,
                action = buf.readByte(),
                value = IProto.readString(buf),
            )
        },
    )

    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, botId)
        buf.writeByte(templateType.toInt())
        IProto.writeString(buf, title)
        IProto.writeString(buf, content)
        IProto.writeString(buf, imageUrl)
        buf.writeShort(buttons.size)
        buttons.forEach { btn ->
            IProto.writeString(buf, btn.label)
            buf.writeByte(btn.action.toInt())
            IProto.writeString(buf, btn.value)
        }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("botId", botId)
        put("templateType", templateType.toInt())
        put("title", title)
        if (content != null) put("content", content)
        if (imageUrl != null) put("imageUrl", imageUrl)
        put("buttons", JsonArray(buttons.map { btn -> buildJsonObject {
            put("label", btn.label)
            put("action", btn.action.toInt())
            if (btn.value != null) put("value", btn.value)
        }}))
    }

    companion object : MessageBodyCreator<InteractiveBody> {
        override fun create(buf: ByteBuf) = InteractiveBody(buf)

        fun fromJson(json: JsonObject): InteractiveBody {
            return InteractiveBody(
                botId = json["botId"]?.jsonPrimitive?.content ?: "",
                templateType = json["templateType"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
                title = json["title"]?.jsonPrimitive?.content ?: "",
                content = json["content"]?.jsonPrimitive?.contentOrNull,
                imageUrl = json["imageUrl"]?.jsonPrimitive?.contentOrNull,
                buttons = json["buttons"]?.jsonArray?.map { btn ->
                    val obj = btn.jsonObject
                    InteractiveButton(
                        label = obj["label"]?.jsonPrimitive?.content ?: "",
                        action = obj["action"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
                        value = obj["value"]?.jsonPrimitive?.contentOrNull,
                    )
                } ?: emptyList(),
            )
        }
    }
}

data class InteractiveButton(
    val label: String,
    val action: Byte,     // 0=callback, 1=url
    val value: String?,
)

/**
 * RICH(36) 富文本消息
 */
class RichBody(
    val segments: List<RichSegment>,
    val mentionUids: List<String>,
) : MessageBody {
    override val packetType = PacketType.RICH

    constructor(buf: ByteBuf) : this(
        segments = (0 until buf.readShort().toInt()).map {
            RichSegment(
                type = buf.readByte(),
                text = IProto.readString(buf),
                href = IProto.readString(buf),
            )
        },
        mentionUids = IProto.readStringList(buf),
    )

    override fun writeTo(buf: ByteBuf) {
        buf.writeShort(segments.size)
        segments.forEach { seg ->
            buf.writeByte(seg.type.toInt())
            IProto.writeString(buf, seg.text)
            IProto.writeString(buf, seg.href)
        }
        IProto.writeStringList(buf, mentionUids)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("segments", JsonArray(segments.map { seg -> buildJsonObject {
            put("type", seg.type.toInt())
            if (seg.text != null) put("text", seg.text)
            if (seg.href != null) put("href", seg.href)
        }}))
        put("mentionUids", JsonArray(mentionUids.map { JsonPrimitive(it) }))
    }

    companion object : MessageBodyCreator<RichBody> {
        override fun create(buf: ByteBuf) = RichBody(buf)

        fun fromJson(json: JsonObject): RichBody {
            return RichBody(
                segments = json["segments"]?.jsonArray?.map { seg ->
                    val obj = seg.jsonObject
                    RichSegment(
                        type = obj["type"]?.jsonPrimitive?.intOrNull?.toByte() ?: 0,
                        text = obj["text"]?.jsonPrimitive?.contentOrNull,
                        href = obj["href"]?.jsonPrimitive?.contentOrNull,
                    )
                } ?: emptyList(),
                mentionUids = json["mentionUids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }
    }
}

data class RichSegment(
    val type: Byte,     // 0=text, 1=bold, 2=italic, 3=link, 4=code
    val text: String?,
    val href: String?,
)
