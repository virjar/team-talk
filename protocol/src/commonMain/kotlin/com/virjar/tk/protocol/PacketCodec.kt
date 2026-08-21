package com.virjar.tk.protocol

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.*
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec

/**
 * 当前连接接收的是哪一端发来的帧。
 *
 * 协议编解码器由客户端和服务端共用，但请求与响应的方向是固定的。服务端必须在读取
 * payload 之前拒绝只可能由服务端发送的包，否则攻击者可以借 RESPONSE/NOTIFY 等类型
 * 让服务端先分配一个大 byte array，再由上层把对象当 UNKNOWN 丢弃。
 */
enum class PacketInboundRole {
    ANY,
    SERVER,
    CLIENT;

    internal fun accepts(type: PacketType): Boolean = when (this) {
        ANY -> true
        SERVER -> type in SERVER_INBOUND_TYPES
        CLIENT -> type in CLIENT_INBOUND_TYPES
    }

    private companion object {
        val SERVER_INBOUND_TYPES = setOf(
            PacketType.AUTH,
            PacketType.SYNC_REQUEST,
            PacketType.DISCONNECT,
            PacketType.PING,
            PacketType.PONG,
            PacketType.INVOKE,
            PacketType.MESSAGE,
        )
        val CLIENT_INBOUND_TYPES = setOf(
            PacketType.AUTH_RESP,
            PacketType.SYNC_BATCH,
            PacketType.SYNC_READY,
            PacketType.SYNC_RESET,
            PacketType.DISCONNECT,
            PacketType.PING,
            PacketType.PONG,
            PacketType.RESPONSE,
            PacketType.STREAM_ITEM,
            PacketType.STREAM_END,
            PacketType.MESSAGE,
            PacketType.MESSAGE_ACK,
            PacketType.NOTIFY,
        )
    }
}

/**
 * TCP 帧编解码器。
 *
 * 帧格式：[TYPE(1B)][LENGTH(4B)][PAYLOAD(LENGTH bytes)]
 *
 * 解码产出 IProto 对象，编码接收 IProto 对象写入 ByteBuf。
 */
class PacketCodec(
    /** 帧长度上限（可变：未认证连接收紧为 [UNAUTHED_LIMIT]，认证成功后调至 16MB）。
     * 慢速攻击防御：防止未认证连接声明大 LENGTH 诱发累积缓冲放大。 */
    @Volatile var maxPayloadLimit: Int = UNAUTHED_LIMIT,
    private val inboundRole: PacketInboundRole = PacketInboundRole.ANY,
) : ByteToMessageCodec<IProto>() {
    companion object {
        /** 帧头大小：TYPE(1B) + LENGTH(4B) */
        const val HEADER_SIZE = 5

        /**
         * 正式发布前的开发协议基线。以后如果发生不兼容变更，必须从当前值递增，
         * 避免两个不同 wire 共用同一版本号。
         */
        const val PROTOCOL_VERSION: Byte = 1

        /** 单帧 payload 上限（认证后） */
        const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024  // 16MB

        /** 未认证连接的帧上限：AUTH 包远小于此值，认证前无任何合法大帧 */
        const val UNAUTHED_LIMIT = 4 * 1024

        /** 认证后上限 */
        const val AUTHED_LIMIT = MAX_PAYLOAD_SIZE

        /** 客户端发送 PING 间隔（秒） */
        const val PING_INTERVAL_SECONDS: Long = 15

        /** 读空闲超时（秒），3 倍心跳间隔。超时后主动关闭触发重连 */
        const val READ_IDLE_TIMEOUT_SECONDS: Long = PING_INTERVAL_SECONDS * 3
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        // 至少需要帧头
        if (buf.readableBytes() < HEADER_SIZE) return

        buf.markReaderIndex()

        val typeCode = buf.readByte().toInt() and 0xFF
        val length = buf.readInt()

        if (length < 0 || length > maxPayloadLimit) {
            throw io.netty.handler.codec.CorruptedFrameException("Invalid payload length: $length (limit=$maxPayloadLimit)")
        }

        val packetType = try {
            PacketType.fromCode(typeCode)
        } catch (e: IllegalArgumentException) {
            // 帧级类型集随 PROTOCOL_VERSION 固定：未知 TYPE = 错位/污染/跨版本，
            // 必须断连，不能静默丢帧掩盖协议异常。
            throw io.netty.handler.codec.CorruptedFrameException("Unknown packet type: $typeCode")
        }
        if (!inboundRole.accepts(packetType)) {
            // 此检查必须位于 payload 完整性等待和 decodePayload 之前：只收齐 5 字节帧头
            // 即可拒绝方向错误的大帧，不让累积缓冲或 readBytes/readString 扩容。
            throw io.netty.handler.codec.CorruptedFrameException(
                "Packet type $packetType is not valid for $inboundRole inbound traffic",
            )
        }

        val signal = packetType.toSignalOrNull()
        if (signal != null && length != 0) {
            // PING/PONG/DISCONNECT 没有 payload。若允许非零长度，认证客户端就能持续发送
            // 16 MiB 填充帧，让 EventLoop 在完全绕过有界 IO 队列的情况下累积和丢弃数据。
            // 只看帧头即可拒绝，不能等待攻击者把声明的填充字节传完。
            throw io.netty.handler.codec.CorruptedFrameException(
                "Signal packet $packetType must have an empty payload, got $length bytes",
            )
        }

        if (buf.readableBytes() < length) {
            buf.resetReaderIndex()
            return
        }

        val proto = if (signal != null) {
            signal
        } else {
            // decodePayload 在当前调用内同步读完，slice 无需独立引用计数。
            // retainedSlice 会把父 ByteBuf 的 refCnt +1，而 PacketBuffer 没有释放语义，
            // 导致每个非空 TCP 帧泄漏一份 Netty 堆外缓冲区。
            val payloadBuf = PacketBuffer(buf.readSlice(length))
            val decoded = decodePayload(packetType, payloadBuf)
            if (payloadBuf.readableBytes() != 0) {
                // 每个 frame 都必须是唯一、完整的 wire 表示。静默忽略尾随字节既隐藏版本
                // 错位，也允许小 Message/Invoke 后附大块 padding 绕过字段预算和任务队列。
                throw io.netty.handler.codec.CorruptedFrameException(
                    "Packet $packetType has ${payloadBuf.readableBytes()} trailing payload bytes",
                )
            }
            decoded
        }

        // ByteToMessageDecoder may decode several coalesced frames before downstream handlers run.
        // Raise the client-side frame limit at the successful AUTH_RESP itself so a large
        // SYNC_BATCH immediately following it in the same TCP read is decoded under the
        // authenticated budget, not the 4 KiB pre-authentication fence.
        if (
            inboundRole == PacketInboundRole.CLIENT &&
            proto is AuthResponsePayload &&
            proto.code == AuthResponsePayload.CODE_OK
        ) {
            maxPayloadLimit = AUTHED_LIMIT
        }

        out.add(proto)
    }

    private fun PacketType.toSignalOrNull(): IProto? = when (this) {
        PacketType.PING -> PingSignal
        PacketType.PONG -> PongSignal
        PacketType.DISCONNECT -> DisconnectSignal
        else -> null
    }

    private fun decodePayload(type: PacketType, buf: PacketBuffer): IProto = when (type) {
        PacketType.AUTH -> AuthRequestPayload.readFrom(buf)
        PacketType.AUTH_RESP -> AuthResponsePayload.readFrom(buf)
        PacketType.SYNC_REQUEST -> SyncRequestPayload.readFrom(buf)
        PacketType.SYNC_BATCH -> SyncBatchPayload.readFrom(buf)
        PacketType.SYNC_READY -> SyncReadyPayload.readFrom(buf)
        PacketType.SYNC_RESET -> SyncResetPayload.readFrom(buf)
        PacketType.INVOKE -> InvokePayload.readFrom(buf)
        PacketType.RESPONSE -> ResponsePayload.readFrom(buf)
        PacketType.STREAM_ITEM -> StreamItemPayload.readFrom(buf)
        PacketType.STREAM_END -> StreamEndPayload.readFrom(buf)
        PacketType.MESSAGE -> Message.readFrom(buf)
        PacketType.MESSAGE_ACK -> MessageAckPayload.readFrom(buf)
        PacketType.NOTIFY -> NotifyPayload.readFrom(buf)
        PacketType.PING, PacketType.PONG, PacketType.DISCONNECT ->
            throw io.netty.handler.codec.CorruptedFrameException("Signal packet $type cannot have a payload")
    }

    override fun encode(ctx: ChannelHandlerContext, msg: IProto, out: ByteBuf) {
        val (typeCode, payloadWriter: (PacketBuffer) -> Unit) = resolveTypeAndWriter(msg)

        out.writeByte(typeCode)

        // 零载荷信号
        if (msg is PingSignal || msg is PongSignal || msg is DisconnectSignal) {
            out.writeInt(0)
            return
        }

        // 先写长度占位
        val lengthIndex = out.writerIndex()
        out.writeInt(0)

        val startIdx = out.writerIndex()
        val buf = PacketBuffer(out)
        payloadWriter(buf)
        val endIdx = out.writerIndex()

        // 回填实际长度
        out.setInt(lengthIndex, endIdx - startIdx)
    }

    private fun resolveTypeAndWriter(msg: IProto): Pair<Int, (PacketBuffer) -> Unit> = when (msg) {
        is AuthRequestPayload -> PacketType.AUTH.code to { it.writePayload(msg) }
        is AuthResponsePayload -> PacketType.AUTH_RESP.code to { it.writePayload(msg) }
        is SyncRequestPayload -> PacketType.SYNC_REQUEST.code to { it.writePayload(msg) }
        is SyncBatchPayload -> PacketType.SYNC_BATCH.code to { it.writePayload(msg) }
        is SyncReadyPayload -> PacketType.SYNC_READY.code to { it.writePayload(msg) }
        is SyncResetPayload -> PacketType.SYNC_RESET.code to { it.writePayload(msg) }
        is PingSignal -> PacketType.PING.code to {}
        is PongSignal -> PacketType.PONG.code to {}
        is DisconnectSignal -> PacketType.DISCONNECT.code to {}
        is InvokePayload -> PacketType.INVOKE.code to { it.writePayload(msg) }
        is ResponsePayload -> PacketType.RESPONSE.code to { it.writePayload(msg) }
        is StreamItemPayload -> PacketType.STREAM_ITEM.code to { it.writePayload(msg) }
        is StreamEndPayload -> PacketType.STREAM_END.code to { it.writePayload(msg) }
        is Message -> PacketType.MESSAGE.code to { it.writePayload(msg) }
        is MessageAckPayload -> PacketType.MESSAGE_ACK.code to { it.writePayload(msg) }
        is NotifyPayload -> PacketType.NOTIFY.code to { it.writePayload(msg) }
        else -> throw IllegalArgumentException("Unknown proto type: ${msg::class}")
    }

    private fun PacketBuffer.writePayload(msg: IProto) {
        msg.writeTo(this)
    }
}
