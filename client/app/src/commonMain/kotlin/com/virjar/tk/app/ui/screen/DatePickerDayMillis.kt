package com.virjar.tk.app.ui.screen

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Material3 DatePicker 的选中/初始值是「UTC 日零点」的 epoch 毫秒，与设备时区无关。
 * LocalDate 与该毫秒值的换算集中在此，调用点不得自算（时区偏移、epochDays 手算都
 * 容易长出第二套等价实现）。
 */
internal fun datePickerDayMillis(date: LocalDate): Long =
    date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun localDateFromDatePickerDayMillis(millis: Long): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
