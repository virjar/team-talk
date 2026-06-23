package com.virjar.tk.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class FirstDisplayCharTest {

    @Test
    fun null_or_blank_returns_question_mark() {
        assertEquals("?", firstDisplayChar(null))
        assertEquals("?", firstDisplayChar(""))
        assertEquals("?", firstDisplayChar("   "))
    }

    @Test
    fun English_name_returns_first_letter() {
        assertEquals("A", firstDisplayChar("Alice"))
        assertEquals("t", firstDisplayChar("test"))
    }

    @Test
    fun Chinese_name_returns_first_character() {
        assertEquals("阿", firstDisplayChar("阿迪"))
        assertEquals("张", firstDisplayChar("张三"))
    }

    @Test
    fun emoji_prefix_is_skipped() {
        assertEquals("阿", firstDisplayChar("🤔阿迪"))
        assertEquals("A", firstDisplayChar("🎉Alice"))
    }

    @Test
    fun all_emoji_returns_question_mark() {
        assertEquals("?", firstDisplayChar("🎉🎊"))
        assertEquals("?", firstDisplayChar("🤔"))
    }

    @Test
    fun number_prefix_returns_first_digit() {
        assertEquals("1", firstDisplayChar("123"))
    }
}
