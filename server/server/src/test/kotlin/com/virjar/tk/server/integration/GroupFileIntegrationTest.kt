package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.groupfile.GroupFileAppendVersionCommand
import com.virjar.tk.server.domain.groupfile.GroupFileCapacityPolicy
import com.virjar.tk.server.domain.groupfile.GroupFileCreateCommand
import com.virjar.tk.server.domain.groupfile.GroupFileDeleteCommand
import com.virjar.tk.server.domain.groupfile.GroupFileRenameCommand
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.infra.db.GroupFileAudits
import com.virjar.tk.server.infra.db.GroupFileChatUsages
import com.virjar.tk.server.infra.db.GroupFileCommands
import com.virjar.tk.server.infra.db.GroupFileEntries
import com.virjar.tk.server.infra.db.repository.ExposedGroupFileRepository
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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

        val folder = createFolder(ctx.groupFileService, owner, chat.chatId, null, "设计资料")
        assertEquals(GroupFileEntry.KIND_FOLDER, folder.kind)

        val ownerPath = ctx.fileStore.store(
            owner,
            "spec.md",
            "text/markdown",
            ByteArrayInputStream("# v1".encodeToByteArray()),
        )
        val ownerAttachment = requireNotNull(ctx.fileStore.getAttachment(ownerPath))

        assertFailsWith<IllegalArgumentException> {
            createFile(ctx.groupFileService, member, chat.chatId, folder.entryId, "spec.md", ownerAttachment)
        }

        val file = createFile(ctx.groupFileService, owner, chat.chatId, folder.entryId, "spec.md", ownerAttachment)
        assertEquals(1, file.contentVersion)
        assertTrue(
            ctx.groupFileRepo.isAttachmentReferencedByAny(
                ownerPath,
                setOf("unrelated-chat", chat.chatId),
            ),
        )
        assertFalse(ctx.groupFileRepo.isAttachmentReferencedByAny(ownerPath, setOf("unrelated-chat")))
        assertFalse(ctx.groupFileRepo.isAttachmentReferencedByAny(ownerPath, emptySet()))
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
        val v2 = addVersion(ctx.groupFileService, member, chat.chatId, file.entryId, memberAttachment, file.revision)
        assertEquals(2, v2.contentVersion)
        assertEquals(listOf(2L, 1L), ctx.groupFileService.listVersions(owner, chat.chatId, file.entryId).map { it.version })
        assertEquals(
            setOf(ownerPath, memberPath),
            ctx.groupFileRepo.getReferencedAttachmentPaths(
                setOf(ownerPath, memberPath, "$owner/missing.bin"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.rename(owner, newId(), chat.chatId, file.entryId, "stale.md", file.revision)
        }
        ctx.groupFileService.rename(
            owner,
            newId(),
            chat.chatId,
            file.entryId,
            "product-spec.md",
            v2.revision,
        )
        val renamed = requireNotNull(ctx.groupFileRepo.find(file.entryId))
        assertEquals("product-spec.md", renamed.name)

        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileService.delete(owner, newId(), chat.chatId, folder.entryId, folder.revision)
        }
        ctx.groupFileService.delete(owner, newId(), chat.chatId, renamed.entryId, renamed.revision)
        assertFalse(ctx.attachmentAccess.canRead(member, ownerPath))
        assertTrue(
            ctx.groupFileRepo.getReferencedAttachmentPaths(setOf(ownerPath, memberPath)).isEmpty(),
        )
        ctx.groupFileService.delete(owner, newId(), chat.chatId, folder.entryId, folder.revision)
        assertTrue(ctx.groupFileService.list(owner, chat.chatId, null).isEmpty())
    }

    @Test
    fun `rename and delete receipts survive restart without duplicate mutation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("mutation-retry-owner"))
        val chat = ctx.chatService.createGroup("可靠变更", null, owner, emptyList())
        val original = createFolder(ctx.groupFileService, owner, chat.chatId, null, "原名称")
        val renameCommandId = newId()

        ctx.groupFileService.rename(
            owner,
            renameCommandId,
            chat.chatId,
            original.entryId,
            "第一次重命名",
            original.revision,
        )
        val renamed = requireNotNull(ctx.groupFileRepo.find(original.entryId))
        assertEquals(original.revision + 1, renamed.revision)
        assertEquals(1L, commandCount(renameCommandId))
        assertEquals(4, commandKind(renameCommandId))
        assertEquals(1L, auditCount(original.entryId, "RENAME"))
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 0L), usage(chat.chatId))

        val restartedRepository = ExposedGroupFileRepository(ctx.database)
        val restartedService = GroupFileService(restartedRepository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
        restartedService.rename(
            owner,
            renameCommandId,
            chat.chatId,
            original.entryId,
            "第一次重命名",
            original.revision,
        )
        assertEquals(renamed, restartedRepository.find(original.entryId))
        assertFailsWith<ReliableCommandConflictException> {
            restartedService.rename(
                owner,
                renameCommandId,
                chat.chatId,
                original.entryId,
                "冲突名称",
                original.revision,
            )
        }
        assertEquals(renamed, restartedRepository.find(original.entryId))
        assertEquals(1L, commandCount(renameCommandId))
        assertEquals(1L, auditCount(original.entryId, "RENAME"))

        restartedService.rename(
            owner,
            newId(),
            chat.chatId,
            original.entryId,
            "当前名称",
            renamed.revision,
        )
        val latest = requireNotNull(restartedRepository.find(original.entryId))
        restartedService.rename(
            owner,
            renameCommandId,
            chat.chatId,
            original.entryId,
            "第一次重命名",
            original.revision,
        )
        assertEquals(latest, restartedRepository.find(original.entryId))
        assertEquals(2L, auditCount(original.entryId, "RENAME"))

        val deleteCommandId = newId()
        restartedService.delete(owner, deleteCommandId, chat.chatId, latest.entryId, latest.revision)
        assertEquals(null, restartedRepository.find(latest.entryId))
        val deletedRevision = entryRevisionIncludingDeleted(latest.entryId)
        assertEquals(latest.revision + 1, deletedRevision)
        assertEquals(1L, commandCount(deleteCommandId))
        assertEquals(5, commandKind(deleteCommandId))
        assertEquals(1L, auditCount(original.entryId, "DELETE"))
        assertEquals(GroupFileUsageSnapshot(activeEntries = 0L, activeVersionBytes = 0L), usage(chat.chatId))

        val afterDeleteRestart = GroupFileService(
            ExposedGroupFileRepository(ctx.database),
            ctx.chatAccess,
            ctx.fileStore,
            ctx.pgUnitOfWork,
            ctx.chatStore,
        )
        afterDeleteRestart.delete(owner, deleteCommandId, chat.chatId, latest.entryId, latest.revision)
        assertFailsWith<ReliableCommandConflictException> {
            afterDeleteRestart.delete(owner, deleteCommandId, chat.chatId, latest.entryId, latest.revision + 1)
        }
        assertEquals(1L, commandCount(deleteCommandId))
        assertEquals(1L, auditCount(original.entryId, "DELETE"))
        assertEquals(deletedRevision, entryRevisionIncludingDeleted(latest.entryId))
        assertEquals(GroupFileUsageSnapshot(activeEntries = 0L, activeVersionBytes = 0L), usage(chat.chatId))
    }

    @Test
    fun `exact mutation receipt remains acknowledgeable after membership removal`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("mutation-retry-membership-owner"))
        val member = ctx.registerUser(uniqueUsername("mutation-retry-member"))
        val chat = ctx.chatService.createGroup("可靠回执", null, owner, listOf(member))
        val folder = createFolder(ctx.groupFileService, member, chat.chatId, null, "成员资料")
        val commandId = newId()

        ctx.groupFileService.rename(
            member,
            commandId,
            chat.chatId,
            folder.entryId,
            "已提交名称",
            folder.revision,
        )
        ctx.chatService.removeMember(owner, chat.chatId, member)

        val restartedService = GroupFileService(
            ExposedGroupFileRepository(ctx.database),
            ctx.chatAccess,
            ctx.fileStore,
            ctx.pgUnitOfWork,
            ctx.chatStore,
        )
        restartedService.rename(
            member,
            commandId,
            chat.chatId,
            folder.entryId,
            "已提交名称",
            folder.revision,
        )
        assertFailsWith<IllegalArgumentException> {
            restartedService.delete(
                member,
                newId(),
                chat.chatId,
                folder.entryId,
                folder.revision + 1,
            )
        }
    }

    @Test
    fun `concurrent exact mutation deliveries share one receipt and one state change`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("mutation-concurrent-owner"))
        val chat = ctx.chatService.createGroup("并发可靠变更", null, owner, emptyList())
        val folder = createFolder(ctx.groupFileService, owner, chat.chatId, null, "并发目录")
        val renameCommandId = newId()
        val renameGate = CompletableDeferred<Unit>()

        val renameResults = coroutineScope {
            List(12) {
                async(Dispatchers.IO) {
                    renameGate.await()
                    runCatching {
                        ctx.groupFileService.rename(
                            owner,
                            renameCommandId,
                            chat.chatId,
                            folder.entryId,
                            "并发名称",
                            folder.revision,
                        )
                    }
                }
            }.also { renameGate.complete(Unit) }.awaitAll()
        }
        assertTrue(renameResults.all { it.isSuccess }, renameResults.mapNotNull { it.exceptionOrNull() }.toString())
        val renamed = requireNotNull(ctx.groupFileRepo.find(folder.entryId))
        assertEquals(folder.revision + 1, renamed.revision)
        assertEquals(1L, commandCount(renameCommandId))
        assertEquals(1L, auditCount(folder.entryId, "RENAME"))

        val deleteCommandId = newId()
        val deleteGate = CompletableDeferred<Unit>()
        val deleteResults = coroutineScope {
            List(12) {
                async(Dispatchers.IO) {
                    deleteGate.await()
                    runCatching {
                        ctx.groupFileService.delete(
                            owner,
                            deleteCommandId,
                            chat.chatId,
                            renamed.entryId,
                            renamed.revision,
                        )
                    }
                }
            }.also { deleteGate.complete(Unit) }.awaitAll()
        }
        assertTrue(deleteResults.all { it.isSuccess }, deleteResults.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(null, ctx.groupFileRepo.find(folder.entryId))
        assertEquals(1L, commandCount(deleteCommandId))
        assertEquals(1L, auditCount(folder.entryId, "DELETE"))
    }

    @Test
    fun `concurrent publishes cannot overrun group quota`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("quota-owner"))
        val chat = ctx.chatService.createGroup("配额测试", null, owner, emptyList())
        val repository = ExposedGroupFileRepository(
            database = ctx.database,
            capacityPolicy = GroupFileCapacityPolicy(maxTotalVersionBytesPerChat = 5),
        )
        val service = GroupFileService(repository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
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
                    runCatching { createFile(service, owner, chat.chatId, null, "quota-$index.bin", attachment) }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(4L, repository.totalVersionBytes(chat.chatId))
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 4L), usage(chat.chatId))

        val accepted = results.first { it.isSuccess }.getOrThrow()
        service.delete(owner, newId(), chat.chatId, accepted.entryId, accepted.revision)
        assertEquals(0L, repository.totalVersionBytes(chat.chatId))
        assertEquals(GroupFileUsageSnapshot(activeEntries = 0L, activeVersionBytes = 0L), usage(chat.chatId))
    }

    @Test
    fun `concurrent entry admission uses one last slot and exact retry does not consume another`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("entry-cap-owner"))
        val chat = ctx.chatService.createGroup("条目容量", null, owner, emptyList())
        val repository = ExposedGroupFileRepository(
            database = ctx.database,
            capacityPolicy = GroupFileCapacityPolicy(
                maxActiveEntriesPerChat = 1,
                maxDirectChildrenPerParent = 1,
            ),
        )
        val service = GroupFileService(repository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
        val candidates = listOf(
            CreateFolderRequest(newId(), newId(), "候选一"),
            CreateFolderRequest(newId(), newId(), "候选二"),
        )

        val results = coroutineScope {
            candidates.map { request ->
                async(Dispatchers.Default) {
                    runCatching {
                        service.createFolder(
                            owner,
                            request.entryId,
                            request.commandId,
                            chat.chatId,
                            null,
                            request.name,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val acceptedIndex = results.indexOfFirst { it.isSuccess }
        val acceptedRequest = candidates[acceptedIndex]
        val acceptedEntry = results[acceptedIndex].getOrThrow()
        assertEquals(
            acceptedEntry,
            service.createFolder(
                owner,
                acceptedRequest.entryId,
                acceptedRequest.commandId,
                chat.chatId,
                null,
                acceptedRequest.name,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            service.createFolder(
                owner,
                acceptedRequest.entryId,
                acceptedRequest.commandId,
                chat.chatId,
                null,
                "${acceptedRequest.name}-changed",
            )
        }
        assertEquals(listOf(acceptedEntry.entryId), repository.list(chat.chatId, null).map { it.entryId })
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 0L), usage(chat.chatId))
    }

    @Test
    fun `zero byte versions stay bounded across repository restart and soft delete releases slots`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("zero-version-owner"))
        val chat = ctx.chatService.createGroup("零字节版本", null, owner, emptyList())
        val policy = GroupFileCapacityPolicy(
            maxTotalVersionBytesPerChat = 0,
            maxActiveEntriesPerChat = 1,
            maxDirectChildrenPerParent = 1,
            maxActiveVersionsPerFile = 2,
        )
        val firstRepository = ExposedGroupFileRepository(ctx.database, capacityPolicy = policy)
        val firstService = GroupFileService(firstRepository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
        val initialAttachment = storeAttachment(owner, "zero-v1.bin", ByteArray(0))
        val entryId = newId()
        val createCommandId = newId()
        val file = firstService.createFile(
            owner,
            entryId,
            createCommandId,
            chat.chatId,
            null,
            "zero.bin",
            initialAttachment,
        )

        assertEquals(
            file,
            firstService.createFile(
                owner,
                entryId,
                createCommandId,
                chat.chatId,
                null,
                "zero.bin",
                initialAttachment,
            ),
        )
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 0L), usage(chat.chatId))
        val competingVersions = listOf(
            AddVersionRequest(newId(), storeAttachment(owner, "zero-v2-a.bin", ByteArray(0))),
            AddVersionRequest(newId(), storeAttachment(owner, "zero-v2-b.bin", ByteArray(0))),
        )
        val results = coroutineScope {
            competingVersions.map { request ->
                async(Dispatchers.Default) {
                    runCatching {
                        firstService.addVersion(
                            owner,
                            request.commandId,
                            chat.chatId,
                            file.entryId,
                            request.attachment,
                            file.revision,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(0L, firstRepository.totalVersionBytes(chat.chatId))
        assertEquals(2, firstRepository.listVersions(file.entryId).size)
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 0L), usage(chat.chatId))

        val restartedRepository = ExposedGroupFileRepository(ctx.database, capacityPolicy = policy)
        val restartedService = GroupFileService(restartedRepository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
        val acceptedRequest = competingVersions[results.indexOfFirst { it.isSuccess }]
        val current = requireNotNull(restartedRepository.find(file.entryId))
        assertEquals(
            current,
            restartedService.addVersion(
                owner,
                acceptedRequest.commandId,
                chat.chatId,
                file.entryId,
                acceptedRequest.attachment,
                file.revision,
            ),
        )
        assertEquals(2, restartedRepository.listVersions(file.entryId).size)
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 0L), usage(chat.chatId))

        val mismatchedRetryAttachment = storeAttachment(owner, "zero-v2-mismatch.bin", ByteArray(0))
        assertFailsWith<IllegalArgumentException> {
            restartedService.addVersion(
                owner,
                acceptedRequest.commandId,
                chat.chatId,
                file.entryId,
                mismatchedRetryAttachment,
                file.revision,
            )
        }
        val overflowAttachment = storeAttachment(owner, "zero-v3.bin", ByteArray(0))
        assertFailsWith<IllegalArgumentException> {
            restartedService.addVersion(
                owner,
                newId(),
                chat.chatId,
                current.entryId,
                overflowAttachment,
                current.revision,
            )
        }

        restartedService.delete(owner, newId(), chat.chatId, current.entryId, current.revision)
        assertEquals(GroupFileUsageSnapshot(activeEntries = 0L, activeVersionBytes = 0L), usage(chat.chatId))
        val replacementAttachment = storeAttachment(owner, "zero-replacement.bin", ByteArray(0))
        val replacement = restartedService.createFile(
            owner,
            newId(),
            newId(),
            chat.chatId,
            null,
            "replacement.bin",
            replacementAttachment,
        )
        assertEquals(1L, replacement.contentVersion)
        assertEquals(listOf(replacement.entryId), restartedRepository.list(chat.chatId, null).map { it.entryId })
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 0L), usage(chat.chatId))
    }

    @Test
    fun `bounded reads reject historical child and version overflow instead of truncating`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("read-cap-owner"))
        val treeChat = ctx.chatService.createGroup("目录读边界", null, owner, emptyList())
        val permissivePolicy = GroupFileCapacityPolicy(
            maxTotalVersionBytesPerChat = 0,
            maxActiveEntriesPerChat = 4,
            maxDirectChildrenPerParent = 2,
            maxActiveVersionsPerFile = 2,
        )
        val writerRepository = ExposedGroupFileRepository(ctx.database, capacityPolicy = permissivePolicy)
        val writerService = GroupFileService(writerRepository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
        createFolder(writerService, owner, treeChat.chatId, null, "目录一")
        createFolder(writerService, owner, treeChat.chatId, null, "目录二")

        val strictChildrenReader = ExposedGroupFileRepository(
            ctx.database,
            capacityPolicy = permissivePolicy.copy(maxDirectChildrenPerParent = 1),
        )
        assertFailsWith<IllegalArgumentException> { strictChildrenReader.list(treeChat.chatId, null) }

        val versionChat = ctx.chatService.createGroup("版本读边界", null, owner, emptyList())
        val firstAttachment = storeAttachment(owner, "read-v1.bin", ByteArray(0))
        val file = createFile(writerService, owner, versionChat.chatId, null, "read.bin", firstAttachment)
        val secondAttachment = storeAttachment(owner, "read-v2.bin", ByteArray(0))
        addVersion(writerService, owner, versionChat.chatId, file.entryId, secondAttachment, file.revision)

        val strictVersionReader = ExposedGroupFileRepository(
            ctx.database,
            capacityPolicy = permissivePolicy.copy(maxActiveVersionsPerFile = 1),
        )
        assertFailsWith<IllegalArgumentException> { strictVersionReader.listVersions(file.entryId) }
    }

    @Test
    fun `missing usage ledger fails closed and is never reconstructed from historical rows`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("missing-ledger-owner"))
        val chat = ctx.chatService.createGroup("容量台账缺失", null, owner, emptyList())
        val repository = ExposedGroupFileRepository(ctx.database)
        val service = GroupFileService(repository, ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore)
        val firstAttachment = storeAttachment(owner, "ledger-v1.bin", byteArrayOf(1, 2, 3))
        val entryId = newId()
        val commandId = newId()
        val file = service.createFile(
            owner,
            entryId,
            commandId,
            chat.chatId,
            null,
            "ledger.bin",
            firstAttachment,
        )
        assertEquals(GroupFileUsageSnapshot(activeEntries = 1L, activeVersionBytes = 3L), usage(chat.chatId))

        transaction(ctx.database) {
            GroupFileChatUsages.deleteWhere { GroupFileChatUsages.chatId eq chat.chatId }
        }

        assertFailsWith<IllegalArgumentException> { repository.totalVersionBytes(chat.chatId) }
        assertFailsWith<IllegalArgumentException> {
            service.createFile(
                owner,
                entryId,
                commandId,
                chat.chatId,
                null,
                "ledger.bin",
                firstAttachment,
            )
        }
        val nextAttachment = storeAttachment(owner, "ledger-v2.bin", byteArrayOf(4))
        assertFailsWith<IllegalArgumentException> {
            addVersion(service, owner, chat.chatId, file.entryId, nextAttachment, file.revision)
        }
        assertEquals(1, repository.listVersions(file.entryId).size)
    }

    @Test
    fun `repository keeps directory tree valid inside mutation transaction`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("tree-owner"))
        val chat = ctx.chatService.createGroup("目录约束", null, owner, emptyList())
        val folder = createFolder(ctx.groupFileService, owner, chat.chatId, null, "父目录")
        val child = createFolder(ctx.groupFileService, owner, chat.chatId, folder.entryId, "子目录")

        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.groupFileRepo.delete(
                    transaction,
                    deleteCommand(chat.chatId, folder.entryId, folder.revision, owner),
                )
            }
        }

        ctx.groupFileService.delete(owner, newId(), chat.chatId, child.entryId, child.revision)
        ctx.groupFileService.delete(owner, newId(), chat.chatId, folder.entryId, folder.revision)
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
            ctx.pgUnitOfWork.write {
                ctx.groupFileRepo.create(transaction, folderCommand(orphan))
            }
        }
    }

    @Test
    fun `repository rechecks active group membership inside every write transaction`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("files-acl-owner"))
        val removed = ctx.registerUser(uniqueUsername("files-acl-removed"))
        val group = ctx.chatService.createGroup("写入权限事务", null, owner, listOf(removed))

        val firstPath = ctx.fileStore.store(
            removed,
            "before-removal.txt",
            "text/plain",
            ByteArrayInputStream("v1".encodeToByteArray()),
        )
        val firstAttachment = requireNotNull(ctx.fileStore.getAttachment(firstPath))
        val existing = createFile(
            ctx.groupFileService,
            removed,
            group.chatId,
            null,
            "成员文件.txt",
            firstAttachment,
        )

        ctx.chatService.removeMember(owner, group.chatId, removed)
        val afterRemoval = folderEntry(group.chatId, removed, "被移除后的目录")
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write { ctx.groupFileRepo.create(transaction, folderCommand(afterRemoval)) }
        }

        val secondPath = ctx.fileStore.store(
            removed,
            "after-removal.txt",
            "text/plain",
            ByteArrayInputStream("v2".encodeToByteArray()),
        )
        val secondAttachment = requireNotNull(ctx.fileStore.getAttachment(secondPath))
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.groupFileRepo.appendVersion(
                    transaction,
                    GroupFileAppendVersionCommand(
                        entryId = existing.entryId,
                        expectedRevision = existing.revision,
                        attachment = secondAttachment,
                        actorUid = removed,
                        commandId = newId(),
                        fingerprint = validFingerprint('b'),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.groupFileRepo.rename(
                    transaction,
                    renameCommand(group.chatId, existing.entryId, existing.revision, "越权重命名.txt", removed),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.groupFileRepo.delete(
                    transaction,
                    deleteCommand(group.chatId, existing.entryId, existing.revision, removed),
                )
            }
        }
        assertEquals(listOf(existing.entryId), ctx.groupFileRepo.list(group.chatId, null).map { it.entryId })

        ctx.chatService.dissolveGroup(owner, group.chatId)
        val afterDissolve = folderEntry(group.chatId, owner, "解散后的目录")
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write { ctx.groupFileRepo.create(transaction, folderCommand(afterDissolve)) }
        }
        assertEquals(listOf(existing.entryId), ctx.groupFileRepo.list(group.chatId, null).map { it.entryId })
    }

    private fun folderEntry(chatId: String, actorUid: String, name: String): GroupFileEntry {
        val now = System.currentTimeMillis()
        return GroupFileEntry(
            entryId = UUID.randomUUID().toString(),
            chatId = chatId,
            kind = GroupFileEntry.KIND_FOLDER,
            name = name,
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
    }

    private fun folderCommand(entry: GroupFileEntry) = GroupFileCreateCommand(
        entry = entry,
        initialVersion = null,
        commandId = newId(),
        fingerprint = validFingerprint('a'),
    )

    private fun renameCommand(
        chatId: String,
        entryId: String,
        expectedRevision: Long,
        name: String,
        actorUid: String,
    ) = GroupFileRenameCommand(
        commandId = newId(),
        chatId = chatId,
        entryId = entryId,
        name = name,
        expectedRevision = expectedRevision,
        actorUid = actorUid,
        fingerprint = validFingerprint('c'),
        updatedAt = System.currentTimeMillis(),
    )

    private fun deleteCommand(
        chatId: String,
        entryId: String,
        expectedRevision: Long,
        actorUid: String,
    ) = GroupFileDeleteCommand(
        commandId = newId(),
        chatId = chatId,
        entryId = entryId,
        expectedRevision = expectedRevision,
        actorUid = actorUid,
        fingerprint = validFingerprint('d'),
        deletedAt = System.currentTimeMillis(),
    )

    private suspend fun createFolder(
        service: GroupFileService,
        actorUid: String,
        chatId: String,
        parentId: String?,
        name: String,
    ): GroupFileEntry = service.createFolder(actorUid, newId(), newId(), chatId, parentId, name)

    private suspend fun createFile(
        service: GroupFileService,
        actorUid: String,
        chatId: String,
        parentId: String?,
        name: String,
        attachment: Attachment,
    ): GroupFileEntry = service.createFile(actorUid, newId(), newId(), chatId, parentId, name, attachment)

    private suspend fun addVersion(
        service: GroupFileService,
        actorUid: String,
        chatId: String,
        entryId: String,
        attachment: Attachment,
        expectedRevision: Long,
    ): GroupFileEntry = service.addVersion(
        actorUid,
        newId(),
        chatId,
        entryId,
        attachment,
        expectedRevision,
    )

    private fun usage(chatId: String): GroupFileUsageSnapshot = transaction(ctx.database) {
        val row = GroupFileChatUsages.selectAll().where {
            GroupFileChatUsages.chatId eq chatId
        }.single()
        GroupFileUsageSnapshot(
            activeEntries = row[GroupFileChatUsages.activeEntries],
            activeVersionBytes = row[GroupFileChatUsages.activeVersionBytes],
        )
    }

    private fun commandCount(commandId: String): Long = transaction(ctx.database) {
        GroupFileCommands.selectAll().where { GroupFileCommands.commandId eq commandId }.count()
    }

    private fun commandKind(commandId: String): Int = transaction(ctx.database) {
        GroupFileCommands.selectAll().where { GroupFileCommands.commandId eq commandId }
            .single()[GroupFileCommands.kind]
    }

    private fun entryRevisionIncludingDeleted(entryId: String): Long = transaction(ctx.database) {
        GroupFileEntries.selectAll().where { GroupFileEntries.entryId eq entryId }
            .single()[GroupFileEntries.revision]
    }

    private fun auditCount(entryId: String, action: String): Long = transaction(ctx.database) {
        GroupFileAudits.selectAll().where {
            (GroupFileAudits.entryId eq entryId) and (GroupFileAudits.action eq action)
        }.count()
    }

    private fun storeAttachment(actorUid: String, name: String, content: ByteArray) =
        requireNotNull(
            ctx.fileStore.getAttachment(
                ctx.fileStore.store(
                    actorUid,
                    name,
                    "application/octet-stream",
                    ByteArrayInputStream(content),
                ),
            ),
        )

    private fun newId(): String = UUID.randomUUID().toString()

    private fun validFingerprint(character: Char): String = character.toString().repeat(64)

    private data class CreateFolderRequest(
        val entryId: String,
        val commandId: String,
        val name: String,
    )

    private data class AddVersionRequest(
        val commandId: String,
        val attachment: Attachment,
    )

    private data class GroupFileUsageSnapshot(
        val activeEntries: Long,
        val activeVersionBytes: Long,
    )
}
