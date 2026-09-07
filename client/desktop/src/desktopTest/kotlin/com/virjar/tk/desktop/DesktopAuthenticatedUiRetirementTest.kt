package com.virjar.tk.desktop

import com.virjar.tk.shared.client.SessionEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopAuthenticatedUiRetirementTest {
    @Test
    fun `failed draft writer remains observable after all other resources retire`() {
        val failure = IllegalStateException("Draft writer did not drain")
        val closed = mutableListOf<String>()
        val retirement = DesktopAuthenticatedUiRetirement(
            closePresentation = { closed += "presentation" },
            captureDocumentDrafts = {
                closed += "draft barrier"
                throw failure
            },
            destroyNavigation = { closed += "navigation" },
            closePlatformResources = { closed += "media" },
            sealDocumentDrafts = { closed += "seal" },
        )
        assertNull(retirement.resourceRetirementFailure)

        retirement.beforeSessionRetirement(SessionEndReason.SHUTDOWN)
        retirement.afterSessionRetirement()

        assertSame(failure, retirement.resourceRetirementFailure)
        assertEquals(listOf("presentation", "draft barrier", "navigation", "media", "seal"), closed)
        retirement.beforeSessionRetirement(SessionEndReason.SHUTDOWN)
        retirement.afterSessionRetirement()
        assertSame(failure, retirement.resourceRetirementFailure)
        assertEquals(5, closed.size)
    }
}
