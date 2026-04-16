package com.virjar.tk.protocol

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.EventLoop
import io.netty.handler.codec.ByteToMessageDecoder
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HandshakeHandler(
    val record: TkLogger,
    val channel: Channel
) : ChannelInboundHandlerAdapter() {
    private val accumulator = ByteToMessageDecoder.MERGE_CUMULATOR
    private var buf: ByteBuf? = null
    private var hasData = false

    init {
        slowAttackDefence(WeakReference(channel), channel.eventLoop())
    }

    private fun checkMagic(buf: ByteBuf): Boolean {
        // 验证 MAGIC
        val magicIndex = buf.readerIndex()
        for (i in IProto.MAGIC.indices) {
            val byte = buf.getByte(magicIndex + i)
            if (byte == IProto.MAGIC[i]) {
                continue
            }
            record.log { "Invalid magic bytes ,index: $i expected:${IProto.MAGIC[i]} current:${byte}" }
            return false
        }
        buf.skipBytes(IProto.MAGIC.size) // MAGIC(7)
        return true
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            // will not happen
            record.log { "unexpected message type:${msg.javaClass}" }
            return
        }
        hasData = true
        val buf = if (buf == null) {
            msg
        } else {
            accumulator.cumulate(ctx.alloc(), buf, msg)
        }
        this.buf = buf

        if (buf.readableBytes() < IProto.MAGIC.size + 1) {
            return
        }

        if (!checkMagic(buf)) {
            buf.release()
            ctx.close()
            return
        }

        val version = buf.readByte() // VERSION(1)
        if (version != IProto.VERSION) {
            record.log { "Invalid protocol version: $version" }
            buf.release()
            ctx.close()
            return
        }

        // pass
        ctx.pipeline().remove(this)
        ctx.fireChannelRead(buf)
    }


    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        buf?.release()
        buf = null
        if (!hasData && cause is IOException) {
            // 有LBS负载均衡的服务，通过探测端口是否开启来判断服务是否存活，
            // 他们只开启端口，然后就会关闭隧道，此时这里就会有IOException: java.io.IOException: Connection reset by peer
            // 此时我们只打印一个日志，但是不输出堆栈，避免堆栈污染日志文本
            record.log { "exception: " + cause.javaClass + " ->" + cause.message + " before any data receive" }
        } else {
            record.log({ "handShark error" }, cause)
        }
        ctx.close()
    }

    /**
     * 慢速攻击防御，有一种攻击手段是创建tcp链接，并且给一部分正确的握手信息，然后就挂起
     * 如果不防御服务器就会一直为这个tcp链接开辟fd和内存资源，这可能导致服务器的fd资源被打满
     *
     * 防御策略：握手请求必须在30s内完成，超时直接报错并且释放连接
     */
    private fun slowAttackDefence(ref: WeakReference<Channel?>, eventLoop: EventLoop) {
        eventLoop.schedule({
            val socketChannel = ref.get() ?: return@schedule
            val magicHandSharker: HandshakeHandler? = socketChannel.pipeline()
                .get<HandshakeHandler?>(HandshakeHandler::class.java)
            if (magicHandSharker == null) {
                return@schedule
            }
            val errorMsg = "no full HandShark data after 30s, this con maybe slow attack"
            record.log { errorMsg }
            socketChannel.pipeline().fireExceptionCaught(
                TimeoutException(errorMsg)
            )
        }, 30, TimeUnit.SECONDS)
    }
}