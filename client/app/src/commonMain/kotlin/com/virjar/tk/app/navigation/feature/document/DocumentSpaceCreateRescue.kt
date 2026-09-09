package com.virjar.tk.app.navigation.feature.document

import kotlinx.serialization.Serializable

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
    val header = readDocumentCreateRescueHeader(source, allowSpaceCreates = true)
    val manifest = header.manifest
    val tombstones = header.tombstones
    val liveSpaces = header.spaceRequests.filterNot { it.draftRecoveryKey() in tombstones }
    requireRescue(liveSpaces.size == 1, "SPACE_CREATE_NOT_UNIQUE")
    val request = liveSpaces.single()
    requireRescue(selectedKey == null || selectedKey == request.draftRecoveryKey(), "RECORD_UNAVAILABLE_OR_RETIRED")

    // Unrelated ordinary tabs stay in the archive. Read their identities so neither a damaged
    // record nor an alias can conceal a competing creation or an ownership dependency.
    val allTabs = readDocumentCreateRescueTabs(source, header, includeContent = false)
    requireRescue(allTabs.none { it.creating && it.spaceId != request.spaceId }, "OTHER_SPACE_CREATE_DEPENDENCY")
    val tabs = allTabs.filter { it.spaceId == request.spaceId }
    // A retired frozen request cannot become an apparently new unsent draft with the same ID.
    requireRescue(tabs.none { it.creating && "document-command-${it.tabId}" in tombstones }, "RETIRED_CREATE_DEPENDENCY")
    tabs.forEach { validateRescueTab(it, includeContent = true) }

    val commands = header.commandKeys.map { readRescueCreateCommand(source, it) }
    requireRescue(commands.all { it.spaceId == request.spaceId }, "OTHER_SPACE_CREATE_DEPENDENCY")
    validateDocumentCreateRescueCommands(tabs, commands)
    validateDocumentCreateRescueDependencies(tabs, commands)
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
