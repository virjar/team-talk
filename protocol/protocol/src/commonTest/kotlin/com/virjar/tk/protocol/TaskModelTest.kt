package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.body.MessageBodyRegistry
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.model.*
import kotlin.test.*

class TaskModelTest {
    @Test
    fun detailsPagesKeepReleasedTaskBoundariesAndRoundTripAllExtensionFacts() {
        val draft = TaskDraft("每周报告", "# 报告\n- 内容", "assignee", dueAt = 500)
        val task = WorkTask(ID, "creator", "assignee", draft.title, draft.description, TaskPolicy.TODO, 0, "", 500, null, 1, 1, 1)
        val options = TaskOptions(TaskOptions.MARKDOWN, 100, documentRefs = listOf(com.virjar.tk.protocol.body.OfficeRefBody(1, ID, ID, "材料")),
            attachments = listOf(Attachment("files/report.txt", "报告.txt", "text/plain", 20)))
        val rule = TaskWeeklyRule(1, "09:00", "17:00", "Asia/Shanghai")
        val series = TaskSeries(ID, "creator", 1, true, rule, 1000)
        val details = TaskDetails(task, options, TaskMetrics(400, 1, 50, 100, null, true), 101, series, "2026-09-14")
        val page = TaskQueryPage(listOf(details, details.copy(task = task.copy(taskId = SECOND_ID))), "next", TaskSummary(3, 3, 1, 500, 0, null))
        assertEquals(page, ProtoCodec.decode(TaskQueryPage, ProtoCodec.encode(page)))
        val command = TaskDetailsCommand(ID, 10, ID, 0, TaskDetailsCommand.CREATE, draft, options, weeklyRule = rule)
        assertEquals(command, ProtoCodec.decode(TaskDetailsCommand, ProtoCodec.encode(command)))
        val deferred = TaskDetailsCommand(ID, 10, ID, 1, TaskDetailsCommand.DEFER, deferDueAt = 900, reason = "等待审核")
        assertEquals(deferred, ProtoCodec.decode(TaskDetailsCommand, ProtoCodec.encode(deferred)))
        val control = TaskSeriesCommand(ID, 10, ID, 1, false)
        assertEquals(control, ProtoCodec.decode(TaskSeriesCommand, ProtoCodec.encode(control)))
        val query = TaskQuery(TaskQuery.GROUP, ID, true, true)
        assertEquals(query, ProtoCodec.decode(TaskQuery, ProtoCodec.encode(query)))
        val started = TaskStartedPayload(ID, 1, 100)
        assertEquals(started, ProtoCodec.decode(TaskStartedPayload, ProtoCodec.encode(started)))
        // Start reminders precede due and must never be folded into the released remindedAt field.
        assertFailsWith<IllegalArgumentException> { task.copy(remindedAt = 100) }
        assertEquals(TaskOptions(), ProtoCodec.decode(TaskOptions, ProtoCodec.encode(TaskOptions())))
        assertFailsWith<IllegalArgumentException> { TaskWeeklyRule(1, "18:00", "09:00", "Asia/Shanghai") }
        assertFailsWith<IllegalArgumentException> { options.copy(attachments = List(21) { options.attachments.single() }) }
    }
    @Test
    fun taskCommandAuditAndReminderRoundTrip() {
        val draft = TaskDraft("整理计划", "第一行\n第二行", "assignee", TaskPolicy.CONTEXT_GROUP, ID, 500)
        val task = WorkTask(ID, "creator", draft.assigneeUid, draft.title, draft.description,
            TaskPolicy.IN_PROGRESS, draft.contextKind, draft.contextId, draft.dueAt, 700, 3, 1, 600)
        assertEquals(task, ProtoCodec.decode(WorkTask, ProtoCodec.encode(task)))
        for (command in listOf(TaskCommand(ID, 10, ID, 0, TaskCommand.CREATE, draft),
            TaskCommand(ID, 10, ID, 3, TaskCommand.EDIT, draft.copy(dueAt = null)),
            TaskCommand(ID, 10, ID, 3, TaskCommand.STATUS, status = TaskPolicy.DONE))) {
            assertEquals(command, ProtoCodec.decode(TaskCommand, ProtoCodec.encode(command)))
        }
        for (result in listOf(TaskCommandResult(task), TaskCommandResult(null))) {
            assertEquals(result, ProtoCodec.decode(TaskCommandResult, ProtoCodec.encode(result)))
        }
        val audit = TaskAudit(ID, 3, "creator", TaskAudit.STATUS_CHANGED, 600, TaskPolicy.TODO,
            TaskPolicy.IN_PROGRESS, "old", "assignee")
        assertEquals(TaskAuditPage(listOf(audit), "next"), ProtoCodec.decode(TaskAuditPage, ProtoCodec.encode(TaskAuditPage(listOf(audit), "next"))))
        assertEquals(TaskPage(listOf(task), null), ProtoCodec.decode(TaskPage, ProtoCodec.encode(TaskPage(listOf(task), null))))
        val changed = TaskChangedPayload(ID, 3, TaskChangedPayload.REVOKED)
        val due = TaskDuePayload(ID, 3, 700)
        assertEquals(changed, ProtoCodec.decode(TaskChangedPayload, ProtoCodec.encode(changed)))
        assertEquals(due, ProtoCodec.decode(TaskDuePayload, ProtoCodec.encode(due)))
    }

    @Test
    fun taskReferenceUsesNewMessageTypeWithoutChangingOfficeReferences() {
        val body = TaskRefBody(ID, "整理计划", "待办")
        assertEquals(MessageType.TASK_REF, MessageBodyPolicy.typeOf(body))
        assertEquals(18, MessageType.TASK_REF.code)
        assertEquals(body, MessageBodyRegistry.decode(MessageType.TASK_REF, PacketBuffer(ProtoCodec.encode(body))))
        assertEquals(17, MessageType.OFFICE_REF.code)
        assertFailsWith<IllegalArgumentException> { com.virjar.tk.protocol.body.OfficeRefBody(3, ID, ID, "无效") }
    }

    @Test
    fun invalidCommandsAndUnboundedPagesAreRejected() {
        val draft = TaskDraft("边界", "🙂".repeat(5_000), "assignee")
        assertEquals(draft, ProtoCodec.decode(TaskDraft, ProtoCodec.encode(draft)))
        assertFailsWith<IllegalArgumentException> { draft.copy(description = draft.description + "x") }
        assertFailsWith<IllegalArgumentException> { TaskCommand(ID, 0, ID, 1, TaskCommand.CREATE, draft) }
        assertFailsWith<IllegalArgumentException> { TaskCommand(ID, 0, ID, 1, TaskCommand.STATUS, draft, TaskPolicy.DONE) }
        assertFailsWith<IllegalArgumentException> { draft.copy(contextKind = TaskPolicy.CONTEXT_GROUP) }
        assertFailsWith<IllegalArgumentException> { TaskPage(emptyList(), "x".repeat(513)) }
        val bytes = ProtoCodec.encode(TaskCommand(ID, 0, ID, 0, TaskCommand.CREATE, draft))
        assertFails { ProtoCodec.decode(TaskCommand, bytes.copyOf(bytes.size - 1)) }
        assertFails { ProtoCodec.decode(TaskCommand, bytes + byteArrayOf(0)) }
    }
    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000001"
        private const val SECOND_ID = "00000000-0000-4000-8000-000000000002"
    }
}
