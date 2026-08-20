package com.virjar.tk.e2e

import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.testing.FakeLocalCache

/**
 * Real TCP clients must complete the same durable-event handshake as production clients.
 * Tests that do not need a full ClientSession still install a real EventProcessor instead of
 * bypassing synchronization or silently discarding replayed events.
 */
internal class E2eEventProjection(client: ImClient) : AutoCloseable {
    private val processor = EventProcessor(client, FakeLocalCache()).also { it.start() }

    override fun close() {
        processor.stop()
    }
}

internal fun ImClient.installE2eEventProjection(): E2eEventProjection = E2eEventProjection(this)
