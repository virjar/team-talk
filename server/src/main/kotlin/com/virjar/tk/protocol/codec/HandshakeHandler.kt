package com.virjar.tk.protocol.codec

import com.virjar.tk.protocol.Frame
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory

/**
 * 握手处理器。
 * 连接建立后立即发送 MAGIC + VERSION 给客户端。
 * 收到客户端的 MAGIC + VERSION 后移除自身。
 */
class HandshakeHandler : ChannelInboundHandlerAdapter() {
    private val logger = LoggerFactory.getLogger("HandshakeHandler")
    private var handshakeSent = false

    override fun channelActive(ctx: ChannelHandlerContext) {
        // 向客户端发送 MAGIC + VERSION
        val buf = Unpooled.buffer(3)
        buf.writeByte(Frame.MAGIC_HIGH.toInt())
        buf.writeByte(Frame.MAGIC_LOW.toInt())
        buf.writeByte(Frame.PROTOCOL_VERSION.toInt())
        ctx.writeAndFlush(buf)
        handshakeSent = true
        logger.debug("[${ctx.channel().id().asShortText()}] Handshake sent")
        ctx.fireChannelActive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is io.netty.buffer.ByteBuf) {
            if (msg.readableBytes() >= 3) {
                val magicHigh = msg.readByte()
                val magicLow = msg.readByte()
                val version = msg.readByte()
                if (magicHigh == Frame.MAGIC_HIGH && magicLow == Frame.MAGIC_LOW) {
                    logger.debug("[${ctx.channel().id().asShortText()}] Handshake completed, version=$version")
                    // 握手完成，移除自身
                    ctx.pipeline().remove(this)
                    // 如果还有剩余数据，继续传递
                    if (msg.readableBytes() > 0) {
                        ctx.fireChannelRead(msg.retain())
                    } else {
                        msg.release()
                    }
                    return
                }
            }
        }
        ctx.fireChannelRead(msg)
    }
}
