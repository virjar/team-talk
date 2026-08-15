package com.virjar.tk.client

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.database.AppDatabase

fun createAndroidLocalCache(context: Context, uid: String): LocalCache {
    // schema 迁移由 SQLiteOpenHelper.onUpgrade 自动执行（.sqm 文件，user_version 比对）
    val driver = AndroidSqliteDriver(AppDatabase.Schema, context, "cache_$uid.db")
    return LocalCacheImpl(driver)
}
