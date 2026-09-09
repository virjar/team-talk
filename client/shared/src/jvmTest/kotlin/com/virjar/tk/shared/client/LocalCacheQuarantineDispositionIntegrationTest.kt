package com.virjar.tk.shared.client

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlin.test.*

/** 真实隔离文件和当前 SQLite：显式放弃只退役已保全的隔离事实，不重放或删除共享源。 */
class LocalCacheQuarantineDispositionIntegrationTest {
    @Test
    fun `JVM discard preserves active reliable facts and shared bytes and permits future quarantine`() = workspace { workspace ->
        val source = source(workspace)
        val selected = jvmFiles(source)
        withCache(source) { assertFalse(it.chatDrafts.orphanSourceCleanupAllowed) }
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantineDatabase, archive)
        // The replacement can legitimately advance after preservation; those new facts are not
        // deletion inputs and must neither block disposition nor be restored from the old archive.
        withCache(source) { cache ->
            cache.enqueueOutgoingMessage(message("after-archive", "new unsent content"), 2, REQUEST_FINGERPRINT)
        }
        file(source, "chat-assets/$fingerprint/$DATASET/$UID/source.blob", "changed source".encodeToByteArray())
        file(source, "chat-assets/$fingerprint/$DATASET/$UID/new-source.blob", PRIVATE_BYTES)
        val docs = DocumentDraftStoragePaths.jvmDirectories(fingerprint, DATASET, UID).joinToString("/")
        file(source, "$docs/manifest", "new document state".encodeToByteArray())
        file(source, "$docs/new-record.payload", PRIVATE_BYTES)
        val before = inventory(source)

        val report = LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)

        assertReport(report, source, archive, exported.manifestSha256, selected)
        assertFalse(report.alreadyAbsent)
        assertFalse(File(source, quarantineDirectory).exists())
        assertEquals(before.filterKeys { it !in selected }, inventory(source), "active database, sources and independent drafts keep their original bytes")
        assertEquals(exported, LocalCacheArchive.verify(archive))
        withCache(source) { cache ->
            assertTrue(cache.chatDrafts.orphanSourceCleanupAllowed)
            val outgoing = assertNotNull(cache.getOutgoingMessage("chat", "current-pending", REQUEST_FINGERPRINT))
            assertEquals(OutgoingMessageState.PENDING, outgoing.state)
            assertEquals(1L, outgoing.createdAt)
            assertEquals("current unsent content", (outgoing.message.body as RichTextBody).markdown)
            assertEquals("new unsent content", (assertNotNull(cache.getOutgoingMessage("chat", "after-archive", REQUEST_FINGERPRINT)).message.body as RichTextBody).markdown)
        }

        // The retained-copy bound must be released by disposition, not by clearing the active account.
        val current = File(source, "$users/$UID/${localCacheDatabaseFileName()}")
        val newlyCorrupt = "new-independent-corruption".encodeToByteArray()
        current.writeBytes(newlyCorrupt)
        withCache(source) { replacement ->
            assertFalse(replacement.chatDrafts.orphanSourceCleanupAllowed)
            assertNull(replacement.getOutgoingMessage("chat", "current-pending"))
        }
        val next = current.parentFile.parentFile.listFiles().orEmpty().single { it.name.startsWith("$UID.corrupt-") }
        assertContentEquals(newlyCorrupt, File(next, localCacheDatabaseFileName()).readBytes())
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `held client lease and unconfirmed manifest refuse before deleting any source`() = workspace { workspace ->
        val source = source(workspace)
        jvmFiles(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantineDatabase, archive)
        val before = inventory(source)
        val archiveBefore = inventory(archive)
        JvmClientDataLease.acquire(source).use {
            rejected("CLIENT_LOCK_UNAVAILABLE") {
                LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)
            }
            assertEquals(before, inventory(source))
        }
        val wrongDigest = (if (exported.manifestSha256.first() == '0') "1" else "0") + exported.manifestSha256.drop(1)
        rejected("ARCHIVE_CONFIRMATION_MISMATCH") {
            LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, wrongDigest)
        }
        assertEquals(before, inventory(source))
        assertEquals(archiveBefore, inventory(archive))
        JvmClientDataLease.acquire(source).close()
    }

    @Test
    fun `different owner layout and altered archive cannot authorize disposition`() = workspace { workspace ->
        val source = source(workspace)
        jvmFiles(source)
        val otherPath = "$users/bob.corrupt-retained/${localCacheDatabaseFileName()}"
        file(source, otherPath, PRIVATE_BYTES)
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantineDatabase, archive)
        val before = inventory(source)
        rejected("ARCHIVE_SELECTION_MISMATCH") {
            LocalCacheQuarantineDisposition.discard(source, otherPath, archive, exported.manifestSha256)
        }
        val androidPath = "databases/" + localCacheDatabaseFileName(fingerprint, DATASET, UID) + ".corrupt-retained"
        rejected("ARCHIVE_SELECTION_MISMATCH") {
            LocalCacheQuarantineDisposition.discard(source, androidPath, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
        }
        assertEquals(before, inventory(source))

        val archivedSource = File(archive, "payload/chat-assets/$fingerprint/$DATASET/$UID/source.blob")
        val original = archivedSource.readBytes()
        archivedSource.writeBytes(changed(original))
        rejected("ARCHIVE_INVALID_OR_CHANGED") {
            LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)
        }
        assertEquals(before, inventory(source), "full archive verification includes shared payloads even though only quarantine is deleted")
        archivedSource.writeBytes(original)
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `new or changed quarantine files reject the whole deletion`() = workspace { workspace ->
        val source = source(workspace)
        jvmFiles(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantineDatabase, archive)
        val extra = file(source, "$quarantineDirectory/new-local-fact", PRIVATE_BYTES)
        val withExtra = inventory(source)
        rejected("SOURCE_DOES_NOT_MATCH_ARCHIVE") {
            LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)
        }
        assertEquals(withExtra, inventory(source), "checking must precede deletion of any archived file")
        assertTrue(extra.delete())
        val selected = File(source, quarantineDatabase)
        selected.writeBytes(changed(selected.readBytes()))
        val withChanged = inventory(source)
        rejected("SOURCE_DOES_NOT_MATCH_ARCHIVE") {
            LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)
        }
        assertEquals(withChanged, inventory(source))
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `interrupted JVM deletion resumes from remaining files using the original archive`() = workspace { workspace ->
        val source = source(workspace)
        val selected = jvmFiles(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantineDatabase, archive)
        assertTrue(File(source, quarantineDatabase).delete())
        assertTrue(File(source, "$quarantineDatabase-wal").delete())
        assertTrue(File(source, quarantineDirectory).isDirectory)
        withCache(source) { assertFalse(it.chatDrafts.orphanSourceCleanupAllowed) }
        val remaining = selected.filterKeys { File(source, it).exists() }
        val before = inventory(source)

        val report = LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)

        assertReport(report, source, archive, exported.manifestSha256, remaining)
        assertFalse(report.alreadyAbsent)
        assertFalse(File(source, quarantineDirectory).exists())
        assertEquals(before.filterKeys { it !in remaining }, inventory(source))
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `empty retained directory and entirely absent quarantine finish idempotently`() = workspace { workspace ->
        val source = source(workspace)
        val selected = jvmFiles(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantineDatabase, archive)
        selected.keys.forEach { assertTrue(File(source, it).delete()) }
        withCache(source) { assertFalse(it.chatDrafts.orphanSourceCleanupAllowed) }
        val before = inventory(source)

        val empty = LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)

        assertReport(empty, source, archive, exported.manifestSha256, emptyMap())
        assertFalse(empty.alreadyAbsent, "the empty quarantine directory still existed before this call")
        assertFalse(File(source, quarantineDirectory).exists(), "an empty retained namespace must not keep orphan cleanup disabled")
        assertEquals(before, inventory(source))
        val absent = LocalCacheQuarantineDisposition.discard(source, quarantineDatabase, archive, exported.manifestSha256)
        assertReport(absent, source, archive, exported.manifestSha256, emptyMap())
        assertTrue(absent.alreadyAbsent)
        assertEquals(before, inventory(source))
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `Android exported layout deletes only selected family and resumes with sidecars alone`() = workspace { workspace ->
        val source = source(workspace, withLock = false)
        val base = "databases/" + localCacheDatabaseFileName(fingerprint, DATASET, UID)
        val quarantine = "$base.corrupt-retained"
        val selected = linkedMapOf<String, ByteArray>()
        for (suffix in listOf("", "-wal", "-shm", "-journal", ".open", ".integrity-checked", ".corruption-reported")) {
            selected[quarantine + suffix] = file(source, quarantine + suffix, ("retained" + suffix).encodeToByteArray()).readBytes()
        }
        file(source, base, PRIVATE_BYTES)
        file(source, "$base-wal", PRIVATE_BYTES)
        file(source, "$base.corrupt-neighbor", PRIVATE_BYTES)
        file(source, "databases/" + localCacheDatabaseFileName(fingerprint, DATASET, "bob") + ".corrupt-retained", PRIVATE_BYTES)
        file(source, "no_backup/chat-assets/$fingerprint/$DATASET/$UID/source.blob", PRIVATE_BYTES)
        val prefix = "no_backup/${DocumentDraftStoragePaths.ANDROID_DIRECTORY}/" +
            DocumentDraftStoragePaths.androidOwnerPrefix(fingerprint, DATASET, UID)
        file(source, "$prefix.manifest", PRIVATE_BYTES)
        file(source, "$prefix.tombstones.bak", PRIVATE_BYTES)
        file(source, "$prefix.${"a".repeat(64)}.record.new", PRIVATE_BYTES)
        file(source, "shared_prefs/teamtalk_document_drafts.xml", PRIVATE_BYTES)
        val archive = File(workspace, "archive")
        val exported = LocalCacheArchive.export(source, quarantine, archive, LocalCacheDiagnosticLayout.ANDROID)
        assertTrue(File(source, quarantine).delete()) // Android may retain only sidecars after interruption.
        val remaining = selected - quarantine
        val before = inventory(source)

        val report = LocalCacheQuarantineDisposition.discard(source, quarantine, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)

        assertReport(report, source, archive, exported.manifestSha256, remaining, quarantine, LocalCacheDiagnosticLayout.ANDROID)
        assertFalse(report.alreadyAbsent)
        assertEquals(before.filterKeys { it !in remaining }, inventory(source))
        assertFalse(File(source, ".lock").exists(), "an exported Android layout is not initialized as a JVM installation")
        val absent = LocalCacheQuarantineDisposition.discard(source, quarantine, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
        assertTrue(absent.alreadyAbsent)
        assertEquals(0, absent.removedFiles)
        assertEquals(0L, absent.removedBytes)
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    private fun jvmFiles(source: File): Map<String, ByteArray> {
        withCache(source) { cache ->
            cache.enqueueOutgoingMessage(message("current-pending", "current unsent content"), 1, REQUEST_FINGERPRINT)
        }
        val retained = linkedMapOf<String, ByteArray>()
        for (suffix in listOf("", "-wal", "-shm", "-journal", ".open", ".integrity-checked", ".corruption-reported")) {
            retained[quarantineDatabase + suffix] = file(source, quarantineDatabase + suffix, PRIVATE_BYTES).readBytes()
        }
        val oldEpoch = "$quarantineDirectory/cache_e7.db"
        retained[oldEpoch] = file(source, oldEpoch, PRIVATE_BYTES).readBytes()
        file(source, "$users/bob/${localCacheDatabaseFileName()}", PRIVATE_BYTES)
        file(source, "chat-assets/$fingerprint/$DATASET/$UID/source.blob", PRIVATE_BYTES)
        file(source, "chat-assets/$fingerprint/$DATASET/$UID/pending.partial", PRIVATE_BYTES)
        val docs = DocumentDraftStoragePaths.jvmDirectories(fingerprint, DATASET, UID).joinToString("/")
        file(source, "$docs/manifest", PRIVATE_BYTES)
        file(source, "$docs/record.payload", PRIVATE_BYTES)
        file(source, "$docs/.manifest-pending.tmp", PRIVATE_BYTES)
        return retained
    }

    private fun assertReport(report: LocalCacheQuarantineDiscardReport, source: File, archive: File, digest: String,
                             removed: Map<String, ByteArray>, path: String = quarantineDatabase,
                             layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM) {
        assertEquals(source.canonicalPath, File(report.root).canonicalPath)
        assertEquals(path, report.path)
        assertEquals(LocalCacheDiagnosticOwner(fingerprint, DATASET, UID), report.owner)
        assertEquals(layout, report.layout)
        assertEquals(archive.canonicalPath, File(report.archiveDirectory).canonicalPath)
        assertEquals(digest, report.manifestSha256)
        assertEquals(removed.size, report.removedFiles)
        assertEquals(removed.values.sumOf { it.size.toLong() }, report.removedBytes)
    }

    private fun withCache(source: File, block: (LocalCache) -> Unit) {
        val cache = createDesktopLocalCache(deployment, DATASET, UID, source)
        try { block(cache) } finally { cache.close() }
    }
    private fun message(id: String, text: String) = Message("chat", id, senderUid = UID,
        messageType = MessageType.RICH_TEXT.code, timestamp = 1, body = RichTextBody(text, plainText = text))
    private fun source(workspace: File, withLock: Boolean = true): File =
        JvmPrivateDataDirectory.createNew(File(workspace, "source"), workspace).root.toFile().also {
            AccountDataOwner(fingerprint, DATASET, UID)
            if (withLock) JvmClientDataLease.acquire(it).close()
        }
    private fun file(root: File, path: String, bytes: ByteArray): File {
        val parts = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(parts.dropLast(1), parts.last()).also { it.writeBytes(bytes) }
    }
    private fun changed(bytes: ByteArray) = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            root.toPath().relativize(path).toString().replace(File.separatorChar, '/') to hash(path.toFile().readBytes())
        }
    }
    private fun rejected(reason: String, block: () -> Unit) {
        val failure = assertFailsWith<IllegalStateException>(block = block)
        assertEquals("Local cache quarantine discard failed: $reason", failure.message)
    }
    private fun workspace(block: (File) -> Unit) {
        val workspace = Files.createTempDirectory("tk-quarantine-disposition-").toRealPath().toFile()
        try { block(workspace) } finally { workspace.deleteRecursively() }
    }
    private companion object {
        val deployment = DeploymentIdentity.from("quarantine-disposition.test.example", 5100, "https://quarantine-disposition.test.example/api")
        val fingerprint = deployment.fingerprint
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val UID = "alice"
        val users = "deployments/$fingerprint/datasets/$DATASET/users"
        val quarantineDirectory = "$users/$UID.corrupt-retained"
        val quarantineDatabase = "$quarantineDirectory/${localCacheDatabaseFileName()}"
        val PRIVATE_BYTES = byteArrayOf(0, 1, -1, 42, 7)
        val REQUEST_FINGERPRINT = byteArrayOf(3, 1, 4)
    }
}
