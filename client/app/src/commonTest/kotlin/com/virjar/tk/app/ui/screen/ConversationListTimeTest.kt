package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.ui.platform.hourMinute
import com.virjar.tk.app.ui.platform.localUiDateTime
import com.virjar.tk.app.ui.platform.monthDay
import com.virjar.tk.shared.platform.platformCurrentTimeMillis
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话列表时间格式契约：今天 HH:mm；前两天「昨天」「前天」；7 天内「周X」；
 * 当年更早 MM/dd；跨年 yyyy/MM/dd。
 */
class ConversationListTimeTest {
    /** 按本地日历回退 N 天的同一时刻，避免直接减毫秒在午夜附近漂移到错误的日期。 */
    private fun localMillisDaysBefore(days: Int): Long {
        val now = localUiDateTime()
        return now.date.minus(days, DateTimeUnit.DAY).atTime(now.time)
            .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    }

    @Test
    fun `today renders as hour-minute`() {
        val now = platformCurrentTimeMillis()
        assertEquals(localUiDateTime(now).hourMinute(), formatListTime(now))
    }

    @Test
    fun `first two previous days render as yesterday and day-before`() {
        assertEquals("昨天", formatListTime(localMillisDaysBefore(1)))
        assertEquals("前天", formatListTime(localMillisDaysBefore(2)))
    }

    @Test
    fun `within a week renders weekday and older renders month-day`() {
        val weekdayMillis = localMillisDaysBefore(3)
        val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[
            localUiDateTime(weekdayMillis).dayOfWeek.isoDayNumber - 1,
        ]
        assertEquals(weekday, formatListTime(weekdayMillis))

        val olderMillis = localMillisDaysBefore(8)
        assertEquals(localUiDateTime(olderMillis).monthDay('/'), formatListTime(olderMillis))
    }

    @Test
    fun `cross-year renders full date`() {
        val millis = LocalDateTime(2000, 1, 15, 8, 0)
            .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        assertEquals("2000/01/15", formatListTime(millis))
    }
}
