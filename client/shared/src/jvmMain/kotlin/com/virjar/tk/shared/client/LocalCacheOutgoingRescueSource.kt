package com.virjar.tk.shared.client

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolLimits
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.database.Outgoing_message
import com.virjar.tk.shared.repository.chatAssetSpoolDirectories
import com.virjar.tk.shared.repository.sanitizeMultipartContentType
import com.virjar.tk.shared.repository.sanitizeMultipartFileName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.sql.Connection
import java.sql.ResultSet

internal data class OutgoingRescueSource(
    val archive: CacheRescueArchive,
    val outgoing: Outgoing_message,
    val message: Message,
    val uploads: List<ChatAssetUpload>,
    val sources: List<LocalCacheArchiveFile>,
    val consumption: StoredChatDraftConsumption?,
)

/** Inspect one immutable request in a disposable SQLite copy; never run cache recovery or uploads. */
internal fun readOutgoingRescueSource(
    archive: File,
    sourceDatabase: String,
    chatId: String,
    clientMsgId: String,
    expectedManifestSha256: String? = null,
): OutgoingRescueSource {
    if (chatId.isBlank() || chatId.length > MessageBodyPolicy.MAX_CHAT_ID_LENGTH || '\u0000' in chatId ||
        clientMsgId.isBlank() || clientMsgId.length > MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH || '\u0000' in clientMsgId)
        rescueFailure("SOURCE_OUTGOING_IDENTITY_MISMATCH")
    return readCacheRescueArchiveDatabase(archive, sourceDatabase, expectedManifestSha256) { connection, source ->
        connection.requireRescueSourceSchema(source.owner, OUTGOING_SOURCE_TABLES)
        val outgoing = connection.readSelectedOutgoing(chatId, clientMsgId)
        val message = decodeOutgoingRescueMessage(outgoing, source.owner)
        connection.requireUnconfirmedOutgoingProjection(chatId, clientMsgId, source.owner.uid)
        connection.requireNoLocalReplyDependency(message)
        val consumption = connection.readOutgoingConsumption(chatId, clientMsgId)
        val assets = when (val body = message.body) {
            is RichTextBody -> body.assets
            is ReplyBody -> body.assets
            else -> emptyList()
        }
        val uploads = connection.readOutgoingSourceUploads(message, assets)
        connection.requireUnsharedOutgoingComposer(chatId, consumption, uploads.mapTo(hashSetOf()) { it.assetId })
        val prefix = (if (source.layout == LocalCacheDiagnosticLayout.ANDROID) listOf("no_backup") else emptyList()) +
            chatAssetSpoolDirectories(AccountDataOwner(source.owner.deploymentFingerprint, source.owner.datasetId, source.owner.uid))
        val sources = uploads.map { job ->
            source.files.singleOrNull {
                it.category == "CHAT_SOURCES" && it.path == prefix.joinToString("/") + "/${job.sourceId}.${job.sha256}.blob"
            }?.takeIf { it.bytes == job.length && it.sha256 == job.sha256 }
                ?: rescueFailure("SOURCE_ATTACHMENT_BYTES_MISSING_OR_MISMATCH")
        }
        OutgoingRescueSource(source, outgoing, message, uploads, sources, consumption)
    }
}

private fun Connection.readSelectedOutgoing(chatId: String, clientMsgId: String): Outgoing_message {
    val rows = rescueRows(
        "SELECT local_ordinal, client_msg_id, chat_id, sender_uid, " +
            "CASE WHEN length(payload) <= ${ProtocolLimits.MAX_PAYLOAD_SIZE} THEN payload END, " +
            "CASE WHEN length(request_fingerprint) <= $MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES THEN request_fingerprint END, " +
            "state, attempt_count, failure_code, " +
            "CASE WHEN length(CAST(last_error AS BLOB)) <= 4000 THEN last_error END, " +
            "terminal_code, server_seq, next_attempt_at, created_at, updated_at, completed_at, " +
            "request_fingerprint IS NOT NULL, last_error IS NOT NULL " +
            "FROM outgoing_message NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? AND CAST(client_msg_id AS TEXT) = ? LIMIT 2",
        listOf(chatId, clientMsgId),
    ) { row ->
        val fingerprint = if (row.rescueLong(17) == 0L) null else row.rescueBlob(6, MAX_OUTGOING_REQUEST_FINGERPRINT_BYTES)
        val error = if (row.rescueLong(18) == 0L) null else row.rescueText(10)
        Outgoing_message(row.rescueLong(1), row.rescueText(2), row.rescueText(3), row.rescueText(4),
            row.rescueBlob(5, ProtocolLimits.MAX_PAYLOAD_SIZE), fingerprint, row.rescueLong(7), row.rescueLong(8),
            row.outgoingNullableLong(9), error, row.outgoingNullableLong(11), row.outgoingNullableLong(12),
            row.rescueLong(13), row.rescueLong(14), row.rescueLong(15), row.outgoingNullableLong(16))
    }
    val row = rows.singleOrNull() ?: rescueFailure("SOURCE_OUTGOING_MISSING_OR_AMBIGUOUS")
    if (row.chat_id != chatId || row.client_msg_id != clientMsgId) rescueFailure("SOURCE_OUTGOING_IDENTITY_MISMATCH")
    if (row.state == OutgoingMessageState.SUCCESS.code || (row.server_seq ?: 0) > 0)
        rescueFailure("SOURCE_OUTGOING_SUCCESS")
    if (row.state !in 0L..3L || row.local_ordinal <= 0 || row.attempt_count < 0 || row.attempt_count == Long.MAX_VALUE ||
        row.next_attempt_at < 0 || row.created_at < 0 || row.updated_at < 0 || (row.server_seq ?: 0) < 0 ||
        (row.completed_at ?: 0) < 0 || row.failure_code?.let { it !in 1L..12L } == true ||
        row.terminal_code?.let { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() } == true ||
        (row.last_error?.length ?: 0) > MAX_OUTGOING_LAST_ERROR_CHARACTERS ||
        row.request_fingerprint?.isEmpty() == true)
        rescueFailure("SOURCE_OUTGOING_STATE_INVALID")
    when (OutgoingMessageState.fromCode(row.state)) {
        OutgoingMessageState.TERMINAL_FAILED -> if (row.failure_code == null || row.completed_at == null || row.next_attempt_at != 0L)
            rescueFailure("SOURCE_OUTGOING_STATE_INVALID")
        else -> {
            if (row.completed_at != null || row.terminal_code != null) rescueFailure("SOURCE_OUTGOING_STATE_INVALID")
            if (row.state == OutgoingMessageState.IN_FLIGHT.code &&
                (row.attempt_count == 0L || row.failure_code != null || row.last_error != null))
                rescueFailure("SOURCE_OUTGOING_STATE_INVALID")
            if (row.state == OutgoingMessageState.RETRY_WAIT.code && (row.attempt_count == 0L || row.failure_code == null))
                rescueFailure("SOURCE_OUTGOING_STATE_INVALID")
        }
    }
    return row
}

private fun decodeOutgoingRescueMessage(row: Outgoing_message, owner: LocalCacheDiagnosticOwner): Message {
    val message = try {
        val decoded = ProtoCodec.decode(Message, row.payload)
        if (MessageType.fromCode(decoded.messageType) !in RESCUABLE_MESSAGE_TYPES ||
            !ProtoCodec.encode(canonicalizeOutboundMessage(decoded)).contentEquals(row.payload))
            rescueFailure("SOURCE_OUTGOING_PAYLOAD_INVALID")
        decoded
    } catch (failure: LocalCacheRescueFailure) { throw failure }
    catch (_: Exception) { rescueFailure("SOURCE_OUTGOING_PAYLOAD_INVALID") }
    if (row.sender_uid != owner.uid || message.senderUid != owner.uid || message.chatId != row.chat_id ||
        message.clientMsgId != row.client_msg_id || message.serverSeq != 0L || message.timestamp < 0)
        rescueFailure("SOURCE_OUTGOING_IDENTITY_MISMATCH")
    // Do not rebuild reference previews: the first declaration, including Office/Task/Reply text,
    // is part of the server's dedup fingerprint even when its authoritative projection has changed.
    return message
}

private fun Connection.requireUnconfirmedOutgoingProjection(chatId: String, clientMsgId: String, uid: String) {
    val rows = rescueRows("SELECT chat_id, client_msg_id, sender_uid, server_seq FROM message NOT INDEXED " +
        "WHERE CAST(chat_id AS TEXT) = ? AND CAST(client_msg_id AS TEXT) = ? LIMIT 2", listOf(chatId, clientMsgId)) { row ->
        if (row.rescueText(1) != chatId || row.rescueText(2) != clientMsgId || row.rescueText(3) != uid)
            rescueFailure("SOURCE_OUTGOING_IDENTITY_MISMATCH")
        row.outgoingNullableLong(4) ?: 0L
    }
    if (rows.size > 1 || rows.any { it < 0 }) rescueFailure("SOURCE_OUTGOING_STATE_INVALID")
    if (rows.any { it > 0 }) rescueFailure("SOURCE_OUTGOING_SUCCESS")
}

private fun Connection.requireNoLocalReplyDependency(message: Message) {
    val reply = message.body as? ReplyBody ?: return
    if (reply.replyToMsgId == message.clientMsgId) rescueFailure("SOURCE_LOCAL_REPLY_DEPENDENCY")
    val projections = rescueRows("SELECT chat_id, client_msg_id, server_seq FROM message NOT INDEXED " +
        "WHERE CAST(chat_id AS TEXT) = ? AND CAST(client_msg_id AS TEXT) = ? LIMIT 2", listOf(message.chatId, reply.replyToMsgId)) { row ->
        if (row.rescueText(1) != message.chatId || row.rescueText(2) != reply.replyToMsgId)
            rescueFailure("SOURCE_LOCAL_REPLY_DEPENDENCY")
        row.outgoingNullableLong(3) ?: 0L
    }
    if (projections.size > 1 || projections.any { it <= 0 }) rescueFailure("SOURCE_LOCAL_REPLY_DEPENDENCY")
    if (projections.isNotEmpty()) return
    // A missing retained history row is normal; a second unconfirmed request is a dependency.
    val pending = rescueRows("SELECT state FROM outgoing_message NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? " +
        "AND CAST(client_msg_id AS TEXT) = ? LIMIT 2", listOf(message.chatId, reply.replyToMsgId)) { it.rescueLong(1) }
    if (pending.size > 1 || pending.any { it != OutgoingMessageState.SUCCESS.code }) rescueFailure("SOURCE_LOCAL_REPLY_DEPENDENCY")
}

private fun Connection.readOutgoingConsumption(chatId: String, clientMsgId: String): StoredChatDraftConsumption? {
    val rows = rescueRows("SELECT chat_id, CASE WHEN length(CAST(payload AS BLOB)) <= $MAX_SYNC_BYTES THEN payload END " +
        "FROM chat_draft_sync NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? LIMIT 2", listOf(chatId)) { row ->
        if (row.rescueText(1) != chatId) rescueFailure("SOURCE_SYNC_INVALID")
        val encoded = strictRescueUtf8(row.rescueTextBytes(2, MAX_SYNC_BYTES))
        decodeRescueSyncRecord(encoded, chatId).also {
            // Long serializers can accept quoted numbers. A consume revision is a reliable fact,
            // so require its stored JSON number rather than silently repairing its representation.
            if (it.consume != null) {
                val consume = (outgoingSourceJson.parseToJsonElement(encoded) as JsonObject)["consume"] as? JsonObject
                    ?: rescueFailure("SOURCE_SYNC_INVALID")
                val revision = consume["expectedRevision"] as? JsonPrimitive ?: rescueFailure("SOURCE_SYNC_INVALID")
                val id = consume["clientMsgId"]
                if (revision.isString || revision.longOrNull == null || id == null ||
                    (id != JsonNull && (id !is JsonPrimitive || !id.isString))) rescueFailure("SOURCE_SYNC_INVALID")
                consume["afterOperationId"]?.let { operation ->
                    if (operation != JsonNull && (operation !is JsonPrimitive || !operation.isString)) rescueFailure("SOURCE_SYNC_INVALID")
                }
            }
        }
    }
    if (rows.size > 1) rescueFailure("SOURCE_SYNC_INVALID")
    val record = rows.singleOrNull() ?: return null
    if (record.localRevision < 0 || record.requiredRevision < 0 || record.acceptedBaseFloor < 0 ||
        record.remote?.chatId?.let { it != chatId } == true) rescueFailure("SOURCE_SYNC_INVALID")
    // An in-flight SET may establish the base of a later consumption. Do not split that chain,
    // including a malformed record whose pending clear has lost its consume field.
    if (record.pending != null || record.pendingRejected) rescueFailure("SOURCE_SYNC_HAS_PENDING_DEPENDENCIES")
    val consume = record.consume?.takeIf { it.clientMsgId == clientMsgId } ?: return null
    if (consume.afterOperationId != null) rescueFailure("SOURCE_SYNC_HAS_PENDING_DEPENDENCIES")
    if (consume.expectedRevision !in 0 until Long.MAX_VALUE) rescueFailure("SOURCE_SYNC_INVALID")
    if (record.dirty) rescueFailure("SOURCE_OUTGOING_SUCCESSOR_DRAFT")
    if (rescueRows("SELECT chat_id FROM conversation_draft_outbox NOT INDEXED WHERE CAST(chat_id AS TEXT) = ? LIMIT 1",
            listOf(chatId)) { true }.isNotEmpty()) rescueFailure("SOURCE_LEGACY_DRAFT_DEPENDENCY")
    return consume
}

private fun Connection.readOutgoingSourceUploads(message: Message, assets: List<EmbeddedAsset>): List<ChatAssetUpload> {
    val byId = assets.associateBy { it.assetId }
    if (byId.size != assets.size) rescueFailure("SOURCE_ASSET_REFERENCES_INVALID")
    val links = rescueRows("SELECT chat_id, client_msg_id, sender_uid, asset_id FROM outgoing_chat_asset NOT INDEXED " +
        "WHERE CAST(chat_id AS TEXT) = ? AND CAST(client_msg_id AS TEXT) = ? LIMIT 257", listOf(message.chatId, message.clientMsgId)) { row ->
        if (row.rescueText(1) != message.chatId || row.rescueText(2) != message.clientMsgId || row.rescueText(3) != message.senderUid)
            rescueFailure("SOURCE_OUTGOING_ASSET_DEPENDENCY")
        row.rescueText(4)
    }
    if (links.size > EmbeddedAsset.MAX_ASSETS_PER_CONTENT || links.distinct().size != links.size || links.any { it !in byId })
        rescueFailure("SOURCE_OUTGOING_ASSET_DEPENDENCY")
    val whereIds = if (byId.isEmpty()) "" else " OR CAST(asset_id AS TEXT) IN (${byId.keys.joinToString(",") { "?" }})"
    val allJobs = rescueRows("SELECT asset_id, chat_id, source_id, referenced, CASE WHEN length(payload) <= $OUTGOING_JOB_BYTES THEN payload END " +
        "FROM chat_asset_upload NOT INDEXED WHERE CAST(chat_id AS TEXT) = ?$whereIds LIMIT 129", listOf(message.chatId) + byId.keys) { row ->
        // Repair commands may carry another upload identity; retain the archive intact instead of
        // attempting to split that same-chat repair workflow during a single-message rescue.
        val job = decodeRescueUpload(row.rescueBlob(5, OUTGOING_JOB_BYTES))
        if (row.rescueText(1) != job.assetId || row.rescueText(2) != job.chatId || job.chatId != message.chatId ||
            row.rescueText(3) != job.sourceId || row.rescueLong(4) !in 0L..1L)
            rescueFailure("SOURCE_UPLOAD_REFERENCE_MISMATCH")
        if (job.assetId in byId && (job.assetId !in links || row.rescueLong(4) != 0L))
            rescueFailure("SOURCE_OUTGOING_ASSET_DEPENDENCY")
        job
    }
    if (allJobs.size > 128 || allJobs.map { it.assetId }.distinct().size != allJobs.size ||
        allJobs.map { it.sourceId }.distinct().size != allJobs.size) rescueFailure("SOURCE_UPLOAD_LIMIT_OR_DUPLICATE")
    val uploads = allJobs.filter { it.assetId in links }
    if (uploads.size != links.size) rescueFailure("SOURCE_UPLOAD_MISSING")
    if (uploads.sumOf { it.length } > AttachmentPolicy.MAX_UPLOAD_BYTES) rescueFailure("SOURCE_UPLOAD_LIMIT_OR_DUPLICATE")
    for (job in uploads) {
        val asset = byId.getValue(job.assetId)
        validateRescueAsset(asset)
        if (asset.attachment.size != job.length || asset.attachment.name != sanitizeMultipartFileName(job.fileName) ||
            asset.attachment.contentType != sanitizeMultipartContentType(job.contentType) || job.asset?.let { it != asset } == true)
            rescueFailure("SOURCE_READY_DESCRIPTOR_MISMATCH")
        val holders = rescueRows("SELECT chat_id, client_msg_id, sender_uid, asset_id FROM outgoing_chat_asset NOT INDEXED " +
            "WHERE CAST(asset_id AS TEXT) = ? LIMIT 2", listOf(job.assetId)) { row ->
            row.rescueText(1) == message.chatId && row.rescueText(2) == message.clientMsgId &&
                row.rescueText(3) == message.senderUid && row.rescueText(4) == job.assetId
        }
        if (holders != listOf(true)) rescueFailure("SOURCE_OUTGOING_ASSET_DEPENDENCY")
        val sourceOwners = rescueRows("SELECT asset_id, source_id FROM chat_asset_upload NOT INDEXED " +
            "WHERE CAST(source_id AS TEXT) = ? LIMIT 2", listOf(job.sourceId)) {
            it.rescueText(1) == job.assetId && it.rescueText(2) == job.sourceId
        }
        if (sourceOwners != listOf(true)) rescueFailure("SOURCE_OUTGOING_ASSET_DEPENDENCY")
    }
    return uploads
}

private fun Connection.requireUnsharedOutgoingComposer(chatId: String, consumption: StoredChatDraftConsumption?, assetIds: Set<String>) {
    if (consumption == null && assetIds.isEmpty()) return
    // Source ids are installation-wide. A falsely cleared referenced flag must not hide another
    // composer's ownership; scan its bounded facts, without indexing or materializing message bodies.
    var totalBytes = 0L
    val seen = hashSetOf<String>()
    val where = if (assetIds.isEmpty()) " WHERE CAST(chat_id AS TEXT) = ?" else ""
    val rows = rescueRows("SELECT chat_id, revision, is_empty, CASE WHEN length(payload) <= $OUTGOING_COMPOSER_BYTES THEN payload END " +
        "FROM chat_composer_draft NOT INDEXED$where LIMIT 1129", if (assetIds.isEmpty()) listOf(chatId) else emptyList()) { row ->
        val rowChatId = row.rescueText(1)
        if (!seen.add(rowChatId)) rescueFailure("SOURCE_DRAFT_ROW_MISMATCH")
        val bytes = row.rescueBlob(4, OUTGOING_COMPOSER_BYTES)
        totalBytes += bytes.size
        if (totalBytes > 64L * 1024 * 1024) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT")
        val snapshot = decodeRescueComposer(bytes, rowChatId)
        if (snapshot.revision != row.rescueLong(2) || row.rescueLong(3) !=
            (if (snapshot.markdown.isEmpty() && snapshot.replyToClientMsgId == null) 1L else 0L)) rescueFailure("SOURCE_DRAFT_ROW_MISMATCH")
        val referenced = MarkdownAssetPolicy.recoveryReferences(snapshot.markdown).mapNotNull { it.assetId }.toSet() +
            snapshot.assets.map { it.assetId } + snapshot.pendingAssetIds
        if (referenced.any { it in assetIds }) rescueFailure("SOURCE_OUTGOING_ASSET_DEPENDENCY")
        if (rowChatId == chatId && consumption != null && (snapshot.markdown.isNotEmpty() || snapshot.assets.isNotEmpty() ||
                snapshot.pendingAssetIds.isNotEmpty() || snapshot.replyToClientMsgId != null || snapshot.replyToServerSeq != 0L))
            rescueFailure("SOURCE_OUTGOING_SUCCESSOR_DRAFT")
        true
    }
    if (rows.size > 1128) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT")
}

private fun ResultSet.outgoingNullableLong(index: Int): Long? = if (getObject(index) == null) null else rescueLong(index)

private const val OUTGOING_JOB_BYTES = 64 * 1024
private const val OUTGOING_COMPOSER_BYTES = 8 * 1024 * 1024
private val outgoingSourceJson = Json
private val OUTGOING_SOURCE_TABLES = setOf("sync_state", "outgoing_message", "message", "outgoing_chat_asset",
    "chat_asset_upload", "chat_composer_draft", "chat_draft_sync", "conversation_draft_outbox")
private val RESCUABLE_MESSAGE_TYPES = setOf(MessageType.RICH_TEXT, MessageType.INTERACTIVE_CARD, MessageType.IMAGE,
    MessageType.VOICE, MessageType.VIDEO, MessageType.FILE, MessageType.LOCATION, MessageType.CARD, MessageType.REPLY,
    MessageType.MERGE_FORWARD, MessageType.STICKER, MessageType.OFFICE_REF, MessageType.TASK_REF)
