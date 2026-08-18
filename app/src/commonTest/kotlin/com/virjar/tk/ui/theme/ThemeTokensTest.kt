package com.virjar.tk.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeTokensTest {

    @Test
    fun `outgoing bubbles use semantic surfaces instead of brand blue`() {
        assertEquals(Color(0xFFE8F0FF), LightTkColors.bubbleOutgoing)
        assertEquals(Color(0xFF1D2129), LightTkColors.bubbleOutgoingContent)
        assertEquals(Color(0xFF203A63), DarkTkColors.bubbleOutgoing)
        assertEquals(Color(0xFFEDF3FF), DarkTkColors.bubbleOutgoingContent)
        assertNotEquals(Color(0xFF3370FF), LightTkColors.bubbleOutgoing)
    }

    @Test
    fun `outgoing bubble text passes WCAG AA contrast`() {
        assertTrue(contrastRatio(LightTkColors.bubbleOutgoing, LightTkColors.bubbleOutgoingContent) >= 4.5)
        assertTrue(contrastRatio(DarkTkColors.bubbleOutgoing, DarkTkColors.bubbleOutgoingContent) >= 4.5)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val (lighter, darker) = listOf(relativeLuminance(first), relativeLuminance(second))
            .sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(component: Float): Double {
        val value = component.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
