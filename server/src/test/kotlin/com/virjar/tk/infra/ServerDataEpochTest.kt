package com.virjar.tk.infra

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

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-data-epoch-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
