package com.virjar.tk.shared.client

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLocalCacheQuarantineTest {
    @Test
    fun `quarantine moves only the exact database family and retains every byte`() =
        withTempDirectory { directory ->
            val database = File(directory, "cache_e27_account.db").withText("main")
            val wal = File(database.path + "-wal").withText("wal")
            val shm = File(database.path + "-shm").withText("shm")
            val journal = File(database.path + "-journal").withText("journal")
            val markers = androidLocalCacheLifecycleMarkers(database)
            val corruptionMarker = markers.corruption.withText("reported")
            val integrityMarker = markers.integrity.withText("checked")
            val openMarker = markers.open.withText("open")
            val neighbour = File(directory, "cache_e27_other.db").withText("other")
            val lookalike = File(database.path + "-wal.backup").withText("lookalike")

            val result = quarantineAndroidLocalCacheDatabase(database, quarantineId = "test")

            assertEquals("main", result.quarantinedMainFile.readText())
            assertEquals("wal", File(result.quarantinedMainFile.path + "-wal").readText())
            assertEquals("shm", File(result.quarantinedMainFile.path + "-shm").readText())
            assertEquals("journal", File(result.quarantinedMainFile.path + "-journal").readText())
            assertEquals(
                "reported",
                File(result.quarantinedMainFile.path + ".corruption-reported").readText(),
            )
            assertEquals(
                "checked",
                File(result.quarantinedMainFile.path + ".integrity-checked").readText(),
            )
            assertEquals("open", File(result.quarantinedMainFile.path + ".open").readText())
            listOf(
                database,
                wal,
                shm,
                journal,
                corruptionMarker,
                integrityMarker,
                openMarker,
            ).forEach { assertFalse(it.exists()) }
            assertEquals("other", neighbour.readText())
            assertEquals("lookalike", lookalike.readText())
        }

    @Test
    fun `an existing quarantine prevents an unbounded second retained copy`() =
        withTempDirectory { directory ->
            val database = File(directory, "cache.db").withText("main")
            File(directory, "cache.db.corrupt-same-wal").withText("occupied")

            assertFailsWith<IOException> {
                quarantineAndroidLocalCacheDatabase(database, quarantineId = "next")
            }

            assertEquals("main", database.readText())
        }

    @Test
    fun `quick check policy covers first use unclean close corruption and periodic audit`() =
        withTempDirectory { directory ->
            val database = File(directory, "cache.db")
            val markers = androidLocalCacheLifecycleMarkers(database)
            val now = 1_000_000_000L

            assertTrue(shouldQuickCheckAndroidLocalCache(markers, now))
            recordSuccessfulAndroidLocalCacheQuickCheck(markers.integrity, now)
            assertFalse(shouldQuickCheckAndroidLocalCache(markers, now + 1L))

            markers.open.withText("open")
            assertTrue(shouldQuickCheckAndroidLocalCache(markers, now + 1L))
            markers.open.delete()

            markers.corruption.withText("reported")
            assertTrue(shouldQuickCheckAndroidLocalCache(markers, now + 1L))
            markers.corruption.delete()

            assertTrue(shouldQuickCheckAndroidLocalCache(markers, now + 8L * 24L * 60L * 60L * 1_000L))
        }

    @Test
    fun `a corruption marker without its main file fails closed and remains available`() =
        withTempDirectory { directory ->
            val database = File(directory, "cache.db")
            val marker = androidLocalCacheLifecycleMarkers(database).corruption.withText("reported")

            assertFailsWith<java.io.FileNotFoundException> {
                quarantineAndroidLocalCacheDatabase(database, quarantineId = "missing")
            }

            assertEquals("reported", marker.readText())
        }

    @Test
    fun `sidecar move failure rolls back before the main-file commit point`() =
        withTempDirectory { directory ->
            val database = File(directory, "cache.db").withText("main")
            val wal = File(database.path + "-wal").withText("wal")
            val shm = File(database.path + "-shm").withText("shm")
            var moveCount = 0

            assertFailsWith<IOException> {
                quarantineAndroidLocalCacheDatabase(
                    databaseFile = database,
                    quarantineId = "rollback",
                    moveFile = { source, target ->
                        moveCount += 1
                        if (moveCount == 2) throw IOException("injected move failure")
                        Files.move(source.toPath(), target.toPath())
                    },
                )
            }

            assertEquals("main", database.readText())
            assertEquals("wal", wal.readText())
            assertEquals("shm", shm.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.contains("corrupt-rollback") })
        }

    private fun <T> withTempDirectory(block: (File) -> T): T {
        val directory = Files.createTempDirectory("tk-android-cache-quarantine-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun File.withText(value: String): File = apply { writeText(value) }
}
