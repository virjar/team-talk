package com.virjar.tk.domain.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatLifecycleGateTest {
    @Test
    fun `multi chat gate acquires a colliding stripe only once`() = runTest {
        val gate = ChatLifecycleGate(stripeCount = 1)
        var entries = 0

        withTimeout(1_000) {
            gate.withChats("same-chat", "same-chat", "different-chat-same-stripe") {
                entries++
            }
        }

        assertEquals(1, entries)
    }

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

    @Test
    fun `deactivate waits until message storage and projection are both complete`() = runTest {
        val gate = ChatLifecycleGate(stripeCount = 8)
        val stored = CompletableDeferred<Unit>()
        val allowProjection = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val message = async {
            gate.withChat("chat-message") {
                order += "store"
                stored.complete(Unit)
                allowProjection.await()
                order += "project"
            }
        }
        stored.await()
        val deactivate = async {
            gate.withChat("chat-message") { order += "deactivate" }
        }
        yield()
        assertFalse(deactivate.isCompleted)

        allowProjection.complete(Unit)
        message.await()
        deactivate.await()
        assertEquals(listOf("store", "project", "deactivate"), order)
    }

    @Test
    fun `dissolve cannot overtake member add conversation projection`() = runTest {
        val gate = ChatLifecycleGate(stripeCount = 8)
        val membershipWritten = CompletableDeferred<Unit>()
        val allowProjection = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val add = async {
            gate.withChat("chat-member") {
                order += "member"
                membershipWritten.complete(Unit)
                allowProjection.await()
                order += "conversation"
                order += "member-event"
            }
        }
        membershipWritten.await()
        val dissolve = async {
            gate.withChat("chat-member") { order += "dissolve" }
        }
        yield()
        assertFalse(dissolve.isCompleted)

        allowProjection.complete(Unit)
        add.await()
        dissolve.await()
        assertEquals(listOf("member", "conversation", "member-event", "dissolve"), order)
    }

    @Test
    fun `role mutations recheck and commit in one serialized order`() = runTest {
        val gate = ChatLifecycleGate(stripeCount = 8)
        val transferChecked = CompletableDeferred<Unit>()
        val allowTransferCommit = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val transfer = async {
            gate.withChat("chat-role") {
                order += "transfer-check"
                transferChecked.complete(Unit)
                allowTransferCommit.await()
                order += "transfer-commit"
            }
        }
        transferChecked.await()
        val setRole = async {
            gate.withChat("chat-role") {
                order += "set-role-recheck"
                order += "set-role-commit"
            }
        }
        yield()
        assertFalse(setRole.isCompleted)

        allowTransferCommit.complete(Unit)
        transfer.await()
        setRole.await()
        assertEquals(
            listOf("transfer-check", "transfer-commit", "set-role-recheck", "set-role-commit"),
            order,
        )
    }
}
