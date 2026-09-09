package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document
import java.util.UUID

internal data class DocumentCreateRescueHeader(
    val manifest: PersistedDocumentWorkspaceManifest,
    val spaceRequests: List<DocumentSpaceCreateRequest>,
    val tombstones: Set<String>,
) {
    val tabKeys: List<String> get() = manifest.tabRecordKeys.filterNot(tombstones::contains)
    val commandKeys: List<String> get() = manifest.pendingDocumentRecordKeys.filterNot(tombstones::contains)
}

internal fun readDocumentCreateRescueHeader(
    source: DocumentDraftRecordSource,
    allowSpaceCreates: Boolean,
): DocumentCreateRescueHeader {
    val manifest = decodeRescueRecord<PersistedDocumentWorkspaceManifest>(source.manifest, MAX_DOCUMENT_DRAFT_MANIFEST_BYTES)
    requireRescue(manifest.schemaVersion == DOCUMENT_DRAFT_SCHEMA_VERSION, "UNSUPPORTED_SCHEMA")
    requireRescue(manifest.hasBoundedIdentityCount(), "INVALID_MANIFEST")
    requireRescue(manifest.pendingDestructiveIntents.isEmpty() &&
        (allowSpaceCreates || manifest.pendingSpaceCreates.isEmpty()), "PENDING_OPERATIONS")
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
    return DocumentCreateRescueHeader(manifest, spaceRequests, tombstones)
}

internal fun readDocumentCreateRescueTabs(
    source: DocumentDraftRecordSource,
    header: DocumentCreateRescueHeader,
    includeContent: Boolean,
): List<DocumentTabState> {
    requireRescueRecordBudget(source, header.tabKeys + header.commandKeys)
    val tabs = header.tabKeys.map { key -> readRescueTab(source, key).also { validateRescueTab(it, includeContent) } }
    requireRescue(tabs.map { it.instanceId }.distinct().size == tabs.size &&
        tabs.map { it.tabId }.distinct().size == tabs.size &&
        tabs.map { it.recoveryId }.distinct().size == tabs.size &&
        tabs.map { it.documentId ?: it.tabId }.distinct().size == tabs.size, "AMBIGUOUS_TAB_IDENTITY")
    return tabs
}

internal fun validateDocumentCreateRescueCommands(tabs: List<DocumentTabState>, commands: List<PendingDocumentCreateCommand>) {
    requireRescue(commands.map { it.documentId }.distinct().size == commands.size &&
        commands.map { it.tabInstanceId }.distinct().size == commands.size, "AMBIGUOUS_CREATE_IDENTITY")
    commands.forEach { command ->
        requireRescue(command.normalized() == command, "INVALID_CREATE_PAIR")
        val tab = tabs.singleOrNull(command::matches)
        requireRescue(tab != null, "INVALID_CREATE_PAIR")
        validateRescueCreatePayload(checkNotNull(tab), command)
    }
}

internal fun validateDocumentCreateRescueDependencies(tabs: List<DocumentTabState>, commands: List<PendingDocumentCreateCommand>) {
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
