package com.virjar.tk.app.client

import com.virjar.tk.shared.client.ConnectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthFormSubmissionStateTest {
    @Test
    fun `accepted submission stays loading through synchronization and can retry after failure`() {
        val form = AuthFormSubmissionState()
        form.submit { AuthSubmissionDisposition.ACCEPTED }
        assertTrue(form.loading)
        form.onConnectionStateChanged(ConnectionState.CONNECTING)
        form.onConnectionStateChanged(ConnectionState.CONNECTED)
        form.onConnectionStateChanged(ConnectionState.SYNCHRONIZING)
        assertTrue(form.loading)
        form.onConnectionStateChanged(ConnectionState.AUTH_FAILED)
        assertFalse(form.loading)

        form.submit { AuthSubmissionDisposition.ACCEPTED }
        assertTrue(form.loading)
        form.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        assertFalse(form.loading)
        form.submit { AuthSubmissionDisposition.ACCEPTED }
        form.onConnectionStateChanged(ConnectionState.AUTHENTICATED)
        assertFalse(form.loading)
    }

    @Test
    fun `rejected or stale submissions neither start loading nor clear an accepted attempt`() {
        val form = AuthFormSubmissionState()
        form.submit { AuthSubmissionDisposition.REJECTED }
        assertFalse(form.loading)
        form.submit { AuthSubmissionDisposition.STALE }
        assertFalse(form.loading)

        form.submit { AuthSubmissionDisposition.ACCEPTED }
        form.submit { AuthSubmissionDisposition.STALE }
        assertTrue(form.loading)
        form.submit { AuthSubmissionDisposition.REJECTED }
        assertTrue(form.loading)
    }

    @Test
    fun `synchronous completion during submission does not restart loading`() {
        val form = AuthFormSubmissionState()
        form.submit {
            form.onConnectionStateChanged(ConnectionState.AUTHENTICATED)
            AuthSubmissionDisposition.ACCEPTED
        }
        assertFalse(form.loading)
    }
}
