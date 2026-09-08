package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.app.ui.component.MessagePreview
import com.virjar.tk.app.ui.screen.taskStatusActions
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.model.TaskPolicy
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
