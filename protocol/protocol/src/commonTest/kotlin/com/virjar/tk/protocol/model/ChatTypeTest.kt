package com.virjar.tk.protocol.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatTypeTest {

    @Test
    fun fromCode_rejects_unknown_code() {
        listOf(0, 99, -1).forEach { code ->
            assertFailsWith<IllegalArgumentException> { ChatType.fromCode(code) }
        }
    }

    @Test
    fun code_values_are_stable_for_protocol() {
        assertEquals(1, ChatType.PERSONAL.code)
        assertEquals(2, ChatType.GROUP.code)
        assertEquals(3, ChatType.SAVED.code)
    }
}
