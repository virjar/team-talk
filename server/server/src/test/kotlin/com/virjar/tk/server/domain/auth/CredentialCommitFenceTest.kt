package com.virjar.tk.server.domain.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialCommitFenceTest {
    @Test
    fun `caller cancellation after mutation starts cannot skip the committed fence`() = runBlocking {
        val mutationStarted = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val fencedEpoch = CompletableDeferred<Long>()

        val request = launch {
            commitCredentialMutationAndFence<Long>(
                commit = {
                    mutationStarted.complete(Unit)
                    releaseMutation.await()
                    7L
                },
                publishFence = { epoch -> fencedEpoch.complete(epoch) },
            )
        }

        withTimeout(1_000) { mutationStarted.await() }
        request.cancel()
        releaseMutation.complete(Unit)

        assertEquals(7L, withTimeout(1_000) { fencedEpoch.await() })
        request.join()
        assertTrue(request.isCancelled)
    }
}
