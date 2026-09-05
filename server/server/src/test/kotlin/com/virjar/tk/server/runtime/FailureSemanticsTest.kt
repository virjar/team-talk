package com.virjar.tk.server.runtime

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FailureSemanticsTest {
    @Test
    fun `later fatal failure becomes terminal without losing ordinary failures`() {
        val ordinary = IllegalStateException("ordinary")
        val fatal = FatalProbe("fatal")
        val later = IllegalArgumentException("later")

        var terminal: Throwable? = null
        terminal = mergeRuntimeFailure(terminal, ordinary)
        terminal = mergeRuntimeFailure(terminal, fatal)
        terminal = mergeRuntimeFailure(terminal, later)

        assertSame(fatal, terminal)
        assertEquals(listOf(ordinary, later), fatal.suppressed.toList())
    }

    @Test
    fun `cancellation has fatal precedence and repeated identity is ignored`() {
        val ordinary = IllegalStateException("ordinary")
        val cancelled = CancellationException("cancelled")

        var terminal: Throwable? = ordinary
        terminal = mergeRuntimeFailure(terminal, cancelled)
        terminal = mergeRuntimeFailure(terminal, cancelled)

        assertSame(cancelled, terminal)
        assertEquals(listOf(ordinary), cancelled.suppressed.toList())
    }

    @Test
    fun `collector drains every action before replaying exact fatal failure`() {
        val fatal = FatalProbe("fatal")
        val visited = mutableListOf<Int>()
        val collector = RuntimeFailureCollector()

        collector.capture {
            visited += 1
            throw IllegalStateException("first")
        }
        collector.capture {
            visited += 2
            throw fatal
        }
        collector.capture { visited += 3 }

        assertEquals(listOf(1, 2, 3), visited)
        assertSame(fatal, collector.failureOrNull())
    }

    private class FatalProbe(message: String) : Error(message)
}
