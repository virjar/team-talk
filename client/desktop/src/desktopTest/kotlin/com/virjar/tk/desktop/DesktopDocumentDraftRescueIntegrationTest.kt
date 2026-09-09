package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadStatus
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecord
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_MANIFEST_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORD_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORDS
import com.virjar.tk.app.navigation.feature.document.MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.DocumentDraftStoragePaths
import com.virjar.tk.shared.client.JvmClientDataLease
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.shared.client.LocalCacheArchive
import com.virjar.tk.shared.client.LocalCacheArchiveReport
import com.virjar.tk.shared.client.LocalCacheDiagnosticOwner
import com.virjar.tk.shared.client.LocalCacheNamespaceArchive
import com.virjar.tk.shared.client.createDesktopLocalCache
import com.virjar.tk.shared.client.prepareJvmClientDataVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real archive, SQLite and product Desktop record format, without starting an application owner. */
class DesktopDocumentDraftRescueIntegrationTest {
    @Test
    fun `space rescue publishes the exact space intent with its own drafts and admitted children`() = workspace { workspace ->
        for (withChildren in listOf(false, true)) {
            val scenario = directory(workspace, "space-$withChildren")
            val source = root(scenario, "source")
            val spaceKey = "space-command-$SPACE"
            val pending = buildJsonArray {
                add(buildJsonObject { put("name", PRIVATE_TITLE); put("description", "private space description"); put("spaceId", SPACE) })
            }
            val selected = tab(creating = true, title = "")
            val ordinary = tab(recoveryId = OTHER_RECOVERY, instanceId = 12, documentId = OTHER_DOCUMENT)
            val childId = "12345678-0000-4000-8000-000000000001"
            val grandchildId = "12345678-0000-4000-8000-000000000002"
            fun nestedTab(id: String, instance: Long, parent: String, ancestors: List<String>) = JsonObject(
                tab(recoveryId = id, instanceId = instance, documentId = id, creating = true) + mapOf(
                    "parentId" to JsonPrimitive(parent), "ancestorIds" to JsonArray(ancestors.map(::JsonPrimitive))))
            fun nestedCommand(id: String, instance: Long, parent: String) = JsonObject(createCommand() + mapOf(
                "documentId" to JsonPrimitive(id), "tabInstanceId" to JsonPrimitive(instance), "parentId" to JsonPrimitive(parent)))
            val tabs = if (withChildren) listOf(
                nestedTab(grandchildId, 14, childId, listOf(DOCUMENT, childId)),
                nestedTab(childId, 13, DOCUMENT, listOf(DOCUMENT)), selected, ordinary,
            ) else emptyList()
            val commands = if (withChildren) listOf(nestedCommand(grandchildId, 14, childId),
                nestedCommand(childId, 13, DOCUMENT), createCommand()) else emptyList()
            storage(source).replace(payload(tabs, pendingSpaces = pending, createCommands = commands), LIMITS)
            val archived = archive(scenario, source)
            val target = root(scenario, "target")
            val sourceBefore = inventory(source)
            val archiveBefore = inventory(archived.directory)
            val targetBefore = inventory(target)
            assertEquals(listOf(spaceKey), DesktopDocumentSpaceCreateRescue.listRecords(archived.directory).recordKeys)
            val preview = DesktopDocumentSpaceCreateRescue.preview(target, DATABASE, archived.directory, spaceKey)
            assertEquals(SPACE, preview.selection.spaceId)
            assertEquals(tabs.size, preview.selection.tabCount)
            assertEquals(commands.size, preview.selection.pendingDocumentCount)
            assertEquals(targetBefore, inventory(target))
            assertFalse(Json.encodeToString(preview).contains(PRIVATE_TITLE))
            assertFalse(Json.encodeToString(preview).contains(PRIVATE_BODY))
            val base = arrayOf("--cache-root", target.path, "--database", DATABASE, "--archive", archived.directory.path, "--record-key", spaceKey)
            val output = mutableListOf<String>()
            assertEquals(2, runDesktopDocumentDraftRescueCommand(arrayOf("import-document-space-create-rescue") + base, output::add))
            assertEquals(targetBefore, inventory(target))
            assertEquals(0, runDesktopDocumentDraftRescueCommand(arrayOf("import-document-space-create-rescue") + base + arrayOf(
                "--confirm-manifest-sha256", preview.manifestSha256, "--expected-target-state-sha256", preview.targetStateSha256,
            ), output::add))
            assertEquals(DesktopDocumentDraftStorageReadStatus.AVAILABLE, storage(target).read(LIMITS) { restored ->
                val manifest = Json.parseToJsonElement(restored.manifest).jsonObject
                assertEquals(pending, manifest.getValue("pendingSpaceCreates"))
                assertEquals(tabs.size, manifest.getValue("tabRecordKeys").jsonArray.size)
                for (tab in tabs) {
                    val key = "tab-" + (tab.getValue("recoveryId") as JsonPrimitive).content
                    assertEquals(tab, Json.parseToJsonElement(assertNotNull(restored.readRecord(key))))
                }
                assertEquals(commands.size, manifest.getValue("pendingDocumentRecordKeys").jsonArray.size)
                for (command in commands) {
                    val id = (command.getValue("documentId") as JsonPrimitive).content
                    assertEquals(command, Json.parseToJsonElement(assertNotNull(restored.readRecord("document-command-$id"))))
                }
            })
            assertNoCommands(target)
            assertEquals(sourceBefore, inventory(source))
            assertEquals(archiveBefore, inventory(archived.directory))
            failure("TARGET_DOCUMENT_DRAFTS_NOT_EMPTY") {
                DesktopDocumentSpaceCreateRescue.importRecords(target, DATABASE, archived.directory, spaceKey,
                    preview.manifestSha256, preview.targetStateSha256)
            }
        }
    }

    @Test
    fun `explicit create rescue restores the original admitted request and later blank title edits as one pair`() = workspace { workspace ->
        for (quarantine in listOf(true, false)) {
            val scenario = directory(workspace, "create-$quarantine")
            val source = root(scenario, "source")
            val selected = tab(creating = true, title = "")
            val command = createCommand()
            val other = tab(recoveryId = OTHER_RECOVERY, instanceId = 12, documentId = OTHER_DOCUMENT)
            storage(source).replace(payload(listOf(selected, other), command = command), LIMITS)
            if (quarantine) corruptUnrelatedMessageIndex(source)
            val archived = archive(scenario, source, quarantine)
            val target = root(scenario, "target")
            val sourceBefore = inventory(source)
            val archiveBefore = inventory(archived.directory)
            val targetBefore = inventory(target)
            assertEquals(listOf(RECORD_KEY), DesktopDocumentDraftRescue.listRecords(archived.directory, DesktopDocumentRescueKind.CREATE).recordKeys)
            val preview = DesktopDocumentDraftRescue.preview(target, DATABASE, archived.directory, RECORD_KEY, DesktopDocumentRescueKind.CREATE)
            assertEquals(targetBefore, inventory(target))
            assertEquals(DOCUMENT, preview.documentId)
            assertEquals("document-command-$DOCUMENT", preview.commandRecordKey)
            assertEquals(2L, preview.admittedEditGeneration)
            assertEquals(3L, preview.currentEditGeneration)
            assertTrue(preview.consequence.contains("retries that exact request"))
            assertFalse(Json.encodeToString(preview).contains(PRIVATE_BODY))
            assertFalse(Json.encodeToString(preview).contains(PRIVATE_TITLE))
            failure("Document draft rescue failed: PENDING_OPERATIONS") { preview(target, archived) }
            val base = arrayOf("--cache-root", target.path, "--database", DATABASE, "--archive", archived.directory.path, "--record-key", RECORD_KEY)
            val output = mutableListOf<String>()
            assertEquals(2, runDesktopDocumentDraftRescueCommand(arrayOf("import-document-create-rescue") + base, output::add))
            assertEquals(targetBefore, inventory(target))
            assertEquals(0, runDesktopDocumentDraftRescueCommand(arrayOf("import-document-create-rescue") + base + arrayOf(
                "--confirm-manifest-sha256", preview.manifestSha256, "--expected-target-state-sha256", preview.targetStateSha256,
            ), output::add))
            assertEquals(DesktopDocumentDraftStorageReadStatus.AVAILABLE, storage(target).read(LIMITS) { restored ->
                val manifest = Json.parseToJsonElement(restored.manifest).jsonObject
                assertEquals(JsonArray(listOf(JsonPrimitive(RECORD_KEY))), manifest.getValue("tabRecordKeys"))
                assertEquals(JsonArray(listOf(JsonPrimitive("document-command-$DOCUMENT"))), manifest.getValue("pendingDocumentRecordKeys"))
                assertEquals(selected, Json.parseToJsonElement(assertNotNull(restored.readRecord(RECORD_KEY))))
                assertEquals(command, Json.parseToJsonElement(assertNotNull(restored.readRecord("document-command-$DOCUMENT"))))
            })
            assertNoCommands(target)
            assertEquals(sourceBefore, inventory(source))
            assertEquals(archiveBefore, inventory(archived.directory))
            failure("TARGET_DOCUMENT_DRAFTS_NOT_EMPTY") {
                DesktopDocumentDraftRescue.importDraft(target, DATABASE, archived.directory, RECORD_KEY,
                    preview.manifestSha256, preview.targetStateSha256, DesktopDocumentRescueKind.CREATE)
            }
        }
    }

    @Test
    fun `verified v1 and v2 archives restore only the selected tab with its exact baseline and assets`() = workspace { workspace ->
        for (quarantine in listOf(true, false)) {
            val scenario = directory(workspace, "format-$quarantine")
            val source = root(scenario, "source")
            val selected = tab(withAsset = true)
            val other = tab(recoveryId = OTHER_RECOVERY, instanceId = 12, documentId = OTHER_DOCUMENT)
            write(source, listOf(selected, other))
            if (quarantine) corruptUnrelatedMessageIndex(source)
            val archived = archive(scenario, source, quarantine)
            val target = root(scenario, "target")
            val sourceBefore = inventory(source)
            val targetBefore = inventory(target)
            val archiveBefore = inventory(archived.directory)
            val listed = DesktopDocumentDraftRescue.listRecords(archived.directory)
            assertEquals(OWNER, listed.owner)
            assertEquals(archived.report.manifestSha256, listed.manifestSha256)
            assertEquals(setOf(RECORD_KEY, "tab-$OTHER_RECOVERY"), listed.recordKeys.toSet())
            val preview = preview(target, archived)
            assertEquals(targetBefore, inventory(target), "preview must not publish or clean target records")
            assertEquals(OWNER, preview.owner)
            assertEquals(DOCUMENT, preview.documentId)
            assertEquals(7L, preview.baseRevision)
            assertEquals(1, preview.attachmentCount)
            assertFalse(preview.creating)
            val reportJson = Json.encodeToString(preview)
            assertFalse(reportJson.contains(PRIVATE_BODY))
            assertFalse(reportJson.contains(PRIVATE_TITLE))
            install(target, archived, preview)
            assertEquals(selected, readSelected(target), "saved and edited content, local identities and base revision must survive")
            assertNoCommands(target)
            assertEquals(sourceBefore, inventory(source))
            assertEquals(archiveBefore, inventory(archived.directory))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            assertTrue(File(source, DATABASE).isFile)
        }
    }

    @Test
    fun `an unsubmitted blank title draft survives a clean deleted target and a second persistence owner`() = workspace { workspace ->
        val source = root(workspace, "source")
        val selected = tab(creating = true, title = "")
        write(source, listOf(selected))
        val archived = archive(workspace, source)
        val target = root(workspace, "target")
        storage(target).delete(LIMITS)
        val neighborOwner = OWNER.copy(uid = "bob")
        desktopDocumentDraftStorage(target, neighborOwner.draftOwner()).replace(payload(listOf(tab(title = "neighbor"))), LIMITS)
        val neighborDirectory = File(target, documentDirectory(neighborOwner))
        val neighborBefore = inventory(neighborDirectory)
        val preview = preview(target, archived)
        assertTrue(preview.creating)
        assertNull(preview.documentId)
        assertNull(preview.baseRevision)
        install(target, archived, preview)
        assertEquals(selected, readSelected(target))
        assertEquals(selected, readSelected(target), "a separately acquired persistence owner must recover the same unsaved tab")
        assertNoCommands(target)
        assertEquals(neighborBefore, inventory(neighborDirectory))
    }

    @Test
    fun `a live target draft and a changed empty target state never get overwritten`() = workspace { workspace ->
        val source = root(workspace, "source")
        write(source, listOf(tab()))
        val archived = archive(workspace, source)
        val target = root(workspace, "target")
        val emptyPreview = preview(target, archived)
        write(target, listOf(tab(title = "newer target draft")))
        val nonempty = inventory(target)
        failure("TARGET_DOCUMENT_DRAFTS_NOT_EMPTY") { preview(target, archived) }
        failure("TARGET_DOCUMENT_DRAFTS_NOT_EMPTY") { install(target, archived, emptyPreview) }
        assertEquals(nonempty, inventory(target))
        storage(target).delete(LIMITS)
        val deleted = inventory(target)
        failure("TARGET_DOCUMENT_DRAFTS_CHANGED") { install(target, archived, emptyPreview) }
        assertEquals(deleted, inventory(target))
        val current = preview(target, archived)
        assertNotEquals(emptyPreview.targetStateSha256, current.targetStateSha256)
        install(target, archived, current)
        assertEquals(tab(), readSelected(target))
    }

    @Test
    fun `retired damaged unfinished and command dependent records cannot be selected as an ordinary draft`() = workspace { workspace ->
        val cases = listOf(
            "tombstone" to "Document draft rescue failed: RECORD_UNAVAILABLE_OR_RETIRED",
            "record-digest" to "Document draft rescue failed: RECORD_UNAVAILABLE",
            "pending-space" to "Document draft rescue failed: PENDING_OPERATIONS",
            "unfinished-asset" to "Document draft rescue failed: UNREADABLE_OR_INVALID_SOURCE",
        )
        for ((kind, reason) in cases) {
            val scenario = directory(workspace, kind)
            val source = root(scenario, "source")
            val selected = tab(withAsset = kind == "unfinished-asset").let { original ->
                if (kind == "unfinished-asset") JsonObject(original + ("draftAssets" to JsonArray(emptyList()))) else original
            }
            val pending = if (kind == "pending-space") buildJsonArray {
                add(buildJsonObject { put("name", "pending space"); put("description", JsonNull); put("spaceId", SPACE) })
            } else JsonArray(emptyList())
            storage(source).replace(payload(listOf(selected), pendingSpaces = pending), LIMITS)
            if (kind == "tombstone") storage(source).tombstone(setOf(RECORD_KEY), LIMITS)
            if (kind == "record-digest") {
                val records = File(source, documentDirectory()).listFiles().orEmpty().filter { it.name.startsWith("record-") }
                assertEquals(1, records.size)
                records.single().writeText("damaged private record")
            }
            val archived = archive(scenario, source)
            val target = root(scenario, "target")
            val beforeSource = inventory(source)
            val beforeTarget = inventory(target)
            failure(reason) { preview(target, archived) }
            assertEquals(beforeSource, inventory(source))
            assertEquals(beforeTarget, inventory(target))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
        }
    }

    @Test
    fun `an empty replacement database cannot hide a quarantined move and target moves also refuse import`() = workspace { workspace ->
        for (pendingInSource in listOf(true, false)) {
            val scenario = directory(workspace, "pending-source-$pendingInSource")
            val source = root(scenario, "source")
            write(source, listOf(tab()))
            if (pendingInSource) {
                privateFile(source, QUARANTINE, File(source, DATABASE).readBytes())
                insertMove(File(source, QUARANTINE))
                assertEquals(0L, scalar(File(source, DATABASE), "SELECT count(*) FROM pending_document_move_command"))
            }
            val archived = archive(scenario, source)
            val target = root(scenario, "target")
            if (!pendingInSource) insertMove(File(target, DATABASE))
            val beforeSource = inventory(source)
            val beforeTarget = inventory(target)
            val code = if (pendingInSource) "SOURCE_DOCUMENT_OPERATION_PENDING" else "TARGET_DOCUMENT_OPERATION_PENDING"
            failure("Local cache document draft rescue failed: $code") { preview(target, archived) }
            assertEquals(beforeSource, inventory(source))
            assertEquals(beforeTarget, inventory(target))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
        }
    }

    @Test
    fun `owner lease digest and CLI confirmation guards leave existing data and default initialization untouched`() = workspace { workspace ->
        val source = root(workspace, "source")
        write(source, listOf(tab()))
        val archived = archive(workspace, source)
        val target = root(workspace, "target")
        val preview = preview(target, archived)
        val beforeTarget = inventory(target)
        JvmClientDataLease.acquire(target).use {
            failure("Local cache document draft rescue failed: CLIENT_LOCK_UNAVAILABLE") { preview(target, archived) }
        }
        failure("Local cache document draft rescue failed: OWNER_MISMATCH") {
            DesktopDocumentDraftRescue.preview(target, database(OWNER.copy(uid = "bob")), archived.directory, RECORD_KEY)
        }
        failure("Local cache document draft rescue failed: ARCHIVE_CONFIRMATION_MISMATCH") {
            DesktopDocumentDraftRescue.importDraft(target, DATABASE, archived.directory, RECORD_KEY, "0".repeat(64), preview.targetStateSha256)
        }
        assertEquals(beforeTarget, inventory(target))
        val defaultProperty = System.getProperty("teamtalk.data.dir")
        val missingRoot = File(workspace, "must-not-create")
        val lines = mutableListOf<String>()
        val base = arrayOf("--cache-root", missingRoot.path, "--database", DATABASE, "--archive", archived.directory.path, "--record-key", RECORD_KEY)
        val invalid = listOf(
            arrayOf("preview-document-draft-rescue"),
            arrayOf("preview-document-draft-rescue") + base + arrayOf("--cache-root", missingRoot.path),
            arrayOf("import-document-draft-rescue") + base,
            arrayOf("import-document-draft-rescue") + base + arrayOf("--confirm-manifest-sha256", "invalid", "--expected-target-state-sha256", "0".repeat(64)),
        )
        invalid.forEach { arguments -> assertEquals(2, runDesktopDocumentDraftRescueCommand(arguments, lines::add)) }
        assertNull(runDesktopDocumentDraftRescueCommand(emptyArray(), lines::add))
        assertFalse(missingRoot.exists())
        assertEquals(defaultProperty, System.getProperty("teamtalk.data.dir"))
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { PRIVATE_BODY in it || PRIVATE_TITLE in it || archived.directory.path in it })
        assertEquals(beforeTarget, inventory(target))
    }

    private data class Archived(val directory: File, val report: LocalCacheArchiveReport)

    private fun preview(root: File, archive: Archived) = DesktopDocumentDraftRescue.preview(root, DATABASE, archive.directory, RECORD_KEY)
    private fun install(root: File, archive: Archived, preview: DesktopDocumentDraftRescuePreview) =
        DesktopDocumentDraftRescue.importDraft(root, DATABASE, archive.directory, RECORD_KEY, preview.manifestSha256, preview.targetStateSha256)

    private fun root(parent: File, name: String): File = directory(parent, name).also { root ->
        JvmClientDataLease.acquire(root).use { prepareJvmClientDataVersion(root) }
        val cache = createDesktopLocalCache(DEPLOYMENT, DATASET, UID, root)
        try {
            cache.bindSyncDataset(DATASET)
            cache.insertMessage(Message("unrelated-chat", "unrelated-message", serverSeq = 1, senderUid = UID,
                timestamp = 1, messageType = MessageType.RICH_TEXT.code, body = RichTextBody("unrelated", plainText = "unrelated")))
        } finally { cache.close() }
    }

    private fun archive(parent: File, source: File, quarantine: Boolean = false): Archived {
        val destination = File(parent, "archive")
        val report = if (quarantine) {
            privateFile(source, QUARANTINE, File(source, DATABASE).readBytes())
            LocalCacheArchive.export(source, QUARANTINE, destination)
        } else LocalCacheNamespaceArchive.export(source, OWNER, destination)
        return Archived(destination, report)
    }

    private fun storage(root: File) = desktopDocumentDraftStorage(root, OWNER.draftOwner())
    private fun write(root: File, tabs: List<JsonObject>) = storage(root).replace(payload(tabs), LIMITS)

    private fun readSelected(root: File): JsonObject {
        val persistence = DesktopDocumentDraftPersistence(root, OWNER.draftOwner())
        var result: JsonObject? = null
        try {
            assertEquals(DocumentDraftReadStatus.AVAILABLE, persistence.read(OWNER.draftOwner()) { source ->
                val manifest = Json.parseToJsonElement(source.manifest).jsonObject
                assertEquals(JsonArray(listOf(JsonPrimitive(RECORD_KEY))), manifest.getValue("tabRecordKeys"))
                listOf("pendingDocumentRecordKeys", "pendingSpaceCreates", "pendingDestructiveIntents").forEach {
                    assertTrue(manifest.getValue(it).jsonArray.isEmpty(), "rescue must not restore automatic operations")
                }
                assertTrue(source.tombstones.isEmpty())
                result = Json.parseToJsonElement(assertNotNull(source.readRecord(RECORD_KEY))).jsonObject
            })
        } finally {
            assertTrue(persistence.retirePreservingDraft())
            persistence.sealPreservedDraft()
        }
        return assertNotNull(result)
    }

    private fun assertNoCommands(root: File) {
        for (table in listOf("pending_document_move_command", "pending_document_comments", "outgoing_message"))
            assertEquals(0L, scalar(File(root, DATABASE), "SELECT count(*) FROM $table"))
    }

    private fun payload(tabs: List<JsonObject>, pendingSpaces: JsonArray = JsonArray(emptyList()), command: JsonObject? = null,
                        createCommands: List<JsonObject> = listOfNotNull(command)): DocumentDraftPayload {
        val tabsRecords = tabs.map { tab ->
            val key = "tab-" + (tab.getValue("recoveryId") as JsonPrimitive).content
            DocumentDraftRecord(key) { tab.toString() }
        }
        val commands = createCommands.map { value ->
            val id = (value.getValue("documentId") as JsonPrimitive).content
            DocumentDraftRecord("document-command-$id") { value.toString() }
        }
        val records = tabsRecords + commands
        val manifest = buildJsonObject {
            put("schemaVersion", 11)
            put("tabRecordKeys", JsonArray(tabsRecords.map { JsonPrimitive(it.key) }))
            put("pendingDocumentRecordKeys", JsonArray(commands.map { JsonPrimitive(it.key) }))
            put("activeTabInstanceId", 11)
            put("selectedSpaceId", SPACE)
            put("pendingSpaceCreates", pendingSpaces)
            put("pendingDestructiveIntents", JsonArray(emptyList()))
        }
        val identities = records.mapTo(linkedSetOf()) { it.key }
        if (pendingSpaces.isNotEmpty()) identities += "space-command-$SPACE"
        return DocumentDraftPayload(manifest.toString(), records, identities)
    }

    private fun createCommand() = buildJsonObject {
        put("documentId", DOCUMENT)
        put("tabInstanceId", 11)
        put("spaceId", SPACE)
        put("parentId", JsonNull)
        put("title", PRIVATE_TITLE)
        put("markdown", "original admitted body")
        put("admittedEditGeneration", 2)
        put("assets", JsonArray(emptyList()))
    }

    private fun tab(
        recoveryId: String = RECOVERY, instanceId: Long = 11, documentId: String = DOCUMENT,
        creating: Boolean = false, title: String = PRIVATE_TITLE, withAsset: Boolean = false,
    ) = buildJsonObject {
        put("tabId", documentId)
        put("instanceId", instanceId)
        put("recoveryId", recoveryId)
        put("documentId", if (creating) JsonNull else JsonPrimitive(documentId))
        put("spaceId", SPACE)
        put("parentId", JsonNull)
        put("ancestorIds", JsonArray(emptyList()))
        put("remoteMissing", false)
        put("savedTitle", if (creating) "" else "saved title")
        put("savedMarkdown", if (creating) "" else "saved body")
        put("draftTitle", title)
        put("draftMarkdown", PRIVATE_BODY + if (withAsset) "\n[source](${EmbeddedAsset.uri(ASSET)})" else "")
        put("revision", if (creating) JsonNull else JsonPrimitive(7))
        put("dirty", true)
        put("creating", creating)
        put("editGeneration", 3)
        put("savedAssets", JsonArray(emptyList()))
        put("draftAssets", JsonArray(if (withAsset) listOf(Json { encodeDefaults = true }.encodeToJsonElement(ASSET_DESCRIPTOR)) else emptyList()))
    }

    private fun corruptUnrelatedMessageIndex(root: File) {
        val database = File(root, DATABASE)
        val pageSize = scalar(database, "PRAGMA page_size")
        val page = scalar(database, "SELECT rootpage FROM sqlite_master WHERE name='idx_message_chat_seq' AND type='index'")
        assertTrue(page > 1)
        RandomAccessFile(database, "rw").use { file -> file.seek((page - 1) * pageSize); file.write(0) }
        val check = runCatching {
            DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA integrity_check(1)").use { rows -> assertTrue(rows.next()); rows.getString(1) }
                }
            }
        }
        assertTrue(check.isFailure || check.getOrNull() != "ok", "fixture must contain genuine unrelated index corruption")
    }

    private fun insertMove(database: File) = DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
        connection.prepareStatement("INSERT INTO pending_document_move_command(operation_id,space_id,node_id,old_parent_id,target_parent_id,name,expected_revision,issued_at) VALUES (?,?,?,NULL,NULL,?,7,1)").use { statement ->
            statement.setString(1, "99999999-9999-4999-8999-999999999999")
            statement.setString(2, SPACE); statement.setString(3, DOCUMENT); statement.setString(4, "pending rename")
            assertEquals(1, statement.executeUpdate())
        }
    }
    private fun scalar(database: File, sql: String): Long = DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getLong(1) } }
    }
    private fun directory(parent: File, name: String) = JvmPrivateDataDirectory.createNew(File(parent, name), parent).root.toFile()
    private fun privateFile(root: File, path: String, bytes: ByteArray): File {
        val parts = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(parts.dropLast(1), parts.last()).also { it.writeBytes(bytes) }
    }
    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            root.toPath().relativize(path).toString() to MessageDigest.getInstance("SHA-256").digest(path.toFile().readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
    private fun failure(message: String, block: () -> Unit) {
        assertEquals(message, assertFailsWith<IllegalStateException>(block = block).message)
    }
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("tk-document-rescue-").toRealPath().toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun LocalCacheDiagnosticOwner.draftOwner() = DocumentDraftOwnerKey(deploymentFingerprint, datasetId, uid)
    private fun database(owner: LocalCacheDiagnosticOwner) = "deployments/${owner.deploymentFingerprint}/datasets/${owner.datasetId}/users/${owner.uid}/cache_e0.db"
    private fun documentDirectory(owner: LocalCacheDiagnosticOwner = OWNER) =
        DocumentDraftStoragePaths.jvmDirectories(owner.deploymentFingerprint, owner.datasetId, owner.uid).joinToString("/")

    private companion object {
        val DEPLOYMENT = DeploymentIdentity.from("document-rescue.test.example", 5100, "https://document-rescue.test.example/api")
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val UID = "alice"
        const val SPACE = "22222222-2222-4222-8222-222222222222"
        const val DOCUMENT = "33333333-3333-4333-8333-333333333333"
        const val OTHER_DOCUMENT = "44444444-4444-4444-8444-444444444444"
        const val RECOVERY = "55555555-5555-4555-8555-555555555555"
        const val OTHER_RECOVERY = "66666666-6666-4666-8666-666666666666"
        const val ASSET = "77777777-7777-4777-8777-777777777777"
        const val RECORD_KEY = "tab-$RECOVERY"
        const val PRIVATE_TITLE = "private rescued title"
        const val PRIVATE_BODY = "private unsaved document body"
        val OWNER = LocalCacheDiagnosticOwner(DEPLOYMENT.fingerprint, DATASET, UID)
        val DATABASE = "deployments/${DEPLOYMENT.fingerprint}/datasets/$DATASET/users/$UID/cache_e0.db"
        val QUARANTINE = "deployments/${DEPLOYMENT.fingerprint}/datasets/$DATASET/users/$UID.corrupt-document-rescue/cache_e0.db"
        val ASSET_DESCRIPTOR = EmbeddedAsset(ASSET, Attachment("2026/09/source.txt", "source.txt", "text/plain", 6))
        val LIMITS = DesktopDocumentDraftLimits(MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong(), MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong(),
            MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES, MAX_DOCUMENT_DRAFT_RECORDS, 8192)
    }
}
