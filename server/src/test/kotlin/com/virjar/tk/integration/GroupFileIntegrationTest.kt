package com.virjar.tk.integration

import com.virjar.tk.domain.groupfile.GroupFileService
import com.virjar.tk.model.GroupFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupFileIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `group file versions keep membership ACL and optimistic concurrency`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("files-owner"))
        val member = ctx.registerUser(uniqueUsername("files-member"))
        val outsider = ctx.registerUser(uniqueUsername("files-outsider"))
        val chat = ctx.chatService.createGroup("项目资料", null, owner, listOf(member))

        val folder = ctx.groupFileService.createFolder(owner, chat.chatId, null, "设计资料")
        assertEquals(GroupFileEntry.KIND_FOLDER, folder.kind)

        val ownerPath = ctx.fileStore.store(
            owner,
            "spec.md",
            "text/markdown",
            ByteArrayInputStream("# v1".encodeToByteArray()),
        )
        val ownerAttachment = requireNotNull(ctx.fileStore.getAttachment(ownerPath))

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.createFile(member, chat.chatId, folder.entryId, "spec.md", ownerAttachment)
        }

        val file = ctx.groupFileService.createFile(owner, chat.chatId, folder.entryId, "spec.md", ownerAttachment)
        assertEquals(1, file.contentVersion)
        assertTrue(ctx.attachmentAccess.canRead(member, ownerPath))
        assertFalse(ctx.attachmentAccess.canRead(outsider, ownerPath))
        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.list(outsider, chat.chatId, null)
        }

        val memberPath = ctx.fileStore.store(
            member,
            "spec-v2.md",
            "text/markdown",
            ByteArrayInputStream("# v2".encodeToByteArray()),
        )
        val memberAttachment = requireNotNull(ctx.fileStore.getAttachment(memberPath))
        val v2 = ctx.groupFileService.addVersion(member, chat.chatId, file.entryId, memberAttachment, file.revision)
        assertEquals(2, v2.contentVersion)
        assertEquals(listOf(2L, 1L), ctx.groupFileService.listVersions(owner, chat.chatId, file.entryId).map { it.version })

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.rename(owner, chat.chatId, file.entryId, "stale.md", file.revision)
        }
        val renamed = ctx.groupFileService.rename(owner, chat.chatId, file.entryId, "product-spec.md", v2.revision)
        assertEquals("product-spec.md", renamed.name)

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.delete(owner, chat.chatId, folder.entryId, folder.revision)
        }
        ctx.groupFileService.delete(owner, chat.chatId, renamed.entryId, renamed.revision)
        assertFalse(ctx.attachmentAccess.canRead(member, ownerPath))
        ctx.groupFileService.delete(owner, chat.chatId, folder.entryId, folder.revision)
        assertTrue(ctx.groupFileService.list(owner, chat.chatId, null).isEmpty())
    }

    @Test
    fun `concurrent publishes cannot overrun group quota`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("quota-owner"))
        val chat = ctx.chatService.createGroup("配额测试", null, owner, emptyList())
        val service = GroupFileService(
            repository = ctx.groupFileRepo,
            access = ctx.chatAccess,
            attachments = ctx.fileStore,
            quotaBytes = 5,
        )
        val attachments = (1..2).map { index ->
            val path = ctx.fileStore.store(
                owner,
                "quota-$index.bin",
                "application/octet-stream",
                ByteArrayInputStream(ByteArray(4) { index.toByte() }),
            )
            requireNotNull(ctx.fileStore.getAttachment(path))
        }

        val results = coroutineScope {
            attachments.mapIndexed { index, attachment ->
                async(Dispatchers.Default) {
                    runCatching { service.createFile(owner, chat.chatId, null, "quota-$index.bin", attachment) }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(4L, ctx.groupFileRepo.totalVersionBytes(chat.chatId))
    }

    @Test
    fun `repository keeps directory tree valid inside mutation transaction`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("tree-owner"))
        val chat = ctx.chatService.createGroup("目录约束", null, owner, emptyList())
        val folder = ctx.groupFileService.createFolder(owner, chat.chatId, null, "父目录")
        val child = ctx.groupFileService.createFolder(owner, chat.chatId, folder.entryId, "子目录")

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileRepo.delete(folder.entryId, folder.revision, owner)
        }

        ctx.groupFileService.delete(owner, chat.chatId, child.entryId, child.revision)
        ctx.groupFileService.delete(owner, chat.chatId, folder.entryId, folder.revision)
        val now = System.currentTimeMillis()
        val orphan = GroupFileEntry(
            entryId = UUID.randomUUID().toString(),
            chatId = chat.chatId,
            parentId = folder.entryId,
            kind = GroupFileEntry.KIND_FOLDER,
            name = "孤儿目录",
            createdBy = owner,
            createdAt = now,
            updatedBy = owner,
            updatedAt = now,
        )

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileRepo.create(orphan, null, GroupFileService.DEFAULT_QUOTA_BYTES)
        }
    }
}
