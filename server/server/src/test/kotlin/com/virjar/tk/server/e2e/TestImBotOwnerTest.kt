package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.DeploymentIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class TestImBotOwnerTest {
    @Test
    fun `each bot cache is born in the authoritative dataset supplied by AUTH`() {
        val deployment = DeploymentIdentity.from(
            tcpHost = "127.0.0.1",
            tcpPort = 5100,
            serverUrl = "http://127.0.0.1:8080",
        )
        val first = testImBotCacheOwner.open(deployment, DATASET_A, "uid-a")
        val second = testImBotCacheOwner.open(deployment, DATASET_B, "uid-b")
        try {
            assertEquals(DATASET_A, first.bindSyncDataset(DATASET_A).datasetId)
            assertEquals(DATASET_B, second.bindSyncDataset(DATASET_B).datasetId)
        } finally {
            first.close()
            second.close()
        }
    }

    private companion object {
        const val DATASET_A = "00000000-0000-4000-8000-000000000001"
        const val DATASET_B = "00000000-0000-4000-8000-000000000002"
    }
}
