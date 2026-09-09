package com.virjar.tk.shared.client

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.agent.AgentCredentials
import com.virjar.tk.shared.agent.HeadlessConfiguration
import com.virjar.tk.shared.agent.createAgentSecurityTestRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Exact-owner preservation and abandonment use real SQLite and private files, never a cache reset. */
class LocalCacheNamespaceIntegrationTest {
    @Test
    fun `JVM namespace archive and discard include all epochs and owned drafts while preserving neighbors`() = workspace { workspace ->
        val source = source(workspace)
        populateJvm(source)
        val before = inventory(source)
        val selected = before.filterKeys(::isJvmSelected)
        val archive = File(workspace, "archive")

        val exported = LocalCacheNamespaceArchive.export(source, owner, archive)

        assertEquals(before, inventory(source))
        assertEquals(selected, inventory(File(archive, "payload")))
        assertEquals(selected.size, exported.fileCount)
        assertEquals(selected.values.sumOf { it.bytes }, exported.totalBytes)
        assertEquals(exported, LocalCacheArchive.verify(archive))
        val manifest = Json.parseToJsonElement(File(archive, "manifest.json").readText()).jsonObject
        assertEquals("2", manifest.getValue("formatVersion").jsonPrimitive.content)
        assertEquals("NAMESPACE", manifest.getValue("purpose").jsonPrimitive.content)
        assertFalse("quarantinePath" in manifest)

        val discarded = LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)

        assertReport(discarded, source, archive, exported.manifestSha256, selected)
        assertFalse(discarded.alreadyAbsent)
        assertEquals(before.filterKeys { it !in selected }, inventory(source))
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `a namespace with only independent drafts is preserved without creating a database`() = workspace { workspace ->
        val source = source(workspace)
        file(source, "$documents/manifest", PRIVATE_BYTES)
        file(source, "$documents/record.payload", "unsaved independent document".encodeToByteArray())
        val selected = inventory(source).filterKeys { it.startsWith("$documents/") }
        val archive = File(workspace, "archive")
        val options = listOf("--cache-root", source.path, "--deployment-fingerprint", fingerprint,
            "--dataset-id", DATASET, "--uid", UID)
        HeadlessConfiguration.execute("export-namespace", options + listOf("--output", archive.path))
        val exported = LocalCacheArchive.verify(archive)
        assertEquals(selected, inventory(File(archive, "payload")))
        assertFalse(File(source, users).exists())

        HeadlessConfiguration.execute("discard-namespace", options + listOf("--archive", archive.path,
            "--confirm-manifest-sha256", exported.manifestSha256))

        assertTrue(inventory(source).keys.none { it in selected })
        assertFalse(File(source, users).exists())
        val again = LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        assertTrue(again.alreadyAbsent)
        assertEquals(0, again.removedFiles)
        assertEquals(0L, again.removedBytes)
        assertEquals(exported, LocalCacheArchive.verify(archive))
        val emptyOutput = File(workspace, "empty-namespace-archive")
        rejected("EMPTY_NAMESPACE_SCOPE") {
            LocalCacheNamespaceArchive.export(source, owner, emptyOutput)
        }
        assertFalse(emptyOutput.exists(), "an empty owner must be rejected before creating an output directory")
    }

    @Test
    fun `lease digest owner and archive purpose must match before any namespace deletion`() = workspace { workspace ->
        val source = source(workspace)
        populateJvm(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheNamespaceArchive.export(source, owner, archive)
        val quarantineArchive = File(workspace, "quarantine-archive")
        val quarantine = "$users/$UID.corrupt-retained/cache_e0.db"
        val preservedQuarantine = LocalCacheArchive.export(source, quarantine, quarantineArchive)
        val before = inventory(source)
        JvmClientDataLease.acquire(source).use {
            rejected("CLIENT_LOCK_UNAVAILABLE") {
                LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
            }
        }
        rejected("ARCHIVE_CONFIRMATION_MISMATCH") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, changedDigest(exported.manifestSha256))
        }
        rejected("ARCHIVE_SELECTION_MISMATCH") {
            LocalCacheNamespaceDisposition.discard(source, owner.copy(uid = "bob"), archive, exported.manifestSha256)
        }
        rejected("ARCHIVE_SELECTION_MISMATCH") {
            LocalCacheNamespaceDisposition.discard(source, owner, quarantineArchive, preservedQuarantine.manifestSha256)
        }
        val wrongPurpose = assertFailsWith<IllegalStateException> {
            LocalCacheQuarantineDisposition.discard(source, quarantine, archive, exported.manifestSha256)
        }
        assertTrue(wrongPurpose.message.orEmpty().endsWith("ARCHIVE_SELECTION_MISMATCH"))
        assertEquals(before, inventory(source))
        assertEquals(exported, LocalCacheArchive.verify(archive))
        assertEquals(preservedQuarantine, LocalCacheArchive.verify(quarantineArchive))
    }

    @Test
    fun `changed archive and new or changed source facts reject the entire deletion`() = workspace { workspace ->
        val source = source(workspace)
        populateJvm(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheNamespaceArchive.export(source, owner, archive)
        val before = inventory(source)
        val manifest = File(archive, "manifest.json")
        val originalManifest = manifest.readBytes()
        manifest.writeText("{}")
        assertFailsWith<IllegalStateException> {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        }
        assertEquals(before, inventory(source))
        manifest.writeBytes(originalManifest)
        val archivedBlob = File(archive, "payload/$spool/source.blob")
        val original = archivedBlob.readBytes()
        archivedBlob.writeBytes(changed(original))
        rejected("PAYLOAD_DIGEST_MISMATCH") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        }
        assertEquals(before, inventory(source))
        archivedBlob.writeBytes(original)

        // A new scope is unsafe even when it has no files yet; it is not an archived owner fact.
        val newQuarantine = JvmPrivateDataDirectory.openExisting(source)
            .ensureDirectory(("$users/$UID.corrupt-after-archive").split('/')).toFile()
        val withNewScope = inventory(source)
        rejected("SOURCE_DOES_NOT_MATCH_ARCHIVE") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        }
        assertEquals(withNewScope, inventory(source))
        assertTrue(newQuarantine.isDirectory)
        assertTrue(newQuarantine.delete())

        val extra = file(source, "$spool/new-source.blob", PRIVATE_BYTES)
        val withNewSource = inventory(source)
        rejected("SOURCE_DOES_NOT_MATCH_ARCHIVE") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        }
        assertEquals(withNewSource, inventory(source))
        assertTrue(extra.delete())
        val draft = File(source, "$documents/record.payload")
        draft.writeBytes(changed(draft.readBytes()))
        val withNewDraft = inventory(source)
        rejected("SOURCE_DOES_NOT_MATCH_ARCHIVE") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        }
        assertEquals(withNewDraft, inventory(source))
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `partial namespace deletion resumes from the same archive and ends idempotently`() = workspace { workspace ->
        val source = source(workspace)
        populateJvm(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheNamespaceArchive.export(source, owner, archive)
        assertTrue(File(source, "$users/$UID/cache_e0.db").delete())
        assertTrue(File(source, "$users/$UID.corrupt-retained/cache_e0.db").delete())
        assertTrue(File(source, "$spool/source.blob").delete())
        val before = inventory(source)
        val remaining = before.filterKeys(::isJvmSelected)

        val discarded = LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)

        assertReport(discarded, source, archive, exported.manifestSha256, remaining)
        assertFalse(discarded.alreadyAbsent)
        assertEquals(before.filterKeys { it !in remaining }, inventory(source))
        assertFalse(File(source, "$users/$UID.corrupt-retained").exists())
        val again = LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        assertTrue(again.alreadyAbsent)
        assertEquals(0, again.removedFiles)
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `current headless credentials protect the selected owner even after its archive was created`() = workspace(agentRoot = true) { workspace ->
        // The actual credential writer requires its dedicated, marked directory outside OS temp.
        val source = File(workspace, "source")
        val identity = AgentCredentials.ensureIdentity(source, deployment)
        JvmClientDataLease.acquire(source).close()
        populateJvm(source)
        val archive = File(workspace, "archive")
        val exported = LocalCacheNamespaceArchive.export(source, owner, archive)
        AgentCredentials.recordAuthentication(source, deployment, "fixture", identity.deviceId, UID, "fixture", "fixture-refresh-do-not-print")
        val before = inventory(source)

        rejected("NAMESPACE_REFERENCED_BY_CREDENTIALS") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256)
        }

        // Headless credentials do not persist a dataset, so the same fp + uid protects every dataset.
        val otherOwner = owner.copy(datasetId = OTHER_DATASET)
        val otherArchive = File(workspace, "other-dataset-archive")
        val otherExported = LocalCacheNamespaceArchive.export(source, otherOwner, otherArchive)
        rejected("NAMESPACE_REFERENCED_BY_CREDENTIALS") {
            LocalCacheNamespaceDisposition.discard(source, otherOwner, otherArchive, otherExported.manifestSha256)
        }

        assertEquals(before, inventory(source), "neither credentials nor account files can be removed while that login selects the owner")
        assertEquals(exported, LocalCacheArchive.verify(archive))
    }

    @Test
    fun `Android exported namespaces preserve shared preferences and neighbors while deleting all selected families`() = workspace { workspace ->
        val source = source(workspace, withLock = false)
        val database = "databases/" + localCacheDatabaseFileName(fingerprint, DATASET, UID)
        val oldDatabase = database.replace("cache_e0_", "cache_e7_")
        val selected = linkedSetOf<String>()
        for (base in listOf(database, oldDatabase, "$database.corrupt-retained")) {
            for (suffix in listOf("", "-wal", ".corruption-reported")) {
                selected += base + suffix
                file(source, base + suffix, (base + suffix).encodeToByteArray())
            }
        }
        val sourceBlob = "no_backup/$spool/source.blob"
        selected += sourceBlob
        file(source, sourceBlob, PRIVATE_BYTES)
        val prefix = "no_backup/${DocumentDraftStoragePaths.ANDROID_DIRECTORY}/" +
            DocumentDraftStoragePaths.androidOwnerPrefix(fingerprint, DATASET, UID)
        for (suffix in listOf(".manifest", ".manifest.bak", ".tombstones", ".${"a".repeat(64)}.record.new")) {
            selected += prefix + suffix
            file(source, prefix + suffix, PRIVATE_BYTES)
        }
        val sharedPreferences = "shared_prefs/teamtalk_document_drafts.xml"
        file(source, sharedPreferences, "<map/>".encodeToByteArray())
        file(source, "$sharedPreferences.bak", "<map/>".encodeToByteArray())
        file(source, "databases/" + localCacheDatabaseFileName(fingerprint, OTHER_DATASET, UID), PRIVATE_BYTES)
        file(source, "databases/" + localCacheDatabaseFileName(fingerprint, DATASET, "bob"), PRIVATE_BYTES)
        val otherPrefix = DocumentDraftStoragePaths.androidOwnerPrefix(fingerprint, DATASET, "bob")
        file(source, "no_backup/${DocumentDraftStoragePaths.ANDROID_DIRECTORY}/$otherPrefix.manifest", PRIVATE_BYTES)
        file(source, "no_backup/document-drafts-v1/legacy.json", PRIVATE_BYTES)
        val beforeExport = inventory(source)
        val archive = File(workspace, "archive")

        val exported = LocalCacheNamespaceArchive.export(source, owner, archive, LocalCacheDiagnosticLayout.ANDROID)

        assertEquals(beforeExport, inventory(source))
        assertEquals(beforeExport.filterKeys { it in selected || it == sharedPreferences || it == "$sharedPreferences.bak" }, inventory(File(archive, "payload")))
        val auth = "shared_prefs/teamtalk_auth.xml"
        for (invalid in listOf("<map>garbage</map>", "<map><long name=\"owner_generation\" value=\"1\">garbage</long></map>")) {
            file(source, auth, invalid.encodeToByteArray())
            val beforeRefusal = inventory(source)
            rejected("CREDENTIALS_UNREADABLE_OR_INVALID") {
                LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
            }
            assertEquals(beforeRefusal, inventory(source))
        }
        val activeAuth = """<map><long name="owner_generation" value="1"/><string name="deployment_fingerprint">$fingerprint</string><string name="uid">$UID</string><string name="refresh_token">fixture-refresh-do-not-print</string><string name="dataset_id">$DATASET</string></map>"""
        file(source, auth, activeAuth.encodeToByteArray())
        var beforeRefusal = inventory(source)
        rejected("NAMESPACE_REFERENCED_BY_CREDENTIALS") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
        }
        assertEquals(beforeRefusal, inventory(source))
        file(source, "$auth.bak", activeAuth.encodeToByteArray())
        file(source, auth, "<map/>".encodeToByteArray())
        beforeRefusal = inventory(source)
        rejected("NAMESPACE_REFERENCED_BY_CREDENTIALS") {
            LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
        }
        assertEquals(beforeRefusal, inventory(source))
        assertTrue(File(source, "$auth.bak").delete())
        // Installation-wide document ownership can advance; it is preserved and never a deletion input.
        val currentPreferences = "<map><string name=\"active_owner_hash\">another-owner</string></map>".encodeToByteArray()
        File(source, sharedPreferences).writeBytes(currentPreferences)
        // Reuse the original archive after an interrupted deletion left just a database sidecar.
        assertTrue(File(source, "$database.corrupt-retained").delete())
        assertTrue(File(source, sourceBlob).delete())
        val before = inventory(source)
        val remaining = before.filterKeys { it in selected }
        val discarded = LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
        assertReport(discarded, source, archive, exported.manifestSha256, remaining, LocalCacheDiagnosticLayout.ANDROID)
        assertEquals(before.filterKeys { it !in selected }, inventory(source))
        assertContentEquals(currentPreferences, File(source, sharedPreferences).readBytes())
        assertFalse(File(source, ".lock").exists())
        assertEquals(exported, LocalCacheArchive.verify(archive))
        val again = LocalCacheNamespaceDisposition.discard(source, owner, archive, exported.manifestSha256, LocalCacheDiagnosticLayout.ANDROID)
        assertTrue(again.alreadyAbsent)
        val emptyOutput = File(workspace, "empty-namespace-archive")
        rejected("EMPTY_NAMESPACE_SCOPE") {
            LocalCacheNamespaceArchive.export(source, owner, emptyOutput, LocalCacheDiagnosticLayout.ANDROID)
        }
        assertFalse(emptyOutput.exists(), "installation-wide preferences alone do not make an owner archive")
        assertContentEquals(currentPreferences, File(source, sharedPreferences).readBytes())
    }

    private fun populateJvm(source: File) {
        val cache = createDesktopLocalCache(deployment, DATASET, UID, source)
        try {
            cache.bindSyncDataset(DATASET)
            cache.enqueueOutgoingMessage(Message("chat", "pending", senderUid = UID,
                messageType = MessageType.RICH_TEXT.code, timestamp = 1,
                body = RichTextBody("private pending body", plainText = "private pending body")), 1)
            cache.chatDrafts.save(ChatDraftSnapshot("chat", revision = 1, markdown = "private unsent draft"))
        } finally { cache.close() }
        file(source, "$users/$UID/cache_e7.db", PRIVATE_BYTES)
        file(source, "$users/$UID/cache_e7.db-journal", PRIVATE_BYTES)
        file(source, "$users/$UID.corrupt-retained/cache_e0.db", PRIVATE_BYTES)
        file(source, "$users/$UID.corrupt-retained/cache_e0.db-wal", PRIVATE_BYTES)
        file(source, "$spool/source.blob", PRIVATE_BYTES)
        file(source, "$spool/upload.partial", PRIVATE_BYTES)
        file(source, "$documents/manifest", PRIVATE_BYTES)
        file(source, "$documents/record.payload", PRIVATE_BYTES)
        for (neighbor in listOf(owner.copy(uid = "bob"), owner.copy(datasetId = OTHER_DATASET), owner.copy(deploymentFingerprint = "b".repeat(64)))) {
            val namespace = "deployments/${neighbor.deploymentFingerprint}/datasets/${neighbor.datasetId}/users/${neighbor.uid}"
            file(source, "$namespace/cache_e0.db", PRIVATE_BYTES)
            file(source, "chat-assets/${neighbor.deploymentFingerprint}/${neighbor.datasetId}/${neighbor.uid}/source.blob", PRIVATE_BYTES)
            val neighborDocs = DocumentDraftStoragePaths.jvmDirectories(neighbor.deploymentFingerprint, neighbor.datasetId, neighbor.uid).joinToString("/")
            file(source, "$neighborDocs/record.payload", PRIVATE_BYTES)
        }
        file(source, "document-drafts-v1/legacy.json", PRIVATE_BYTES)
        file(source, "media-cache/keep.bin", PRIVATE_BYTES)
    }

    private fun isJvmSelected(path: String) = path.startsWith("$users/$UID/") ||
        path.startsWith("$users/$UID.corrupt-retained/") || path.startsWith("$spool/") || path.startsWith("$documents/")

    private fun assertReport(report: LocalCacheNamespaceDiscardReport, source: File, archive: File, digest: String,
                             removed: Map<String, FileState>, layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM) {
        assertEquals(source.canonicalPath, File(report.root).canonicalPath)
        assertEquals(owner, report.owner)
        assertEquals(layout, report.layout)
        assertEquals(archive.canonicalPath, File(report.archiveDirectory).canonicalPath)
        assertEquals(digest, report.manifestSha256)
        assertEquals(removed.size, report.removedFiles)
        assertEquals(removed.values.sumOf { it.bytes }, report.removedBytes)
        assertTrue(report.consequence.isNotBlank())
    }

    private data class FileState(val bytes: Long, val sha256: String)
    private fun inventory(root: File): Map<String, FileState> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            val bytes = path.toFile().readBytes()
            root.toPath().relativize(path).toString().replace(File.separatorChar, '/') to FileState(bytes.size.toLong(), hash(bytes))
        }
    }
    private fun source(workspace: File, withLock: Boolean = true): File =
        JvmPrivateDataDirectory.createNew(File(workspace, "source"), workspace).root.toFile().also {
            AccountDataOwner(fingerprint, DATASET, UID)
            if (withLock) JvmClientDataLease.acquire(it).close()
        }
    private fun file(root: File, path: String, bytes: ByteArray): File {
        val parts = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(parts.dropLast(1), parts.last()).also { it.writeBytes(bytes) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun changed(bytes: ByteArray) = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    private fun changedDigest(digest: String) = (if (digest.first() == '0') "1" else "0") + digest.drop(1)
    private fun rejected(reason: String, block: () -> Unit) {
        val failure = assertFailsWith<IllegalStateException>(block = block)
        assertEquals("Local cache archive failed: $reason", failure.message)
    }
    private fun workspace(agentRoot: Boolean = false, block: (File) -> Unit) {
        val root = if (agentRoot) createAgentSecurityTestRoot("tk-namespace-")
            else Files.createTempDirectory("tk-namespace-").toRealPath().toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private companion object {
        val deployment = DeploymentIdentity.from("namespace.test.example", 5100, "https://namespace.test.example/api")
        val fingerprint = deployment.fingerprint
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val OTHER_DATASET = "22222222-2222-4222-8222-222222222222"
        const val UID = "alice"
        val owner = LocalCacheDiagnosticOwner(fingerprint, DATASET, UID)
        val users = "deployments/$fingerprint/datasets/$DATASET/users"
        val spool = "chat-assets/$fingerprint/$DATASET/$UID"
        val documents = DocumentDraftStoragePaths.jvmDirectories(fingerprint, DATASET, UID).joinToString("/")
        val PRIVATE_BYTES = byteArrayOf(0, 1, -1, 42, 7)
    }
}
