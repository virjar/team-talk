package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

private data class DiagnosticQuery(val table: String, val where: String = "", val json: Boolean = false)

/** 固定的聚合查询：任何调用方提供的 SQL、payload、token 或消息正文都不会进入报告。 */
private val diagnosticQueries = linkedMapOf(
    "messages" to DiagnosticQuery("message"),
    "totalOutgoing" to DiagnosticQuery("outgoing_message"),
    "pendingOutgoing" to DiagnosticQuery("outgoing_message", "state IN (0, 1, 2)"),
    "failedOutgoing" to DiagnosticQuery("outgoing_message", "state = 3"),
    "completedOutgoing" to DiagnosticQuery("outgoing_message", "state = 4"),
    "pendingGroupCreations" to DiagnosticQuery("pending_group_creation"),
    "pendingContactDecisions" to DiagnosticQuery("pending_contact_decision"),
    "pendingInviteLinks" to DiagnosticQuery("pending_invite_link_creation"),
    "pendingGroupFileCommands" to DiagnosticQuery("pending_group_file_command"),
    "pendingDocumentMoves" to DiagnosticQuery("pending_document_move_command"),
    "pendingGroupBotCredentials" to DiagnosticQuery("pending_group_bot_credential_command"),
    "pendingDocumentComments" to DiagnosticQuery("pending_document_comments"),
    "pendingTaskCommands" to DiagnosticQuery("pending_task_commands"),
    "conversationDraftMirrors" to DiagnosticQuery("conversation_draft_outbox", "state = 0"),
    "conversationDraftMirrorsAwaitingProjection" to DiagnosticQuery("conversation_draft_outbox", "state = 1"),
    "pendingReadMarkers" to DiagnosticQuery("conversation_read_outbox"),
    "pendingBotMessages" to DiagnosticQuery("bot_message_inbox", "acked = 0"),
    "chatDrafts" to DiagnosticQuery("chat_composer_draft", "is_empty = 0"),
    "chatUploads" to DiagnosticQuery("chat_asset_upload"),
    "outgoingAssets" to DiagnosticQuery("outgoing_chat_asset"),
    "sharedDraftRows" to DiagnosticQuery("chat_draft_sync"),
    "sharedDraftDirty" to DiagnosticQuery("chat_draft_sync", "json_extract(payload, '$.dirty') = 1", true),
    "sharedDraftPending" to DiagnosticQuery("chat_draft_sync", "json_extract(payload, '$.pending') IS NOT NULL", true),
    "sharedDraftConsume" to DiagnosticQuery("chat_draft_sync", "json_extract(payload, '$.consume') IS NOT NULL", true),
    "sharedDraftConflicts" to DiagnosticQuery("chat_draft_sync", "json_extract(payload, '$.conflict') = 1", true),
)

private data class DiagnosticFileState(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?)
private class DiagnosticCaptureFailure(val reason: String) : Exception()

internal fun inspectLocalCacheCopy(
    root: Path,
    database: Path,
    owner: LocalCacheDiagnosticOwner?,
    quarantine: Boolean,
    remainingBytes: Long,
): Pair<LocalCacheDatabaseDiagnostic, Long> {
    val counts = diagnosticQueries.keys.associateWith<String, Long?> { null }.toMutableMap()
    val issues = mutableListOf<String>()
    val files = mutableListOf<LocalCacheDiagnosticFile>()
    var schema: Long? = null
    var consumedBytes = 0L
    var temporary: Path? = null
    val relative = root.relativize(database).toString().replace(File.separatorChar, '/')
    try {
        val before = diagnosticFamily(root, database)
        files += before.map { (path, state) -> LocalCacheDiagnosticFile(root.relativize(path).toString().replace(File.separatorChar, '/'), state.size) }
        if (database !in before) throw DiagnosticCaptureFailure("DATABASE_MAIN_MISSING")
        val total = before.values.sumOf { it.size }
        if (total > LocalCacheDiagnostics.MAX_DATABASE_BYTES || total > remainingBytes) throw DiagnosticCaptureFailure("SNAPSHOT_SIZE_LIMIT")
        // 即使捕获失败也计入 I/O 预算，这样多个损坏的候选无法绕过它。
        consumedBytes = total
        temporary = Files.createTempDirectory("teamtalk-cache-diagnostic-")
        val security = JvmPrivatePathSecurity.forPath(temporary, JvmFileSystemIdentity.currentOwner(temporary))
        val privateCopy = temporary.resolve("snapshot")
        security.createDirectory(privateCopy)
        val capturedHashes = before.mapValues { (source, state) ->
            // SHM 是一次性共享内存协调数据，不是 WAL 数据。只在副本中重新生成它。
            val copy = if (source == database || source.fileName.toString().endsWith("-wal") || source.fileName.toString().endsWith("-journal")) {
                privateCopy.resolve(source.fileName.toString()).also(security::createEmptyFile)
            } else null
            digestDiagnosticFile(root, source, state.size, copy)
        }
        if (diagnosticFamily(root, database) != before || before.any { (source, state) ->
                !capturedHashes.getValue(source).contentEquals(digestDiagnosticFile(root, source, state.size))
            } || diagnosticFamily(root, database) != before) {
            throw DiagnosticCaptureFailure("SOURCE_CHANGED_DURING_CAPTURE")
        }
        Class.forName("org.sqlite.JDBC")
        val copy = privateCopy.resolve(database.fileName)
        // SQLite 3.51 的 mode=ro 在重新打开数据库后会跳过 CHECK 约束校验：
        // quick_check 与 integrity_check 都可能对违反 CHECK 的行报告 "ok"。
        // 只用 rw 打开这个一次性私有副本，并在读取其 schema 之前禁止 SQL 写入。
        // query_only 保留 CHECK 校验；源文件从不进入 JDBC。
        DriverManager.getConnection("jdbc:sqlite:${copy.toUri()}?mode=rw").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA query_only = ON")
                statement.execute("PRAGMA trusted_schema = OFF")
                statement.execute("PRAGMA busy_timeout = 1000")
            }
            schema = connection.diagnosticLong("PRAGMA user_version")
            if (schema!! > AppDatabase.Schema.version) throw DiagnosticCaptureFailure("UNSUPPORTED_SCHEMA_VERSION")
            val healthy = try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check(1)").use { rows ->
                        rows.next() && rows.getString(1) == "ok"
                    }
                }
            } catch (_: Exception) { throw DiagnosticCaptureFailure("SQLITE_INTEGRITY_CHECK_UNREADABLE") }
            if (!healthy) throw DiagnosticCaptureFailure("SQLITE_INTEGRITY_CHECK_FAILED")
            val tables = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
            val sharedDraftShapeReadable = if ("chat_draft_sync" in tables) runCatching {
                connection.diagnosticLong("SELECT count(*) FROM chat_draft_sync WHERE NOT json_valid(payload)") == 0L &&
                    connection.diagnosticLong("""SELECT count(*) FROM chat_draft_sync WHERE
                        json_type(payload) != 'object' OR
                        coalesce(json_type(payload, '$.dirty'), 'missing') NOT IN ('true', 'false') OR
                        coalesce(json_type(payload, '$.conflict'), 'missing') NOT IN ('true', 'false') OR
                        coalesce(json_type(payload, '$.pending'), 'missing') NOT IN ('object', 'null') OR
                        coalesce(json_type(payload, '$.consume'), 'missing') NOT IN ('object', 'null')
                    """.trimIndent()) == 0L
            }.getOrDefault(false) else false
            diagnosticQueries.forEach { (key, query) ->
                if (query.table !in tables) issues += "TABLE_NOT_PRESENT:$key"
                else try {
                    if (query.json && !sharedDraftShapeReadable) {
                        issues += "PAYLOAD_UNREADABLE:$key"
                    } else {
                        counts[key] = connection.diagnosticLong("SELECT count(*) FROM ${query.table}" + query.where.takeIf(String::isNotEmpty)?.let { " WHERE $it" }.orEmpty())
                    }
                } catch (_: Exception) { issues += "AGGREGATE_UNREADABLE:$key" }
            }
        }
    } catch (failure: DiagnosticCaptureFailure) {
        issues += failure.reason
    } catch (_: Exception) {
        // SQLite 错误文本可能包含 SQL 或调用方路径。只返回稳定且不敏感的原因。
        issues += "DATABASE_OR_SNAPSHOT_UNREADABLE"
    } finally {
        temporary?.let { directory ->
            try { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
            catch (_: Exception) { issues += "PRIVATE_TEMP_CLEANUP_FAILED" }
        }
    }
    return LocalCacheDatabaseDiagnostic(relative, owner, quarantine, schema, files, counts, issues.distinct()) to consumedBytes
}

private fun Connection.diagnosticLong(sql: String): Long = createStatement().use { statement ->
    statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getLong(1) }
}

/** 在每个已知命名空间组件处拒绝符号链接，包括清单扫描之后被替换的组件。 */
private fun requireDiagnosticPath(root: Path, path: Path) {
    var current = root
    for (component in root.relativize(path)) {
        current = current.resolve(component)
        if (Files.isSymbolicLink(current)) throw DiagnosticCaptureFailure("SYMLINK_NOT_READ")
    }
}

private fun diagnosticFamily(root: Path, database: Path): Map<Path, DiagnosticFileState> = buildMap {
    requireDiagnosticPath(root, database)
    for (suffix in LocalCacheDiagnostics.FAMILY_SUFFIXES) {
        val path = database.resolveSibling(database.fileName.toString() + suffix)
        if (!Files.exists(path, NOFOLLOW_LINKS)) continue
        requireDiagnosticPath(root, path)
        val attrs = basicAttributes(path)
        if (!attrs.isRegularFile) throw DiagnosticCaptureFailure("DATABASE_FAMILY_NOT_REGULAR")
        put(path, DiagnosticFileState(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey()))
    }
}

private fun digestDiagnosticFile(root: Path, source: Path, expectedBytes: Long, copy: Path? = null): ByteArray {
    requireDiagnosticPath(root, source)
    val hash = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(source, READ, NOFOLLOW_LINKS).use { input ->
        val output = copy?.let { Files.newOutputStream(it, WRITE, NOFOLLOW_LINKS) }
        try {
            val buffer = ByteArray(64 * 1024)
            var consumed = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                consumed += read
                if (consumed > expectedBytes) throw DiagnosticCaptureFailure("SOURCE_CHANGED_DURING_CAPTURE")
                hash.update(buffer, 0, read)
                output?.write(buffer, 0, read)
            }
            if (consumed != expectedBytes) throw DiagnosticCaptureFailure("SOURCE_CHANGED_DURING_CAPTURE")
        } finally { output?.close() }
    }
    return hash.digest()
}
