package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.requireChatDraftChatId
import com.virjar.tk.shared.repository.chatAssetSpoolDirectories
import com.virjar.tk.shared.repository.sanitizeMultipartContentType
import com.virjar.tk.shared.repository.sanitizeMultipartFileName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.sql.Connection

internal data class ChatDraftRescueSource(
    val owner: LocalCacheDiagnosticOwner,
    val layout: LocalCacheDiagnosticLayout,
    val manifestSha256: String,
    val sourceDatabase: String,
    val snapshot: ChatDraftSnapshot,
    val uploads: List<ChatAssetUpload>,
    val sources: List<LocalCacheArchiveFile>,
)

/** Read the complete draft graph without opening the original installation in SQLite. */
internal fun readChatDraftRescueSource(
    archive: File, sourceDatabase: String, chatId: String, expectedManifestSha256: String? = null,
): ChatDraftRescueSource {
    requireChatDraftChatId(chatId)
    return readCacheRescueArchiveDatabase(archive, sourceDatabase, expectedManifestSha256) { connection, source ->
        connection.readRescueDraft(source.owner, source.layout, source.manifestSha256, source.sourceDatabase, chatId, source.files)
    }
}

private fun Connection.readRescueDraft(
    owner: LocalCacheDiagnosticOwner,
    layout: LocalCacheDiagnosticLayout,
    manifestHash: String,
    sourceDatabase: String,
    chatId: String,
    files: List<LocalCacheArchiveFile>,
): ChatDraftRescueSource {
    requireRescueSourceSchema(owner, REQUIRED_TABLES)
    val snapshots = rescueRows(
        "SELECT chat_id, revision, is_empty, CASE WHEN length(payload) <= $MAX_DRAFT_BYTES THEN payload END " +
            "FROM chat_composer_draft NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? LIMIT 2", listOf(chatId),
    ) { row ->
        val snapshot = decodeRescueComposer(row.rescueBlob(4, MAX_DRAFT_BYTES), chatId)
        if (row.rescueText(1) != chatId || snapshot.chatId != chatId || row.rescueLong(2) != snapshot.revision ||
            row.rescueLong(3) != if (snapshot.markdown.isEmpty() && snapshot.replyToClientMsgId == null) 1L else 0L)
            rescueFailure("SOURCE_DRAFT_ROW_MISMATCH")
        snapshot
    }
    val snapshot = snapshots.singleOrNull() ?: rescueFailure("SOURCE_DRAFT_MISSING_OR_AMBIGUOUS")
    validateRescueSnapshot(snapshot)
    if (snapshot.markdown.isEmpty() && snapshot.replyToClientMsgId == null) rescueFailure("SOURCE_DRAFT_EMPTY")
    val sync = rescueRows("SELECT chat_id, CASE WHEN length(CAST(payload AS BLOB)) <= $MAX_SYNC_BYTES THEN payload END " +
        "FROM chat_draft_sync NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? LIMIT 2", listOf(chatId)) { row ->
        if (row.rescueText(1) != chatId) rescueFailure("SOURCE_SYNC_INVALID")
        decodeRescueSyncRecord(strictRescueUtf8(row.rescueTextBytes(2, MAX_SYNC_BYTES)), chatId)
    }
    if (sync.size > 1) rescueFailure("SOURCE_SYNC_INVALID")
    sync.singleOrNull()?.let { record ->
        if (record.chatId != chatId || record.localRevision < 0 || record.requiredRevision < 0 || record.acceptedBaseFloor < 0 ||
            (record.remote != null && record.remote.chatId != chatId)) rescueFailure("SOURCE_SYNC_INVALID")
        if (record.pending != null || record.consume != null || record.pendingRejected)
            rescueFailure("SOURCE_SYNC_HAS_PENDING_DEPENDENCIES")
    }
    if (rescueRows("SELECT chat_id FROM conversation_draft_outbox NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? LIMIT 1", listOf(chatId)) { true }.isNotEmpty())
        rescueFailure("SOURCE_LEGACY_DRAFT_DEPENDENCY")
    val outgoing = rescueRows("SELECT chat_id, state FROM outgoing_message NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? LIMIT 8193", listOf(chatId)) {
        if (it.rescueText(1) != chatId) rescueFailure("SOURCE_OUTGOING_DEPENDENCY")
        it.rescueLong(2)
    }
    if (outgoing.size > 8192 || outgoing.any { it != OutgoingMessageState.SUCCESS.code })
        rescueFailure("SOURCE_OUTGOING_DEPENDENCY")
    val references = MarkdownAssetPolicy.references(snapshot.markdown)
    val ids = references.mapTo(linkedSetOf()) { it.assetId ?: rescueFailure("SOURCE_ASSET_REFERENCES_INVALID") }
    if (ids.size > EmbeddedAsset.MAX_ASSETS_PER_CONTENT) rescueFailure("SOURCE_ASSET_REFERENCES_INVALID")
    val placeholders = if (ids.isEmpty()) "" else " OR CAST(asset_id AS TEXT) IN (${ids.joinToString(",") { "?" }})"
    val uploads = rescueRows(
        "SELECT asset_id, chat_id, source_id, referenced, CASE WHEN length(payload) <= $MAX_JOB_BYTES THEN payload END " +
            "FROM chat_asset_upload NOT INDEXED WHERE CAST(chat_id AS TEXT) = ?$placeholders LIMIT 129", listOf(chatId) + ids,
    ) { row ->
        val job = decodeRescueUpload(row.rescueBlob(5, MAX_JOB_BYTES))
        if (row.rescueText(1) != job.assetId || row.rescueText(2) != chatId || job.chatId != chatId ||
            row.rescueText(3) != job.sourceId || row.rescueLong(4) != 1L || job.assetId !in ids)
            rescueFailure("SOURCE_UPLOAD_REFERENCE_MISMATCH")
        job
    }
    if (uploads.size > 128 || uploads.map { it.assetId }.distinct().size != uploads.size ||
        uploads.map { it.sourceId }.distinct().size != uploads.size || uploads.sumOf { it.length } > AttachmentPolicy.MAX_UPLOAD_BYTES)
        rescueFailure("SOURCE_UPLOAD_LIMIT_OR_DUPLICATE")
    // Also detect cross-chat links to a selected id. Only bounded owner-local metadata is read.
    if (rescueRows("SELECT asset_id FROM outgoing_chat_asset NOT INDEXED WHERE CAST(chat_id AS TEXT) = ?$placeholders LIMIT 1",
            listOf(chatId) + ids) { true }.isNotEmpty()) rescueFailure("SOURCE_OUTGOING_DEPENDENCY")
    val jobs = uploads.associateBy { it.assetId }
    val declared = snapshot.assets.associateBy { it.assetId }
    if (declared.size != snapshot.assets.size || snapshot.pendingAssetIds.distinct().size != snapshot.pendingAssetIds.size ||
        declared.keys.any { it !in ids } || snapshot.pendingAssetIds.any { it !in ids }) rescueFailure("SOURCE_ASSET_REFERENCES_INVALID")
    val effective = linkedMapOf<String, EmbeddedAsset>()
    val pending = mutableListOf<String>()
    for (id in ids) {
        val job = jobs[id]
        if (job == null) {
            if (id in snapshot.pendingAssetIds) rescueFailure("SOURCE_UPLOAD_MISSING")
            effective[id] = declared[id] ?: rescueFailure("SOURCE_ASSET_DESCRIPTOR_MISSING")
        } else if (job.state == ChatAssetUploadState.READY) {
            val asset = checkNotNull(job.asset)
            if (declared[id] != null && declared[id] != asset) rescueFailure("SOURCE_READY_DESCRIPTOR_MISMATCH")
            effective[id] = asset
        } else {
            // Expiry can leave the old descriptor in the composer while the durable job becomes
            // FAILED with asset=null. Preserve its source and expose this reference as pending.
            declared[id]?.let { validateRescueUploadedMetadata(job, it) }
            pending += id
        }
    }
    references.filter { it.presentation == EmbeddedAssetPresentation.IMAGE }.forEach { reference ->
        val id = checkNotNull(reference.assetId)
        val type = effective[id]?.attachment?.contentType ?: jobs[id]?.let { sanitizeMultipartContentType(it.contentType) }
        if (type?.startsWith("image/", ignoreCase = true) != true) rescueFailure("SOURCE_IMAGE_REFERENCE_INVALID")
    }
    val prefix = (if (layout == LocalCacheDiagnosticLayout.ANDROID) listOf("no_backup") else emptyList()) +
        chatAssetSpoolDirectories(AccountDataOwner(owner.deploymentFingerprint, owner.datasetId, owner.uid))
    val sources = uploads.map { job ->
        files.singleOrNull { it.path == prefix.joinToString("/") + "/${job.sourceId}.${job.sha256}.blob" && it.category == "CHAT_SOURCES" }
            ?.takeIf { it.bytes == job.length && it.sha256 == job.sha256 }
            ?: rescueFailure("SOURCE_ATTACHMENT_BYTES_MISSING_OR_MISMATCH")
    }
    return ChatDraftRescueSource(owner, layout, manifestHash, sourceDatabase,
        snapshot.copy(assets = effective.values.toList(), pendingAssetIds = pending), uploads, sources)
}

private fun validateRescueSnapshot(snapshot: ChatDraftSnapshot) {
    if (snapshot.revision <= 0 || snapshot.mode !in 0..2 || snapshot.selectionStart < 0 || snapshot.selectionEnd < 0 ||
        (snapshot.sharedRevision ?: 0) < 0) rescueFailure("SOURCE_DRAFT_INVALID")
    MessageBodyPolicy.validateMarkdown(snapshot.markdown)
    if (snapshot.replyToServerSeq < 0 || ((snapshot.replyToClientMsgId == null) != (snapshot.replyToServerSeq == 0L)))
        rescueFailure("SOURCE_LOCAL_REPLY_DEPENDENCY")
    snapshot.replyToClientMsgId?.let { if (it.isBlank() || it.length > 64) rescueFailure("SOURCE_DRAFT_INVALID") }
    if (snapshot.assets.size > EmbeddedAsset.MAX_ASSETS_PER_CONTENT || snapshot.pendingAssetIds.size > EmbeddedAsset.MAX_ASSETS_PER_CONTENT)
        rescueFailure("SOURCE_ASSET_REFERENCES_INVALID")
    snapshot.assets.forEach(::validateRescueAsset)
    snapshot.pendingAssetIds.forEach(EmbeddedAsset::requireCanonicalAssetId)
}

internal fun decodeRescueUpload(bytes: ByteArray): ChatAssetUpload =
    rescueDecode<ChatAssetUpload>(bytes, UPLOAD_REQUIRED).also(::validateRescueUpload)

private fun validateRescueUpload(job: ChatAssetUpload) {
    EmbeddedAsset.requireCanonicalAssetId(job.assetId)
    EmbeddedAsset.requireCanonicalAssetId(job.sourceId)
    AttachmentUploadIdentity(job.uploadId, job.issuedAt) // Historical source bytes do not expire with an HTTP identity.
    if (job.length !in 0..AttachmentPolicy.MAX_UPLOAD_BYTES || !job.sha256.matches(Regex("[0-9a-f]{64}")) ||
        job.fileName.isBlank() || job.fileName.length > AttachmentPolicy.MAX_NAME_LENGTH ||
        job.contentType.isBlank() || job.contentType.length > AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH ||
        job.attempt < 0 || job.nextAttemptAt < 0 || (job.failure?.length ?: 0) > 500)
        rescueFailure("SOURCE_UPLOAD_INVALID")
    if (job.repairForClientMsgId != null) rescueFailure("SOURCE_UPLOAD_REPAIR_DEPENDENCY")
    job.asset?.let { asset ->
        validateRescueAsset(asset)
        validateRescueUploadedMetadata(job, asset)
    }
    if (job.state == ChatAssetUploadState.READY && job.asset == null) rescueFailure("SOURCE_READY_DESCRIPTOR_MISSING")
}

private fun validateRescueUploadedMetadata(job: ChatAssetUpload, asset: EmbeddedAsset) {
    if (asset.assetId != job.assetId || asset.attachment.size != job.length ||
        asset.attachment.name != sanitizeMultipartFileName(job.fileName) ||
        asset.attachment.contentType != sanitizeMultipartContentType(job.contentType)) rescueFailure("SOURCE_READY_DESCRIPTOR_MISMATCH")
}

internal fun validateRescueAsset(asset: EmbeddedAsset) {
    if (MarkdownAssetPolicy.canonicalize("[asset](${EmbeddedAsset.uri(asset.assetId)})", listOf(asset)) != listOf(asset))
        rescueFailure("SOURCE_ASSET_DESCRIPTOR_INVALID")
}

private inline fun <reified T> rescueDecode(bytes: ByteArray, required: Set<String>): T {
    val encoded = strictRescueUtf8(bytes)
    try { requireUnambiguousRecoveryJson(encoded) }
    catch (_: Exception) { rescueFailure("SOURCE_JSON_SHAPE_INVALID") }
    val objectValue = rescueJson.parseToJsonElement(encoded) as? JsonObject ?: rescueFailure("SOURCE_JSON_SHAPE_INVALID")
    if (!objectValue.keys.containsAll(required)) rescueFailure("SOURCE_JSON_SHAPE_INVALID")
    validateRescueJsonTypes(objectValue)
    return rescueJson.decodeFromString<T>(encoded)
}

/** Shared structural decoding only; source and target apply their own reliable-state admission. */
internal fun decodeRescueComposer(bytes: ByteArray, chatId: String): ChatDraftSnapshot {
    if (bytes.size > MAX_DRAFT_BYTES) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT")
    return rescueDecode<ChatDraftSnapshot>(bytes, SNAPSHOT_REQUIRED).also {
        if (it.chatId != chatId) rescueFailure("SOURCE_DRAFT_ROW_MISMATCH")
    }
}

/** Shared structural decoding only; source and target apply their own reliable-state admission. */
internal fun decodeRescueSyncRecord(encoded: String, chatId: String): StoredChatDraftSyncRecord {
    if (encoded.length > MAX_SYNC_BYTES) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT")
    val bytes = encoded.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_SYNC_BYTES) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT")
    return rescueDecode<StoredChatDraftSyncRecord>(bytes, SYNC_REQUIRED).also {
        if (it.chatId != chatId) rescueFailure("SOURCE_SYNC_INVALID")
    }
}

private fun validateRescueJsonTypes(value: JsonObject) {
    val booleans = setOf("stale", "dirty", "conflict", "pendingRejected", "isImage")
    val integers = setOf("revision", "mode", "selectionStart", "selectionEnd", "replyToServerSeq", "sharedRevision",
        "requiredRevision", "localRevision", "acceptedBaseFloor", "length", "issuedAt", "attempt", "nextAttemptAt")
    val objects = setOf("remote", "pending", "consume", "asset")
    val arrays = setOf("assets", "pendingAssetIds")
    for ((key, element) in value) {
        val valid = when {
            key in booleans -> element is JsonPrimitive && !element.isString && element.booleanOrNull != null
            key in integers -> element == JsonNull && key == "sharedRevision" ||
                element is JsonPrimitive && !element.isString && element.longOrNull != null
            key in objects -> element == JsonNull || element is JsonObject
            key in arrays -> element is JsonArray
            else -> element == JsonNull || element is JsonPrimitive && element.isString
        }
        if (!valid) rescueFailure("SOURCE_JSON_SHAPE_INVALID")
    }
}

internal fun strictRescueUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

private val rescueJson = Json { encodeDefaults = true }
private val REQUIRED_TABLES = setOf("sync_state", "chat_composer_draft", "chat_asset_upload", "chat_draft_sync",
    "outgoing_chat_asset", "conversation_draft_outbox", "outgoing_message")
private val SNAPSHOT_REQUIRED = setOf("chatId", "revision", "markdown", "assets", "pendingAssetIds", "mode",
    "selectionStart", "selectionEnd", "replyToClientMsgId", "replyToServerSeq")
private val UPLOAD_REQUIRED = setOf("assetId", "chatId", "sourceId", "length", "sha256", "fileName", "contentType",
    "isImage", "uploadId", "issuedAt", "state", "attempt", "nextAttemptAt", "asset", "failure")
private val SYNC_REQUIRED = setOf("chatId", "remote", "stale", "requiredRevision", "localRevision", "dirty", "pending",
    "consume", "conflict", "failure", "pendingRejected")
private const val MAX_DRAFT_BYTES = 8 * 1024 * 1024
internal const val MAX_SYNC_BYTES = 16 * 1024 * 1024
private const val MAX_JOB_BYTES = 64 * 1024
