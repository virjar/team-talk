package com.virjar.tk.protocol

/**
 * 零载荷信号对象（PING/PONG/DISCONNECT 无 payload）。
 */
object PingSignal : IProto {
    override fun writeTo(buf: PacketBuffer) {}
}

object PongSignal : IProto {
    override fun writeTo(buf: PacketBuffer) {}
}

object DisconnectSignal : IProto {
    override fun writeTo(buf: PacketBuffer) {}
}
