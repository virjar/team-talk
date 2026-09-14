package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.app.ui.component.MessagePreview
import com.virjar.tk.app.ui.screen.taskStatusActions
import com.virjar.tk.app.ui.screen.taskDeferralDueAt
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.TaskDetails
import com.virjar.tk.protocol.model.TaskOptions
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.TaskRecurrenceRule
import com.virjar.tk.protocol.model.WorkTask
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class TaskEditorTest {
    @Test
    fun `deadline uses local calendar time and rejects missing or nonexistent times`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val input = TaskDeadlineInput("2026-09-08", "18:30")
        assertEquals(Instant.parse("2026-09-08T10:30:00Z").toEpochMilli(), input.epochMillis(zone))
        assertEquals(input, TaskDeadlineInput.from(input.epochMillis(zone), zone))
        assertNull(TaskDeadlineInput().epochMillis(zone))
        assertFailsWith<IllegalArgumentException> { TaskDeadlineInput("2026-02-30", "12:00").epochMillis(zone) }
        assertFailsWith<IllegalArgumentException> { TaskDeadlineInput("2026-09-08", "").epochMillis(zone) }
        assertFailsWith<IllegalArgumentException> {
            TaskDeadlineInput("2026-03-08", "02:30").epochMillis(ZoneId.of("America/New_York"))
        }
    }

    @Test
    fun `editing title preserves exact due instant and clearing deadline remains explicit`() {
        val original = task().copy(dueAt = 1_788_881_234_567L)
        val editor = TaskEditorState.from(original).copy(title = " 新标题 ")
        assertEquals(original.dueAt, editor.draft().dueAt)
        assertEquals("新标题", editor.draft().title)
        assertNull(editor.copy(deadline = TaskDeadlineInput()).draft().dueAt)
        assertFailsWith<IllegalArgumentException> { editor.copy(contextKind = TaskPolicy.CONTEXT_GROUP, contextId = "").draft() }
    }

    @Test
    fun `editing extended task preserves materials literal legacy text and exact start until explicitly changed`() {
        val original = task().copy(description = "*旧文字* [不是自动转换的链接](note)",
            dueAt = 1_788_885_234_567L, contextKind = TaskPolicy.CONTEXT_GROUP,
            contextId = "00000000-0000-4000-8000-000000000002")
        val options = TaskOptions(startsAt = 1_788_881_234_567L, shareToGroup = true,
            documentRefs = listOf(OfficeRefBody(OfficeRefBody.REF_TYPE_DOCUMENT, "space", "doc", "说明文档")),
            attachments = listOf(Attachment("files/task.txt", "材料.txt", "text/plain", 12)))
        val editor = TaskEditorState.from(TaskDetails(original, options)).copy(title = "新标题")
        assertEquals(original.description, editor.draft().description)
        assertEquals(original.dueAt, editor.draft().dueAt)
        assertEquals(options, editor.optionsForSave())
        assertEquals(TaskOptions.TEXT, TaskEditorState.from(original).options.descriptionFormat)
        assertEquals(TaskOptions.MARKDOWN, TaskEditorState.create("owner").options.descriptionFormat)
        assertEquals(editor.editorKey, editor.copy(description = "新正文").editorKey)
        assertNull(editor.copy(start = TaskDeadlineInput()).optionsForSave().startsAt)
        assertFalse(editor.copy(contextKind = TaskPolicy.CONTEXT_NONE, contextId = "").optionsForSave().shareToGroup)
        assertFailsWith<IllegalArgumentException> {
            editor.copy(start = TaskDeadlineInput.from(original.dueAt!! + 86_400_000)).optionsForSave()
        }
    }

    @Test
    fun `calendar create preserves cadence and anchor while occurrence edits never reschedule the series`() {
        val input = TaskRecurrenceInput(true, TaskRecurrenceRule.WEEKLY, "2", "2026-09-14", "09:00", "18:00", "Asia/Shanghai")
        val weekly = TaskEditorState.create("owner").copy(title = "每周统计", recurrenceInput = input,
            start = TaskDeadlineInput("2026-09-14", "09:00"), deadline = TaskDeadlineInput("2026-09-14", "18:00"))
        assertEquals(TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 2, "2026-09-14", "09:00", "18:00", "Asia/Shanghai"), weekly.recurrenceRule())
        assertNull(weekly.draft().dueAt)
        assertNull(weekly.optionsForSave().startsAt)
        val occurrence = TaskEditorState.from(task()).copy(recurrenceInput = input)
        assertNull(occurrence.recurrenceRule())
        assertFailsWith<IllegalArgumentException> { weekly.copy(recurrenceInput = input.copy(timeZone = "Unknown/Zone")).recurrenceRule() }
        assertFailsWith<IllegalArgumentException> { weekly.copy(recurrenceInput = input.copy(dueLocalTime = "09:00")).recurrenceRule() }
        assertFailsWith<IllegalArgumentException> { weekly.copy(recurrenceInput = input.copy(startLocalTime = "25:00")).recurrenceRule() }
        val monthly = input.copy(frequency = TaskRecurrenceRule.MONTHLY, interval = "1", firstDate = "2026-01-31").rule()
        assertEquals("2026-01-31", monthly.firstDate)
        assertEquals("每月 31 日", taskRecurrenceLabel(monthly))
        assertEquals("每 2 周的周一", taskRecurrenceLabel(input.rule()))
        assertFailsWith<IllegalArgumentException> { input.copy(firstDate = "2026-02-30").rule() }
        assertFailsWith<IllegalArgumentException> { input.copy(interval = "").rule() }
        assertFailsWith<IllegalArgumentException> { input.copy(interval = "0").rule() }
        assertFailsWith<IllegalArgumentException> { input.copy(interval = "13").rule() }
    }

    @Test
    fun `deferral form requires a reason and an explicit deadline after both current deadline and now`() {
        val input = TaskDeadlineInput("2030-01-02", "18:00")
        val due = requireNotNull(input.epochMillis())
        assertEquals(due, taskDeferralDueAt(input, "等待统计材料", due - 120_000, due - 60_000))
        assertFailsWith<IllegalArgumentException> { taskDeferralDueAt(input, "原因", null, due - 60_000) }
        assertFailsWith<IllegalArgumentException> { taskDeferralDueAt(input, "  ", due - 120_000, due - 60_000) }
        assertFailsWith<IllegalArgumentException> { taskDeferralDueAt(TaskDeadlineInput(), "原因", due - 120_000, due - 60_000) }
        assertFailsWith<IllegalArgumentException> { taskDeferralDueAt(input, "原因", due, due - 60_000) }
        assertFailsWith<IllegalArgumentException> { taskDeferralDueAt(input, "原因", due - 120_000, due) }
    }

    @Test
    fun `assignee cannot cancel or reverse creator cancellation and references have a readable preview`() {
        val task = task()
        assertEquals(setOf(TaskPolicy.IN_PROGRESS, TaskPolicy.DONE), taskStatusActions(task, "assignee").map { it.first }.toSet())
        assertEquals(setOf(TaskPolicy.IN_PROGRESS, TaskPolicy.DONE, TaskPolicy.CANCELLED), taskStatusActions(task, "creator").map { it.first }.toSet())
        assertTrue(taskStatusActions(task.copy(status = TaskPolicy.CANCELLED), "assignee").isEmpty())
        assertTrue(taskStatusActions(task, "other").isEmpty())
        assertEquals(setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS, TaskPolicy.DONE),
            taskStatusActions(task.copy(status = TaskPolicy.CANCELLED), "creator").map { it.first }.toSet())
        assertEquals("[任务] 准备报告", MessagePreview.previewBody(TaskRefBody(task.taskId, task.title)))
        assertFalse(taskIsOverdue(task.copy(status = TaskPolicy.DONE, dueAt = 10), 20))
        assertTrue(taskIsOverdue(task.copy(dueAt = 10), 20))
    }

    private fun task() = WorkTask("00000000-0000-4000-8000-000000000001", "creator", "assignee", "准备报告", "说明",
        TaskPolicy.TODO, TaskPolicy.CONTEXT_NONE, "", null, null, 1, 1, 1)
}
