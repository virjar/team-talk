package com.virjar.tk.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTypeTest {

    @Test
    fun fromCode_returns_PERSONAL_for_1() {
        assertEquals(ChatType.PERSONAL, ChatType.fromCode(1))
    }

    @Test
    fun fromCode_returns_GROUP_for_2() {
        assertEquals(ChatType.GROUP, ChatType.fromCode(2))
    }

    @Test
    fun fromCode_falls_back_to_PERSONAL_for_unknown_code() {
        assertEquals(ChatType.PERSONAL, ChatType.fromCode(0))
        assertEquals(ChatType.PERSONAL, ChatType.fromCode(99))
        assertEquals(ChatType.PERSONAL, ChatType.fromCode(-1))
    }

    @Test
    fun code_values_are_stable_for_protocol() {
        assertEquals(1, ChatType.PERSONAL.code)
        assertEquals(2, ChatType.GROUP.code)
    }
}
