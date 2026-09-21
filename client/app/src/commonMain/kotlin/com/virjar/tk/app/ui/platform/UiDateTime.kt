package com.virjar.tk.app.ui.platform

import com.virjar.tk.shared.platform.platformCurrentTimeMillis
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

internal fun localUiDateTime(epochMillis: Long = platformCurrentTimeMillis()): LocalDateTime =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())

internal fun LocalDateTime.hourMinute(): String = "${hour.twoDigits()}:${minute.twoDigits()}"
internal fun LocalDateTime.monthDay(separator: Char = '-'): String = "${month.number.twoDigits()}$separator${day.twoDigits()}"
private fun Int.twoDigits(): String = toString().padStart(2, '0')

internal fun formatUiDateTime(epochMillis: Long): String = localUiDateTime(epochMillis).let {
    "${it.date} ${it.hourMinute()}"
}
internal fun formatUiMonthDayTime(epochMillis: Long): String = localUiDateTime(epochMillis).let {
    "${it.monthDay()} ${it.hourMinute()}"
}
