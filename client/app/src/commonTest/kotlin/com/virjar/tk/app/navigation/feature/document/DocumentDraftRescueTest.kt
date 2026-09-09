package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentDraftRescueTest {
    @Test
    fun `listing live manifest identities never reads or promises recovery of their records`() {
        val selected = tab()
        val retired = tab().copy(tabId = OTHER, documentId = OTHER, instanceId = 2, recoveryId = OTHER)
        val source = source(selected, retired)
        val unreadable = object : DocumentDraftRecordSource {
            override val manifest = source.manifest
            override val tombstones = setOf(retired.draftRecoveryKey())
            override fun recordByteCount(key: String): Long = error("Listing must not inspect a record")
            override fun readRecord(key: String): String = error("Broken record body must remain unread")
        }
        assertEquals(listOf(selected.draftRecoveryKey()), DocumentDraftRescue.recordKeys(unreadable))
        assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepare(Source(source.manifest, mapOf(selected.draftRecoveryKey() to "broken JSON")), selected.draftRecoveryKey())
        }
        val pending = documentDraftPayloadJson.decodeFromString<PersistedDocumentWorkspaceManifest>(source.manifest)
            .copy(pendingDocumentRecordKeys = listOf("document-command-$OTHER"))
        assertEquals("PENDING_OPERATIONS", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.recordKeys(Source(documentDraftPayloadJson.encodeToString(pending), emptyMap()))
        }.reason)
    }

    @Test
    fun `existing dirty document preserves its identity old base and complete private content`() {
        val original = tab().copy(remoteMissing = true)
        val source = source(original)
        val prepared = DocumentDraftRescue.prepare(source, original.draftRecoveryKey())
        val restored = restore(prepared.payload)

        assertEquals(listOf(original.copy(pathResolved = false)), restored.tabs)
        assertEquals(7L, prepared.metadata.baseRevision)
        assertEquals(original.recoveryId, prepared.metadata.recoveryId)
        assertEquals(original.draftMarkdown.length, prepared.metadata.markdownCharacters)
        assertEquals(1, prepared.metadata.attachmentCount)
        assertTrue(restored.tabs.single().dirty)
        assertTrue(restored.pendingDocumentCreates.isEmpty())
        assertTrue(restored.pendingDestructiveIntents.isEmpty())
        assertEquals(listOf(original.draftRecoveryKey()), source.reads)
        val metadata = documentDraftPayloadJson.encodeToString(prepared.metadata)
        assertFalse(metadata.contains("private draft"))
        assertFalse(metadata.contains("unfinished title"))
        assertFalse(metadata.contains("2026/09/private.txt"))
    }

    @Test
    fun `one unsent creating tab is selected without turning it into a create command`() {
        val selected = tab().copy(documentId = null, revision = null, creating = true,
            savedTitle = "", savedMarkdown = "", savedAssets = emptyList(), draftTitle = "  ")
        val neighbor = tab().copy(tabId = OTHER, documentId = OTHER, instanceId = 2,
            recoveryId = OTHER, draftMarkdown = "neighbor", draftAssets = emptyList())
        val source = source(selected, neighbor)
        val before = source.records.toMap()
        val prepared = DocumentDraftRescue.prepare(source, selected.draftRecoveryKey())
        val restored = restore(prepared.payload)

        assertTrue(prepared.metadata.creating)
        assertEquals(null, prepared.metadata.documentId)
        assertEquals(null, prepared.metadata.baseRevision)
        assertEquals(listOf(selected.copy(pathResolved = false)), restored.tabs)
        assertEquals(selected.instanceId, restored.activeTabInstanceId)
        assertEquals(setOf(selected.draftRecoveryKey()), prepared.payload.activeRecoveryKeys)
        assertTrue(restored.pendingDocumentCreates.isEmpty())
        assertTrue(restored.pendingSpaceCreates.isEmpty())
        assertEquals(before, source.records)
        assertEquals(listOf(selected.draftRecoveryKey()), source.reads)
    }

    @Test
    fun `any pending document space or destructive operation rejects the entire source before reading a body`() {
        val selected = tab()
        val source = source(selected)
        val manifest = documentDraftPayloadJson.decodeFromString<PersistedDocumentWorkspaceManifest>(source.manifest)
        val manifests = listOf(
            manifest.copy(pendingDocumentRecordKeys = listOf("document-command-$OTHER")),
            manifest.copy(pendingSpaceCreates = listOf(PersistedDocumentSpaceCreateRequest("pending", spaceId = SPACE))),
            manifest.copy(pendingDestructiveIntents = listOf(PersistedDocumentDestructiveIntent.from(
                PendingDocumentSpaceArchiveIntent(OTHER, SPACE)))),
        )
        for (pending in manifests) {
            val candidate = Source(documentDraftPayloadJson.encodeToString(pending), source.records)
            assertEquals("PENDING_OPERATIONS", assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepare(candidate, selected.draftRecoveryKey())
            }.reason)
            assertTrue(candidate.reads.isEmpty())
        }
    }

    @Test
    fun `retired keys invalid tombstones and broken record identity cannot revive a tab`() {
        val selected = tab()
        val source = source(selected)
        for (tombstones in listOf(setOf(selected.draftRecoveryKey()), setOf("invalid/key"))) {
            val candidate = Source(source.manifest, source.records, tombstones)
            assertFailsWith<DocumentDraftRescueException> { DocumentDraftRescue.prepare(candidate, selected.draftRecoveryKey()) }
            assertTrue(candidate.reads.isEmpty())
        }
        val badRecords = listOf(
            PersistedDocumentTabDraft.from(selected.copy(recoveryId = OTHER)),
            PersistedDocumentTabDraft.from(selected.copy(instanceId = 0)),
            PersistedDocumentTabDraft.from(selected.copy(dirty = false)),
            PersistedDocumentTabDraft.from(selected.copy(revision = -1)),
            PersistedDocumentTabDraft.from(selected.copy(draftAssets = emptyList())),
        )
        for (record in badRecords) {
            val candidate = Source(source.manifest, mapOf(selected.draftRecoveryKey() to documentDraftPayloadJson.encodeToString(record)))
            assertFailsWith<DocumentDraftRescueException> { DocumentDraftRescue.prepare(candidate, selected.draftRecoveryKey()) }
        }
        assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepare(Source(source.manifest, emptyMap()), selected.draftRecoveryKey())
        }
    }

    @Test
    fun `strict recovery refuses ambiguous unknown coerced or incomplete JSON without exposing private content`() {
        val selected = tab()
        val source = source(selected)
        val key = selected.draftRecoveryKey()
        val encoded = source.records.getValue(key)
        val objectValue = documentDraftPayloadJson.parseToJsonElement(encoded) as JsonObject
        val variants = listOf(
            "{\"unexpected\":\"private draft\"," + encoded.drop(1),
            "{\"revision\":7," + encoded.drop(1),
            "{\"revis\\u0069on\":7," + encoded.drop(1),
            JsonObject(objectValue + ("revision" to JsonPrimitive("7"))).toString(),
            JsonObject(objectValue + ("dirty" to JsonPrimitive("true"))).toString(),
            JsonObject(objectValue - "draftAssets").toString(),
            encoded.dropLast(1),
        )
        for (invalid in variants) {
            val failure = assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepare(Source(source.manifest, mapOf(key to invalid)), key)
            }
            assertFalse(failure.message.orEmpty().contains("private draft"))
        }
        for (invalidManifest in listOf(
            "{\"unknownOperation\":true," + source.manifest.drop(1),
            source.manifest.replace("\"schemaVersion\":$DOCUMENT_DRAFT_SCHEMA_VERSION", "\"schemaVersion\":\"$DOCUMENT_DRAFT_SCHEMA_VERSION\""),
            "{\"tabRecordKeys\":[]," + source.manifest.drop(1),
        )) {
            assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepare(Source(invalidManifest, source.records), key)
            }
        }
        val wrongSize = object : DocumentDraftRecordSource by source {
            override fun recordByteCount(key: String) = source.recordByteCount(key)!! + 1
        }
        assertEquals("RECORD_SIZE_MISMATCH", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepare(wrongSize, key)
        }.reason)
    }

    @Test
    fun `storage cancellation and fatal failures keep their original boundary`() {
        val source = source(tab())
        val cancelled = CancellationException("cancelled")
        val fatal = AssertionError("fatal")
        for (failure in listOf(cancelled, fatal)) {
            val unavailable = object : DocumentDraftRecordSource by source {
                override fun readRecord(key: String): String = throw failure
            }
            val actual = assertFailsWith<Throwable> { DocumentDraftRescue.prepare(unavailable, tab().draftRecoveryKey()) }
            assertSame(failure, actual)
        }
    }

    @Test
    fun `admitted create rescue restores only the exact pair through the ordinary store and outbox`() {
        val selected = creatingTab()
        val command = assertNotNull(PendingDocumentCreateCommand.capture(selected))
        val neighbor = tab().copy(tabId = OTHER, documentId = OTHER, instanceId = 2, recoveryId = OTHER)
        val source = createSource(listOf(selected, neighbor), listOf(command))
        val originalRecords = source.records.toMap()
        assertEquals(listOf(selected.draftRecoveryKey()), DocumentDraftRescue.createRecordKeys(source))

        val prepared = DocumentDraftRescue.prepareCreate(source, selected.draftRecoveryKey())
        val restored = restore(prepared.payload)
        assertEquals(listOf(selected.copy(pathResolved = false)), restored.tabs)
        assertEquals(listOf(command), restored.pendingDocumentCreates)
        assertTrue(restored.pendingSpaceCreates.isEmpty())
        assertTrue(restored.pendingDestructiveIntents.isEmpty())
        assertEquals(setOf(selected.draftRecoveryKey(), command.draftRecoveryKey()), prepared.payload.activeRecoveryKeys)
        val outbox = DocumentDurableCreateOutbox()
        outbox.restore(restored.pendingSpaceCreates, restored.pendingDocumentCreates)
        assertEquals(command, outbox.acquireDocument(restored.tabs.single()))
        assertEquals(listOf(PendingDocumentCreateReplay(command, restored.tabs.single())),
            outbox.replayableDocuments(restored.tabs, setOf(SPACE)))
        assertTrue(outbox.replayableDocuments(restored.tabs, emptySet()).isEmpty())
        assertEquals(originalRecords, source.records)
        assertEquals(command.draftRecoveryKey(), prepared.metadata.commandRecordKey)
        assertEquals(command.title.length, prepared.metadata.frozenTitleCharacters)
        assertEquals(command.markdown.length, prepared.metadata.frozenMarkdownCharacters)
        val metadata = documentDraftPayloadJson.encodeToString(prepared.metadata)
        assertFalse(metadata.contains(command.title))
        assertFalse(metadata.contains("private draft"))
        assertFalse(metadata.contains("2026/09/private.txt"))
        assertEquals("PENDING_OPERATIONS", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepare(source, selected.draftRecoveryKey())
        }.reason)
    }

    @Test
    fun `later empty title and replacement assets survive beside the original frozen create request`() {
        val admitted = creatingTab()
        val command = assertNotNull(PendingDocumentCreateCommand.capture(admitted))
        val nextAsset = EmbeddedAsset(OTHER, Attachment("2026/09/later.txt", "later.txt", "text/plain", 5))
        val successor = admitted.copy(editGeneration = admitted.editGeneration + 1, draftTitle = "",
            draftMarkdown = "later body [later](${EmbeddedAsset.uri(OTHER)})", draftAssets = listOf(nextAsset))
        val prepared = DocumentDraftRescue.prepareCreate(createSource(listOf(successor), listOf(command)), successor.draftRecoveryKey())
        val restored = restore(prepared.payload)
        assertEquals(listOf(successor.copy(pathResolved = false)), restored.tabs)
        assertEquals(listOf(command), restored.pendingDocumentCreates)
        assertEquals(admitted.editGeneration, prepared.metadata.admittedEditGeneration)
        assertEquals(successor.editGeneration, prepared.metadata.currentEditGeneration)
        val outbox = DocumentDurableCreateOutbox()
        outbox.restore(emptyList(), restored.pendingDocumentCreates)
        // Capture of the blank successor is invalid. Replay must still return the original A.
        assertEquals(null, PendingDocumentCreateCommand.capture(restored.tabs.single()))
        assertEquals(command, outbox.acquireDocument(restored.tabs.single()))
        assertEquals(admitted.draftAssets, outbox.pendingDocuments().single().assets)
        assertEquals(listOf(nextAsset), restored.tabs.single().draftAssets)
    }

    @Test
    fun `same generation payload disagreement and incompatible create identities cannot be normalized away`() {
        val admitted = creatingTab()
        val command = assertNotNull(PendingDocumentCreateCommand.capture(admitted))
        for (changed in listOf(
            admitted.copy(draftTitle = "different title"),
            admitted.copy(draftMarkdown = "different body", draftAssets = emptyList()),
            admitted.copy(draftAssets = admitted.draftAssets.map { it.copy(attachment = it.attachment.copy(size = 99)) }),
        )) {
            assertEquals("CREATE_GENERATION_PAYLOAD_MISMATCH", assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepareCreate(createSource(listOf(changed), listOf(command)), changed.draftRecoveryKey())
            }.reason)
        }
        for (changedCommand in listOf(
            command.copy(tabInstanceId = 2), command.copy(spaceId = OTHER),
            command.copy(parentId = OTHER), command.copy(admittedEditGeneration = admitted.editGeneration + 1),
            command.copy(title = "  ${command.title}  "),
        )) {
            assertEquals("INVALID_CREATE_PAIR", assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepareCreate(createSource(listOf(admitted), listOf(changedCommand)), admitted.draftRecoveryKey())
            }.reason)
        }
    }

    @Test
    fun `retired pairs and other pending or creating dependencies reject before any command can replay`() {
        val selected = creatingTab()
        val command = assertNotNull(PendingDocumentCreateCommand.capture(selected))
        val source = createSource(listOf(selected), listOf(command))
        for ((key, reason) in listOf(command.draftRecoveryKey() to "CREATE_COMMAND_NOT_UNIQUE",
            selected.draftRecoveryKey() to "RECORD_UNAVAILABLE_OR_RETIRED")) {
            val retired = Source(source.manifest, source.records, setOf(key))
            assertEquals(reason, assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepareCreate(retired, selected.draftRecoveryKey())
            }.reason)
            assertTrue(retired.reads.isEmpty())
        }
        val manifest = documentDraftPayloadJson.decodeFromString<PersistedDocumentWorkspaceManifest>(source.manifest)
        for (pending in listOf(
            manifest.copy(pendingSpaceCreates = listOf(PersistedDocumentSpaceCreateRequest("pending", spaceId = SPACE))),
            manifest.copy(pendingDestructiveIntents = listOf(PersistedDocumentDestructiveIntent.from(PendingDocumentSpaceArchiveIntent(OTHER, SPACE)))),
        )) {
            val candidate = Source(documentDraftPayloadJson.encodeToString(pending), source.records)
            assertEquals("PENDING_OPERATIONS", assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.createRecordKeys(candidate)
            }.reason)
            assertTrue(candidate.reads.isEmpty())
        }
        val other = selected.copy(tabId = OTHER, instanceId = 2, recoveryId = OTHER)
        assertEquals("CREATE_TAB_NOT_UNIQUE", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepareCreate(createSource(listOf(selected, other), listOf(command)), selected.draftRecoveryKey())
        }.reason)
        assertEquals("CREATE_COMMAND_NOT_UNIQUE", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepareCreate(createSource(listOf(selected, other),
                listOf(command, assertNotNull(PendingDocumentCreateCommand.capture(other)))), selected.draftRecoveryKey())
        }.reason)
        val alias = tab().copy(tabId = OTHER, instanceId = 2, recoveryId = OTHER)
        assertEquals("CREATE_PARENT_OR_IDENTITY_DEPENDENCY", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepareCreate(createSource(listOf(selected, alias), listOf(command)), selected.draftRecoveryKey())
        }.reason)
        assertEquals("CREATE_PARENT_OR_IDENTITY_DEPENDENCY", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepareCreate(createSource(listOf(selected.copy(parentId = DOCUMENT)),
                listOf(command.copy(parentId = DOCUMENT))), selected.draftRecoveryKey())
        }.reason)
    }

    @Test
    fun `create rescue strictly decodes commands and requires complete canonical frozen and successor assets`() {
        val selected = creatingTab()
        val command = assertNotNull(PendingDocumentCreateCommand.capture(selected))
        val source = createSource(listOf(selected), listOf(command))
        val key = command.draftRecoveryKey()
        val encoded = source.records.getValue(key)
        val json = documentDraftPayloadJson.parseToJsonElement(encoded) as JsonObject
        for (invalid in listOf(
            "{\"title\":\"hidden command\"," + encoded.drop(1),
            JsonObject(json - "assets").toString(),
            JsonObject(json + ("admittedEditGeneration" to JsonPrimitive("4"))).toString(),
            documentDraftPayloadJson.encodeToString(PersistedDocumentCreateCommand.from(command.copy(assets = emptyList()))),
        )) {
            assertFailsWith<DocumentDraftRescueException> {
                DocumentDraftRescue.prepareCreate(Source(source.manifest, source.records + (key to invalid)), selected.draftRecoveryKey())
            }
        }
        val brokenSuccessor = selected.copy(editGeneration = 5, draftAssets = emptyList())
        assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepareCreate(createSource(listOf(brokenSuccessor), listOf(command)), selected.draftRecoveryKey())
        }
        val manifest = documentDraftPayloadJson.decodeFromString<PersistedDocumentWorkspaceManifest>(source.manifest)
        assertEquals("UNSUPPORTED_SCHEMA", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.prepareCreate(Source(documentDraftPayloadJson.encodeToString(manifest.copy(schemaVersion = 10)), source.records),
                selected.draftRecoveryKey())
        }.reason)
    }

    private fun creatingTab() = tab().copy(documentId = null, revision = null, creating = true,
        savedTitle = "", savedMarkdown = "", savedAssets = emptyList(), draftTitle = "frozen title")

    private fun createSource(tabs: List<DocumentTabState>, commands: List<PendingDocumentCreateCommand>): Source {
        // Encode the raw current format, including inconsistent fixtures: normal restore must not
        // filter malformed identities or commands before the rescue reader can reject them.
        val payload = encodeDocumentDraftPayload(DocumentWorkspaceDraftSnapshot(tabs, tabs.first().instanceId, SPACE,
            pendingDocumentCreates = commands))
        return Source(payload.manifest, payload.records.associate { it.key to it.payload() })
    }

    private fun source(vararg tabs: DocumentTabState): Source {
        val persistence = MemoryPersistence()
        assertTrue(DocumentDraftStore(persistence).save(OWNER, tabs.toList(), tabs.first().tabId, SPACE))
        return assertNotNull(persistence.current)
    }

    private fun restore(payload: DocumentDraftPayload): DocumentWorkspaceDraftSnapshot {
        val persistence = MemoryPersistence()
        assertTrue(persistence.write(OWNER) { payload })
        return assertNotNull(DocumentDraftStore(persistence).restore(OWNER))
    }

    private class Source(
        override val manifest: String,
        val records: Map<String, String>,
        override val tombstones: Set<String> = emptySet(),
    ) : DocumentDraftRecordSource {
        val reads = mutableListOf<String>()
        override fun recordByteCount(key: String): Long? = records[key]?.encodeToByteArray()?.size?.toLong()
        override fun readRecord(key: String): String? = records[key].also { reads += key }
    }

    private class MemoryPersistence : DocumentDraftPersistence {
        var current: Source? = null
        override fun read(ownerKey: DocumentDraftOwnerKey, consume: (DocumentDraftRecordSource) -> Unit): DocumentDraftReadStatus {
            val source = current ?: return DocumentDraftReadStatus.ABSENT
            consume(source)
            return DocumentDraftReadStatus.AVAILABLE
        }
        override fun write(ownerKey: DocumentDraftOwnerKey, payload: () -> DocumentDraftPayload): Boolean {
            val value = payload()
            current = Source(value.manifest, value.records.associate { it.key to it.payload() })
            return true
        }
        override fun flush() = true
        override fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean = error("Unexpected retirement")
        override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean = error("Unexpected deletion")
        override fun clearAll(): Boolean = error("Unexpected clearing")
    }

    private fun tab() = DocumentTabState(
        tabId = DOCUMENT, instanceId = 1, recoveryId = RECOVERY, documentId = DOCUMENT, spaceId = SPACE,
        parentId = null, ancestorIds = emptyList(), savedTitle = "saved title", savedMarkdown = "saved body",
        draftTitle = "  unfinished title  ", draftMarkdown = "private draft\n[file](${EmbeddedAsset.uri(ASSET)})",
        revision = 7, dirty = true, editGeneration = 4,
        draftAssets = listOf(EmbeddedAsset(ASSET, Attachment("2026/09/private.txt", "private.txt", "text/plain", 4))),
    )

    private companion object {
        const val SPACE = "00000000-0000-4000-8000-000000000001"
        const val DOCUMENT = "00000000-0000-4000-8000-000000000002"
        const val RECOVERY = "00000000-0000-4000-8000-000000000003"
        const val OTHER = "00000000-0000-4000-8000-000000000004"
        const val ASSET = "00000000-0000-4000-8000-000000000005"
        val OWNER = DocumentDraftOwnerKey("a".repeat(64), "00000000-0000-4000-8000-000000000006", "alice")
    }
}
