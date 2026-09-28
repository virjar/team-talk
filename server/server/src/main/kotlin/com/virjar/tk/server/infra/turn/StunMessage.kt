package com.virjar.tk.server.infra.turn

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * STUN(RFC 5389)与 TURN(RFC 5766)报文编解码的最小实现,供内嵌 TURN 服务使用。
 *
 * 只覆盖内嵌 TURN 需要的消息与属性:UDP relay 的 Allocate/Refresh/CreatePermission/
 * ChannelBind 请求与响应、Send/Data 指示、ChannelData 帧,以及 Binding(STUN 探活)。
 * 编解码为纯字节操作,正确性由 RFC 5769 测试向量与 libwebrtc 真实互通锁定。
 */
object Stun {
    const val MAGIC_COOKIE: Int = 0x2112A442
    const val HEADER_LENGTH = 20
    const val INTEGRITY_LENGTH = 24
    const val FINGERPRINT_LENGTH = 8
    private const val FINGERPRINT_XOR = 0x5354554E

    /** STUN 消息类别。 */
    const val CLASS_REQUEST = 0x00
    const val CLASS_INDICATION = 0x01
    const val CLASS_SUCCESS = 0x02
    const val CLASS_ERROR = 0x03

    // 方法(RFC 5389 §18.2 / RFC 5766 §14)
    const val METHOD_BINDING = 0x001
    const val METHOD_ALLOCATE = 0x003
    const val METHOD_REFRESH = 0x004
    const val METHOD_SEND = 0x006
    const val METHOD_DATA = 0x007
    const val METHOD_CREATE_PERMISSION = 0x008
    const val METHOD_CHANNEL_BIND = 0x009

    // 公共属性
    const val ATTR_MAPPED_ADDRESS = 0x0001
    const val ATTR_USERNAME = 0x0006
    const val ATTR_MESSAGE_INTEGRITY = 0x0008
    const val ATTR_ERROR_CODE = 0x0009
    const val ATTR_UNKNOWN_ATTRIBUTES = 0x000A
    const val ATTR_CHANNEL_NUMBER = 0x000C
    const val ATTR_LIFETIME = 0x000D
    const val ATTR_XOR_PEER_ADDRESS = 0x0012
    const val ATTR_DATA = 0x0013
    const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val ATTR_REQUESTED_TRANSPORT = 0x0019
    const val ATTR_DONT_FRAGMENT = 0x001A
    const val ATTR_SOFTWARE = 0x8022
    const val ATTR_REALM = 0x8023
    const val ATTR_NONCE = 0x8024
    const val ATTR_FINGERPRINT = 0x8028

    // 错误码(RFC 5766 §15.6 + RFC 5389)
    const val ERROR_TRY_ALTERNATE = 300
    const val ERROR_BAD_REQUEST = 400
    const val ERROR_UNAUTHORIZED = 401
    const val ERROR_UNKNOWN_ATTRIBUTE = 420
    const val ERROR_ALLOCATION_MISMATCH = 437
    const val ERROR_STALE_NONCE = 438
    const val ERROR_WRONG_CREDENTIALS = 441
    const val ERROR_UNSUPPORTED_TRANSPORT = 442
    const val ERROR_ALLOCATION_QUOTA = 486
    const val ERROR_INSUFFICIENT_CAPACITY = 508

    /** ChannelData 帧的 channel number 合法区间(RFC 5766 §11.2)。 */
    const val CHANNEL_MIN = 0x4000
    const val CHANNEL_MAX = 0x7FFF

    /**
     * 消息类型编码（RFC 5389 §5）：class bit0 在 bit4、bit1 在 bit8，
     * method 的高中低位分别左移 2/1/0 —— 如 Send indication(0x006,01)=0x0016、
     * Allocate success(0x003,10)=0x0103。
     */
    fun messageType(method: Int, clazz: Int): Int =
        ((method and 0x0F80) shl 2) or ((method and 0x0070) shl 1) or (method and 0x000F) or
            ((clazz and 1) shl 4) or ((clazz and 2) shl 7)

    fun methodOf(messageType: Int): Int =
        ((messageType and 0x3E00) ushr 2) or ((messageType and 0x00E0) ushr 1) or (messageType and 0x000F)

    fun classOf(messageType: Int): Int =
        ((messageType and 0x10) ushr 4) or ((messageType and 0x100) ushr 7)

    /** 传输层判别:ChannelData 帧以 0x4000-0x7FFF 开头,STUN 报文以两个 0 bit 开头。 */
    fun isChannelData(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): Boolean {
        if (length < 4) return false
        val first = packet[offset].toInt() and 0xFF
        return first in 0x40..0x7F
    }

    /** 解析 STUN 报文;非 STUN 或损坏报文返回 null。 */
    fun parse(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): StunMessage? {
        if (length < HEADER_LENGTH) return null
        if ((packet[offset].toInt() and 0xC0) != 0) return null
        val messageType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val msgLength = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
        if (readInt32(packet, offset + 4) != MAGIC_COOKIE) return null
        if (msgLength % 4 != 0 || offset + HEADER_LENGTH + msgLength > offset + length) return null
        val txId = packet.copyOfRange(offset + 8, offset + 20)
        val attributes = ArrayList<StunAttribute>(8)
        var integrityStart = -1
        var pos = offset + HEADER_LENGTH
        val end = offset + HEADER_LENGTH + msgLength
        while (pos + 4 <= end) {
            val type = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
            val valueLength = ((packet[pos + 2].toInt() and 0xFF) shl 8) or (packet[pos + 3].toInt() and 0xFF)
            if (pos + 4 + valueLength > end) return null
            if (type == ATTR_MESSAGE_INTEGRITY) integrityStart = pos
            attributes += StunAttribute(type, packet.copyOfRange(pos + 4, pos + 4 + valueLength))
            pos += 4 + ((valueLength + 3) and (4 - 1).inv())
        }
        return StunMessage(messageType, txId, attributes, if (integrityStart >= 0) integrityStart - offset else -1)
    }

    /**
     * 编码 STUN 报文。integrityKey 非空时追加 MESSAGE-INTEGRITY(RFC 5389 §15.4),
     * appendFingerprint 时追加 FINGERPRINT(§15.5):integrity 的长度字段不含 fingerprint,
     * fingerprint 的 CRC 覆盖包含 integrity 在内的前缀。
     */
    fun encode(
        messageType: Int,
        txId: ByteArray,
        attributes: List<StunAttribute>,
        integrityKey: ByteArray? = null,
        appendFingerprint: Boolean = false,
    ): ByteArray {
        require(txId.size == 12) { "transaction id 必须 12 字节" }
        val body = ByteArrayOutputStream()
        attributes.forEach { writeAttribute(body, it.type, it.value) }
        val bodySize = body.size()

        var declaredLength = bodySize
        if (integrityKey != null) declaredLength += INTEGRITY_LENGTH
        if (appendFingerprint) declaredLength += FINGERPRINT_LENGTH

        val out = ByteArray(HEADER_LENGTH + declaredLength)
        out[0] = ((messageType ushr 8) and 0xFF).toByte()
        out[1] = (messageType and 0xFF).toByte()
        writeInt32(out, 4, MAGIC_COOKIE)
        System.arraycopy(txId, 0, out, 8, 12)
        body.writeTo(out, HEADER_LENGTH)

        var writePos = HEADER_LENGTH + bodySize
        if (integrityKey != null) {
            // HMAC 输入:header(长度到 integrity 结束)+ 既有属性
            setLength(out, bodySize + INTEGRITY_LENGTH)
            val mac = hmacSha1(integrityKey, out, 0, HEADER_LENGTH + bodySize)
            writeInt32(out, writePos, (ATTR_MESSAGE_INTEGRITY shl 16) or 20)
            System.arraycopy(mac, 0, out, writePos + 4, 20)
            writePos += INTEGRITY_LENGTH
        }
        if (appendFingerprint) {
            setLength(out, declaredLength)
            val crc = CRC32().apply { update(out, 0, writePos) }
            writeInt32(out, writePos, (ATTR_FINGERPRINT shl 16) or 4)
            writeInt32(out, writePos + 4, crc.value.toInt() xor FINGERPRINT_XOR)
        }
        setLength(out, declaredLength)
        return out
    }

    /**
     * 按 RFC 5389 §15.4 校验 MESSAGE-INTEGRITY。消息不含该属性时返回 null(调用方按未认证处理),
     * 含属性时返回是否匹配。长度字段按"到 integrity 属性结束、不含其后属性"调整。
     */
    fun verifyIntegrity(packet: ByteArray, offset: Int, length: Int, key: ByteArray): Boolean? {
        val message = parse(packet, offset, length) ?: return null
        val integrityStart = message.integrityAttributeStart
        if (integrityStart < 0) return null
        val prefix = packet.copyOfRange(offset, offset + integrityStart)
        // 长度字段 = integrity 属性结束 - HEADER,即 body + 24
        val adjustedLength = integrityStart + INTEGRITY_LENGTH - HEADER_LENGTH
        prefix[2] = ((adjustedLength ushr 8) and 0xFF).toByte()
        prefix[3] = (adjustedLength and 0xFF).toByte()
        val expected = hmacSha1(key, prefix, 0, prefix.size)
        val provided = message.attributes.first { it.type == ATTR_MESSAGE_INTEGRITY }.value
        return MessageDigest.isEqual(expected, provided)
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        // (input, offset, len) 与 (output, offset, len) 两个重载参数完全同形，显式切片消歧
        val input = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        return mac.doFinal(input)
    }

    // -- 地址属性(XOR-MAPPED/PEER/RELAYED 共用编解码,RFC 5389 §15.2) --

    fun encodeXorAddress(address: InetAddress, port: Int, txId: ByteArray): ByteArray {
        val raw = address.address
        val family = when (raw.size) {
            4 -> StunFamily.IPV4
            16 -> StunFamily.IPV6
            else -> throw IllegalArgumentException("仅支持 IPv4/IPv6")
        }
        val value = ByteArray(4 + raw.size)
        value[1] = (family.code and 0xFF).toByte()
        val xPort = port xor (MAGIC_COOKIE ushr 16)
        value[2] = ((xPort ushr 8) and 0xFF).toByte()
        value[3] = (xPort and 0xFF).toByte()
        val cookie = ByteArray(4 + 12)
        writeInt32(cookie, 0, MAGIC_COOKIE)
        System.arraycopy(txId, 0, cookie, 4, 12)
        for (i in raw.indices) value[4 + i] = (raw[i].toInt() xor cookie[i].toInt()).toByte()
        return value
    }

    fun decodeXorAddress(value: ByteArray, txId: ByteArray): Pair<InetAddress, Int>? {
        if (value.size < 8) return null
        val family = StunFamily.fromCode(value[1].toInt() and 0xFF) ?: return null
        val addressLength = if (family == StunFamily.IPV4) 4 else 16
        if (value.size != 4 + addressLength) return null
        val port = ((value[2].toInt() and 0xFF) shl 8 or (value[3].toInt() and 0xFF)) xor (MAGIC_COOKIE ushr 16)
        val cookie = ByteArray(4 + 12)
        writeInt32(cookie, 0, MAGIC_COOKIE)
        System.arraycopy(txId, 0, cookie, 4, 12)
        val raw = ByteArray(addressLength)
        for (i in 0 until addressLength) raw[i] = (value[4 + i].toInt() xor cookie[i].toInt()).toByte()
        return InetAddress.getByAddress(raw) to port
    }

    /** 非 XOR 的 MAPPED-ADDRESS(RFC 5389 §15.1),Binding 响应同时携带以兼容简单客户端。 */
    fun encodeMappedAddress(address: InetAddress, port: Int): ByteArray {
        val raw = address.address
        val family = if (raw.size == 4) StunFamily.IPV4 else StunFamily.IPV6
        val value = ByteArray(4 + raw.size)
        value[1] = (family.code and 0xFF).toByte()
        value[2] = ((port ushr 8) and 0xFF).toByte()
        value[3] = (port and 0xFF).toByte()
        System.arraycopy(raw, 0, value, 4, raw.size)
        return value
    }

    /** ERROR-CODE 属性值:2B 保留 + 1B 类 + 1B 编号 + 原因短语。 */
    fun encodeErrorCode(code: Int, reason: String): ByteArray {
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
        val value = ByteArray(4 + reasonBytes.size)
        value[2] = ((code / 100) and 0xFF).toByte()
        value[3] = ((code % 100) and 0xFF).toByte()
        System.arraycopy(reasonBytes, 0, value, 4, reasonBytes.size)
        return value
    }

    fun decodeErrorCode(value: ByteArray): Int? {
        if (value.size < 4) return null
        return (value[2].toInt() and 0xFF) * 100 + (value[3].toInt() and 0xFF)
    }

    fun md5Utf8(text: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))

    private fun setLength(out: ByteArray, value: Int) {
        out[2] = ((value ushr 8) and 0xFF).toByte()
        out[3] = (value and 0xFF).toByte()
    }

    private fun writeAttribute(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
        out.write((type ushr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write((value.size ushr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
        repeat((4 - value.size % 4) % 4) { out.write(0) }
    }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

    private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    private class ByteArrayOutputStream(initial: Int = 256) {
        private var buffer = ByteArray(initial)
        private var size = 0

        fun write(bytes: Int) {
            ensure(1)
            buffer[size++] = bytes.toByte()
        }

        fun write(bytes: ByteArray) {
            ensure(bytes.size)
            System.arraycopy(bytes, 0, buffer, size, bytes.size)
            size += bytes.size
        }

        fun writeTo(target: ByteArray, offset: Int) = System.arraycopy(buffer, 0, target, offset, size)

        fun size(): Int = size

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }
    }
}

/** 解析后的 STUN 报文:类型、事务 ID 与属性列表。 */
class StunMessage(
    val messageType: Int,
    val transactionId: ByteArray,
    val attributes: List<StunAttribute>,
    /** MESSAGE-INTEGRITY 属性头在报文内的偏移;不含该属性时为 -1。 */
    val integrityAttributeStart: Int = -1,
) {
    val method: Int get() = Stun.methodOf(messageType)
    val clazz: Int get() = Stun.classOf(messageType)

    fun attr(type: Int): StunAttribute? = attributes.firstOrNull { it.type == type }
}

/** 原始属性:类型 + 值字节(不含 padding)。 */
class StunAttribute(val type: Int, val value: ByteArray)

/** STUN 地址族。 */
enum class StunFamily(val code: Int) {
    IPV4(0x01), IPV6(0x02);

    companion object {
        fun fromCode(code: Int): StunFamily? = entries.firstOrNull { it.code == code }
    }
}
