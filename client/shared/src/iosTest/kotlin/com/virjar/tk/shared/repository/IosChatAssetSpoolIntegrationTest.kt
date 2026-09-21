package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.platform.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class IosChatAssetSpoolIntegrationTest {
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
