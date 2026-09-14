package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.model.TaskWeeklyRule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Calendar arithmetic only; persistence and delivery stay in TaskService's existing maintenance loop. */
internal class TaskWeeklySchedule(private val rule: TaskWeeklyRule) {
    private val zone = ZoneId.of(rule.timeZone)
    private val start = LocalTime.parse(rule.startLocalTime)
    private val due = LocalTime.parse(rule.dueLocalTime)
    private val weekday = DayOfWeek.of(rule.weekday)
    data class Occurrence(val date: String, val startsAt: Long, val dueAt: Long)

    fun on(date: LocalDate): Occurrence {
        val starts = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        val localDue = date.atTime(due).atZone(zone).toInstant().toEpochMilli()
        // Zone gaps move local times forward. Preserve a positive interval if that crosses due time.
        val ends = if (localDue > starts) localDue else starts + Duration.between(start, due).toMillis()
        return Occurrence(date.toString(), starts, ends)
    }
    fun next(now: Long): Occurrence {
        val local = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val candidate = local.with(TemporalAdjusters.nextOrSame(weekday))
        return on(candidate).takeIf { it.startsAt >= now } ?: on(candidate.plusWeeks(1))
    }
    fun latest(now: Long): Occurrence {
        val local = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val candidate = local.with(TemporalAdjusters.previousOrSame(weekday))
        return on(candidate).takeIf { it.startsAt <= now } ?: on(candidate.minusWeeks(1))
    }
    fun following(occurrence: Occurrence): Occurrence = on(LocalDate.parse(occurrence.date).plusWeeks(1))
}
