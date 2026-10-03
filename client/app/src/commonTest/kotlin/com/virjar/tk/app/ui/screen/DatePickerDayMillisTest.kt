package com.virjar.tk.app.ui.screen

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** DatePicker 毫秒 ↔ LocalDate 换算语义：必须是「UTC 日零点」，与设备时区无关。 */
class DatePickerDayMillisTest {

    @Test
    fun 纪元锚点与相邻日() {
        assertEquals(0L, datePickerDayMillis(LocalDate(1970, 1, 1)))
        assertEquals(86_400_000L, datePickerDayMillis(LocalDate(1970, 1, 2)))
        assertEquals(-86_400_000L, datePickerDayMillis(LocalDate(1969, 12, 31)))
    }

    @Test
    fun 往返保持同一天() {
        for (date in listOf(LocalDate(2026, 9, 8), LocalDate(2024, 2, 29), LocalDate(2000, 3, 1))) {
            assertEquals(date, localDateFromDatePickerDayMillis(datePickerDayMillis(date)))
        }
    }
}
