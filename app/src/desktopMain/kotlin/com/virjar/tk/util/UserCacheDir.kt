package com.virjar.tk.util

import com.virjar.tk.storage.resolveDataDir
import java.io.File

actual fun resolveUserCacheDir(uid: String): File {
    return File(resolveDataDir(), "users/$uid/cache")
}
