package com.virjar.tk.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PrimaryContextGestureActionsTest {

    @Test
    fun `single tap activates item without opening context menu`() {
        var clickCount = 0
        var longPressCount = 0
        val actions = PrimaryContextGestureActions(
            onClick = { clickCount += 1 },
            onLongPress = { longPressCount += 1 },
        )

        actions.click()

        assertEquals(1, clickCount)
        assertEquals(0, longPressCount)
    }

    @Test
    fun `long press opens context menu without activating item`() {
        var clickCount = 0
        var longPressCount = 0
        val actions = PrimaryContextGestureActions(
            onClick = { clickCount += 1 },
            onLongPress = { longPressCount += 1 },
        )

        actions.longPress()

        assertEquals(0, clickCount)
        assertEquals(1, longPressCount)
    }
}
