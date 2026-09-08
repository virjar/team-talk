package com.virjar.tk.app.navigation.feature.task

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 编辑器显示本地日期/时间；协议只接收明确的 UTC 时间点。 */
internal data class TaskDeadlineInput(val date: String = "", val time: String = "") {
    fun epochMillis(zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (date.isBlank() && time.isBlank()) return null
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "请填写日期，例如 2026-09-08" }
        require(time.matches(Regex("\\d{2}:\\d{2}"))) { "请填写时间，例如 18:00" }
        val local = try {
            LocalDateTime.of(LocalDate.parse(date), LocalTime.of(time.take(2).toInt(), time.takeLast(2).toInt()))
        } catch (_: Exception) {
            throw IllegalArgumentException("日期或时间无效，请检查后重试")
        }
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "该本地时间因夏令时调整不存在，请选择其他时间" }
        // 夏令时回拨的重复时刻使用第一次出现的偏移，与 Java 的本地时间默认解释一致。
        return local.toInstant(offsets.first()).toEpochMilli().also {
            require(it > 0L) { "截止时间必须晚于 1970 年 1 月 1 日" }
        }
    }

    companion object {
        fun from(epochMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): TaskDeadlineInput {
            if (epochMillis == null) return TaskDeadlineInput()
            val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
            return TaskDeadlineInput(local.toLocalDate().toString(), local.toLocalTime().format(TIME_FORMAT))
        }
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

internal fun taskDateTimeLabel(epochMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    if (epochMillis == null) "无截止时间" else TaskDeadlineInput.from(epochMillis, zone).let { "${it.date} ${it.time}" }
