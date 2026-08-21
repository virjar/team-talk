package com.virjar.tk.domain.attachment

import com.virjar.tk.body.FileBody
import com.virjar.tk.domain.chat.ChatAccessPolicy
import com.virjar.tk.domain.chat.ChatAccessSnapshot
import com.virjar.tk.domain.chat.ChatAccessSource
import com.virjar.tk.domain.message.MessageProjectionTarget
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
            val service = AttachmentAccessService(
                files,
                AttachmentReferences(messages::getAttachmentChatIds),
                ChatAccessPolicy(AccessibleChatsSource()),
            )

            assertTrue(service.canRead("owner", path))
            assertTrue(service.canRead("member", path))
            assertFalse(service.canRead("outsider", path))
            assertFalse(service.canRead("member", "owner/missing.txt"))
        } finally {
            messages.close()
            files.close()
            root.deleteRecursively()
        }
    }
}

private class AccessibleChatsSource : ChatAccessSource {
    override suspend fun load(chatId: String, memberUids: Set<String>) = ChatAccessSnapshot(null)

    override suspend fun loadAllMembers(chatId: String) = ChatAccessSnapshot(null)

    override suspend fun listAccessibleChatIds(uid: String): Set<String> =
        if (uid == "owner" || uid == "member") setOf("chat-private") else emptySet()

    override suspend fun <T> read(
        chatId: String,
        memberUids: Set<String>,
        includeAllMembers: Boolean,
        block: (ChatAccessSnapshot) -> T,
    ): T = block(if (includeAllMembers) loadAllMembers(chatId) else load(chatId, memberUids))

    override suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
        block(listAccessibleChatIds(uid))
}
