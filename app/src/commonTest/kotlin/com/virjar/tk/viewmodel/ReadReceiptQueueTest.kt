package com.virjar.tk.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReadReceiptQueueTest {
    @Test
    fun `one in-flight receipt is followed only by the highest waiting watermark`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val synchronized = mutableListOf<Long>()
        val queue = ReadReceiptQueue(this) { readSeq ->
            synchronized += readSeq
            if (readSeq == 40L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            true
        }

        queue.request(40)
        runCurrent()
        firstStarted.await()
        queue.request(41)
        queue.request(42)
        queue.request(41)
        releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(listOf(40L, 42L), synchronized)
        queue.close()
    }

    @Test
    fun `failed high watermark survives a lower retry trigger and success suppresses duplicates`() = runTest {
        val attempts = mutableListOf<Long>()
        val queue = ReadReceiptQueue(this) {
            attempts += it
            attempts.size > 1
        }

        queue.request(42)
        runCurrent()
        queue.request(41)
        runCurrent()
        queue.request(42)
        queue.request(41)
        runCurrent()

        assertEquals(listOf(42L, 42L), attempts)
        queue.close()
    }
}
