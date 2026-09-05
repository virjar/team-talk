package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.GroupFileChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.infra.db.repository.ExposedGroupFileRepository
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTENT-01：群文件变更事件流。事件与变更同事务提交、按群行锁串行（成员流内顺序=变更顺序）、
 * 收件人是提交时刻活动成员；精确重放只确认既有回执，不再次推进任何成员的事件流。
 */
class GroupFileChangeEventIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private fun eventsAfter(uid: String, afterSeq: Long): List<GroupFileChangedPayload> = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and
                (SyncEvents.streamSeq greater afterSeq) and
                (SyncEvents.eventType eq NotifyType.GROUP_FILE_CHANGED.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { ProtoCodec.decode(GroupFileChangedPayload, it[SyncEvents.payload]) }
    }

    private fun latestSeq(uid: String): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq, SortOrder.DESC).limit(1)
            .firstOrNull()?.get(SyncEvents.streamSeq) ?: 0L
    }

    @Test
    fun `folder create broadcasts upsert to every member and outsiders receive nothing`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfe-owner"))
        val member = ctx.registerUser(uniqueUsername("gfe-member"))
        val outsider = ctx.registerUser(uniqueUsername("gfe-outsider"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "事件群", null, owner, listOf(member))

        val baseOwner = latestSeq(owner)
        val baseMember = latestSeq(member)
        val baseOutsider = latestSeq(outsider)

        val folder = ctx.groupFileService.createFolder(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), group.chatId, null, "会议纪要")

        val ownerEvents = eventsAfter(owner, baseOwner)
        val memberEvents = eventsAfter(member, baseMember)
        assertEquals(1, ownerEvents.size, "创建者本人也是收件人")
        assertEquals(1, memberEvents.size)
        assertEquals(0, eventsAfter(outsider, baseOutsider).size, "非群成员不收事件")

        val payload = memberEvents.single()
        assertEquals(GroupFileChangedPayload.OPERATION_UPSERT, payload.operation)
        assertEquals(group.chatId, payload.chatId)
        val eventEntry = requireNotNull(payload.entry)
        assertEquals(folder.entryId, eventEntry.entryId)
        assertEquals("会议纪要", eventEntry.name)
        assertEquals(folder.revision, eventEntry.revision)
    }

    @Test
    fun `file version and rename carry monotonic revisions in member stream order`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfe-ver-owner"))
        val member = ctx.registerUser(uniqueUsername("gfe-ver-member"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "版本群", null, owner, listOf(member))
        val path = File.createTempFile("gfe-ver", ".tmp").apply { writeText("v1") }
        val attachmentPath = try {
            ctx.fileStore.store(owner, "spec.md", "text/markdown", path)
        } finally {
            path.delete()
        }
        val attachment = requireNotNull(ctx.fileStore.getAttachment(attachmentPath))
        val file = ctx.groupFileService.createFile(
            owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            group.chatId, null, "spec.md", attachment,
        )

        val baseMember = latestSeq(member)
        val v2 = File.createTempFile("gfe-v2", ".tmp").apply { writeText("v2 content") }
        val v2Path = try {
            ctx.fileStore.store(owner, "spec-v2.md", "text/markdown", v2)
        } finally {
            v2.delete()
        }
        ctx.groupFileService.addVersion(
            owner, UUID.randomUUID().toString(), group.chatId, file.entryId,
            requireNotNull(ctx.fileStore.getAttachment(v2Path)), file.revision,
        )
        val renamed = ctx.groupFileService.rename(
            owner, UUID.randomUUID().toString(), group.chatId, file.entryId, "最终规格.md",
            file.revision + 1,
        )

        val events = eventsAfter(member, baseMember)
        assertEquals(2, events.size)
        assertTrue(events.all { it.operation == GroupFileChangedPayload.OPERATION_UPSERT })
        assertEquals(file.revision + 1, events[0].entry!!.revision, "版本追加后 revision+1")
        assertEquals(file.revision + 2, events[1].entry!!.revision, "重命名再+1")
        assertEquals("最终规格.md", events[1].entry!!.name)
        assertEquals("最终规格.md", requireNotNull(renamed).name)
    }

    @Test
    fun `delete broadcasts a tombstone revision and replay does not resurrect`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfe-del-owner"))
        val member = ctx.registerUser(uniqueUsername("gfe-del-member"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "删除群", null, owner, listOf(member))
        val folder = ctx.groupFileService.createFolder(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), group.chatId, null, "待删目录")

        val baseMember = latestSeq(member)
        val deleteCommandId = UUID.randomUUID().toString()
        ctx.groupFileService.delete(owner, deleteCommandId, group.chatId, folder.entryId, folder.revision)

        val events = eventsAfter(member, baseMember)
        assertEquals(1, events.size)
        val delete = events.single()
        assertEquals(GroupFileChangedPayload.OPERATION_DELETE, delete.operation)
        assertEquals(folder.entryId, delete.deletedEntryId)
        assertEquals(folder.revision + 1, delete.deletedRevision, "墓穴 revision 是删除后的最终 revision")

        assertTrue(
            ctx.groupFileService.list(owner, group.chatId, null).isEmpty(),
            "删除后目录为空",
        )
        // 幂等重放（同 commandId + 原指纹）：回执命中返回原结果，不产生新数据行
        val afterReplayBase = latestSeq(member)
        ctx.groupFileService.delete(owner, deleteCommandId, group.chatId, folder.entryId, folder.revision)
        assertEquals(0, eventsAfter(member, afterReplayBase).size, "回执命中不再追加事件")
        assertFailsWith<IllegalArgumentException> {
            // 已删除条目对任何新 commandId 的删除都是明确失败，不复活
            ctx.groupFileService.delete(owner, UUID.randomUUID().toString(), group.chatId, folder.entryId, folder.revision)
        }
    }

    @Test
    fun `removed member stops receiving but historical events remain replayable`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfe-rm-owner"))
        val member = ctx.registerUser(uniqueUsername("gfe-rm-member"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "移除群", null, owner, listOf(member))
        ctx.groupFileService.createFolder(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), group.chatId, null, "移除前")

        val baseMember = latestSeq(member)
        ctx.chatService.removeMember(owner, group.chatId, member)
        ctx.groupFileService.createFolder(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), group.chatId, null, "移除后")

        assertEquals(0, eventsAfter(member, baseMember).size, "移除后不再收新事件")
        assertTrue(eventsAfter(member, 0).isNotEmpty(), "历史事件仍在流内可重放")
    }

    @Test
    fun `all five command replays return acknowledgements without appending change events`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfe-replay-owner"))
        val member = ctx.registerUser(uniqueUsername("gfe-replay-member"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "重放群", null, owner, listOf(member))
        val baseOwner = latestSeq(owner)
        val baseMember = latestSeq(member)
        val folderId = UUID.randomUUID().toString()
        val folderCommandId = UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()
        val fileCommandId = UUID.randomUUID().toString()
        val versionCommandId = UUID.randomUUID().toString()
        val renameCommandId = UUID.randomUUID().toString()
        val deleteCommandId = UUID.randomUUID().toString()
        val path = ctx.fileStore.store(
            owner, "spec.md", "text/markdown", ByteArrayInputStream("版本内容".encodeToByteArray()),
        )
        val attachment = requireNotNull(ctx.fileStore.getAttachment(path))
        val folder = ctx.groupFileService.createFolder(owner, folderId, folderCommandId, group.chatId, null, "资料")
        val file = ctx.groupFileService.createFile(
            owner, fileId, fileCommandId, group.chatId, folderId, "spec.md", attachment,
        )
        val version = ctx.groupFileService.addVersion(
            owner, versionCommandId, group.chatId, fileId, attachment, file.revision,
        )
        val renamed = requireNotNull(ctx.groupFileService.rename(
            owner, renameCommandId, group.chatId, fileId, "最终版.md", version.revision,
        ))

        // 重新装配服务与仓储，确认去重来自 PostgreSQL 回执，而不是服务实例内的状态。
        val restarted = GroupFileService(
            ExposedGroupFileRepository(ctx.database), ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore,
        )
        assertEquals(folder, restarted.createFolder(owner, folderId, folderCommandId, group.chatId, null, "资料"))
        assertEquals(renamed, restarted.createFile(
            owner, fileId, fileCommandId, group.chatId, folderId, "spec.md", attachment,
        ), "创建命令重放保持既有 RPC 语义，返回当前条目")
        assertEquals(renamed, restarted.addVersion(
            owner, versionCommandId, group.chatId, fileId, attachment, file.revision,
        ), "版本命令重放不倒退当前条目")
        assertNull(restarted.rename(owner, renameCommandId, group.chatId, fileId, "最终版.md", version.revision))

        restarted.delete(owner, deleteCommandId, group.chatId, fileId, renamed.revision)
        restarted.delete(owner, deleteCommandId, group.chatId, fileId, renamed.revision)
        for ((uid, baseline) in listOf(owner to baseOwner, member to baseMember)) {
            val events = eventsAfter(uid, baseline)
            assertEquals(5, events.size, "五次实际变更只产生五条事件，精确重放不占用新的同步序号")
            assertEquals(
                listOf(folderId, fileId, fileId, fileId, null),
                events.map { it.entry?.entryId },
            )
            assertEquals(GroupFileChangedPayload.OPERATION_DELETE, events.last().operation)
        }
    }

    @Test
    fun `rename receipt remains acknowledgeable after removal and deletion without reading the entry`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("gfe-retired-owner"))
        val member = ctx.registerUser(uniqueUsername("gfe-retired-member"))
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "历史回执群", null, owner, listOf(member))
        val folder = ctx.groupFileService.createFolder(
            member, UUID.randomUUID().toString(), UUID.randomUUID().toString(), group.chatId, null, "原名称",
        )
        val commandId = UUID.randomUUID().toString()
        val renamed = requireNotNull(ctx.groupFileService.rename(
            member, commandId, group.chatId, folder.entryId, "已提交名称", folder.revision,
        ))
        ctx.chatService.removeMember(owner, group.chatId, member)
        val beforeReplay = latestSeq(owner)
        assertNull(ctx.groupFileService.rename(
            member, commandId, group.chatId, folder.entryId, "已提交名称", folder.revision,
        ))
        assertEquals(0, eventsAfter(owner, beforeReplay).size, "离群成员的旧回执不能触发群内新事件")

        ctx.groupFileService.delete(owner, UUID.randomUUID().toString(), group.chatId, folder.entryId, renamed.revision)
        val afterDelete = latestSeq(owner)
        val restarted = GroupFileService(
            ExposedGroupFileRepository(ctx.database), ctx.chatAccess, ctx.fileStore, ctx.pgUnitOfWork, ctx.chatStore,
        )
        assertNull(restarted.rename(member, commandId, group.chatId, folder.entryId, "已提交名称", folder.revision))
        assertEquals(0, eventsAfter(owner, afterDelete).size, "条目已删除不影响已提交命令的成功确认")
        assertFailsWith<ReliableCommandConflictException> {
            restarted.rename(member, commandId, group.chatId, folder.entryId, "不同请求", folder.revision)
        }
        assertFailsWith<IllegalArgumentException> {
            restarted.rename(member, UUID.randomUUID().toString(), group.chatId, folder.entryId, "新请求", folder.revision)
        }
    }
}
