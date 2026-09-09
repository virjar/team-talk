package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.model.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LocalCacheOutgoingRescuePreview(
    val root: String,
    val database: String,
    val owner: LocalCacheDiagnosticOwner,
    val chatId: String,
    val clientMsgId: String,
    val sourceDatabase: String,
    val manifestSha256: String,
    val composerRevision: Long,
    val outgoingOrdinal: Long,
    val sourceState: String,
    val willResumeSending: Boolean,
    val payloadBytes: Int,
    val attachmentCount: Int,
    val sourceBytes: Long,
    val consequence: String,
)

@Serializable
data class LocalCacheOutgoingRescueReport(
    val preview: LocalCacheOutgoingRescuePreview,
    val installedLocalOrdinal: Long,
)

/** Explicitly resumes one archived send intent; never invents a new message identity. */
object LocalCacheOutgoingRescue {
    fun preview(
        root: File, databasePath: String, archive: File, sourceDatabase: String, chatId: String, clientMsgId: String,
    ): LocalCacheOutgoingRescuePreview = rescueGuarded("outgoing") {
        val source = readOutgoingRescueSource(archive, sourceDatabase, chatId, clientMsgId)
        withCacheRescueTarget(root, databasePath, source.archive.owner) { target ->
            target.checkOutgoingDestination(source)
            checkRescueSourceDestination(target.directory, source.sourceFiles())
            target.outgoingPreview(databasePath, source)
        }
    }

    fun importMessage(
        root: File, databasePath: String, archive: File, sourceDatabase: String, chatId: String, clientMsgId: String,
        expectedManifestSha256: String, expectedComposerRevision: Long, expectedOutgoingOrdinal: Long,
    ): LocalCacheOutgoingRescueReport = rescueGuarded("outgoing") {
        val source = readOutgoingRescueSource(archive, sourceDatabase, chatId, clientMsgId, expectedManifestSha256)
        withCacheRescueTarget(root, databasePath, source.archive.owner) { target ->
            if (expectedComposerRevision < 0 || target.composerRevision() != expectedComposerRevision)
                rescueFailure("TARGET_COMPOSER_CHANGED")
            if (expectedOutgoingOrdinal < 0 || target.outgoingOrdinal() != expectedOutgoingOrdinal)
                rescueFailure("TARGET_OUTGOING_CHANGED")
            target.checkOutgoingDestination(source)
            checkRescueSourceDestination(target.directory, source.sourceFiles())
            val preview = target.outgoingPreview(databasePath, source)
            // The original archive remains the recovery authority if publication fails. Copied
            // sources are verified first, and cannot overwrite another identity's bytes.
            copyRescueSources(archiveRoot(archive), target.directory, source.sourceFiles())
            if (LocalCacheArchive.verify(archive).manifestSha256 != expectedManifestSha256)
                rescueFailure("ARCHIVE_CHANGED")
            val ordinal = target.installOutgoing(source)
            target.commit()
            LocalCacheOutgoingRescueReport(preview, ordinal)
        }
    }
}

private fun OutgoingRescueSource.sourceFiles() = CacheRescueSources(archive.owner, uploads, sources)

private fun CacheRescueTarget.outgoingPreview(database: String, source: OutgoingRescueSource): LocalCacheOutgoingRescuePreview {
    val state = OutgoingMessageState.fromCode(source.outgoing.state)
    val resumes = state != OutgoingMessageState.TERMINAL_FAILED
    return LocalCacheOutgoingRescuePreview(directory.root.toString(), database, source.archive.owner,
        source.message.chatId, source.message.clientMsgId, source.archive.sourceDatabase, source.archive.manifestSha256,
        composerRevision(), outgoingOrdinal(), state.name, resumes, source.outgoing.payload.size,
        AttachmentPolicy.attachments(source.message).size, source.sources.sumOf { it.bytes },
        (if (resumes) "Import authorizes the existing send intent to resume automatically when this client authenticates. "
            else "Import preserves the terminal failure; this message will not automatically resume sending. ") +
            "The original clientMsgId, payload and request fingerprint are preserved. Current server rules still apply. " +
            "Attachment sources remain available for explicit failure recovery; no replacement upload or new message identity is created. " +
            "The archive and unrelated local facts remain unchanged.")
}

private fun CacheRescueTarget.outgoingOrdinal(): Long {
    if (db.rescueLong("SELECT count(*) FROM sqlite_sequence WHERE name='outgoing_message'") > 1L)
        rescueFailure("TARGET_OUTGOING_CHANGED")
    val ordinal = db.rescueOptionalText("SELECT seq FROM sqlite_sequence WHERE name='outgoing_message'")?.toLong() ?: 0
    if (ordinal < 0 || ordinal == Long.MAX_VALUE ||
        db.rescueLong("SELECT coalesce(max(local_ordinal),0) FROM outgoing_message") > ordinal)
        rescueFailure("TARGET_OUTGOING_CHANGED")
    return ordinal
}

private fun CacheRescueTarget.checkOutgoingDestination(source: OutgoingRescueSource) {
    val message = source.message
    for (table in listOf("message", "outgoing_message")) {
        if (db.rescueLong("SELECT count(*) FROM $table WHERE CAST(chat_id AS TEXT)=? AND CAST(client_msg_id AS TEXT)=?",
                message.chatId, message.clientMsgId) != 0L) rescueFailure("TARGET_MESSAGE_EXISTS")
    }
    checkEmptyComposer(message.chatId, source.uploads)
    outgoingOrdinal()
    val terminal = source.outgoing.state == OutgoingMessageState.TERMINAL_FAILED.code
    val states = if (terminal) "3,4" else "0,1,2"
    val maxCount = if (terminal) MAX_TERMINAL_OUTGOING_RECEIPTS else MAX_ACTIVE_OUTGOING_MESSAGES
    val maxBytes = if (terminal) MAX_TERMINAL_OUTGOING_STORED_BYTES else MAX_ACTIVE_OUTGOING_STORED_BYTES
    val bytes = source.outgoing.payload.size.toLong() + (source.outgoing.request_fingerprint?.size ?: 0)
    if (db.rescueLong("SELECT count(*) FROM outgoing_message WHERE state IN ($states)") >= maxCount ||
        db.rescueLong("SELECT coalesce(sum(length(payload)+coalesce(length(request_fingerprint),0)),0) FROM outgoing_message WHERE state IN ($states)") + bytes > maxBytes)
        rescueFailure("TARGET_CAPACITY_EXCEEDED")
    if (db.rescueLong("SELECT count(*) FROM outgoing_chat_asset") + source.uploads.size > 8_192 ||
        db.rescueLong("SELECT count(*) FROM chat_asset_upload") + source.uploads.size > 128 ||
        db.rescueLong("SELECT coalesce(sum(json_extract(CAST(payload AS TEXT),'$.length')),0) FROM chat_asset_upload") +
            source.uploads.sumOf { it.length } > 512L * 1024 * 1024)
        rescueFailure("TARGET_CAPACITY_EXCEEDED")
    if (source.consumption != null) {
        if (db.rescueLong("SELECT count(*) FROM chat_draft_sync WHERE chat_id<>?", message.chatId) + 1 > 1_000 ||
            db.rescueLong("SELECT coalesce(sum(length(CAST(payload AS BLOB))),0) FROM chat_draft_sync WHERE chat_id<>?", message.chatId) +
                rescuedConsumption(source, Math.addExact(composerRevision(), 1)).encodeToByteArray().size > 64L * 1024 * 1024)
            rescueFailure("TARGET_CAPACITY_EXCEEDED")
    }
    val size = Math.multiplyExact(db.rescueLong("PRAGMA page_count"), db.rescueLong("PRAGMA page_size"))
    if (size + bytes * 2 + source.uploads.size * 65_536L + 65_536 > LocalCacheArchive.MAX_FILE_BYTES)
        rescueFailure("TARGET_DATABASE_SIZE_LIMIT")
}

private fun rescuedConsumption(source: OutgoingRescueSource, revision: Long): String = rescueOutgoingJson.encodeToString(
    StoredChatDraftSyncRecord(chatId = source.message.chatId, localRevision = revision, stale = true,
        consume = source.consumption),
)

private fun CacheRescueTarget.installOutgoing(source: OutgoingRescueSource): Long {
    val row = source.outgoing
    val message = source.message
    val terminal = row.state == OutgoingMessageState.TERMINAL_FAILED.code
    val state = if (terminal) OutgoingMessageState.TERMINAL_FAILED else OutgoingMessageState.PENDING
    val now = System.currentTimeMillis()
    db.rescueUpdate("INSERT INTO outgoing_message(client_msg_id,chat_id,sender_uid,payload,request_fingerprint,state," +
        "attempt_count,failure_code,last_error,terminal_code,server_seq,next_attempt_at,created_at,updated_at,completed_at) " +
        "VALUES (?,?,?,?,?,?,?,?,?,?,NULL,0,?,?,?)", row.client_msg_id, row.chat_id, row.sender_uid, row.payload,
        row.request_fingerprint, state.code, row.attempt_count, row.failure_code, row.last_error, row.terminal_code,
        row.created_at, if (terminal) row.updated_at else now, if (terminal) row.completed_at else null)
    val ordinal = db.rescueLong("SELECT local_ordinal FROM outgoing_message WHERE chat_id=? AND client_msg_id=?", row.chat_id, row.client_msg_id)
    db.rescueUpdate("INSERT INTO message(chat_id,client_msg_id,server_seq,sender_uid,message_type,timestamp,flags,body,send_status,outgoing_failure_code) " +
        "VALUES (?,?,0,?,?,?,?,?,?,?)", message.chatId, message.clientMsgId, message.senderUid, message.messageType,
        message.timestamp, message.flags, message.body?.let(ProtoCodec::encode),
        if (terminal) Message.SEND_STATUS_FAILED else Message.SEND_STATUS_QUEUED, if (terminal) row.failure_code else null)
    for (job in source.uploads) {
        // Keep the outgoing payload immutable. A later explicit replacement may use this source,
        // but a recovered local upload must never silently change the original send request.
        val retained = job.copy(state = ChatAssetUploadState.FAILED, asset = null, nextAttemptAt = 0,
            failure = "救援的附件源已保留，需要时请在失败消息中重新上传")
        db.rescueUpdate("INSERT INTO chat_asset_upload(asset_id,chat_id,source_id,referenced,payload) VALUES (?,?,?,0,?)",
            job.assetId, job.chatId, job.sourceId, rescueOutgoingJson.encodeToString(retained).encodeToByteArray())
        db.rescueUpdate("INSERT INTO outgoing_chat_asset(chat_id,client_msg_id,sender_uid,asset_id) VALUES (?,?,?,?)",
            message.chatId, message.clientMsgId, message.senderUid, job.assetId)
    }
    if (source.consumption != null) {
        val revision = Math.addExact(composerRevision(), 1)
        val tombstone = rescueOutgoingJson.encodeToString(ChatDraftSnapshot(message.chatId, revision, sharedRevision = 0)).encodeToByteArray()
        db.rescueUpdate("INSERT INTO chat_composer_draft(chat_id,revision,is_empty,payload) VALUES (?,?,1,?) " +
            "ON CONFLICT(chat_id) DO UPDATE SET revision=excluded.revision,is_empty=1,payload=excluded.payload", message.chatId, revision, tombstone)
        db.rescueUpdate("INSERT INTO chat_draft_sync(chat_id,payload) VALUES (?,?) ON CONFLICT(chat_id) DO UPDATE SET payload=excluded.payload",
            message.chatId, rescuedConsumption(source, revision))
        db.rescueUpdate("UPDATE chat_composer_clock SET revision=? WHERE id=1", revision)
        db.rescueUpdate("UPDATE conversation SET draft=NULL WHERE chat_id=?", message.chatId)
    }
    return ordinal
}

private val rescueOutgoingJson = Json { encodeDefaults = true }
