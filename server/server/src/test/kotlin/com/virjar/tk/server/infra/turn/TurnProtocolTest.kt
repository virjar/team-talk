package com.virjar.tk.server.infra.turn

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STUN 编解码与 MESSAGE-INTEGRITY 的正确性：以 RFC 5769 §2.1 官方测试向量锁定实现，
 * 避免"自洽但理解错误"的系统性偏差。
 */
class TurnProtocolTest {

    // RFC 5769 §2.1 样例 Binding Request（Software "STUN test client"）
    private val rfc5769Request = hex(
        "000100582112a442b7e7a701bc34d686fa87dfae" +
            "802200105354554e207465737420636c69656e74" +
            "002400046e0001ff" +
            "80290008932ff9b151263b36" +
            "000600096576746a3a68367659202020" +
            "000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2" +
            "80280004e57a3bcf",
    )
    private val rfc5769Key = "VOkJxbRl1RmTxUk/WvJxBt".toByteArray(Charsets.UTF_8)

    @Test
    fun `RFC 5769 向量 - integrity 校验通过`() {
        val message = Stun.parse(rfc5769Request)
        assertNotNull(message)
        assertEquals(108, rfc5769Request.size)
        assertEquals("evtj:h6vY", message.attr(Stun.ATTR_USERNAME)?.value?.toString(Charsets.UTF_8))
        assertEquals(true, Stun.verifyIntegrity(rfc5769Request, 0, rfc5769Request.size, rfc5769Key))
        assertNotNull(message.attr(Stun.ATTR_FINGERPRINT))
    }

    @Test
    fun `RFC 5769 向量 - 篡改即失败`() {
        val tampered = rfc5769Request.copyOf().also { it[40] = (it[40].toInt() xor 0x01).toByte() }
        assertEquals(false, Stun.verifyIntegrity(tampered, 0, tampered.size, rfc5769Key))
        assertEquals(false, Stun.verifyIntegrity(rfc5769Request, 0, rfc5769Request.size, "wrong-key".toByteArray()))
    }

    @Test
    fun `encode 自洽 - integrity 与 fingerprint`() {
        val txId = ByteArray(12) { it.toByte() }
        val attrs = listOf(
            StunAttribute(Stun.ATTR_USERNAME, "user-1".toByteArray()),
            StunAttribute(Stun.ATTR_XOR_MAPPED_ADDRESS, Stun.encodeXorAddress(InetAddress.getByName("127.0.0.1"), 50000, txId)),
        )
        val packet = Stun.encode(
            Stun.messageType(Stun.METHOD_ALLOCATE, Stun.CLASS_SUCCESS), txId, attrs,
            integrityKey = "0123456789abcdef".toByteArray(), appendFingerprint = true,
        )
        assertEquals(true, Stun.verifyIntegrity(packet, 0, packet.size, "0123456789abcdef".toByteArray()))
        assertEquals(false, Stun.verifyIntegrity(packet, 0, packet.size, "fedcba9876543210".toByteArray()))
    }

    @Test
    fun `XOR 地址编解码 round-trip`() {
        val txId = ByteArray(12) { (it * 7).toByte() }
        listOf(
            InetAddress.getByName("127.0.0.1") to 3478,
            InetAddress.getByName("192.0.2.1") to 32853,
            InetAddress.getByName("::1") to 51000,
        ).forEach { (address, port) ->
            val decoded = Stun.decodeXorAddress(Stun.encodeXorAddress(address, port, txId), txId)
            assertEquals(address to port, decoded)
        }
    }

    @Test
    fun `非 STUN 报文返回 null`() {
        assertNull(Stun.parse(ByteArray(20)))
        assertNull(Stun.parse(ByteArray(4)))
        assertNull(Stun.parse(hex("400100002112a442" + "00".repeat(12))))
    }

    @Test
    fun `channel data 判别`() {
        assertTrue(Stun.isChannelData(hex("4001001000000000")))
        assertTrue(!Stun.isChannelData(hex("000100002112a442" + "00".repeat(12))))
        assertTrue(!Stun.isChannelData(ByteArray(3)))
    }

    @Test
    fun `消息类型编码锁定已知值`() {
        val known = listOf(
            Triple(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, 0x0001),
            Triple(Stun.METHOD_BINDING, Stun.CLASS_INDICATION, 0x0011),
            Triple(Stun.METHOD_BINDING, Stun.CLASS_SUCCESS, 0x0101),
            Triple(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST, 0x0003),
            Triple(Stun.METHOD_ALLOCATE, Stun.CLASS_SUCCESS, 0x0103),
            Triple(Stun.METHOD_REFRESH, Stun.CLASS_REQUEST, 0x0004),
            Triple(Stun.METHOD_SEND, Stun.CLASS_INDICATION, 0x0016),
            Triple(Stun.METHOD_DATA, Stun.CLASS_INDICATION, 0x0017),
            Triple(Stun.METHOD_CREATE_PERMISSION, Stun.CLASS_REQUEST, 0x0008),
            Triple(Stun.METHOD_CREATE_PERMISSION, Stun.CLASS_SUCCESS, 0x0108),
            Triple(Stun.METHOD_CHANNEL_BIND, Stun.CLASS_REQUEST, 0x0009),
        )
        known.forEach { (method, clazz, expected) ->
            val type = Stun.messageType(method, clazz)
            assertEquals(expected, type, "type(method=$method,class=$clazz)")
            assertEquals(method, Stun.methodOf(type))
            assertEquals(clazz, Stun.classOf(type))
        }
    }

    @Test
    fun `短期凭据签发与过期`() {
        val credentials = TurnCredentials("0123456789abcdef0123456789abcdef".toByteArray(), "teamtalk.local")
        val now = System.currentTimeMillis() / 1000
        val issued = credentials.issue(600)
        assertNotNull(credentials.longTermKey(issued.username, now))
        assertNull(credentials.longTermKey("0", now))
        assertNull(credentials.longTermKey("${now - 1}", now))
        assertNull(credentials.longTermKey("garbage", now))
        // 签发凭据可推导出与 verify 一致的 key（ByteArray 按 contentEquals 比较）
        val viaVerify = credentials.verify(issued.username, issued.password, now)!!
        val viaLongTermKey = credentials.longTermKey(issued.username, now)!!
        assertTrue(viaVerify.contentEquals(viaLongTermKey))
    }

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
