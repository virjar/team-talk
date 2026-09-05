package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.body.buildRichTextBody
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CLIENT-08 保存的消息：私有 saved 会话语义、复制保存、幂等重放与源撤回隔离。
 */
class SavedMessageIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private suspend fun sendText(senderUid: String, chatId: String, text: String): Long {
        val msg = Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody(text),
        )
        return ctx.messageService.sendMessage(senderUid, msg)
    }

    @Test
    fun `saved chat is a singleton per user with fixed display name`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("saved-owner"))
        val first = ctx.chatService.getOrCreateSavedChat(uid)
        val second = ctx.chatService.getOrCreateSavedChat(uid)

        assertEquals(3, first.chatType)
        assertEquals(first.chatId, second.chatId, "同一用户必须复用同一个 saved 会话")

        val conversation = ctx.conversationService.listConversations(uid)
            .first { it.chatId == first.chatId }
        assertEquals("保存的消息", conversation.chatName)
        assertEquals(3, conversation.chatType)
        assertEquals(null, conversation.peerUid)
        assertEquals(0L, conversation.lastSeq)
        assertEquals(null, conversation.lastMsgTimestamp)
        assertEquals(false, conversation.isPinned)

        val other = ctx.registerUser(uniqueUsername("saved-other"))
        val otherChat = ctx.chatService.getOrCreateSavedChat(other)
        assertTrue(otherChat.chatId != first.chatId, "不同用户各自拥有独立 saved 会话")
    }

    @Test
    fun `save copies the source as own forwarded message and replay returns the same copy`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("save-sender"))
        val member = ctx.registerUser(uniqueUsername("save-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "save me please")

        val baseline = latestEventSeq(member)
        val saveStartedAt = System.currentTimeMillis()
        val saved = ctx.messageService.saveMessage(member, chat.chatId, seq, "save-op-1")
        val savedChat = ctx.chatService.getOrCreateSavedChat(member)

        assertEquals(savedChat.chatId, saved.chatId, "副本必须落在保存者的 saved 会话")
        assertEquals(member, saved.senderUid, "副本发送者是保存者本人")
        assertEquals("save-op-1", saved.clientMsgId)
        assertTrue(saved.flags and Message.FLAG_FORWARDED != 0)
        assertTrue(saved.serverSeq > 0)
        assertTrue(saved.timestamp >= saveStartedAt, "副本使用首次保存时间，而非源消息时间")
        val savedConversation = ctx.conversationService.listConversations(member)
            .single { it.chatId == savedChat.chatId }
        assertEquals(saved.timestamp, savedConversation.lastMsgTimestamp)
        assertEquals(0, savedConversation.unreadCount, "本人保存的消息不新增未读")

        // 保存者收到 CHAT_CREATED(saved) + MESSAGE_RECV(副本) + CONVERSATION_UPDATED
        val events = eventTypesAfter(member, baseline)
        assertTrue(NotifyType.CHAT_CREATED in events, "首次保存应创建 saved 会话: $events")
        assertTrue(NotifyType.MESSAGE_RECV in events)

        // 同 operationId 重放返回原副本，不产生第二条
        val replay = ctx.messageService.saveMessage(member, chat.chatId, seq, "save-op-1")
        assertEquals(saved.serverSeq, replay.serverSeq)
        assertEquals(saved.timestamp, replay.timestamp, "幂等重放不能改变首次保存时间")
        assertEquals(
            1,
            ctx.messageService.getHistory(member, savedChat.chatId, 0, 10).size,
            "重放不得产生第二条保存消息",
        )

        // 用户设置只改变自己的列表状态，不把旧消息伪装成刚刚收到。
        ctx.conversationService.setPin(member, savedChat.chatId, true)
        ctx.conversationService.setPin(member, savedChat.chatId, false)
        ctx.conversationService.setMute(member, savedChat.chatId, true)
        ctx.conversationService.setDraft(member, savedChat.chatId, "private draft")
        ctx.conversationService.markRead(member, savedChat.chatId, saved.serverSeq)
        val afterSettings = ctx.conversationService.listConversations(member)
            .single { it.chatId == savedChat.chatId }
        assertEquals(saved.timestamp, afterSettings.lastMsgTimestamp)
        assertEquals(saved.serverSeq, afterSettings.lastSeq)
        assertEquals(0, afterSettings.unreadCount)

        // 第二条不同源消息用不同 operationId 正常保存
        val seq2 = sendText(sender, chat.chatId, "another one")
        val saved2 = ctx.messageService.saveMessage(member, chat.chatId, seq2, "save-op-2")
        assertTrue(saved2.serverSeq > saved.serverSeq)
    }

    @Test
    fun `source revocation after save keeps the copy intact and later saves of revoked source fail`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("revoke-sender"))
        val member = ctx.registerUser(uniqueUsername("revoke-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "to be revoked later")

        val saved = ctx.messageService.saveMessage(member, chat.chatId, seq, "save-before-revoke")
        ctx.messageService.revokeMessage(sender, chat.chatId, seq)

        // 副本独立保留，内容不再随源撤回变化
        val copy = ctx.messageService.getHistory(member, saved.chatId, 0, 10)
            .first { it.serverSeq == saved.serverSeq }
        assertTrue(copy.flags and Message.FLAG_REVOKED == 0, "源撤回不得影响已保存副本")

        // 撤回后的源不能再保存
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.saveMessage(member, chat.chatId, seq, "save-after-revoke")
        }

        // 但首次成功后的同 operationId 重放仍命中原收据（丢响应重试场景）
        val replay = ctx.messageService.saveMessage(member, chat.chatId, seq, "save-before-revoke")
        assertEquals(saved.serverSeq, replay.serverSeq)
        assertEquals(saved.timestamp, replay.timestamp)
    }

    @Test
    fun `saving requires source membership and saved chat is searchable`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("acl-sender"))
        val member = ctx.registerUser(uniqueUsername("acl-member"))
        val outsider = ctx.registerUser(uniqueUsername("acl-outsider"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "acl target")

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.saveMessage(outsider, chat.chatId, seq, "save-outside")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.saveMessage(member, chat.chatId, seq + 999, "save-missing")
        }

        ctx.messageService.saveMessage(member, chat.chatId, seq, "save-search")
        val hits = ctx.messageService.searchMessages(member, "", "acl target", 10)
        assertTrue(hits.any { it.chatId != chat.chatId }, "全局搜索应覆盖 saved 会话中的副本")
    }

    @Test
    fun `revoking a saved copy removes it from the saved conversation`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("del-sender"))
        val member = ctx.registerUser(uniqueUsername("del-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "delete this copy")

        val saved = ctx.messageService.saveMessage(member, chat.chatId, seq, "save-del")
        ctx.messageService.revokeMessage(member, saved.chatId, saved.serverSeq)
        val copy = ctx.messageService.getHistory(member, saved.chatId, 0, 10)
            .first { it.serverSeq == saved.serverSeq }
        assertTrue(copy.flags and Message.FLAG_REVOKED != 0, "删除收藏即撤回该自有副本")
    }

    @Test
    fun `rich attachment source keeps its references in the saved copy`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("rich-sender"))
        val member = ctx.registerUser(uniqueUsername("rich-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val path = java.io.File.createTempFile("saved-asset-", ".tmp")
            .apply { writeText("attachment body") }
            .let { source ->
                try {
                    ctx.fileStore.store(sender, "saved-photo.png", "image/png", source)
                } finally {
                    source.delete()
                }
            }
        val asset = com.virjar.tk.protocol.model.EmbeddedAsset(
            assetId = UUID.randomUUID().toString(),
            attachment = requireNotNull(ctx.fileStore.getAttachment(path)),
        )
        val msg = Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = sender,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = com.virjar.tk.protocol.body.buildRichTextBody(
                "![saved](" + com.virjar.tk.protocol.model.EmbeddedAsset.uri(asset.assetId) + ")",
                listOf(asset),
            ),
        )
        val seq = ctx.messageService.sendMessage(sender, msg)

        val saved = ctx.messageService.saveMessage(member, chat.chatId, seq, "save-rich")
        val copy = ctx.messageService.getHistory(member, saved.chatId, 0, 10)
            .first { it.serverSeq == saved.serverSeq }
        val body = copy.body as com.virjar.tk.protocol.body.RichTextBody
        assertTrue(
            body.markdown.contains(com.virjar.tk.protocol.model.EmbeddedAsset.uri(asset.assetId)),
            "副本必须保留资产引用",
        )
        assertTrue(ctx.attachmentAccess.canRead(member, path), "保存者通过副本获得资产读取权")
    }

    private fun latestEventSeq(uid: String): Long = transaction(ctx.database) {
        com.virjar.tk.server.infra.db.SyncEvents.selectAll().where { com.virjar.tk.server.infra.db.SyncEvents.uid eq uid }
            .orderBy(com.virjar.tk.server.infra.db.SyncEvents.streamSeq, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(com.virjar.tk.server.infra.db.SyncEvents.streamSeq)
            ?: 0L
    }

    private fun eventTypesAfter(uid: String, afterSeq: Long): List<NotifyType> = transaction(ctx.database) {
        com.virjar.tk.server.infra.db.SyncEvents.selectAll().where {
            (com.virjar.tk.server.infra.db.SyncEvents.uid eq uid) and
                (com.virjar.tk.server.infra.db.SyncEvents.streamSeq greater afterSeq)
        }.orderBy(com.virjar.tk.server.infra.db.SyncEvents.streamSeq, org.jetbrains.exposed.sql.SortOrder.ASC)
            .map { row -> NotifyType.fromCode(row[com.virjar.tk.server.infra.db.SyncEvents.eventType]) }
    }
}
