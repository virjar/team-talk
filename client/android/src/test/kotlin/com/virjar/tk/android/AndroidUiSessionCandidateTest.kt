package com.virjar.tk.android

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidUiSessionCandidateTest {
    @Test
    fun `failed platform attachment releases resources and never publishes either session`() {
        val gate = AndroidSessionOwnerGate<Any>()
        val previous = Any()
        val next = Any()
        gate.replaceOwner(previous) { Unit }
        val resources = AndroidAuthenticatedResourceOwner()
        val events = mutableListOf<String>()
        val hotDrafts = mutableListOf("unsaved body")
        var published: Any? = null
        val failure = IllegalStateException("notification channel unavailable")

        assertSame(failure, assertFailsWith<IllegalStateException> {
            gate.replaceOwner(next) {
                assertSame(previous, it)
                assertFalse(gate.runIfOwner(previous) { error("old callback admitted during replacement") })
                val candidate = completeAndroidUiCandidate(
                    state = hotDrafts,
                    resources = resources,
                    attachResources = {
                        resources.acquire { AutoCloseable { events += "stop notifications" } }
                        resources.acquire { AutoCloseable { events += "stop media" } }
                        throw failure
                    },
                    discardState = { events += "destroy candidate UI" },
                )
                published = candidate
            }
        })

        assertNull(published)
        assertEquals(listOf("stop notifications", "stop media", "destroy candidate UI"), events)
        assertEquals(listOf("unsaved body"), hotDrafts)
        assertFalse(gate.runIfOwner(previous) { error("retired owner survived failure") })
        assertFalse(gate.runIfOwner(next) { error("failed candidate became owner") })
        assertNull(resources.acquire { error("sealed owner admitted another producer") }.resourceOrNull())

        // 重试借用原热草稿；只有资源完整组装以后才能接收该会话的回调。
        val retryResources = AndroidAuthenticatedResourceOwner()
        val retry = gate.replaceOwner(next) {
            assertNull(it)
            completeAndroidUiCandidate(hotDrafts, retryResources, {}, { error("successful candidate discarded") })
        }
        assertSame(hotDrafts, retry)
        assertTrue(gate.runIfOwner(next) { events += "next callback" })
        retryResources.closeAll()
    }

    @Test
    fun `candidate cleanup continues after cancellation and retains both failures`() {
        val resources = AndroidAuthenticatedResourceOwner()
        val constructionFailure = IllegalStateException("attachment failed")
        val cancellation = CancellationException("resource cancelled")
        val uiFailure = IllegalStateException("UI disposal failed")
        var secondResourceClosed = false
        var uiDisposed = false
        resources.acquire { AutoCloseable { throw cancellation } }
        resources.acquire { AutoCloseable { secondResourceClosed = true } }

        val terminal = assertFailsWith<CancellationException> {
            completeAndroidUiCandidate(
                state = Any(), resources = resources,
                attachResources = { throw constructionFailure },
                discardState = { uiDisposed = true; throw uiFailure },
            )
        }

        assertSame(cancellation, terminal)
        assertTrue(secondResourceClosed)
        assertTrue(uiDisposed)
        assertTrue(terminal.suppressed.any { it === constructionFailure })
        assertTrue(terminal.suppressed.any { it === uiFailure })
    }
}
