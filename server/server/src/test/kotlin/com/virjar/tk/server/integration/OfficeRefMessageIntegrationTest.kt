package com.virjar.tk.server.integration

import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CONTENT-08 类型化办公对象引用：发送时校验对象与权限并重建安全预览；
 * 打开经各域读入口重校验；转发只复制引用与冻结快照。
 */
class OfficeRefMessageIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private fun refMessage(chatId: String, body: OfficeRefBody) = Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = "",
        messageType = MessageType.OFFICE_REF.code,
        timestamp = System.currentTimeMillis(),
        body = body,
    )

    @Test
    fun `document ref rebuilds the authoritative preview and rejects spoofed display fields`() = runTest {
        val author = ctx.registerUser(uniqueUsername("docref-author"))
        val space = ctx.documentService.createSpace(author, "ref-space", null)
        val document = ctx.documentService.createDocument(author, space.spaceId, null, "权威文档标题", "# body")

        val peer = ctx.registerUser(uniqueUsername("docref-peer"))
        val granted = ctx.documentService.upsertGrant(
            actorUid = author,
            spaceId = space.spaceId,
            principalType = com.virjar.tk.protocol.model.DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = peer,
            role = com.virjar.tk.protocol.model.DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
            issuedAt = System.currentTimeMillis(),
        )
        assertTrue(granted.policyRevision > space.policyRevision)
        val chat = ctx.chatService.createPersonalChat(author, peer)

        // 客户端伪造的 title/subtitle 必须被服务端快照覆盖
        val seq = ctx.messageService.sendMessage(
            author,
            refMessage(
                chat.chatId,
                OfficeRefBody(
                    refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                    spaceId = space.spaceId,
                    targetId = document.documentId,
                    title = "伪造标题",
                    subtitle = "伪造副标题",
                ),
            ),
        )
        assertTrue(seq > 0)

        val stored = ctx.messageService.getHistory(peer, chat.chatId, 0, 10)
            .first { it.serverSeq == seq }
        val ref = stored.body as OfficeRefBody
        assertEquals("权威文档标题", ref.title, "title 必须是服务端权威快照")
        assertTrue(ref.subtitle.isNotBlank(), "subtitle 由服务端派生")

        // 对端可按当前权限打开（现有读入口即打开校验）
        val opened = ctx.documentService.getDocument(peer, space.spaceId, document.documentId)
        assertEquals("权威文档标题", opened.title)
    }

    @Test
    fun `missing or inaccessible document refs are rejected before ack`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("docref-sender"))
        val other = ctx.registerUser(uniqueUsername("docref-other"))
        val chat = ctx.chatService.createPersonalChat(sender, other)

        // 不存在的文档
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                sender,
                refMessage(
                    chat.chatId,
                    OfficeRefBody(
                        refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                        spaceId = UUID.randomUUID().toString(),
                        targetId = UUID.randomUUID().toString(),
                        title = "ghost",
                    ),
                ),
            )
        }

        // 存在但发送者无读权限（他人空间）
        val owner = ctx.registerUser(uniqueUsername("docref-owner"))
        val space = ctx.documentService.createSpace(owner, "private-space", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "私有文档", "# x")
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                sender,
                refMessage(
                    chat.chatId,
                    OfficeRefBody(
                        refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                        spaceId = space.spaceId,
                        targetId = document.documentId,
                        title = "私有文档",
                    ),
                ),
            )
        }
    }

    @Test
    fun `revoked grant degrades opening while the frozen snapshot stays readable`() = runTest {
        val author = ctx.registerUser(uniqueUsername("docref-revoke-author"))
        val peer = ctx.registerUser(uniqueUsername("docref-revoke-peer"))
        val space = ctx.documentService.createSpace(author, "revoke-space", null)
        val document = ctx.documentService.createDocument(author, space.spaceId, null, "会被撤权的文档", "# x")
        val granted = ctx.documentService.upsertGrant(
            actorUid = author,
            spaceId = space.spaceId,
            principalType = com.virjar.tk.protocol.model.DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = peer,
            role = com.virjar.tk.protocol.model.DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
            issuedAt = System.currentTimeMillis(),
        )
        val chat = ctx.chatService.createPersonalChat(author, peer)

        val seq = ctx.messageService.sendMessage(
            author,
            refMessage(
                chat.chatId,
                OfficeRefBody(
                    refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                    spaceId = space.spaceId,
                    targetId = document.documentId,
                    title = "placeholder",
                ),
            ),
        )

        val removed = ctx.documentService.removeGrant(
            actorUid = author,
            spaceId = space.spaceId,
            principalType = com.virjar.tk.protocol.model.DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = peer,
            expectedPolicyRevision = granted.policyRevision,
            operationId = UUID.randomUUID().toString(),
            issuedAt = System.currentTimeMillis(),
        )
        assertTrue(removed.policyRevision > granted.policyRevision)

        // 冻结快照仍可读
        val stored = ctx.messageService.getHistory(peer, chat.chatId, 0, 10).first { it.serverSeq == seq }
        assertEquals("会被撤权的文档", (stored.body as OfficeRefBody).title)
        // 打开校验安全降级
        assertFailsWith<com.virjar.tk.server.domain.document.DocumentAccessDeniedException> {
            ctx.documentService.getDocument(peer, space.spaceId, document.documentId)
        }
    }

    @Test
    fun `group file ref validates membership and entry with deleted entries degrading at open time`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfref-owner"))
        val member = ctx.registerUser(uniqueUsername("gfref-member"))
        val outsider = ctx.registerUser(uniqueUsername("gfref-outsider"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "ref-group", null, owner, listOf(member))

        val path = java.io.File.createTempFile("gfref-", ".tmp").apply { writeText("entry body") }
        val attachmentPath = try {
            ctx.fileStore.store(owner, "spec.md", "text/markdown", path)
        } finally {
            path.delete()
        }
        val entryId = UUID.randomUUID().toString()
        val commandId = UUID.randomUUID().toString()
        ctx.groupFileService.createFile(
            actorUid = owner,
            entryId = entryId,
            commandId = commandId,
            chatId = group.chatId,
            parentId = null,
            name = "规格说明.md",
            declared = requireNotNull(ctx.fileStore.getAttachment(attachmentPath)),
        )

        // 成员可发送引用，预览由服务端重建
        val chat = ctx.chatService.createPersonalChat(member, outsider)
        val seq = ctx.messageService.sendMessage(
            member,
            refMessage(
                chat.chatId,
                OfficeRefBody(
                    refType = OfficeRefBody.REF_TYPE_GROUP_FILE,
                    spaceId = group.chatId,
                    targetId = entryId,
                    title = "伪造名",
                ),
            ),
        )
        val stored = ctx.messageService.getHistory(member, chat.chatId, 0, 10).first { it.serverSeq == seq }
        val ref = stored.body as OfficeRefBody
        assertEquals("规格说明.md", ref.title, "群文件引用预览必须是权威条目名")
        assertTrue(ref.subtitle.contains("MARKDOWN") || ref.subtitle.isNotBlank())

        // 打开校验：成员成功、局外人明确失败
        assertEquals(entryId, ctx.groupFileService.getEntry(member, group.chatId, entryId).entryId)
        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.getEntry(outsider, group.chatId, entryId)
        }

        // 删除条目后：冻结快照保留，打开降级
        ctx.groupFileService.delete(owner, UUID.randomUUID().toString(), group.chatId, entryId, expectedRevision = 1)
        assertEquals("规格说明.md", (stored.body as OfficeRefBody).title)
        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.getEntry(member, group.chatId, entryId)
        }
    }

    @Test
    fun `forwarding copies the frozen snapshot without rebuilding or expanding authority`() = runTest {
        val author = ctx.registerUser(uniqueUsername("fwdref-author"))
        val peer = ctx.registerUser(uniqueUsername("fwdref-peer"))
        val third = ctx.registerUser(uniqueUsername("fwdref-third"))
        val space = ctx.documentService.createSpace(author, "fwd-space", null)
        val document = ctx.documentService.createDocument(author, space.spaceId, null, "转发文档", "# x")
        ctx.documentService.upsertGrant(
            actorUid = author,
            spaceId = space.spaceId,
            principalType = com.virjar.tk.protocol.model.DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = peer,
            role = com.virjar.tk.protocol.model.DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
            issuedAt = System.currentTimeMillis(),
        )
        val chat = ctx.chatService.createPersonalChat(author, peer)
        val target = ctx.chatService.createPersonalChat(peer, third)

        val seq = ctx.messageService.sendMessage(
            author,
            refMessage(
                chat.chatId,
                OfficeRefBody(
                    refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                    spaceId = space.spaceId,
                    targetId = document.documentId,
                    title = "placeholder",
                ),
            ),
        )

        // peer 转发到与 third 的会话：引用与冻结快照原样复制，third 从未获得文档权限
        val forwarded = ctx.messageService.forwardMessage(peer, chat.chatId, seq, target.chatId)
        val ref = forwarded.body as OfficeRefBody
        assertEquals("转发文档", ref.title, "转发保留冻结快照")
        assertEquals(space.spaceId, ref.spaceId)
        assertFailsWith<com.virjar.tk.server.domain.document.DocumentAccessDeniedException> {
            ctx.documentService.getDocument(third, space.spaceId, document.documentId)
        }
    }
}
