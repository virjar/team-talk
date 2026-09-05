package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.TelemetryHighlightSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientTelemetryHighlightTest {
    @Test
    fun `literal em text cannot forge a highlight span`() {
        val parsed = checkNotNull(
            parseTelemetryHighlightFragment(
                "literal <em> stays plain \u0001matched text\u0002",
                maxCharacters = 200,
            ),
        )

        assertEquals("literal <em> stays plain matched text", parsed.text)
        assertEquals(
            listOf(TelemetryHighlightSpan(start = 25, end = 37)),
            parsed.spans,
        )
    }

    @Test
    fun `malformed control markers fail closed instead of becoming markup`() {
        assertNull(parseTelemetryHighlightFragment("text \u0001without close", 200))
        assertNull(parseTelemetryHighlightFragment("text \u0002without open", 200))
        assertNull(parseTelemetryHighlightFragment("\u0001nested \u0001text\u0002", 200))
    }
}
