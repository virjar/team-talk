package com.virjar.tk.app.navigation.feature.document

import kotlinx.serialization.Serializable
import java.util.UUID

/** Aggregate counts only; the source's private titles, bodies and descriptors are not reported. */
@Serializable
data class DocumentTreeCreateRescueMetadata(
    val spaceId: String,
    val tabCount: Int,
    val pendingDocumentCount: Int,
    val markdownCharacters: Int,
    val attachmentCount: Int,
)

class DocumentTreeCreateRescuePrepared(
    val metadata: DocumentTreeCreateRescueMetadata,
    val payload: DocumentDraftPayload,
)

/** Selects existing-space creation work without admitting any new space or document request. */
object DocumentTreeCreateRescue {
    /** All source records are validated before any independently recoverable space is listed. */
    fun spaceIds(source: DocumentDraftRecordSource): List<String> = documentDraftRescueGuarded {
        val rescued = readTreeCreateRescue(source)
        rescued.commands.map { it.spaceId }.distinct().also { spaces ->
            spaces.forEach { rescued.selectedSnapshot(it) }
        }
    }

    fun prepare(source: DocumentDraftRecordSource, spaceId: String): DocumentTreeCreateRescuePrepared = documentDraftRescueGuarded {
        requireRescue(runCatching { UUID.fromString(spaceId).toString() == spaceId }.getOrDefault(false), "INVALID_SPACE_ID")
        val snapshot = readTreeCreateRescue(source).selectedSnapshot(spaceId)
        DocumentTreeCreateRescuePrepared(
            metadata = DocumentTreeCreateRescueMetadata(
                spaceId = spaceId,
                tabCount = snapshot.tabs.size,
                pendingDocumentCount = snapshot.pendingDocumentCreates.size,
                markdownCharacters = snapshot.tabs.sumOf { it.draftMarkdown.length },
                attachmentCount = snapshot.tabs.sumOf { it.draftAssets.size },
            ),
            payload = encodeDocumentDraftPayload(snapshot),
        )
    }
}

private data class TreeCreateRescue(
    val header: DocumentCreateRescueHeader,
    val tabs: List<DocumentTabState>,
    val commands: List<PendingDocumentCreateCommand>,
) {
    fun selectedSnapshot(spaceId: String): DocumentWorkspaceDraftSnapshot {
        val selectedTabs = tabs.filter { it.spaceId == spaceId }
        val selectedCommands = commands.filter { it.spaceId == spaceId }
        requireRescue(selectedCommands.isNotEmpty(), "CREATE_WORKSET_UNAVAILABLE")
        val foreignIds = tabs.asSequence().filter { it.spaceId != spaceId }
            .flatMap { listOfNotNull(it.tabId, it.documentId).asSequence() }.toHashSet()
        commands.filter { it.spaceId != spaceId }.forEach { foreignIds += it.documentId }
        requireRescue(selectedTabs.none { tab ->
            tab.parentId?.let(foreignIds::contains) == true || tab.ancestorIds.any(foreignIds::contains)
        }, "OTHER_SPACE_CREATE_DEPENDENCY")
        validateDocumentCreateRescueDependencies(selectedTabs, selectedCommands)
        val snapshot = DocumentWorkspaceDraftSnapshot(
            tabs = selectedTabs,
            activeTabInstanceId = header.manifest.activeTabInstanceId?.takeIf { id -> selectedTabs.any { it.instanceId == id } }
                ?: selectedTabs.first().instanceId,
            selectedSpaceId = spaceId,
            pendingDocumentCreates = selectedCommands,
        )
        requireRescue(snapshot.normalized() == snapshot && snapshot.hasBoundedPersistenceShape(), "INVALID_TREE_WORKSET")
        return snapshot
    }
}

private fun readTreeCreateRescue(source: DocumentDraftRecordSource): TreeCreateRescue {
    val header = readDocumentCreateRescueHeader(source, allowSpaceCreates = false)
    // Selection never hides a damaged neighbor or an ambiguous alias. Unselected records
    // remain in the archive, but their identities and immutable requests must be trustworthy.
    val tabs = readDocumentCreateRescueTabs(source, header, includeContent = true)
    val identities = tabs.flatMap { tab -> listOfNotNull(tab.tabId, tab.documentId).distinct() }
    requireRescue(identities.distinct().size == identities.size, "AMBIGUOUS_TAB_IDENTITY")
    requireRescue(tabs.none { it.creating && "document-command-${it.tabId}" in header.tombstones }, "RETIRED_CREATE_DEPENDENCY")
    val commands = header.commandKeys.map { readRescueCreateCommand(source, it) }
    validateDocumentCreateRescueCommands(tabs, commands)
    return TreeCreateRescue(header, tabs, commands)
}
