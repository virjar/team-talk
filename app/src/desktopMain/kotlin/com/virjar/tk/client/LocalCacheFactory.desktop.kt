package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.env.DesktopEnvironment
import java.io.File

fun createDesktopLocalCache(uid: String): LocalCache {
    val dataDir = DesktopEnvironment.dataDir
    val dir = File(dataDir, "users/$uid")
    if (!dir.exists()) dir.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dir.absolutePath}/cache.db")
    AppDatabase.Schema.create(driver)
    return LocalCacheImpl(driver)
}
