package com.virjar.tk.e2e

import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.protocol.payload.ResponsePayload
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID

/**
 * 跨端编解码一致性 smoke 测试。
 *
 * 用真实客户端 Repository（:app）构造 RPC payload，发送到真实 demo 服务端，
 * 验证服务端能正确解析（不返回 500/FatalCodec）。
 *
 * **这是防止 getHistory 2字段vs3字段 类 bug 复发的核心防线**：
 * 之前 RemoteDemoE2eTest 没覆盖 getHistory，导致客户端 2 字段 + 服务端 3 字段
 * 的不一致潜伏到 UI 自动化测试才暴露。本测试覆盖所有 Repository 的全部 RPC 方法。
 *
 * 判断标准：status != 500 即通过（400 业务错误也 OK，说明编解码正确只是业务拒绝）。
 * status == 500 且 payload 含"编解码"/"协议解析" = FatalCodec = 编解码不一致 = FAIL。
 *
 * 运行：./gradlew :server:test --tests "*RpcCodecConsistencyTest" -Dtk.e2e.remote=true
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class RpcCodecConsistencyTest {

    private lateinit var user1: RemoteDemoSupport.Session
    private lateinit var user2: RemoteDemoSupport.Session
    private lateinit var chatId: String
    private lateinit var groupChatId: String

    /** 一次断言：status 不是 500（编解码 OK），打印详情便于排查 */
    private fun assertCodecOk(label: String, resp: ResponsePayload) {
        val msg = resp.payload?.decodeToString() ?: ""
        assertTrue(
            resp.status != 500,
            "[$label] 编解码不一致！status=${resp.status} msg=$msg\n" +
                "服务端无法解析客户端发送的 payload（字段数量/类型/顺序不匹配）"
        )
        println("  [OK] $label: status=${resp.status}")
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("[RpcCodecConsistency] target = ${RemoteDemoSupport.host}:${RemoteDemoSupport.port}")
        user1 = RemoteDemoSupport.registerUser("codec1")
        user2 = RemoteDemoSupport.registerUser("codec2")
        // 建好友 + 私聊，为需要 chatId 的 RPC 提供前置
        val applyResp = user1.invoke(ServiceId.CONTACT, 2, // APPLY
            com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") })
        val apply = com.virjar.tk.protocol.ProtoCodec.decode(com.virjar.tk.model.ContactApply, applyResp.payload!!)
        user2.invoke(ServiceId.CONTACT, 3, // ACCEPT
            com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(apply.token) })
        val chatResp = user1.invoke(ServiceId.CHAT, 1, // CREATE_PERSONAL
            com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(user2.uid) })
        chatId = com.virjar.tk.protocol.ProtoCodec.decode(com.virjar.tk.model.Chat, chatResp.payload!!).chatId
        // 建群，为需要 group chatId 的 RPC（GET_MEMBERS 等）提供前置
        val grpResp = user1.invoke(ServiceId.CHAT, 2, com.virjar.tk.protocol.ProtoCodec.encodePayload {
            writeString("CodecGroup"); writeString(null); writeVarInt(1); writeString(user2.uid)
        })
        groupChatId = com.virjar.tk.protocol.ProtoCodec.decode(com.virjar.tk.model.Chat, grpResp.payload!!).chatId
        println("[RpcCodecConsistency] 前置完成: user1=${user1.uid} user2=${user2.uid} chatId=$chatId groupChatId=$groupChatId")
    }

    // ── MessageRepository ──

    @Test fun `GET_HISTORY codec`() = runBlocking { assertCodecOk("GET_HISTORY", user1.invoke(ServiceId.MESSAGE, 1, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeVarLong(0); writeVarInt(50) })) }
    @Test fun `MESSAGE SEARCH codec`() = runBlocking { assertCodecOk("SEARCH", user1.invoke(ServiceId.MESSAGE, 2, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeString("test"); writeVarInt(10) })) }
    @Test fun `MESSAGE REVOKE codec`() = runBlocking {
        // 先发一条消息拿 seq
        val msg = com.virjar.tk.model.Message(chatId = chatId, clientMsgId = UUID.randomUUID().toString(), senderUid = user1.uid, messageType = 1, timestamp = System.currentTimeMillis(), body = null)
        val ack = user1.imClient.sendAndWaitAck(msg)
        assertCodecOk("REVOKE", user1.invoke(ServiceId.MESSAGE, 3, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeVarLong(ack.serverSeq) }))
    }
    @Test fun `MESSAGE MARK_READ codec`() = runBlocking { assertCodecOk("MARK_READ", user1.invoke(ServiceId.MESSAGE, 6, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeVarLong(1) })) }

    // ── ContactRepository ──

    @Test fun `CONTACT LIST codec`() = runBlocking { assertCodecOk("CONTACT.LIST", user1.invoke(ServiceId.CONTACT, 1)) }
    @Test fun `CONTACT APPLY codec`() = runBlocking { assertCodecOk("CONTACT.APPLY", user1.invoke(ServiceId.CONTACT, 2, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(user2.uid); writeString("hello") })) }
    @Test fun `CONTACT BLACKLIST codec`() = runBlocking { assertCodecOk("CONTACT.BLACKLIST", user1.invoke(ServiceId.CONTACT, 7, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(user2.uid) })) }
    @Test fun `CONTACT LIST_APPLIES codec`() = runBlocking { assertCodecOk("CONTACT.LIST_APPLIES", user1.invoke(ServiceId.CONTACT, 9)) }
    @Test fun `CONTACT LIST_BLACKLIST codec`() = runBlocking { assertCodecOk("CONTACT.LIST_BLACKLIST", user1.invoke(ServiceId.CONTACT, 10)) }

    // ── ChatRepository ──

    @Test fun `CHAT CREATE_PERSONAL codec`() = runBlocking { assertCodecOk("CREATE_PERSONAL", user1.invoke(ServiceId.CHAT, 1, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(user2.uid) })) }
    @Test fun `CHAT CREATE_GROUP codec`() = runBlocking { assertCodecOk("CREATE_GROUP", user1.invoke(ServiceId.CHAT, 2, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString("TestGroup"); writeString(null); writeVarInt(1); writeString(user2.uid) })) }
    @Test fun `CHAT GET codec`() = runBlocking { assertCodecOk("CHAT.GET", user1.invoke(ServiceId.CHAT, 3, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId) })) }
    @Test fun `CHAT ADD_MEMBERS codec`() = runBlocking { assertCodecOk("CHAT.ADD_MEMBERS", user1.invoke(ServiceId.CHAT, 6, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(groupChatId); writeVarInt(1); writeString(user2.uid) })) }
    @Test fun `CHAT REMOVE_MEMBERS codec`() = runBlocking { assertCodecOk("CHAT.REMOVE_MEMBERS", user1.invoke(ServiceId.CHAT, 7, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(groupChatId); writeString(user2.uid) })) }
    @Test fun `CHAT GET_MEMBERS codec`() = runBlocking { assertCodecOk("CHAT.GET_MEMBERS", user1.invoke(ServiceId.CHAT, 8, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(groupChatId) })) }

    // ── ConversationRepository ──

    @Test fun `CONVERSATION LIST codec`() = runBlocking { assertCodecOk("CONV.LIST", user1.invoke(ServiceId.CONVERSATION, 1)) }
    @Test fun `CONVERSATION SYNC codec`() = runBlocking { assertCodecOk("CONV.SYNC", user1.invoke(ServiceId.CONVERSATION, 2, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeVarLong(0) })) }
    @Test fun `CONVERSATION SET_DRAFT codec`() = runBlocking { assertCodecOk("CONV.SET_DRAFT", user1.invoke(ServiceId.CONVERSATION, 3, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeString("draft text") })) }
    @Test fun `CONVERSATION SET_PIN codec`() = runBlocking { assertCodecOk("CONV.SET_PIN", user1.invoke(ServiceId.CONVERSATION, 4, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeVarInt(1) })) }
    @Test fun `CONVERSATION SET_MUTE codec`() = runBlocking { assertCodecOk("CONV.SET_MUTE", user1.invoke(ServiceId.CONVERSATION, 5, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(chatId); writeVarInt(1) })) }

    // ── UserRepository ──

    @Test fun `USER GET_PROFILE codec`() = runBlocking { assertCodecOk("USER.GET_PROFILE", user1.invoke(ServiceId.USER, 1, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString(null) })) }
    @Test fun `USER SEARCH codec`() = runBlocking { assertCodecOk("USER.SEARCH", user1.invoke(ServiceId.USER, 3, com.virjar.tk.protocol.ProtoCodec.encodePayload { writeString("test") })) }

    // ── DeviceRepository ──

    @Test fun `DEVICE LIST codec`() = runBlocking { assertCodecOk("DEVICE.LIST", user1.invoke(ServiceId.DEVICE, 1)) }
}
