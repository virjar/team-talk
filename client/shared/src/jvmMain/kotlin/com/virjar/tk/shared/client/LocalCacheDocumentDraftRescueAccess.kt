package com.virjar.tk.shared.client

import java.io.File
import java.nio.file.Files

/**
 * Offline filesystem/SQLite boundary for the Desktop-owned document record codec.
 * Callbacks never receive an original archive path or a live SQLite connection.
 */
object LocalCacheDocumentDraftRescueAccess {
    fun <T> withSourceCopy(
        archive: File,
        expectedManifestSha256: String? = null,
        read: (temporaryRoot: File, owner: LocalCacheDiagnosticOwner, manifestSha256: String) -> T,
    ): T = rescueGuarded("document draft") {
        val metadata = readCacheRescueArchiveMetadata(archive, expectedManifestSha256)
        if (metadata.layout != LocalCacheDiagnosticLayout.JVM) rescueFailure("SOURCE_DOCUMENT_LAYOUT_UNSUPPORTED")
        val databaseFiles = metadata.files.filter { it.category in setOf("DATABASES", "ACTIVE_DATABASE", "QUARANTINE") }
        val mains = databaseFiles.filter { it.path.substringAfterLast('/').matches(Regex("cache_e[0-9]+\\.db")) }
        if (mains.isEmpty() || mains.size > 32) rescueFailure("SOURCE_DOCUMENT_DATABASES_UNAVAILABLE")
        // Document records are shared by every database generation of this owner. Selecting only an
        // empty replacement database could conceal an unconfirmed rename in its quarantine.
        if (databaseFiles.any { file ->
                listOf("-wal", "-journal", "-shm").any { suffix ->
                    file.path.endsWith(".db$suffix") && mains.none { it.path == file.path.removeSuffix(suffix) }
                }
            }) rescueFailure("SOURCE_DOCUMENT_DATABASE_FAMILY_INCOMPLETE")
        for (main in mains) {
            readCacheRescueArchiveDatabase(archive, main.path, metadata.manifestSha256) { db, source ->
                db.requireRescueSourceSchema(source.owner, setOf("sync_state", "pending_document_move_command"))
                if (db.rescueRows("SELECT 1 FROM pending_document_move_command NOT INDEXED LIMIT 1") { true }.isNotEmpty())
                    rescueFailure("SOURCE_DOCUMENT_OPERATION_PENDING")
            }
        }
        val files = metadata.files.filter { it.category == "DOCUMENT_DRAFTS" }
        if (files.isEmpty()) rescueFailure("SOURCE_DOCUMENT_DRAFTS_MISSING")
        if (files.any { it.bytes > 16L * 1024 * 1024 } || files.sumOf { it.bytes } > 64L * 1024 * 1024)
            rescueFailure("SOURCE_DOCUMENT_DRAFT_SIZE_LIMIT")
        val temporary = Files.createTempDirectory("teamtalk-document-rescue-")
        try {
            val directory = JvmPrivateDataDirectory.openExisting(temporary.toFile())
            val root = archiveRoot(archive)
            for (file in files) {
                val parts = file.path.split('/')
                val target = directory.preparePrivateFile(parts.dropLast(1), parts.last()).toPath()
                if (digestArchiveFile(root, root.resolve("payload").resolve(file.path), file.bytes, target) != file.sha256)
                    rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
            }
            val result = read(temporary.toFile(), metadata.owner, metadata.manifestSha256)
            if (LocalCacheArchive.verify(archive).manifestSha256 != metadata.manifestSha256)
                rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
            result
        } finally {
            try { Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
            catch (_: Exception) { rescueFailure("PRIVATE_TEMP_CLEANUP_FAILED") }
        }
    }

    /** Holds the existing installation lease and healthy current database transaction throughout. */
    fun <T> withTarget(
        root: File, database: String, owner: LocalCacheDiagnosticOwner,
        action: (JvmPrivateDataDirectory) -> T,
    ): T = rescueGuarded("document draft") {
        withCacheRescueTarget(root, database, owner) { target ->
            if (target.db.rescueLong("SELECT count(*) FROM pending_document_move_command NOT INDEXED") != 0L)
                rescueFailure("TARGET_DOCUMENT_OPERATION_PENDING")
            action(target.directory)
        }
    }
}
