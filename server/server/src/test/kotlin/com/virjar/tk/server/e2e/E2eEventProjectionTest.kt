package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.ImClient
import kotlin.test.Test
import kotlin.test.assertFailsWith

class E2eEventProjectionTest {
    @Test
    fun `AUTH dataset binding is canonical idempotent and cannot cross datasets`() {
        val client = ImClient()
        val projection = client.installE2eEventProjection()
        try {
            assertFailsWith<IllegalArgumentException> { projection.bindDataset("not-a-dataset") }

            projection.bindDataset(DATASET_A)
            projection.bindDataset(DATASET_A)
            assertFailsWith<IllegalStateException> { projection.bindDataset(DATASET_B) }
        } finally {
            try {
                projection.close()
            } finally {
                client.destroy()
            }
        }
    }

    private companion object {
        const val DATASET_A = "00000000-0000-4000-8000-000000000001"
        const val DATASET_B = "00000000-0000-4000-8000-000000000002"
    }
}
