package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.requireChatDraftChatId
import com.virjar.tk.shared.database.AppDatabase
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

internal data class ChatDraftRescueSource(
    val owner: LocalCacheDiagnosticOwner,
    val layout: LocalCacheDiagnosticLayout,
    val manifestSha256: String,
    val sourceDatabase: String,
    val snapshot: ChatDraftSnapshot,
    val uploads: List<ChatAssetUpload>,
    val sources: List<LocalCacheArchiveFile>,
)

/** Read an explicitly selected draft; neither the archive nor its source installation enters JDBC. */
internal fun readChatDraftRescueSource(
    archive: File,
    sourceDatabase: String,
    chatId: String,
    expectedManifestSha256: String? = null,
): ChatDraftRescueSource {
    var temporary: Path? = null
    try {
        requireChatDraftChatId(chatId)
        val root = archiveRoot(archive)
        val verified = LocalCacheArchive.verify(archive)
        if (expectedManifestSha256 != null && verified.manifestSha256 != expectedManifestSha256)
            rescueFailure("ARCHIVE_CONFIRMATION_MISMATCH")
        val encoded = readArchiveManifest(root.resolve("manifest.json"))
        if (archiveSha256(encoded.toByteArray(Charsets.UTF_8)) != verified.manifestSha256)
            rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
        val version = rescueJson.parseToJsonElement(encoded).let { it as? JsonObject }
            ?.get("formatVersion")?.jsonPrimitive?.intOrNull
        val (owner, layout, files) = when (version) {
            1 -> rescueJson.decodeFromString<LocalCacheArchiveManifest>(encoded).let { Triple(it.owner, it.layout, it.files) }
            2 -> rescueJson.decodeFromString<LocalCacheNamespaceArchiveManifest>(encoded).let { Triple(it.owner, it.layout, it.files) }
            else -> rescueFailure("UNSUPPORTED_ARCHIVE_FORMAT")
        }
        val main = files.singleOrNull { it.path == sourceDatabase && it.category in DATABASE_CATEGORIES }
            ?: rescueFailure("SOURCE_DATABASE_NOT_IN_ARCHIVE")
        val mainName = sourceDatabase.substringAfterLast('/')
        val databaseName = if (layout == LocalCacheDiagnosticLayout.JVM) Regex("cache_e[0-9]+\\.db") else
            Regex("cache_e[0-9]+" + Regex.escape("_${owner.deploymentFingerprint}_${owner.datasetId}_${owner.uid}.db") +
                "(?:\\.corrupt-[A-Za-z0-9-]+)?")
        if (!databaseName.matches(mainName)) rescueFailure("SOURCE_SELECTION_NOT_DATABASE_MAIN")
        val family = listOf("", "-wal", "-journal").mapNotNull { suffix ->
            files.singleOrNull { it.path == sourceDatabase + suffix }?.also {
                if (it.category != main.category) rescueFailure("SOURCE_DATABASE_FAMILY_INVALID")
            }
        }
        if (family.sumOf { it.bytes } > LocalCacheDiagnostics.MAX_DATABASE_BYTES)
            rescueFailure("SOURCE_DATABASE_SIZE_LIMIT")
        temporary = Files.createTempDirectory("teamtalk-draft-rescue-")
        val security = JvmPrivatePathSecurity.forPath(temporary, JvmFileSystemIdentity.currentOwner(temporary))
        for (file in family) {
            val copy = temporary.resolve("cache.db" + file.path.removePrefix(sourceDatabase))
            security.createEmptyFile(copy)
            if (digestArchiveFile(root, root.resolve("payload").resolve(file.path), file.bytes, copy) != file.sha256)
                rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
        }
        Class.forName("org.sqlite.JDBC")
        // Recovery/checkpoint may affect only this disposable family. Do not use immutable=1 (which
        // ignores WAL) or a normal cache factory (which can migrate or garbage-collect local facts).
        val result = DriverManager.getConnection("jdbc:sqlite:${temporary.resolve("cache.db").toUri()}?mode=rw").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA query_only = ON")
                statement.execute("PRAGMA trusted_schema = OFF")
                statement.execute("PRAGMA busy_timeout = 1000")
            }
            connection.readRescueDraft(owner, layout, verified.manifestSha256, sourceDatabase, chatId, files)
        }
        if (LocalCacheArchive.verify(archive).manifestSha256 != verified.manifestSha256)
            rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
        return result
    } catch (failure: LocalCacheChatDraftRescueFailure) {
        throw failure
    } catch (_: Exception) {
        // SQL, decoder and filesystem exception messages can contain private text or paths.
        rescueFailure("SOURCE_DRAFT_UNREADABLE_OR_INVALID")
    } finally {
        temporary?.let { directory ->
            try { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
            catch (_: Exception) { rescueFailure("PRIVATE_TEMP_CLEANUP_FAILED") }
        }
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
    val schema = createStatement().use { it.executeQuery("PRAGMA user_version").use { row ->
        if (!row.next()) rescueFailure("SOURCE_SCHEMA_UNREADABLE")
        row.rescueLong(1)
    } }
    if (schema != AppDatabase.Schema.version) rescueFailure("SOURCE_SCHEMA_UNSUPPORTED")
    // Do not gate healthy draft pages on unrelated message/history corruption. These fixed queries
    // deliberately bypass indexes so a damaged index cannot make a dependency appear absent.
    for (table in REQUIRED_TABLES) {
        val kind = rescueRows("SELECT type, CASE WHEN length(CAST(sql AS BLOB)) <= 65536 THEN sql END FROM sqlite_master WHERE name = ? LIMIT 2", listOf(table)) { row ->
            row.rescueText(1) to row.rescueText(2)
        }.singleOrNull() ?: rescueFailure("SOURCE_SCHEMA_UNSUPPORTED")
        if (kind.first != "table" || kind.second.trimStart().startsWith("CREATE VIRTUAL", ignoreCase = true))
            rescueFailure("SOURCE_SCHEMA_UNSUPPORTED")
    }
    val datasets = rescueRows("SELECT singleton_id, dataset_id, cursor FROM sync_state NOT INDEXED LIMIT 2") { row ->
        Triple(row.rescueLong(1), row.rescueText(2), row.rescueLong(3))
    }
    if (datasets.size != 1 || datasets.single().let { it.first != 1L || it.second != owner.datasetId || it.third < 0 })
        rescueFailure("SOURCE_DATASET_MISMATCH")
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
        val job = rescueDecode<ChatAssetUpload>(row.rescueBlob(5, MAX_JOB_BYTES), UPLOAD_REQUIRED)
        if (row.rescueText(1) != job.assetId || row.rescueText(2) != chatId || job.chatId != chatId ||
            row.rescueText(3) != job.sourceId || row.rescueLong(4) != 1L || job.assetId !in ids)
            rescueFailure("SOURCE_UPLOAD_REFERENCE_MISMATCH")
        validateRescueUpload(job)
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

private fun validateRescueAsset(asset: EmbeddedAsset) {
    if (MarkdownAssetPolicy.canonicalize("[asset](${EmbeddedAsset.uri(asset.assetId)})", listOf(asset)) != listOf(asset))
        rescueFailure("SOURCE_ASSET_DESCRIPTOR_INVALID")
}

private fun <T> Connection.rescueRows(sql: String, values: List<String> = emptyList(), read: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }

private fun ResultSet.rescueLong(index: Int): Long = when (val value = getObject(index)) {
    is Int -> value.toLong()
    is Long -> value
    else -> rescueFailure("SOURCE_SQL_VALUE_INVALID")
}
private fun ResultSet.rescueText(index: Int): String = strictRescueUtf8(rescueTextBytes(index, MAX_SYNC_BYTES))
private fun ResultSet.rescueTextBytes(index: Int, max: Int): ByteArray {
    if (getObject(index) !is String) rescueFailure("SOURCE_SQL_VALUE_INVALID")
    return getBytes(index).also { if (it.size > max) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT") }
}
private fun ResultSet.rescueBlob(index: Int, max: Int): ByteArray = (getObject(index) as? ByteArray)
    ?.also { if (it.size > max) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT") } ?: rescueFailure("SOURCE_SQL_VALUE_INVALID")

private inline fun <reified T> rescueDecode(bytes: ByteArray, required: Set<String>): T {
    val encoded = strictRescueUtf8(bytes)
    rejectDuplicateRescueJsonKeys(encoded)
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

/** Strict decoding also rejects duplicate keys, which otherwise silently keep only the last value. */
private fun rejectDuplicateRescueJsonKeys(encoded: String) {
    val frames = mutableListOf<MutableSet<String>?>()
    var index = 0
    while (index < encoded.length) {
        when (encoded[index]) {
            '{', '[' -> { frames.add(if (encoded[index] == '{') mutableSetOf() else null)
                if (frames.size > 64) rescueFailure("SOURCE_JSON_SHAPE_INVALID") }
            '}', ']' -> { if (frames.isEmpty()) rescueFailure("SOURCE_JSON_SHAPE_INVALID"); frames.removeAt(frames.lastIndex) }
            '"' -> {
                val start = index++
                while (index < encoded.length && encoded[index] != '"') {
                    if (encoded[index] == '\\') index++
                    index++
                }
                if (index >= encoded.length) rescueFailure("SOURCE_JSON_SHAPE_INVALID")
                var next = index + 1
                while (next < encoded.length && encoded[next].isWhitespace()) next++
                if (encoded.getOrNull(next) == ':') {
                    val keys = frames.lastOrNull() ?: rescueFailure("SOURCE_JSON_SHAPE_INVALID")
                    if (!keys.add(rescueJson.decodeFromString<String>(encoded.substring(start, index + 1))))
                        rescueFailure("SOURCE_JSON_SHAPE_INVALID")
                }
            }
        }
        index++
    }
}

private fun strictRescueUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

private val rescueJson = Json { encodeDefaults = true }
private val DATABASE_CATEGORIES = setOf("QUARANTINE", "ACTIVE_DATABASE", "DATABASES")
private val REQUIRED_TABLES = setOf("sync_state", "chat_composer_draft", "chat_asset_upload", "chat_draft_sync",
    "outgoing_chat_asset", "conversation_draft_outbox", "outgoing_message")
private val SNAPSHOT_REQUIRED = setOf("chatId", "revision", "markdown", "assets", "pendingAssetIds", "mode",
    "selectionStart", "selectionEnd", "replyToClientMsgId", "replyToServerSeq")
private val UPLOAD_REQUIRED = setOf("assetId", "chatId", "sourceId", "length", "sha256", "fileName", "contentType",
    "isImage", "uploadId", "issuedAt", "state", "attempt", "nextAttemptAt", "asset", "failure")
private val SYNC_REQUIRED = setOf("chatId", "remote", "stale", "requiredRevision", "localRevision", "dirty", "pending",
    "consume", "conflict", "failure", "pendingRejected")
private const val MAX_DRAFT_BYTES = 8 * 1024 * 1024
private const val MAX_SYNC_BYTES = 16 * 1024 * 1024
private const val MAX_JOB_BYTES = 64 * 1024
