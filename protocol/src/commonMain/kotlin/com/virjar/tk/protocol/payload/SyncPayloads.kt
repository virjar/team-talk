package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.PacketCodec

/**
 * 客户端在本地事件消费者与持久化缓存都就绪后发起的增量同步请求。
 *
 * 每一批事件必须完成本地投影并持久化 [lastEventId] 后，客户端才发送下一次请求。
 */
data class SyncRequestPayload(
    val lastEventId: Long,
) : IProto {
    init {
        require(lastEventId >= 0L) { "lastEventId must be non-negative" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(lastEventId)
    }

    companion object : IProtoReader<SyncRequestPayload> {
        override fun readFrom(buf: PacketBuffer) = SyncRequestPayload(buf.readVarLong())
    }
}

/** 服务端返回的一小批持久事件；客户端成功处理整批后再请求下一批。 */
data class SyncBatchPayload(
    val events: List<NotifyPayload>,
) : IProto {
    init {
        require(events.isNotEmpty()) { "sync batch must not be empty" }
        require(events.size <= MAX_EVENTS) { "sync batch exceeds $MAX_EVENTS events" }
        require(events.all { it.eventId > 0L }) { "sync batch can only contain durable events" }
        require(events.zipWithNext().all { (left, right) -> left.eventId < right.eventId }) {
            "sync batch events must be strictly ordered"
        }
        require(batchWireSize(events) <= PacketCodec.MAX_PAYLOAD_SIZE.toLong()) {
            "sync batch exceeds ${PacketCodec.MAX_PAYLOAD_SIZE} wire bytes"
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(events.size)
        events.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<SyncBatchPayload> {
        const val MAX_EVENTS = 64

        /**
         * Select the largest strictly ordered prefix that is provably encodable in one frame.
         * Empty means the first event is legal as a standalone NOTIFY but cannot fit after the
         * batch count byte; the server must send that one event standalone during synchronization.
         */
        fun boundedPrefix(
            events: List<NotifyPayload>,
            maximumWireBytes: Int = PacketCodec.MAX_PAYLOAD_SIZE,
        ): List<NotifyPayload> {
            require(maximumWireBytes in 1..PacketCodec.MAX_PAYLOAD_SIZE) {
                "maximumWireBytes must be in 1..${PacketCodec.MAX_PAYLOAD_SIZE}"
            }
            require(events.size <= MAX_EVENTS) { "sync page exceeds $MAX_EVENTS events" }
            require(events.all { it.eventId > 0L }) { "sync page can only contain durable events" }
            require(events.zipWithNext().all { (left, right) -> left.eventId < right.eventId }) {
                "sync page events must be strictly ordered"
            }
            events.firstOrNull()?.let { first ->
                require(eventWireSize(first) <= PacketCodec.MAX_PAYLOAD_SIZE.toLong()) {
                    "single sync event exceeds ${PacketCodec.MAX_PAYLOAD_SIZE} wire bytes"
                }
            }
            var wireBytes = 1L // count VarInt is one byte because MAX_EVENTS < 128
            val selected = ArrayList<NotifyPayload>(minOf(events.size, MAX_EVENTS))
            for (event in events.take(MAX_EVENTS)) {
                val eventBytes = eventWireSize(event)
                if (wireBytes + eventBytes > maximumWireBytes.toLong()) break
                selected += event
                wireBytes += eventBytes
            }
            return selected
        }

        fun eventWireSize(event: NotifyPayload): Long {
            val bytesLength = event.payload?.size
            return varLongWireSize(event.eventId) + 1L + 1L + if (bytesLength == null) {
                0L
            } else {
                varIntWireSize(bytesLength) + bytesLength.toLong()
            }
        }

        private fun batchWireSize(events: List<NotifyPayload>): Long =
            varIntWireSize(events.size) + events.sumOf(::eventWireSize)

        private fun varIntWireSize(value: Int): Long {
            require(value >= 0)
            var remaining = value
            var bytes = 1L
            while (remaining > 0x7F) {
                remaining = remaining ushr 7
                bytes += 1L
            }
            return bytes
        }

        private fun varLongWireSize(value: Long): Long {
            require(value >= 0L)
            var remaining = value
            var bytes = 1L
            while (remaining > 0x7FL) {
                remaining = remaining ushr 7
                bytes += 1L
            }
            return bytes
        }

        override fun readFrom(buf: PacketBuffer): SyncBatchPayload {
            val count = buf.readCollectionSize(
                maximum = MAX_EVENTS,
                minimumBytesPerEntry = 3,
                fieldName = "sync events",
            )
            return SyncBatchPayload(List(count) { NotifyPayload.readFrom(buf) })
        }
    }
}

/**
 * 服务端已在用户事件门闩内二次确认无遗漏，并把当前连接注册为实时事件接收者。
 * 此包永远先于注册后产生的实时 NOTIFY 写入同一连接。
 */
data object SyncReadyPayload : IProto, IProtoReader<SyncReadyPayload> {
    override fun writeTo(buf: PacketBuffer) = Unit
    override fun readFrom(buf: PacketBuffer): SyncReadyPayload = this
}

/**
 * 服务端拒绝了客户端持久游标，要求在当前已认证连接内丢弃本地服务端投影并从 0 重放。
 *
 * RESET 不携带可选策略或服务端游标：客户端只有一种安全行为，即原子清空投影，持久化
 * cursor=0，再发送 `SYNC_REQUEST(0)`。任何重置失败或同连接重复 RESET 都必须断开。
 */
data object SyncResetPayload : IProto, IProtoReader<SyncResetPayload> {
    override fun writeTo(buf: PacketBuffer) = Unit
    override fun readFrom(buf: PacketBuffer): SyncResetPayload = this
}
