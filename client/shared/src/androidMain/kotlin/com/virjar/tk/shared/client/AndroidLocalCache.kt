package com.virjar.tk.shared.client

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import java.io.File

fun createAndroidLocalCache(
    context: Context,
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    uid: String,
): LocalCache {
    val databaseName = localCacheDatabaseFileName(deploymentIdentity.fingerprint, datasetId, uid)
    val databaseFile = context.getDatabasePath(databaseName)
    val driver = openCheckedAndroidLocalCacheDriver(context, databaseName, databaseFile)
    return createLocalCacheWithOwnedDriver(driver)
}

/**
 * AndroidX 默认的 [SupportSQLiteOpenHelper.Callback.onCorruption] 会关闭并删除整个数据库族。本缓存同时还
 * 拥有仅存本地的可靠发件箱，因此删除绝不是可接受的恢复手段。该回调只记录失败而不触碰已打开的句柄；真正的
 * owner 是工厂，它会在隔离任何文件之前先关闭 driver。
 */
internal class NonDeletingAndroidLocalCacheCallback(
    private val corruptionMarker: File,
) : AndroidSqliteDriver.Callback(AppDatabase.Schema) {
    @Volatile
    var corruptionReported: Boolean = false
        private set

    override fun onCorruption(db: SupportSQLiteDatabase) {
        corruptionReported = true
        try {
            corruptionMarker.createNewFile()
        } catch (_: Exception) {
            // 内存中的报告仍然保护这次打开尝试。之后的启动也会运行 quick_check，因此无法持久化
            // 标记绝不能恢复 delete-on-corrupt 行为。
        }
    }
}
