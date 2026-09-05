package com.virjar.tk.android

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 解析 Android 由文件系统支撑的媒体根目录，避免在 Compose Main 线程上触碰磁盘。 */
internal suspend fun resolveAndroidMediaCacheRoot(context: Context): File =
    resolveAndroidMediaCacheRoot { context.applicationContext.cacheDir }

/** 可注入的接缝，供 JVM 门控测试用来证明该提供者是在 IO 线程上求值的。 */
internal suspend fun resolveAndroidMediaCacheRoot(cacheRootProvider: () -> File): File =
    withContext(Dispatchers.IO) { cacheRootProvider() }
