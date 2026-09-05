package com.virjar.tk.server.infra.db

import com.virjar.tk.server.infra.DataResetRequiredException
import com.virjar.tk.server.infra.ServerDataEpoch
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import com.virjar.tk.server.testing.PostgresSchemaLease
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DatabaseDatasetIdentityIntegrationTest {
    @Test
    fun `dataset identity is stable across reopen and unique for a fresh schema`() {
        PostgresSchemaLease.open().use { firstLease ->
            val firstId = DatabaseFactory.createForTest(firstLease).use { database ->
                database.datasetId.also(SyncDatasetIdPolicy::requireValid)
            }
            val reopenedId = DatabaseFactory.createForTest(firstLease).use { database ->
                database.datasetId.also(SyncDatasetIdPolicy::requireValid)
            }
            assertEquals(firstId, reopenedId)

            PostgresSchemaLease.open().use { freshLease ->
                val freshId = DatabaseFactory.createForTest(freshLease).use { database ->
                    database.datasetId.also(SyncDatasetIdPolicy::requireValid)
                }
                assertNotEquals(firstId, freshId)
            }
        }
    }

    @Test
    fun `one local durable root cannot be spliced onto a fresh PostgreSQL schema`() {
        val dataRoot = Files.createTempDirectory("teamtalk-dataset-binding-").toFile()
        try {
            ServerDataEpoch.initializeOrValidate(dataRoot)
            PostgresSchemaLease.open().use { firstLease ->
                val firstId = DatabaseFactory.createForTest(firstLease).use { database ->
                    database.datasetId
                }
                ServerDataEpoch.bindOrValidateDataset(dataRoot, firstId)

                DatabaseFactory.createForTest(firstLease).use { reopened ->
                    ServerDataEpoch.bindOrValidateDataset(dataRoot, reopened.datasetId)
                }
            }

            PostgresSchemaLease.open().use { replacementLease ->
                DatabaseFactory.createForTest(replacementLease).use { replacement ->
                    assertFailsWith<DataResetRequiredException> {
                        ServerDataEpoch.bindOrValidateDataset(dataRoot, replacement.datasetId)
                    }
                }
            }
        } finally {
            dataRoot.deleteRecursively()
        }
    }

    private fun DatabaseFactory.createForTest(lease: PostgresSchemaLease): PostgresDatabase =
        create(
            jdbcUrl = lease.jdbcUrl,
            user = lease.user,
            password = lease.password,
            maxPoolSize = 1,
        )
}
