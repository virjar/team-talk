package com.virjar.tk.client

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.database.AppDatabase

fun createAndroidLocalCache(
    context: Context,
    deploymentIdentity: DeploymentIdentity,
    uid: String,
): LocalCache {
    val driver = AndroidSqliteDriver(
        AppDatabase.Schema,
        context,
        localCacheDatabaseFileName(deploymentIdentity.fingerprint, uid),
    )
    return LocalCacheImpl(driver)
}
