package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.platform.PlatformFile as File

data class LocalCacheStorageCompactionReport(
    val bytesBefore: Long,
    val bytesAfter: Long,
    val pagesBefore: Long,
    val pagesAfter: Long,
    val freePagesBefore: Long,
    val freePagesAfter: Long,
) {
    val reclaimedBytes: Long get() = (bytesBefore - bytesAfter).coerceAtLeast(0)
}

enum class LocalCacheStorageCompactionFailure {
    UNSUPPORTED_STORAGE, DATABASE_IN_USE,
    UNSUPPORTED_SCHEMA_VERSION, INTEGRITY_CHECK_FAILED, DATABASE_SIZE_LIMIT,
    INSUFFICIENT_FREE_SPACE, CHECKPOINT_INCOMPLETE, STORAGE_IO_FAILED,
}

class LocalCacheStorageCompactionException(val reason: LocalCacheStorageCompactionFailure) :
    IllegalStateException("Local cache storage compaction failed: ${reason.name}")

/**
 * Physical maintenance of the factory-selected database, called only while its LocalCache gate is
 * held. Android's driver borrows a connection per statement, so do not change connection PRAGMAs or
 * rely on a BEGIN/COMMIT pair to keep one pooled connection locked. VACUUM owns its SQLite transaction.
 */
internal class LocalCacheStorageMaintenance(
    private val databaseFile: File,
    private val maxDatabaseBytes: Long = 512L * 1024 * 1024,
) {
    fun compact(driver: SqlDriver): LocalCacheStorageCompactionReport = try {
        if (driver.currentTransaction() != null) fail(LocalCacheStorageCompactionFailure.DATABASE_IN_USE)
        val bytesBefore = familyBytes()
        if (bytesBefore > maxDatabaseBytes) fail(LocalCacheStorageCompactionFailure.DATABASE_SIZE_LIMIT)
        val schema = driver.longPragma("user_version")
        if (schema != AppDatabase.Schema.version) fail(LocalCacheStorageCompactionFailure.UNSUPPORTED_SCHEMA_VERSION)
        driver.requireIntegrity()
        val pagesBefore = driver.longPragma("page_count")
        val freePagesBefore = driver.longPragma("freelist_count")
        val pageSize = driver.longPragma("page_size")
        val logicalBytes = checkedPageBytes(pagesBefore, pageSize)
        if (logicalBytes > maxDatabaseBytes) fail(LocalCacheStorageCompactionFailure.DATABASE_SIZE_LIMIT)
        val requiredBytes = 2 * maxOf(bytesBefore, logicalBytes) + SPACE_RESERVE_BYTES
        if (databaseFile.usableSpace < requiredBytes) fail(LocalCacheStorageCompactionFailure.INSUFFICIENT_FREE_SPACE)

        driver.execute(null, "VACUUM", 0).value
        driver.requireIntegrity()
        if (driver.longPragma("user_version") != schema) fail(LocalCacheStorageCompactionFailure.UNSUPPORTED_SCHEMA_VERSION)
        val checkpointed = driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
            QueryResult.Value(cursor.next().value && cursor.getLong(0) == 0L)
        }, 0).value
        if (!checkpointed) fail(LocalCacheStorageCompactionFailure.CHECKPOINT_INCOMPLETE)
        LocalCacheStorageCompactionReport(bytesBefore, familyBytes(), pagesBefore, driver.longPragma("page_count"),
            freePagesBefore, driver.longPragma("freelist_count"))
    } catch (failure: LocalCacheStorageCompactionException) {
        throw failure
    } catch (failure: Exception) {
        if (isFatalSessionLifecycleFailure(failure)) throw failure
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        causes.filterIsInstance<LocalCacheStorageCompactionException>().firstOrNull()?.let { throw it }
        if (causes.any { it::class.simpleName == "SQLiteDatabaseCorruptException" }) {
            fail(LocalCacheStorageCompactionFailure.INTEGRITY_CHECK_FAILED)
        }
        val busy = platformSqliteBusy(failure) ||
            failure::class.simpleName in setOf("SQLiteDatabaseLockedException", "SQLiteTableLockedException")
        fail(if (busy) LocalCacheStorageCompactionFailure.DATABASE_IN_USE else LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED)
    }

    private fun familyBytes(): Long {
        if (!databaseFile.isFile || databaseFile.isSymbolicLink()) fail(LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED)
        return (listOf(databaseFile.name) + localCacheDatabaseSidecars(databaseFile.name)).sumOf { name ->
            val file = File(databaseFile.parentFile, name)
            if (!file.exists()) 0L else {
                if (!file.isFile || file.isSymbolicLink()) fail(LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED)
                file.length()
            }
        }
    }

    private fun checkedPageBytes(pages: Long, size: Long): Long {
        check(pages >= 0 && size > 0 && pages <= Long.MAX_VALUE / size) { "Invalid SQLite page size" }
        return pages * size
    }

    private fun SqlDriver.longPragma(name: String): Long = executeQuery(null, "PRAGMA $name", { cursor ->
        check(cursor.next().value)
        QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }, 0).value

    private fun SqlDriver.requireIntegrity() {
        val healthy = executeQuery(null, "PRAGMA integrity_check(1)", { cursor ->
            val ok = cursor.next().value && cursor.getString(0) == "ok"
            QueryResult.Value(ok && !cursor.next().value)
        }, 0).value
        if (!healthy) fail(LocalCacheStorageCompactionFailure.INTEGRITY_CHECK_FAILED)
    }

    private fun fail(reason: LocalCacheStorageCompactionFailure): Nothing = throw LocalCacheStorageCompactionException(reason)

    private companion object {
        const val SPACE_RESERVE_BYTES = 16L * 1024 * 1024
    }
}

internal expect fun platformSqliteBusy(failure: Throwable): Boolean
