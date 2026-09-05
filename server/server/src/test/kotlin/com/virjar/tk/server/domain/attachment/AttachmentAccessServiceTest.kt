package com.virjar.tk.server.domain.attachment

import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ChatAccessSnapshot
import com.virjar.tk.server.domain.chat.ChatAccessSource
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentAccessServiceTest {
    @Test
    fun `owner and referenced chat members can read but outsiders cannot`() = runTest {
        val root = Files.createTempDirectory("tk-attachment-access-").toFile()
        val files = FileStore(
            File(root, "files-db").absolutePath,
            File(root, "files").absolutePath,
        )
        val messages = MessageStore(File(root, "messages").absolutePath)
        try {
            files.init()
            messages.init()
            val source = File(root, "source.txt").apply { writeText("private") }
            val path = files.store("owner", "private.txt", "text/plain", source)
            val attachment = requireNotNull(files.getAttachment(path))
            val message = Message(
                chatId = "chat-private",
                clientMsgId = "message-1",
                serverSeq = 1,
                senderUid = "owner",
                messageType = MessageType.FILE.code,
                timestamp = 1,
                body = FileBody(attachment),
            )
            messages.storeMessage(
                message,
                message,
                MessageProjectionTarget(chatType = 1, recipientUids = listOf("owner", "member")),
            )
            var observedAuthorizedChats: Set<String>? = null
            val service = AttachmentAccessService(
                files,
                object : AttachmentReferences {
                    override fun getChatIds(path: String): Set<String> =
                        error("download authorization must not materialize every referencing chat")

                    override fun isReferencedByAny(path: String, chatIds: Set<String>): Boolean {
                        observedAuthorizedChats = chatIds
                        return messages.isAttachmentReferencedByAny(path, chatIds)
                    }

                    override fun getReferencedPaths(paths: Set<String>): Set<String> =
                        messages.getReferencedAttachmentPaths(paths)
                },
                ChatAccess(AccessibleChatsSource()),
            )

            assertTrue(service.canRead("owner", path))
            assertTrue(service.canRead("member", path))
            assertEquals(setOf("chat-private"), observedAuthorizedChats)
            assertFalse(service.canRead("outsider", path))
            assertFalse(service.canRead("member", "owner/missing.txt"))
        } finally {
            messages.close()
            files.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `any authenticated user can read only the current avatar reference`() = runTest {
        val root = Files.createTempDirectory("tk-avatar-access-").toFile()
        val files = FileStore(
            File(root, "files-db").absolutePath,
            File(root, "files").absolutePath,
        )
        try {
            files.init()
            val source = File(root, "avatar.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val path = files.store("owner", "avatar.png", "image/png", source)
            files.markBusinessBound(listOf(path))
            var currentAvatarPaths = setOf(path)
            val service = AttachmentAccessService(
                attachments = files,
                references = object : AttachmentReferences {
                    override fun getChatIds(path: String): Set<String> = emptySet()
                    override fun getReferencedPaths(paths: Set<String>): Set<String> =
                        paths.intersect(currentAvatarPaths)
                },
                chats = ChatAccess(AccessibleChatsSource()),
                userAvatars = UserAvatarReferences { paths -> paths.intersect(currentAvatarPaths) },
            )

            assertTrue(service.canRead("authenticated-reader", path))
            currentAvatarPaths = emptySet()
            assertFalse(service.canRead("authenticated-reader", path))
            assertFalse(service.canRead("owner", path), "bound old avatar must not fall back to uploader ownership")
        } finally {
            files.close()
            root.deleteRecursively()
        }
    }
}

private class AccessibleChatsSource : ChatAccessSource {
    override suspend fun load(chatId: String, memberUids: Set<String>) = ChatAccessSnapshot(null)

    override suspend fun listAccessibleChatIds(uid: String): Set<String> =
        if (uid == "owner" || uid == "member") setOf("chat-private") else emptySet()

    override suspend fun <T> read(
        chatId: String,
        memberUids: Set<String>,
        includeAllMembers: Boolean,
        block: (ChatAccessSnapshot) -> T,
    ): T = block(load(chatId, memberUids))

    override suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
        block(listAccessibleChatIds(uid))
}
