package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.VideoBody
import com.virjar.tk.protocol.body.VoiceBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.MessageAttachmentSearchPolicy
import com.virjar.tk.server.domain.message.MessageAttachmentSearchPage
import com.virjar.tk.server.domain.message.MessageAttachmentSearchPosition
import com.virjar.tk.server.domain.message.MessageSearch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageAttachmentSearchIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `attachment categories filter before result limit and preserve actual descriptors`() = runTest {
        val owner = ctx.registerUser()
        val chat = ctx.chatService.createGroup("Attachment categories", null, owner, listOf(owner))
        val image = attachment(owner, "Report-现场.PNG", "image/png")
        val imageSeq = send(owner, chat.chatId, MessageType.IMAGE, ImageBody(image))
        val video = attachment(owner, "Report-video.mp4", "video/mp4")
        send(owner, chat.chatId, MessageType.VIDEO, VideoBody(video))
        val voice = attachment(owner, "Report-recording.ogg", "audio/ogg")
        send(owner, chat.chatId, MessageType.VOICE, VoiceBody(voice))
        repeat(12) { send(owner, chat.chatId, MessageType.FILE, FileBody(attachment(owner, "Report-$it.pdf", "application/pdf"))) }

        val first = ctx.messageService.searchAttachments(owner, request(chat.chatId, "report", ContentSearchRequest.FILE_TYPE_IMAGE, 1))
        val hit = first.items.single()
        assertEquals(imageSeq, hit.serverSeq)
        assertEquals(image.name, hit.title)
        assertEquals(image.contentType, hit.mimeType)
        assertEquals(image.size, hit.size)
        assertEquals(MessageAttachmentSearchPolicy.targetId(image), hit.targetId)
        assertEquals(chat.chatId, hit.scopeId)
        assertNull(first.nextCursor)
        assertEquals(video.name, ctx.messageService.searchAttachments(owner, request(chat.chatId, "report", ContentSearchRequest.FILE_TYPE_VIDEO)).items.single().title)
        assertEquals(voice.name, ctx.messageService.searchAttachments(owner, request(chat.chatId, "report", ContentSearchRequest.FILE_TYPE_AUDIO)).items.single().title)
        assertEquals(12, ctx.messageService.searchAttachments(owner, request(chat.chatId, "report", ContentSearchRequest.FILE_TYPE_OTHER)).items.size)
        assertEquals(image.name, ctx.messageService.searchAttachments(owner, request(chat.chatId, "现场")).items.single().title)
    }

    @Test
    fun `rich and reply main attachments are distinct per message and never expose thumbnails`() = runTest {
        val owner = ctx.registerUser()
        val chat = ctx.chatService.createGroup("Attachment sources", null, owner, listOf(owner))
        val image = attachment(owner, "shared-original.png", "image/png")
        val thumbnail = attachment(owner, "thumbnail-only.jpg", "image/jpeg")
        val first = EmbeddedAsset(UUID.randomUUID().toString(), image, thumbnail)
        val duplicatePath = first.copy(assetId = UUID.randomUUID().toString())
        val rich = richMessage(owner, chat.chatId, listOf(first, duplicatePath))
        val richSeq = ctx.messageService.sendMessage(owner, rich)
        val reply = ReplyBody(
            replyToMsgId = richSeq.toString(),
            replyToSenderUid = owner,
            content = "![reply](${EmbeddedAsset.uri(first.assetId)})",
            assets = listOf(first),
        )
        val replySeq = send(owner, chat.chatId, MessageType.REPLY, reply)
        val hits = ctx.messageService.searchAttachments(owner, request(chat.chatId, "original")).items
        assertEquals(setOf(richSeq, replySeq), hits.map { it.serverSeq }.toSet())
        assertEquals(2, hits.size)
        assertEquals(setOf(MessageAttachmentSearchPolicy.targetId(image)), hits.map { it.targetId }.toSet())
        assertTrue(ctx.messageService.searchAttachments(owner, request(chat.chatId, "thumbnail-only")).items.isEmpty())
    }

    @Test
    fun `keyset resumes inside one message and ignores newer inserted messages`() = runTest {
        val owner = ctx.registerUser()
        val chat = ctx.chatService.createGroup("Attachment pagination", null, owner, listOf(owner))
        val assets = (1..4).map { EmbeddedAsset(UUID.randomUUID().toString(), attachment(owner, "page-$it.png", "image/png")) }
        val originalSeq = ctx.messageService.sendMessage(owner, richMessage(owner, chat.chatId, assets))
        var query = request(chat.chatId, "page-", limit = 1)
        var page = ctx.messageService.searchAttachments(owner, query)
        val found = page.items.toMutableList()
        assertNotNull(page.nextCursor)
        send(owner, chat.chatId, MessageType.FILE, FileBody(attachment(owner, "page-new.pdf", "application/pdf")))
        repeat(5) {
            val next = page.nextCursor ?: return@repeat
            query = query.copy(cursor = next)
            page = ctx.messageService.searchAttachments(owner, query)
            found += page.items
        }
        assertNull(page.nextCursor)
        assertEquals(4, found.size)
        assertEquals(setOf(originalSeq), found.map { it.serverSeq }.toSet())
        assertEquals(assets.map { MessageAttachmentSearchPolicy.targetId(it.attachment) }.sorted(), found.map { it.targetId })
        val cursor = ctx.messageService.searchAttachments(owner, request(chat.chatId, "page-", limit = 1)).nextCursor!!
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.searchAttachments(owner, request(chat.chatId, "different").copy(cursor = cursor))
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.searchAttachments(owner, request(chat.chatId, "page-", ContentSearchRequest.FILE_TYPE_IMAGE).copy(cursor = cursor))
        }
    }

    @Test
    fun `global and scoped attachment search obey current membership and old hit access is denied`() = runTest {
        val owner = ctx.registerUser()
        val reader = ctx.registerUser()
        val outsider = ctx.registerUser()
        val chat = ctx.chatService.createGroup("Attachment permissions", null, owner, listOf(owner, reader))
        val secret = ctx.chatService.createGroup("Private attachment", null, outsider, listOf(outsider))
        val file = attachment(owner, "permission-report.pdf", "application/pdf")
        send(owner, chat.chatId, MessageType.FILE, FileBody(file))
        send(outsider, secret.chatId, MessageType.FILE, FileBody(attachment(outsider, "permission-secret.pdf", "application/pdf")))
        val oldHit = ctx.messageService.searchAttachments(reader, request("", "permission-")).items.single()
        assertEquals(chat.chatId, oldHit.scopeId)
        assertTrue(ctx.attachmentAccess.canRead(reader, file.path))
        ctx.chatService.removeMember(owner, chat.chatId, reader)
        assertTrue(ctx.messageService.searchAttachments(reader, request("", "permission-")).items.isEmpty())
        assertFailsWith<IllegalArgumentException> { ctx.messageService.searchAttachments(reader, request(chat.chatId, "permission-")) }
        assertFailsWith<IllegalArgumentException> { ctx.messageService.getHistory(reader, oldHit.scopeId, oldHit.serverSeq + 1L, 10) }
        assertFalse(ctx.attachmentAccess.canRead(reader, file.path))
    }

    @Test
    fun `edited and revoked attachments cannot be resurrected by stale index candidates`() = runTest {
        val owner = ctx.registerUser()
        val chat = ctx.chatService.createGroup("Attachment revisions", null, owner, listOf(owner))
        val old = EmbeddedAsset(UUID.randomUUID().toString(), attachment(owner, "old-asset.png", "image/png"))
        val original = richMessage(owner, chat.chatId, listOf(old))
        val seq = ctx.messageService.sendMessage(owner, original)
        val stale = ctx.searchIndex.searchAttachments("old-asset", setOf(chat.chatId), 0, 10)
        val staleService = ctx.freshMessageService(search = fixedCandidates(stale))
        val replacement = EmbeddedAsset(UUID.randomUUID().toString(), attachment(owner, "new-asset.png", "image/png"))
        ctx.messageService.editMessage(owner, chat.chatId, seq, richMessage(owner, chat.chatId, listOf(replacement)))
        assertTrue(ctx.messageService.searchAttachments(owner, request(chat.chatId, "old-asset")).items.isEmpty())
        assertTrue(staleService.searchAttachments(owner, request(chat.chatId, "old-asset")).items.isEmpty())
        assertFalse(ctx.attachmentAccess.canRead(owner, old.attachment.path))
        val current = ctx.messageService.searchAttachments(owner, request(chat.chatId, "new-asset")).items.single()
        assertTrue(current.revision > stale.hits.single().revision)
        val currentCandidates = ctx.searchIndex.searchAttachments("new-asset", setOf(chat.chatId), 0, 10)
        ctx.messageService.revokeMessage(owner, chat.chatId, seq)
        assertTrue(ctx.messageService.searchAttachments(owner, request(chat.chatId, "new-asset")).items.isEmpty())
        assertTrue(ctx.freshMessageService(search = fixedCandidates(currentCandidates)).searchAttachments(owner, request(chat.chatId, "new-asset")).items.isEmpty())
        assertFalse(ctx.attachmentAccess.canRead(owner, replacement.attachment.path))
    }

    @Test
    fun `fresh checks reject poisoned scope name type and target manifest before returning hits`() = runTest {
        val owner = ctx.registerUser()
        val outsider = ctx.registerUser()
        val allowed = ctx.chatService.createGroup("Allowed attachments", null, owner, listOf(owner))
        val secret = ctx.chatService.createGroup("Secret attachments", null, outsider, listOf(outsider))
        send(owner, allowed.chatId, MessageType.FILE, FileBody(attachment(owner, "actual-report.pdf", "application/pdf")))
        send(outsider, secret.chatId, MessageType.FILE, FileBody(attachment(outsider, "secret-report.pdf", "application/pdf")))
        val secretCandidates = ctx.searchIndex.searchAttachments("secret", setOf(secret.chatId), 0, 10)
        assertTrue(ctx.freshMessageService(search = fixedCandidates(secretCandidates)).searchAttachments(owner, request(allowed.chatId, "secret")).items.isEmpty())
        val actual = ctx.searchIndex.searchAttachments("actual", setOf(allowed.chatId), 0, 10)
        val service = ctx.freshMessageService(search = fixedCandidates(actual))
        assertTrue(service.searchAttachments(owner, request(allowed.chatId, "absent")).items.isEmpty())
        assertTrue(service.searchAttachments(owner, request(allowed.chatId, "actual", ContentSearchRequest.FILE_TYPE_IMAGE)).items.isEmpty())
        val changedManifest = actual.copy(hits = actual.hits.map { it.copy(attachmentManifest = "bad") })
        assertTrue(ctx.freshMessageService(search = fixedCandidates(changedManifest)).searchAttachments(owner, request(allowed.chatId, "actual")).items.isEmpty())
    }

    private fun fixedCandidates(page: MessageAttachmentSearchPage): MessageSearch = object : MessageSearch by ctx.searchIndex {
        override fun searchAttachments(
            query: String,
            chatIds: Set<String>,
            fileType: Int,
            limit: Int,
            after: MessageAttachmentSearchPosition?,
            includeAfter: Boolean,
        ) = page.copy(hasMore = false)
    }

    private fun request(scope: String, keyword: String, fileType: Int = 0, limit: Int = 20) =
        ContentSearchRequest(ContentSearchRequest.KIND_CHAT_ATTACHMENT, keyword, scope, fileType, limit)

    private suspend fun send(owner: String, chatId: String, type: MessageType, body: MessageBody): Long =
        ctx.messageService.sendMessage(owner, message(owner, chatId, type, body))

    private fun message(owner: String, chatId: String, type: MessageType, body: MessageBody) = Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = owner,
        messageType = type.code,
        timestamp = 1L,
        body = body,
    )

    private fun richMessage(owner: String, chatId: String, assets: List<EmbeddedAsset>): Message =
        message(owner, chatId, MessageType.RICH_TEXT, buildRichTextBody(
            assets.joinToString("\n") { "![asset](${EmbeddedAsset.uri(it.assetId)})" }, assets,
        ))

    private fun attachment(owner: String, name: String, mime: String): Attachment {
        val source = File.createTempFile("attachment-search-", ".tmp").apply { writeText(UUID.randomUUID().toString()) }
        return try {
            val path = ctx.fileStore.store(owner, name, mime, source)
            checkNotNull(ctx.fileStore.getAttachment(path))
        } finally {
            source.delete()
        }
    }
}
