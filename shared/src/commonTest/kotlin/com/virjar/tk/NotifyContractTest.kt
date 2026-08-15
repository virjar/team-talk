package com.virjar.tk

import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyContracts
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import com.virjar.tk.protocol.payload.GenericPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NOTIFY 契约测试：锁定 [NotifyContracts]（NotifyType → payload 类型唯一事实源）。
 *
 * 防御目标：服务端 emit 类型与客户端 decode 类型的错配
 * （历史 bug：3b74b64 修的 CONTACT_APPLY、本次修的 CONTACT_ACCEPTED/DELETED）。
 * 任何一侧改动 payload 类型而未同步契约表，此测试立即失败。
 */
class NotifyContractTest {

    @Test
    fun `完备性 - 每个 NotifyType 要么有契约要么显式豁免`() {
        val covered = NotifyContracts.payloads.keys + NotifyContracts.exempt
        assertEquals(
            NotifyType.entries.toSet(),
            covered,
            "存在未登记契约也未豁免的 NotifyType，请在 NotifyContracts 登记（新增必填）",
        )
    }

    @Test
    fun `完备性 - 豁免类型不得同时有契约`() {
        val overlap = NotifyContracts.payloads.keys intersect NotifyContracts.exempt
        assertTrue(overlap.isEmpty(), "豁免类型不应同时登记契约：$overlap")
    }

    @Test
    fun `roundTrip - 每个契约 payload 编解码往返一致`() {
        for ((type, reader) in NotifyContracts.payloads) {
            val sample = sampleOf(type)
            val decoded = ProtoCodec.decode(reader, ProtoCodec.encode(sample))
            // reader 类型是 out IProto，往返后与样例 equals（data class 结构相等）
            assertEquals(sample, decoded, "round-trip 失败: $type -> ${sample::class.simpleName}")
        }
    }

    @Test
    fun `companion类名解析 - 契约表 reader 去后缀后等于样例类名`() {
        // 服务器 assertContract 用同一规则比对 emit payload 类型
        for ((type, reader) in NotifyContracts.payloads) {
            val expected = NotifyContracts.expectedPayloadClassName(type, reader::class.java.name)
            val actual = sampleOf(type)::class.java.name
            assertEquals(
                expected, actual,
                "契约 $type 的 reader($expected) 与样例类名($actual)不一致——检查登记的 companion 是否正确",
            )
        }
    }

    /** 每个契约类型的最小合法样例（新增契约时同步补充）。 */
    private fun sampleOf(type: NotifyType): IProto = when (type) {
        NotifyType.CONTACT_APPLY -> ContactApply(
            id = 1, fromUid = "u1", toUid = "u2", token = "tk", remark = "hi",
            status = 0, createdAt = 100L, fromUser = sampleUser,
        )
        NotifyType.CONTACT_ACCEPTED, NotifyType.CONTACT_DELETED -> Contact(
            uid = "u1", friendUid = "u2", remark = "r", status = 1, user = sampleUser,
        )
        NotifyType.CHAT_CREATED, NotifyType.CHAT_UPDATED, NotifyType.CHAT_DELETED,
        NotifyType.MEMBER_ADDED, NotifyType.MEMBER_REMOVED, NotifyType.MEMBER_MUTED,
        NotifyType.MEMBER_UNMUTED, NotifyType.MEMBER_ROLE_CHANGED,
        -> Chat(chatId = "c1", chatType = 2, name = "g", memberCount = 2, mutedAll = true)
        NotifyType.MESSAGE_RECV, NotifyType.TYPING -> Message(
            chatId = "c1", clientMsgId = "m1", serverSeq = 5L, senderUid = "u1",
            messageType = 1, timestamp = 1L, flags = 0,
        )
        NotifyType.CONVERSATION_UPDATED, NotifyType.CONVERSATION_DELETED -> Conversation(
            chatId = "c1", chatType = 1, chatName = "n", lastSeq = 9L, readSeq = 3L,
            unreadCount = 6, isPinned = true, peerReadSeq = 2L,
        )
        NotifyType.READ_SYNC -> ReadSyncPayload(peerUid = "u2", chatId = "c1", peerReadSeq = 7L)
        NotifyType.USER_UPDATED -> sampleUser
        NotifyType.GENERIC -> GenericPayload(extensionType = 1, data = byteArrayOf(1, 2))
        NotifyType.PRESENCE -> error("PRESENCE 已豁免，无样例")
    }

    private val sampleUser = User(uid = "u1", username = "alice", name = "Alice")
}
