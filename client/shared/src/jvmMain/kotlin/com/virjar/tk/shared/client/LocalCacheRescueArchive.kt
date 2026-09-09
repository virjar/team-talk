package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

internal data class CacheRescueArchive(
    val owner: LocalCacheDiagnosticOwner,
    val layout: LocalCacheDiagnosticLayout,
    val manifestSha256: String,
    val sourceDatabase: String,
    val files: List<LocalCacheArchiveFile>,
)

internal class LocalCacheRescueFailure(val reason: String) : IllegalStateException(reason)
internal fun rescueFailure(reason: String): Nothing = throw LocalCacheRescueFailure(reason)

internal inline fun <T> rescueGuarded(kind: String = "draft", action: () -> T): T = try { action() }
catch (failure: LocalCacheRescueFailure) { throw IllegalStateException("Local cache $kind rescue failed: ${failure.reason}") }
catch (_: Exception) { throw IllegalStateException("Local cache $kind rescue failed: PATH_STATE_OR_IO_FAILURE") }

/** SQLite recovery is confined to a verified, disposable copy of the selected database family. */
internal fun <T> readCacheRescueArchiveDatabase(
    archive: File,
    sourceDatabase: String,
    expectedManifestSha256: String? = null,
    read: (Connection, CacheRescueArchive) -> T,
): T {
    var temporary: Path? = null
    try {
        val root = archiveRoot(archive)
        val verified = LocalCacheArchive.verify(archive)
        if (expectedManifestSha256 != null && verified.manifestSha256 != expectedManifestSha256)
            rescueFailure("ARCHIVE_CONFIRMATION_MISMATCH")
        val encoded = readArchiveManifest(root.resolve("manifest.json"))
        if (archiveSha256(encoded.toByteArray(Charsets.UTF_8)) != verified.manifestSha256)
            rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
        val version = archiveRescueJson.parseToJsonElement(encoded).let { it as? JsonObject }
            ?.get("formatVersion")?.jsonPrimitive?.intOrNull
        val (owner, layout, files) = when (version) {
            1 -> archiveRescueJson.decodeFromString<LocalCacheArchiveManifest>(encoded).let { Triple(it.owner, it.layout, it.files) }
            2 -> archiveRescueJson.decodeFromString<LocalCacheNamespaceArchiveManifest>(encoded).let { Triple(it.owner, it.layout, it.files) }
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
        temporary = Files.createTempDirectory("teamtalk-cache-rescue-")
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
            read(connection, CacheRescueArchive(owner, layout, verified.manifestSha256, sourceDatabase, files))
        }
        if (LocalCacheArchive.verify(archive).manifestSha256 != verified.manifestSha256)
            rescueFailure("ARCHIVE_CHANGED_DURING_RESCUE")
        return result
    } catch (failure: LocalCacheRescueFailure) {
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

internal fun Connection.requireRescueSourceSchema(owner: LocalCacheDiagnosticOwner, requiredTables: Set<String>) {
    val schema = createStatement().use { it.executeQuery("PRAGMA user_version").use { row ->
        if (!row.next()) rescueFailure("SOURCE_SCHEMA_UNREADABLE")
        row.rescueLong(1)
    } }
    if (schema != AppDatabase.Schema.version) rescueFailure("SOURCE_SCHEMA_UNSUPPORTED")
    // Do not gate readable selected pages on unrelated message/history corruption. These fixed queries
    // deliberately bypass indexes so a damaged index cannot make a dependency appear absent.
    for (table in requiredTables) {
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
}

internal fun <T> Connection.rescueRows(sql: String, values: List<String> = emptyList(), read: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }

internal fun ResultSet.rescueLong(index: Int): Long = when (val value = getObject(index)) {
    is Int -> value.toLong()
    is Long -> value
    else -> rescueFailure("SOURCE_SQL_VALUE_INVALID")
}
internal fun ResultSet.rescueText(index: Int): String = strictRescueUtf8(rescueTextBytes(index, MAX_SYNC_BYTES))
internal fun ResultSet.rescueTextBytes(index: Int, max: Int): ByteArray {
    if (getObject(index) !is String) rescueFailure("SOURCE_SQL_VALUE_INVALID")
    return getBytes(index).also { if (it.size > max) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT") }
}
internal fun ResultSet.rescueBlob(index: Int, max: Int): ByteArray = (getObject(index) as? ByteArray)
    ?.also { if (it.size > max) rescueFailure("SOURCE_PAYLOAD_SIZE_LIMIT") } ?: rescueFailure("SOURCE_SQL_VALUE_INVALID")


private val archiveRescueJson = Json { encodeDefaults = true }
private val DATABASE_CATEGORIES = setOf("QUARANTINE", "ACTIVE_DATABASE", "DATABASES")
