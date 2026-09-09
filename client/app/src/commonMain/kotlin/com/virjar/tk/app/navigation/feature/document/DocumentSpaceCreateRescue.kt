package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document
import kotlinx.serialization.Serializable
import java.util.UUID

/** Counts describe the retained tabs; no private name, description, body or descriptor is reported. */
@Serializable
data class DocumentSpaceCreateRescueMetadata(
    val recordKey: String,
    val spaceId: String,
    val nameCharacters: Int,
    val descriptionCharacters: Int,
    val tabCount: Int,
    val pendingDocumentCount: Int,
    val markdownCharacters: Int,
    val attachmentCount: Int,
)

/** Private content is handed to the existing atomic manifest writer, never to the CLI printer. */
class DocumentSpaceCreateRescuePrepared(
    val metadata: DocumentSpaceCreateRescueMetadata,
    val payload: DocumentDraftPayload,
)

/** Restores one admitted space request and its document creation dependencies without replaying them. */
object DocumentSpaceCreateRescue {
    /** Listing validates the complete selected workset, including frozen document requests. */
    fun recordKeys(source: DocumentDraftRecordSource): List<String> = documentDraftRescueGuarded {
        listOf(readSpaceCreateRescue(source, null).request.draftRecoveryKey())
    }

    fun prepare(source: DocumentDraftRecordSource, recordKey: String): DocumentSpaceCreateRescuePrepared =
        documentDraftRescueGuarded {
            requireRescue(recordKey.isDocumentDraftRecordKey(), "INVALID_RECORD_KEY")
            val rescued = readSpaceCreateRescue(source, recordKey)
            val request = rescued.request
            val tabs = rescued.snapshot.tabs
            DocumentSpaceCreateRescuePrepared(
                metadata = DocumentSpaceCreateRescueMetadata(
                    recordKey = recordKey,
                    spaceId = request.spaceId,
                    nameCharacters = request.intent.name.length,
                    descriptionCharacters = request.intent.description?.length ?: 0,
                    tabCount = tabs.size,
                    pendingDocumentCount = rescued.snapshot.pendingDocumentCreates.size,
                    markdownCharacters = tabs.sumOf { it.draftMarkdown.length },
                    attachmentCount = tabs.sumOf { it.draftAssets.size },
                ),
                payload = encodeDocumentDraftPayload(rescued.snapshot),
            )
        }
}

private data class SpaceCreateRescue(
    val request: DocumentSpaceCreateRequest,
    val snapshot: DocumentWorkspaceDraftSnapshot,
)

private fun readSpaceCreateRescue(source: DocumentDraftRecordSource, selectedKey: String?): SpaceCreateRescue {
    val manifest = decodeRescueRecord<PersistedDocumentWorkspaceManifest>(source.manifest, MAX_DOCUMENT_DRAFT_MANIFEST_BYTES)
    requireRescue(manifest.schemaVersion == DOCUMENT_DRAFT_SCHEMA_VERSION, "UNSUPPORTED_SCHEMA")
    requireRescue(manifest.hasBoundedIdentityCount(), "INVALID_MANIFEST")
    requireRescue(manifest.pendingDestructiveIntents.isEmpty(), "PENDING_OPERATIONS")
    val spaceRequests = manifest.pendingSpaceCreates.map { persisted ->
        persisted.toRequest().also { request ->
            requireRescue(request.normalized() == request, "INVALID_SPACE_CREATE")
        }
    }
    val keys = manifest.tabRecordKeys + manifest.pendingDocumentRecordKeys + spaceRequests.map { it.draftRecoveryKey() }
    requireRescue(keys.all(String::isDocumentDraftRecordKey) && keys.distinct().size == keys.size &&
        (manifest.activeTabInstanceId == null || manifest.activeTabInstanceId in 1L until Long.MAX_VALUE) &&
        (manifest.selectedSpaceId == null || manifest.selectedSpaceId.isNotBlank()), "INVALID_MANIFEST")
    val tombstones = source.tombstones.toSet()
    requireRescue(tombstones.size <= MAX_DOCUMENT_DRAFT_RECORDS && tombstones.all(String::isDocumentDraftRecordKey),
        "INVALID_TOMBSTONES")
    val liveSpaces = spaceRequests.filterNot { it.draftRecoveryKey() in tombstones }
    requireRescue(liveSpaces.size == 1, "SPACE_CREATE_NOT_UNIQUE")
    val request = liveSpaces.single()
    requireRescue(selectedKey == null || selectedKey == request.draftRecoveryKey(), "RECORD_UNAVAILABLE_OR_RETIRED")

    val tabKeys = manifest.tabRecordKeys.filterNot(tombstones::contains)
    val commandKeys = manifest.pendingDocumentRecordKeys.filterNot(tombstones::contains)
    requireRescueRecordBudget(source, tabKeys + commandKeys)
    // Unrelated ordinary tabs stay in the archive. Read their identities so neither a damaged
    // record nor an alias can conceal a competing creation or an ownership dependency.
    val allTabs = tabKeys.map { key -> readRescueTab(source, key).also { validateRescueTab(it, includeContent = false) } }
    requireRescue(allTabs.map { it.instanceId }.distinct().size == allTabs.size &&
        allTabs.map { it.tabId }.distinct().size == allTabs.size &&
        allTabs.map { it.recoveryId }.distinct().size == allTabs.size &&
        allTabs.map { it.documentId ?: it.tabId }.distinct().size == allTabs.size, "AMBIGUOUS_TAB_IDENTITY")
    requireRescue(allTabs.none { it.creating && it.spaceId != request.spaceId }, "OTHER_SPACE_CREATE_DEPENDENCY")
    val tabs = allTabs.filter { it.spaceId == request.spaceId }
    // A retired frozen request cannot become an apparently new unsent draft with the same ID.
    requireRescue(tabs.none { it.creating && "document-command-${it.tabId}" in tombstones }, "RETIRED_CREATE_DEPENDENCY")
    tabs.forEach { validateRescueTab(it, includeContent = true) }

    val commands = commandKeys.map { readRescueCreateCommand(source, it) }
    requireRescue(commands.all { it.spaceId == request.spaceId }, "OTHER_SPACE_CREATE_DEPENDENCY")
    requireRescue(commands.map { it.documentId }.distinct().size == commands.size &&
        commands.map { it.tabInstanceId }.distinct().size == commands.size, "AMBIGUOUS_CREATE_IDENTITY")
    commands.forEach { command ->
        requireRescue(command.normalized() == command, "INVALID_CREATE_PAIR")
        val tab = tabs.singleOrNull(command::matches)
        requireRescue(tab != null, "INVALID_CREATE_PAIR")
        validateRescueCreatePayload(checkNotNull(tab), command)
    }
    validateSpaceCreateDependencies(tabs, commands)
    val snapshot = DocumentWorkspaceDraftSnapshot(
        tabs = tabs,
        activeTabInstanceId = manifest.activeTabInstanceId?.takeIf { id -> tabs.any { it.instanceId == id } }
            ?: tabs.firstOrNull()?.instanceId,
        selectedSpaceId = request.spaceId,
        pendingSpaceCreates = listOf(request),
        pendingDocumentCreates = commands,
    )
    requireRescue(snapshot.normalized() == snapshot && snapshot.hasBoundedPersistenceShape(), "INVALID_SPACE_WORKSET")
    return SpaceCreateRescue(request, snapshot)
}

private fun validateSpaceCreateDependencies(tabs: List<DocumentTabState>, commands: List<PendingDocumentCreateCommand>) {
    val creatingById = tabs.filter { it.creating }.associateBy { it.tabId }
    val admittedIds = commands.mapTo(hashSetOf()) { it.documentId }
    val dependencies = mutableMapOf<String, Set<String>>()
    for (tab in creatingById.values) {
        val ancestors = tab.ancestorIds
        requireRescue(ancestors.size <= Document.MAX_ANCESTOR_DEPTH &&
            ancestors.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } &&
            ancestors.distinct().size == ancestors.size && tab.tabId !in ancestors &&
            (if (tab.parentId == null) ancestors.isEmpty() else ancestors.lastOrNull() == tab.parentId),
            "CREATE_PARENT_DEPENDENCY")
        val localAncestors = ancestors.filterTo(hashSetOf()) { it in creatingById }
        requireRescue(localAncestors.all { it in admittedIds }, "UNSUBMITTED_CREATE_PARENT")
        dependencies[tab.tabId] = localAncestors
    }
    // Paths may describe different moments: a committed parent can move before its local
    // create acknowledgement completes. Preserve those paths, but reject a dependency cycle
    // that would make the existing replay coordinator wait forever. Remote ancestors remain
    // subject to the server's current path checks when the original request is submitted.
    while (dependencies.isNotEmpty()) {
        val ready = dependencies.filterValues { it.none(dependencies::containsKey) }.keys
        requireRescue(ready.isNotEmpty(), "CREATE_PARENT_DEPENDENCY")
        ready.forEach(dependencies::remove)
    }
}
