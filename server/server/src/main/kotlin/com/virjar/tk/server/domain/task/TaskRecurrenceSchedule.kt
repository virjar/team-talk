package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.model.TaskRecurrenceRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 只负责按固定锚点计算日历；持久期次、启停和提醒仍由 TaskService 的维护事务拥有。 */
internal class TaskRecurrenceSchedule(private val rule: TaskRecurrenceRule) {
    private val zone = ZoneId.of(rule.timeZone)
    private val start = LocalTime.parse(rule.startLocalTime)
    private val due = LocalTime.parse(rule.dueLocalTime)
    private val firstDate = LocalDate.parse(rule.firstDate)
    data class Occurrence(val date: String, val startsAt: Long, val dueAt: Long)

    private fun on(date: LocalDate): Occurrence {
        val starts = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        val localDue = date.atTime(due).atZone(zone).toInstant().toEpochMilli()
        // 夏令时缺口将本地时间前移；若因此越过截止时间，保留原本的正时长。
        val ends = if (localDue > starts) localDue else starts + Duration.between(start, due).toMillis()
        return Occurrence(date.toString(), starts, ends)
    }
    fun next(now: Long): Occurrence {
        val local = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val index = indexAt(local).coerceAtLeast(0)
        return at(index).takeIf { it.startsAt >= now } ?: at(index + 1)
    }
    fun latest(now: Long): Occurrence? {
        val local = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val index = indexAt(local)
        if (index < 0) return null
        return at(index).takeIf { it.startsAt <= now } ?: if (index == 0L) null else at(index - 1)
    }
    fun following(occurrence: Occurrence): Occurrence {
        val date = LocalDate.parse(occurrence.date)
        val index = indexAt(date)
        require(index >= 0 && dateAt(index) == date) { "期次日期不属于此周期" }
        return at(index + 1)
    }

    private fun indexAt(date: LocalDate): Long = when (rule.frequency) {
        TaskRecurrenceRule.WEEKLY -> Math.floorDiv(ChronoUnit.DAYS.between(firstDate, date), 7L * rule.interval)
        else -> Math.floorDiv(ChronoUnit.MONTHS.between(YearMonth.from(firstDate), YearMonth.from(date)), rule.interval.toLong())
    }

    private fun at(index: Long): Occurrence = on(dateAt(index))

    // 每一期都从首次日期计算；31 日在二月落到月末后，三月仍回到 31 日。
    private fun dateAt(index: Long): LocalDate = when (rule.frequency) {
        TaskRecurrenceRule.WEEKLY -> firstDate.plusWeeks(index * rule.interval)
        else -> firstDate.plusMonths(index * rule.interval)
    }
}
