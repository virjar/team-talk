package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf

// ============================================================
// 零载荷信号对象（PacketType 1-3）
// ============================================================

/** DISCONNECT(1) 断开连接信号 */
object DisconnectSignal : IProto {
    override val packetType = PacketType.DISCONNECT
    override fun writeTo(buf: ByteBuf) {}

    val CREATOR: IProtoCreator<DisconnectSignal> = object : IProtoCreator<DisconnectSignal> {
        override fun create(buf: ByteBuf): DisconnectSignal = DisconnectSignal
    }
}

/** PING(2) 心跳请求信号 */
object PingSignal : IProto {
    override val packetType = PacketType.PING
    override fun writeTo(buf: ByteBuf) {}

    val CREATOR: IProtoCreator<PingSignal> = object : IProtoCreator<PingSignal> {
        override fun create(buf: ByteBuf): PingSignal = PingSignal
    }
}

/** PONG(3) 心跳响应信号 */
object PongSignal : IProto {
    override val packetType = PacketType.PONG
    override fun writeTo(buf: ByteBuf) {}

    val CREATOR: IProtoCreator<PongSignal> = object : IProtoCreator<PongSignal> {
        override fun create(buf: ByteBuf): PongSignal = PongSignal
    }
}
