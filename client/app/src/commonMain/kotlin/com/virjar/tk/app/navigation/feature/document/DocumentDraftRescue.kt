package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.shared.client.requireUnambiguousRecoveryJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.encodeToJsonElement

/** Safe to report without publishing the selected title, Markdown or attachment descriptors. */
@Serializable
data class DocumentDraftRescueMetadata(
    val recordKey: String,
    val tabId: String,
    val instanceId: Long,
    val recoveryId: String,
    val documentId: String?,
    val spaceId: String,
    val baseRevision: Long?,
    val creating: Boolean,
    val markdownCharacters: Int,
    val attachmentCount: Int,
    val commandRecordKey: String? = null,
    val frozenTitleCharacters: Int? = null,
    val frozenMarkdownCharacters: Int? = null,
    val admittedEditGeneration: Long? = null,
    val currentEditGeneration: Long? = null,
)

/** The payload contains private content and is for the existing persistence writer only. */
class DocumentDraftRescuePrepared(
    val metadata: DocumentDraftRescueMetadata,
    val payload: DocumentDraftPayload,
)

/** Prepares one independently recoverable draft or admitted create pair without mutating storage. */
object DocumentDraftRescue {
    /** Lists live manifest identities only; availability and validity require [prepare]. */
    fun recordKeys(source: DocumentDraftRecordSource): List<String> = documentDraftRescueGuarded {
        readRescueRecordKeys(source)
    }

    fun prepare(source: DocumentDraftRecordSource, recordKey: String): DocumentDraftRescuePrepared = documentDraftRescueGuarded {
        requireRescue(recordKey.isDocumentDraftRecordKey(), "INVALID_RECORD_KEY")
        requireRescue(recordKey in readRescueRecordKeys(source), "RECORD_UNAVAILABLE_OR_RETIRED")
        val tab = readRescueTab(source, recordKey)
        val snapshot = DocumentWorkspaceDraftSnapshot(listOf(tab), tab.instanceId, tab.spaceId)
        validateRescueTab(tab, includeContent = true)
        DocumentDraftRescuePrepared(
            metadata = DocumentDraftRescueMetadata(recordKey, tab.tabId, tab.instanceId, tab.recoveryId,
                tab.documentId, tab.spaceId, tab.revision, tab.creating, tab.draftMarkdown.length, tab.draftAssets.size),
            payload = encodeDocumentDraftPayload(snapshot),
        )
    }

    /** Lists the single independently recoverable creation pair, using the same validation as import. */
    fun createRecordKeys(source: DocumentDraftRecordSource): List<String> = documentDraftRescueGuarded {
        listOf(readCreateRescuePair(source, null).tab.draftRecoveryKey())
    }

    /** Restores an already admitted create intent, preserving its frozen request and later edits. */
    fun prepareCreate(source: DocumentDraftRecordSource, recordKey: String): DocumentDraftRescuePrepared = documentDraftRescueGuarded {
        requireRescue(recordKey.isDocumentDraftRecordKey(), "INVALID_RECORD_KEY")
        val (tab, command) = readCreateRescuePair(source, recordKey)
        val snapshot = DocumentWorkspaceDraftSnapshot(listOf(tab), tab.instanceId, tab.spaceId,
            pendingDocumentCreates = listOf(command))
        requireRescue(snapshot.normalized() == snapshot && snapshot.hasBoundedPersistenceShape(), "INVALID_CREATE_PAIR")
        DocumentDraftRescuePrepared(
            metadata = DocumentDraftRescueMetadata(recordKey, tab.tabId, tab.instanceId, tab.recoveryId,
                tab.documentId, tab.spaceId, tab.revision, tab.creating, tab.draftMarkdown.length, tab.draftAssets.size,
                command.draftRecoveryKey(), command.title.length, command.markdown.length,
                command.admittedEditGeneration, tab.editGeneration),
            payload = encodeDocumentDraftPayload(snapshot),
        )
    }
}

class DocumentDraftRescueException internal constructor(val reason: String) :
    IllegalStateException("Document draft rescue failed: $reason")

private fun rescueFailed(reason: String): Nothing = throw DocumentDraftRescueException(reason)
internal fun requireRescue(condition: Boolean, reason: String) {
    if (!condition) rescueFailed(reason)
}

internal fun <T> documentDraftRescueGuarded(action: () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: DocumentDraftRescueException) {
    throw failure
} catch (_: Exception) {
    // Decoder/storage exceptions can include private titles, body fragments or source paths.
    rescueFailed("UNREADABLE_OR_INVALID_SOURCE")
}

private fun readRescueRecordKeys(source: DocumentDraftRecordSource): List<String> {
    val manifest = decodeRescueRecord<PersistedDocumentWorkspaceManifest>(source.manifest, MAX_DOCUMENT_DRAFT_MANIFEST_BYTES)
    requireRescue(manifest.schemaVersion == DOCUMENT_DRAFT_SCHEMA_VERSION, "UNSUPPORTED_SCHEMA")
    requireRescue(manifest.hasBoundedIdentityCount(), "INVALID_MANIFEST")
    requireRescue(manifest.pendingDocumentRecordKeys.isEmpty() && manifest.pendingSpaceCreates.isEmpty() &&
        manifest.pendingDestructiveIntents.isEmpty(), "PENDING_OPERATIONS")
    requireRescue(manifest.tabRecordKeys.all(String::isDocumentDraftRecordKey) &&
        manifest.tabRecordKeys.distinct().size == manifest.tabRecordKeys.size &&
        (manifest.activeTabInstanceId == null || manifest.activeTabInstanceId in 1L until Long.MAX_VALUE) &&
        (manifest.selectedSpaceId == null || manifest.selectedSpaceId.isNotBlank()), "INVALID_MANIFEST")
    val tombstones = source.tombstones.toSet()
    requireRescue(tombstones.size <= MAX_DOCUMENT_DRAFT_RECORDS &&
        tombstones.all(String::isDocumentDraftRecordKey), "INVALID_TOMBSTONES")
    return manifest.tabRecordKeys.filterNot(tombstones::contains)
}

internal inline fun <reified T> decodeRescueRecord(encoded: String, maximumBytes: Int): T {
    requireRescue(encoded.length <= maximumBytes &&
        encoded.encodeToByteArray(throwOnInvalidSequence = true).size <= maximumBytes, "SOURCE_SIZE_LIMIT")
    requireUnambiguousRecoveryJson(encoded)
    val decoded = documentDraftPayloadJson.decodeFromString<T>(encoded)
    // The current writer includes every default. Equality also rejects coercions such as a
    // quoted revision/boolean, missing fields and nested descriptor types that decoding accepts.
    requireRescue(documentDraftPayloadJson.parseToJsonElement(encoded) ==
        documentDraftPayloadJson.encodeToJsonElement(decoded), "INVALID_JSON_SHAPE")
    return decoded
}

private data class CreateRescuePair(val tab: DocumentTabState, val command: PendingDocumentCreateCommand)

private fun readCreateRescuePair(source: DocumentDraftRecordSource, selectedKey: String?): CreateRescuePair {
    val manifest = decodeRescueRecord<PersistedDocumentWorkspaceManifest>(source.manifest, MAX_DOCUMENT_DRAFT_MANIFEST_BYTES)
    requireRescue(manifest.schemaVersion == DOCUMENT_DRAFT_SCHEMA_VERSION, "UNSUPPORTED_SCHEMA")
    requireRescue(manifest.hasBoundedIdentityCount(), "INVALID_MANIFEST")
    requireRescue(manifest.pendingSpaceCreates.isEmpty() && manifest.pendingDestructiveIntents.isEmpty(), "PENDING_OPERATIONS")
    val keys = manifest.tabRecordKeys + manifest.pendingDocumentRecordKeys
    requireRescue(keys.all(String::isDocumentDraftRecordKey) && keys.distinct().size == keys.size &&
        (manifest.activeTabInstanceId == null || manifest.activeTabInstanceId in 1L until Long.MAX_VALUE) &&
        (manifest.selectedSpaceId == null || manifest.selectedSpaceId.isNotBlank()), "INVALID_MANIFEST")
    val tombstones = source.tombstones.toSet()
    requireRescue(tombstones.size <= MAX_DOCUMENT_DRAFT_RECORDS && tombstones.all(String::isDocumentDraftRecordKey), "INVALID_TOMBSTONES")
    val tabKeys = manifest.tabRecordKeys.filterNot(tombstones::contains)
    val commandKeys = manifest.pendingDocumentRecordKeys.filterNot(tombstones::contains)
    requireRescue(commandKeys.size == 1, "CREATE_COMMAND_NOT_UNIQUE")
    requireRescue(selectedKey == null || selectedKey in tabKeys, "RECORD_UNAVAILABLE_OR_RETIRED")
    // The source may retain unrelated ordinary tabs. Their identities must be readable before
    // selecting a pair; a damaged neighbor must not conceal a competing create or parent intent.
    requireRescueRecordBudget(source, tabKeys + commandKeys)
    val tabs = tabKeys.map { key -> readRescueTab(source, key).also { validateRescueTab(it, includeContent = false) } }
    requireRescue(tabs.map { it.instanceId }.distinct().size == tabs.size &&
        tabs.map { it.tabId }.distinct().size == tabs.size && tabs.map { it.recoveryId }.distinct().size == tabs.size,
        "AMBIGUOUS_TAB_IDENTITY")
    val creating = tabs.filter { it.creating }
    requireRescue(creating.size == 1, "CREATE_TAB_NOT_UNIQUE")
    val tab = creating.single()
    requireRescue(selectedKey == null || tab.draftRecoveryKey() == selectedKey, "CREATE_TAB_MISMATCH")
    val commandKey = commandKeys.single()
    val command = readRescueCreateCommand(source, commandKey)
    requireRescue(command.normalized() == command && command.matches(tab), "INVALID_CREATE_PAIR")
    requireRescue(command.parentId != command.documentId && command.documentId !in tab.ancestorIds &&
        tabs.none { it !== tab && it.documentId == command.documentId }, "CREATE_PARENT_OR_IDENTITY_DEPENDENCY")
    validateRescueCreatePayload(tab, command)
    return CreateRescuePair(tab, command)
}

private fun readRescueRecord(source: DocumentDraftRecordSource, key: String): String {
    val size = source.recordByteCount(key)
    requireRescue(size != null && size in 1L..MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong(), "RECORD_UNAVAILABLE")
    val encoded = source.readRecord(key) ?: rescueFailed("RECORD_UNAVAILABLE")
    requireRescue(encoded.length <= MAX_DOCUMENT_DRAFT_RECORD_BYTES &&
        encoded.encodeToByteArray(throwOnInvalidSequence = true).size.toLong() == size, "RECORD_SIZE_MISMATCH")
    return encoded
}

internal fun readRescueTab(source: DocumentDraftRecordSource, key: String): DocumentTabState {
    val tab = decodeRescueRecord<PersistedDocumentTabDraft>(readRescueRecord(source, key), MAX_DOCUMENT_DRAFT_RECORD_BYTES).toTab()
    requireRescue(tab.draftRecoveryKey() == key, "RECORD_IDENTITY_MISMATCH")
    return tab
}

internal fun validateRescueTab(tab: DocumentTabState, includeContent: Boolean) {
    val snapshot = DocumentWorkspaceDraftSnapshot(listOf(tab), tab.instanceId, tab.spaceId)
    requireRescue(snapshot.normalized() == snapshot && snapshot.hasBoundedPersistenceShape(), "INVALID_DRAFT")
    requireRescue(tab.editGeneration in 0L until Long.MAX_VALUE &&
        (tab.revision == null || tab.revision in 1L until Long.MAX_VALUE) &&
        (tab.documentId == null || tab.documentId.isNotBlank()), "INVALID_DRAFT")
    if (includeContent) {
        // Only admitted commands normalize titles. An ordinary or later edit may be blank.
        DocumentPolicy.validateMarkdownEnvelope(tab.savedMarkdown)
        DocumentPolicy.validateMarkdownEnvelope(tab.draftMarkdown)
        MarkdownAssetPolicy.requireCanonical(tab.savedMarkdown, tab.savedAssets)
        MarkdownAssetPolicy.requireCanonical(tab.draftMarkdown, tab.draftAssets)
    }
}

internal fun requireRescueRecordBudget(source: DocumentDraftRecordSource, keys: List<String>) {
    var totalBytes = 0L
    for (key in keys) {
        val size = source.recordByteCount(key)
        requireRescue(size != null && size in 1L..MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong(), "RECORD_UNAVAILABLE")
        totalBytes += checkNotNull(size)
        requireRescue(totalBytes <= MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES, "SOURCE_SIZE_LIMIT")
    }
}

internal fun readRescueCreateCommand(source: DocumentDraftRecordSource, key: String): PendingDocumentCreateCommand {
    val command = decodeRescueRecord<PersistedDocumentCreateCommand>(readRescueRecord(source, key),
        MAX_DOCUMENT_DRAFT_RECORD_BYTES).toCommand()
    requireRescue(command.draftRecoveryKey() == key, "RECORD_IDENTITY_MISMATCH")
    return command
}

internal fun validateRescueCreatePayload(tab: DocumentTabState, command: PendingDocumentCreateCommand) {
    // At the admitted generation an unequal payload would be overwritten by the normal ACK merge.
    // A later generation is user work and must stay separate from the immutable request.
    requireRescue(tab.editGeneration != command.admittedEditGeneration ||
        PendingDocumentCreateCommand.capture(tab) == command, "CREATE_GENERATION_PAYLOAD_MISMATCH")
    validateRescueTab(tab, includeContent = true)
    MarkdownAssetPolicy.requireCanonical(command.markdown, command.assets)
}
