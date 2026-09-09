package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentTreeCreateRescueTest {
    @Test
    fun `independent spaces select their complete work without importing neighboring creation requests`() {
        val selected = tab(DOCUMENT, 1)
        val neighbor = tab(OTHER_DOCUMENT, 2, OTHER_SPACE)
        val unsent = tab(UNSENT, 3).copy(draftTitle = "", draftMarkdown = "never admitted", draftAssets = emptyList())
        val ordinary = tab(ORDINARY, 4).copy(documentId = ORDINARY, creating = false, revision = 8,
            savedTitle = "saved private title", savedMarkdown = "saved private body",
            draftTitle = "later private title", draftMarkdown = "later private body", draftAssets = emptyList())
        val commands = listOf(command(neighbor), command(selected))
        val original = snapshot(listOf(neighbor, selected, unsent, ordinary), commands)
        val source = source(original)
        val before = source.records.toMap()
        assertEquals(listOf(OTHER_SPACE, SPACE), DocumentTreeCreateRescue.spaceIds(source))
        val prepared = DocumentTreeCreateRescue.prepare(source, SPACE)
        val restored = restore(prepared.payload)
        assertEquals(listOf(selected, unsent, ordinary), restored.tabs)
        assertEquals(listOf(commands.last()), restored.pendingDocumentCreates)
        assertTrue(restored.pendingSpaceCreates.isEmpty())
        assertEquals(SPACE, restored.selectedSpaceId)
        assertEquals(selected.instanceId, restored.activeTabInstanceId)
        assertEquals(before.keys, source.reads.toSet())
        assertEquals(before, source.records)
        assertEquals(3, prepared.metadata.tabCount)
        assertEquals(1, prepared.metadata.pendingDocumentCount)
        assertEquals(restored.tabs.sumOf { it.draftMarkdown.length }, prepared.metadata.markdownCharacters)
        assertEquals(1, prepared.metadata.attachmentCount)
        assertEquals(setOf(selected.draftRecoveryKey(), unsent.draftRecoveryKey(), ordinary.draftRecoveryKey(),
            commands.last().draftRecoveryKey()), prepared.payload.activeRecoveryKeys)
        val other = restore(DocumentTreeCreateRescue.prepare(source, OTHER_SPACE).payload)
        assertEquals(listOf(neighbor), other.tabs)
        assertEquals(listOf(commands.first()), other.pendingDocumentCreates)
        assertEquals(neighbor.instanceId, other.activeTabInstanceId)
        val outbox = DocumentDurableCreateOutbox()
        outbox.restore(restored.pendingSpaceCreates, restored.pendingDocumentCreates)
        assertEquals(commands.last(), outbox.acquireDocument(selected))
        assertEquals(listOf(commands.last()), outbox.pendingDocuments())
        val metadata = documentDraftPayloadJson.encodeToString(prepared.metadata)
        for (privateContent in listOf("private title", "private body", "2026/09/private.txt")) {
            assertFalse(privateContent in metadata)
        }
    }

    @Test
    fun `reversed nested work keeps original requests successors and unsent leaves with remote ancestor hints`() {
        val parent = tab(DOCUMENT, 1)
        val child = tab(CHILD, 2).copy(parentId = DOCUMENT, ancestorIds = listOf(REMOTE, DOCUMENT))
        val grandchild = tab(GRANDCHILD, 3).copy(parentId = CHILD, ancestorIds = listOf(REMOTE, DOCUMENT, CHILD))
        val unsent = tab(UNSENT, 4).copy(parentId = GRANDCHILD,
            ancestorIds = grandchild.ancestorIds + GRANDCHILD, draftTitle = "", draftAssets = emptyList(), draftMarkdown = "leaf")
        val commands = listOf(command(grandchild), command(child), command(parent))
        val successor = child.copy(editGeneration = child.editGeneration + 1, draftTitle = "",
            draftMarkdown = "later child body", draftAssets = emptyList())
        val tabs = listOf(grandchild, unsent, successor, parent)
        val prepared = DocumentTreeCreateRescue.prepare(source(snapshot(tabs, commands)), SPACE)
        val restored = restore(prepared.payload)
        assertEquals(tabs, restored.tabs)
        assertEquals(commands, restored.pendingDocumentCreates)
        assertTrue(restored.pendingSpaceCreates.isEmpty())
        val outbox = DocumentDurableCreateOutbox()
        outbox.restore(emptyList(), restored.pendingDocumentCreates)
        assertEquals(listOf(commands.last()), outbox.replayableDocuments(restored.tabs, setOf(SPACE)).map { it.command })
        assertEquals(commands[1], outbox.acquireDocument(successor))
        assertFalse(outbox.pendingDocuments().any { it.documentId == unsent.tabId })

        // A parent known only to the server does not need a fabricated local command.
        val external = tab(CHILD, 2).copy(parentId = REMOTE, ancestorIds = listOf(REMOTE))
        val externalCommand = command(external)
        val externalRestore = restore(DocumentTreeCreateRescue.prepare(source(snapshot(listOf(external),
            listOf(externalCommand))), SPACE).payload)
        assertEquals(listOf(external), externalRestore.tabs)
        assertEquals(listOf(externalCommand), externalRestore.pendingDocumentCreates)
    }

    @Test
    fun `known foreign parents ancestor hints and cross record aliases are rejected`() {
        val selected = tab(DOCUMENT, 1)
        val neighbor = tab(OTHER_DOCUMENT, 2, OTHER_SPACE)
        for (dependent in listOf(
            selected.copy(parentId = OTHER_DOCUMENT, ancestorIds = listOf(OTHER_DOCUMENT)),
            selected.copy(parentId = REMOTE, ancestorIds = listOf(OTHER_DOCUMENT, REMOTE)),
        )) {
            assertEquals("OTHER_SPACE_CREATE_DEPENDENCY", failure(source(snapshot(listOf(dependent, neighbor),
                listOf(command(dependent), command(neighbor))))))
        }
        val remoteNeighbor = neighbor.copy(tabId = ORDINARY, documentId = OTHER_DOCUMENT, creating = false, revision = 2)
        val dependent = selected.copy(parentId = OTHER_DOCUMENT, ancestorIds = listOf(OTHER_DOCUMENT))
        assertEquals("OTHER_SPACE_CREATE_DEPENDENCY", failure(source(snapshot(listOf(dependent, remoteNeighbor),
            listOf(command(dependent))))))
        val tabIdDependent = selected.copy(parentId = ORDINARY, ancestorIds = listOf(ORDINARY))
        assertEquals("OTHER_SPACE_CREATE_DEPENDENCY", failure(source(snapshot(listOf(tabIdDependent, remoteNeighbor),
            listOf(command(tabIdDependent))))))

        val alias = neighbor.copy(documentId = DOCUMENT, creating = false, revision = 1)
        assertEquals("AMBIGUOUS_TAB_IDENTITY", failure(source(snapshot(listOf(selected, alias), listOf(command(selected))))))
        // Distinct effective document IDs do not make another tab's identity a valid alias.
        val ordinary = selected.copy(documentId = ORDINARY, creating = false, revision = 1)
        val crossFieldAlias = neighbor.copy(documentId = DOCUMENT, creating = false, revision = 1)
        assertEquals("AMBIGUOUS_TAB_IDENTITY", failure(source(snapshot(listOf(ordinary, crossFieldAlias), emptyList()))))
        val duplicateInstance = neighbor.copy(instanceId = selected.instanceId)
        assertEquals("AMBIGUOUS_TAB_IDENTITY", failure(source(snapshot(listOf(selected, duplicateInstance), emptyList()))))
    }

    @Test
    fun `unselected contents and frozen requests must still pass complete validation`() {
        val selected = tab(DOCUMENT, 1)
        val neighbor = tab(OTHER_DOCUMENT, 2, OTHER_SPACE)
        val commands = listOf(command(selected), command(neighbor))
        val original = snapshot(listOf(selected, neighbor), commands)
        assertEquals("CREATE_GENERATION_PAYLOAD_MISMATCH", failure(source(original.copy(
            tabs = listOf(selected, neighbor.copy(draftTitle = "other current content"))))))
        assertEquals("INVALID_CREATE_PAIR", failure(source(original.copy(
            pendingDocumentCreates = listOf(commands.first(), commands.last().copy(title = " invalid title "))))))
        val damagedBody = neighbor.copy(editGeneration = neighbor.editGeneration + 1, draftAssets = emptyList())
        assertEquals("UNREADABLE_OR_INVALID_SOURCE", failure(source(original.copy(tabs = listOf(selected, damagedBody)))))
        val raw = source(original)
        val damagedRecord = Source(raw.manifest, raw.records + (neighbor.draftRecoveryKey() to "broken JSON"))
        assertEquals("UNREADABLE_OR_INVALID_SOURCE", failure(damagedRecord))
        assertFailsWith<DocumentDraftRescueException> { DocumentTreeCreateRescue.spaceIds(damagedRecord) }
        val orphanCommand = commands.last().copy(tabInstanceId = 100)
        assertEquals("INVALID_CREATE_PAIR", failure(source(original.copy(pendingDocumentCreates = listOf(commands.first(), orphanCommand)))))
    }

    @Test
    fun `selected tree rejects unadmitted parents cycles retired requests and invalid path depth`() {
        val parent = tab(DOCUMENT, 1)
        val child = tab(CHILD, 2).copy(parentId = DOCUMENT, ancestorIds = listOf(DOCUMENT))
        val commands = listOf(command(child), command(parent))
        val original = snapshot(listOf(child, parent), commands)
        assertEquals("UNSUBMITTED_CREATE_PARENT", failure(source(original.copy(pendingDocumentCreates = listOf(commands.first())))))
        assertEquals("RETIRED_CREATE_DEPENDENCY", failure(source(original, setOf(commands.last().draftRecoveryKey()))))
        assertEquals("INVALID_CREATE_PAIR", failure(source(original, setOf(parent.draftRecoveryKey()))))
        val cyclic = parent.copy(parentId = CHILD, ancestorIds = listOf(CHILD))
        assertEquals("CREATE_PARENT_DEPENDENCY", failure(source(snapshot(listOf(child, cyclic),
            listOf(commands.first(), command(cyclic))))))
        for (ancestors in listOf(emptyList(), listOf(CHILD, DOCUMENT), listOf(DOCUMENT, DOCUMENT), listOf("invalid", DOCUMENT))) {
            assertEquals("CREATE_PARENT_DEPENDENCY", failure(source(original.copy(tabs = listOf(child.copy(ancestorIds = ancestors), parent)))))
        }
        val deep = child.copy(parentId = REMOTE, ancestorIds = (1..128).map {
            "10000000-0000-4000-8000-" + it.toString().padStart(12, '0')
        } + REMOTE)
        assertEquals("CREATE_PARENT_DEPENDENCY", failure(source(snapshot(listOf(deep), listOf(command(deep))))))
    }

    @Test
    fun `raw structural operations missing selection and malformed manifests cannot enter tree rescue`() {
        val selected = tab(DOCUMENT, 1)
        val original = snapshot(listOf(selected), listOf(command(selected)))
        val request = DocumentSpaceCreateRequest(DocumentSpaceCreateIntent("private space", null), SPACE)
        for (tombstones in listOf(emptySet(), setOf(request.draftRecoveryKey()))) {
            assertEquals("PENDING_OPERATIONS", failure(source(original.copy(pendingSpaceCreates = listOf(request)), tombstones)))
        }
        val destructive = PendingDocumentSpaceArchiveIntent(REMOTE, SPACE)
        assertEquals("PENDING_OPERATIONS", failure(source(original.copy(pendingDestructiveIntents = listOf(destructive)))))
        val raw = source(original)
        assertEquals("CREATE_WORKSET_UNAVAILABLE", failure(raw, OTHER_SPACE))
        assertEquals("INVALID_SPACE_ID", failure(raw, "not-a-space"))
        val unsentOnly = source(original.copy(pendingDocumentCreates = emptyList()))
        assertTrue(DocumentTreeCreateRescue.spaceIds(unsentOnly).isEmpty())
        assertEquals("CREATE_WORKSET_UNAVAILABLE", failure(unsentOnly))
        val manifest = documentDraftPayloadJson.parseToJsonElement(raw.manifest) as JsonObject
        for (invalid in listOf(
            "{\"pendingSpaceCreates\":[]," + raw.manifest.drop(1),
            JsonObject(manifest - "pendingDestructiveIntents").toString(),
            JsonObject(manifest + ("schemaVersion" to JsonPrimitive("11"))).toString(),
        )) {
            assertFailsWith<DocumentDraftRescueException> { DocumentTreeCreateRescue.spaceIds(Source(invalid, raw.records)) }
        }
    }

    @Test
    fun `single and space rescue entry points retain their original admission rules`() {
        val selected = tab(DOCUMENT, 1)
        val neighbor = tab(OTHER_DOCUMENT, 2, OTHER_SPACE)
        val single = source(snapshot(listOf(selected), listOf(command(selected))))
        assertEquals(listOf(selected.draftRecoveryKey()), DocumentDraftRescue.createRecordKeys(single))
        assertEquals(listOf(command(selected)), restore(DocumentDraftRescue.prepareCreate(single, selected.draftRecoveryKey()).payload)
            .pendingDocumentCreates)
        val multiple = snapshot(listOf(selected, neighbor), listOf(command(selected), command(neighbor)))
        assertEquals("CREATE_COMMAND_NOT_UNIQUE", assertFailsWith<DocumentDraftRescueException> {
            DocumentDraftRescue.createRecordKeys(source(multiple))
        }.reason)
        val request = DocumentSpaceCreateRequest(DocumentSpaceCreateIntent("private space", null), SPACE)
        val withSpace = source(multiple.copy(pendingSpaceCreates = listOf(request)))
        assertEquals("OTHER_SPACE_CREATE_DEPENDENCY", assertFailsWith<DocumentDraftRescueException> {
            DocumentSpaceCreateRescue.prepare(withSpace, request.draftRecoveryKey())
        }.reason)
        val supportedSpace = source(snapshot(listOf(selected), listOf(command(selected))).copy(pendingSpaceCreates = listOf(request)))
        assertEquals(listOf(request), restore(DocumentSpaceCreateRescue.prepare(supportedSpace, request.draftRecoveryKey()).payload)
            .pendingSpaceCreates)
    }

    private fun failure(source: DocumentDraftRecordSource, spaceId: String = SPACE): String =
        assertFailsWith<DocumentDraftRescueException> { DocumentTreeCreateRescue.prepare(source, spaceId) }.reason

    private fun tab(id: String, instance: Long, spaceId: String = SPACE) = DocumentTabState(
        tabId = id, instanceId = instance, recoveryId = id, documentId = null, spaceId = spaceId,
        parentId = null, ancestorIds = emptyList(), pathResolved = false, revision = null, savedTitle = "", savedMarkdown = "",
        draftTitle = "private title", draftMarkdown = "private body [file](${EmbeddedAsset.uri(ASSET)})",
        creating = true, dirty = true, editGeneration = 7,
        draftAssets = listOf(EmbeddedAsset(ASSET, Attachment("2026/09/private.txt", "private.txt", "text/plain", 4))),
    )

    private fun command(tab: DocumentTabState) = assertNotNull(PendingDocumentCreateCommand.capture(tab))

    private fun snapshot(tabs: List<DocumentTabState>, commands: List<PendingDocumentCreateCommand>) =
        DocumentWorkspaceDraftSnapshot(tabs, tabs.firstOrNull()?.instanceId, tabs.firstOrNull()?.spaceId,
            pendingDocumentCreates = commands)

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
        const val OTHER_SPACE = "00000000-0000-4000-8000-000000000002"
        const val DOCUMENT = "00000000-0000-4000-8000-000000000003"
        const val OTHER_DOCUMENT = "00000000-0000-4000-8000-000000000004"
        const val CHILD = "00000000-0000-4000-8000-000000000005"
        const val GRANDCHILD = "00000000-0000-4000-8000-000000000006"
        const val UNSENT = "00000000-0000-4000-8000-000000000007"
        const val ORDINARY = "00000000-0000-4000-8000-000000000008"
        const val REMOTE = "00000000-0000-4000-8000-000000000009"
        const val ASSET = "00000000-0000-4000-8000-000000000010"
        val OWNER = DocumentDraftOwnerKey("a".repeat(64), "00000000-0000-4000-8000-000000000011", "alice")
    }
}
