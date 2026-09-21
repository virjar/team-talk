@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.virjar.tk.shared.http

import com.virjar.tk.shared.client.SessionBoundaryReentrantCloseException
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.*
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

class FoundationHttpClientTest {
    @Test fun closeCancelsPreparationAndWaitsForItsCleanup(): Unit = runBlocking {
        val owner = FoundationHttpClient()
        val entered = CompletableDeferred<Unit>()
        val cleaned = PlatformAtomicBoolean(false)
        val pending = async(Dispatchers.Default) {
            owner.operation {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.set(true) }
            }
        }
        withTimeout(5_000) { entered.await() }
        withContext(Dispatchers.Default) { owner.close() }
        assertTrue(cleaned.get(), "close returned before an admitted upload source released resources")
        assertFailsWith<CancellationException> { pending.await() }
        assertFailsWith<IllegalStateException> { owner.operation { error("must not run") } }
        owner.close()
    }

    @Test fun reentrantCloseRejectsTheBoundaryWithoutDeadlocking(): Unit = runBlocking {
        val owner = FoundationHttpClient()
        val detected = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) {
            owner.operation {
                assertFailsWith<SessionBoundaryReentrantCloseException> { owner.close() }
                detected.complete(Unit)
            }
        }
        withTimeout(5_000) { detected.await() }
        assertFailsWith<CancellationException> { pending.await() }
        withContext(Dispatchers.Default) { owner.close() }
    }

    @Test fun concurrentClosersWaitForChildCleanupAfterOperationBodyExits(): Unit = runBlocking {
        val owner = FoundationHttpClient()
        val entered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) {
            owner.operation {
                CoroutineScope(currentCoroutineContext()).launch {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() } }
                }
                // The body exits, but its structured child still owns admitted work.
            }
        }
        withTimeout(5_000) { entered.await() }
        val first = async(Dispatchers.Default) { owner.close() }
        withTimeout(5_000) { cleanupStarted.await() }
        val second = async(Dispatchers.Default) { owner.close() }
        try {
            delay(50)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
        } finally { releaseCleanup.complete(Unit) }
        withTimeout(5_000) { first.await(); second.await() }
        assertFailsWith<CancellationException> { pending.await() }
    }

    @Test fun requestAndServerAddressRejectCredentialAndHeaderInjection() {
        assertEquals("https://example.com/api", canonicalHttpServerBase("HTTPS://EXAMPLE.COM:443/api/"))
        assertEquals("http://127.0.0.1:5101", canonicalHttpServerBase("http://127.0.0.1:5101/"))
        listOf("https://user:secret@example.com", "https://example.com?x=1", "https://example.com#secret", "file:///tmp/test").forEach {
            assertFailsWith<IllegalArgumentException> { canonicalHttpServerBase(it) }
        }
        assertFailsWith<IllegalArgumentException> { foundationHttpRequest("https://user:secret@example.com", "GET") }
        assertFailsWith<IllegalArgumentException> {
            foundationHttpRequest("https://example.com", "GET", mapOf("Authorization" to "Bearer token\r\nInjected: bad"))
        }
        val request = foundationHttpRequest("https://example.com", "POST", mapOf("Authorization" to "Bearer current"), byteArrayOf(1, 2, 3))
        assertEquals("Bearer current", request.valueForHTTPHeaderField("Authorization"))
        assertEquals(3uL, request.HTTPBody?.length)
    }

    @Test fun platformUploadSourceStreamsLargeFilesAndRejectsMutation(): Unit = runBlocking {
        val file = PlatformFile(NSTemporaryDirectory(), "teamtalk-upload-test-${platformRandomUuid()}")
        try {
            val content = "upload fixture ".repeat(300_000)
            file.writeText(content)
            val source = file.asUploadSource()
            var received = 0L
            source.writeTo { bytes, offset, length ->
                assertTrue(length in 1..DEFAULT_UPLOAD_CHUNK_BYTES)
                assertTrue(offset >= 0 && offset + length <= bytes.size)
                received += length
            }
            assertEquals(content.encodeToByteArray().size.toLong(), received)
            file.appendText("changed")
            assertFailsWith<IllegalStateException> { source.writeTo { _, _, _ -> error("mutated source must not publish") } }
        } finally { file.delete() }
    }
}
