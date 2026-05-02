package com.virjar.tk.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class AppDatabase actual constructor(uid: String) {
    private val driver: SqlDriver
    actual val queries: DatabaseQueries

    init {
        val dbName = "teamtalk_$uid.db"
        val isNew = !appContext.getDatabasePath(dbName).exists()
        driver = AndroidSqliteDriver(TeamTalkDatabase.Schema, appContext, dbName)
        if (isNew) {
            TeamTalkDatabase.Schema.create(driver)
        }
        queries = TeamTalkDatabase(driver).databaseQueries
    }

    actual fun close() {
        driver.close()
    }

    companion object {
        internal lateinit var appContext: Context

        fun init(context: Context) {
            appContext = context.applicationContext
        }
    }
}
