package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.model.TaskRecurrenceRule
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskRecurrenceScheduleTest {
    @Test fun `calendar weeks retain local times across DST and only choose one missed date`() {
        val schedule = TaskRecurrenceSchedule(TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 1,
            "2026-03-01", "09:00", "17:00", "America/New_York"))
        val before = schedule.next(time("2026-03-01T00:00:00Z"))
        val after = schedule.following(before)
        assertEquals("2026-03-08", after.date)
        assertEquals(167L * 60 * 60 * 1000, after.startsAt - before.startsAt)
        assertEquals("2026-03-29", schedule.latest(time("2026-03-30T00:00:00Z"))?.date)
        val gap = TaskRecurrenceSchedule(TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 1,
            "2026-03-08", "02:30", "03:00", "America/New_York")).next(time("2026-03-08T00:00:00Z"))
        assertEquals(time("2026-03-08T07:30:00Z"), gap.startsAt)
        assertEquals(30 * 60 * 1000L, gap.dueAt - gap.startsAt)
        val overlap = TaskRecurrenceSchedule(TaskRecurrenceRule(TaskRecurrenceRule.WEEKLY, 1,
            "2026-11-01", "01:30", "02:30", "America/New_York")).next(time("2026-11-01T00:00:00Z"))
        assertEquals(time("2026-11-01T05:30:00Z"), overlap.startsAt)
        assertEquals(time("2026-11-01T07:30:00Z"), overlap.dueAt)
        assertFails { TaskRecurrenceSchedule(rule(TaskRecurrenceRule.WEEKLY, 1, "2026-03-01").copy(timeZone = "not-a-zone")) }
    }

    @Test fun `two week anchor crosses years without drifting to an intervening matching weekday`() {
        val schedule = TaskRecurrenceSchedule(rule(TaskRecurrenceRule.WEEKLY, 2, "2025-12-29"))
        assertNull(schedule.latest(time("2025-12-28T12:00:00Z")))
        assertNull(schedule.latest(time("2025-12-29T08:59:59Z")))
        val first = schedule.next(time("2025-01-01T00:00:00Z"))
        assertEquals("2025-12-29", first.date)
        assertEquals(first, schedule.latest(first.startsAt))
        assertEquals(first, schedule.next(first.startsAt))
        assertEquals("2026-01-12", schedule.following(first).date)
        assertEquals("2025-12-29", schedule.latest(time("2026-01-05T12:00:00Z"))?.date)
        assertEquals("2026-01-12", schedule.next(time("2026-01-05T12:00:00Z")).date)
        assertEquals("2026-02-09", schedule.latest(time("2026-02-20T12:00:00Z"))?.date)
    }

    @Test fun `monthly 31 clamps only the missing month and returns to its original day`() {
        val schedule = TaskRecurrenceSchedule(rule(TaskRecurrenceRule.MONTHLY, 1, "2025-01-31"))
        assertNull(schedule.latest(time("2025-01-30T12:00:00Z")))
        var occurrence = schedule.next(time("2025-01-01T00:00:00Z"))
        val dates = mutableListOf(occurrence.date)
        repeat(3) { occurrence = schedule.following(occurrence); dates += occurrence.date }
        assertEquals(listOf("2025-01-31", "2025-02-28", "2025-03-31", "2025-04-30"), dates)
        assertEquals("2025-02-28", schedule.latest(time("2025-03-30T12:00:00Z"))?.date)
        assertEquals("2025-03-31", schedule.next(time("2025-03-30T12:00:00Z")).date)
        assertEquals("2025-01-31", schedule.latest(time("2025-02-28T08:00:00Z"))?.date)
    }

    @Test fun `month intervals preserve their original month phase and leap day anchor`() {
        val quarterly = TaskRecurrenceSchedule(rule(TaskRecurrenceRule.MONTHLY, 3, "2024-01-31"))
        val first = quarterly.next(time("2024-01-01T00:00:00Z"))
        val april = quarterly.following(first)
        assertEquals("2024-04-30", april.date)
        assertEquals("2024-07-31", quarterly.following(april).date)
        assertEquals("2024-10-31", quarterly.latest(time("2024-12-31T12:00:00Z"))?.date)
        assertEquals("2025-01-31", quarterly.next(time("2024-12-31T12:00:00Z")).date)

        val monthly = TaskRecurrenceSchedule(rule(TaskRecurrenceRule.MONTHLY, 1, "2024-01-31"))
        assertEquals("2024-02-29", monthly.next(time("2024-02-01T00:00:00Z")).date)
        val yearly = TaskRecurrenceSchedule(rule(TaskRecurrenceRule.MONTHLY, 12, "2024-02-29"))
        assertEquals("2025-02-28", yearly.next(time("2025-01-01T00:00:00Z")).date)
        assertEquals("2028-02-29", yearly.next(time("2028-01-01T00:00:00Z")).date)
        assertNull(yearly.latest(time("2024-02-01T00:00:00Z")))
    }

    private fun rule(frequency: Int, interval: Int, firstDate: String) =
        TaskRecurrenceRule(frequency, interval, firstDate, "09:00", "17:00", "UTC")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
}
