package com.virjar.tk.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.storage.resolveDataDir
import java.io.File

actual class AppDatabase actual constructor() {
    private val driver: SqlDriver
    actual val queries: DatabaseQueries

    init {
        val dataDir = resolveDataDir()
        dataDir.mkdirs()
        val dbFile = File(dataDir, "teamtalk.db")
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (isNew) {
            TeamTalkDatabase.Schema.create(driver)
        }
        queries = TeamTalkDatabase(driver).databaseQueries
    }

    actual fun close() {
        driver.close()
    }
}
