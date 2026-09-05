package com.virjar.tk.app.navigation.feature.document

import kotlinx.serialization.Serializable

internal const val DOCUMENT_DRAFT_SCHEMA_VERSION = 11

/** 与热草稿存储及其规范化规则分离的版本化 wire 形状。 */
@Serializable
internal data class PersistedDocumentWorkspaceManifest(
    val schemaVersion: Int,
    val tabRecordKeys: List<String>,
    val pendingDocumentRecordKeys: List<String> = emptyList(),
    val activeTabInstanceId: Long? = null,
    val selectedSpaceId: String? = null,
    val pendingSpaceCreates: List<PersistedDocumentSpaceCreateRequest> = emptyList(),
    val pendingDestructiveIntents: List<PersistedDocumentDestructiveIntent> = emptyList(),
) {
    fun hasBoundedIdentityCount(): Boolean =
        pendingDestructiveIntents.size <= MAX_DOCUMENT_DESTRUCTIVE_INTENTS &&
            tabRecordKeys.size + pendingDocumentRecordKeys.size + pendingSpaceCreates.size +
            pendingDestructiveIntents.size <= MAX_DOCUMENT_DRAFT_RECORDS
}

@Serializable
internal data class PersistedDocumentCreateCommand(
    val documentId: String,
    val tabInstanceId: Long,
    val spaceId: String,
    val parentId: String? = null,
    val title: String,
    val markdown: String,
    val admittedEditGeneration: Long,
    val assets: List<com.virjar.tk.protocol.model.EmbeddedAsset> = emptyList(),
) {
    fun toCommand() = PendingDocumentCreateCommand(
        documentId = documentId,
        tabInstanceId = tabInstanceId,
        spaceId = spaceId,
        parentId = parentId,
        title = title,
        markdown = markdown,
        admittedEditGeneration = admittedEditGeneration,
        assets = assets,
    )

    companion object {
        fun from(command: PendingDocumentCreateCommand) = PersistedDocumentCreateCommand(
            documentId = command.documentId,
            tabInstanceId = command.tabInstanceId,
            spaceId = command.spaceId,
            parentId = command.parentId,
            title = command.title,
            markdown = command.markdown,
            admittedEditGeneration = command.admittedEditGeneration,
            assets = command.assets,
        )
    }
}

@Serializable
internal data class PersistedDocumentSpaceCreateRequest(
    val name: String,
    val description: String? = null,
    val spaceId: String,
) {
    fun toRequest() = DocumentSpaceCreateRequest(
        intent = DocumentSpaceCreateIntent(name, description),
        spaceId = spaceId,
    )

    companion object {
        fun from(request: DocumentSpaceCreateRequest) = PersistedDocumentSpaceCreateRequest(
            name = request.intent.name,
            description = request.intent.description,
            spaceId = request.spaceId,
        )
    }
}

@Serializable
internal data class PersistedDocumentDestructiveIntent(
    val kind: Int,
    val operationId: String,
    val spaceId: String,
    val documentId: String? = null,
    val parentId: String? = null,
    val expectedRevision: Long? = null,
) {
    fun toIntent(): DocumentDestructiveIntent? {
        return when (kind) {
            KIND_ARCHIVE_SPACE -> if (
                documentId == null && parentId == null && expectedRevision == null
            ) {
                PendingDocumentSpaceArchiveIntent(operationId = operationId, spaceId = spaceId)
            } else {
                null
            }
            KIND_DELETE_LEAF -> {
                val targetDocumentId = documentId ?: return null
                val revision = expectedRevision ?: return null
                PendingDocumentLeafDeleteIntent(
                    operationId = operationId,
                    spaceId = spaceId,
                    documentId = targetDocumentId,
                    parentId = parentId,
                    expectedRevision = revision,
                )
            }
            else -> null
        }
    }

    companion object {
        private const val KIND_ARCHIVE_SPACE = 1
        private const val KIND_DELETE_LEAF = 2

        fun from(intent: DocumentDestructiveIntent): PersistedDocumentDestructiveIntent =
            when (intent) {
                is PendingDocumentSpaceArchiveIntent -> PersistedDocumentDestructiveIntent(
                    kind = KIND_ARCHIVE_SPACE,
                    operationId = intent.operationId,
                    spaceId = intent.spaceId,
                )
                is PendingDocumentLeafDeleteIntent -> PersistedDocumentDestructiveIntent(
                    kind = KIND_DELETE_LEAF,
                    operationId = intent.operationId,
                    spaceId = intent.spaceId,
                    documentId = intent.documentId,
                    parentId = intent.parentId,
                    expectedRevision = intent.expectedRevision,
                )
            }
    }
}

@Serializable
internal data class PersistedDocumentTabDraft(
    val tabId: String,
    val instanceId: Long,
    val recoveryId: String,
    val documentId: String? = null,
    val spaceId: String,
    val parentId: String? = null,
    val ancestorIds: List<String> = emptyList(),
    val remoteMissing: Boolean,
    val savedTitle: String,
    val savedMarkdown: String,
    val draftTitle: String,
    val draftMarkdown: String,
    val revision: Long? = null,
    val dirty: Boolean,
    val creating: Boolean,
    val editGeneration: Long,
    val savedAssets: List<com.virjar.tk.protocol.model.EmbeddedAsset> = emptyList(),
    val draftAssets: List<com.virjar.tk.protocol.model.EmbeddedAsset> = emptyList(),
) {
    fun toTab() = DocumentTabState(
        tabId = tabId,
        instanceId = instanceId,
        recoveryId = recoveryId,
        documentId = documentId,
        spaceId = spaceId,
        parentId = parentId,
        ancestorIds = ancestorIds,
        pathResolved = false,
        remoteMissing = remoteMissing,
        savedTitle = savedTitle,
        savedMarkdown = savedMarkdown,
        draftTitle = draftTitle,
        draftMarkdown = draftMarkdown,
        revision = revision,
        dirty = dirty,
        creating = creating,
        editGeneration = editGeneration,
        savedAssets = savedAssets,
        draftAssets = draftAssets,
    )

    companion object {
        fun from(tab: DocumentTabState) = PersistedDocumentTabDraft(
            tabId = tab.tabId,
            instanceId = tab.instanceId,
            recoveryId = tab.recoveryId,
            documentId = tab.documentId,
            spaceId = tab.spaceId,
            parentId = tab.parentId,
            ancestorIds = tab.ancestorIds,
            remoteMissing = tab.remoteMissing,
            savedTitle = tab.savedTitle,
            savedMarkdown = tab.savedMarkdown,
            draftTitle = tab.draftTitle,
            draftMarkdown = tab.draftMarkdown,
            revision = tab.revision,
            dirty = tab.dirty,
            creating = tab.creating,
            editGeneration = tab.editGeneration,
            savedAssets = tab.savedAssets,
            draftAssets = tab.draftAssets,
        )
    }
}
