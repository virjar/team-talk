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
)

/** The payload contains private content and is for the existing persistence writer only. */
class DocumentDraftRescuePrepared(
    val metadata: DocumentDraftRescueMetadata,
    val payload: DocumentDraftPayload,
)

/** Selects one ordinary draft without mutating storage or admitting any remote operation. */
object DocumentDraftRescue {
    /** Lists live manifest identities only; availability and validity require [prepare]. */
    fun recordKeys(source: DocumentDraftRecordSource): List<String> = documentDraftRescueGuarded {
        readRescueRecordKeys(source)
    }

    fun prepare(source: DocumentDraftRecordSource, recordKey: String): DocumentDraftRescuePrepared = documentDraftRescueGuarded {
        requireRescue(recordKey.isDocumentDraftRecordKey(), "INVALID_RECORD_KEY")
        requireRescue(recordKey in readRescueRecordKeys(source), "RECORD_UNAVAILABLE_OR_RETIRED")
        val expectedBytes = source.recordByteCount(recordKey)
        requireRescue(expectedBytes != null && expectedBytes in 1L..MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong(),
            "RECORD_UNAVAILABLE")
        val encoded = source.readRecord(recordKey) ?: rescueFailed("RECORD_UNAVAILABLE")
        requireRescue(encoded.length <= MAX_DOCUMENT_DRAFT_RECORD_BYTES &&
            encoded.encodeToByteArray(throwOnInvalidSequence = true).size.toLong() == expectedBytes,
            "RECORD_SIZE_MISMATCH")
        val persisted = decodeRescueRecord<PersistedDocumentTabDraft>(encoded, MAX_DOCUMENT_DRAFT_RECORD_BYTES)
        val tab = persisted.toTab()
        requireRescue(tab.draftRecoveryKey() == recordKey, "RECORD_IDENTITY_MISMATCH")
        val snapshot = DocumentWorkspaceDraftSnapshot(listOf(tab), tab.instanceId, tab.spaceId)
        requireRescue(snapshot.normalized() == snapshot && snapshot.hasBoundedPersistenceShape(), "INVALID_DRAFT")
        requireRescue(tab.editGeneration in 0L until Long.MAX_VALUE &&
            (tab.revision == null || tab.revision in 1L until Long.MAX_VALUE) &&
            (tab.documentId == null || tab.documentId.isNotBlank()), "INVALID_DRAFT")
        // Draft titles are editable input, including blank text and whitespace. Do not normalize
        // them as a submitted command or silently rewrite any saved baseline or local body.
        DocumentPolicy.validateMarkdownEnvelope(tab.savedMarkdown)
        DocumentPolicy.validateMarkdownEnvelope(tab.draftMarkdown)
        MarkdownAssetPolicy.requireCanonical(tab.savedMarkdown, tab.savedAssets)
        MarkdownAssetPolicy.requireCanonical(tab.draftMarkdown, tab.draftAssets)
        DocumentDraftRescuePrepared(
            metadata = DocumentDraftRescueMetadata(recordKey, tab.tabId, tab.instanceId, tab.recoveryId,
                tab.documentId, tab.spaceId, tab.revision, tab.creating, tab.draftMarkdown.length, tab.draftAssets.size),
            payload = encodeDocumentDraftPayload(snapshot),
        )
    }
}

class DocumentDraftRescueException internal constructor(val reason: String) :
    IllegalStateException("Document draft rescue failed: $reason")

private fun rescueFailed(reason: String): Nothing = throw DocumentDraftRescueException(reason)
private fun requireRescue(condition: Boolean, reason: String) {
    if (!condition) rescueFailed(reason)
}

private inline fun <T> documentDraftRescueGuarded(action: () -> T): T = try {
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

private inline fun <reified T> decodeRescueRecord(encoded: String, maximumBytes: Int): T {
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
