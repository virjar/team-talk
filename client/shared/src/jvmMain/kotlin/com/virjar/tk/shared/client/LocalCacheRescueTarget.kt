package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** The root lease and this dedicated exclusive SQLite transaction own all target-side changes. */
internal fun <T> withCacheRescueTarget(
    root: File, databasePath: String, owner: LocalCacheDiagnosticOwner, action: (CacheRescueTarget) -> T,
): T {
    requireArchiveRelativePath(databasePath)
    val parts = databasePath.split('/')
    if (parts.size != 7 || parts[0] != "deployments" || parts[2] != "datasets" || parts[4] != "users" ||
        parts[6] != localCacheDatabaseFileName()) rescueFailure("INVALID_TARGET_DATABASE")
    val actualOwner = LocalCacheDiagnosticOwner(validatedDeploymentFingerprint(parts[1]),
        validatedLocalCacheDatasetId(parts[3]), validatedLocalCacheOwnerId(parts[5]))
    if (actualOwner != owner) rescueFailure("OWNER_MISMATCH")
    val directory = JvmPrivateDataDirectory.openExisting(root)
    directory.requirePrivateFile(emptyList(), ".lock")
    val lease = try { JvmClientDataLease.acquire(root) } catch (_: Exception) { rescueFailure("CLIENT_LOCK_UNAVAILABLE") }
    lease.use {
        if (directory.atomicTextFile(fileName = ".client-data-version").readText(128)?.trim() != "ready:${ProtocolVersions.MAJOR}")
            rescueFailure("INSTALLATION_VERSION_NOT_READY")
        val database = directory.requirePrivateFile(parts.dropLast(1), parts.last()).toPath()
        val family = captureArchiveInventory(directory.root, LocalCacheArchivePlan(owner,
            listOf(ArchiveScopePlan(databasePath, "DATABASE", ArchiveScopeKind.DATABASE_FAMILY))))
        if (family.files.values.sumOf { it.bytes } > LocalCacheArchive.MAX_FILE_BYTES) rescueFailure("TARGET_DATABASE_SIZE_LIMIT")
        for (relative in family.files.keys) {
            val path = directory.root.resolve(relative)
            requireSameOwner(path, Files.getOwner(directory.root, NOFOLLOW_LINKS), "Rescue database family")
            if ("unix" in path.fileSystem.supportedFileAttributeViews() &&
                (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L)
                rescueFailure("TARGET_DATABASE_LINKED")
        }
        Class.forName("org.sqlite.JDBC")
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=rw").use { connection ->
                connection.rescueExecute("PRAGMA busy_timeout = 0")
                connection.rescueExecute("PRAGMA trusted_schema = OFF")
                connection.rescueExecute("PRAGMA synchronous = FULL")
                connection.rescueExecute("PRAGMA fullfsync = ON")
                if (connection.rescueText("PRAGMA journal_mode") !in setOf("delete", "truncate", "persist", "wal"))
                    rescueFailure("UNSAFE_TARGET_JOURNAL_MODE")
                if (connection.rescueText("PRAGMA locking_mode = EXCLUSIVE") != "exclusive") rescueFailure("TARGET_DATABASE_IN_USE")
                connection.rescueExecute("BEGIN EXCLUSIVE")
                try {
                    if (connection.rescueLong("PRAGMA user_version") != AppDatabase.Schema.version) rescueFailure("UNSUPPORTED_TARGET_SCHEMA")
                    if (connection.rescueText("PRAGMA integrity_check(1)") != "ok") rescueFailure("TARGET_DATABASE_CORRUPT")
                    val size = Math.multiplyExact(connection.rescueLong("PRAGMA page_count"), connection.rescueLong("PRAGMA page_size"))
                    if (size > LocalCacheArchive.MAX_FILE_BYTES) rescueFailure("TARGET_DATABASE_SIZE_LIMIT")
                    if (connection.rescueText("SELECT dataset_id FROM sync_state WHERE singleton_id=1") != owner.datasetId)
                        rescueFailure("TARGET_DATASET_MISMATCH")
                    return action(CacheRescueTarget(directory, connection))
                } finally {
                    // A read-only preview or rejected import rolls back. COMMIT has already ended a
                    // successful import, so ROLLBACK's 'no transaction' is harmless in that case.
                    runCatching { connection.rescueExecute("ROLLBACK") }
                }
            }
        } catch (failure: SQLException) {
            rescueFailure(when (failure.errorCode and 0xff) {
                5, 6 -> "TARGET_DATABASE_IN_USE"
                11, 26 -> "TARGET_DATABASE_CORRUPT"
                13 -> "TARGET_DISK_FULL"
                else -> "TARGET_SQLITE_FAILURE"
            })
        }
    }
}

internal class CacheRescueTarget(val directory: JvmPrivateDataDirectory, val db: Connection) {
    private val json = Json { encodeDefaults = true }
    fun composerRevision(): Long = db.rescueLong("SELECT revision FROM chat_composer_clock WHERE id=1").also {
        if (it < 0 || it == Long.MAX_VALUE) rescueFailure("TARGET_COMPOSER_CHANGED")
    }

    fun checkEmptyComposer(chat: String, uploads: List<ChatAssetUpload>): Long {
        // A damaged BLOB key with identical text must not make a reliable row appear absent.
        for (table in listOf("conversation", "chat_composer_draft", "chat_draft_sync", "conversation_draft_outbox",
            "chat_asset_upload", "outgoing_chat_asset", "outgoing_message", "message")) {
            if (db.rescueLong("SELECT count(*) FROM $table WHERE CAST(chat_id AS TEXT)=? AND typeof(chat_id)<>'text'", chat) != 0L)
                rescueFailure("TARGET_RELIABLE_WORK_EXISTS")
        }
        if (db.rescueLong("SELECT count(*) FROM conversation WHERE chat_id=?", chat) != 1L) rescueFailure("TARGET_CHAT_MISSING")
        db.rescueBlob("SELECT payload FROM chat_composer_draft WHERE chat_id=?", chat)?.let { bytes ->
            val draft = try { decodeRescueComposer(bytes, chat) }
            catch (_: Exception) { rescueFailure("TARGET_DRAFT_UNREADABLE") }
            if (draft.chatId != chat || draft.markdown.isNotEmpty() || draft.replyToClientMsgId != null ||
                draft.assets.isNotEmpty() || draft.pendingAssetIds.isNotEmpty() ||
                draft.revision <= 0 || draft.revision != db.rescueLong("SELECT revision FROM chat_composer_draft WHERE chat_id=?", chat) ||
                db.rescueLong("SELECT is_empty FROM chat_composer_draft WHERE chat_id=?", chat) != 1L)
                rescueFailure("TARGET_DRAFT_NOT_EMPTY")
        }
        val syncBytes = db.rescueBlob("SELECT CAST(payload AS BLOB) FROM chat_draft_sync WHERE chat_id=?", chat)
        if (syncBytes != null) {
            val record = try { decodeRescueSyncRecord(syncBytes.decodeToString(throwOnInvalidSequence = true), chat) }
            catch (_: Exception) { rescueFailure("TARGET_SYNC_UNREADABLE") }
            if (record.dirty || record.pending != null || record.consume != null || record.conflict || record.failure != null || record.pendingRejected)
                rescueFailure("TARGET_RELIABLE_WORK_EXISTS")
        } else if (!db.rescueOptionalText("SELECT draft FROM conversation WHERE chat_id=?", chat).isNullOrEmpty()) {
            rescueFailure("TARGET_DRAFT_NOT_EMPTY")
        }
        for (sql in listOf("SELECT count(*) FROM conversation_draft_outbox WHERE chat_id=?",
            "SELECT count(*) FROM chat_asset_upload WHERE chat_id=?", "SELECT count(*) FROM outgoing_chat_asset WHERE chat_id=?",
            "SELECT count(*) FROM outgoing_message WHERE chat_id=? AND state<>4",
            "SELECT count(*) FROM message WHERE chat_id=? AND server_seq=0")) {
            if (db.rescueLong(sql, chat) != 0L) rescueFailure("TARGET_RELIABLE_WORK_EXISTS")
        }
        for (job in uploads) {
            if (db.rescueLong("SELECT count(*) FROM chat_asset_upload WHERE CAST(asset_id AS TEXT)=? OR CAST(source_id AS TEXT)=?", job.assetId, job.sourceId) != 0L ||
                db.rescueLong("SELECT count(*) FROM outgoing_chat_asset WHERE CAST(asset_id AS TEXT)=?", job.assetId) != 0L)
                rescueFailure("TARGET_ASSET_ID_CONFLICT")
        }
        val revision = composerRevision()
        if (db.rescueLong("SELECT coalesce(max(revision),0) FROM chat_composer_draft") > revision)
            rescueFailure("TARGET_COMPOSER_CHANGED")
        return revision
    }

    fun checkCapacity(candidate: ChatDraftRescueSource, draftBytes: Int, syncBytes: Int) {
        if (db.rescueLong("SELECT count(*) FROM chat_composer_draft WHERE is_empty=0") + 1 > 1_000 ||
            db.rescueLong("SELECT coalesce(sum(length(payload)),0) FROM chat_composer_draft WHERE is_empty=0") + draftBytes > 48L * 1024 * 1024 ||
            db.rescueLong("SELECT count(*) FROM chat_draft_sync WHERE chat_id<>?", candidate.snapshot.chatId) + 1 > 1_000 ||
            db.rescueLong("SELECT coalesce(sum(length(CAST(payload AS BLOB))),0) FROM chat_draft_sync WHERE chat_id<>?", candidate.snapshot.chatId) + syncBytes > 64L * 1024 * 1024 ||
            db.rescueLong("SELECT count(*) FROM chat_asset_upload") + candidate.uploads.size > 128)
            rescueFailure("TARGET_CAPACITY_EXCEEDED")
        // The stored source lengths, including unrelated pending uploads, share the normal SDK quota.
        val lengths = db.rescueLong("SELECT coalesce(sum(json_extract(CAST(payload AS TEXT),'$.length')),0) FROM chat_asset_upload")
        if (lengths < 0 || lengths + candidate.uploads.sumOf { it.length } > 512L * 1024 * 1024)
            rescueFailure("TARGET_CAPACITY_EXCEEDED")
    }

    fun install(draft: ChatDraftSnapshot, bytes: ByteArray, sync: String, jobs: List<ChatAssetUpload>) {
        db.rescueUpdate("INSERT INTO chat_composer_draft(chat_id,revision,is_empty,payload) VALUES (?,?,0,?) " +
            "ON CONFLICT(chat_id) DO UPDATE SET revision=excluded.revision,is_empty=0,payload=excluded.payload", draft.chatId, draft.revision, bytes)
        db.rescueUpdate("INSERT INTO chat_draft_sync(chat_id,payload) VALUES (?,?) ON CONFLICT(chat_id) DO UPDATE SET payload=excluded.payload", draft.chatId, sync)
        for (job in jobs) db.rescueUpdate("INSERT INTO chat_asset_upload(asset_id,chat_id,source_id,referenced,payload) VALUES (?,?,?,1,?)",
            job.assetId, job.chatId, job.sourceId, json.encodeToString(job).encodeToByteArray())
        db.rescueUpdate("UPDATE chat_composer_clock SET revision=? WHERE id=1", draft.revision)
        val preview = draft.markdown.takeIf { MarkdownAssetPolicy.recoveryReferences(it).isEmpty() && it.isNotEmpty() }
        db.rescueUpdate("UPDATE conversation SET draft=? WHERE chat_id=?", preview, draft.chatId)
    }
    fun commit() { db.rescueExecute("COMMIT") }
}

internal fun Connection.rescueExecute(sql: String) { createStatement().use { it.execute(sql) } }
internal fun Connection.rescueText(sql: String, vararg parameters: Any?): String =
    rescueOptionalText(sql, *parameters) ?: rescueFailure("TARGET_ROW_MISSING")
internal fun Connection.rescueLong(sql: String, vararg parameters: Any?): Long =
    rescueText(sql, *parameters).toLong()
internal fun Connection.rescueOptionalText(sql: String, vararg parameters: Any?): String? = prepareStatement(sql).use { statement ->
    parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeQuery().use { if (it.next()) it.getString(1) else null }
}
internal fun Connection.rescueBlob(sql: String, parameter: String): ByteArray? = prepareStatement(sql).use { statement ->
    statement.setString(1, parameter)
    statement.executeQuery().use { rows ->
        if (!rows.next()) null else rows.getBinaryStream(1).use { stream ->
            stream.readNBytes(2 * 1024 * 1024 + 1).also { if (it.size > 2 * 1024 * 1024) rescueFailure("TARGET_PAYLOAD_SIZE_LIMIT") }
        }
    }
}
internal fun Connection.rescueUpdate(sql: String, vararg parameters: Any?) = prepareStatement(sql).use { statement ->
    parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    if (statement.executeUpdate() != 1) rescueFailure("TARGET_WRITE_FAILED")
}
