package com.virjar.tk.server.integration

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.repository.ExposedChatAccessSource
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.server.protocol.connection.authorizeTypingDelivery
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthoritativeChatAccessIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `another node membership removal immediately closes every content read boundary`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("authoritative-owner"))
        val member = ctx.registerUser(uniqueUsername("authoritative-member"))
        val group = ctx.chatService.createGroup("Authoritative ACL", null, owner, listOf(member))
        ctx.chatService.setRole(owner, group.chatId, member, role = 1)
        ctx.chatService.createInviteLink(owner, group.chatId, "private", maxUses = 0, expiresAt = 0)
        ctx.messageService.sendMessage(
            owner,
            Message(
                chatId = group.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = owner,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = 0L,
                body = buildRichTextBody("classified"),
            ),
        )

        val upload = File.createTempFile("authoritative-chat-access", ".txt").apply {
            writeText("private attachment")
        }
        val path = ctx.fileStore.store(owner, "private.txt", "text/plain", upload)
        val attachment = requireNotNull(ctx.fileStore.getAttachment(path))
        ctx.groupFileService.createFile(
            owner,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            group.chatId,
            null,
            "private.txt",
            attachment,
        )

        // 模拟第二个服务器：显式把有界聚合成员快照加载进本进程，
        // 而另一个进程提交了撤销且无法在此发布本地失效回调。当没有聚合快照驻留时，
        // 点查寻有意绕过成员缓存，因此它自己无法建立这个测试夹具。
        assertTrue(ctx.chatStore.getMembers(group.chatId).any { it.uid == member })
        assertTrue(ctx.attachmentAccess.canRead(member, path))
        val admittedTyping = authorizeTypingDelivery(ctx.chatAccess, member, typingMessage(group.chatId, owner))
        assertEquals(member, admittedTyping.message.senderUid, "authenticated uid must replace the declared sender")
        assertEquals(listOf(owner), admittedTyping.recipientUids)
        val revokedRows = transaction(ctx.database) {
            GroupMembers.update({
                (GroupMembers.chatId eq group.chatId) and (GroupMembers.uid eq member)
            }) {
                it[status] = 0
            }
        }
        assertEquals(1, revokedRows, "the simulated peer must commit exactly one revocation")
        assertNotNull(ctx.chatStore.getMember(group.chatId, member), "fixture must retain the stale process cache")

        assertFailsWith<ChatAccessDeniedException> { ctx.chatService.getChatFor(member, group.chatId) }
        assertFailsWith<ChatAccessDeniedException> { ctx.chatService.getMembersFor(member, group.chatId) }
        assertFailsWith<ChatAccessDeniedException> { ctx.chatService.listInviteLinks(member, group.chatId) }
        assertFailsWith<ChatAccessDeniedException> { ctx.groupFileService.list(member, group.chatId, null) }
        assertFailsWith<ChatAccessDeniedException> {
            ctx.messageService.getHistory(member, group.chatId, Long.MAX_VALUE, 10)
        }
        assertFailsWith<ChatAccessDeniedException> {
            ctx.messageService.searchMessages(member, group.chatId, "classified", 10)
        }
        assertTrue(ctx.conversationService.listConversations(member).none { it.chatId == group.chatId })
        assertFalse(ctx.attachmentAccess.canRead(member, path))
        assertFailsWith<ChatAccessDeniedException> {
            authorizeTypingDelivery(ctx.chatAccess, member, typingMessage(group.chatId, owner))
        }
    }

    @Test
    fun `managed chat projection pending state fails closed`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("pending-projection-owner"))
        val group = ctx.chatService.createGroup("Pending Projection", null, owner, emptyList())

        transaction(ctx.database) {
            OrganizationManagedChatProjections.insert {
                it[unitId] = UUID.randomUUID().toString()
                it[chatId] = group.chatId
                it[desiredRevision] = 2L
                it[appliedRevision] = 1L
                it[desiredActive] = true
                it[attemptCount] = 0
                it[nextAttemptAt] = 0L
                it[updatedAt] = System.currentTimeMillis()
            }
        }

        assertFailsWith<ChatAccessDeniedException> {
            ctx.chatService.getChatFor(owner, group.chatId)
        }
        assertFailsWith<ChatAccessDeniedException> {
            authorizeTypingDelivery(ctx.chatAccess, owner, typingMessage(group.chatId, "spoofed"))
        }
        assertTrue(ctx.conversationService.listConversations(owner).none { it.chatId == group.chatId })
    }

    @Test
    fun `accessible chat read fails closed before an over-cap projection is materialized`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bounded-access-owner"))
        ctx.chatService.createGroup("First bounded ACL group", null, owner, emptyList())
        ctx.chatService.createGroup("Second bounded ACL group", null, owner, emptyList())

        val deliberatelyTightSource = ExposedChatAccessSource(
            database = ctx.database,
            maxAccessibleChats = 1,
        )
        assertFailsWith<IllegalStateException> {
            deliberatelyTightSource.listAccessibleChatIds(owner)
        }
    }

    private fun typingMessage(chatId: String, declaredSenderUid: String) = Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = declaredSenderUid,
        messageType = MessageType.TYPING.code,
        timestamp = 0L,
    )
}
