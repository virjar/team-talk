package com.virjar.tk.client

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.database.AppDatabase

fun createAndroidLocalCache(context: Context, uid: String): LocalCache {
    val driver = AndroidSqliteDriver(AppDatabase.Schema, context, localCacheDatabaseFileName(uid))
    return LocalCacheImpl(driver)
}
