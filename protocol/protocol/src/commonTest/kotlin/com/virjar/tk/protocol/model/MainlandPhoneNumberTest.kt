package com.virjar.tk.protocol.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MainlandPhoneNumberTest {
    @Test
    fun `bare mainland entry and historical prefix describe the same phone`() {
        assertEquals("13800001234", MainlandPhoneNumber.normalize("13800001234"))
        assertEquals("13800001234", MainlandPhoneNumber.normalize(" +8613800001234 "))
        assertEquals("13800001234", MainlandPhoneNumber.display("+8613800001234"))
        assertNull(MainlandPhoneNumber.normalize(" "))
        listOf("12345", "138000012345", "12800001234", "+113800001234").forEach { invalid ->
            assertEquals(MainlandPhoneNumber.FORMAT_MESSAGE,
                assertFailsWith<IllegalArgumentException> { MainlandPhoneNumber.normalize(invalid) }.message)
        }
    }
}
