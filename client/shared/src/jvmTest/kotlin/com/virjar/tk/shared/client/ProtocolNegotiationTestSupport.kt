package com.virjar.tk.shared.client

import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolNegotiation
import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.test.assertEquals

/** 真实 socket 测试先走生产协商帧，再进入各测试自身关心的 AUTH/sync 场景。 */
internal fun negotiateTestSocket(socket: Socket, server: ProtocolRange = ProtocolVersions.SUPPORTED) {
    val input = DataInputStream(socket.getInputStream())
    assertEquals(PacketType.NEGOTIATE.code, input.readUnsignedByte())
    val request = ProtoCodec.decode(ProtocolNegotiateRequestPayload, input.readNBytes(input.readInt()))
    val response = ProtoCodec.encode(ProtocolNegotiation.negotiate(request.supported, server))
    DataOutputStream(socket.getOutputStream()).apply {
        writeByte(PacketType.NEGOTIATE_RESP.code)
        writeInt(response.size)
        write(response)
        flush()
    }
}
