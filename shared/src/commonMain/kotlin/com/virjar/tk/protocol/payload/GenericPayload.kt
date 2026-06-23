package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 通用扩展载荷。用于 NOTIFY 和 MESSAGE 的 GENERIC(99) 入口。
 *
 * wire format: [extensionType(varInt)] [opaque bytes]
 *
 * - extensionType: ExtensionType 枚举值，用于路由分发
 * - data: 扩展的 opaque 字节，由扩展处理器自行解析
 *
 * RPC 扩展不走此载荷，而是直接用 ServiceId.GENERIC + methodId(=extensionType) + payload，
 * 因为 RPC 已有 requestId/serviceId/methodId/payload 结构，methodId 直接复用为 extensionType。
 */
data class GenericPayload(
    val extensionType: Int,
    val data: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(extensionType)
        buf.writeBytes(data)
    }

    companion object : IProtoReader<GenericPayload> {
        override fun readFrom(buf: PacketBuffer) = GenericPayload(
            extensionType = buf.readVarInt(),
            data = buf.readBytes(),
        )
    }
}
