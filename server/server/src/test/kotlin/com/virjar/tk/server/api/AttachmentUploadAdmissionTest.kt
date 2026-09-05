package com.virjar.tk.server.api

import com.virjar.tk.protocol.body.AttachmentPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AttachmentUploadAdmissionTest {

    @Test
    fun `default admission reserves at most four worst case uploads`() {
        val admission = AttachmentUploadAdmission()

        assertEquals(4, admission.maxConcurrentUploads)

        val leases = List(MAX_CONCURRENT_ATTACHMENT_UPLOADS) { index ->
            assertNotNull(admission.tryAcquire("uid-$index"))
        }
        assertEquals(MAX_CONCURRENT_ATTACHMENT_UPLOADS, admission.activeUploadCount)
        assertEquals(MAX_CONCURRENT_ATTACHMENT_UPLOADS, admission.activeUidCount)
        assertNull(admission.tryAcquire("overflow"), "the saturated admission must reject without waiting")

        leases.forEach(AttachmentUploadLease::close)
        assertEquals(0, admission.activeUploadCount)
        assertEquals(0, admission.activeUidCount)
    }

    @Test
    fun `lease release is idempotent and cannot manufacture a permit`() {
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        val first = assertNotNull(admission.tryAcquire("same-uid"))

        first.close()
        first.close()

        val replacement = assertNotNull(admission.tryAcquire("same-uid"))
        assertNull(admission.tryAcquire("different-uid"), "closing one lease twice must release only one permit")
        replacement.close()
        assertEquals(0, admission.activeUploadCount)
    }

    @Test
    fun `one uid may upload two assets concurrently without monopolizing all global slots`() {
        val admission = AttachmentUploadAdmission()
        val first = assertNotNull(admission.tryAcquire("one-uid"))
        val second = assertNotNull(admission.tryAcquire("one-uid"))
        assertNull(admission.tryAcquire("one-uid"))

        val others = (3..4).map { index ->
            assertNotNull(admission.tryAcquire("uid-$index"))
        }
        assertEquals(4, admission.activeUploadCount)
        assertEquals(3, admission.activeUidCount)

        first.close()
        second.close()
        others.forEach(AttachmentUploadLease::close)
        assertEquals(0, admission.activeUploadCount)
        assertEquals(0, admission.activeUidCount, "inactive uid keys must not be retained")
    }

    @Test
    fun `configuration cannot exceed the two gibibyte staging budget`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadAdmission(maxConcurrentUploads = MAX_CONCURRENT_ATTACHMENT_UPLOADS + 1)
        }
        assertEquals(
            2L * 1024 * 1024 * 1024,
            MAX_CONCURRENT_ATTACHMENT_UPLOADS.toLong() * AttachmentPolicy.MAX_UPLOAD_BYTES,
        )
    }
}
