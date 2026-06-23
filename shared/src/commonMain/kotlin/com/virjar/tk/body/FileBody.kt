package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class FileBody(
    val url: String,
    val fileName: String,
    val size: Long = 0,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(url)
        buf.writeString(fileName)
        buf.writeVarLong(size)
    }

    companion object : IProtoReader<FileBody> {
        override fun readFrom(buf: PacketBuffer) = FileBody(
            url = buf.readString()!!,
            fileName = buf.readString()!!,
            size = buf.readVarLong(),
        )
    }
}

    data class VideoBody(
        val url: String,
        val duration: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val size: Long = 0,
        val thumbnailUrl: String? = null,
    ) : MessageBody {
        override fun writeTo(buf: PacketBuffer) {
            buf.writeString(url)
            buf.writeVarInt(duration)
            buf.writeVarInt(width)
            buf.writeVarInt(height)
            buf.writeVarLong(size)
            buf.writeString(thumbnailUrl)
        }

        companion object : IProtoReader<VideoBody> {
            override fun readFrom(buf: PacketBuffer) = VideoBody(
                url = buf.readString()!!,
                duration = buf.readVarInt(),
                width = buf.readVarInt(),
                height = buf.readVarInt(),
                size = buf.readVarLong(),
                thumbnailUrl = buf.readString(),
            )
        }
    }

data class LocationBody(
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
    val address: String? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        // 使用 String 传输 double 避免浮点精度问题
        buf.writeString(latitude.toString())
        buf.writeString(longitude.toString())
        buf.writeString(title)
        buf.writeString(address)
    }

    companion object : IProtoReader<LocationBody> {
        override fun readFrom(buf: PacketBuffer) = LocationBody(
            latitude = buf.readString()!!.toDouble(),
            longitude = buf.readString()!!.toDouble(),
            title = buf.readString(),
            address = buf.readString(),
        )
    }
}

data class CardBody(
    val targetUid: String,
    val targetName: String,
    val targetAvatar: String? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(targetUid)
        buf.writeString(targetName)
        buf.writeString(targetAvatar)
    }

    companion object : IProtoReader<CardBody> {
        override fun readFrom(buf: PacketBuffer) = CardBody(
            targetUid = buf.readString()!!,
            targetName = buf.readString()!!,
            targetAvatar = buf.readString(),
        )
    }
}

data class StickerBody(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(url)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
    }

    companion object : IProtoReader<StickerBody> {
        override fun readFrom(buf: PacketBuffer) = StickerBody(
            url = buf.readString()!!,
            width = buf.readVarInt(),
            height = buf.readVarInt(),
        )
    }
}
