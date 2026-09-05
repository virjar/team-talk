package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.message.MessageReactionService
import com.virjar.tk.server.infra.db.MessageReactions
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CLIENT-05 表情回应：聚合存储、幂等增删、成员校验、事件与撤回清理。
 */
class MessageReactionIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private suspend fun sendText(senderUid: String, chatId: String, text: String): Long {
        val msg = com.virjar.tk.protocol.model.Message(
            chatId = chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = com.virjar.tk.protocol.MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = com.virjar.tk.protocol.body.buildRichTextBody(text),
        )
        return ctx.messageService.sendMessage(senderUid, msg)
    }

    @Test
    fun `add is idempotent, aggregates are authoritative and events reach every member`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("react-sender"))
        val member = ctx.registerUser(uniqueUsername("react-member"))
        val outsider = ctx.registerUser(uniqueUsername("react-outsider"))
        val chat = ctx.chatService.createGroup(java.util.UUID.randomUUID().toString(), "reaction-group", null, sender, listOf(member))
        val seq = sendText(sender, chat.chatId, "react to me")
        val baselines = listOf(sender, member).associateWith(::latestEventSeq)

        ctx.reactionService.addReaction(member, chat.chatId, seq, "👍")
        ctx.reactionService.addReaction(sender, chat.chatId, seq, "👍")
        // 重复添加是幂等成功，不产生新事件
        ctx.reactionService.addReaction(sender, chat.chatId, seq, "👍")

        val summary = ctx.reactionService.listReactions(member, chat.chatId, seq, seq).single()
        assertEquals(seq, summary.serverSeq)
        assertEquals(
            listOf(MessageReactionGroup("👍", listOf(member, sender))),
            summary.groups,
        )

        // 两个成员各收到一次 MESSAGE_REACTION；重复添加不再追加事件
        listOf(sender, member).forEach { uid ->
            assertEquals(
                listOf(NotifyType.MESSAGE_REACTION, NotifyType.MESSAGE_REACTION),
                eventTypesAfter(uid, baselines.getValue(uid)),
            )
            reactionEventsAfter(uid, baselines.getValue(uid)).let { payloads ->
                assertEquals(2, payloads.size)
                assertTrue(payloads.any { it.actorUid == member && it.emoji == "👍" && it.added })
                assertTrue(payloads.any { it.actorUid == sender && it.emoji == "👍" && it.added })
            }
        }

        // 非成员不能读聚合
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.listReactions(outsider, chat.chatId, seq, seq)
        }
    }

    @Test
    fun `remove is idempotent and a later re-add emits again`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("rm-sender"))
        val member = ctx.registerUser(uniqueUsername("rm-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "remove me")

        ctx.reactionService.addReaction(member, chat.chatId, seq, "🎉")
        ctx.reactionService.removeReaction(member, chat.chatId, seq, "🎉")
        // 重复删除幂等成功
        ctx.reactionService.removeReaction(member, chat.chatId, seq, "🎉")

        assertTrue(ctx.reactionService.listReactions(sender, chat.chatId, seq, seq).isEmpty())

        val baseline = latestEventSeq(sender)
        ctx.reactionService.addReaction(member, chat.chatId, seq, "🎉")
        assertEquals(
            listOf(NotifyType.MESSAGE_REACTION),
            eventTypesAfter(sender, baseline),
        )
        assertEquals(1, reactionRows(chat.chatId, seq))
    }

    @Test
    fun `membership and revoked-target validation`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("val-sender"))
        val member = ctx.registerUser(uniqueUsername("val-member"))
        val outsider = ctx.registerUser(uniqueUsername("val-outsider"))
        val chat = ctx.chatService.createGroup(java.util.UUID.randomUUID().toString(), "reaction-gate", null, sender, listOf(member))
        val seq = sendText(sender, chat.chatId, "gate")

        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(outsider, chat.chatId, seq, "👍")
        }
        // 不存在的消息
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(member, chat.chatId, seq + 999L, "👍")
        }
        // 非法 emoji
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(member, chat.chatId, seq, "")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(member, chat.chatId, seq, "x".repeat(65))
        }

        ctx.reactionService.addReaction(member, chat.chatId, seq, "👍")
        // 离群后不能再回应，但历史行保留为事实
        ctx.chatService.removeMember(sender, chat.chatId, member)
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(member, chat.chatId, seq, "👍")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.removeReaction(member, chat.chatId, seq, "👍")
        }
        assertEquals(1, reactionRows(chat.chatId, seq))
    }

    @Test
    fun `revoke clears reactions atomically with the revoke projection`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("revoke-sender"))
        val member = ctx.registerUser(uniqueUsername("revoke-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "to be revoked")

        ctx.reactionService.addReaction(member, chat.chatId, seq, "👍")
        ctx.messageService.revokeMessage(sender, chat.chatId, seq)

        assertTrue(ctx.reactionService.listReactions(sender, chat.chatId, seq, seq).isEmpty())
        assertEquals(0, reactionRows(chat.chatId, seq))

        // 撤回后的消息不能再回应
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(member, chat.chatId, seq, "👍")
        }
    }

    @Test
    fun `per-user distinct emoji cap holds and repeated favourites stay idempotent`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("cap-sender"))
        val member = ctx.registerUser(uniqueUsername("cap-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "cap me")

        val emojis = (0 until MessageReactionService.MAX_DISTINCT_EMOJIS_PER_USER_MESSAGE)
            .map { index -> "e${'a' + index}" }
        emojis.forEach { emoji ->
            ctx.reactionService.addReaction(member, chat.chatId, seq, emoji)
        }
        // 第 13 个不同 emoji 被拒绝；已有 emoji 的重复添加仍然成功
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.addReaction(member, chat.chatId, seq, "extra")
        }
        ctx.reactionService.addReaction(member, chat.chatId, seq, emojis.first())

        val summary = ctx.reactionService.listReactions(sender, chat.chatId, seq, seq).single()
        assertEquals(
            MessageReactionService.MAX_DISTINCT_EMOJIS_PER_USER_MESSAGE,
            summary.groups.size,
        )
    }

    @Test
    fun `list range validation and empty windows`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("range-sender"))
        val member = ctx.registerUser(uniqueUsername("range-member"))
        val chat = ctx.chatService.createPersonalChat(sender, member)
        val seq = sendText(sender, chat.chatId, "range")

        assertTrue(ctx.reactionService.listReactions(sender, chat.chatId, 1, seq).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.listReactions(sender, chat.chatId, 0, seq)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.reactionService.listReactions(sender, chat.chatId, seq + 1, seq)
        }
    }

    private fun latestEventSeq(uid: String): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SyncEvents.streamSeq)
            ?: 0L
    }

    private fun eventTypesAfter(uid: String, afterSeq: Long): List<NotifyType> = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.streamSeq greater afterSeq)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { row -> NotifyType.fromCode(row[SyncEvents.eventType]) }
    }

    private fun reactionEventsAfter(uid: String, afterSeq: Long): List<MessageReactionEventPayload> =
        transaction(ctx.database) {
            SyncEvents.selectAll().where {
                (SyncEvents.uid eq uid) and
                    (SyncEvents.streamSeq greater afterSeq) and
                    (SyncEvents.eventType eq NotifyType.MESSAGE_REACTION.code)
            }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
                .map { row -> ProtoCodec.decode(MessageReactionEventPayload, row[SyncEvents.payload]) }
        }

    private fun reactionRows(chatId: String, serverSeq: Long): Int = transaction(ctx.database) {
        MessageReactions.selectAll().where {
            (MessageReactions.chatId eq chatId) and (MessageReactions.serverSeq eq serverSeq)
        }.count().toInt()
    }
}
