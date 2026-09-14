package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.model.TaskWeeklyRule
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskWeeklyScheduleTest {
    @Test fun `calendar weeks retain local times across DST and only choose one missed date`() {
        val schedule = TaskWeeklySchedule(TaskWeeklyRule(7, "09:00", "17:00", "America/New_York"))
        val before = schedule.on(LocalDate.parse("2026-03-01"))
        val after = schedule.following(before)
        assertEquals("2026-03-08", after.date)
        assertEquals(167L * 60 * 60 * 1000, after.startsAt - before.startsAt)
        assertEquals("2026-03-29", schedule.latest(Instant.parse("2026-03-30T00:00:00Z").toEpochMilli()).date)
        val gap = TaskWeeklySchedule(TaskWeeklyRule(7, "02:30", "03:00", "America/New_York")).on(LocalDate.parse("2026-03-08"))
        assertTrue(gap.dueAt > gap.startsAt)
        assertFails { TaskWeeklySchedule(TaskWeeklyRule(1, "09:00", "17:00", "not-a-zone")) }
    }
}
