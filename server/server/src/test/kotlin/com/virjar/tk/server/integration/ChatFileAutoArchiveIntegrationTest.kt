package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.groupfile.ChatFileAutoArchive
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 群聊文件自动归档：消息附件进入“聊天文件”文件夹，与消息共享物理附件、
 * 引用独立计数（删除互不影响）；图片不归档；归档失败不影响消息发送。
 */
class ChatFileAutoArchiveIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private suspend fun upload(actor: String, name: String, contentType: String, bytes: ByteArray): Attachment {
        val path = ctx.fileStore.store(actor, name, contentType, ByteArrayInputStream(bytes))
        return requireNotNull(ctx.fileStore.getAttachment(path))
    }

    private suspend fun sendFileMessage(
        senderUid: String,
        chatId: String,
        attachment: Attachment,
        image: Boolean = false,
    ): Long = ctx.messageService.sendMessage(
        senderUid,
        Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = if (image) MessageType.IMAGE.code else MessageType.FILE.code,
            timestamp = System.currentTimeMillis(),
            body = if (image) ImageBody(attachment) else FileBody(attachment),
        ),
    )

    private suspend fun chatFilesFolder(chatId: String, actor: String): GroupFileEntry? =
        ctx.groupFileService.list(actor, chatId, null)
            .firstOrNull { it.kind == GroupFileEntry.KIND_FOLDER && it.name == ChatFileAutoArchive.CHAT_FILES_FOLDER_NAME }

    @Test
    fun `file messages are archived into the chat files folder sharing the same attachment`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("archive-owner"))
        val member = ctx.registerUser(uniqueUsername("archive-member"))
        val chat = ctx.chatService.createGroup("归档群", null, owner, listOf(member))

        val attachment = upload(owner, "手册.pdf", "application/pdf", "#pdf".encodeToByteArray())
        val seq = sendFileMessage(owner, chat.chatId, attachment)
        assertTrue(seq > 0)

        val folder = chatFilesFolder(chat.chatId, owner)
        checkNotNull(folder) { "发送文件后应自动创建归档文件夹" }
        val archived = ctx.groupFileService.list(owner, chat.chatId, folder.entryId)
        assertEquals(1, archived.size)
        assertEquals("手册.pdf", archived.single().name)
        assertEquals(owner, archived.single().createdBy, "归档条目归因于消息发送者")
        assertEquals(attachment.path, archived.single().attachment?.path, "与消息共享同一物理附件")

        // 生命周期隔离：删除归档条目后，消息附件仍可读（消息引用仍在）。
        ctx.groupFileService.delete(owner, UUID.randomUUID().toString(), chat.chatId, archived.single().entryId, archived.single().revision)
        assertTrue(ctx.attachmentAccess.canRead(owner, attachment.path), "删除空间条目不影响聊天下载")
    }

    @Test
    fun `image attachments are not archived`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("archive-image"))
        val chat = ctx.chatService.createGroup("图片群", null, owner, listOf(owner))

        val image = upload(owner, "截图.png", "image/png", "png".encodeToByteArray())
        sendFileMessage(owner, chat.chatId, image, image = true)

        assertEquals(null, chatFilesFolder(chat.chatId, owner), "纯图片消息不创建归档文件夹")
    }

    @Test
    fun `same-name files get deduplicated suffixes and both messages still send`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("archive-dup"))
        val chat = ctx.chatService.createGroup("重名群", null, owner, listOf(owner))

        val first = upload(owner, "报告.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "v1".encodeToByteArray())
        val second = upload(owner, "报告.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "v2".encodeToByteArray())
        sendFileMessage(owner, chat.chatId, first)
        sendFileMessage(owner, chat.chatId, second)

        val folder = checkNotNull(chatFilesFolder(chat.chatId, owner))
        val names = ctx.groupFileService.list(owner, chat.chatId, folder.entryId).map { it.name }.sorted()
        assertEquals(listOf("报告 (1).docx", "报告.docx"), names, "重名自动加序号后缀")
    }

    @Test
    fun `personal chat file messages are not archived`() = runTest {
        val alice = ctx.registerUser(uniqueUsername("archive-personal-a"))
        val bob = ctx.registerUser(uniqueUsername("archive-personal-b"))
        val chat = ctx.chatService.createPersonalChat(alice, bob)

        val attachment = upload(alice, "私聊文件.txt", "text/plain", "x".encodeToByteArray())
        sendFileMessage(alice, chat.chatId, attachment)

        assertFailsWith<com.virjar.tk.server.domain.chat.ChatAccessDeniedException> {
            ctx.groupFileService.list(alice, chat.chatId, null)
        }
    }
}
