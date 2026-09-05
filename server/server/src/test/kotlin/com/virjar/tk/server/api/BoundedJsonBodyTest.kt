package com.virjar.tk.server.api

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BoundedJsonBodyTest {
    @Test
    fun `exact structured body limit is accepted`() = runBlocking {
        val body = ByteArray(32) { it.toByte() }

        assertContentEquals(body, ByteReadChannel(body).readStructuredBodyBounded(body.size))
    }

    @Test
    fun `first byte beyond structured body limit is rejected`() {
        runBlocking {
            val body = ByteArray(33) { it.toByte() }

            assertFailsWith<StructuredHttpBodyTooLargeException> {
                ByteReadChannel(body).readStructuredBodyBounded(body.size - 1)
            }
        }
    }
}
