package com.virjar.tk

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowChromeTest {

    @Test
    fun `title bar double click toggles floating and maximized`() {
        assertEquals(
            WindowPlacement.Maximized,
            nextTitleBarPlacement(WindowPlacement.Floating),
        )
        assertEquals(
            WindowPlacement.Floating,
            nextTitleBarPlacement(WindowPlacement.Maximized),
        )
    }

    @Test
    fun `title bar double click never changes fullscreen`() {
        assertEquals(
            WindowPlacement.Fullscreen,
            nextTitleBarPlacement(WindowPlacement.Fullscreen),
        )
    }
}
