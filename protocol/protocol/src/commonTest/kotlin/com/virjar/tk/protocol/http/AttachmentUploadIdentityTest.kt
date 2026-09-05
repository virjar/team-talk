package com.virjar.tk.protocol.http

import com.virjar.tk.protocol.ReliableCommandContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AttachmentUploadIdentityTest {
    @Test
    fun `headers parse one canonical stable identity`() {
        val identity = parseAttachmentUploadIdentityHeaders(
            uploadIdHeader = UPLOAD_ID,
            issuedAtHeader = "1701234567890",
        )

        assertEquals(UPLOAD_ID, identity.uploadId)
        assertEquals(1_701_234_567_890L, identity.issuedAt)
    }

    @Test
    fun `headers reject missing aliased or malformed values`() {
        assertFailsWith<IllegalArgumentException> {
            parseAttachmentUploadIdentityHeaders(null, "0")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAttachmentUploadIdentityHeaders(UPLOAD_ID, null)
        }
        assertFailsWith<IllegalArgumentException> {
            parseAttachmentUploadIdentityHeaders(UPLOAD_ID.uppercase(), "0")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAttachmentUploadIdentityHeaders(UPLOAD_ID, "+1")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAttachmentUploadIdentityHeaders(UPLOAD_ID, "01")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAttachmentUploadIdentityHeaders(UPLOAD_ID, Long.MAX_VALUE.toString() + "0")
        }
    }

    @Test
    fun `shared retry horizon and clock skew boundaries are exact`() {
        val now = 1_800_000_000_000L
        val oldest = AttachmentUploadIdentity(
            UPLOAD_ID,
            now - ReliableCommandContract.RETRY_HORIZON_MILLIS,
        )
        val newest = AttachmentUploadIdentity(
            UPLOAD_ID,
            now + ReliableCommandContract.MAX_FUTURE_CLOCK_SKEW_MILLIS,
        )

        assertSame(oldest, oldest.requireActiveAt(now))
        assertSame(newest, newest.requireActiveAt(now))
        assertFailsWith<IllegalArgumentException> {
            oldest.copy(issuedAt = oldest.issuedAt - 1L).requireActiveAt(now)
        }
        assertFailsWith<IllegalArgumentException> {
            newest.copy(issuedAt = newest.issuedAt + 1L).requireActiveAt(now)
        }
        val saturated = AttachmentUploadIdentity(UPLOAD_ID, Long.MAX_VALUE)
        assertSame(saturated, saturated.requireActiveAt(Long.MAX_VALUE))
    }

    private companion object {
        const val UPLOAD_ID = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
