package com.virjar.tk.server.integration

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.message.OfficeRefResolver
import com.virjar.tk.server.domain.task.*
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.db.repository.ExposedTaskRepository
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TaskDetailsIntegrationTest {
    companion object { @JvmField @RegisterExtension val ext = IntegrationTestExtension() }
    private val ctx get() = ext.env
    private val repository = ExposedTaskRepository()
    private val now = AtomicLong(System.currentTimeMillis())
    private val lifecycle = AttachmentLifecycleGate()
    private fun service(uow: PgUnitOfWork = ctx.pgUnitOfWork) = TaskService(repository, uow, now::get,
        ctx.fileStore, lifecycle, OfficeRefResolver(ctx.documentService, ctx.groupFileService))
    private fun id() = UUID.randomUUID().toString()
    private suspend fun user() = ctx.registerUser(uniqueUsername("task-details"))
    private fun create(assignee: String, options: TaskOptions = TaskOptions(), group: String? = null, dueAt: Long? = null,
        recurrence: TaskRecurrenceRule? = null) = TaskDetailsCommand(id(), now.get(), id(), 0, TaskDetailsCommand.CREATE,
        TaskDraft("待办计划", "# 完整说明\n\n- 材料与任务同时关联", assignee,
            if (group == null) TaskPolicy.CONTEXT_NONE else TaskPolicy.CONTEXT_GROUP, group.orEmpty(), dueAt), options, recurrenceRule = recurrence)
    private suspend fun create(uid: String, command: TaskDetailsCommand) = assertNotNull(service().modify(uid, command).task)
    private fun edit(details: TaskDetails, options: TaskOptions = details.options, assignee: String = details.task.assigneeUid) =
        with(details.task) { TaskDetailsCommand(id(), now.get(), taskId, revision, TaskDetailsCommand.EDIT,
            TaskDraft(title, description, assignee, contextKind, contextId, dueAt), options) }
    private suspend fun status(uid: String, details: TaskDetails, status: Int) = service().mutate(uid,
        TaskCommand(id(), now.get(), details.task.taskId, details.task.revision, TaskCommand.STATUS, status = status))
    private fun events(uid: String, type: NotifyType) = transaction(ctx.database) {
        SyncEvents.selectAll().where { (SyncEvents.uid eq uid) and (SyncEvents.eventType eq type.code) }
            .orderBy(SyncEvents.streamSeq).map { it[SyncEvents.payload] }
    }

    @Test fun `legacy tasks retain bytes private visibility and unknown historical metrics after reopen`() = runTest {
        val owner = user(); val assignee = user(); val member = user()
        val group = ctx.chatService.createGroup(id(), "旧任务", null, owner, listOf(assignee, member))
        val command = TaskCommand(id(), now.get(), id(), 0, TaskCommand.CREATE,
            TaskDraft("旧正文", "*这是普通文本*", assignee, TaskPolicy.CONTEXT_GROUP, group.chatId, now.get() + 1000))
        val task = assertNotNull(service().mutate(owner, command).task)
        // A released database has no extension row. No original deadline is inferred during migration.
        transaction(ctx.database) { TaskExtensions.deleteWhere { TaskExtensions.taskId eq task.taskId } }
        val reopened = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        assertContentEquals(ProtoCodec.encode(task), ProtoCodec.encode(reopened.details(assignee, task.taskId).task))
        assertEquals(TaskOptions(), reopened.details(owner, task.taskId).options)
        assertEquals(TaskMetrics(), reopened.details(owner, task.taskId).metrics)
        assertFailsWith<TaskAccessDeniedException> { reopened.details(member, task.taskId) }
        assertEquals(0L, reopened.query(member, TaskQuery(TaskQuery.GROUP, group.chatId), null, 1).summary.totalCount)
        assertEquals(task, reopened.mutate(owner, command).task)
    }

    @Test fun `group summary covers all shared rows and current members while edits stay owner controlled`() = runTest {
        val owner = user(); val assignee = user(); val member = user(); val outsider = user()
        val group = ctx.chatService.createGroup(id(), "群待办", null, owner, listOf(assignee, member))
        create(owner, create(assignee, group = group.chatId)) // Private association never enters the card.
        val tasks = List(3) { index -> create(owner, create(assignee, TaskOptions(shareToGroup = true), group.chatId, now.get() + index - 1)) }
        val page = service().query(member, TaskQuery(TaskQuery.GROUP, group.chatId, openOnly = true), null, 1)
        assertEquals(1, page.items.size); assertEquals(3L, page.summary.openCount); assertEquals(1L, page.summary.overdueCount)
        assertNotNull(page.nextCursor)
        val second = service().query(member, TaskQuery(TaskQuery.GROUP, group.chatId, openOnly = true), page.nextCursor, 1)
        assertEquals(page.summary, second.summary); assertNotEquals(page.items.single().task.taskId, second.items.single().task.taskId)
        assertFailsWith<TaskAccessDeniedException> { service().query(outsider, TaskQuery(TaskQuery.GROUP, group.chatId), null, 20) }
        assertFailsWith<TaskAccessDeniedException> { service().modify(member, edit(tasks.first())) }
        assertFailsWith<TaskAccessDeniedException> { status(member, tasks.first(), TaskPolicy.DONE) }
        val hidden = create(owner, edit(tasks.first(), options = TaskOptions()))
        assertFailsWith<TaskAccessDeniedException> { service().details(member, hidden.task.taskId) }
        assertEquals(TaskChangedPayload.REVOKED, ProtoCodec.decode(TaskChangedPayload, events(member, NotifyType.TASK_CHANGED).last()).kind)
        ctx.chatService.leaveGroup(member, group.chatId)
        assertFailsWith<TaskAccessDeniedException> { service().details(member, tasks.last().task.taskId) }
        assertEquals(tasks.last().task, service().get(assignee, tasks.last().task.taskId))
    }

    @Test fun `deferral receipts audit history original deadline and processing aggregate share one transaction`() = runTest {
        val owner = user(); val assignee = user(); val next = user()
        val original = create(owner, create(assignee, TaskOptions(TaskOptions.MARKDOWN), dueAt = now.get() + 1000))
        now.addAndGet(100)
        val command = TaskDetailsCommand(id(), now.get(), original.task.taskId, original.task.revision, TaskDetailsCommand.DEFER,
            deferDueAt = now.get() + 2000, reason = "等待原始材料")
        assertFailsWith<TaskAccessDeniedException> { service().modify(owner, command) }
        val deferred = create(assignee, command)
        assertEquals(original.task.dueAt, deferred.metrics.originalDueAt); assertEquals(1, deferred.metrics.deferralCount)
        assertEquals(deferred, service().modify(assignee, command).task)
        assertFailsWith<ReliableCommandConflictException> { service().modify(assignee, command.copy(reason = "另一意图")) }
        val entry = service().history(owner, original.task.taskId, null, 1).items.single()
        assertEquals("等待原始材料", entry.deferral?.reason); assertEquals(TaskAudit.EDITED, entry.audit.action)
        now.addAndGet(100)
        status(assignee, deferred, TaskPolicy.DONE)
        val finished = service().details(assignee, original.task.taskId)
        assertEquals(200L, finished.metrics.processingMillis)
        val summary = service().query(assignee, TaskQuery(), null, 1).summary
        assertEquals(1L, summary.completedCount); assertEquals(200L, summary.averageProcessingMillis)
        val reassigned = create(owner, edit(finished, assignee = next))
        assertNull(service().modify(assignee, command).task)
        assertEquals(1, reassigned.metrics.deferralCount)
        assertEquals(TaskOptions.MARKDOWN, reassigned.options.descriptionFormat)
        // Released EDIT changes only its own fields and keeps extension facts.
        val legacy = TaskCommand(id(), now.get(), reassigned.task.taskId, reassigned.task.revision, TaskCommand.EDIT,
            TaskDraft("旧端改标题", reassigned.task.description, next, dueAt = reassigned.task.dueAt))
        service().mutate(owner, legacy)
        assertEquals(reassigned.options, service().details(owner, reassigned.task.taskId).options)
        assertEquals(reassigned.metrics, service().details(owner, reassigned.task.taskId).metrics)
    }

    @Test fun `start reminder survives restart without altering released due marker and rearms on reassignment`() = runTest {
        val owner = user(); val assignee = user(); val next = user()
        val initial = create(owner, create(assignee, TaskOptions(startsAt = now.get() + 100), dueAt = now.get() + 1000))
        assertEquals(0L, service().query(assignee, TaskQuery(openOnly = true, startedOnly = true), null, 20).summary.totalCount)
        now.addAndGet(100)
        val restarted = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        restarted.remindStarted(); restarted.remindStarted()
        assertEquals(1, events(assignee, NotifyType.TASK_STARTED).size)
        val started = restarted.details(assignee, initial.task.taskId)
        assertNotNull(started.startRemindedAt); assertNull(started.task.remindedAt)
        assertEquals(1L, restarted.query(assignee, TaskQuery(openOnly = true, startedOnly = true), null, 20).summary.openCount)
        create(owner, edit(started, assignee = next)); restarted.remindStarted()
        val assigned = restarted.details(next, initial.task.taskId)
        assertTrue(assertNotNull(assigned.startRemindedAt) > assertNotNull(started.startRemindedAt))
        assertTrue(events(next, NotifyType.TASK_DUE).isEmpty())
        now.addAndGet(900); restarted.remindDue()
        assertEquals(1, events(next, NotifyType.TASK_DUE).size)
        status(next, restarted.details(next, initial.task.taskId), TaskPolicy.DONE)
        assertEquals(0L, restarted.query(next, TaskQuery(openOnly = true, startedOnly = true), null, 20).summary.openCount)
        val simultaneous = create(owner, create(next, TaskOptions(startsAt = now.get()), dueAt = now.get()))
        restarted.remindStarted(); restarted.remindDue()
        val both = restarted.details(next, simultaneous.task.taskId)
        assertNotEquals(both.startRemindedAt, both.task.remindedAt)
        assertTrue(assertNotNull(both.task.remindedAt) > assertNotNull(both.startRemindedAt))
    }

    @Test fun `weekly occurrences are independent latest missed only and enable receipts use the task namespace`() = runTest {
        val owner = user(); val assignee = user()
        now.set(Instant.parse("2026-09-14T00:00:00Z").toEpochMilli())
        val first = create(owner, create(assignee, recurrence = TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 1, "2026-09-14", "09:00", "17:00", "Asia/Shanghai")))
        assertEquals("2026-09-14", first.occurrenceDate)
        assertEquals(Instant.parse("2026-09-14T01:00:00Z").toEpochMilli(), first.options.startsAt)
        val initialSeries = assertNotNull(first.series)
        val pausedBeforeStart = service().modifySeries(owner, TaskSeriesCommand(id(), now.get(), initialSeries.seriesId, initialSeries.revision, false))
        val resumedBeforeStart = service().modifySeries(owner, TaskSeriesCommand(id(), now.get(), initialSeries.seriesId, pausedBeforeStart.revision, true))
        assertEquals(initialSeries.nextOccurrenceAt, resumedBeforeStart.nextOccurrenceAt)
        now.set(Instant.parse("2026-09-14T02:00:00Z").toEpochMilli()); service().generateRecurring()
        assertEquals(1L, service().query(assignee, TaskQuery(), null, 20).summary.totalCount)
        // An unrelated user can claim client-selected UUIDs, including the old predictable occurrence
        // formula. Generation is governed by the series lock/date, not that public ID namespace.
        val outsider = user()
        val predicted = UUID.nameUUIDFromBytes("task-weekly:${initialSeries.seriesId}:2026-10-05".toByteArray(Charsets.UTF_8)).toString()
        create(outsider, create(outsider).copy(taskId = predicted))
        now.set(Instant.parse("2026-10-05T02:00:00Z").toEpochMilli())
        assertTrue(service().generateRecurring() >= 1); service().generateRecurring()
        val page = service().query(assignee, TaskQuery(), null, 20)
        assertEquals(2, page.items.size)
        val latest = page.items.first { it.task.taskId != first.task.taskId }
        assertEquals("2026-10-05", latest.occurrenceDate); assertEquals(TaskPolicy.TODO, latest.task.status)
        status(assignee, latest, TaskPolicy.DONE)
        assertEquals(TaskPolicy.TODO, service().get(owner, first.task.taskId).status)
        val currentSeries = assertNotNull(service().details(owner, first.task.taskId).series)
        val disable = TaskSeriesCommand(id(), now.get(), currentSeries.seriesId, currentSeries.revision, false)
        val stopped = service().modifySeries(owner, disable)
        assertFalse(stopped.enabled); assertEquals(stopped, service().modifySeries(owner, disable))
        assertNotNull(ctx.pgUnitOfWork.read { repository.findReceipt(transaction, owner, disable.operationId) })
        assertFailsWith<TaskAccessDeniedException> { service().modifySeries(assignee, disable.copy(operationId = id(), expectedRevision = stopped.revision, enabled = true)) }
        now.addAndGet(8L * 24 * 60 * 60 * 1000); service().generateRecurring()
        assertEquals(2L, service().query(owner, TaskQuery(TaskQuery.CREATED), null, 20).summary.totalCount)
    }

    @Test fun `processing average uses nonnegative duration per completed task and ignores unknown history`() = runTest {
        val owner = user(); val assignee = user()
        val early = create(owner, create(assignee, TaskOptions(startsAt = now.get() + 1000), dueAt = now.get() + 2000))
        status(assignee, early, TaskPolicy.DONE)
        val normal = create(owner, create(assignee))
        now.addAndGet(1000); status(assignee, normal, TaskPolicy.DONE)
        val unknown = create(owner, create(assignee))
        status(assignee, unknown, TaskPolicy.DONE)
        transaction(ctx.database) { TaskExtensions.deleteWhere { TaskExtensions.taskId eq unknown.task.taskId } }
        val summary = service().query(assignee, TaskQuery(), null, 20).summary
        assertEquals(3L, summary.completedCount)
        assertEquals(500L, summary.averageProcessingMillis)
        assertEquals(0L, service().details(assignee, early.task.taskId).metrics.processingMillis)
    }

    @Test fun `creating a recurring task includes the current unfinished period but skips expired history`() = runTest {
        val owner = user(); val assignee = user()
        val rule = TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 1, "2026-09-14", "09:00", "18:00", "Asia/Shanghai")
        now.set(Instant.parse("2026-09-14T03:00:00Z").toEpochMilli()) // 周一 11:00，本期尚未截止。
        val current = create(owner, create(assignee, recurrence = rule))
        assertEquals("2026-09-14", current.occurrenceDate)
        assertEquals(Instant.parse("2026-09-14T01:00:00Z").toEpochMilli(), current.options.startsAt)
        assertEquals(Instant.parse("2026-09-14T10:00:00Z").toEpochMilli(), current.task.dueAt)
        assertTrue(service().remindStarted() >= 1)
        service().remindStarted()
        assertEquals(1, events(assignee, NotifyType.TASK_STARTED).size)
        assertTrue(events(assignee, NotifyType.TASK_DUE).isEmpty())

        now.set(Instant.parse("2026-09-14T11:00:00Z").toEpochMilli()) // 周一 19:00，历史本期已结束。
        val next = create(owner, create(assignee, recurrence = rule))
        assertEquals("2026-09-21", next.occurrenceDate)
        assertEquals(Instant.parse("2026-09-21T01:00:00Z").toEpochMilli(), next.options.startsAt)
        assertEquals(Instant.parse("2026-09-21T10:00:00Z").toEpochMilli(), next.task.dueAt)
        assertEquals(rule, assertNotNull(next.series).recurrenceRule)
        val future = create(owner, create(assignee, recurrence = rule.copy(firstDate = "2026-09-28")))
        assertEquals("2026-09-28", future.occurrenceDate)
    }

    @Test fun `monthly anchor survives database reread pause resume and latest missed generation without day drift`() = runTest {
        val owner = user(); val assignee = user()
        now.set(Instant.parse("2024-01-31T00:00:00Z").toEpochMilli())
        val rule = TaskRecurrenceRule(TaskRecurrenceRule.MONTHLY, 1, "2024-01-31", "09:00", "17:00", "Asia/Shanghai")
        val first = create(owner, create(assignee, recurrence = rule))
        val seriesId = assertNotNull(first.series).seriesId
        assertEquals(Instant.parse("2024-02-29T01:00:00Z").toEpochMilli(), first.series?.nextOccurrenceAt)
        val reopened = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        now.set(Instant.parse("2024-02-29T02:00:00Z").toEpochMilli())
        assertTrue(reopened.generateRecurring() >= 1)
        service().generateRecurring()
        val february = reopened.query(assignee, TaskQuery(), null, 20)
        assertEquals(setOf("2024-01-31", "2024-02-29"), february.items.map { it.occurrenceDate }.toSet())
        val afterFebruary = assertNotNull(reopened.details(owner, first.task.taskId).series)
        assertEquals(Instant.parse("2024-03-31T01:00:00Z").toEpochMilli(), afterFebruary.nextOccurrenceAt)
        val disabled = reopened.modifySeries(owner, TaskSeriesCommand(id(), now.get(), seriesId, afterFebruary.revision, false))
        now.set(Instant.parse("2024-04-15T00:00:00Z").toEpochMilli())
        val resumed = service().modifySeries(owner, TaskSeriesCommand(id(), now.get(), seriesId, disabled.revision, true))
        assertEquals(rule, resumed.recurrenceRule)
        assertEquals(Instant.parse("2024-04-30T01:00:00Z").toEpochMilli(), resumed.nextOccurrenceAt)
        now.set(Instant.parse("2024-04-30T02:00:00Z").toEpochMilli())
        service().generateRecurring()
        val april = reopened.query(assignee, TaskQuery(), null, 20)
        assertEquals(setOf("2024-01-31", "2024-02-29", "2024-04-30"), april.items.map { it.occurrenceDate }.toSet())
        assertEquals(Instant.parse("2024-05-31T01:00:00Z").toEpochMilli(), reopened.details(owner, first.task.taskId).series?.nextOccurrenceAt)

        now.set(Instant.parse("2024-08-31T02:00:00Z").toEpochMilli())
        service().generateRecurring(); reopened.generateRecurring()
        val latest = reopened.query(assignee, TaskQuery(), null, 20)
        assertEquals(4L, latest.summary.totalCount)
        assertEquals(setOf("2024-01-31", "2024-02-29", "2024-04-30", "2024-08-31"), latest.items.map { it.occurrenceDate }.toSet())
        assertEquals(Instant.parse("2024-09-30T01:00:00Z").toEpochMilli(), reopened.details(owner, first.task.taskId).series?.nextOccurrenceAt)
    }

    @Test fun `materials retain independent document permission and task attachment read and GC ownership`() = runTest {
        val owner = user(); val assignee = user(); val member = user(); val outsider = user()
        val group = ctx.chatService.createGroup(id(), "关联材料", null, owner, listOf(assignee, member))
        val space = ctx.documentService.createSpace(owner, "私有材料", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "权威标题", "# private")
        val path = ctx.fileStore.store(owner, "report.txt", "text/plain", "报告材料".byteInputStream())
        val attachment = assertNotNull(ctx.fileStore.getAttachment(path))
        val options = TaskOptions(TaskOptions.MARKDOWN, shareToGroup = true,
            documentRefs = listOf(OfficeRefBody(1, space.spaceId, document.documentId, "伪造标题")), attachments = listOf(attachment))
        val command = create(assignee, options, group.chatId)
        assertFailsWith<IllegalArgumentException> { service().modify(outsider, create(outsider, TaskOptions(attachments = listOf(attachment)))) }
        val task = create(owner, command)
        assertEquals("权威标题", task.options.documentRefs.single().title)
        assertEquals(task, service().modify(owner, command).task)
        assertFalse(ctx.fileStore.isStaging(path))
        assertTrue(ctx.attachmentAccess.canRead(member, path)); assertFalse(ctx.attachmentAccess.canRead(outsider, path))
        assertFailsWith<DocumentAccessDeniedException> { ctx.documentService.getDocument(assignee, space.spaceId, document.documentId) }
        ctx.cleanupExpiredAttachments(now.get() + 40L * 24 * 60 * 60 * 1000)
        assertNotNull(ctx.fileStore.getAttachment(path))
        ctx.chatService.leaveGroup(member, group.chatId)
        assertFalse(ctx.attachmentAccess.canRead(member, path)); assertTrue(ctx.attachmentAccess.canRead(assignee, path))
        create(owner, edit(task, task.options.copy(attachments = emptyList())))
        assertFalse(ctx.attachmentAccess.canRead(owner, path))
        ctx.cleanupExpiredAttachments(now.get() + 40L * 24 * 60 * 60 * 1000)
        assertNull(ctx.fileStore.getAttachment(path))
    }

    @Test fun `extension write and start delivery roll back with receipt and event flush and new RPC is minor gated`() = runTest {
        val owner = user(); val assignee = user()
        val command = create(assignee, TaskOptions(startsAt = now.get()), dueAt = now.get() + 1000)
        val failing = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) error("task extension rollback") }))
        assertFailsWith<IllegalStateException> { failing.modify(owner, command) }
        assertFailsWith<TaskNotFoundException> { service().details(owner, command.taskId) }
        assertNull(ctx.pgUnitOfWork.read { repository.findReceipt(transaction, owner, command.operationId) })
        create(owner, command)
        assertFailsWith<IllegalStateException> { failing.remindStarted() }
        assertNull(service().details(assignee, command.taskId).startRemindedAt)
        assertTrue(events(assignee, NotifyType.TASK_STARTED).isEmpty())
        val dispatcher = RpcDispatcher(ctx.rpcStubRegistry)
        suspend fun invoke(version: ProtocolVersion) = dispatcher.dispatch(assignee, "task-details-device", 1, "task-details-session",
            InvokePayload(1, TaskRpcContract.SERVICE, TaskRpcContract.M_DETAILS, TaskRpcContract.encodeDetails(command.taskId)), version)
        assertEquals(0, invoke(ProtocolVersion(0, 3)).status)
        assertNotEquals(0, invoke(ProtocolVersion(0, 2)).status)
    }

    @Test fun `fixed weekly template retains attachments after a single occurrence removes its materials`() = runTest {
        val owner = user(); val assignee = user()
        now.set(Instant.parse("2026-09-14T00:00:00Z").toEpochMilli())
        val path = ctx.fileStore.store(owner, "weekly.txt", "text/plain", "周模板".byteInputStream())
        val options = TaskOptions(attachments = listOf(assertNotNull(ctx.fileStore.getAttachment(path))))
        val first = create(owner, create(assignee, options, recurrence = TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 1, "2026-09-14", "09:00", "17:00", "Asia/Shanghai")))
        create(owner, edit(first, options.copy(attachments = emptyList())))
        assertFalse(ctx.attachmentAccess.canRead(assignee, path))
        ctx.cleanupExpiredAttachments(System.currentTimeMillis() + 40L * 24 * 60 * 60 * 1000)
        assertNotNull(ctx.fileStore.getAttachment(path))
        now.set(Instant.parse("2026-09-21T02:00:00Z").toEpochMilli()); service().generateRecurring()
        val second = service().query(assignee, TaskQuery(), null, 20).items.first { it.task.taskId != first.task.taskId }
        assertEquals(path, second.options.attachments.single().path)
        assertTrue(ctx.attachmentAccess.canRead(assignee, path))
    }
}
