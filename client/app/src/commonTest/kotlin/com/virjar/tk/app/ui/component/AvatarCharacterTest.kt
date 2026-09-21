package com.virjar.tk.app.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarCharacterTest {
    @Test
    fun skipsEmojiAndKeepsSupplementaryLettersWhole() {
        assertEquals("张", firstDisplayChar("😀张三"))
        assertEquals("𠀀", firstDisplayChar("👩‍💻𠀀同事"))
        assertEquals("7", firstDisplayChar("\u0301 7号"))
        assertEquals("?", firstDisplayChar("🎉😀"))
        assertEquals("A", firstDisplayChar("\uD800A"))
    }
}
