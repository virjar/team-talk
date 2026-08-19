package com.virjar.tk

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacFullScreenContentSyncTest {

    @Test
    fun `native fullscreen with stale content requires synchronization`() {
        assertTrue(
            needsMacFullScreenContentSync(
                placement = WindowPlacement.Fullscreen,
                contentWidth = 1000,
                contentHeight = 720,
                screenWidth = 1792,
                screenHeight = 1120,
            ),
        )
    }

    @Test
    fun `matching fullscreen and non-fullscreen content are left alone`() {
        assertFalse(
            needsMacFullScreenContentSync(
                placement = WindowPlacement.Fullscreen,
                contentWidth = 1792,
                contentHeight = 1120,
                screenWidth = 1792,
                screenHeight = 1120,
            ),
        )
        assertFalse(
            needsMacFullScreenContentSync(
                placement = WindowPlacement.Maximized,
                contentWidth = 1000,
                contentHeight = 720,
                screenWidth = 1792,
                screenHeight = 1120,
            ),
        )
    }
}
