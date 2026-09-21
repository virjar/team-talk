package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.platform.*
import kotlinx.coroutines.*
import kotlin.test.*

class IosChatAssetSpoolIntegrationTest {
    @Test
    fun reopenedOwnersShareReservationsAndCancelledImportsReleaseNativeFiles() = runBlocking<Unit> {
        val root = PlatformFile(platformDataDir(), "native-spool-test-${platformRandomUuid()}")
        check(root.mkdir())
        try {
            val owner = AccountDataOwner("a".repeat(64), platformRandomUuid(), "owner")
            val spool = createChatAssetSpool(root, owner, quotaBytes = 4, maxEntries = 1)
            val started = CompletableDeferred<Unit>()
            val pending = async(Dispatchers.Default) {
                spool.stage(object : UploadSource {
                    override val contentLength = 4L
                    override suspend fun writeTo(sink: UploadSink) {
                        sink.write(byteArrayOf(1, 2), 0, 2)
                        started.complete(Unit)
                        awaitCancellation()
                    }
                })
            }
            try {
                withTimeout(5_000) { started.await() }
                val reopened = createChatAssetSpool(root, owner, quotaBytes = 4, maxEntries = 1)
                assertFailsWith<IllegalStateException> { reopened.stage(byteArrayOf(3).asSmallUploadSource()) }
                pending.cancelAndJoin()
                assertTrue(reopened.list().isEmpty())
                val staged = reopened.stage(byteArrayOf(3, 4, 5, 6).asSmallUploadSource())
                assertEquals(listOf(staged), spool.list())
                val directory = chatAssetSpoolDirectories(owner).fold(root) { parent, part -> PlatformFile(parent, part) }
                assertEquals(1, checkNotNull(directory.listFiles()).size, "cancelled native writer left a partial behind")
            } finally { pending.cancelAndJoin() }
        } finally { check(root.deleteRecursively()) }
    }

    @Test
    fun persistedSourceIsVerifiedBeforeDeliveryAndDeletesAfterItsReadLease() = runBlocking {
        val root = PlatformFile(platformDataDir(), "native-spool-test-${platformRandomUuid()}")
        check(root.mkdir())
        try {
            val owner = AccountDataOwner("a".repeat(64), platformRandomUuid(), "owner")
            val source = object : UploadSource {
                override val contentLength = 3L * 1024 * 1024 + 17
                override suspend fun writeTo(sink: UploadSink) {
                    val chunk = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES) { (it % 251).toByte() }
                    var remaining = contentLength
                    while (remaining > 0) {
                        val count = minOf(chunk.size.toLong(), remaining).toInt()
                        sink.write(chunk, 0, count); remaining -= count
                    }
                }
            }
            val spool = createChatAssetSpool(root, owner)
            val staged = spool.stage(source)
            val reopened = createChatAssetSpool(root, owner)
            assertEquals(listOf(staged), reopened.list())
            var delivered = 0L
            reopened.open(staged.sourceId).writeTo { bytes, offset, length ->
                assertTrue(length <= DEFAULT_UPLOAD_CHUNK_BYTES)
                repeat(length) { assertEquals((((delivered + it) % DEFAULT_UPLOAD_CHUNK_BYTES) % 251).toByte(), bytes[offset + it]) }
                delivered += length
                if (delivered == length.toLong()) reopened.delete(staged.sourceId)
            }
            assertEquals(source.contentLength, delivered)
            assertTrue(reopened.list().isEmpty())

            val tampered = spool.stage(source)
            val directory = chatAssetSpoolDirectories(owner).fold(root) { parent, part -> PlatformFile(parent, part) }
            PlatformFile(directory, "${tampered.sourceId}.${tampered.sha256}.blob").writeBytes(byteArrayOf(1))
            delivered = 0
            assertFailsWith<IllegalStateException> {
                reopened.open(tampered.sourceId).writeTo { _, _, length -> delivered += length }
            }
            assertEquals(0L, delivered)
        } finally { check(root.deleteRecursively()) }
    }
}
