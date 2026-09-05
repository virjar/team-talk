package com.virjar.tk.server.infra.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileAdminDiagnosticsTest {
    @Test
    fun `server listings are bounded and deterministically ordered`() = withRoots { root ->
        val logs = Files.createDirectories(root.resolve("logs"))
        val traces = Files.createDirectories(logs.resolve("traces"))
        write(logs.resolve("old.log"), "old", modified = 1L)
        write(traces.resolve("new.log"), "new", modified = 3L)
        write(logs.resolve("middle.log"), "middle", modified = 2L)

        val diagnostics = diagnostics(
            root = root,
            limits = AdminDiagnosticsLimits(
                serverLogFiles = 2,
            ),
        )

        assertEquals(listOf("traces/new.log", "middle.log"), diagnostics.listServerLogs().map { it.name })
    }

    @Test
    fun `tail is line capped byte bounded and never reads a traversal escape`() = withRoots { root ->
        val logs = Files.createDirectories(root.resolve("logs"))
        val serverLog = logs.resolve("server.log")
        write(serverLog, "机密\nvisible-one\nvisible-two\n")
        write(logs.resolve("unterminated.log"), "a-line-with-no-newline-that-exceeds-the-tail-budget")
        val outside = write(root.resolve("outside.log"), "outside")
        Files.createSymbolicLink(logs.resolve("escape.log"), outside)

        val byteBounded = diagnostics(
            root = root,
            limits = AdminDiagnosticsLimits(tailBytes = 26, tailChunkBytes = 7),
        )
        val tail = byteBounded.readServerLog("server.log", 2_000)
        assertEquals(listOf("visible-one", "visible-two"), tail)
        assertEquals(emptyList(), byteBounded.readServerLog("unterminated.log", 2_000))
        assertFailsWith<IllegalArgumentException> { byteBounded.readServerLog("../outside.log", 10) }
        assertFailsWith<IllegalArgumentException> { byteBounded.readServerLog("escape.log", 10) }
    }

    @Test
    fun `storage traversal stops at its entry budget and marks the byte total as truncated`() = withRoots { root ->
        val storage = Files.createDirectories(root.resolve("rocks"))
        write(storage.resolve("a.bin"), "a")
        write(storage.resolve("b.bin"), "bb")
        write(storage.resolve("c.bin"), "ccc")

        val exact = measureDirectorySize(storage, entryBudget = 10)
        assertEquals(3, exact.visitedEntries)
        assertEquals(6L, exact.bytes)
        assertFalse(exact.truncated)

        val bounded = measureDirectorySize(storage, entryBudget = 2)
        assertEquals(2, bounded.visitedEntries)
        assertTrue(bounded.bytes in 1L..5L)
        assertTrue(bounded.truncated)

        val diagnostics = FileAdminDiagnostics(
            logsRoot = root.resolve("logs"),
            rocksDbRoots = listOf(storage),
            fileStoreRoots = emptyList(),
            limits = AdminDiagnosticsLimits(storageEntriesPerRoot = 2),
        )
        val usage = diagnostics.storageUsage()
        assertTrue(usage.truncated)
        assertTrue(usage.rocksdbBytes in 1L..5L)
        assertEquals(0L, usage.fileStoreBytes)
    }

    private fun diagnostics(root: Path, limits: AdminDiagnosticsLimits) = FileAdminDiagnostics(
        logsRoot = root.resolve("logs"),
        rocksDbRoots = listOf(root.resolve("rocks")),
        fileStoreRoots = listOf(root.resolve("files")),
        limits = limits,
    )

    private fun write(path: Path, text: String, modified: Long? = null): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        modified?.let { Files.setLastModifiedTime(path, FileTime.fromMillis(it)) }
        return path
    }

    private fun withRoots(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-admin-diagnostics-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
