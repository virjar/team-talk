package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * `NotifyType.GENERIC(99)` 与 `MessageType.GENERIC(99)` 共用的通用扩展载荷。
 *
 * wire format: `[extensionType(varInt)] [opaque bytes]`
 *
 * - [extensionType] 是 [com.virjar.tk.protocol.ExtensionType] 的稳定编号；未知编号仍可解码。
 * - [data] 必须被基础设施原样保存和转发，只有对应扩展处理器可以解释其内容。
 * - MESSAGE 接收端遇到未知扩展只显示“不支持的扩展消息”，不得因无法理解 opaque data 断连。
 * - NOTIFY 接收端遇到未知扩展安全忽略；若是持久事件，外层游标仍正常推进。
 *
 * RPC 不额外套本载荷：它保留 `service = GenericRpcContract.SERVICE`，并直接使用
 * `methodId = ExtensionType.code` 与 InvokePayload 自带的 payload 字段。
 */
data class GenericPayload(
    val extensionType: Int,
    val data: ByteArray?,
) : MessageBody {
    init {
        require(extensionType >= 0) { "extensionType must be non-negative" }
    }

    // ByteArray 字段的 data class 默认 equals 比较引用；opaque payload 必须按内容判等，
    // 才能保证缓存、幂等摘要与 round-trip 测试不因数组实例不同而失真。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as GenericPayload
        return extensionType == other.extensionType && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = extensionType
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }

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
