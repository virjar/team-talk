package com.virjar.tk.util

import com.virjar.tk.database.AppDatabase
import java.io.File

actual fun resolveUserCacheDir(uid: String): File {
    return File(AppDatabase.appContext.cacheDir, "users/$uid")
}
