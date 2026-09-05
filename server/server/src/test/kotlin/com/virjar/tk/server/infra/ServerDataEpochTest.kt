package com.virjar.tk.server.infra

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerDataEpochTest {
    @Test
    fun `fresh data root receives the current marker and is reusable`() = withTempRoot { root ->
        ServerDataEpoch.initializeOrValidate(root)
        assertEquals(
            ServerDataEpoch.CURRENT_EPOCH.toString(),
            File(root, "data-epoch").readText().trim(),
        )

        ServerDataEpoch.initializeOrValidate(root)
    }

    @Test
    fun `fresh local stores bind atomically to the PostgreSQL dataset and remain reusable`() =
        withTempRoot { root ->
            ServerDataEpoch.initializeOrValidate(root)
            ServerDataEpoch.bindOrValidateDataset(root, DATASET_A)

            assertEquals(DATASET_A, File(root, "dataset-id").readText().trim())
            ServerDataEpoch.bindOrValidateDataset(root, DATASET_A)
        }

    @Test
    fun `a different PostgreSQL dataset cannot open existing local durable data`() =
        withTempRoot { root ->
            ServerDataEpoch.initializeOrValidate(root)
            ServerDataEpoch.bindOrValidateDataset(root, DATASET_A)
            File(root, "rocksdb").apply { mkdirs() }
            File(root, "rocksdb/CURRENT").writeText("current")

            assertFailsWith<DataResetRequiredException> {
                ServerDataEpoch.bindOrValidateDataset(root, DATASET_B)
            }
        }

    @Test
    fun `unidentified existing local data cannot be adopted by a PostgreSQL dataset`() =
        withTempRoot { root ->
            ServerDataEpoch.initializeOrValidate(root)
            File(root, "file-store/files").apply { mkdirs() }
            File(root, "file-store/files/object").writeText("bytes")

            assertFailsWith<DataResetRequiredException> {
                ServerDataEpoch.bindOrValidateDataset(root, DATASET_A)
            }
            assertEquals(false, File(root, "dataset-id").exists())
        }

    @Test
    fun `malformed local dataset identity fails closed`() = withTempRoot { root ->
        ServerDataEpoch.initializeOrValidate(root)
        File(root, "dataset-id").writeText("not-a-dataset\n")

        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.bindOrValidateDataset(root, DATASET_A)
        }
    }

    @Test
    fun `dataset binding cannot precede epoch initialization`() = withTempRoot { root ->
        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.bindOrValidateDataset(root, DATASET_A)
        }
    }

    @Test
    fun `unmarked durable data is rejected instead of decoded as current`() = withTempRoot { root ->
        File(root, "rocksdb").apply { mkdirs() }
        File(root, "rocksdb/CURRENT").writeText("old")

        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.initializeOrValidate(root)
        }
    }

    @Test
    fun `mismatched marker is rejected`() = withTempRoot { root ->
        File(root, "data-epoch").writeText("${ServerDataEpoch.CURRENT_EPOCH + 1}\n")

        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.initializeOrValidate(root)
        }
    }

    @Test
    fun `legacy raw-token store is rejected before creating an epoch marker`() = withTempRoot { root ->
        File(root, "tokenstore").apply { mkdirs() }
        File(root, "tokenstore/CURRENT").writeText("legacy rocks token data")

        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.initializeOrValidate(root)
        }
        assertEquals(false, File(root, "data-epoch").exists())
    }

    @Test
    fun `legacy raw-token store is rejected even beside a current marker`() = withTempRoot { root ->
        File(root, "data-epoch").writeText("${ServerDataEpoch.CURRENT_EPOCH}\n")
        File(root, "tokenstore").apply { mkdirs() }
        File(root, "tokenstore/CURRENT").writeText("legacy rocks token data")

        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.initializeOrValidate(root)
        }
    }

    @Test
    fun `legacy unbounded client logs are rejected even beside a current marker`() = withTempRoot { root ->
        File(root, "data-epoch").writeText("${ServerDataEpoch.CURRENT_EPOCH}\n")
        File(root, "client-logs/legacy-device/2026-08-01").apply { mkdirs() }
        File(root, "client-logs/legacy-device/2026-08-01/fatal.log").writeText("legacy raw log")

        assertFailsWith<DataResetRequiredException> {
            ServerDataEpoch.initializeOrValidate(root)
        }
    }

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-data-epoch-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val DATASET_A = "00000000-0000-4000-8000-000000000001"
        const val DATASET_B = "00000000-0000-4000-8000-000000000002"
    }
}
