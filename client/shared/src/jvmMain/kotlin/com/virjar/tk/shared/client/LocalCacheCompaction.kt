package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

@Serializable
data class LocalCacheCompactionReport(
    val root: String,
    val path: String,
    val owner: LocalCacheDiagnosticOwner,
    val schemaVersion: Long,
    /** Main database plus WAL, SHM and rollback journal, measured outside the maintenance connection. */
    val bytesBefore: Long,
    val bytesAfter: Long,
    val pagesBefore: Long,
    val pagesAfter: Long,
    val freePagesBefore: Long,
    val freePagesAfter: Long,
)

private class LocalCacheCompactionFailure(reason: String) : IllegalStateException("Local cache compaction failed: $reason")

/**
 * Explicit offline maintenance of one current JVM account database. The installation lease and a
 * dedicated SQLite exclusive lock remain held until VACUUM, checkpoint and connection close finish.
 * SQLite owns the transactional rewrite; no cache factory, migration, file replacement or cleanup runs.
 */
object LocalCacheCompaction {
    const val MAX_DATABASE_BYTES = 512L * 1024 * 1024
    private const val SPACE_RESERVE_BYTES = 16L * 1024 * 1024

    fun compact(root: File, databasePath: String): LocalCacheCompactionReport {
        val components = databasePath.split('/')
        require(components.size == 7 && components[0] == "deployments" && components[2] == "datasets" &&
            components[4] == "users" && components[6] == localCacheDatabaseFileName()) {
            "--database must name one current JVM account database from the doctor report"
        }
        val owner = LocalCacheDiagnosticOwner(validatedDeploymentFingerprint(components[1]),
            validatedLocalCacheDatasetId(components[3]), validatedLocalCacheOwnerId(components[5]))
        try {
            val privateData = JvmPrivateDataDirectory.openExisting(root)
            val directories = components.dropLast(1)
            // Validate without creating an absent namespace or database, including every parent component.
            privateData.requirePrivateFile(directories, components.last())
            val lease = try { JvmClientDataLease.acquire(root) }
                catch (_: Exception) { fail("CLIENT_LOCK_UNAVAILABLE") }
            lease.use {
                val marker = privateData.atomicTextFile(fileName = ".client-data-version").readText(128)?.trim()
                if (marker != "ready:${ProtocolVersions.MAJOR}") fail("INSTALLATION_VERSION_NOT_READY")
                val database = privateData.requirePrivateFile(directories, components.last()).toPath()
                if (hasRetainedJvmLocalCacheQuarantine(database.parent.toFile())) fail("QUARANTINE_REQUIRES_DISPOSITION")
                val bytesBefore = familyBytes(database)
                if (bytesBefore > MAX_DATABASE_BYTES) fail("DATABASE_SIZE_LIMIT")
                Class.forName("org.sqlite.JDBC")
                val result = DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=rw").use { connection ->
                    connection.executeMaintenance("PRAGMA busy_timeout = 0")
                    connection.executeMaintenance("PRAGMA trusted_schema = OFF")
                    connection.executeMaintenance("PRAGMA synchronous = FULL")
                    connection.executeMaintenance("PRAGMA fullfsync = ON")
                    connection.executeMaintenance("PRAGMA journal_size_limit = 0")
                    if (connection.maintenanceText("PRAGMA locking_mode = EXCLUSIVE") != "exclusive") fail("EXCLUSIVE_LOCK_UNAVAILABLE")
                    // The root lease excludes TeamTalk clients. This also rejects an outstanding SQLite
                    // transaction from an SDK caller or a client whose UI lock was just released.
                    connection.executeMaintenance("BEGIN EXCLUSIVE")
                    connection.executeMaintenance("COMMIT")
                    val schema = connection.maintenanceLong("PRAGMA user_version")
                    if (schema != AppDatabase.Schema.version) fail("UNSUPPORTED_SCHEMA_VERSION")
                    val journal = connection.maintenanceText("PRAGMA journal_mode")
                    if (journal !in setOf("delete", "truncate", "persist", "wal")) fail("UNSAFE_JOURNAL_MODE")
                    connection.requireMaintenanceIntegrity()
                    val pagesBefore = connection.maintenanceLong("PRAGMA page_count")
                    val freePagesBefore = connection.maintenanceLong("PRAGMA freelist_count")
                    val pageSize = connection.maintenanceLong("PRAGMA page_size")
                    val logicalBytes = Math.multiplyExact(pagesBefore, pageSize)
                    if (logicalBytes > MAX_DATABASE_BYTES) fail("DATABASE_SIZE_LIMIT")
                    // SQLite may need two database sizes of additional disk space. This is a preflight,
                    // not a reservation; runtime I/O/full errors still fail through SQLite's transaction.
                    val requiredSpace = 2 * maxOf(bytesBefore, logicalBytes) + SPACE_RESERVE_BYTES
                    if (Files.getFileStore(database).usableSpace < requiredSpace) fail("INSUFFICIENT_FREE_SPACE")
                    connection.executeMaintenance("VACUUM")
                    connection.requireMaintenanceIntegrity()
                    if (connection.maintenanceLong("PRAGMA user_version") != schema) fail("SCHEMA_CHANGED")
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
                            if (!rows.next() || rows.getInt(1) != 0) fail("CHECKPOINT_INCOMPLETE")
                        }
                    }
                    LocalCacheCompactionReport(privateData.root.toString(), databasePath, owner, schema,
                        bytesBefore, 0, pagesBefore, connection.maintenanceLong("PRAGMA page_count"),
                        freePagesBefore, connection.maintenanceLong("PRAGMA freelist_count"))
                }
                // Only SQLite retires its sidecars. Never delete a leftover WAL/journal to report savings.
                privateData.requirePrivateFile(directories, components.last())
                return result.copy(bytesAfter = familyBytes(database))
            }
        } catch (failure: LocalCacheCompactionFailure) {
            throw failure
        } catch (failure: SQLException) {
            fail(when (failure.errorCode and 0xff) {
                5, 6 -> "DATABASE_IN_USE"
                11, 26 -> "DATABASE_CORRUPT"
                13 -> "SQLITE_DISK_FULL"
                else -> "SQLITE_MAINTENANCE_FAILED"
            })
        } catch (_: Exception) {
            // Paths/SQL exceptions can include stored text. The CLI only emits fixed classifications.
            fail("PATH_STATE_OR_IO_FAILURE")
        }
    }

    private fun familyBytes(database: Path): Long {
        val owner = Files.getOwner(database, NOFOLLOW_LINKS)
        return (listOf(database.fileName.toString()) + localCacheDatabaseSidecars(database.fileName.toString())).sumOf { name ->
            val path = database.resolveSibling(name)
            if (!Files.exists(path, NOFOLLOW_LINKS)) 0L else {
                val attributes = basicAttributes(path)
                requireRealFile(attributes, "SQLite database family")
                requireSameOwner(path, owner, "SQLite database family")
                // Sidecars may use SQLite's mode, but their namespace is private. A hard link must not
                // allow SQLite to modify a file with another owner outside that namespace.
                if (Files.getFileStore(path).supportsFileAttributeView("unix")) {
                    check((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1)
                }
                attributes.size()
            }
        }
    }

    private fun Connection.requireMaintenanceIntegrity() {
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check(1)").use { rows ->
                if (!rows.next() || rows.getString(1) != "ok") fail("INTEGRITY_CHECK_FAILED")
            }
        }
    }

    private fun Connection.executeMaintenance(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.maintenanceText(sql: String): String = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
    private fun Connection.maintenanceLong(sql: String) = maintenanceText(sql).toLong()
    private fun fail(reason: String): Nothing = throw LocalCacheCompactionFailure(reason)
}
