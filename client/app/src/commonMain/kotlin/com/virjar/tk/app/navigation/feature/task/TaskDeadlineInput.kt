package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.app.ui.platform.hourMinute
import kotlin.time.Instant
import kotlinx.datetime.*

/** 编辑器显示本地日期/时间；协议只接收明确的 UTC 时间点。 */
@kotlinx.serialization.Serializable
internal data class TaskDeadlineInput(val date: String = "", val time: String = "") {
    fun epochMillis(zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
        if (date.isBlank() && time.isBlank()) return null
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "请填写日期，例如 2026-09-08" }
        require(time.matches(Regex("\\d{2}:\\d{2}"))) { "请填写时间，例如 18:00" }
        val local = try {
            LocalDateTime(LocalDate.parse(date), LocalTime(time.take(2).toInt(), time.takeLast(2).toInt()))
        } catch (_: Exception) {
            throw IllegalArgumentException("日期或时间无效，请检查后重试")
        }
        // kotlinx-datetime resolves an overlap to its first instant. Reject gaps by checking
        // the resolved local time rather than silently moving a user's deadline forward.
        val instant = local.toInstant(zone)
        require(instant.toLocalDateTime(zone) == local) { "该本地时间因夏令时调整不存在，请选择其他时间" }
        return instant.toEpochMilliseconds().also {
            require(it > 0L) { "截止时间必须晚于 1970 年 1 月 1 日" }
        }
    }

    companion object {
        fun from(epochMillis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): TaskDeadlineInput {
            if (epochMillis == null) return TaskDeadlineInput()
            val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
            return TaskDeadlineInput(local.date.toString(), local.hourMinute())
        }
    }
}

internal fun taskDateTimeLabel(epochMillis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    if (epochMillis == null) "无截止时间" else TaskDeadlineInput.from(epochMillis, zone).let { "${it.date} ${it.time}" }
