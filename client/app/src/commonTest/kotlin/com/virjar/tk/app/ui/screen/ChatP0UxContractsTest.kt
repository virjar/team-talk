package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.GroupMemberRole
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * 办公 IM 核心 P0 交互与边界契约测试：
 * 1) 全局搜索禁止误匹配内部 UUID，且支持中文名称与备注拼音首字母检索；
 * 2) 群主/管理员可撤回群成员消息，本人撤回含 30 秒时钟容差；
 * 3) 输入法组合态与提交防抖窗口内禁止 Enter 误发送，无实际候选时允许 @ 文本直接发送。
 */
class ChatP0UxContractsTest {

    @Test
    fun `search filters ignore uuid chatId and match pinyin initials`() {
        val zhangSan = User(uid = "u-zs", username = "zhangsan", name = "张三")
        val liSi = User(uid = "u-ls", username = "lisi", name = "李四", phone = "13800138000")
        val groupConv = Conversation(
            chatId = "7b8f9c0a-beef-4d12-9012-123456abcdef",
            chatType = ChatType.GROUP.code,
            chatName = "研发架构群",
            lastMessage = "下午三点评审",
        )
        val personalConv = Conversation(
            chatId = "11223344-cafe-4000-8000-998877665544",
            chatType = ChatType.PERSONAL.code,
            peerUid = "u-zs",
            peerRevision = 1L,
            lastMessage = "收到",
        )
        val conversations = listOf(groupConv, personalConv)
        val peers = mapOf("u-zs" to zhangSan)
        val remarks = mapOf("u-zs" to "老张")

        assertEquals(emptyList(), filterConversationsForSearch(conversations, "beef", peers, remarks))
        assertEquals(emptyList(), filterConversationsForSearch(conversations, "abcdef", peers, remarks))
        assertEquals(listOf(groupConv), filterConversationsForSearch(conversations, "yfjgq", peers, remarks))
        assertEquals(listOf(personalConv), filterConversationsForSearch(conversations, "zs", peers, remarks))
        assertEquals(listOf(personalConv), filterConversationsForSearch(conversations, "LZ", peers, remarks))
        assertEquals(listOf(groupConv), filterConversationsForSearch(conversations, "评审", peers, remarks))

        val contacts = listOf(
            Contact(uid = "me", friendUid = "u-zs", remark = "老张", user = zhangSan),
            Contact(uid = "me", friendUid = "u-ls", remark = null, user = liSi),
        )
        assertEquals(listOf(zhangSan), filterContactsForSearch(contacts, "zs"))
        assertEquals(listOf(zhangSan), filterContactsForSearch(contacts, "lz"))
        assertEquals(listOf(liSi), filterContactsForSearch(contacts, "ls"))
        assertEquals(listOf(liSi), filterContactsForSearch(contacts, "13800"))
    }

    @Test
    fun `revoke action mirrors group manager privilege and self revoke clock skew window`() {
        val now = 1_000_000_000L
        fun confirmedMsg(senderUid: String, ageMillis: Long): Message = Message(
            chatId = "g-1",
            clientMsgId = "c-1",
            senderUid = senderUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = now - ageMillis,
            serverSeq = 10L,
            body = buildRichTextBody("hello"),
        )

        val unconfirmed = confirmedMsg("me", 0L).copy(serverSeq = 0L)
        assertFalse(canShowRevokeMessageAction(unconfirmed, isMe = true, isPersonal = false, isSavedChat = false, myRole = GroupMemberRole.OWNER, nowMillis = now))

        // 本人普通消息：2 分 20 秒内允许（含 30 秒容差），满 2 分 30 秒隐藏
        val withinGrace = confirmedMsg("me", 140_000L)
        val expired = confirmedMsg("me", 150_000L)
        assertTrue(canShowRevokeMessageAction(withinGrace, isMe = true, isPersonal = true, isSavedChat = false, myRole = GroupMemberRole.MEMBER, nowMillis = now))
        assertFalse(canShowRevokeMessageAction(expired, isMe = true, isPersonal = true, isSavedChat = false, myRole = GroupMemberRole.MEMBER, nowMillis = now))

        // 保存的消息：超期也可撤回/清理本人消息
        assertTrue(canShowRevokeMessageAction(expired, isMe = true, isPersonal = false, isSavedChat = true, myRole = GroupMemberRole.MEMBER, nowMillis = now))

        // 群管理员与群主可随时撤回群内他人消息；普通成员不可撤回他人消息
        val otherOld = confirmedMsg("other", 3_600_000L)
        assertFalse(canShowRevokeMessageAction(otherOld, isMe = false, isPersonal = false, isSavedChat = false, myRole = GroupMemberRole.MEMBER, nowMillis = now))
        assertTrue(canShowRevokeMessageAction(otherOld, isMe = false, isPersonal = false, isSavedChat = false, myRole = GroupMemberRole.ADMIN, nowMillis = now))
        assertTrue(canShowRevokeMessageAction(otherOld, isMe = false, isPersonal = false, isSavedChat = false, myRole = GroupMemberRole.OWNER, nowMillis = now))
        assertFalse(canShowRevokeMessageAction(otherOld, isMe = false, isPersonal = true, isSavedChat = false, myRole = GroupMemberRole.OWNER, nowMillis = now))
    }

    @Test
    fun `ime composition guard and visual enter send respect composition grace window and empty mention matches`() {
        val timeSource = TestTimeSource()
        val guard = ImeCompositionGuard(timeSource = timeSource, postCommitGrace = 50.milliseconds)

        // 输入法正在选词时拦截回车发送
        assertTrue(guard.shouldDeferEnterToIme(isComposingNow = true))
        assertFalse(
            shouldSendVisualComposerOnEnter(
                enterKeySends = true,
                hasCommandModifier = false,
                isShiftPressed = false,
                deferEnterToIme = true,
                hasActiveMentionPopup = false,
            ),
        )

        // 刚结束组合的 50ms 防抖窗口内继续拦截（macOS Cocoa 先提交文本再触发 Enter 的竞态）
        assertTrue(guard.shouldDeferEnterToIme(isComposingNow = false))
        timeSource += 30.milliseconds
        assertTrue(guard.shouldDeferEnterToIme(isComposingNow = false))

        // 超过防抖窗后恢复发送；若无实际候选弹窗，带 @word 也能直接发送
        timeSource += 25.milliseconds
        assertFalse(guard.shouldDeferEnterToIme(isComposingNow = false))
        assertTrue(
            shouldSendVisualComposerOnEnter(
                enterKeySends = true,
                hasCommandModifier = false,
                isShiftPressed = false,
                deferEnterToIme = false,
                hasActiveMentionPopup = false,
            ),
        )

        // 当且仅当实际存在可见候选列表时，让位给候选上屏
        assertFalse(
            shouldSendVisualComposerOnEnter(
                enterKeySends = true,
                hasCommandModifier = false,
                isShiftPressed = false,
                deferEnterToIme = false,
                hasActiveMentionPopup = true,
            ),
        )
    }
}
