package com.virjar.tk.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.PingSignal
import com.virjar.tk.util.AppLog
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit

/**
 * 客户端握手 Handler：新协议流程。
 *
 * 新流程：连接后先读取服务端发来的 MAGIC_WITH_VERSION(8 bytes)，
 * 然后发送 AUTH 包（由 TcpConnection 触发），再等待 AUTH_RESP。
 *
 * Pipeline 位置：
 * - 握手前: idle → ClientHandshakeHandler
 * - 握手后: idle → PacketCodec → ClientHandler
 */
class ClientHandshakeHandler(
    private val connection: TcpConnection,
    private val listener: ConnectionListener,
) : ByteToMessageDecoder() {

    private enum class Phase { WAIT_MAGIC, WAIT_AUTH_RESP }
    private var phase = Phase.WAIT_MAGIC

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        when (phase) {
            Phase.WAIT_MAGIC -> {
                // 等待 MAGIC(7) + VERSION(1) = 8 bytes
                if (buf.readableBytes() < IProto.MAGIC_WITH_VERSION.size) return

                // 验证 MAGIC + VERSION
                for (i in IProto.MAGIC_WITH_VERSION.indices) {
                    if (buf.readByte() != IProto.MAGIC_WITH_VERSION[i]) {
                        connection.onAuthFailed()
                        listener.onAuthFailed(connection, AuthResponsePayload.CODE_AUTH_FAILED, "invalid magic")
                        ctx.close()
                        return
                    }
                }

                // MAGIC 验证通过，通知 TcpConnection 发送 AUTH 包
                phase = Phase.WAIT_AUTH_RESP
                connection.onMagicReceived()
            }

            Phase.WAIT_AUTH_RESP -> {
                // 等待 AUTH_RESP 包: PacketType(1) + Length(4) + Payload
                if (buf.readableBytes() < 5) return

                buf.markReaderIndex()
                val typeCode = buf.readByte()
                val length = buf.readInt()

                if (length < 0 || buf.readableBytes() < length) {
                    buf.resetReaderIndex()
                    return
                }

                // 验证是 AUTH_RESP 包
                if (typeCode != PacketType.AUTH_RESP.code) {
                    connection.onAuthFailed()
                    listener.onAuthFailed(connection, AuthResponsePayload.CODE_AUTH_FAILED, "unexpected packet type")
                    ctx.close()
                    return
                }

                val payloadBuf = if (length > 0) buf.retainedSlice(buf.readerIndex(), length) else null
                buf.skipBytes(length)

                try {
                    val authResp = if (payloadBuf != null) AuthResponsePayload(payloadBuf) else AuthResponsePayload(AuthResponsePayload.CODE_AUTH_FAILED, "empty payload")

                    if (authResp.code == AuthResponsePayload.CODE_OK) {
                        connection.onHandshakeSuccess()

                        // 升级 pipeline：移除自己，添加 PacketCodec + ClientHandler
                        ctx.pipeline().apply {
                            addLast("codec", PacketCodec())
                            addLast("handler", connection.createClientHandler())
                            remove(this@ClientHandshakeHandler)
                        }
                        // ByteToMessageDecoder.handlerRemoved 会自动将剩余字节传递给下游
                    } else {
                        connection.onAuthFailed()
                        listener.onAuthFailed(connection, authResp.code, authResp.reason)
                        ctx.close()
                    }
                } finally {
                    payloadBuf?.release()
                }
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        AppLog.e("ClientHandshake", "exceptionCaught", cause)
        ctx.close()
    }
}
