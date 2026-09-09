package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.*

/** 真实文件族验证保全包：不要求损坏资料可打开，也不能在导出或校验时重放、清理原资料。 */
class LocalCacheArchiveIntegrationTest {
    @Test
    fun `JVM quarantine export preserves exact bytes and verifies independently after relocation`() = workspace { workspace ->
        val source = source(workspace)
        val selected = jvmFiles(source)
        val neighbor = file(source, "$USERS/bob/cache_e0.db", PRIVATE_BYTES)
        file(source, "credentials", "not-an-archive-input".encodeToByteArray())
        val before = inventory(source)
        val destination = File(workspace, "archive")

        val exported = LocalCacheArchive.export(source, QUARANTINE_DATABASE, destination)

        assertPackage(destination, selected)
        assertEquals(before, inventory(source))
        assertFalse(File(destination, "payload/${relative(source, neighbor)}").exists())
        assertFalse(File(destination, "payload/credentials").exists())
        assertEquals(selected.size, exported.fileCount)
        assertEquals(selected.values.sumOf { it.size.toLong() }, exported.totalBytes)
        assertEquals(hash(File(destination, "manifest.json").readBytes()), exported.manifestSha256)

        val relocated = File(workspace, "relocated")
        Files.move(destination.toPath(), relocated.toPath())
        val retiredSource = File(workspace, "original-source-offline")
        Files.move(source.toPath(), retiredSource.toPath())
        val verified = LocalCacheArchive.verify(relocated)
        assertEquals(exported.manifestSha256, verified.manifestSha256)
        assertEquals(exported.fileCount, verified.fileCount)
        assertEquals(exported.totalBytes, verified.totalBytes)
        assertEquals(relocated.canonicalPath, File(verified.directory).canonicalPath)
        assertEquals(before, inventory(retiredSource), "verification needs neither the original path nor a running client")
    }

    @Test
    fun `missing replacement spool and document components are explicitly recorded`() = workspace { workspace ->
        val source = source(workspace)
        val retained = file(source, QUARANTINE_DATABASE, PRIVATE_BYTES)
        val before = inventory(source)
        val destination = File(workspace, "absent-components")

        LocalCacheArchive.export(source, QUARANTINE_DATABASE, destination)

        assertPackage(destination, mapOf(QUARANTINE_DATABASE to retained.readBytes()))
        val scopes = manifest(destination).getValue("scopes").jsonArray.map { it.jsonObject }
        assertEquals(4, scopes.size)
        assertEquals(1, scopes.count { it.getValue("present").jsonPrimitive.boolean })
        val absent = scopes.filterNot { it.getValue("present").jsonPrimitive.boolean }.map { it.getValue("path").jsonPrimitive.content }.toSet()
        assertEquals(setOf("$USERS/$UID", "chat-assets/$FINGERPRINT/$DATASET/$UID", documentDirectories().joinToString("/")), absent)
        assertEquals(before, inventory(source))
    }

    @Test
    fun `root lease missing lock and existing or nested destination are refused without source changes`() = workspace { workspace ->
        val source = source(workspace)
        file(source, QUARANTINE_DATABASE, PRIVATE_BYTES)
        val before = inventory(source)
        JvmClientDataLease.acquire(source).use {
            val destination = File(workspace, "busy")
            rejected("CLIENT_LOCK_UNAVAILABLE") { LocalCacheArchive.export(source, QUARANTINE_DATABASE, destination) }
            assertFalse(File(destination, "manifest.json").exists())
            assertEquals(before, inventory(source))
        }
        val existing = privateDirectory(workspace, "existing")
        file(existing, "keep", PRIVATE_BYTES)
        val existingBefore = inventory(existing)
        rejected("DESTINATION_EXISTS") { LocalCacheArchive.export(source, QUARANTINE_DATABASE, existing) }
        assertEquals(existingBefore, inventory(existing))
        val nested = File(source, "archive")
        rejected("DESTINATION_INSIDE_SOURCE") { LocalCacheArchive.export(source, QUARANTINE_DATABASE, nested) }
        assertFalse(nested.exists())
        assertEquals(before, inventory(source))
        assertTrue(File(source, ".lock").delete())
        val withoutLock = inventory(source)
        rejected("PATH_STATE_OR_IO_FAILURE") { LocalCacheArchive.export(source, QUARANTINE_DATABASE, File(workspace, "missing-lock")) }
        assertFalse(File(source, ".lock").exists(), "read-only preservation must not initialize a missing source lock")
        assertEquals(withoutLock, inventory(source))
    }

    @Test
    fun `verification refuses same length tampering missing extra and escaping payload paths`() = workspace { workspace ->
        val source = source(workspace)
        file(source, QUARANTINE_DATABASE, PRIVATE_BYTES)
        val before = inventory(source)
        val destination = File(workspace, "tampered")
        LocalCacheArchive.export(source, QUARANTINE_DATABASE, destination)
        val target = File(destination, "payload/$QUARANTINE_DATABASE")
        val original = target.readBytes()
        target.writeBytes(original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        rejected("PAYLOAD_DIGEST_MISMATCH") { LocalCacheArchive.verify(destination) }
        target.writeBytes(original)
        assertTrue(target.delete())
        rejected { LocalCacheArchive.verify(destination) }
        file(destination, "payload/$QUARANTINE_DATABASE", original)
        val extra = file(destination, "payload/unlisted", PRIVATE_BYTES)
        rejected { LocalCacheArchive.verify(destination) }
        assertTrue(extra.delete())

        val manifestFile = File(destination, "manifest.json")
        val originalManifest = manifestFile.readBytes()
        val parsed = manifest(destination)
        val first = parsed.getValue("files").jsonArray.first().jsonObject
        val escaped = JsonObject(first + ("path" to JsonPrimitive("../outside")))
        manifestFile.writeText(JsonObject(parsed + ("files" to JsonArray(listOf(escaped)))).toString())
        rejected { LocalCacheArchive.verify(destination) }
        manifestFile.writeBytes(originalManifest)
        LocalCacheArchive.verify(destination)
        assertEquals(before, inventory(source))
    }

    @Test
    fun `Android exported app data keeps only selected families plus shared draft preferences`() = workspace { workspace ->
        val source = source(workspace, withLock = false)
        val base = "databases/" + localCacheDatabaseFileName(FINGERPRINT, DATASET, UID)
        val quarantine = "$base.corrupt-retained"
        val selected = linkedMapOf<String, ByteArray>()
        fun add(path: String, bytes: ByteArray = PRIVATE_BYTES) { selected[path] = file(source, path, bytes).readBytes() }
        add(quarantine)
        add("$quarantine-wal", byteArrayOf(0, 1, -1))
        add("$quarantine.corruption-reported")
        val active = file(source, base, byteArrayOf())
        sqlite(active)
        selected[base] = active.readBytes()
        add("$base.open")
        add("no_backup/chat-assets/$FINGERPRINT/$DATASET/$UID/source.blob")
        val documentPrefix = "no_backup/${DocumentDraftStoragePaths.ANDROID_DIRECTORY}/" +
            DocumentDraftStoragePaths.androidOwnerPrefix(FINGERPRINT, DATASET, UID)
        add("$documentPrefix.manifest")
        add("$documentPrefix.tombstones.bak")
        add("$documentPrefix.${"a".repeat(64)}.record.new")
        add("shared_prefs/teamtalk_document_drafts.xml", "<map><string name=\"active_owner_hash\">owner-coordination</string></map>".encodeToByteArray())
        add("shared_prefs/teamtalk_document_drafts.xml.bak")
        val otherDocument = "no_backup/${DocumentDraftStoragePaths.ANDROID_DIRECTORY}/" +
            DocumentDraftStoragePaths.androidOwnerPrefix(FINGERPRINT, DATASET, "bob") + ".manifest"
        file(source, otherDocument, PRIVATE_BYTES)
        file(source, "databases/" + localCacheDatabaseFileName(FINGERPRINT, DATASET, "bob"), PRIVATE_BYTES)
        file(source, "shared_prefs/credentials.xml", PRIVATE_BYTES)
        val before = inventory(source)
        val destination = File(workspace, "android-archive")

        LocalCacheArchive.export(source, quarantine, destination, LocalCacheDiagnosticLayout.ANDROID)

        assertPackage(destination, selected, LocalCacheDiagnosticLayout.ANDROID)
        assertEquals(selected.size, LocalCacheArchive.verify(destination).fileCount)
        assertFalse(File(source, ".lock").exists())
        assertFalse(File(destination, "payload/$otherDocument").exists())
        assertEquals(before, inventory(source))

        // File-family presence means at least one listed file; it cannot survive an emptied inventory.
        val manifestFile = File(destination, "manifest.json")
        val originalManifest = manifestFile.readBytes()
        val parsed = manifest(destination)
        val entries = parsed.getValue("files").jsonArray
        for (category in listOf("ACTIVE_DATABASE", "DOCUMENT_DRAFTS", "DOCUMENT_OWNER_STATE")) {
            val remaining = entries.filterNot { it.jsonObject.getValue("category").jsonPrimitive.content == category }
            manifestFile.writeText(JsonObject(parsed + ("files" to JsonArray(remaining))).toString())
            rejected("MANIFEST_SCOPE_MISMATCH") { LocalCacheArchive.verify(destination) }
        }
        manifestFile.writeBytes(originalManifest)
        LocalCacheArchive.verify(destination)
    }

    @Test
    fun `linked source and incomplete manifest never become a verified archive`() = workspace { workspace ->
        val source = source(workspace)
        file(source, QUARANTINE_DATABASE, PRIVATE_BYTES)
        val external = file(workspace, "external", PRIVATE_BYTES)
        val linked = file(source, "chat-assets/$FINGERPRINT/$DATASET/$UID/linked.blob", byteArrayOf())
        Files.delete(linked.toPath())
        Files.createSymbolicLink(linked.toPath(), external.toPath())
        val before = inventory(source)
        val destination = File(workspace, "linked")
        rejected { LocalCacheArchive.export(source, QUARANTINE_DATABASE, destination) }
        assertFalse(File(destination, "manifest.json").exists())
        assertEquals(before, inventory(source))
        assertContentEquals(PRIVATE_BYTES, external.readBytes())

        val incomplete = privateDirectory(workspace, "incomplete")
        file(incomplete, "payload/$QUARANTINE_DATABASE", PRIVATE_BYTES)
        rejected { LocalCacheArchive.verify(incomplete) }
        file(incomplete, "manifest.json", "{\"formatVersion\":1,".encodeToByteArray())
        val incompleteBefore = inventory(incomplete)
        rejected { LocalCacheArchive.verify(incomplete) }
        assertEquals(incompleteBefore, inventory(incomplete), "verify never repairs incomplete packages")
    }

    private fun jvmFiles(source: File): Map<String, ByteArray> {
        val selected = linkedMapOf<String, ByteArray>()
        fun add(path: String, bytes: ByteArray = PRIVATE_BYTES) { selected[path] = file(source, path, bytes).readBytes() }
        add(QUARANTINE_DATABASE)
        listOf("-wal", "-shm", "-journal", ".open", ".integrity-checked", ".corruption-reported").forEach { add(QUARANTINE_DATABASE + it) }
        add("$QUARANTINE_DIRECTORY/cache_e7.db")
        val activePath = "$USERS/$UID/cache_e0.db"
        val active = file(source, activePath, byteArrayOf())
        sqlite(active)
        selected[activePath] = active.readBytes()
        add("$USERS/$UID/cache_e0.db.open")
        add("chat-assets/$FINGERPRINT/$DATASET/$UID/source.blob")
        add("chat-assets/$FINGERPRINT/$DATASET/$UID/uncommitted.partial")
        val docs = documentDirectories().joinToString("/")
        listOf("manifest", "tombstones", "record.payload", ".manifest-pending.tmp").forEach { add("$docs/$it") }
        return selected
    }

    private fun assertPackage(destination: File, expected: Map<String, ByteArray>, layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM) {
        val manifest = manifest(destination)
        assertEquals(1, manifest.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(layout.name, manifest.getValue("layout").jsonPrimitive.content)
        assertEquals(UID, manifest.getValue("owner").jsonObject.getValue("uid").jsonPrimitive.content)
        assertTrue(manifest.getValue("limitations").jsonArray.isNotEmpty(), "coverage and recovery limits must appear in serialized output")
        val entries = manifest.getValue("files").jsonArray.map { it.jsonObject }
        assertEquals(expected.keys, entries.map { it.getValue("path").jsonPrimitive.content }.toSet())
        assertEquals(expected.size, entries.size)
        for ((path, bytes) in expected) {
            assertContentEquals(bytes, File(destination, "payload/$path").readBytes(), path)
            val entry = entries.single { it.getValue("path").jsonPrimitive.content == path }
            assertEquals(bytes.size.toLong(), entry.getValue("bytes").jsonPrimitive.long)
            assertEquals(hash(bytes), entry.getValue("sha256").jsonPrimitive.content)
        }
        assertEquals(expected.keys.map { "payload/$it" }.toSet() + "manifest.json", inventory(destination).keys)
        LocalCacheArchive.verify(destination)
    }

    private fun sqlite(file: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try { AppDatabase.Schema.create(driver); driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0) }
        finally { driver.close() }
        DriverManager.getConnection("jdbc:sqlite:${file.toPath().toUri()}?mode=rw").use { db ->
            db.prepareStatement("INSERT INTO outgoing_message(client_msg_id,chat_id,sender_uid,payload,state,created_at,updated_at) VALUES ('stable','chat','alice',?,1,1,1)").use {
                it.setBytes(1, PRIVATE_BYTES); it.executeUpdate()
            }
        }
    }

    private fun manifest(directory: File) = Json.parseToJsonElement(File(directory, "manifest.json").readText()).jsonObject
    private fun documentDirectories() = DocumentDraftStoragePaths.jvmDirectories(FINGERPRINT, DATASET, UID)
    private fun source(workspace: File, withLock: Boolean = true): File = privateDirectory(workspace, "source").also {
        AccountDataOwner(FINGERPRINT, DATASET, UID) // Validate shared fixture identity before any rejection assertion.
        if (withLock) JvmClientDataLease.acquire(it).close()
    }
    private fun privateDirectory(parent: File, name: String) = JvmPrivateDataDirectory.createNew(File(parent, name), parent).root.toFile()
    private fun file(root: File, path: String, bytes: ByteArray): File {
        val parts = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(parts.dropLast(1), parts.last()).also { it.writeBytes(bytes) }
    }
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            root.toPath().relativize(path).toString().replace(File.separatorChar, '/') to
                if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}" else hash(path.toFile().readBytes())
        }
    }
    private fun rejected(reason: String? = null, block: () -> Unit) {
        val failure = assertFails(block = block)
        assertTrue(failure is IllegalStateException || failure is IllegalArgumentException, "unexpected failure: ${failure.javaClass.name}")
        if (reason != null) assertEquals(reason, failure.message?.removePrefix("Local cache archive failed: "))
    }
    private fun workspace(block: (File) -> Unit) {
        val workspace = Files.createTempDirectory("tk-cache-archive-").toRealPath().toFile()
        try { block(workspace) } finally { workspace.deleteRecursively() }
    }
    private companion object {
        const val FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val UID = "alice"
        const val USERS = "deployments/$FINGERPRINT/datasets/$DATASET/users"
        const val QUARANTINE_DIRECTORY = "$USERS/$UID.corrupt-retained"
        const val QUARANTINE_DATABASE = "$QUARANTINE_DIRECTORY/cache_e0.db"
        val PRIVATE_BYTES = byteArrayOf(0, 1, -1) + "private-source-content".encodeToByteArray()
    }
}
