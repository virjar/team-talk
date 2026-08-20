package com.virjar.tk.infra

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Fail-fast guard for all non-PostgreSQL durable server data.
 *
 * Message bytes use the current protocol model, tokens refer to current relational identities,
 * Lucene is derived from current messages, and file metadata is joined with current ACL rows.
 * They therefore advance as one disposable pre-release epoch instead of each store carrying a
 * separate migration branch.
 */
object ServerDataEpoch {
    const val CURRENT_EPOCH = 5

    private const val MARKER_FILE = "data-epoch"
    private val durableRelativePaths = listOf(
        "rocksdb",
        "tokenstore",
        "lucene-index",
        "file-store/rocksdb",
        "file-store/files",
    )

    fun initializeOrValidate(dataRoot: File) {
        dataRoot.mkdirs()
        val marker = File(dataRoot, MARKER_FILE)
        if (marker.exists()) {
            val actual = marker.readText().trim().toIntOrNull()
                ?: throw DataResetRequiredException("Server data epoch marker is invalid: ${marker.absolutePath}")
            if (actual != CURRENT_EPOCH) {
                throw DataResetRequiredException(
                    "Server data epoch $actual is incompatible with required epoch $CURRENT_EPOCH; " +
                        "reset the disposable pre-release server data",
                )
            }
            return
        }

        val populated = durableRelativePaths
            .map { File(dataRoot, it) }
            .filter { it.containsEntries() }
            .map { it.relativeTo(dataRoot).path }
        if (populated.isNotEmpty()) {
            throw DataResetRequiredException(
                "Server data predates epoch $CURRENT_EPOCH; reset the disposable pre-release data " +
                    "before startup (found: ${populated.joinToString()})",
            )
        }

        val pending = File(dataRoot, "$MARKER_FILE.tmp-${ProcessHandle.current().pid()}")
        pending.writeText("$CURRENT_EPOCH\n")
        try {
            Files.move(
                pending.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun File.containsEntries(): Boolean = isDirectory && !list().isNullOrEmpty()
}

class DataResetRequiredException(message: String) : IllegalStateException(message)
