package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AuthResponsePayload

/** 顺序读帧的字节源抽象：Netty ByteBuf 与增量读取器的内部缓冲各出一版薄适配。 */
interface PacketByteSource {
    fun readableBytes(): Int

    /** 记录当前读取位置；[reset] 回滚到这里（payload 尚未到齐时等待更多字节）。 */
    fun markPosition()
    fun reset()

    fun readUnsignedByte(): Int

    /** 读一个大端 4 字节长度。 */
    fun readIntBigEndian(): Int
    fun readBytes(count: Int): ByteArray
}

/** 一次成功读出的帧；[widensPayloadLimit] 表示 CLIENT 角色的 AUTH_RESP(CODE_OK) 当帧放宽上限。 */
class PacketFrameRead internal constructor(
    val packet: IProto,
    val widensPayloadLimit: Boolean,
)

/**
 * 从已缓冲字节顺序读出一帧；字节不足时回滚读取位置并返回 null。头部校验与 payload
 * 解码沿用 [PacketFrames] 语义（非法输入抛 [ProtocolCorruptionException]），由调用方
 * 翻译成各自传输层的异常类型。Netty codec 与 [PacketFrameReader] 共享这一份决策。
 */
fun PacketByteSource.readPacketFrame(
    inboundRole: PacketInboundRole,
    maxPayloadLimit: Int,
): PacketFrameRead? {
    if (readableBytes() < PacketFrames.HEADER_SIZE) return null
    markPosition()
    val typeCode = readUnsignedByte()
    val length = readIntBigEndian()
    PacketFrames.validateHeader(typeCode, length, inboundRole, maxPayloadLimit)
    if (readableBytes() < length) {
        reset()
        return null
    }
    val packet = PacketFrames.decode(typeCode, readBytes(length), inboundRole, maxPayloadLimit)
    val widens = inboundRole == PacketInboundRole.CLIENT &&
        packet is AuthResponsePayload &&
        packet.code == AuthResponsePayload.CODE_OK
    return PacketFrameRead(packet, widens)
}

/**
 * 面向"字节到达回调"型传输（iOS Network.framework、未来的 raw-socket 后端）的增量帧
 * 读取器：自管半包累积，调用方只负责把到达的字节喂进来。
 *
 * AUTH_RESP(CODE_OK) 当帧放宽后续帧上限；业务认证完成（连接 owner 的
 * onAuthenticationAccepted）也可以直接置 [authenticated]，两个入口共享同一状态。
 * 校验失败按 [readPacketFrame] 语义抛出，读取器此后视为不可用，由调用方关闭连接。
 */
class PacketFrameReader(private val inboundRole: PacketInboundRole) {
    private var buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
    private var buffered = 0
    private var position = 0
    private val source = BufferSource()

    /** 客户端方向的认证上限状态；连接 owner 在认证确认后可直接置位。 */
    var authenticated: Boolean = false

    /** 尚未组成完整帧的残留字节数；对端在半帧处结束时据此判定流截断。 */
    fun hasPendingBytes(): Boolean = buffered > position

    /**
     * 喂入一段到达字节；对其中每个完整帧调用一次 [onPacket]（按到达顺序）。
     * [keepReading] 在每帧投递后询问是否继续：回调里关闭了连接时返回 false，同批
     * 已到达的后续帧停留在缓冲中不再投递（与旧的按需读取在关闭边界上的语义一致）。
     */
    fun feed(
        input: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = input.size,
        keepReading: () -> Boolean = { true },
        onPacket: (IProto) -> Unit,
    ) {
        require(startIndex in 0..endIndex && endIndex in startIndex..input.size)
        append(input, startIndex, endIndex)
        while (true) {
            val frame = source.readPacketFrame(inboundRole, payloadLimit()) ?: break
            if (frame.widensPayloadLimit) authenticated = true
            onPacket(frame.packet)
            if (!keepReading()) break
        }
        compact()
    }

    private fun payloadLimit(): Int =
        if (authenticated) PacketFrames.AUTHED_LIMIT else PacketFrames.UNAUTHED_LIMIT

    private fun append(input: ByteArray, startIndex: Int, endIndex: Int) {
        val count = endIndex - startIndex
        if (count == 0) return
        val required = buffered + count
        if (required > buffer.size) {
            var capacity = buffer.size
            while (capacity < required) capacity = maxOf(capacity * 2, required)
            buffer = buffer.copyOf(capacity)
        }
        input.copyInto(buffer, buffered, startIndex, endIndex)
        buffered += count
    }

    /** 把未消费字节前移归零；半包大缓冲消费完之后收缩回初始容量。 */
    private fun compact() {
        if (position == 0) return
        val remaining = buffered - position
        if (remaining > 0) buffer.copyInto(buffer, 0, position, buffered)
        buffered = remaining
        position = 0
        if (remaining == 0 && buffer.size > INITIAL_BUFFER_CAPACITY) {
            buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
        }
    }

    private inner class BufferSource : PacketByteSource {
        private var mark = 0

        override fun readableBytes(): Int = buffered - position
        override fun markPosition() {
            mark = position
        }

        override fun reset() {
            position = mark
        }

        override fun readUnsignedByte(): Int = buffer[position++].toInt() and 0xFF

        override fun readIntBigEndian(): Int {
            var value = 0
            repeat(4) { value = (value shl 8) or (buffer[position++].toInt() and 0xFF) }
            return value
        }

        override fun readBytes(count: Int): ByteArray {
            val chunk = buffer.copyOfRange(position, position + count)
            position += count
            return chunk
        }
    }

    private companion object {
        // 单条消息帧平均只有几 KB；64 KB 足够装下常见的整批到达，认证后的最大帧再按需扩容。
        const val INITIAL_BUFFER_CAPACITY = 64 * 1024
    }
}
