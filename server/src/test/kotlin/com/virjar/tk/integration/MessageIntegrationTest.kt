package com.virjar.tk.integration

import com.virjar.tk.body.EditBody
import com.virjar.tk.body.CardBody
import com.virjar.tk.body.ForwardBody
import com.virjar.tk.body.GenericPayload
import com.virjar.tk.body.InteractiveCardBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.ReactionBody
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.RevokeBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.buildMentionMarkdown
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.model.Message
import com.virjar.tk.model.ProfilePatch
import com.virjar.tk.model.ProfilePatchValue
import com.virjar.tk.protocol.ExtensionType
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageIntegrationTest {

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
    fun `send message returns seq`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val seq = sendText(uid1, chat.chatId, "Hello")
        assertTrue(seq > 0)
    }

    @Test
    fun `server rejects client-created generic messages with an unregistered extension`() = runTest {
        val sender = ctx.registerUser()
        val peer = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val unknownCode = generateSequence(1) { it + 1 }
            .first { ExtensionType.fromCode(it) == null }
        val message = Message(
            chatId = chat.chatId,
            clientMsgId = "unregistered-generic",
            senderUid = sender,
            messageType = MessageType.GENERIC.code,
            timestamp = 1,
            body = GenericPayload(unknownCode, byteArrayOf(1, 2, 3)),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(sender, message)
        }

        assertTrue(failure.message.orEmpty().contains("未登记的消息扩展类型"))
        assertTrue(ctx.messageService.getHistory(sender, chat.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `blacklist blocks new messages in an existing personal chat`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        ctx.contactService(uid2).blacklist(uid1)

        assertFailsWith<IllegalArgumentException> { sendText(uid1, chat.chatId, "blocked") }
        assertFailsWith<IllegalArgumentException> { sendText(uid2, chat.chatId, "also blocked") }
        assertTrue(ctx.messageService.getHistory(uid1, chat.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `get message history`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "Msg1")
        sendText(uid1, chat.chatId, "Msg2")
        sendText(uid1, chat.chatId, "Msg3")
        val history = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
        assertEquals(3, history.size)
    }

    @Test
    fun `get history with pagination`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "Msg1")
        sendText(uid1, chat.chatId, "Msg2")
        sendText(uid1, chat.chatId, "Msg3")
        val page1 = ctx.messageService.getHistory(uid1, chat.chatId, 0, 2)
        assertEquals(2, page1.size)
        // fromSeq 包含该 seq 的消息本身，取前一页最后一条的 seq-1
        val lastSeq = page1.last().serverSeq - 1
        val page2 = ctx.messageService.getHistory(uid1, chat.chatId, lastSeq, 2)
        assertEquals(1, page2.size)
    }

    @Test
    fun `revoke message`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val seq = sendText(uid1, chat.chatId, "Secret")
        ctx.messageService.revokeMessage(uid1, chat.chatId, seq)
        val history = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
        val revoked = history.first { it.serverSeq == seq }
        assertTrue(revoked.flags != 0) // 标记为已撤回
    }

    @Test
    fun `sender cannot edit or revoke group history after leaving`() = runTest {
        val owner = ctx.registerUser()
        val sender = ctx.registerUser()
        val group = ctx.chatService.createGroup("Former member operations", null, owner, listOf(sender))
        val original = Message(
            chatId = group.chatId,
            clientMsgId = "former-member-message",
            senderUid = sender,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody("original"),
        )
        val seq = ctx.messageService.sendMessage(sender, original)
        ctx.chatService.removeMember(owner, group.chatId, sender)

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.editMessage(
                sender,
                group.chatId,
                seq,
                original.copy(body = buildRichTextBody("edited after leaving")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.revokeMessage(sender, group.chatId, seq)
        }

        val stored = ctx.messageService.getHistory(owner, group.chatId, 0, 10).single()
        assertEquals(0, stored.flags)
        assertEquals("original", (stored.body as RichTextBody).markdown)
    }

    @Test
    fun `history and search reject pagination outside response budget`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "bounded query")

        listOf(0, MessageService.MAX_QUERY_PAGE_SIZE + 1, Int.MAX_VALUE).forEach { invalidLimit ->
            assertFailsWith<IllegalArgumentException> {
                ctx.messageService.getHistory(uid1, chat.chatId, 0, invalidLimit)
            }
            assertFailsWith<IllegalArgumentException> {
                ctx.messageService.searchMessages(uid1, chat.chatId, "bounded", invalidLimit)
            }
        }

        assertEquals(
            1,
            ctx.messageService.getHistory(uid1, chat.chatId, 0, MessageService.MAX_QUERY_PAGE_SIZE).size,
        )
        assertEquals(
            1,
            ctx.messageService.searchMessages(
                uid1,
                chat.chatId,
                "bounded",
                MessageService.MAX_QUERY_PAGE_SIZE,
            ).size,
        )
    }

    @Test
    fun `send message to group`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        val seq = sendText(creator, group.chatId, "Group msg")
        assertTrue(seq > 0)
    }

    @Test
    fun `search messages by keyword`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "Hello world")
        sendText(uid1, chat.chatId, "Random text")
        sendText(uid1, chat.chatId, "Hello again")
        val results = ctx.messageService.searchMessages(uid1, chat.chatId, "Hello", 10)
        assertTrue(results.size >= 2)
    }

    @Test
    fun `forward message after restart preserves target sequence and conversation`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val uid3 = ctx.registerUser()
        val chat1 = ctx.chatService.createPersonalChat(uid1, uid2)
        val chat2 = ctx.chatService.createPersonalChat(uid1, uid3)
        val seq = sendText(uid1, chat1.chatId, "Forward me")
        sendText(uid1, chat2.chatId, "Target 1")
        sendText(uid3, chat2.chatId, "Target 2")
        sendText(uid1, chat2.chatId, "Target 3")

        // 新 ChatStore 没有任何会话/maxSeq 热缓存，等价于服务进程重启。
        val restartedService = ctx.freshMessageService()
        val forwarded = restartedService.forwardMessage(uid1, chat1.chatId, seq, chat2.chatId)
        assertNotNull(forwarded)
        assertEquals(chat2.chatId, forwarded.chatId)
        assertEquals(4, forwarded.serverSeq)
        assertEquals("Forward me", (forwarded.body as RichTextBody).markdown)

        val targetHistory = restartedService.getHistory(uid1, chat2.chatId, 0, 10)
        assertEquals(listOf(4L, 3L, 2L, 1L), targetHistory.map { it.serverSeq })
        assertEquals(forwarded.clientMsgId, targetHistory.first().clientMsgId)

        val senderConversation = ctx.conversationService.listConversations(uid1)
            .first { it.chatId == chat2.chatId }
        val recipientConversation = ctx.conversationService.listConversations(uid3)
            .first { it.chatId == chat2.chatId }
        assertEquals(4, senderConversation.lastSeq)
        assertEquals(4, senderConversation.readSeq)
        assertEquals(0, senderConversation.unreadCount)
        assertEquals("Forward me", senderConversation.lastMessage)
        assertEquals(4, recipientConversation.lastSeq)
        assertEquals(2, recipientConversation.unreadCount)
    }

    @Test
    fun `blacklist blocks forwarding into an existing personal chat`() = runTest {
        val sender = ctx.registerUser()
        val sourcePeer = ctx.registerUser()
        val targetPeer = ctx.registerUser()
        val source = ctx.chatService.createPersonalChat(sender, sourcePeer)
        val target = ctx.chatService.createPersonalChat(sender, targetPeer)
        val sourceSeq = sendText(sender, source.chatId, "must not cross block")
        ctx.contactService(targetPeer).blacklist(sender)

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.forwardMessage(sender, source.chatId, sourceSeq, target.chatId)
        }
        assertTrue(ctx.messageService.getHistory(sender, target.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `member and all mute block forwarding into a group`() = runTest {
        val owner = ctx.registerUser()
        val sender = ctx.registerUser()
        val sourcePeer = ctx.registerUser()
        val source = ctx.chatService.createPersonalChat(sender, sourcePeer)
        val target = ctx.chatService.createGroup("Forward permissions", null, owner, listOf(sender))
        val sourceSeq = sendText(sender, source.chatId, "must respect mute")

        ctx.chatService.muteMember(owner, target.chatId, sender, durationSeconds = 3_600)
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.forwardMessage(sender, source.chatId, sourceSeq, target.chatId)
        }

        ctx.chatService.unmuteMember(owner, target.chatId, sender)
        ctx.chatService.muteAll(owner, target.chatId)
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.forwardMessage(sender, source.chatId, sourceSeq, target.chatId)
        }
        assertTrue(ctx.messageService.getHistory(owner, target.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `client message id dedup`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val clientMsgId = UUID.randomUUID().toString()
        val msg = Message(
            chatId = chat.chatId,
            clientMsgId = clientMsgId,
            senderUid = uid1,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody("Dedup"),
        )
        val seq1 = ctx.messageService.sendMessage(uid1, msg)
        val seq2 = ctx.messageService.sendMessage(uid1, msg)
        assertEquals(seq1, seq2)
    }

    @Test
    fun `lost ack retry remains successful after sender leaves group`() = runTest {
        val owner = ctx.registerUser()
        val sender = ctx.registerUser()
        val group = ctx.chatService.createGroup("Idempotent leave", null, owner, listOf(sender))
        val accepted = Message(
            chatId = group.chatId,
            clientMsgId = "accepted-before-sender-left",
            senderUid = sender,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody("accepted once"),
        )

        val acceptedSeq = ctx.messageService.sendMessage(sender, accepted)
        ctx.chatService.removeMember(owner, group.chatId, sender)

        // 原 ACK 即使丢失也必须可恢复；离群只阻止新的消息身份。
        assertEquals(acceptedSeq, ctx.messageService.sendMessage(sender, accepted))
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                sender,
                accepted.copy(clientMsgId = "new-after-sender-left", body = buildRichTextBody("new")),
            )
        }
        assertEquals(1, ctx.messageService.getHistory(owner, group.chatId, 0, 10).size)
    }

    @Test
    fun `lost ack retry remains successful after sender is muted`() = runTest {
        val owner = ctx.registerUser()
        val sender = ctx.registerUser()
        val group = ctx.chatService.createGroup("Idempotent mute", null, owner, listOf(sender))
        val accepted = Message(
            chatId = group.chatId,
            clientMsgId = "accepted-before-sender-muted",
            senderUid = sender,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody("accepted once"),
        )

        val acceptedSeq = ctx.messageService.sendMessage(sender, accepted)
        ctx.chatService.muteMember(owner, group.chatId, sender, durationSeconds = 3_600)

        // 同一持久化身份返回原 seq；禁言仍严格拒绝新的 clientMsgId。
        assertEquals(acceptedSeq, ctx.messageService.sendMessage(sender, accepted))
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                sender,
                accepted.copy(clientMsgId = "new-after-sender-muted", body = buildRichTextBody("new")),
            )
        }
        assertEquals(1, ctx.messageService.getHistory(owner, group.chatId, 0, 10).size)
    }

    @Test
    fun `concurrent idempotent sends project one durable message event`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val message = Message(
            chatId = chat.chatId,
            clientMsgId = "concurrent-idempotent-send",
            senderUid = uid1,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody("only once"),
        )
        val before = ctx.syncEventReader.getEventsAfter(uid2, 0, 1_000)
            .count { it.notifyType == NotifyType.MESSAGE_RECV.code }

        val sequences = coroutineScope {
            List(24) {
                async(Dispatchers.Default) { ctx.messageService.sendMessage(uid1, message) }
            }.awaitAll()
        }

        assertEquals(1, sequences.toSet().size)
        val after = ctx.syncEventReader.getEventsAfter(uid2, 0, 1_000)
            .count { it.notifyType == NotifyType.MESSAGE_RECV.code }
        assertEquals(before + 1, after)
    }

    @Test
    fun `client message id is globally unique per chat and conflicting identity is rejected`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val uid3 = ctx.registerUser()
        val chat1 = ctx.chatService.createPersonalChat(uid1, uid2)
        val chat2 = ctx.chatService.createPersonalChat(uid1, uid3)
        val clientMsgId = "shared-client-id"

        fun message(chatId: String, senderUid: String, markdown: String) = Message(
            chatId = chatId,
            clientMsgId = clientMsgId,
            senderUid = senderUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody(markdown),
        )

        val uid1Chat1 = ctx.messageService.sendMessage(uid1, message(chat1.chatId, uid1, "u1/chat1"))
        assertEquals(
            uid1Chat1,
            ctx.messageService.sendMessage(uid1, message(chat1.chatId, uid1, "u1/chat1")),
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(uid2, message(chat1.chatId, uid2, "u1/chat1"))
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(uid2, message(chat1.chatId, uid2, "u2/chat1"))
        }
        val uid1Chat2 = ctx.messageService.sendMessage(uid1, message(chat2.chatId, uid1, "u1/chat2"))

        assertTrue(uid1Chat2 > 0)
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(uid1, message(chat1.chatId, uid1, "conflicting body"))
        }
        assertEquals(1, ctx.messageService.getHistory(uid1, chat1.chatId, 0, 10).size)
    }

    @Test
    fun `mentions must resolve to current chat members before a new message is accepted`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val leavingMember = ctx.registerUser()
        val outsider = ctx.registerUser()
        val group = ctx.chatService.createGroup("Mention policy", null, owner, listOf(member, leavingMember))
        val targetSeq = sendText(member, group.chatId, "reply target")

        val validRich = Message(
            chatId = group.chatId,
            clientMsgId = "valid-rich-mention",
            senderUid = owner,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody("hello ${buildMentionMarkdown("member", member)}"),
        )
        assertTrue(ctx.messageService.sendMessage(owner, validRich) > 0)

        val invalidRich = validRich.copy(
            clientMsgId = "invalid-rich-mention",
            body = RichTextBody(
                markdown = "hello ${buildMentionMarkdown("outsider", outsider)}",
                // 伪造一个空侧信道也不能绕过 canonical Markdown 解析。
                mentions = emptyList(),
                plainText = "forged",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(owner, invalidRich)
        }

        val invalidReply = Message(
            chatId = group.chatId,
            clientMsgId = "invalid-reply-mention",
            senderUid = owner,
            messageType = MessageType.REPLY.code,
            timestamp = 1,
            body = ReplyBody(
                replyToMsgId = targetSeq.toString(),
                replyToSenderUid = member,
                content = "reply ${buildMentionMarkdown("outsider", outsider)}",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(owner, invalidReply)
        }

        // 首次接受时 mention 合法；目标之后离群不应破坏延迟幂等 ACK。
        val retryAfterLeave = validRich.copy(
            clientMsgId = "retry-after-mentioned-member-left",
            body = buildRichTextBody("ping ${buildMentionMarkdown("leaving", leavingMember)}"),
        )
        val acceptedSeq = ctx.messageService.sendMessage(owner, retryAfterLeave)
        ctx.chatService.removeMember(owner, group.chatId, leavingMember)
        assertEquals(acceptedSeq, ctx.messageService.sendMessage(owner, retryAfterLeave))

        assertEquals(3, ctx.messageService.getHistory(owner, group.chatId, 0, 10).size)
    }

    @Test
    fun `server rebuilds new message envelope and edit preserves original identity`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val beforeSend = System.currentTimeMillis()
        val forged = Message(
            chatId = chat.chatId,
            clientMsgId = "stable-client-id",
            serverSeq = 999,
            senderUid = "forged-sender",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            flags = Message.FLAG_REVOKED or Message.FLAG_FORWARDED,
            body = buildRichTextBody("original"),
            sendStatus = Message.SEND_STATUS_FAILED,
            uploadProgress = 0.8f,
        )

        val seq = ctx.messageService.sendMessage(uid1, forged)
        val afterSend = System.currentTimeMillis()
        val stored = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10).single()
        assertEquals(seq, stored.serverSeq)
        assertEquals(uid1, stored.senderUid)
        assertEquals(0, stored.flags)
        assertTrue(stored.timestamp in beforeSend..afterSend)

        ctx.messageService.editMessage(
            uid1,
            chat.chatId,
            seq,
            forged.copy(
                chatId = "forged-chat",
                clientMsgId = "forged-client-id",
                serverSeq = -1,
                senderUid = "another-forged-sender",
                timestamp = Long.MAX_VALUE,
                flags = Message.FLAG_FORWARDED,
                body = buildRichTextBody("edited"),
            ),
        )

        val edited = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10).single()
        assertEquals(stored.chatId, edited.chatId)
        assertEquals(stored.clientMsgId, edited.clientMsgId)
        assertEquals(stored.serverSeq, edited.serverSeq)
        assertEquals(stored.senderUid, edited.senderUid)
        assertEquals(stored.timestamp, edited.timestamp)
        assertEquals(stored.flags or Message.FLAG_EDITED, edited.flags)
        assertEquals("edited", (edited.body as RichTextBody).markdown)

        // 首次发送请求的延迟重试仍命中原 seq；编辑后的可变正文不应污染幂等摘要。
        assertEquals(seq, ctx.messageService.sendMessage(uid1, forged))
    }

    @Test
    fun `server rebuilds rich text derived fields from markdown`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val msg = Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = uid1,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = RichTextBody(
                markdown = "**真实正文**",
                mentions = emptyList(),
                plainText = "客户端伪造字段",
            ),
        )

        val seq = ctx.messageService.sendMessage(uid1, msg)
        val stored = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
            .first { it.serverSeq == seq }
        val storedBody = stored.body as RichTextBody
        assertEquals("**真实正文**", storedBody.markdown)
        assertEquals("真实正文", storedBody.plainText)
        assertTrue(ctx.messageService.searchMessages(uid1, chat.chatId, "客户端伪造字段", 10).isEmpty())
        assertEquals(seq, ctx.messageService.searchMessages(uid1, chat.chatId, "真实正文", 10).single().serverSeq)
        assertEquals(
            "真实正文",
            ctx.conversationService.listConversations(uid2).first { it.chatId == chat.chatId }.lastMessage,
        )
    }

    @Test
    fun `server rejects unsafe message bodies before storage`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                uid1,
                Message(
                    chatId = chat.chatId,
                    clientMsgId = "invalid-card",
                    senderUid = uid1,
                    messageType = MessageType.INTERACTIVE_CARD.code,
                    timestamp = 1,
                    body = InteractiveCardBody("{not-json"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                uid1,
                Message(
                    chatId = chat.chatId,
                    clientMsgId = "oversized-reply",
                    senderUid = uid1,
                    messageType = MessageType.REPLY.code,
                    timestamp = 1,
                    body = ReplyBody(
                        replyToMsgId = "message-1",
                        replyToSenderUid = uid2,
                        content = "a".repeat(MessageBodyPolicy.MAX_MARKDOWN_LENGTH + 1),
                    ),
                ),
            )
        }

        assertTrue(ctx.messageService.getHistory(uid1, chat.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `reply metadata is rebuilt from a target in the same chat`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val uid3 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val otherChat = ctx.chatService.createPersonalChat(uid1, uid3)
        val targetSeq = sendText(uid2, chat.chatId, "权威原文")

        val spoofed = Message(
            chatId = chat.chatId,
            clientMsgId = "reply-with-spoofed-side-channel",
            senderUid = uid1,
            messageType = MessageType.REPLY.code,
            timestamp = 1,
            body = ReplyBody(
                replyToMsgId = targetSeq.toString(),
                replyToSenderUid = uid1,
                replyToSenderName = "伪造同事",
                replySnippet = "伪造引文",
                content = "收到",
            ),
        )
        val replySeq = ctx.messageService.sendMessage(uid1, spoofed)
        val storedReply = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
            .first { it.serverSeq == replySeq }.body as ReplyBody
        assertEquals(uid2, storedReply.replyToSenderUid)
        assertEquals(assertNotNull(ctx.userRepo.findByUid(uid2)).name, storedReply.replyToSenderName)
        assertEquals("权威原文", storedReply.replySnippet)

        sendText(uid1, otherChat.chatId, "other-1")
        sendText(uid3, otherChat.chatId, "other-2")
        sendText(uid1, otherChat.chatId, "other-3")
        val otherSeq = sendText(uid3, otherChat.chatId, "other-4")
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                uid1,
                spoofed.copy(
                    clientMsgId = "cross-chat-reply",
                    body = (spoofed.body as ReplyBody).copy(replyToMsgId = otherSeq.toString()),
                ),
            )
        }
    }

    @Test
    fun `contact card metadata is rebuilt from authoritative user directory`() = runTest {
        val sender = ctx.registerUser()
        val target = ctx.registerUser()
        ctx.userService.updateProfile(
            target,
            ProfilePatch(
                name = ProfilePatchValue.Set("权威姓名"),
                avatar = ProfilePatchValue.Set("https://im.example.test/avatars/authoritative.png"),
            ),
        )
        val chat = ctx.chatService.createPersonalChat(sender, target)
        val forged = Message(
            chatId = chat.chatId,
            clientMsgId = "forged-contact-card",
            senderUid = sender,
            messageType = MessageType.CARD.code,
            timestamp = 1,
            body = CardBody(
                targetUid = target,
                targetName = "伪造姓名",
                targetAvatar = "https://evil.example.test/forged.png",
            ),
        )

        val seq = ctx.messageService.sendMessage(sender, forged)
        val stored = ctx.messageService.getHistory(sender, chat.chatId, 0, 10)
            .first { it.serverSeq == seq }.body as CardBody
        assertEquals(target, stored.targetUid)
        assertEquals("权威姓名", stored.targetName)
        assertEquals("https://im.example.test/avatars/authoritative.png", stored.targetAvatar)

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                sender,
                forged.copy(
                    clientMsgId = "missing-contact-card",
                    body = CardBody("missing-user", "不存在", "https://evil.example.test/missing.png"),
                ),
            )
        }
        assertEquals(1, ctx.messageService.getHistory(sender, chat.chatId, 0, 10).size)
    }

    @Test
    fun `operation bodies cannot enter the new message pipeline`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val operations = listOf(
            MessageType.FORWARD to ForwardBody(forwardFromMsgId = "message-1"),
            MessageType.REVOKE to RevokeBody("message-1"),
            MessageType.EDIT to EditBody("message-1", "edited"),
            MessageType.REACTION to ReactionBody("message-1", "👍"),
        )

        operations.forEachIndexed { index, (type, body) ->
            assertFailsWith<IllegalArgumentException>("$type 必须走专用操作入口") {
                ctx.messageService.sendMessage(
                    uid1,
                    Message(
                        chatId = chat.chatId,
                        clientMsgId = "operation-$index",
                        senderUid = uid1,
                        messageType = type.code,
                        timestamp = 1,
                        body = body,
                    ),
                )
            }
        }

        assertTrue(ctx.messageService.getHistory(uid1, chat.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `edit cannot turn text into an operation body`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val original = Message(
            chatId = chat.chatId,
            clientMsgId = "editable-message",
            senderUid = uid1,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = buildRichTextBody("original"),
        )
        val seq = ctx.messageService.sendMessage(uid1, original)

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.editMessage(
                uid1,
                chat.chatId,
                seq,
                original.copy(
                    messageType = MessageType.REACTION.code,
                    body = ReactionBody(seq.toString(), "👍"),
                ),
            )
        }

        val stored = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10).single()
        assertEquals(MessageType.RICH_TEXT.code, stored.messageType)
        assertEquals("original", (stored.body as RichTextBody).markdown)
    }
}
