package com.virjar.tk.protocol

import io.netty.buffer.ByteBuf

/**
 * 所有需要二进制序列化的消息都实现此接口。
 * 字段按固定顺序读写，无需字段名或 tag。
 */
interface IProto {
    /** 此消息对应的 PacketType，用于编码时自动确定包类型 */
    val packetType: PacketType

    fun writeTo(buf: ByteBuf)

    companion object {
        /** 单个 Packet 最大载荷字节数（16 MiB） */
        const val MAX_PACKET_SIZE = 16 * 1024 * 1024
        val MAGIC = byteArrayOf(0x54, 0x4B, 0x50, 0x52, 0x4F, 0x54, 0x4F) // "TKPROTO"

        /**
         * 当前协议版本，目前原型阶段，暂时没有多协议版本兼容，所以握手固定1
         */
        const val VERSION: Byte = 1


        val MAGIC_WITH_VERSION = MAGIC.copyOf(MAGIC.size + 1).apply {
            this[MAGIC.size] = VERSION
        }

        /**
         * 服务端读空闲超时（秒）。超过此时间未收到任何数据将关闭连接。
         * 客户端应确保 PING 间隔小于此值（推荐不超过一半）。
         */
        const val IDLE_TIMEOUT_SECONDS = 60

        /**
         * 客户端写空闲超时（秒）。超过此时间未发送数据将触发 PING。
         * 必须小于 [IDLE_TIMEOUT_SECONDS] 以确保服务端不会因超时断开。
         */
        const val CLIENT_PING_INTERVAL_SECONDS = 30

        /**
         * 写入可空字符串：null 编码为 -1 (单字节 0xFF)；非 null 时用 VarInt 编码字节长度。
         * VarInt 最多 3 字节即可覆盖 16MB 上限（16,777,215）。
         */
        fun writeString(buf: ByteBuf, value: String?) {
            if (value == null) {
                buf.writeByte(0xFF)
                return
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeVarInt(buf, bytes.size.toLong())
            buf.writeBytes(bytes)
        }

        /**
         * 读取可空字符串。
         * @param maxLength 允许的最大字节长度，超过则抛 [IllegalArgumentException]。默认 [IProto.MAX_PACKET_SIZE]。
         */
        fun readString(buf: ByteBuf, maxLength: Int = MAX_PACKET_SIZE): String? {
            val first = buf.getByte(buf.readerIndex()).toInt() and 0xFF
            if (first == 0xFF) {
                buf.skipBytes(1)
                return null
            }
            val len = readVarInt(buf).toInt()
            require(len <= maxLength) { "String too long: $len > $maxLength" }
            val bytes = ByteArray(len)
            buf.readBytes(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun writeStringList(buf: ByteBuf, list: List<String>) {
            buf.writeShort(list.size)
            list.forEach { writeString(buf, it) }
        }

        fun readStringList(buf: ByteBuf): List<String> {
            val count = buf.readShort().toInt()
            return (0 until count).map { readString(buf)!! }
        }

        fun writeVarInt(buf: ByteBuf, value: Long) {
            var v = value
            while (v > 0x7F) {
                buf.writeByte((v and 0x7F).toInt() or 0x80)
                v = v shr 7
            }
            buf.writeByte(v.toInt())
        }

        fun readVarInt(buf: ByteBuf): Long {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = buf.readByte().toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                shift += 7
            } while (b and 0x80 != 0)
            return result
        }

        fun writeByteArray(buf: ByteBuf, value: ByteArray?) {
            if (value == null) {
                buf.writeInt(-1)
                return
            }
            buf.writeInt(value.size)
            buf.writeBytes(value)
        }

        fun readByteArray(buf: ByteBuf): ByteArray? {
            val len = buf.readInt()
            if (len < 0) return null
            val bytes = ByteArray(len)
            buf.readBytes(bytes)
            return bytes
        }

        fun writeStringNullableList(buf: ByteBuf, list: List<String?>) {
            buf.writeShort(list.size)
            list.forEach { writeString(buf, it) }
        }

        fun readStringNullableList(buf: ByteBuf): List<String?> {
            val count = buf.readShort().toInt()
            return (0 until count).map { readString(buf) }
        }
    }
}

/**
 * IProto 对象的工厂接口。每个 Payload 的 companion object 实现此接口，
 * 供 PacketCodec 自动反序列化。
 */
interface IProtoCreator<T : IProto> {
    fun create(buf: ByteBuf): T
}

/**
 * 用于从 RocksDB 读取的原始字节包装，不经过 create() 反序列化。
 * PacketCodec.encode() 对 RawProto 直接写出其 bytes。
 */
class RawProto(override val packetType: PacketType, private val bytes: ByteArray) : IProto {
    override fun writeTo(buf: ByteBuf) {
        buf.writeBytes(bytes)
    }
}
