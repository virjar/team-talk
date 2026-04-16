package com.virjar.tk.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class AppDatabase actual constructor() {
    private val driver: SqlDriver
    actual val queries: DatabaseQueries

    init {
        driver = AndroidSqliteDriver(TeamTalkDatabase.Schema, appContext, "teamtalk.db")
        queries = TeamTalkDatabase(driver).databaseQueries
    }

    actual fun close() {
        driver.close()
    }

    companion object {
        private lateinit var appContext: Context

        fun init(context: Context) {
            appContext = context.applicationContext
        }
    }
}
