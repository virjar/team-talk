package com.virjar.tk.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

/**
 * 通用编解码工具。
 * 将 IProto 编码为 ByteArray / 从 ByteArray 解码。
 */
object ProtoCodec {

    // ── 编码 ──

    fun encode(proto: IProto): ByteArray = writeBuf { proto.writeTo(it) }

    fun encodeList(protos: List<IProto>): ByteArray = writeBuf { buf ->
        buf.writeVarInt(protos.size)
        for (proto in protos) proto.writeTo(buf)
    }

    // ── 解码 ──

    fun <T : IProto> decode(reader: IProtoReader<T>, bytes: ByteArray): T {
        return reader.readFrom(PacketBuffer(Unpooled.wrappedBuffer(bytes)))
    }

    fun <T : IProto> decodeList(reader: IProtoReader<T>, bytes: ByteArray): List<T> {
        val buf = PacketBuffer(Unpooled.wrappedBuffer(bytes))
        val count = buf.readVarInt()
        return (0 until count).map { reader.readFrom(buf) }
    }

    // ── Payload 写入辅助 ──

    /**
     * 自定义 Payload 编码，用于客户端构建复杂的 RPC 请求参数。
     */
    inline fun encodePayload(crossinline block: PacketBuffer.() -> Unit): ByteArray {
        val byteBuf: ByteBuf = Unpooled.buffer()
        try {
            val buf = PacketBuffer(byteBuf)
            buf.block()
            val bytes = ByteArray(byteBuf.readableBytes())
            byteBuf.readBytes(bytes)
            return bytes
        } finally {
            byteBuf.release()
        }
    }

    // ── Payload 读取辅助 ──

    /**
     * 从 ByteArray 打开 PacketBuffer 并执行 block，用于 RPC 路由中读取请求参数。
     */
    inline fun <T> withPayload(payload: ByteArray?, block: PacketBuffer.() -> T): T {
        return PacketBuffer(Unpooled.wrappedBuffer(payload!!)).block()
    }

    // ── 内部 ──

    private inline fun writeBuf(block: (PacketBuffer) -> Unit): ByteArray {
        val byteBuf: ByteBuf = Unpooled.buffer()
        try {
            val buf = PacketBuffer(byteBuf)
            block(buf)
            val bytes = ByteArray(byteBuf.readableBytes())
            byteBuf.readBytes(bytes)
            return bytes
        } finally {
            byteBuf.release()
        }
    }
}
