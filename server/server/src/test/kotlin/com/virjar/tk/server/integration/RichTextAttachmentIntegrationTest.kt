package com.virjar.tk.server.integration

import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RichTextAttachmentIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `rich edit replaces references and forward creates target references for main and thumbnail`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("rich-asset-sender"))
        val sourcePeer = ctx.registerUser(uniqueUsername("rich-asset-source-peer"))
        val targetPeer = ctx.registerUser(uniqueUsername("rich-asset-target-peer"))
        val sourceChat = ctx.chatService.createPersonalChat(sender, sourcePeer)
        val targetChat = ctx.chatService.createPersonalChat(sender, targetPeer)

        val first = embeddedAsset(sender, "first.png", "first-body", withThumbnail = true)
        val initial = message(
            sender,
            sourceChat.chatId,
            "![第一张](${EmbeddedAsset.uri(first.assetId)})",
            listOf(first),
        )
        val sourceSeq = ctx.messageService.sendMessage(sender, initial)
        val firstPaths = first.attachments().mapTo(linkedSetOf()) { it.path }
        assertEquals(firstPaths, ctx.messageStore.getReferencedAttachmentPaths(firstPaths))
        firstPaths.forEach { path ->
            assertNotNull(ctx.fileStore.getMeta(path)?.businessBoundAt)
            assertTrue(ctx.attachmentAccess.canRead(sourcePeer, path))
        }

        val second = embeddedAsset(sender, "second.png", "second-body", withThumbnail = true)
        val editedDeclaration = initial.copy(
            body = buildRichTextBody(
                "![第二张](${EmbeddedAsset.uri(second.assetId)})",
                listOf(second),
            ),
        )
        ctx.messageService.editMessage(sender, sourceChat.chatId, sourceSeq, editedDeclaration)
        val secondPaths = second.attachments().mapTo(linkedSetOf()) { it.path }
        assertEquals(emptySet(), ctx.messageStore.getReferencedAttachmentPaths(firstPaths))
        assertEquals(secondPaths, ctx.messageStore.getReferencedAttachmentPaths(secondPaths))
        firstPaths.forEach { path ->
            assertFalse(
                ctx.attachmentAccess.canRead(sender, path),
                "an edited-out, once-bound upload must not regain uploader staging access",
            )
        }

        val forwarded = ctx.messageService.forwardMessage(sender, sourceChat.chatId, sourceSeq, targetChat.chatId)
        assertEquals(listOf(second), (forwarded.body as RichTextBody).assets)
        secondPaths.forEach { path ->
            assertEquals(
                setOf(sourceChat.chatId, targetChat.chatId),
                ctx.messageStore.getAttachmentChatIds(path),
            )
            assertTrue(ctx.attachmentAccess.canRead(sourcePeer, path))
            assertTrue(ctx.attachmentAccess.canRead(targetPeer, path))
        }
    }

    @Test
    fun `successful asset resolution publishes fail closed before later message admission fails`() = runTest {
        val memberA = ctx.registerUser(uniqueUsername("rich-bound-member-a"))
        val memberB = ctx.registerUser(uniqueUsername("rich-bound-member-b"))
        val outsider = ctx.registerUser(uniqueUsername("rich-bound-outsider"))
        val chat = ctx.chatService.createPersonalChat(memberA, memberB)
        val asset = embeddedAsset(outsider, "rejected.png", "rejected-body", withThumbnail = false)
        val declaration = message(
            outsider,
            chat.chatId,
            "![拒绝](${EmbeddedAsset.uri(asset.assetId)})",
            listOf(asset),
        )

        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(outsider, declaration)
        }

        val path = asset.attachment.path
        assertNotNull(ctx.fileStore.getMeta(path)?.businessBoundAt)
        assertEquals(emptySet(), ctx.messageStore.getReferencedAttachmentPaths(setOf(path)))
        assertFalse(
            ctx.attachmentAccess.canRead(outsider, path),
            "a post-resolution admission failure must not restore uploader staging authority",
        )
    }

    @Test
    fun `reply assets require stored objects and leave no references after revoke`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("reply-asset-sender"))
        val peer = ctx.registerUser(uniqueUsername("reply-asset-peer"))
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val targetSeq = ctx.messageService.sendMessage(
            peer,
            message(peer, chat.chatId, "需要回复的原文", emptyList()),
        )
        val asset = embeddedAsset(sender, "reply.png", "reply-body", withThumbnail = true)
        val markdown = "![现场图](${EmbeddedAsset.uri(asset.assetId)})"

        val missingAsset = asset.copy(
            assetId = UUID.randomUUID().toString(),
            attachment = asset.attachment.copy(path = "$sender/missing-reply.png"),
            thumbnail = null,
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(
                sender,
                replyMessage(
                    sender = sender,
                    chatId = chat.chatId,
                    targetSeq = targetSeq,
                    markdown = "![缺失](${EmbeddedAsset.uri(missingAsset.assetId)})",
                    assets = listOf(missingAsset),
                ),
            )
        }

        val replySeq = ctx.messageService.sendMessage(
            sender,
            replyMessage(sender, chat.chatId, targetSeq, markdown, listOf(asset)),
        )
        val stored = ctx.messageService.getHistory(peer, chat.chatId, 0, 10)
            .first { it.serverSeq == replySeq }
            .body as ReplyBody
        assertEquals(listOf(asset), stored.assets)
        val paths = asset.attachments().mapTo(linkedSetOf()) { it.path }
        assertEquals(paths, ctx.messageStore.getReferencedAttachmentPaths(paths))
        paths.forEach { path ->
            assertNotNull(ctx.fileStore.getMeta(path)?.businessBoundAt)
            assertTrue(ctx.attachmentAccess.canRead(peer, path))
        }
        assertEquals(
            replySeq,
            ctx.messageService.searchMessages(peer, chat.chatId, "现场图", 10).single().serverSeq,
        )

        ctx.messageService.revokeMessage(sender, chat.chatId, replySeq)

        assertEquals(emptySet(), ctx.messageStore.getReferencedAttachmentPaths(paths))
        paths.forEach { path ->
            assertFalse(ctx.attachmentAccess.canRead(sender, path))
            assertFalse(ctx.attachmentAccess.canRead(peer, path))
        }
    }

    private fun message(
        sender: String,
        chatId: String,
        markdown: String,
        assets: List<EmbeddedAsset>,
    ) = Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = sender,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1,
        body = buildRichTextBody(markdown, assets),
    )

    private fun replyMessage(
        sender: String,
        chatId: String,
        targetSeq: Long,
        markdown: String,
        assets: List<EmbeddedAsset>,
    ) = Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = sender,
        messageType = MessageType.REPLY.code,
        timestamp = 1,
        body = ReplyBody(
            replyToMsgId = targetSeq.toString(),
            replyToSenderUid = "client-side-value-is-rebuilt",
            content = markdown,
            assets = assets,
        ),
    )

    private fun embeddedAsset(
        uid: String,
        name: String,
        body: String,
        withThumbnail: Boolean,
    ): EmbeddedAsset {
        val mainPath = store(uid, name, "image/png", body)
        val thumbnailPath = if (withThumbnail) store(uid, "thumb-$name", "image/jpeg", "thumb-$body") else null
        return EmbeddedAsset(
            assetId = UUID.randomUUID().toString(),
            attachment = requireNotNull(ctx.fileStore.getAttachment(mainPath)),
            thumbnail = thumbnailPath?.let { requireNotNull(ctx.fileStore.getAttachment(it)) },
            width = 800,
            height = 600,
        )
    }

    private fun store(uid: String, name: String, contentType: String, body: String): String {
        val source = File.createTempFile("rich-asset-", ".tmp").apply { writeText(body) }
        return try {
            ctx.fileStore.store(uid, name, contentType, source)
        } finally {
            source.delete()
        }
    }
}
