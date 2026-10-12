package com.virjar.tk.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor

/**
 * wire 编码类型的标记接口：实现本接口的类型在 wire/DB 上以其 [code] 的 Int 值传输。
 *
 * 契约签名按二进制语义比对——模型字段在 `kotlin.Int` 与本类型之间切换不构成 wire
 * 变更（读写仍由各模型的 `writeTo`/`readFrom` 手写代码负责）。实现类型应提供
 * `fromCode(Int)` 容错解码：未知值承载为保留原 code 的成员，多端混布下旧端不崩溃。
 */
interface WireCoded {
    val code: Int
}

/**
 * WireCoded 类型的 kotlinx 序列化基类：任何 JSON/序列化场景下的形态都是 Int code，
 * 与二进制 wire 语义保持一致（sealed 多态形态不外泄）。
 */
abstract class WireCodedSerializer<T : WireCoded>(serialName: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.INT)
    final override fun serialize(encoder: Encoder, value: T) = encoder.encodeInt(value.code)
    final override fun deserialize(decoder: Decoder): T = fromCode(decoder.decodeInt())
    protected abstract fun fromCode(code: Int): T
}
