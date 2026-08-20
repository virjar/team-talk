package com.virjar.tk.domain.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatLifecycleGateTest {
    @Test
    fun `same chat lifecycle work cannot interleave`() = runTest {
        val gate = ChatLifecycleGate(stripeCount = 8)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val deactivation = async {
            gate.withChat("chat-a") {
                order += "cleanup"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "deactivate"
            }
        }
        firstEntered.await()
        val grant = async {
            gate.withChat("chat-a") { order += "grant" }
        }
        yield()

        assertFalse(grant.isCompleted)
        releaseFirst.complete(Unit)
        deactivation.await()
        grant.await()
        assertEquals(listOf("cleanup", "deactivate", "grant"), order)
    }
}
