package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlinx.coroutines.CancellationException
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

class DocumentSpaceCreateRescueTest {
    @Test
    fun `standalone space request retains its exact frozen identity without inventing a tab`() {
        val request = spaceRequest()
        val source = source(DocumentWorkspaceDraftSnapshot(emptyList(), null, SPACE,
            pendingSpaceCreates = listOf(request)))
        assertEquals(listOf(request.draftRecoveryKey()), DocumentSpaceCreateRescue.recordKeys(source))
        val prepared = DocumentSpaceCreateRescue.prepare(source, request.draftRecoveryKey())
        val restored = restore(prepared.payload)
        assertTrue(restored.tabs.isEmpty())
        assertTrue(restored.pendingDocumentCreates.isEmpty())
        assertEquals(listOf(request), restored.pendingSpaceCreates)
        assertEquals(setOf(request.draftRecoveryKey()), prepared.payload.activeRecoveryKeys)
        assertTrue(prepared.payload.records.isEmpty())
        assertTrue(source.reads.isEmpty())
        assertEquals(0, prepared.metadata.tabCount)
        assertEquals(0, prepared.metadata.markdownCharacters)
        val outbox = DocumentDurableCreateOutbox()
        outbox.restore(restored.pendingSpaceCreates, restored.pendingDocumentCreates)
        assertEquals(request, outbox.acquireSpace(request.intent.name, request.intent.description))
        assertEquals("PENDING_OPERATIONS", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.recordKeys(source)
        }.reason)
    }

    @Test
    fun `complete direct workset retains all frozen requests later edits and unsent drafts without importing neighbors`() {
        val admitted = creatingTab()
        val first = assertNotNull(PendingDocumentCreateCommand.capture(admitted))
        val laterAsset = EmbeddedAsset(SECOND_ASSET, Attachment("2026/09/later.txt", "later.txt", "text/plain", 9))
        val successor = admitted.copy(editGeneration = admitted.editGeneration + 1, draftTitle = "",
            draftMarkdown = "later private body [file](${EmbeddedAsset.uri(SECOND_ASSET)})", draftAssets = listOf(laterAsset))
        val secondTab = creatingTab().copy(tabId = SECOND_DOCUMENT, instanceId = 2, recoveryId = SECOND_DOCUMENT,
            draftTitle = "another admitted title", draftMarkdown = "second body", draftAssets = emptyList())
        val second = assertNotNull(PendingDocumentCreateCommand.capture(secondTab))
        val unsent = creatingTab().copy(tabId = THIRD_DOCUMENT, instanceId = 3, recoveryId = THIRD_DOCUMENT,
            draftTitle = "", draftMarkdown = "not submitted", draftAssets = emptyList())
        val ordinary = creatingTab().copy(tabId = FOURTH_DOCUMENT, documentId = FOURTH_DOCUMENT,
            instanceId = 4, recoveryId = FOURTH_DOCUMENT, creating = false, revision = 8,
            parentId = PARENT, ancestorIds = listOf(PARENT), savedTitle = "old title", savedMarkdown = "old body",
            draftTitle = "ordinary private edit", draftMarkdown = "ordinary private body", draftAssets = emptyList())
        val neighbor = ordinary.copy(tabId = OTHER_DOCUMENT, documentId = OTHER_DOCUMENT, spaceId = OTHER_SPACE,
            instanceId = 5, recoveryId = OTHER_DOCUMENT)
        val selectedTabs = listOf(successor, secondTab, unsent, ordinary)
        val original = DocumentWorkspaceDraftSnapshot(selectedTabs + neighbor, neighbor.instanceId, OTHER_SPACE,
            pendingSpaceCreates = listOf(spaceRequest()), pendingDocumentCreates = listOf(first, second))
        val source = source(original)
        val before = source.records.toMap()
        val prepared = DocumentSpaceCreateRescue.prepare(source, spaceRequest().draftRecoveryKey())
        val restored = restore(prepared.payload)
        assertEquals(selectedTabs.map { it.copy(pathResolved = false) }, restored.tabs)
        assertEquals(listOf(first, second), restored.pendingDocumentCreates)
        assertEquals(listOf(spaceRequest()), restored.pendingSpaceCreates)
        assertEquals(SPACE, restored.selectedSpaceId)
        assertEquals(successor.instanceId, restored.activeTabInstanceId)
        assertEquals(before, source.records)
        assertEquals(4, prepared.metadata.tabCount)
        assertEquals(2, prepared.metadata.pendingDocumentCount)
        assertEquals(selectedTabs.sumOf { it.draftMarkdown.length }, prepared.metadata.markdownCharacters)
        assertEquals(1, prepared.metadata.attachmentCount)
        assertEquals(7, prepared.payload.activeRecoveryKeys.size)
        val outbox = DocumentDurableCreateOutbox()
        outbox.restore(restored.pendingSpaceCreates, restored.pendingDocumentCreates)
        assertEquals(first, outbox.acquireDocument(restored.tabs.first()))
        assertTrue(outbox.replayableDocuments(restored.tabs, emptySet()).isEmpty())
        assertEquals(listOf(first, second), outbox.replayableDocuments(restored.tabs, setOf(SPACE)).map { it.command })
        assertTrue(outbox.pendingDocuments().none { it.documentId == unsent.tabId })
        val metadata = documentDraftPayloadJson.encodeToString(prepared.metadata)
        for (privateContent in listOf("private space", "private description", "private body", "2026/09/later.txt")) {
            assertFalse(metadata.contains(privateContent))
        }
    }

    @Test
    fun `noncanonical multiple or retired space requests and destructive intents cannot enter restoration`() {
        val original = snapshot()
        for (request in listOf(spaceRequest().copy(intent = DocumentSpaceCreateIntent(" private space ", null)),
            spaceRequest().copy(intent = DocumentSpaceCreateIntent("private space", "  private description  ")))) {
            val candidate = source(original.copy(pendingSpaceCreates = listOf(request)))
            assertEquals("INVALID_SPACE_CREATE", failure(candidate))
            assertTrue(candidate.reads.isEmpty())
        }
        val many = source(original.copy(pendingSpaceCreates = listOf(spaceRequest(), spaceRequest().copy(spaceId = OTHER_SPACE))))
        assertEquals("SPACE_CREATE_NOT_UNIQUE", failure(many))
        assertTrue(many.reads.isEmpty())
        val retired = source(original, setOf(spaceRequest().draftRecoveryKey()))
        assertEquals("SPACE_CREATE_NOT_UNIQUE", failure(retired))
        assertTrue(retired.reads.isEmpty())
        val destructive = source(original.copy(pendingDestructiveIntents = listOf(PendingDocumentSpaceArchiveIntent(PARENT, SPACE))))
        assertEquals("PENDING_OPERATIONS", failure(destructive))
        assertTrue(destructive.reads.isEmpty())
    }

    @Test
    fun `outside creating work parent chains retired commands and ambiguous tabs reject the source`() {
        val original = snapshot()
        val selected = original.tabs.single()
        val command = original.pendingDocumentCreates.single()
        val outside = selected.copy(tabId = OTHER_DOCUMENT, instanceId = 2, recoveryId = OTHER_DOCUMENT, spaceId = OTHER_SPACE)
        assertEquals("OTHER_SPACE_CREATE_DEPENDENCY", failure(source(original.copy(tabs = original.tabs + outside))))
        assertEquals("OTHER_SPACE_CREATE_DEPENDENCY", failure(source(original.copy(
            pendingDocumentCreates = listOf(command.copy(spaceId = OTHER_SPACE))))))
        for (child in listOf(selected.copy(parentId = PARENT), selected.copy(ancestorIds = listOf(PARENT)))) {
            assertEquals("CREATE_PARENT_DEPENDENCY", failure(source(original.copy(tabs = listOf(child)))))
        }
        assertEquals("RETIRED_CREATE_DEPENDENCY", failure(source(original, setOf(command.draftRecoveryKey()))))
        assertEquals("INVALID_CREATE_PAIR", failure(source(original, setOf(selected.draftRecoveryKey()))))
        val alias = selected.copy(tabId = OTHER_DOCUMENT, documentId = selected.tabId, instanceId = 2,
            recoveryId = OTHER_DOCUMENT, creating = false, revision = 1, spaceId = OTHER_SPACE)
        assertEquals("AMBIGUOUS_TAB_IDENTITY", failure(source(original.copy(tabs = original.tabs + alias))))
        // A second ordinary record is not imported, but must not conceal a damaged identity.
        val raw = source(original.copy(tabs = original.tabs + alias.copy(documentId = OTHER_DOCUMENT)))
        val broken = Source(raw.manifest, raw.records + (alias.draftRecoveryKey() to "broken JSON"))
        assertEquals("UNREADABLE_OR_INVALID_SOURCE", failure(broken))
    }

    @Test
    fun `every frozen command and current tab must be canonical and agree at the admitted generation`() {
        val original = snapshot()
        val selected = original.tabs.single()
        val command = original.pendingDocumentCreates.single()
        assertEquals("CREATE_GENERATION_PAYLOAD_MISMATCH", failure(source(original.copy(
            tabs = listOf(selected.copy(draftTitle = "different title"))))))
        for (wrong in listOf(command.copy(tabInstanceId = 2), command.copy(title = " ${command.title} "),
            command.copy(admittedEditGeneration = selected.editGeneration + 1), command.copy(assets = emptyList()))) {
            assertEquals("INVALID_CREATE_PAIR", failure(source(original.copy(pendingDocumentCreates = listOf(wrong)))))
        }
        val incompleteLater = selected.copy(editGeneration = selected.editGeneration + 1, draftAssets = emptyList())
        assertEquals("UNREADABLE_OR_INVALID_SOURCE", failure(source(original.copy(tabs = listOf(incompleteLater)))))
        val badSavedBase = selected.copy(savedMarkdown = "[missing](${EmbeddedAsset.uri(ASSET)})")
        assertEquals("UNREADABLE_OR_INVALID_SOURCE", failure(source(original.copy(tabs = listOf(badSavedBase)))))
    }

    @Test
    fun `strict manifest and command decoding bounds and cancellation preserve the source boundary`() {
        val raw = source(snapshot())
        val manifest = documentDraftPayloadJson.parseToJsonElement(raw.manifest) as JsonObject
        for (invalid in listOf(
            "{\"pendingSpaceCreates\":[]," + raw.manifest.drop(1),
            JsonObject(manifest - "pendingDestructiveIntents").toString(),
            JsonObject(manifest + ("schemaVersion" to JsonPrimitive("11"))).toString(),
        )) {
            assertFailsWith<DocumentDraftRescueException> { DocumentSpaceCreateRescue.recordKeys(Source(invalid, raw.records)) }
        }
        val commandKey = snapshot().pendingDocumentCreates.single().draftRecoveryKey()
        val command = documentDraftPayloadJson.parseToJsonElement(raw.records.getValue(commandKey)) as JsonObject
        assertEquals("INVALID_JSON_SHAPE", failure(Source(raw.manifest,
            raw.records + (commandKey to JsonObject(command + ("admittedEditGeneration" to JsonPrimitive("7"))).toString()))))
        val oversized = object : DocumentDraftRecordSource by raw {
            override fun recordByteCount(key: String) = MAX_DOCUMENT_DRAFT_RECORD_BYTES + 1L
        }
        assertEquals("RECORD_UNAVAILABLE", assertFailsWith<DocumentDraftRescueException> {
            DocumentSpaceCreateRescue.recordKeys(oversized)
        }.reason)
        assertTrue(raw.reads.isEmpty())
        for (cause in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            val failed = object : DocumentDraftRecordSource by raw {
                override fun readRecord(key: String): String = throw cause
            }
            assertSame(cause, assertFailsWith<Throwable> { DocumentSpaceCreateRescue.recordKeys(failed) })
        }
    }

    private fun failure(source: DocumentDraftRecordSource): String = assertFailsWith<DocumentDraftRescueException> {
        DocumentSpaceCreateRescue.prepare(source, spaceRequest().draftRecoveryKey())
    }.reason

    private fun spaceRequest() = DocumentSpaceCreateRequest(DocumentSpaceCreateIntent("private space", "private description"), SPACE)

    private fun creatingTab() = DocumentTabState(
        tabId = DOCUMENT, instanceId = 1, recoveryId = RECOVERY, documentId = null, spaceId = SPACE,
        parentId = null, ancestorIds = emptyList(), savedTitle = "", savedMarkdown = "",
        draftTitle = "frozen title", draftMarkdown = "private body [file](${EmbeddedAsset.uri(ASSET)})",
        revision = null, creating = true, dirty = true, editGeneration = 7,
        draftAssets = listOf(EmbeddedAsset(ASSET, Attachment("2026/09/private.txt", "private.txt", "text/plain", 4))),
    )

    private fun snapshot(): DocumentWorkspaceDraftSnapshot {
        val tab = creatingTab()
        return DocumentWorkspaceDraftSnapshot(listOf(tab), tab.instanceId, SPACE, pendingSpaceCreates = listOf(spaceRequest()),
            pendingDocumentCreates = listOf(assertNotNull(PendingDocumentCreateCommand.capture(tab))))
    }

    private fun source(snapshot: DocumentWorkspaceDraftSnapshot, tombstones: Set<String> = emptySet()): Source {
        val payload = encodeDocumentDraftPayload(snapshot)
        return Source(payload.manifest, payload.records.associate { it.key to it.payload() }, tombstones)
    }

    private fun restore(payload: DocumentDraftPayload): DocumentWorkspaceDraftSnapshot {
        val persisted = Source(payload.manifest, payload.records.associate { it.key to it.payload() })
        val persistence = object : DocumentDraftPersistence {
            override fun read(ownerKey: DocumentDraftOwnerKey, consume: (DocumentDraftRecordSource) -> Unit): DocumentDraftReadStatus {
                consume(persisted)
                return DocumentDraftReadStatus.AVAILABLE
            }
            override fun write(ownerKey: DocumentDraftOwnerKey, payload: () -> DocumentDraftPayload): Boolean = error("Unexpected rewrite")
            override fun flush() = true
            override fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean = error("Unexpected retirement")
            override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean = error("Unexpected deletion")
            override fun clearAll(): Boolean = error("Unexpected clearing")
        }
        return assertNotNull(DocumentDraftStore(persistence).restore(OWNER))
    }

    private class Source(
        override val manifest: String,
        val records: Map<String, String>,
        override val tombstones: Set<String> = emptySet(),
    ) : DocumentDraftRecordSource {
        val reads = mutableListOf<String>()
        override fun recordByteCount(key: String) = records[key]?.encodeToByteArray()?.size?.toLong()
        override fun readRecord(key: String) = records[key].also { reads += key }
    }

    private companion object {
        const val SPACE = "00000000-0000-4000-8000-000000000001"
        const val DOCUMENT = "00000000-0000-4000-8000-000000000002"
        const val RECOVERY = "00000000-0000-4000-8000-000000000003"
        const val OTHER_SPACE = "00000000-0000-4000-8000-000000000004"
        const val ASSET = "00000000-0000-4000-8000-000000000005"
        const val SECOND_ASSET = "00000000-0000-4000-8000-000000000006"
        const val SECOND_DOCUMENT = "00000000-0000-4000-8000-000000000007"
        const val THIRD_DOCUMENT = "00000000-0000-4000-8000-000000000008"
        const val FOURTH_DOCUMENT = "00000000-0000-4000-8000-000000000009"
        const val OTHER_DOCUMENT = "00000000-0000-4000-8000-000000000010"
        const val PARENT = "00000000-0000-4000-8000-000000000011"
        val OWNER = DocumentDraftOwnerKey("a".repeat(64), "00000000-0000-4000-8000-000000000012", "alice")
    }
}
